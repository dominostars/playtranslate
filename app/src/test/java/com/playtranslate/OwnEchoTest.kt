package com.playtranslate

import android.graphics.Rect
import com.playtranslate.ui.TextBox
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * [OverlayToolkit.findsOwnEcho] — the contamination tripwire's geometry.
 * The text predicate has its own suite ([EchoDetectionTest]); these traces
 * pin WHERE the echo must be looked for: at the rects the presenter actually
 * PAINTS, not just the anchor rect. The furigana cases are the round-11
 * finding (2026-07-10): annotations render outside the anchor, so a
 * false-CLEAN stream shows them to OCR as a phantom group that intersects no
 * anchor at all.
 *
 * Runs under Robolectric for [android.graphics.Rect].
 */
@RunWith(RobolectricTestRunner::class)
class OwnEchoTest {

    private val baseRect = Rect(0, 100, 400, 160)
    /** Where furigana paints for [baseRect]: strictly above, no overlap. */
    private val annotationRect = Rect(0, 55, 400, 95)

    private fun box(rect: Rect, translated: String, source: String) = TextBox(
        translatedText = translated,
        bounds = rect,
        sourceText = source,
        lineCount = 1,
    )

    private fun group(text: String, rect: Rect) = OcrManager.OcrGroup(text, rect)

    /** Painted geometry of the translation flavor: the anchor itself. */
    private val anchorOnly: (TextBox) -> List<TextBox> = { listOf(it) }

    // ── Translation flavor: behavior identical to the historical check ────

    @Test
    fun translationEcho_atAnchorRect_detected() {
        val anchor = box(baseRect, "I understand", "確認しました")
        val groups = listOf(group("確認しましたhunderstandhd", baseRect))
        assertTrue(OverlayToolkit.findsOwnEcho(groups, listOf(anchor), anchorOnly))
    }

    @Test
    fun translationText_elsewhereOnScreen_notEcho() {
        // The game legitimately shows the same words somewhere we don't paint.
        val anchor = box(baseRect, "I understand", "確認しました")
        val groups = listOf(group("I understand", Rect(0, 600, 400, 660)))
        assertFalse(OverlayToolkit.findsOwnEcho(groups, listOf(anchor), anchorOnly))
    }

    @Test
    fun nonDiscriminatingBox_translationEqualsSource_skipped() {
        // Numbers/names: OCR reading "1204" at the box proves nothing.
        val anchor = box(baseRect, "1204", "1204")
        val groups = listOf(group("1204", baseRect))
        assertFalse(OverlayToolkit.findsOwnEcho(groups, listOf(anchor), anchorOnly))
    }

    // ── Furigana flavor: the round-11 hole and its fix ─────────────────────

    /** Anchor at the base-text rect; readings joined as the payload; the
     *  painted annotation box sits strictly above the anchor. */
    private val furiganaAnchor = box(baseRect, "かんじのよみかたです", "漢字の読み方です")
    private val furiganaPainted: (TextBox) -> List<TextBox> = {
        listOf(box(annotationRect, "かんじのよみかたです", ""))
    }

    @Test
    fun furiganaPhantom_atAnnotationRect_detected() {
        // False-CLEAN stream: OCR reads our painted readings as their own
        // group above the line — intersecting no anchor.
        val phantom = listOf(group("かんじのよみかたです", annotationRect))
        assertTrue(
            OverlayToolkit.findsOwnEcho(phantom, listOf(furiganaAnchor), furiganaPainted)
        )
        // The historical anchor-only geometry misses exactly this — the hole
        // this suite exists to keep closed.
        assertFalse(
            OverlayToolkit.findsOwnEcho(phantom, listOf(furiganaAnchor), anchorOnly)
        )
    }

    @Test
    fun furiganaMerged_baseGroupGrownOverAnnotations_detected() {
        // The other manifestation: OCR merges our readings into the base
        // line's group (bounds grow upward over the annotation area). Anchor
        // intersection already catches this one — documented so a future
        // "simplify to painted-only" keeps both covered.
        val merged = listOf(
            group("かんじのよみかたです\n漢字の読み方です", Rect(0, 55, 400, 160))
        )
        assertTrue(
            OverlayToolkit.findsOwnEcho(merged, listOf(furiganaAnchor), furiganaPainted)
        )
    }

    @Test
    fun cleanStream_baseTextOnly_notEcho() {
        // True clean stream: OCR sees only the game's base text at the base
        // rect — readings never appear. The steady state must stay quiet.
        val groups = listOf(group("漢字の読み方です", baseRect))
        assertFalse(
            OverlayToolkit.findsOwnEcho(groups, listOf(furiganaAnchor), furiganaPainted)
        )
    }
}
