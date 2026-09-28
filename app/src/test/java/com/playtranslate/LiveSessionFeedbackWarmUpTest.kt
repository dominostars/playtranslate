package com.playtranslate

import android.os.Looper
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import java.time.Duration

/**
 * The live start's first-cycle gate warms the OCR engine the first pass
 * will read (Codex adversarial, 2026-09-28). The game language can change
 * while a start waits on its consent dialog or stream probe, and the
 * service restarts only a running session for that, so a pending start
 * used to warm the old language and leave its first pass to load the new
 * one's engine cold, timed as a slow pass. Each of the gate's two loads has
 * a hang guard of its own. The warm-up is faked, so these see which
 * languages were warmed, in what order, and on the main looper's clock.
 */
@RunWith(RobolectricTestRunner::class)
class LiveSessionFeedbackWarmUpTest {

    private var service: ServiceController<CaptureService>? = null
    private lateinit var svc: CaptureService

    private fun settle() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))

    @Before fun setUp() {
        val built = Robolectric.buildService(CaptureService::class.java).create()
        service = built
        svc = built.get()
    }

    @After fun tearDown() {
        service?.destroy()
        service = null
    }

    @Test fun `the gate warms the language the first pass will read, when it changed during the wait`() {
        var lang = "ja"
        val warmed = mutableListOf<String>()
        val feedback = LiveSessionFeedback(
            svc.serviceScope, svc.mediaProjectionController, sourceLang = { lang },
            warmUp = { warmed += it },
        )
        settle()
        assertEquals("the start's own warm-up", listOf("ja"), warmed)

        lang = "zh"
        val gate = svc.serviceScope.launch { feedback.awaitFirstCycleClear() }
        settle()

        assertTrue(gate.isCompleted)
        assertEquals(listOf("ja", "zh"), warmed)
        feedback.dispose()
    }

    // One load at a time: on the slow devices this exists for, two native
    // model loads at once would only compete.
    @Test fun `the gate warms the current language only after the start's own warm-up settled`() {
        var lang = "ja"
        val warmed = mutableListOf<String>()
        val startsWarmUp = CompletableDeferred<Unit>()
        val feedback = LiveSessionFeedback(
            svc.serviceScope, svc.mediaProjectionController, sourceLang = { lang },
            warmUp = {
                warmed += it
                if (it == "ja") startsWarmUp.await()
            },
        )
        settle()
        lang = "zh"
        val gate = svc.serviceScope.launch { feedback.awaitFirstCycleClear() }
        settle()
        assertFalse("still waiting on the start's warm-up", gate.isCompleted)
        assertEquals(listOf("ja"), warmed)

        startsWarmUp.complete(Unit)
        settle()

        assertTrue(gate.isCompleted)
        assertEquals(listOf("ja", "zh"), warmed)
        feedback.dispose()
    }

    // Codex adversarial 2026-09-28: one shared cap let a slow first load use
    // up the second's time, leaving the rest of that load to the first pass.
    @Test fun `a slow start warm-up leaves the new language a cap of its own`() {
        var lang = "ja"
        val loaded = mutableListOf<String>()
        val feedback = LiveSessionFeedback(
            svc.serviceScope, svc.mediaProjectionController, sourceLang = { lang },
            warmUp = {
                delay(if (it == "ja") 7_000 else 2_000)
                loaded += it
            },
        )
        shadowOf(Looper.getMainLooper()).idle()   // the start's own warm-up begins: 7 s
        lang = "zh"
        val gate = svc.serviceScope.launch { feedback.awaitFirstCycleClear() }

        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(10))

        assertTrue(gate.isCompleted)
        assertEquals("both loads finished inside the gate", listOf("ja", "zh"), loaded)
        feedback.dispose()
    }

    @Test fun `a warm-up that never returns still lets the first cycle go after its cap`() {
        var lang = "ja"
        val feedback = LiveSessionFeedback(
            svc.serviceScope, svc.mediaProjectionController, sourceLang = { lang },
            warmUp = { if (it == "zh") awaitCancellation() },
        )
        settle()
        lang = "zh"
        val gate = svc.serviceScope.launch { feedback.awaitFirstCycleClear() }

        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(LiveSessionFeedback.WARMUP_JOIN_CAP_MS - 100))
        assertFalse(gate.isCompleted)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200))

        assertTrue(gate.isCompleted)
        feedback.dispose()
    }
}
