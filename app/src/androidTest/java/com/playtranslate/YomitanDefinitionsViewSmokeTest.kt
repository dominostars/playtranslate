package com.playtranslate

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.playtranslate.ui.DefinitionsDocument
import com.playtranslate.ui.WordDefinitionData
import com.playtranslate.ui.YomitanDefinitionsView
import com.playtranslate.model.ImportedSense
import com.playtranslate.model.ImportedSenseGroup
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Real-WebView smoke test for the styled definitions component: the shell
 * boots, a structured-content swap renders, and the page reports a
 * non-zero painted height over the bridge. Needs a device/emulator with a
 * WebView provider (skips when construction fails, e.g. a stripped-down
 * test image).
 */
@RunWith(AndroidJUnit4::class)
class YomitanDefinitionsViewSmokeTest {

    @Test
    fun structuredContentReportsPaintedHeight() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val ctx = instrumentation.targetContext
        val latch = CountDownLatch(1)
        val height = AtomicInteger(0)
        var usable = true

        instrumentation.runOnMainSync {
            val view = YomitanDefinitionsView(
                ctx,
                DefinitionsDocument.Tokens(
                    text = 0xFFEFEFEF.toInt(),
                    textMuted = 0xFFA0A0A0.toInt(),
                    textHint = 0xFF606060.toInt(),
                    accent = 0xFF00BCD4.toInt(),
                    panel = 0xFF242424.toInt(),
                    baseFontSizePx = 14f,
                ),
            )
            if (!view.isUsable()) {
                usable = false
                latch.countDown()
                return@runOnMainSync
            }
            view.onContentHeight = { h ->
                if (h > 0) {
                    height.set(h)
                    latch.countDown()
                }
            }
            // Layout pass so the WebView has a width to lay against.
            view.measure(
                android.view.View.MeasureSpec.makeMeasureSpec(800, android.view.View.MeasureSpec.EXACTLY),
                android.view.View.MeasureSpec.makeMeasureSpec(600, android.view.View.MeasureSpec.AT_MOST),
            )
            view.layout(0, 0, 800, 600)

            val glossary =
                """[{"type":"structured-content","content":{"tag":"ul","data":{"content":"glossary"},""" +
                    """"content":[{"tag":"li","content":"Japanese andromeda"},{"tag":"li","content":"lily-of-the-valley"}]}}]"""
            val data = WordDefinitionData(
                word = "馬酔木",
                reading = "あせび",
                senses = emptyList(),
                freqScore = 0,
                isCommon = false,
                importedGroups = listOf(
                    ImportedSenseGroup(
                        "Jitendex",
                        listOf(ImportedSense("Japanese andromeda", scRowid = 1L)),
                        dictId = "smoketest",
                    ),
                ),
            )
            view.setContent(
                DefinitionsDocument.contentHtml(
                    data,
                    structured = mapOf(1L to glossary),
                    localizePos = { it.joinToString("/") },
                ),
                dictCss = mapOf(
                    "smoketest" to "ul[data-sc-content=\"glossary\"] { list-style-type: \"※\"; }",
                ),
                sourceLanguage = "ja",
            )
        }

        assumeTrue("Skipped: no WebView provider on this image.", usable)
        assertTrue(
            "page never reported a painted height",
            latch.await(15, TimeUnit.SECONDS) && height.get() > 0,
        )
    }
}
