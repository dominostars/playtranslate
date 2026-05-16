package com.playtranslate.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import com.playtranslate.Prefs
import com.playtranslate.language.SourceLangId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Cached wrapper around the system [TextToSpeech] engine.
 *
 * The engine is built once and reused across [speak] calls, so only the first
 * call after a change pays the engine-bind cost.
 *
 * Two mechanisms keep the cache honest:
 *  - Engine switched: each call compares the live system default-engine
 *    package against the one the cached engine was built against, and rebinds
 *    when the user has chosen a different engine.
 *  - Cached binding dead (engine uninstalled, disabled, process killed, or
 *    updated in place): caught lazily. `setLanguage` on a dead binding can't
 *    reach the engine to confirm anything, so it reports the language
 *    unsupported — ambiguous with a genuinely unsupported language. On any
 *    "unsupported" result the engine is rebuilt once and the language
 *    re-checked; a freshly bound engine answers authoritatively. The happy
 *    path (supported language, live engine) never triggers the rebuild.
 */
object TtsEngine {

    /** Outcome of a [speak] call. */
    sealed interface SpeakResult {
        /** The word is being spoken. */
        object Spoken : SpeakResult

        /** A TTS engine is active but has no voice for the requested language.
         *  [engineLabel] is the engine's human-readable name when known
         *  (e.g. "Speech Services by Google"), otherwise null. */
        data class LanguageUnsupported(val engineLabel: String?) : SpeakResult

        /** No usable TTS engine is installed/enabled on the device. */
        object NoEngine : SpeakResult
    }

    /** Serialises [speak] calls so concurrent callers can't both rebuild the
     *  shared engine handle. */
    private val lock = Mutex()

    /** The cached engine, reused across calls. */
    @Volatile private var tts: TextToSpeech? = null

    /** Default-engine package [tts] was built against, or null when there is
     *  no valid cached engine. The cache is reused only while the live system
     *  default still equals this. */
    private var cachedEnginePackage: String? = null

    /**
     * Speak [text] in [lang], or report why it couldn't. Reuses the cached
     * engine; rebuilds only on the first call, after an engine switch, or to
     * confirm a "can't serve this" result against a fresh engine. Prefers the
     * user's saved voice for [lang] when one is set.
     */
    suspend fun speak(context: Context, text: String, lang: SourceLangId): SpeakResult =
        lock.withLock {
            val savedVoiceName = Prefs(context).ttsVoiceName(lang)
            var engine = currentEngine(context) ?: return@withLock SpeakResult.NoEngine
            if (!prepareEngine(engine, lang, savedVoiceName)) {
                // "Can't serve this" is ambiguous: the engine genuinely lacks
                // the language, or the cached binding is dead and can't answer.
                // Rebuild once and re-ask — a fresh binding is authoritative.
                discardEngine()
                engine = currentEngine(context) ?: return@withLock SpeakResult.NoEngine
                if (!prepareEngine(engine, lang, savedVoiceName)) {
                    return@withLock SpeakResult.LanguageUnsupported(activeEngineLabel(engine))
                }
            }
            engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "pt-tts")
            SpeakResult.Spoken
        }

    /** Stop any in-progress utterance. */
    fun stop() {
        tts?.stop()
    }

    /** Whether a usable TTS engine can be bound right now. */
    suspend fun isEngineAvailable(context: Context): Boolean =
        lock.withLock { currentEngine(context) != null }

    /** The voices the active engine offers for [lang]. For Chinese — where the
     *  source language fixes a script but engine voices are region-tagged —
     *  the script-appropriate regions are floated to the top (Traditional →
     *  TW/HK/MO, Simplified → CN/SG); within that, ordered best-quality-first.
     *  Empty when no engine is available. */
    suspend fun voicesFor(context: Context, lang: SourceLangId): List<Voice> =
        lock.withLock {
            val engine = currentEngine(context) ?: return@withLock emptyList()
            val voices = engine.voices ?: return@withLock emptyList()
            // The source language fixes a script; engine voices are tagged by
            // region. Float the script's regions first so the natural pick
            // sits on top. Empty (a no-op) for single-locale languages.
            val preferredRegions: Set<String> = when {
                lang.locale.language != "zh" -> emptySet()
                lang.locale.script == "Hant" -> setOf("TW", "HK", "MO")
                else -> setOf("CN", "SG")
            }
            voices
                .filter { it.locale.language == lang.locale.language }
                .sortedWith(
                    compareByDescending<Voice> { it.locale.country in preferredRegions }
                        .thenByDescending { it.quality }
                        .thenBy { it.name },
                )
        }

    /** Speak a short in-language sample with [voice] — the voice picker's
     *  audition. A null [voice] auditions the language's default voice.
     *  Best-effort; no-op when no engine is available. */
    suspend fun previewVoice(context: Context, voice: Voice?, lang: SourceLangId) {
        lock.withLock {
            val engine = currentEngine(context) ?: return@withLock
            if (voice != null) engine.voice = voice else engine.setLanguage(lang.locale)
            engine.speak(
                lang.displayName(lang.locale),
                TextToSpeech.QUEUE_FLUSH, null, "pt-tts-preview",
            )
        }
    }

    /** Select [lang] on [engine] and report whether it can serve it.
     *  `setLanguage` both selects the locale and returns the availability
     *  code, so it is the authoritative check. */
    private fun isLanguageSupported(engine: TextToSpeech, lang: SourceLangId): Boolean =
        when (engine.setLanguage(lang.locale)) {
            TextToSpeech.LANG_AVAILABLE,
            TextToSpeech.LANG_COUNTRY_AVAILABLE,
            TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE -> true
            else -> false
        }

    /** Ready [engine] to speak [lang] and report whether it can.
     *
     *  The saved voice ([savedVoiceName], a [Voice] name) is tried first: it
     *  was chosen from the engine's own enumerated voices, so its presence in
     *  [TextToSpeech.getVoices] means the engine can speak it. That is
     *  authoritative even when [isLanguageSupported]'s `setLanguage` rejects a
     *  bare script-only locale (e.g. `zh-Hant`, which carries no region for the
     *  engine to resolve). Only when there is no usable saved voice does the
     *  `setLanguage` check decide — selecting the language's default voice as
     *  its side effect. */
    private fun prepareEngine(
        engine: TextToSpeech,
        lang: SourceLangId,
        savedVoiceName: String?,
    ): Boolean {
        if (savedVoiceName != null) {
            val voice = engine.voices?.firstOrNull { it.name == savedVoiceName }
            if (voice != null) {
                engine.voice = voice
                return true
            }
        }
        return isLanguageSupported(engine, lang)
    }

    /**
     * The engine to speak with: the cached one when the live system default
     * still matches what it was built against, otherwise a freshly bound one.
     * Null when no engine can be bound (none installed, or the only one
     * disabled).
     */
    private suspend fun currentEngine(context: Context): TextToSpeech? {
        val cached = tts
        // Reuse the cache only while the cached engine is still the system
        // default. An uninstalled/disabled engine also fails this — its
        // package is no longer what getDefaultEngine() resolves to — and a
        // dead-but-still-default binding is caught by speak()'s rebuild path.
        if (cached != null && cachedEnginePackage != null &&
            cached.defaultEngine == cachedEnginePackage
        ) {
            return cached
        }
        // Stale or absent: a different engine is now the default, or nothing
        // is cached yet. Drop the old handle and bind the current default.
        discardEngine()
        val ready = CompletableDeferred<Boolean>()
        val engine = withContext(Dispatchers.Main) {
            TextToSpeech(context.applicationContext) { status ->
                ready.complete(status == TextToSpeech.SUCCESS)
            }
        }
        // Stored before the await so a cancelled build is still cleaned up by
        // the next call's discardEngine().
        tts = engine
        if (!ready.await()) {
            discardEngine()
            return null
        }
        cachedEnginePackage = engine.defaultEngine
        return engine
    }

    /** Shut down and forget the cached engine. */
    private fun discardEngine() {
        tts?.shutdown()
        tts = null
        cachedEnginePackage = null
    }

    /** Human-readable name of the active (default) engine, or null if it
     *  can't be resolved. */
    private fun activeEngineLabel(engine: TextToSpeech): String? {
        val pkg = engine.defaultEngine ?: return null
        return engine.engines.firstOrNull { it.name == pkg }?.label
    }
}
