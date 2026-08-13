package com.playtranslate

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.playtranslate.model.ImportedSense
import com.playtranslate.model.ImportedSenseGroup
import com.playtranslate.ui.DefinitionsDocument
import com.playtranslate.ui.WordDefinitionData
import com.playtranslate.ui.YomitanDefinitionsView
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Proof-on-engine for dictionary CSS application: binds structured content
 * WITH a dictionary stylesheet and asserts, via computed styles inside the
 * real WebView, that (a) a scoped rule actually painted the content and
 * (b) a scope-escape attempt (stray brace) did NOT reach elements outside
 * the dictionary's section. Runs meaningfully on OLD engines — the API 30
 * emulator's WebView predates constructable stylesheets and CSS nesting,
 * which is exactly the class of device (AOSP WebView, e.g. AYN Thor at
 * Chromium 109) where the styled path first shipped broken.
 */
@RunWith(AndroidJUnit4::class)
class YomitanDictCssApplyTest {

    private fun probe(view: YomitanDefinitionsView, js: String, timeoutS: Long = 15): String? {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val result = AtomicReference<String?>()
        val deadline = System.currentTimeMillis() + timeoutS * 1000
        while (System.currentTimeMillis() < deadline) {
            val latch = CountDownLatch(1)
            instrumentation.runOnMainSync {
                val wv = (view.getChildAt(0) as? android.webkit.WebView)
                if (wv == null) {
                    result.set(null); latch.countDown()
                } else {
                    wv.evaluateJavascript(js) { v -> result.set(v); latch.countDown() }
                }
            }
            latch.await(5, TimeUnit.SECONDS)
            val v = result.get()
            if (v != null && v != "null" && v != "\"pending\"") return v
            Thread.sleep(200)
        }
        return result.get()
    }

    @Test
    fun dictionaryCssPaintsContentAndEscapeStaysContained() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val ctx = instrumentation.targetContext
        var usable = true
        lateinit var view: YomitanDefinitionsView

        instrumentation.runOnMainSync {
            view = YomitanDefinitionsView(
                ctx,
                DefinitionsDocument.Tokens(
                    text = 0xFFEFEFEF.toInt(), textMuted = 0xFFA0A0A0.toInt(),
                    textHint = 0xFF606060.toInt(), accent = 0xFF00BCD4.toInt(),
                    panel = 0xFF242424.toInt(), baseFontSizePx = 14f,
                ),
            )
            usable = view.isUsable()
            if (!usable) return@runOnMainSync
            view.measure(
                android.view.View.MeasureSpec.makeMeasureSpec(800, android.view.View.MeasureSpec.EXACTLY),
                android.view.View.MeasureSpec.makeMeasureSpec(600, android.view.View.MeasureSpec.AT_MOST),
            )
            view.layout(0, 0, 800, 600)

            val glossary =
                """[{"type":"structured-content","content":{"tag":"span","data":{"class":"chip"},"content":"styled chip"}}]"""
            val data = WordDefinitionData(
                word = "猫", reading = "ねこ", senses = emptyList(),
                freqScore = 0, isCommon = false,
                importedGroups = listOf(
                    ImportedSenseGroup(
                        "Gauntlet", listOf(ImportedSense("styled chip", scRowid = 1L)),
                        dictId = "csstest",
                    ),
                ),
            )
            // The stylesheet carries a gradient rule (the observable), an
            // @media rule, and ends with a scope-escape attempt.
            val css = """
                span[data-sc-class="chip"] { background-image: linear-gradient(135deg, rgb(123,47,247), rgb(241,7,163)); }
                @media (min-width: 1px) { span[data-sc-class="chip"] { font-weight: 700; } }
                } #root { display: none !important; } body { visibility: hidden !important; }
            """.trimIndent()
            view.setContent(
                DefinitionsDocument.contentHtml(
                    data, structured = mapOf(1L to glossary), localizePos = { it.joinToString("/") },
                ),
                dictCss = mapOf("csstest" to css),
                sourceLanguage = "ja",
            )
        }
        assumeTrue("Skipped: no WebView provider on this image.", usable)

        val chipBg = probe(
            view,
            """(function(){
                 var el = document.querySelector('span[data-sc-class="chip"]');
                 if (!el) return 'pending';
                 return getComputedStyle(el).backgroundImage;
               })()""",
        )
        assertTrue(
            "dictionary CSS did not paint the chip (backgroundImage=$chipBg)",
            chipBg != null && chipBg.contains("gradient"),
        )

        val mediaWeight = probe(
            view,
            """(function(){
                 var el = document.querySelector('span[data-sc-class="chip"]');
                 return el ? getComputedStyle(el).fontWeight : 'pending';
               })()""",
        )
        assertTrue(
            "@media rule did not apply (fontWeight=$mediaWeight)",
            mediaWeight != null && mediaWeight.contains("700"),
        )

        val escape = probe(
            view,
            """(function(){
                 var root = document.getElementById('root');
                 return getComputedStyle(root).display + '/' + getComputedStyle(document.body).visibility;
               })()""",
        )
        assertTrue(
            "scope escape reached the page chrome (root/body=$escape)",
            escape != null && !escape.contains("none") && !escape.contains("hidden"),
        )
    }
}
