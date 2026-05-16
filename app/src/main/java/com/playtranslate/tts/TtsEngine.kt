package com.playtranslate.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
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

    /** Monotonic source of unique utterance ids. A fresh id per [speak] lets
     *  [utteranceListener] tell this call's utterance apart from a prior one
     *  being flushed by its QUEUE_FLUSH. Mutated only under [lock]. */
    private var utteranceCounter = 0

    /** The caller awaiting its utterance's completion. Written only under
     *  [lock] — by [speak] and [discardEngine]; [utteranceListener] only reads
     *  it, on the engine's callback thread. A finished awaiter left in the slot
     *  is harmless: ids are unique and completing it again is a no-op. */
    @Volatile private var pending: PendingSpeech? = null

    /** An awaited utterance: the id it was queued under, paired with the
     *  deferred to complete once it reaches a terminal state. */
    private class PendingSpeech(
        val utteranceId: String,
        val done: CompletableDeferred<Unit>,
    )

    /** Engine-wide progress listener, installed once per engine build by
     *  [currentEngine]. Completes the [pending] awaiter when ITS utterance
     *  finishes, errors, or is stopped/flushed; callbacks for any other id
     *  (e.g. a prior utterance discarded by a new QUEUE_FLUSH) are ignored so
     *  the wait can't end early. */
    private val utteranceListener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {}
        override fun onDone(utteranceId: String?) { settle(utteranceId) }
        @Suppress("OVERRIDE_DEPRECATION")
        override fun onError(utteranceId: String?) { settle(utteranceId) }
        override fun onStop(utteranceId: String?, interrupted: Boolean) {
            settle(utteranceId)
        }

        private fun settle(utteranceId: String?) {
            val awaited = pending ?: return
            if (awaited.utteranceId == utteranceId) awaited.done.complete(Unit)
        }
    }

    /**
     * Speak [text] in [lang], or report why it couldn't. Reuses the cached
     * engine; rebuilds only on the first call, after an engine switch, or to
     * confirm a "can't serve this" result against a fresh engine. Prefers the
     * user's saved voice for [lang] when one is set.
     *
     * With [awaitCompletion] set, suspends until the utterance finishes,
     * errors, or is interrupted (by [stop] or a later speak) before returning;
     * otherwise returns once the utterance is queued. A non-[SpeakResult.Spoken]
     * outcome is returned without waiting either way.
     */
    suspend fun speak(
        context: Context,
        text: String,
        lang: SourceLangId,
        awaitCompletion: Boolean = false,
    ): SpeakResult {
        val completion: CompletableDeferred<Unit>? = lock.withLock {
            val savedVoiceName = Prefs(context).ttsVoiceName(lang)
            var engine = currentEngine(context) ?: return SpeakResult.NoEngine
            if (!prepareEngine(engine, lang, savedVoiceName)) {
                // "Can't serve this" is ambiguous: the engine genuinely lacks
                // the language, or the cached binding is dead and can't answer.
                // Rebuild once and re-ask — a fresh binding is authoritative.
                discardEngine()
                engine = currentEngine(context) ?: return SpeakResult.NoEngine
                if (!prepareEngine(engine, lang, savedVoiceName)) {
                    return SpeakResult.LanguageUnsupported(activeEngineLabel(engine))
                }
            }
            val utteranceId = "pt-tts-${++utteranceCounter}"
            // Every speak() flushes the queue, so a prior utterance — and any
            // caller awaiting it — is finished the moment this one is enqueued.
            // Settle that awaiter now: once [pending] is overwritten below, the
            // listener's id check would no longer match its terminal callback.
            pending?.done?.complete(Unit)
            val done = if (awaitCompletion) {
                CompletableDeferred<Unit>().also { pending = PendingSpeech(utteranceId, it) }
            } else {
                pending = null
                null
            }
            val queued = engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
            // A rejected enqueue — e.g. text over getMaxSpeechInputLength() —
            // fires no progress callback; settle the awaiter here so it can't
            // hang on an utterance that will never run. The rejection is not
            // surfaced as a distinct result (speak() still returns Spoken):
            // an OCR'd screen of game text never approaches that length.
            if (done != null && queued == TextToSpeech.ERROR) done.complete(Unit)
            done
        }
        // Awaited outside the lock — a long utterance must not block other calls.
        completion?.await()
        return SpeakResult.Spoken
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
        // Routes utterance completion back to an awaiting speak() caller.
        engine.setOnUtteranceProgressListener(utteranceListener)
        return engine
    }

    /** Shut down and forget the cached engine. */
    private fun discardEngine() {
        tts?.shutdown()
        tts = null
        cachedEnginePackage = null
        // A shut-down engine delivers no further utterance callbacks; settle
        // any awaiter now so it can't hang waiting for one.
        pending?.done?.complete(Unit)
        pending = null
    }

    /** Human-readable name of the active (default) engine, or null if it
     *  can't be resolved. */
    private fun activeEngineLabel(engine: TextToSpeech): String? {
        val pkg = engine.defaultEngine ?: return null
        return engine.engines.firstOrNull { it.name == pkg }?.label
    }
}
