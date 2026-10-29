package com.playtranslate.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.playtranslate.translation.BackendFailure
import com.playtranslate.translation.BackendFailureKind
import com.playtranslate.translation.TranslationError
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The pill's words for every [TranslationError], through the real string
 * resources: a format string with a broken placeholder would throw or
 * print the wrong thing only here, at render time.
 */
@RunWith(RobolectricTestRunner::class)
class TranslationErrorMessagesTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()

    private fun service(kind: BackendFailureKind, code: Int? = null, name: String = "Gemini") =
        TranslationErrorMessages.text(
            ctx, TranslationError.Service("id", name, BackendFailure(kind, code)),
        )

    @Test fun `the connection error names no service`() {
        assertEquals("Connection error", TranslationErrorMessages.text(ctx, TranslationError.Connection))
    }

    @Test fun `every service kind reads as name, colon, problem`() {
        val expected = mapOf(
            BackendFailureKind.AUTH to "Gemini: Invalid API key",
            BackendFailureKind.BILLING to "Gemini: Out of credits",
            BackendFailureKind.DAILY_QUOTA to "Gemini: Daily limit reached",
            BackendFailureKind.MONTHLY_QUOTA to "Gemini: Monthly quota used up",
            BackendFailureKind.RATE_LIMITED to "Gemini: Too many requests",
            BackendFailureKind.SERVER_ERROR to "Gemini: Server error",
            BackendFailureKind.REJECTED to "Gemini: Request rejected",
            BackendFailureKind.BAD_RESPONSE to "Gemini: Unexpected response",
            BackendFailureKind.TIMEOUT to "Gemini: Not responding",
            BackendFailureKind.UNREACHABLE to "Gemini: Can't connect",
            BackendFailureKind.NOT_RUNNING to "Gemini: Not running",
            BackendFailureKind.TRIAL_ENDED to "Gemini: Free trial ended",
            BackendFailureKind.MEMBERSHIP_INACTIVE to "Gemini: Membership inactive",
            BackendFailureKind.CLOSED to "Gemini: Service closed",
        )
        // Exhaustive: a new kind without a message fails here.
        assertEquals(BackendFailureKind.entries.toSet(), expected.keys)
        for ((kind, text) in expected) assertEquals(text, service(kind))
    }

    @Test fun `a rejection shows its status code when there is one`() {
        assertEquals("OpenAI: Request rejected (HTTP 404)", service(BackendFailureKind.REJECTED, 404, "OpenAI"))
    }

    @Test fun `only a rejection shows the code`() {
        assertEquals("Gemini: Server error", service(BackendFailureKind.SERVER_ERROR, 503))
    }

    @Test fun `the stopped cloud service reads as not running, without its 404`() {
        assertEquals(
            "PlayTranslate Cloud: Not running",
            service(BackendFailureKind.NOT_RUNNING, 404, "PlayTranslate Cloud"),
        )
    }

    /** Wording by kind, not by backend: the cloud's account and closed
     *  states have kinds of their own, so no service's BILLING changes. */
    @Test fun `TRIAL_ENDED, MEMBERSHIP_INACTIVE and CLOSED have their wording, and BILLING keeps Out of credits`() {
        val cloud = "PlayTranslate Cloud"
        assertEquals("PlayTranslate Cloud: Free trial ended", service(BackendFailureKind.TRIAL_ENDED, 403, cloud))
        assertEquals("PlayTranslate Cloud: Membership inactive", service(BackendFailureKind.MEMBERSHIP_INACTIVE, 403, cloud))
        assertEquals("PlayTranslate Cloud: Service closed", service(BackendFailureKind.CLOSED, 410, cloud))
        assertEquals("Claude: Out of credits", service(BackendFailureKind.BILLING, 400, "Claude"))
        assertEquals("OpenAI: Out of credits", service(BackendFailureKind.BILLING, 429, "OpenAI"))
    }
}
