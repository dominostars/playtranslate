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

#include <cstdint>
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

// NOTE: jstr() above uses GetStringUTFChars, which yields JNI *modified* UTF-8.
// That's fine for the ASCII filesystem paths passed to loadModel, but NOT for
// user text: modified UTF-8 encodes supplementary code points (emoji, rare CJK,
// U+10000+) as 6-byte CESU-8 surrogate pairs and NUL as 0xC0 0x80, while slimt
// expects standard UTF-8 (4-byte supplementary, 1-byte NUL). translate/pivot
// convert the source/target text explicitly via the two helpers below so real
// user text round-trips losslessly instead of being mangled or rejected.

// Java String (UTF-16) -> standard UTF-8. Combines surrogate pairs into 4-byte
// sequences; lone/invalid surrogates become U+FFFD.
std::string jstringToUtf8(JNIEnv* env, jstring s) {
  if (s == nullptr) return std::string();
  const jsize len = env->GetStringLength(s);
  const jchar* u = env->GetStringChars(s, nullptr);
  if (u == nullptr) return std::string();
  std::string out;
  out.reserve(static_cast<size_t>(len) + static_cast<size_t>(len) / 2 + 1);
  for (jsize i = 0; i < len; ++i) {
    uint32_t cp = u[i];
    if (cp >= 0xD800u && cp <= 0xDBFFu) {  // high surrogate
      const uint32_t lo = (i + 1 < len) ? static_cast<uint32_t>(u[i + 1]) : 0u;
      if (lo >= 0xDC00u && lo <= 0xDFFFu) {
        cp = 0x10000u + ((cp - 0xD800u) << 10) + (lo - 0xDC00u);
        ++i;
      } else {
        cp = 0xFFFDu;  // unpaired high surrogate
      }
    } else if (cp >= 0xDC00u && cp <= 0xDFFFu) {  // unpaired low surrogate
      cp = 0xFFFDu;
    }
    if (cp < 0x80u) {
      out.push_back(static_cast<char>(cp));
    } else if (cp < 0x800u) {
      out.push_back(static_cast<char>(0xC0u | (cp >> 6)));
      out.push_back(static_cast<char>(0x80u | (cp & 0x3Fu)));
    } else if (cp < 0x10000u) {
      out.push_back(static_cast<char>(0xE0u | (cp >> 12)));
      out.push_back(static_cast<char>(0x80u | ((cp >> 6) & 0x3Fu)));
      out.push_back(static_cast<char>(0x80u | (cp & 0x3Fu)));
    } else {
      out.push_back(static_cast<char>(0xF0u | (cp >> 18)));
      out.push_back(static_cast<char>(0x80u | ((cp >> 12) & 0x3Fu)));
      out.push_back(static_cast<char>(0x80u | ((cp >> 6) & 0x3Fu)));
      out.push_back(static_cast<char>(0x80u | (cp & 0x3Fu)));
    }
  }
  env->ReleaseStringChars(s, u);
  return out;
}

// Standard UTF-8 -> Java String. Decodes to UTF-16 (supplementary -> surrogate
// pair) and uses NewString, NOT NewStringUTF (which expects modified UTF-8 and
// mangles slimt's 4-byte output). Invalid/truncated sequences become U+FFFD.
jstring utf8ToJstring(JNIEnv* env, const std::string& s) {
  std::vector<jchar> u;
  u.reserve(s.size() + 1);
  const size_t n = s.size();
  size_t i = 0;
  while (i < n) {
    const uint8_t c0 = static_cast<uint8_t>(s[i]);
    uint32_t cp;
    size_t extra;
    if (c0 < 0x80u) { cp = c0; extra = 0; }
    else if ((c0 & 0xE0u) == 0xC0u) { cp = c0 & 0x1Fu; extra = 1; }
    else if ((c0 & 0xF0u) == 0xE0u) { cp = c0 & 0x0Fu; extra = 2; }
    else if ((c0 & 0xF8u) == 0xF0u) { cp = c0 & 0x07u; extra = 3; }
    else { cp = 0xFFFDu; extra = 0; }  // invalid lead byte
    bool ok = (i + extra < n);
    for (size_t k = 0; ok && k < extra; ++k) {
      const uint8_t cc = static_cast<uint8_t>(s[i + 1 + k]);
      if ((cc & 0xC0u) != 0x80u) { ok = false; break; }
      cp = (cp << 6) | (cc & 0x3Fu);
    }
    if (!ok) { cp = 0xFFFDu; i += 1; }  // truncated/invalid: replace, resync 1 byte
    else { i += 1 + extra; }
    if (cp <= 0xFFFFu) {
      u.push_back(static_cast<jchar>(cp));
    } else if (cp <= 0x10FFFFu) {
      cp -= 0x10000u;
      u.push_back(static_cast<jchar>(0xD800u | (cp >> 10)));
      u.push_back(static_cast<jchar>(0xDC00u | (cp & 0x3FFu)));
    } else {
      u.push_back(static_cast<jchar>(0xFFFDu));
    }
  }
  return env->NewString(u.data(), static_cast<jsize>(u.size()));
}

// One text through one model (single hop) or two (English pivot). On success
// writes the target text to `out` (which may legitimately be empty for empty
// input) and returns true. Returns false — distinct from a valid empty result
// — on a native exception or empty Responses, so the JNI can surface failure as
// a null jstring and the Kotlin waterfall falls back to the next backend instead
// of caching/displaying a blank translation as if Bergamot had succeeded.
bool run(Blocking* service, Model* first, Model* second,
         const std::string& text, std::string& out) {
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
    if (responses.empty()) return false;
    out = responses.front().target.text;
    return true;
  } catch (const std::exception& e) {
    LOGE("translate failed: %s", e.what());
    return false;
  } catch (...) {
    LOGE("translate failed: unknown native error");
    return false;
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
    jstring target_vocab_path, jstring shortlist_path, jint encoder_layers,
    jint decoder_layers, jint feed_forward_depth, jint num_heads,
    jstring split_mode) {
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
    // Split-vocab (en->CJK) models pass a distinct target vocab; "" => single-vocab.
    package.target_vocabulary = jstr(env, target_vocab_path);
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
  std::string out;
  if (!run(service, model, nullptr, jstringToUtf8(env, text), out)) return nullptr;
  return utf8ToJstring(env, out);
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
  std::string out;
  if (!run(service, first, second, jstringToUtf8(env, text), out)) return nullptr;
  return utf8ToJstring(env, out);
}

}  // extern "C"
