package com.playtranslate

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The delivery layer's panel-content record — each rule pins a shipped or
 * review-caught bug ([LivePanelRecord] kdoc carries the history). The
 * commit-only-on-screen-derived-delivery rule is wiring
 * ([CaptureService.translateAndSendToPanel] commits, [CaptureService.emitResult]
 * deliberately does not) and is pinned by comments at both sites.
 */
class LivePanelRecordTest {

    private val ja = "JA>en"

    @Test fun `fresh record accepts anything`() {
        val r = LivePanelRecord()
        assertTrue(r.isNew(0, "扉は固く閉ざされている", ja))
    }

    @Test fun `delivered content is a duplicate, jitter included`() {
        val r = LivePanelRecord()
        r.committed(0, "夜になると魔物が現れるという話だ", ja)
        assertFalse("exact repeat", r.isNew(0, "夜になると魔物が現れるという話だ", ja))
        // Same length, one misread glyph — OCR jitter, not new content.
        assertFalse("same-length jitter", r.isNew(0, "夜になると魔物が現れるという話た", ja))
    }

    @Test fun `growth is content — the stuck-partial pin`() {
        val r = LivePanelRecord()
        // A beat-pause delivered the partial; the completion's tail sits
        // inside the bag-of-chars tolerance but MUST deliver.
        r.committed(0, "扉が開く", ja)
        assertTrue(r.isNew(0, "扉が開くよ", ja))
    }

    @Test fun `small shrink is jitter, significant shrink is content`() {
        val r = LivePanelRecord()
        r.committed(0, "回復薬を手に入れた！", ja)
        // Typewriters never shrink — a glyph dropout must not flap the
        // panel with a truncated read and then the repair.
        assertFalse("dropout shrink suppressed", r.isNew(0, "回復薬を手に入れた", ja))
        // A genuinely different, shorter message still delivers.
        assertTrue("real shorter message delivers", r.isNew(0, "はい", ja))
    }

    @Test fun `clear makes identical text new again — the no-text pin`() {
        val r = LivePanelRecord()
        r.committed(0, "回復薬を手に入れた！", ja)
        assertFalse(r.isNew(0, "回復薬を手に入れた！", ja))
        // Screen went no-text; the panel no longer shows the recorded text.
        r.clear()
        assertTrue("reappearing text must deliver", r.isNew(0, "回復薬を手に入れた！", ja))
    }

    @Test fun `a language change invalidates the record`() {
        val r = LivePanelRecord()
        r.committed(0, "この先、危険につき立入禁止", "JA>en")
        assertTrue("same text, new target pair", r.isNew(0, "この先、危険につき立入禁止", "JA>fr"))
    }

    @Test fun `displays dedup independently — the ping-pong pin`() {
        val r = LivePanelRecord()
        // Two live displays feed one panel. Each must compare against its
        // OWN last delivery: with a single slot, A's commit made B's
        // unchanged text look new (and vice versa) — the two loops
        // re-emitted every settled cycle forever.
        r.committed(0, "上画面の台詞", ja)
        r.committed(1, "下画面のメニュー", ja)
        assertFalse("display 0 stays deduped", r.isNew(0, "上画面の台詞", ja))
        assertFalse("display 1 stays deduped", r.isNew(1, "下画面のメニュー", ja))
    }
}
