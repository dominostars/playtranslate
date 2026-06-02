#include <jni.h>
#include <cstring>
#include <map>
#include <string>
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

// Multi-input / multi-output forward pass for models the single-IO nativeRun
// can't drive (D-FINE Meiki det/rec: 2-in incl. int32 orig_target_sizes, 3-out
// incl. int32 char_codes; manga-ocr decoder: 2-in incl. int32 input_ids). Inputs
// are matched to session tensors BY NAME via getSessionInputAll; outputs returned
// by name via getSessionOutputAll (caller looks them up — order not guaranteed).
//
// Params (parallel arrays, length = #inputs):
//   jNames   String[]    input tensor names
//   jShapes  int[][]     NCHW shape per input
//   jDtypes  int[]       0 = float, 1 = int32
//   jData    Object[]    float[] (dtype 0) or int[] (dtype 1) per input
// Returns Object[]{ String[] names, int[] dtypes, Object[] shapes(int[]),
//   Object[] data(float[]|int[]) } over all session outputs, or null on failure.
extern "C"
JNIEXPORT jobjectArray JNICALL
Java_com_playtranslate_mnn_MnnInterpreter_nativeRunMulti(
        JNIEnv *env, jobject /*unused*/, jlong handle,
        jobjectArray jNames, jobjectArray jShapes, jintArray jDtypes, jobjectArray jData) {
    auto *model = reinterpret_cast<OcrModel *>(handle);
    if (model == nullptr || model->net == nullptr || model->session == nullptr) {
        LOGe("nativeRunMulti: invalid handle");
        return nullptr;
    }
    MNN::Interpreter *net = model->net;
    MNN::Session *session = model->session;

    const jsize nIn = env->GetArrayLength(jNames);
    jint *dtypes = env->GetIntArrayElements(jDtypes, nullptr);
    const std::map<std::string, MNN::Tensor *> inputs = net->getSessionInputAll(session);

    // ---- read names + shapes, resize each named input ----
    std::vector<std::string> names((size_t) nIn);
    for (jsize i = 0; i < nIn; ++i) {
        auto jn = (jstring) env->GetObjectArrayElement(jNames, i);
        const char *c = env->GetStringUTFChars(jn, nullptr);
        names[i] = c;
        env->ReleaseStringUTFChars(jn, c);
        env->DeleteLocalRef(jn);

        auto js = (jintArray) env->GetObjectArrayElement(jShapes, i);
        const jsize sl = env->GetArrayLength(js);
        jint *se = env->GetIntArrayElements(js, nullptr);
        std::vector<int> dims(se, se + sl);
        env->ReleaseIntArrayElements(js, se, JNI_ABORT);
        env->DeleteLocalRef(js);

        auto it = inputs.find(names[i]);
        if (it == inputs.end()) {
            LOGe("nativeRunMulti: no session input named '%s'", names[i].c_str());
            env->ReleaseIntArrayElements(jDtypes, dtypes, JNI_ABORT);
            return nullptr;
        }
        net->resizeTensor(it->second, dims);
    }
    net->resizeSession(session);

    // ---- fill each input via a typed host tensor ----
    for (jsize i = 0; i < nIn; ++i) {
        MNN::Tensor *t = inputs.at(names[i]);
        auto *host = new MNN::Tensor(t, t->getDimensionType());
        const int count = host->elementSize();
        auto jd = env->GetObjectArrayElement(jData, i);
        if (dtypes[i] == 1) {
            auto ja = (jintArray) jd;
            if (env->GetArrayLength(ja) != count) {
                LOGe("nativeRunMulti: int input '%s' len mismatch", names[i].c_str());
                delete host; env->DeleteLocalRef(jd);
                env->ReleaseIntArrayElements(jDtypes, dtypes, JNI_ABORT);
                return nullptr;
            }
            jint *p = env->GetIntArrayElements(ja, nullptr);
            memcpy(host->host<int32_t>(), p, (size_t) count * sizeof(int32_t));
            env->ReleaseIntArrayElements(ja, p, JNI_ABORT);
        } else {
            auto ja = (jfloatArray) jd;
            if (env->GetArrayLength(ja) != count) {
                LOGe("nativeRunMulti: float input '%s' len mismatch", names[i].c_str());
                delete host; env->DeleteLocalRef(jd);
                env->ReleaseIntArrayElements(jDtypes, dtypes, JNI_ABORT);
                return nullptr;
            }
            jfloat *p = env->GetFloatArrayElements(ja, nullptr);
            memcpy(host->host<float>(), p, (size_t) count * sizeof(float));
            env->ReleaseFloatArrayElements(ja, p, JNI_ABORT);
        }
        t->copyFromHostTensor(host);
        delete host;
        env->DeleteLocalRef(jd);
    }
    env->ReleaseIntArrayElements(jDtypes, dtypes, JNI_ABORT);

    // ---- run ----
    if (net->runSession(session) != MNN::NO_ERROR) {
        LOGe("nativeRunMulti: runSession failed");
        return nullptr;
    }

    // ---- collect all outputs by name ----
    const std::map<std::string, MNN::Tensor *> outputs = net->getSessionOutputAll(session);
    const jsize nOut = (jsize) outputs.size();
    jclass strCls = env->FindClass("java/lang/String");
    jclass objCls = env->FindClass("java/lang/Object");
    jobjectArray outNames = env->NewObjectArray(nOut, strCls, nullptr);
    jintArray outDtypes = env->NewIntArray(nOut);
    jobjectArray outShapes = env->NewObjectArray(nOut, objCls, nullptr);
    jobjectArray outData = env->NewObjectArray(nOut, objCls, nullptr);
    std::vector<jint> dtypeBuf((size_t) nOut);

    jsize oi = 0;
    for (const auto &kv : outputs) {
        MNN::Tensor *t = kv.second;
        auto *host = new MNN::Tensor(t, t->getDimensionType());
        t->copyToHostTensor(host);
        const int count = host->elementSize();
        const std::vector<int> shp = host->shape();

        jstring jn = env->NewStringUTF(kv.first.c_str());
        env->SetObjectArrayElement(outNames, oi, jn);
        env->DeleteLocalRef(jn);

        jintArray jshp = env->NewIntArray((jsize) shp.size());
        { std::vector<jint> tmp(shp.begin(), shp.end());
          env->SetIntArrayRegion(jshp, 0, (jsize) tmp.size(), tmp.data()); }
        env->SetObjectArrayElement(outShapes, oi, jshp);
        env->DeleteLocalRef(jshp);

        const bool isFloat = (host->getType().code == halide_type_float);
        dtypeBuf[oi] = isFloat ? 0 : 1;
        if (isFloat) {
            jfloatArray jd = env->NewFloatArray(count);
            env->SetFloatArrayRegion(jd, 0, count, host->host<float>());
            env->SetObjectArrayElement(outData, oi, jd);
            env->DeleteLocalRef(jd);
        } else {
            jintArray jd = env->NewIntArray(count);
            env->SetIntArrayRegion(jd, 0, count, (const jint *) host->host<int32_t>());
            env->SetObjectArrayElement(outData, oi, jd);
            env->DeleteLocalRef(jd);
        }
        delete host;
        ++oi;
    }
    env->SetIntArrayRegion(outDtypes, 0, nOut, dtypeBuf.data());

    jobjectArray result = env->NewObjectArray(4, objCls, nullptr);
    env->SetObjectArrayElement(result, 0, outNames);
    env->SetObjectArrayElement(result, 1, outDtypes);
    env->SetObjectArrayElement(result, 2, outShapes);
    env->SetObjectArrayElement(result, 3, outData);
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
