#include <jni.h>
#include <cstring>
#include <vector>

#include "logging.h"
#include <MNN/Interpreter.hpp>
#include <MNN/Tensor.hpp>
#include <MNN/MNNForwardType.h>

// ----------------------------------------------------------------------------
// General-purpose MNN inference shim — the non-LLM counterpart to mnn_chat.cpp.
//
// mnn_chat.cpp drives MNN's high-level `Transformer::Llm` API (autoregressive
// text generation). This file exposes MNN's *general* Session API
// (`Interpreter → createSession → runSession`, operating on raw Tensors) for
// fixed-graph CNN/CRNN models — specifically the PaddleOCR PP-OCRv5 detector
// and recognizer used by the OCR spike (docs/paddleocr-spike-scope.md).
//
// Deliberately dumb: it loads a model, runs one float input through it, and
// returns the float output + shape. ALL OCR logic (image preprocessing, DBNet
// postprocessing, CTC decode) lives in Kotlin — this layer knows nothing about
// OCR. Single-input / single-output only (`getSessionInput/Output(.., nullptr)`
// returns the sole IO tensor), which is all PP-OCRv5 det and rec need.
//
// Handle model: each loaded model is one `OcrModel` (Interpreter + Session),
// returned to Kotlin as an opaque jlong. `MnnInterpreter` (Kotlin) owns the
// lifecycle and serializes calls — a single Session is NOT safe for concurrent
// runSession, so the Kotlin side must not call run() re-entrantly on one handle.
//
// Links against the same libMNN.so / libMNN_Express.so already pulled in by the
// :mnn module for the LLM path; no new native dependency. Compiled into the
// existing `mnn-chat` shared library (see CMakeLists.txt).
// ----------------------------------------------------------------------------

namespace {

struct OcrModel {
    MNN::Interpreter *net = nullptr;
    MNN::Session *session = nullptr;
};

// Map the Kotlin precision flag to MNN's BackendConfig precision mode.
//   0 = Normal (fp32-ish, the spike's baseline)
//   1 = High   (force fp32)
//   2 = Low    (fp16 on ARM — typical mobile deployment, faster)
MNN::BackendConfig::PrecisionMode precisionFromFlag(jint flag) {
    switch (flag) {
        case 1:  return MNN::BackendConfig::Precision_High;
        case 2:  return MNN::BackendConfig::Precision_Low;
        default: return MNN::BackendConfig::Precision_Normal;
    }
}

} // namespace

extern "C"
JNIEXPORT jlong JNICALL
Java_com_playtranslate_mnn_MnnInterpreter_nativeCreate(
        JNIEnv *env, jobject /*unused*/, jstring jpath, jint numThread, jint precision) {
    const char *path = env->GetStringUTFChars(jpath, nullptr);
    MNN::Interpreter *net = MNN::Interpreter::createFromFile(path);
    LOGi("MnnInterpreter: createFromFile %s -> %p", path, (void *) net);
    env->ReleaseStringUTFChars(jpath, path);
    if (net == nullptr) {
        LOGe("MnnInterpreter: createFromFile returned null");
        return 0;
    }

    MNN::ScheduleConfig config;
    config.type = MNN_FORWARD_CPU;                 // OpenCL is OFF in this build (see :mnn CMake)
    config.numThread = numThread > 0 ? numThread : 4;
    MNN::BackendConfig backendConfig;
    backendConfig.precision = precisionFromFlag(precision);
    backendConfig.power = MNN::BackendConfig::Power_Normal;
    config.backendConfig = &backendConfig;

    MNN::Session *session = net->createSession(config);
    if (session == nullptr) {
        LOGe("MnnInterpreter: createSession returned null");
        MNN::Interpreter::destroy(net);
        return 0;
    }

    auto *model = new OcrModel{net, session};
    return reinterpret_cast<jlong>(model);
}

// Runs one forward pass. `jinput` is the input data as a flat float[] in NCHW
// order; `jshape` is the matching int[] (e.g. {1,3,H,W} for det, {1,3,48,W} for
// a rec crop). Returns Object[]{ float[] outputData, int[] outputShape }, or
// null on any failure (the Kotlin side throws).
extern "C"
JNIEXPORT jobjectArray JNICALL
Java_com_playtranslate_mnn_MnnInterpreter_nativeRun(
        JNIEnv *env, jobject /*unused*/, jlong handle, jfloatArray jinput, jintArray jshape) {
    auto *model = reinterpret_cast<OcrModel *>(handle);
    if (model == nullptr || model->net == nullptr || model->session == nullptr) {
        LOGe("nativeRun: invalid handle");
        return nullptr;
    }
    MNN::Interpreter *net = model->net;
    MNN::Session *session = model->session;

    // ---- read shape + input from Java ----
    const jsize shapeLen = env->GetArrayLength(jshape);
    const jsize inputLen = env->GetArrayLength(jinput);
    std::vector<int> dims(shapeLen);
    {
        jint *shapeElems = env->GetIntArrayElements(jshape, nullptr);
        long expected = shapeLen > 0 ? 1 : 0;
        for (jsize i = 0; i < shapeLen; ++i) {
            dims[i] = shapeElems[i];
            expected *= shapeElems[i];
        }
        env->ReleaseIntArrayElements(jshape, shapeElems, JNI_ABORT);
        if (expected != inputLen) {
            LOGe("nativeRun: shape product %ld != input length %d", expected, (int) inputLen);
            return nullptr;
        }
    }

    // ---- resize to the requested dynamic shape ----
    MNN::Tensor *input = net->getSessionInput(session, nullptr);
    if (input == nullptr) {
        LOGe("nativeRun: getSessionInput returned null");
        return nullptr;
    }
    net->resizeTensor(input, dims);
    net->resizeSession(session);

    // ---- copy input via a host tensor (handles NCHW -> internal NC4HW4) ----
    {
        auto *hostInput = new MNN::Tensor(input, MNN::Tensor::CAFFE);  // CAFFE = NCHW
        if (hostInput->elementSize() != (int) inputLen) {
            LOGe("nativeRun: host input elementSize %d != input length %d",
                 hostInput->elementSize(), (int) inputLen);
            delete hostInput;
            return nullptr;
        }
        jfloat *src = env->GetFloatArrayElements(jinput, nullptr);
        memcpy(hostInput->host<float>(), src, (size_t) inputLen * sizeof(float));
        env->ReleaseFloatArrayElements(jinput, src, JNI_ABORT);
        input->copyFromHostTensor(hostInput);
        delete hostInput;
    }

    // ---- run ----
    MNN::ErrorCode code = net->runSession(session);
    if (code != MNN::NO_ERROR) {
        LOGe("nativeRun: runSession failed code=%d", (int) code);
        return nullptr;
    }

    // ---- copy output out via a host tensor ----
    MNN::Tensor *output = net->getSessionOutput(session, nullptr);
    if (output == nullptr) {
        LOGe("nativeRun: getSessionOutput returned null");
        return nullptr;
    }
    auto *hostOutput = new MNN::Tensor(output, MNN::Tensor::CAFFE);
    output->copyToHostTensor(hostOutput);

    const int outCount = hostOutput->elementSize();
    std::vector<int> outShape = hostOutput->shape();

    jfloatArray outData = env->NewFloatArray(outCount);
    env->SetFloatArrayRegion(outData, 0, outCount, hostOutput->host<float>());

    jintArray outShapeArr = env->NewIntArray((jsize) outShape.size());
    {
        std::vector<jint> tmp(outShape.begin(), outShape.end());
        env->SetIntArrayRegion(outShapeArr, 0, (jsize) tmp.size(), tmp.data());
    }
    delete hostOutput;

    // ---- pack {float[] data, int[] shape} ----
    jclass objClass = env->FindClass("java/lang/Object");
    jobjectArray result = env->NewObjectArray(2, objClass, nullptr);
    env->SetObjectArrayElement(result, 0, outData);
    env->SetObjectArrayElement(result, 1, outShapeArr);
    return result;
}

extern "C"
JNIEXPORT void JNICALL
Java_com_playtranslate_mnn_MnnInterpreter_nativeDestroy(
        JNIEnv * /*env*/, jobject /*unused*/, jlong handle) {
    auto *model = reinterpret_cast<OcrModel *>(handle);
    if (model == nullptr) return;
    if (model->net != nullptr) {
        // Interpreter::destroy frees the net and all its sessions.
        MNN::Interpreter::destroy(model->net);
    }
    delete model;
    LOGi("MnnInterpreter: destroyed handle %p", (void *) model);
}
