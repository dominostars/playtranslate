package com.playtranslate.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.playtranslate.OneShotOverlayData
import com.playtranslate.language.ChineseScriptVariant
import com.playtranslate.language.SourceLangId
import com.playtranslate.model.PendingTranslation
import com.playtranslate.model.TextSegment
import com.playtranslate.model.TranslationLangContext
import com.playtranslate.model.TranslationResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Pins the deferred-translation contract on [TranslationResultViewModel]:
 * a completion patches the Ready result in place (translation + note +
 * backend, pending cleared, on-screen boxes preserved or swapped), every
 * path that derives a NEW translation (edit-overlay commit, caller-supplied
 * update) clears the pending, and the apply guard is IDENTITY — via
 * [PendingTranslation.requestToken] — so a stale completion can never pass a
 * newer result's guard even when every user-visible field matches (repeat
 * capture of the same text with History off).
 */
@RunWith(RobolectricTestRunner::class)
class TranslationResultViewModelDeferredTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()

    private fun pending(text: String = "こんにちは") = PendingTranslation(
        groupTexts = listOf(text),
        sourceLangId = SourceLangId.JA,
        targetLang = "en",
    )

    private fun deferredResult(
        p: PendingTranslation = pending(),
        text: String = "こんにちは",
        timestamp: String = "00:00:00",
    ) = TranslationResult(
        originalText = text,
        segments = listOf(TextSegment(text)),
        translatedText = "",
        timestamp = timestamp,
        pendingTranslation = p,
        langContext = TranslationLangContext(SourceLangId.JA, "en", ChineseScriptVariant.SIMPLIFIED),
    )

    private fun boxes() = OnScreenBoxes(OneShotOverlayData(emptyList(), 0, 0, 100, 100), displayId = 0)

    @Test
    fun `applyDeferredTranslation patches Ready in place and clears the pending`() {
        val vm = TranslationResultViewModel()
        val p = pending()
        vm.displayResult(deferredResult(p), ctx)
        vm.applyDeferredTranslation(p, "Hello", note = null, backendDisplayName = "DeepL")
        val ready = vm.result.value as ResultState.Ready
        assertEquals("Hello", ready.result.translatedText)
        assertEquals("DeepL", ready.result.backendDisplayName)
        assertNull(ready.result.pendingTranslation)
        assertEquals("こんにちは", ready.result.originalText)
    }

    @Test
    fun `applyDeferredTranslation preserves existing boxes when given none`() {
        val skeleton = boxes()
        val vm = TranslationResultViewModel()
        val p = pending()
        vm.displayResult(deferredResult(p), ctx, onScreenBoxes = skeleton)
        vm.applyDeferredTranslation(p, "Hello", null, null)
        assertSame(skeleton, (vm.result.value as ResultState.Ready).onScreenBoxes)
    }

    @Test
    fun `applyDeferredTranslation swaps in freshly filled boxes`() {
        val skeleton = boxes()
        val filled = boxes()
        val vm = TranslationResultViewModel()
        val p = pending()
        vm.displayResult(deferredResult(p), ctx, onScreenBoxes = skeleton)
        vm.applyDeferredTranslation(p, "Hello", null, null, onScreenBoxes = filled)
        assertSame(filled, (vm.result.value as ResultState.Ready).onScreenBoxes)
    }

    @Test
    fun `applyDeferredTranslation no-ops on a result without a pending`() {
        val vm = TranslationResultViewModel()
        vm.displayResult(
            deferredResult().copy(translatedText = "Edited", pendingTranslation = null), ctx,
        )
        val before = vm.result.value
        vm.applyDeferredTranslation(pending(), "Stale completion", null, null)
        assertSame(before, vm.result.value)
        assertEquals("Edited", (vm.result.value as ResultState.Ready).result.translatedText)
    }

    @Test
    fun `updateOriginalText clears the pending so a late completion cannot clobber the edit`() {
        val vm = TranslationResultViewModel()
        val p = pending()
        vm.displayResult(deferredResult(p), ctx)
        vm.updateOriginalText("edited source", ctx)
        assertNull((vm.result.value as ResultState.Ready).result.pendingTranslation)
        // The late completion then no-ops via the guard above.
        vm.applyDeferredTranslation(p, "Old source translation", null, null)
        assertEquals("", (vm.result.value as ResultState.Ready).result.translatedText)
    }

    @Test
    fun `updateTranslation supersedes and clears the pending`() {
        val vm = TranslationResultViewModel()
        vm.displayResult(deferredResult(), ctx)
        vm.updateTranslation("From an edit", appCtx = ctx)
        assertNull((vm.result.value as ResultState.Ready).result.pendingTranslation)
        assertEquals("From an edit", (vm.result.value as ResultState.Ready).result.translatedText)
    }

    @Test
    fun `stale completion for an older pending cannot clobber a newer deferred result`() {
        // Codex round-1 race: deferred result A starts its completion,
        // deferred result B (different source) is displayed before A's
        // translation returns, then A's completion lands late.
        val vm = TranslationResultViewModel()
        val pendingA = pending("こんにちは")
        vm.displayResult(deferredResult(pendingA, text = "こんにちは"), ctx)

        val pendingB = pending("さようなら")
        vm.displayResult(deferredResult(pendingB, text = "さようなら", timestamp = "00:00:01"), ctx)

        // A's completion returns late — must be a no-op.
        vm.applyDeferredTranslation(pendingA, "Hello", null, "DeepL")
        val afterStale = (vm.result.value as ResultState.Ready).result
        assertEquals("", afterStale.translatedText)
        assertEquals(pendingB, afterStale.pendingTranslation)

        // B's own completion still lands normally.
        vm.applyDeferredTranslation(pendingB, "Goodbye", null, "DeepL")
        val afterOwn = (vm.result.value as ResultState.Ready).result
        assertEquals("Goodbye", afterOwn.translatedText)
        assertNull(afterOwn.pendingTranslation)
    }

    @Test
    fun `same-field pendings are still distinct requests`() {
        // Codex round-5: two captures of the SAME text under the same pair
        // and eligibility (History off ⇒ no session id) must not share an
        // identity — the requestToken is the discriminator. A's stale
        // completion may not clear or update B's structurally-lookalike
        // result; B's own completion still lands.
        val pendingA = pending("こんにちは")
        val pendingB = pending("こんにちは")
        assertNotEquals(pendingA, pendingB)

        val vm = TranslationResultViewModel()
        vm.displayResult(deferredResult(pendingA), ctx)
        vm.displayResult(deferredResult(pendingB, timestamp = "00:00:01"), ctx)

        vm.applyDeferredTranslation(pendingA, "Stale hello", null, "DeepL")
        val afterStale = (vm.result.value as ResultState.Ready).result
        assertEquals("", afterStale.translatedText)
        assertEquals(pendingB, afterStale.pendingTranslation)

        vm.applyDeferredTranslation(pendingB, "Hello", null, "DeepL")
        assertEquals("Hello", (vm.result.value as ResultState.Ready).result.translatedText)
    }
}
