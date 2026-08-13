package com.playtranslate.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Regression pin for the stale-height race (Codex adversarial finding):
 * a height report scheduled two frames after swap N can arrive after swap
 * N+1 was issued; treating it as proof that N+1 painted would reveal the
 * PREVIOUS lookup's styled content under the new word. The component must
 * accept only reports stamped with the LATEST render generation.
 */
@RunWith(RobolectricTestRunner::class)
class YomitanDefinitionsViewRenderSeqTest {

    private fun view(): YomitanDefinitionsView = YomitanDefinitionsView(
        RuntimeEnvironment.getApplication(),
        DefinitionsDocument.Tokens(
            text = 0xFFEFEFEF.toInt(),
            textMuted = 0xFFA0A0A0.toInt(),
            textHint = 0xFF606060.toInt(),
            accent = 0xFF00BCD4.toInt(),
            panel = 0xFF242424.toInt(),
            baseFontSizePx = 14f,
        ),
    )

    @Test
    fun `first bind's report cannot activate the second bind`() {
        val v = view()
        assumeTrue("Robolectric image without a shadow WebView", v.isUsable())
        v.setContent("<div>word A</div>", emptyMap(), "ja") // gen 1
        v.setContent("<div>word B</div>", emptyMap(), "ja") // gen 2
        // Swap A's two-frame callback lands late, stamped gen 1: stale.
        assertFalse(v.acceptsHeightReport(1))
        // Swap B's own report is the one allowed through.
        assertTrue(v.acceptsHeightReport(2))
    }

    @Test
    fun `reports predating any bind are dropped`() {
        val v = view()
        assumeTrue("Robolectric image without a shadow WebView", v.isUsable())
        // The shell's resize listener can fire before the first swap and
        // reports gen 0 — never valid once a bind is expected.
        v.setContent("<div>word A</div>", emptyMap(), "ja")
        assertFalse(v.acceptsHeightReport(0))
    }

    @Test
    fun `a rebind of the same content re-arms on its own generation`() {
        val v = view()
        assumeTrue("Robolectric image without a shadow WebView", v.isUsable())
        v.setContent("<div>word A</div>", emptyMap(), "ja") // gen 1
        v.setContent("<div>word A</div>", emptyMap(), "ja") // gen 2 (deck-badge rebind)
        assertFalse(v.acceptsHeightReport(1))
        assertTrue(v.acceptsHeightReport(2))
    }
}
