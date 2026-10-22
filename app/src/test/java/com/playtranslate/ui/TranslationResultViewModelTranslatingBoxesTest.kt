package com.playtranslate.ui

import android.content.Context
import android.graphics.Rect
import androidx.test.core.app.ApplicationProvider
import com.playtranslate.OneShotOverlayData
import com.playtranslate.RegionEntry
import com.playtranslate.language.ChineseScriptVariant
import com.playtranslate.language.SourceLangId
import com.playtranslate.model.OcrProvenance
import com.playtranslate.model.TextSegment
import com.playtranslate.model.TranslationLangContext
import com.playtranslate.model.TranslationResult
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.coroutines.CoroutineContext

/**
 * [TranslationResultViewModel.applyTranslatingBoxes]: a box fill of a one-shot
 * capture patches the boxes of the Translating placeholder and nothing else.
 * The source stays, the word lookups the placeholder started are not restarted,
 * and any other state is left alone. Lookups are observed by launch count: the
 * VM launches nothing on its scope but the lookup pipeline, and the test scope
 * holds every launch without running it.
 */
@RunWith(RobolectricTestRunner::class)
class TranslationResultViewModelTranslatingBoxesTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()

    /** Counts launches on the VM's scope and never runs them. */
    private class HeldLaunches : CoroutineDispatcher() {
        var count = 0
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            count++
        }
    }

    private val launches = HeldLaunches()
    private val vm = TranslationResultViewModel(CoroutineScope(SupervisorJob() + launches))

    private val drawn = Rect(10, 10, 110, 40)
    private val segments = listOf(TextSegment("ソース"))
    private val provenance = OcrProvenance(
        engineLabel = "ML Kit",
        engineToken = "mlkit",
        displayId = 0,
        sourceLangId = SourceLangId.JA,
        region = RegionEntry(label = "", top = 0f, bottom = 1f, id = "full"),
    )

    private fun boxes(text: String) = OnScreenBoxes(
        OneShotOverlayData(
            boxes = listOf(TextBox(text, Rect(drawn), drawBounds = Rect(drawn))),
            cropLeft = 0, cropTop = 0, screenshotW = 400, screenshotH = 300,
            cropWidth = 200, cropHeight = 160,
            text = listOf(Rect(drawn)),
        ),
        displayId = 0,
    )

    private fun showPlaceholder(onScreenBoxes: OnScreenBoxes?) =
        vm.showTranslatingPlaceholder("ソース", segments, ctx, provenance, onScreenBoxes)

    @Test
    fun `a box fill patches the placeholder's boxes and keeps its source`() {
        showPlaceholder(boxes(""))
        val filled = boxes("source")
        vm.applyTranslatingBoxes(filled)
        assertEquals(
            ResultState.Translating("ソース", segments, provenance, filled),
            vm.result.value,
        )
        assertSame(filled, (vm.result.value as ResultState.Translating).onScreenBoxes)
    }

    @Test
    fun `a box fill does not restart the word lookups`() {
        showPlaceholder(boxes(""))
        assertEquals("the placeholder starts one lookup", 1, launches.count)
        vm.applyTranslatingBoxes(boxes("source"))
        assertEquals(1, launches.count)
    }

    @Test
    fun `a box fill leaves a Ready result alone`() {
        val ready = TranslationResult(
            originalText = "ソース",
            segments = segments,
            translatedText = "source",
            timestamp = "00:00:00",
            langContext = TranslationLangContext(SourceLangId.JA, "en", ChineseScriptVariant.SIMPLIFIED),
        )
        vm.displayResult(ready, ctx, onScreenBoxes = boxes("source"))
        val before = vm.result.value
        vm.applyTranslatingBoxes(boxes("late"))
        assertSame(before, vm.result.value)
    }

    @Test
    fun `a box fill leaves a status alone`() {
        vm.showStatus("Recognizing text")
        val before = vm.result.value
        vm.applyTranslatingBoxes(boxes("late"))
        assertSame(before, vm.result.value)
    }
}
