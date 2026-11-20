package com.playtranslate.tts

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.ResolveInfo
import android.content.pm.ServiceInfo
import android.os.Looper
import android.provider.Settings
import android.speech.tts.TextToSpeech
import kotlinx.coroutines.sync.Mutex
import org.junit.Assert.assertTrue
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowTextToSpeech

/**
 * A speech engine for a JVM test: a text-to-speech service the test
 * runtime's package manager lists, named by the system settings as the
 * default engine, so [TtsEngine.activeEngineLabel] answers [label] once the
 * binding is answered ([answerTtsBinding]). With none installed the runtime
 * lists no engine and the label is null. The test runtime resets the
 * package manager and the settings between tests.
 */
fun installTtsEngine(ctx: Context, label: String, pkg: String = "com.example.speech") {
    val service = ServiceInfo().apply {
        packageName = pkg
        name = "$pkg.SpeechService"
        nonLocalizedLabel = label
        applicationInfo = ApplicationInfo().apply {
            packageName = pkg
            flags = ApplicationInfo.FLAG_SYSTEM
        }
    }
    shadowOf(ctx.packageManager).addResolveInfoForIntent(
        Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE),
        ResolveInfo().apply { serviceInfo = service },
    )
    Settings.Secure.putString(ctx.contentResolver, Settings.Secure.TTS_DEFAULT_SYNTH, pkg)
}

/**
 * Frees [TtsEngine] of earlier tests, for a test that answers its binding.
 * A page an earlier test closed while it waited for the engine (the test
 * runtime never reports one ready by itself) was cancelled, but the runtime
 * clears the main looper's queue between tests, so the cancellation never
 * ran and that call still holds the engine's lock; every later call would
 * wait behind it. The JVM's one [TtsEngine] outlives each test. Call it
 * before the page opens, while nothing of this test holds the lock.
 */
fun releaseTtsEngineFromEarlierTests() {
    val lock = TtsEngine::class.java.getDeclaredField("lock")
        .apply { isAccessible = true }
        .get(null) as Mutex
    if (lock.isLocked) lock.unlock()
}

/**
 * Answers the binding [TtsEngine] waits on: the test runtime builds the
 * TextToSpeech but never reports it ready, so without this nothing that
 * needs the engine (its voices, its name) ever answers.
 */
fun answerTtsBinding() {
    val deadline = System.currentTimeMillis() + 5_000
    while (ShadowTextToSpeech.getLastTextToSpeechInstance() == null) {
        assertTrue("timed out waiting for the engine to be bound", System.currentTimeMillis() < deadline)
        shadowOf(Looper.getMainLooper()).idle()
        Thread.sleep(10)
    }
    val engine = ShadowTextToSpeech.getLastTextToSpeechInstance()!!
    shadowOf(engine).onInitListener.onInit(TextToSpeech.SUCCESS)
}

/**
 * A device with no engine: every binding [TtsEngine] makes is refused
 * until [done]. Each call binds afresh after a refusal (whether there is
 * an engine, its voices, its name), so one refusal is not enough.
 */
fun refuseTtsBindingsUntil(what: String, done: () -> Boolean) {
    val refused = mutableSetOf<TextToSpeech>()
    val deadline = System.currentTimeMillis() + 5_000
    while (!done()) {
        assertTrue("timed out waiting for $what", System.currentTimeMillis() < deadline)
        shadowOf(Looper.getMainLooper()).idle()
        val engine = ShadowTextToSpeech.getLastTextToSpeechInstance()
        if (engine != null && refused.add(engine)) {
            shadowOf(engine).onInitListener.onInit(TextToSpeech.ERROR)
        }
        Thread.sleep(10)
    }
}
