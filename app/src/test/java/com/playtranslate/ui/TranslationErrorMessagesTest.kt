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
}
