package com.playtranslate.translationlog

import android.graphics.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Pins the translation-log write gate on the canonical auto-mode noise
 * patterns, in BOTH script classes (char-metric JA, word-metric EN) — a
 * JA-tuned predicate proves nothing for Latin pairs and vice versa.
 * Thresholds themselves are provisional pending real-trace sweeps; these
 * tests pin the SHAPE of the policy: what kind of text falls to which
 * decision, not the exact constant values.
 */
@RunWith(RobolectricTestRunner::class)
class LogWriteGateTest {

    private val dialogueBox = Rect(100, 800, 1800, 1000)
    private val hudCorner = Rect(1600, 20, 1900, 80)

    private fun ja() = LogWriteGate("ja")
    private fun en() = LogWriteGate("en")

    // ── Sentence-ness ────────────────────────────────────────────────────

    @Test
    fun hudChurnIsSuppressed_bothScripts() {
        // Clocks/dates/counters: digits are never content, any script.
        assertFalse(LogWriteGate.isSentenceLike("12:41", "ja"))
        assertFalse(LogWriteGate.isSentenceLike("12:41", "en"))
        assertFalse(LogWriteGate.isSentenceLike("7月11日", "ja"))
        assertFalse(LogWriteGate.isSentenceLike("Jul 11", "en"))
        assertFalse(LogWriteGate.isSentenceLike("x 3", "en"))
        assertFalse(LogWriteGate.isSentenceLike("HP 320/450", "en"))
    }

    @Test
    fun dialogueIsAdmitted_bothScripts() {
        assertTrue(LogWriteGate.isSentenceLike("こんにちは、世界のみなさん", "ja"))
        // Short but terminal-punctuated dialogue stays in.
        assertTrue(LogWriteGate.isSentenceLike("はい。", "ja"))
        assertTrue(LogWriteGate.isSentenceLike("Hello there, traveler", "en"))
        assertTrue(LogWriteGate.isSentenceLike("Got it.", "en"))
        // Closing quotes don't hide the terminal.
        assertTrue(LogWriteGate.isSentenceLike("「行きましょう。」", "ja"))
    }

    @Test
    fun bareNamesFallBelowTheFloor() {
        // Name-box churn: a 3-char name is not a log entry. Conscious
        // acceptance — speaker attribution is a future pairing feature,
        // not a log entry class.
        assertFalse(LogWriteGate.isSentenceLike("アリス", "ja"))
        assertFalse(LogWriteGate.isSentenceLike("Alice", "en"))
    }

    // ── Supersession (typewriter reveal that beat the hold) ─────────────

    @Test
    fun typewriterTrailCollapsesToOneEntry_ja() {
        val g = ja()
        // Partial fragment below the floor: suppressed, no trail entry.
        val d0 = g.offer("こんにち", dialogueBox, atMs = 0, cycle = 1)
        assertTrue(d0 is LogWriteGate.Decision.Suppress)

        val d1 = g.offer("こんにちは、世界", dialogueBox, atMs = 500, cycle = 2)
        assertTrue(d1 is LogWriteGate.Decision.Append)

        val d2 = g.offer("こんにちは、世界のみなさん。", dialogueBox, atMs = 1000, cycle = 3)
        val replace = d2 as LogWriteGate.Decision.Replace
        assertEquals("こんにちは、世界", replace.previous.text)
        assertEquals("こんにちは、世界のみなさん。", replace.entry.text)
    }

    @Test
    fun typewriterTrailCollapsesToOneEntry_en() {
        val g = en()
        val d1 = g.offer("We should head to the", dialogueBox, 0, 1)
        assertTrue(d1 is LogWriteGate.Decision.Append)
        val d2 = g.offer("We should head to the northern gate.", dialogueBox, 400, 2)
        assertTrue(d2 is LogWriteGate.Decision.Replace)
    }

    @Test
    fun newLineInSameBoxAppends() {
        val g = ja()
        g.offer("こんにちは、世界のみなさん。", dialogueBox, 0, 1)
        // Dialogue advanced: same region, entirely different text — a new
        // entry, never a replacement.
        val d = g.offer("今日はいい天気ですね、出かけましょう。", dialogueBox, 3000, 5)
        assertTrue(d is LogWriteGate.Decision.Append)
    }

    @Test
    fun crossRegionGrowthCollapsesOntoFullestRead() {
        val g = en()
        g.offer("We should head to the northern gate", dialogueBox, 0, 1)
        // The containment tier is region-free BY DESIGN: world-anchored and
        // scrolling text moves between reads (real-trace: the same sign at
        // three screen positions), so the same content growing in a new
        // rect folds onto its fullest form instead of double-logging.
        val d = g.offer(
            "We should head to the northern gate before dark.", hudCorner, 500, 2,
        )
        assertEquals(
            "We should head to the northern gate before dark.",
            (d as LogWriteGate.Decision.Replace).entry.text,
        )
    }

    // ── Dedupe ───────────────────────────────────────────────────────────

    @Test
    fun repeatedLineIsSuppressedAsDuplicate() {
        val g = ja()
        g.offer("こんにちは、世界のみなさん。", dialogueBox, 0, 1)
        g.offer("今日はいい天気ですね、出かけましょう。", dialogueBox, 2000, 4)
        // Re-entering the scene re-commits the first line verbatim.
        val d = g.offer("こんにちは、世界のみなさん。", dialogueBox, 60_000, 90)
        assertEquals(
            LogWriteGate.SuppressReason.DUPLICATE,
            (d as LogWriteGate.Decision.Suppress).reason,
        )
    }

    @Test
    fun dedupeKeyIgnoresNonSourceGlyphs() {
        // OCR jitter on punctuation/whitespace must not defeat dedupe.
        val a = LogWriteGate.normalizedKey("こんにちは、世界。", "ja")
        val b = LogWriteGate.normalizedKey("こんにちは 、世界 。", "ja")
        assertEquals(a, b)
    }

    @Test
    fun keyFoldsOcrJitterEquivalentForms() {
        // Width flips (NFKC) and kana-size flips must not mint new keys —
        // both Luna and GSM fold before comparing for this exact reason.
        assertEquals(
            LogWriteGate.normalizedKey("メニューを開いてください。", "ja"),
            LogWriteGate.normalizedKey("ﾒﾆｭｰを開いてください。", "ja"),
        )
        assertEquals(
            LogWriteGate.normalizedKey("行きましょう。", "ja"),
            LogWriteGate.normalizedKey("行きましよう。", "ja"),
        )
    }

    @Test
    fun kanaSizeFlippedRepeatIsExactDuplicate() {
        val g = ja()
        assertTrue(
            g.offer("それでは、行きましょう。", dialogueBox, 0, 1)
                is LogWriteGate.Decision.Append,
        )
        val d = g.offer("それでは、行きましよう。", dialogueBox, 30_000, 20)
        assertEquals(
            LogWriteGate.SuppressReason.DUPLICATE,
            (d as LogWriteGate.Decision.Suppress).reason,
        )
    }

    // ── Deliberate entries (one-shot / lookup) ───────────────────────────

    @Test
    fun deliberateEntriesBypassNoiseGatesButShareExactDedupe() {
        val g = ja()
        // A bare name fails the auto sentence floor but is a deliberate
        // capture — the user asked for it.
        assertTrue(g.offerDeliberate("アリス", 0, 1) is LogWriteGate.Decision.Append)
        // Exact repeat dedupes...
        assertEquals(
            LogWriteGate.SuppressReason.DUPLICATE,
            (g.offerDeliberate("アリス", 1000, 2) as LogWriteGate.Decision.Suppress).reason,
        )
        // ...and the seen map is SHARED with the auto path: a deliberate
        // capture of a line auto mode already logged collapses too.
        g.offer("こんにちは、世界のみなさん。", dialogueBox, 2000, 3)
        assertEquals(
            LogWriteGate.SuppressReason.DUPLICATE,
            (g.offerDeliberate("こんにちは、世界のみなさん。", 3000, 4) as LogWriteGate.Decision.Suppress).reason,
        )
    }

    @Test
    fun deliberateEntriesNeverBecomeSupersessionTargets() {
        val g = ja()
        g.offerDeliberate("こんにちは、世界", 0, 1)
        // An auto commit that would look like typewriter growth of the
        // deliberate entry must APPEND, not replace it.
        val d = g.offer("こんにちは、世界のみなさんお元気ですか。", dialogueBox, 500, 2)
        assertTrue(d is LogWriteGate.Decision.Append)
    }

    // ── Near-duplicate collapse (OCR jitter re-reads) ────────────────────

    @Test
    fun jitterTripleCollapsesToOneEntry_evenWhileMovingAcrossScreen() {
        // First real-trace finding: the same shop sign read three ways at
        // three screen positions (camera tracks the player) — the tight
        // tier must be region-free or world-anchored text never collapses.
        val g = ja()
        assertTrue(
            g.offer("辰巳東交番", Rect(103, 284, 323, 312), 0, 1)
                is LogWriteGate.Decision.Append,
        )
        val d2 = g.offer("辰巳東交街", Rect(707, 312, 993, 357), 2000, 2)
        assertEquals(
            LogWriteGate.SuppressReason.NEAR_DUPLICATE,
            (d2 as LogWriteGate.Decision.Suppress).reason,
        )
        val d3 = g.offer("辰巳東交器", Rect(1189, 299, 1326, 323), 4000, 3)
        assertEquals(
            LogWriteGate.SuppressReason.NEAR_DUPLICATE,
            (d3 as LogWriteGate.Decision.Suppress).reason,
        )
    }

    @Test
    fun wholesaleGarbleCollapsesOnlyWhenRegionAgrees() {
        // Second real-trace finding: nav-bar re-garblings differ by ~35% of
        // their chars — beyond the tight tier — but sit at a fixed rect.
        val g = ja()
        val navBar = Rect(47, 1010, 1027, 1052)
        val a = "エロマップキログメールメニューEハダッシュロネットワークヨセーブ"
        val b = "エロマップイ・ログのメールのメニューダッュネルワークヨセーブ"
        assertTrue(g.offer(a, navBar, 0, 1) is LogWriteGate.Decision.Append)
        val d = g.offer(b, Rect(50, 1011, 1030, 1053), 5000, 2)
        assertEquals(
            LogWriteGate.SuppressReason.NEAR_DUPLICATE,
            (d as LogWriteGate.Decision.Suppress).reason,
        )
        // The same ~35%-distant pair in an unrelated region does NOT
        // collapse — the loose tier is same-region-only.
        val g2 = ja()
        assertTrue(g2.offer(a, navBar, 0, 1) is LogWriteGate.Decision.Append)
        assertTrue(
            g2.offer(b, Rect(300, 100, 900, 200), 5000, 2)
                is LogWriteGate.Decision.Append,
        )
    }

    @Test
    fun shortDistinctRepliesNeverCollapse() {
        // The safety property that rules out a fixed char tolerance:
        // consecutive short replies in the same box stay separate entries.
        val g = ja()
        assertTrue(g.offer("はい。", dialogueBox, 0, 1) is LogWriteGate.Decision.Append)
        assertTrue(g.offer("いいえ。", dialogueBox, 1000, 2) is LogWriteGate.Decision.Append)
    }

    // ── Region mute (near-dup density, never append count) ──────────────

    @Test
    fun garbleChurnMutesItsRegion_dialogueRegionUnaffected() {
        val g = ja()
        val navBar = Rect(0, 1000, 1900, 1060)
        val base = "マップログメールメニューダッシュネットワークセーブ"
        assertTrue(g.offer(base, navBar, 0, 1) is LogWriteGate.Decision.Append)
        // Three single-substitution garblings of the same bar → three
        // near-dups → the region mutes.
        for ((i, variant) in listOf(
            "ヌップログメールメニューダッシュネットワークセーブ", // substitution
            "マップログメールメニョーダッシュネットワークセーブ", // substitution
            "マップログメールメニューダッシュネットワークセーホ", // substitution
        ).withIndex()) {
            val d = g.offer(variant, navBar, (i + 1) * 1000L, i + 2)
            assertEquals(
                LogWriteGate.SuppressReason.NEAR_DUPLICATE,
                (d as LogWriteGate.Decision.Suppress).reason,
            )
        }
        // A COMPLETELY different string in the muted region is refused...
        val d = g.offer("全然違う新しい文章がここに出ます。", navBar, 9000, 9)
        assertEquals(
            LogWriteGate.SuppressReason.REGION_MUTED,
            (d as LogWriteGate.Decision.Suppress).reason,
        )
        // ...while the dialogue region is untouched by the mute.
        assertTrue(
            g.offer("会話はいつも通り記録されます。", dialogueBox, 9500, 10)
                is LogWriteGate.Decision.Append,
        )
    }

    @Test
    fun typewriterReRevealsNeverMuteTheDialogueBox() {
        // Second real-trace finding: typewriter re-reveals are legitimate
        // near-dups of the dialogue box; under count-all churn they muted
        // the box mid-session. Containment-class events must not churn.
        val g = ja()
        assertTrue(
            g.offer("こうやって、キャンプすんの", dialogueBox, 0, 1)
                is LogWriteGate.Decision.Append,
        )
        // Micro-growth within the near-dup budget: the fuller read WINS.
        val grown = g.offer("こうやって、キャンプすんのさ", dialogueBox, 500, 2)
        assertEquals(
            "こうやって、キャンプすんのさ",
            (grown as LogWriteGate.Decision.Replace).entry.text,
        )
        // Partial re-reveals (scroll-back / re-render) suppress, no churn.
        for ((i, partial) in listOf(
            "こうやって、キャン",
            "こうやって、キャンプす",
            "こうやってキャンプすん",
        ).withIndex()) {
            val d = g.offer(partial, dialogueBox, 1000L + i, 3 + i)
            assertEquals(
                LogWriteGate.SuppressReason.NEAR_DUPLICATE,
                (d as LogWriteGate.Decision.Suppress).reason,
            )
        }
        // Three containment events accrued zero churn: new dialogue in the
        // same box still appends — the box is NOT muted.
        assertTrue(
            g.offer("全く新しい台詞がここに続きます。", dialogueBox, 9000, 9)
                is LogWriteGate.Decision.Append,
        )
    }

    @Test
    fun deepPartialReRevealIsSuppressed_distanceFree() {
        // A partial can sit arbitrarily far from its full line by bag
        // distance — the containment tier must not depend on the budget.
        val g = ja()
        g.offer("「キャンプではさ色んな事が、できるんだよ・・", dialogueBox, 0, 1)
        val d = g.offer("「キャンプではさ色んな、", dialogueBox, 17_000, 8)
        assertEquals(
            LogWriteGate.SuppressReason.NEAR_DUPLICATE,
            (d as LogWriteGate.Decision.Suppress).reason,
        )
    }

    @Test
    fun shortLinesAreNotSwallowedByLongLinesContainingTheirChars() {
        val g = ja()
        assertTrue(g.offer("はいはい。", dialogueBox, 0, 1) is LogWriteGate.Decision.Append)
        // Contains は+い twice over, but the short entry is far below the
        // containment ratio — it must neither be replaced nor collapse.
        val d = g.offer(
            "はい、それはいいですね。今日は早く行きましょう。", dialogueBox, 3000, 2,
        )
        assertTrue(d is LogWriteGate.Decision.Append)
    }

    @Test
    fun dialogueBoxNeverMutes() {
        // Many pairwise-distant appends accrue zero near-dups.
        val g = ja()
        val lines = listOf(
            "怪事件の被害者が、遂に月高からも出たそうね。",
            "被害者が校舎正門で倒れていた以上、そこが現場である可能性が高いわ。",
            "あるいは別の場所で被害を受けて、それから正門に運ばれたとしても…",
            "何故犯人は、そんな事をしたのか?",
            "いずれにせよこの事件、月高が絡んでる可能性があるわね…",
            "そういえば、今日の放課後は空いてる?",
        )
        for ((i, line) in lines.withIndex()) {
            assertTrue(
                "line $i must append",
                g.offer(line, dialogueBox, i * 3000L, i + 1) is LogWriteGate.Decision.Append,
            )
        }
    }
}
