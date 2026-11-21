package com.playtranslate.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.playtranslate.TranslationCache
import com.playtranslate.language.ChineseScriptVariant
import com.playtranslate.language.SourceLangId
import com.playtranslate.model.TextSegment
import com.playtranslate.model.TranslationLangContext
import com.playtranslate.model.TranslationResult
import com.playtranslate.translation.BackendFailure
import com.playtranslate.translation.BackendFailureKind
import com.playtranslate.translation.FakeSwitchedBackend
import com.playtranslate.translation.TranslationBackend
import com.playtranslate.translation.TranslationBackendRegistry
import com.playtranslate.ui.ChosenServiceTranslation.Outcome
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException

/**
 * [ChosenServiceTranslation.run], the one runner behind a service picked
 * for a result on show, over the real registry and a service that answers
 * as the test says. The pages and the capture sheet test what they do with
 * its outcome through a seam in front of it
 * ([TranslationResultViewModelPickTest], [CaptureSheetTranslationPickTest]);
 * this is what the outcome is.
 *
 * A run is all or nothing. Every unit translated is the translation, unit
 * by unit, under the service's name. A unit that has text and comes back
 * with none fails the whole run, however many of the others were
 * translated: landing it would blank that unit's part of the translation on
 * show and take its box off the game screen. Only a unit with no text of
 * its own may come back with none. A service that throws, or is not there
 * to ask, fails the run too. The failure carries a reason only for an
 * online service, whose reason the user can act on.
 */
@RunWith(RobolectricTestRunner::class)
class ChosenServiceTranslationTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()

    private fun sp() = ctx.getSharedPreferences("playtranslate_prefs", Context.MODE_PRIVATE)

    @Before fun setUp() {
        sp().edit().clear().commit()
        TranslationCache.shared.resetForTest()
    }

    @After fun tearDown() {
        TranslationCache.shared.resetForTest()
        TranslationBackendRegistry.close()
        sp().edit().clear().commit()
    }

    /** A capture of [units], one OCR group each, translated before by the
     *  configured service. */
    private fun capture(vararg units: String) = TranslationResult(
        originalText = units.joinToString(" "),
        segments = listOf(TextSegment(units.joinToString(" "))),
        translatedText = units.joinToString(TranslationResult.UNIT_SEPARATOR) { "old:$it" },
        timestamp = "",
        backendDisplayName = "Lingva",
        langContext = TranslationLangContext(SourceLangId.JA, "en", ChineseScriptVariant.SIMPLIFIED),
        groupTexts = units.toList(),
    )

    private fun picked(online: Boolean = true, reply: suspend (String) -> String = { "picked:$it" }) =
        FakeSwitchedBackend(id = "picked", priority = 10, displayName = "Picked", requiresInternet = online, reply = reply)

    private fun run(backend: TranslationBackend, result: TranslationResult, registered: Boolean = true): Outcome {
        TranslationBackendRegistry.init(if (registered) listOf(backend) else emptyList())
        return runBlocking { ChosenServiceTranslation.run(ctx, backend, result) }
    }

    @Test fun `every unit translated is the translation, unit by unit, under the service's name`() {
        val outcome = run(picked(), capture("一", "二")) as Outcome.Translated

        assertEquals(listOf("picked:一", "picked:二"), outcome.perUnit)
        assertEquals("joined as a capture joins its groups'", "picked:一\n\npicked:二", outcome.text)
        assertEquals("Picked", outcome.backendDisplayName)
    }

    @Test fun `one unit answered with nothing fails the whole run`() {
        val backend = picked { if (it == "二") "" else "picked:$it" }

        val outcome = run(backend, capture("一", "二", "三")) as Outcome.Failed

        assertEquals("every unit was asked for", 3, backend.translateCalls.get())
        assertEquals("picked", outcome.serviceId)
        assertEquals("Picked", outcome.serviceName)
        assertEquals("an answer with nothing usable in it", BackendFailure.BAD_RESPONSE, outcome.failure)
    }

    @Test fun `a unit answered with only whitespace is a unit answered with nothing`() {
        val outcome = run(picked { if (it == "一") " \n" else "picked:$it" }, capture("一", "二"))

        assertEquals(BackendFailure.BAD_RESPONSE, (outcome as Outcome.Failed).failure)
    }

    @Test fun `a result of one unit answered with nothing fails`() {
        val outcome = run(picked { "" }, capture("一"))

        assertEquals(BackendFailure.BAD_RESPONSE, (outcome as Outcome.Failed).failure)
    }

    @Test fun `an offline service's unit answered with nothing fails the run with no reason to name`() {
        val outcome = run(picked(online = false) { if (it == "二") "" else "picked:$it" }, capture("一", "二"))

        assertNull((outcome as Outcome.Failed).failure)
    }

    @Test fun `a unit with no text of its own may come back with none`() {
        val outcome = run(picked { if (it.isBlank()) "" else "picked:$it" }, capture("一", " "))

        assertEquals(listOf("picked:一", ""), (outcome as Outcome.Translated).perUnit)
    }

    @Test fun `a service that throws fails the run, with its reason when it is an online one`() {
        val online = run(picked { throw IOException("no route") }, capture("一", "二")) as Outcome.Failed
        assertEquals(BackendFailure(BackendFailureKind.UNREACHABLE), online.failure)
        assertEquals("Picked", online.serviceName)

        val offline = run(picked(online = false) { throw IllegalStateException("model") }, capture("一", "二")) as Outcome.Failed
        assertNull("an on-device failure names nothing the user can act on", offline.failure)
    }

    @Test fun `a service that is no longer there to ask fails the run`() {
        val backend = picked()

        val outcome = run(backend, capture("一"), registered = false) as Outcome.Failed

        assertEquals("nobody was asked", 0, backend.translateCalls.get())
        assertEquals("Picked", outcome.serviceName)
        assertNull(outcome.failure)
    }
}
