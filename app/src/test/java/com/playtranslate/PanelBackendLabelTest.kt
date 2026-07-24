package com.playtranslate

import android.graphics.Rect
import com.playtranslate.ui.TextBox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Unit tests for [OverlayToolkit.panelBackendLabel] — the aggregate
 * "Translated by …" attribution for live panel emissions. The contract:
 * one label only when every translated box names the same backend; any
 * disagreement, or a translated box with unknown provenance, suppresses
 * it (returns null). Untranslated boxes (skeletons mid-flight) carry no
 * evidence and must not affect the verdict.
 *
 * Runs under Robolectric so [android.graphics.Rect] is available on the
 * JVM (same convention as [ScanlineReconcilerTest]).
 */
@RunWith(RobolectricTestRunner::class)
class PanelBackendLabelTest {

    private fun box(translatedText: String, backend: String?) = TextBox(
        translatedText = translatedText,
        bounds = Rect(0, 0, 100, 20),
        sourceText = "src",
        backendDisplayName = backend,
    )

    @Test
    fun `single backend across translated boxes yields its name`() {
        val boxes = listOf(box("Hello", "Qwen"), box("World", "Qwen"))
        assertEquals("Qwen", OverlayToolkit.panelBackendLabel(boxes))
    }

    @Test
    fun `disagreeing backends suppress the label`() {
        val boxes = listOf(box("Hello", "Qwen"), box("World", "DeepL"))
        assertNull(OverlayToolkit.panelBackendLabel(boxes))
    }

    @Test
    fun `translated box with unknown provenance suppresses the label`() {
        val boxes = listOf(box("Hello", "Qwen"), box("World", null))
        assertNull(OverlayToolkit.panelBackendLabel(boxes))
    }

    @Test
    fun `untranslated skeletons carry no evidence`() {
        val boxes = listOf(box("Hello", "Qwen"), box("", null))
        assertEquals("Qwen", OverlayToolkit.panelBackendLabel(boxes))
    }

    @Test
    fun `all unknown provenance yields no label`() {
        val boxes = listOf(box("Hello", null), box("World", null))
        assertNull(OverlayToolkit.panelBackendLabel(boxes))
    }

    @Test
    fun `no translated boxes yields no label`() {
        assertNull(OverlayToolkit.panelBackendLabel(emptyList()))
        assertNull(OverlayToolkit.panelBackendLabel(listOf(box("", null))))
    }
}
