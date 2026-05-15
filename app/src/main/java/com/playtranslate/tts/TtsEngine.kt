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
 * Thin wrapper around the system [TextToSpeech] engine.
 *
 * A fresh engine is bound on every [speak] call. The set of installed TTS
 * engines can change at any time — install, uninstall, enable, disable — and a
 * [TextToSpeech] handle does NOT survive its engine being removed: calls on a
 * dead handle return error codes that are indistinguishable from a genuine
 * "language unsupported" answer. Caching a handle across calls therefore can't
 * be made reliable, so the engine is rebuilt each time and every result
 * reflects the device's current state. Binding to an already-running engine
 * service is cheap; the cost is only paid when the engine is cold.
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

    /** Serialises [speak] calls — concurrent callers must not both rebuild the
     *  shared engine handle. */
    private val lock = Mutex()

    /** The most recently bound engine. Kept only so [stop] can reach it and so
     *  the next [speak] can shut it down before rebinding — never reused as a
     *  live engine across calls. */
    @Volatile private var tts: TextToSpeech? = null

    /**
     * Bind the system TTS engine, then either speak [text] in [lang] or report
     * why it couldn't. Suspends sub-second while the engine initialises.
     */
    suspend fun speak(context: Context, text: String, lang: SourceLangId): SpeakResult =
        lock.withLock {
            val engine = bindFreshEngine(context)
                ?: return@withLock SpeakResult.NoEngine
            // setLanguage both selects the locale and reports whether the
            // engine can serve it — its return is the authoritative
            // availability check, so there is no separate isLanguageAvailable
            // query to race against it.
            val supported = when (engine.setLanguage(lang.locale)) {
                TextToSpeech.LANG_AVAILABLE,
                TextToSpeech.LANG_COUNTRY_AVAILABLE,
                TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE -> true
                else -> false
            }
            if (!supported) {
                return@withLock SpeakResult.LanguageUnsupported(activeEngineLabel(engine))
            }
            engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "pt-tts")
            SpeakResult.Spoken
        }

    /** Stop any in-progress utterance. */
    fun stop() {
        tts?.stop()
    }

    /** Shut down the previous engine and bind a fresh one. Returns the engine
     *  once its async init succeeds, or null if none could be bound (no engine
     *  installed, or the only one is disabled). */
    private suspend fun bindFreshEngine(context: Context): TextToSpeech? {
        tts?.shutdown()
        tts = null
        val ready = CompletableDeferred<Boolean>()
        val engine = withContext(Dispatchers.Main) {
            TextToSpeech(context.applicationContext) { status ->
                ready.complete(status == TextToSpeech.SUCCESS)
            }
        }
        tts = engine
        if (ready.await()) return engine
        engine.shutdown()
        tts = null
        return null
    }

    /** Human-readable name of the active (default) engine, or null if it
     *  can't be resolved. */
    private fun activeEngineLabel(engine: TextToSpeech): String? {
        val pkg = engine.defaultEngine ?: return null
        return engine.engines.firstOrNull { it.name == pkg }?.label
    }
}
