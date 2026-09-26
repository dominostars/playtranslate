package com.playtranslate.ui

import android.graphics.Rect
import android.util.TypedValue
import android.view.View
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.text.BreakIterator
import kotlin.math.ceil

/**
 * Real-font parity for the minimum text size: pass 4's measurer certifies a
 * rect for a size, and the render child's autosize must then actually reach
 * that size there, without breaking a word across lines. The JVM tests can't
 * check this (Robolectric's text metrics are fake); this renders offscreen the
 * way the camera rasterizer does, with the device's fonts.
 *
 * A box far too small for its text sits alone in a large view, so there is
 * always room to reach the target.
 */
@RunWith(AndroidJUnit4::class)
class OverlayMinTextParityTest {

    private val corpus = listOf(
        "Hello there, my friend",
        "Donaudampfschifffahrtsgesellschaft Kapitän",
        "こんにちは、世界の皆さん",
        "مرحبا بالعالم الجميل",
    )

    @Test
    fun certifiedSize_isWhatAutosizeReaches_andNoWordBreaks() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val ctx = instrumentation.targetContext
        val failures = mutableListOf<String>()
        instrumentation.runOnMainSync {
            for (sp in listOf(8, 12, 20)) {
                val targetPx = ceil(
                    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp.toFloat(), ctx.resources.displayMetrics),
                )
                for (text in corpus) {
                    val view = TranslationOverlayView(ctx, renderConfig = OverlayRenderConfig(minTextSp = sp))
                    val w = View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY)
                    val h = View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY)
                    view.measure(w, h)
                    view.layout(0, 0, 1080, 1920)
                    view.setBoxes(listOf(TextBox(text, Rect(500, 900, 504, 904))), 0, 0, 1080, 1920)
                    view.measure(w, h)
                    view.layout(0, 0, 1080, 1920)
                    val child = view.getChildAt(0) as TextView
                    if (child.textSize < targetPx - 0.5f) {
                        failures += "$sp sp \"$text\": autosize ${child.textSize} px < certified $targetPx px"
                    }
                    val layout = child.layout ?: continue
                    val breaks = BreakIterator.getLineInstance().apply { setText(text) }
                    for (line in 1 until layout.lineCount) {
                        val start = layout.getLineStart(line)
                        if (!breaks.isBoundary(start)) {
                            failures += "$sp sp \"$text\": line $line starts mid-word at $start"
                        }
                    }
                }
            }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }
}
