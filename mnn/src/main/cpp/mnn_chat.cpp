#include <jni.h>
#include <android/log.h>
#include <string>
#include <sstream>
#include <memory>

#include "logging.h"
#include "llm/llm.hpp"

using MNN::Transformer::Llm;
using MNN::Transformer::LlmContext;
using MNN::Transformer::LlmStatus;

// Process-wide singleton state. Mirrors the global pattern :llama/ai_chat.cpp
// uses for llama.cpp's `g_model / g_context / g_batch`; the Kotlin side
// (`MnnChatImpl`) is a process-singleton too and serializes all JNI calls
// through `mnnDispatcher`, so cross-thread access here is not a concern.
//
// `g_llm` owns the underlying engine; resetting it triggers `Llm`'s
// destructor which frees the loaded model + KV cache. `g_system_prompt_position`
// is the post-prefill KV index recorded by `processSystemPrompt`; the
// `nativeResetForNextPrompt` path rewinds to this boundary via
// `Llm::eraseHistory(begin, end)`, the MNN equivalent of llama.cpp's
// `llama_memory_seq_rm`. Spike: mnn-spike/SPIKE_REPORT.md.
static std::unique_ptr<Llm> g_llm;
static size_t g_system_prompt_position = 0;

namespace {
// Escape a string for safe embedding as a JSON string value. Android file
// paths under noBackupFilesDir never contain quotes/backslashes in practice,
// but we escape defensively so the dynamically-built runtime config handed to
// Llm::set_config() can never be malformed JSON (the path is the only
// runtime-variable substring).
std::string jsonEscapeString(const std::string &s) {
    std::string out;
    out.reserve(s.size() + 8);
    for (char c : s) {
        if (c == '"' || c == '\\') out.push_back('\\');
        out.push_back(c);
    }
    return out;
}
} // namespace

// ----------------------------------------------------------------------------
// Lifecycle
// ----------------------------------------------------------------------------

extern "C"
JNIEXPORT void JNICALL
Java_com_playtranslate_mnn_internal_MnnChatImpl_init(
        JNIEnv * /*env*/, jobject /*unused*/, jstring /*nativeLibDir*/) {
    // MNN built with MNN_USE_LOGCAT=ON wires `MNN_PRINT` straight to
    // __android_log_print under MNN's own tag. Unlike llama.cpp (which needs
    // `ggml_backend_load_all_from_path` to dlopen the CPU variant .so's),
    // MNN's backends are in-process — nothing to load here.
    LOGi("MNN JNI initialized");
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_playtranslate_mnn_internal_MnnChatImpl_load(
        JNIEnv *env, jobject /*unused*/, jstring jconfig_path) {
    const char *config_path = env->GetStringUTFChars(jconfig_path, nullptr);
    LOGi("Llm::createLLM from %s", config_path);
    Llm *llm = Llm::createLLM(std::string(config_path));
    env->ReleaseStringUTFChars(jconfig_path, config_path);
    if (!llm) {
        LOGe("Llm::createLLM returned null");
        return 1;
    }
    g_llm.reset(llm);
    g_system_prompt_position = 0;
    return 0;
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_playtranslate_mnn_internal_MnnChatImpl_prepare(
        JNIEnv *env, jobject /*unused*/, jstring jmmap_dir) {
    if (!g_llm) {
        LOGe("prepare(): no llm loaded");
        return 1;
    }
    // Per-model mmap weight-cache dir supplied by MnnChatImpl.loadModel.
    // Empty string = mmap disabled (the legacy anonymous-weights path).
    const char *mmap_dir_c = env->GetStringUTFChars(jmmap_dir, nullptr);
    std::string mmap_dir(mmap_dir_c ? mmap_dir_c : "");
    if (mmap_dir_c) env->ReleaseStringUTFChars(jmmap_dir, mmap_dir_c);
    // KV reuse + raw prompt feed + greedy sampling.
    //
    // reuse_kv=true: `generate_init` won't wipe the KV between calls, so
    //   `eraseHistory(sys_pos, current)` in `nativeResetForNextPrompt` actually
    //   preserves the system-prompt prefill across translations.
    //
    // use_template=false: skip MNN's `apply_chat_template`. The taobao-mnn
    //   Qwen2.5-1.5B ships without a jinja `chat_template`; the fallback in
    //   `Tokenizer::apply_chat_template` bare-concatenates message contents and
    //   strips role markers (`<|im_start|>` etc.) — degenerate output. We build
    //   the full envelope in Kotlin (`QwenChatTemplate.systemBlock` /
    //   `userBlock` add `<|im_start|>system\n…<|im_end|>\n` /
    //   `<|im_start|>user\n…<|im_end|>\n<|im_start|>assistant\n`) and feed the
    //   engine the raw token stream via `Llm::response(string, ...)`.
    //
    // sampler_type=greedy: deterministic max-likelihood selection. Matches
    //   :llama's `DEFAULT_SAMPLER_TEMP=0.0f` in ai_chat.cpp and the spike's
    //   `config_reuse.json` recipe. MNN's default sampler is temperature/top-p
    //   which adds non-determinism the translation pipeline doesn't want and
    //   runs slower per token.
    //
    // Each shipped model's `config.json` typically carries these values, but
    // the canonical source for some entries (e.g. the Hunyuan-MT 1.5 bundle
    // fetched directly from wangjazz/Hunyuan-MT1.5-1.8B-MNN via the
    // MultiFile downloader strategy) is upstream, not us — so the local
    // config.json reflects upstream choices, not ours. set_config() here is
    // a belt-and-suspenders override that pins our preferred runtime config
    // regardless of what the upstream `config.json` happens to contain.
    //
    // MUST run BEFORE load(): MNN constructs the sampler during load() from
    // whatever config is set then, so setting `sampler_type` afterwards
    // leaves the default mixed/temperature sampler in place. reuse_kv /
    // use_template are read per-call and tolerate either order, but pinning
    // all three pre-load keeps the contract uniform.
    // When an mmap dir is supplied, add `use_mmap` + `tmp_path` so MNN maps the
    // (rearranged) weights from disk as reclaimable file-backed pages instead of
    // holding them in anonymous RAM — the kernel can then page them out under
    // pressure instead of OOM-killing us.
    //
    // `mmap_size: 4096` (~4 GiB) is load-bearing, not a tuning knob. The default
    // 1024 (1 GiB) splits the ~3 GB model across 3 `.static` chunks, and the warm
    // restore then SIGSEGV'd (SEGV_ACCERR) in createExecutionWithExternal reading
    // an external-weight offset across a chunk boundary. A single chunk large
    // enough to hold the whole model removes the boundary entirely — a structural
    // fix, not a device-specific workaround. Validated cold→warm with no crash
    // across Gemma E2B (multimodal Omni), Hunyuan-MT and Qwen 3.5 (text-only) on
    // an SD 8 Gen 2; warm reloads run ~0.2–0.3 s vs ~3–4 s cold. `use_cached_mmap:
    // true` keeps the rearranged cache on disk so warm reloads reuse it;
    // `kvcache_mmap` stays false (KV cache in RAM).
    //
    // Caveat: a native warm-restore crash can't fall through in-process, and the
    // cache persists across launches, so a regression would re-crash on the next
    // launch's first load too. Treated as structural (single-device validation
    // above) — re-validate warm loads on new SoC / precision classes before a
    // wide rollout.
    std::string runtime_config =
        R"({"reuse_kv": true, "use_template": false, "sampler_type": "greedy")";
    if (!mmap_dir.empty()) {
        runtime_config += R"(, "use_mmap": true, "use_cached_mmap": true, "mmap_size": 4096, "tmp_path": ")" + jsonEscapeString(mmap_dir) + R"(")";
    }
    runtime_config += "}";
    if (!g_llm->set_config(runtime_config)) {
        if (!mmap_dir.empty()) {
            // mmap was requested because the device is below the resident-memory
            // floor, where the caller deliberately avoids the anonymous-weights
            // load (it can OOM-kill us). If set_config didn't apply, use_mmap /
            // tmp_path never took effect and load() would silently load anon —
            // exactly that OOM path, with no chance to fall through. Fail instead,
            // so MnnChatImpl throws and the waterfall drops to a lighter backend.
            LOGe("Llm::set_config failed with mmap requested; refusing anonymous fallback");
            return 3;
        }
        LOGw("Llm::set_config failed; relying on bundled config.json values");
    }
    LOGi("Llm::load() (weights + module init), mmap=%s", mmap_dir.empty() ? "off" : "on");
    if (!g_llm->load()) {
        LOGe("Llm::load() returned false");
        return 2;
    }
    return 0;
}

extern "C"
JNIEXPORT void JNICALL
Java_com_playtranslate_mnn_internal_MnnChatImpl_unload(
        JNIEnv * /*env*/, jobject /*unused*/) {
    if (g_llm) {
        LOGi("Llm unload (destructor)");
        g_llm.reset();
        g_system_prompt_position = 0;
    }
}

extern "C"
JNIEXPORT void JNICALL
Java_com_playtranslate_mnn_internal_MnnChatImpl_shutdown(
        JNIEnv * /*env*/, jobject /*unused*/) {
    // MNN has no global backend-free analog to llama_backend_free(); per-Llm
    // destruction (via the unique_ptr's reset in unload()) covers everything.
    // Kept symmetric with :llama's `shutdown` JNI entry so the Kotlin facade
    // can mirror the lifecycle methods 1:1.
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_playtranslate_mnn_internal_MnnChatImpl_systemInfo(
        JNIEnv *env, jobject /*unused*/) {
    return env->NewStringUTF("MNN (Module API, Express runtime)");
}

// ----------------------------------------------------------------------------
// Prompt processing
// ----------------------------------------------------------------------------

extern "C"
JNIEXPORT jint JNICALL
Java_com_playtranslate_mnn_internal_MnnChatImpl_processSystemPrompt(
        JNIEnv *env, jobject /*unused*/, jstring jsystem_prompt) {
    if (!g_llm) {
        LOGe("processSystemPrompt: no llm loaded");
        return 1;
    }
    const char *sys = env->GetStringUTFChars(jsystem_prompt, nullptr);
    std::string sys_str(sys);
    env->ReleaseStringUTFChars(jsystem_prompt, sys);

    // Prefill only — max_new_tokens=0 stops generation immediately after the
    // forward pass ingests the prompt tokens. The post-prefill KV position is
    // recorded as the rewind boundary for subsequent `eraseHistory` calls
    // (analogous to llama.cpp's `system_prompt_position`).
    std::ostringstream sink;
    g_llm->response(sys_str, &sink, nullptr, /*max_new_tokens=*/0);

    g_system_prompt_position = g_llm->getCurrentHistory();
    // Snapshot the linear-attention (gated delta rule) recurrent state at this
    // system boundary so each resetForNextPrompt's eraseHistory rewinds the SSM
    // state here too — eraseHistory only rewinds the positional KV cache, which
    // is enough for full-attention models but leaves a mixed-attention model
    // (Qwen 3.5) polluted across turns. No-op for non-linear models (Qwen 2.5 /
    // Gemma / HyMt): no snapshot buffers are ever allocated.
    g_llm->snapshotLinearState();
    LOGi("processSystemPrompt: prefill complete, history=%zu", g_system_prompt_position);

    // Surface status failures to the caller. RUNNING and *_FINISHED are both
    // benign here (we passed max_new_tokens=0 so the engine immediately
    // transitions to MAX_TOKENS_FINISHED after prefill).
    const LlmContext *ctx = g_llm->getContext();
    if (ctx && ctx->status == LlmStatus::INTERNAL_ERROR) {
        LOGe("processSystemPrompt: INTERNAL_ERROR after prefill");
        return 2;
    }
    return 0;
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_playtranslate_mnn_internal_MnnChatImpl_nativeProcessRawPrefix(
        JNIEnv *env, jobject /*unused*/, jstring jprefix) {
    // With use_template:false the engine accepts pre-formatted text directly.
    // For MNN we don't need a separate `processRawPrefix` vs.
    // `processSystemPrompt` code path (llama.cpp needed it because its chat
    // template was layered on top of `setSystemPrompt`); both routes do the
    // same thing here. Kept for InferenceEngine parity with :llama so the
    // Gemma3Prefix path can compile, even though the migration plan currently
    // only routes Qwen-MNN through the StandardChat path (gemma-3 on MNN is
    // blocked by alibaba/MNN#4463).
    return Java_com_playtranslate_mnn_internal_MnnChatImpl_processSystemPrompt(env, nullptr, jprefix);
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_playtranslate_mnn_internal_MnnChatImpl_nativeResetForNextPrompt(
        JNIEnv * /*env*/, jobject /*unused*/) {
    if (!g_llm) {
        LOGe("nativeResetForNextPrompt: no llm loaded");
        return 1;
    }
    if (g_system_prompt_position == 0) {
        // Nothing pinned — full reset. Equivalent to llama.cpp's
        // `llama_memory_clear` + `chat_msgs.clear()` fallback path.
        LOGv("resetForNextPrompt: full Llm::reset() (no system prompt)");
        g_llm->reset();
        return 0;
    }
    const size_t current = g_llm->getCurrentHistory();
    if (current > g_system_prompt_position) {
        LOGv("resetForNextPrompt: erase KV [%zu, %zu)",
             g_system_prompt_position, current);
        g_llm->eraseHistory(g_system_prompt_position, current);
    }
    return 0;
}

// ----------------------------------------------------------------------------
// User-prompt generation
//
// Unlike llama.cpp's per-token `generateNextToken` JNI surface, MNN's public
// `Llm::response` API is blocking — it runs prefill + the entire generation
// loop synchronously and writes the decoded text to the supplied
// `std::ostream`. We mirror that here as a single blocking JNI call that
// returns the full assistant text; the Kotlin facade emits it as one Flow
// item so callers see the same `Flow<String>` shape as the llama path. For
// translation the difference is invisible (the translator concatenates
// everything into one StringBuilder anyway), and we avoid having to
// reimplement sampling + tokenization on the Kotlin side.
// ----------------------------------------------------------------------------

static jstring blocking_response(
        JNIEnv *env, const std::string &prompt, jint n_predict) {
    if (!g_llm) {
        LOGe("blocking_response: no llm loaded");
        return env->NewStringUTF("");
    }

    std::ostringstream sink;
    g_llm->response(prompt, &sink, nullptr, n_predict);

    const LlmContext *ctx = g_llm->getContext();
    if (ctx) {
        if (ctx->status == LlmStatus::INTERNAL_ERROR) {
            LOGe("response: INTERNAL_ERROR");
            // Surface as a Java RuntimeException so the Flow rejects rather
            // than completing with a partial / empty result, matching the
            // recovery pattern in :llama's generateNextToken.
            jclass rte = env->FindClass("java/lang/RuntimeException");
            if (rte) env->ThrowNew(rte, "MNN Llm response failed (INTERNAL_ERROR)");
            return nullptr;
        } else if (ctx->status == LlmStatus::TIMEOUT) {
            LOGw("response: TIMEOUT (returning partial result)");
        }
    }

    return env->NewStringUTF(sink.str().c_str());
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_playtranslate_mnn_internal_MnnChatImpl_processUserPromptBlocking(
        JNIEnv *env, jobject /*unused*/, jstring juser_prompt, jint n_predict) {
    const char *user = env->GetStringUTFChars(juser_prompt, nullptr);
    std::string prompt_str(user);
    env->ReleaseStringUTFChars(juser_prompt, user);
    return blocking_response(env, prompt_str, n_predict);
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_playtranslate_mnn_internal_MnnChatImpl_nativeProcessRawSuffixBlocking(
        JNIEnv *env, jobject /*unused*/, jstring jsuffix, jint n_predict) {
    // Same code path as processUserPromptBlocking — with use_template:false the
    // distinction (templated user prompt vs. raw suffix) collapses on the
    // engine side. The Kotlin facade keeps the two methods separate to mirror
    // :llama's InferenceEngine surface and let the LlamaTranslator's
    // Gemma3Prefix path port unchanged.
    const char *suffix = env->GetStringUTFChars(jsuffix, nullptr);
    std::string suffix_str(suffix);
    env->ReleaseStringUTFChars(jsuffix, suffix);
    return blocking_response(env, suffix_str, n_predict);
}
