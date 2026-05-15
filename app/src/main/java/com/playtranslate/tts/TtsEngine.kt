package com.playtranslate.tts

import android.content.Context
import android.speech.tts.TextToSpeech
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
     * confirm a "language unsupported" result against a fresh engine.
     */
    suspend fun speak(context: Context, text: String, lang: SourceLangId): SpeakResult =
        lock.withLock {
            var engine = currentEngine(context) ?: return@withLock SpeakResult.NoEngine
            if (!isLanguageSupported(engine, lang)) {
                // "Unsupported" is ambiguous: the engine genuinely lacks the
                // language, or the cached binding is dead and can't answer.
                // Rebuild once and re-ask — a fresh binding is authoritative.
                discardEngine()
                engine = currentEngine(context) ?: return@withLock SpeakResult.NoEngine
                if (!isLanguageSupported(engine, lang)) {
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
