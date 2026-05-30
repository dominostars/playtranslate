// JNI bridge to slimt (Bergamot) — built with the gemmology int8 backend, which
// is correct on ARM (avoids the ruy garbage-output bug,
// DavidVentura/offline-translator#185). Mirrors slimt's own bindings/java/slimt.cpp
// but in the com.playtranslate.bergamot namespace and adds a single-text translate
// + a native pivot entry point.
//
// Handles are raw native pointers passed as jlong. The engine is single-threaded;
// the Kotlin side (BergamotTranslator) serializes all access. Every entry point is
// fail-soft: returns 0 / nullptr on error and logs to logcat.
#include "slimt/slimt.hh"

#include <android/log.h>
#include <jni.h>

#include <string>
#include <vector>

using slimt::Blocking;
using slimt::Config;
using slimt::Model;
using slimt::Options;
using slimt::Package;
using slimt::Ptr;
using slimt::Responses;

#define LOG_TAG "bergamot_jni"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

std::string jstr(JNIEnv* env, jstring s) {
  if (s == nullptr) return std::string();
  const char* c = env->GetStringUTFChars(s, nullptr);
  std::string out = (c != nullptr) ? std::string(c) : std::string();
  if (c != nullptr) env->ReleaseStringUTFChars(s, c);
  return out;
}

// One text through one model (single hop) or two (English pivot). Returns the
// single response's target text, or "" on empty/failure.
std::string run(Blocking* service, Model* first, Model* second,
                const std::string& text) {
  try {
    std::vector<std::string> sources{text};
    Options options{};
    options.html = false;
    Ptr<Model> m1(first, [](Model*) {});
    Responses responses;
    if (second == nullptr) {
      responses = service->translate(m1, std::move(sources), options);
    } else {
      Ptr<Model> m2(second, [](Model*) {});
      responses = service->pivot(m1, m2, std::move(sources), options);
    }
    if (responses.empty()) return std::string();
    return responses.front().target.text;
  } catch (const std::exception& e) {
    LOGE("translate failed: %s", e.what());
    return std::string();
  } catch (...) {
    LOGE("translate failed: unknown native error");
    return std::string();
  }
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_playtranslate_bergamot_BergamotNative_createService(JNIEnv*, jobject,
                                                             jlong cache_size) {
  try {
    Config config;
    config.cache_size = static_cast<size_t>(cache_size);
    return reinterpret_cast<jlong>(new Blocking(config));
  } catch (const std::exception& e) {
    LOGE("createService failed: %s", e.what());
    return 0;
  }
}

JNIEXPORT void JNICALL
Java_com_playtranslate_bergamot_BergamotNative_destroyService(JNIEnv*, jobject,
                                                              jlong handle) {
  delete reinterpret_cast<Blocking*>(handle);
}

JNIEXPORT jlong JNICALL
Java_com_playtranslate_bergamot_BergamotNative_loadModel(
    JNIEnv* env, jobject, jstring model_path, jstring vocab_path,
    jstring shortlist_path, jint encoder_layers, jint decoder_layers,
    jint feed_forward_depth, jint num_heads, jstring split_mode) {
  try {
    Model::Config config;
    config.encoder_layers = static_cast<size_t>(encoder_layers);
    config.decoder_layers = static_cast<size_t>(decoder_layers);
    config.feed_forward_depth = static_cast<size_t>(feed_forward_depth);
    config.num_heads = static_cast<size_t>(num_heads);
    std::string sm = jstr(env, split_mode);
    if (!sm.empty()) config.split_mode = sm;

    Package<std::string> package;
    package.model = jstr(env, model_path);
    package.vocabulary = jstr(env, vocab_path);
    package.shortlist = jstr(env, shortlist_path);
    package.ssplit = std::string();

    return reinterpret_cast<jlong>(new Model(config, package));
  } catch (const std::exception& e) {
    LOGE("loadModel failed: %s", e.what());
    return 0;
  } catch (...) {
    LOGE("loadModel failed: unknown native error");
    return 0;
  }
}

JNIEXPORT void JNICALL
Java_com_playtranslate_bergamot_BergamotNative_destroyModel(JNIEnv*, jobject,
                                                            jlong handle) {
  delete reinterpret_cast<Model*>(handle);
}

JNIEXPORT jstring JNICALL
Java_com_playtranslate_bergamot_BergamotNative_translate(
    JNIEnv* env, jobject, jlong service_handle, jlong model_handle,
    jstring text) {
  auto* service = reinterpret_cast<Blocking*>(service_handle);
  auto* model = reinterpret_cast<Model*>(model_handle);
  if (service == nullptr || model == nullptr) return nullptr;
  std::string out = run(service, model, nullptr, jstr(env, text));
  return env->NewStringUTF(out.c_str());
}

JNIEXPORT jstring JNICALL
Java_com_playtranslate_bergamot_BergamotNative_pivot(
    JNIEnv* env, jobject, jlong service_handle, jlong first_handle,
    jlong second_handle, jstring text) {
  auto* service = reinterpret_cast<Blocking*>(service_handle);
  auto* first = reinterpret_cast<Model*>(first_handle);
  auto* second = reinterpret_cast<Model*>(second_handle);
  if (service == nullptr || first == nullptr || second == nullptr)
    return nullptr;
  std::string out = run(service, first, second, jstr(env, text));
  return env->NewStringUTF(out.c_str());
}

}  // extern "C"
