package com.playtranslate

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The stateless half of the panel policy ([panelOfferEligible]): which
 * frames get OFFERED to the delivery layer. Reveal prefixes never
 * (typewriter growth sits inside the significant-change jitter tolerance,
 * so growth has its own rule), boundary-complete reads immediately
 * (TypewriterGate's zero-latency release, shared [SentenceBoundary]
 * predicate), settled frames every cycle ([LivePanelRecord] dedups).
 */
class PanelOfferEligibleTest {

    @Test fun `reveal prefixes are never offered`() {
        val reveal = "彼は長い沈黙のあとで真実を語り始めた"
        var at = 2
        while (at < reveal.length) {
            assertFalse(
                "prefix must not be offered: ${reveal.take(at)}",
                panelOfferEligible(reveal.take(at - 2).ifEmpty { null }, reveal.take(at), "ja"),
            )
            at += 2
        }
        assertTrue("settled repeat offers", panelOfferEligible(reveal, reveal, "ja"))
    }

    @Test fun `first frame has nothing to compare — not offered`() {
        assertFalse(panelOfferEligible(null, "セーブしますか？", "ja"))
    }

    @Test fun `boundary-complete growth is offered immediately`() {
        assertFalse(panelOfferEligible("少年は静かに言", "少年は静かに言った", "ja"))
        assertTrue(
            "the read landing on 。 IS the completed message",
            panelOfferEligible("少年は静かに言った", "少年は静かに言った。", "ja"),
        )
    }

    @Test fun `multi-sentence reveal offers each completed sentence`() {
        assertTrue(panelOfferEligible("戦いは終わっ", "戦いは終わった。", "ja"))
        assertFalse(panelOfferEligible("戦いは終わった。", "戦いは終わった。そして朝", "ja"))
        assertTrue(panelOfferEligible("戦いは終わった。そして朝", "戦いは終わった。そして朝が来た。", "ja"))
    }

    @Test fun `interior growth beside a static punct-final line is not offered`() {
        // Joined frame text ends with a STATIC punct-final box while the
        // first box is still typing — suffix-growth fails, no early offer.
        assertFalse(panelOfferEligible("台詞\n終わり。", "台詞が伸\n終わり。", "ja"))
        // It still settles the ordinary way.
        assertTrue(panelOfferEligible("台詞が伸\n終わり。", "台詞が伸\n終わり。", "ja"))
    }

    @Test fun `no-terminal-convention language falls back to settled repeat`() {
        assertFalse(panelOfferEligible("ข้อความ", "ข้อความจบ.", "th"))
        assertTrue(panelOfferEligible("ข้อความจบ.", "ข้อความจบ.", "th"))
    }

    @Test fun `same-length jitter on a settled screen still offers`() {
        assertTrue(panelOfferEligible("回復薬を手に入れた！", "回復菜を手に入れた！", "ja"))
    }

    @Test fun `a content swap holds one cycle then offers`() {
        assertFalse(
            "new message must hold one cycle",
            panelOfferEligible("回復薬を手に入れた！", "扉は固く閉ざされている", "ja"),
        )
        assertTrue(panelOfferEligible("扉は固く閉ざされている", "扉は固く閉ざされている", "ja"))
    }
}
