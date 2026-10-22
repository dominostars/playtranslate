package com.playtranslate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the furigana tier's change gate, the pure halves:
 * [ChangeGateRules.decide], the order the gate consults its facts in,
 * [GateTick.skips], the one rule for whether a tick is dropped, and
 * [ChangeGateLedger], the tally of reads, skips and waste. The bitmap
 * sampling shell ([FuriganaChangeGate]) rides [OutsideChangeGate] and is
 * exercised on a device.
 */
class FuriganaChangeGateTest {

    private fun verdict(
        fired: Boolean = false,
        settle: Boolean = false,
        volatile: Int = 0,
    ) = OutsideBlockGrid.Verdict(
        fired = fired,
        firedBlocks = if (fired) 1 else 0,
        pendingSettle = settle,
        volatileBlocks = volatile,
    )

    private fun decide(
        v: OutsideBlockGrid.Verdict,
        hasAnchor: Boolean = true,
        reveal: Boolean = false,
        panel: Boolean = false,
        skips: Int = 0,
    ) = ChangeGateRules.decide(hasAnchor, reveal, panel, skips, v)

    // ── Rules ──────────────────────────────────────────────────────────

    @Test
    fun `no anchor seeds, whatever the verdict`() {
        assertEquals(GateReason.SEED, decide(verdict(fired = true), hasAnchor = false))
        assertEquals(GateReason.SEED, decide(verdict(), hasAnchor = false, reveal = true, panel = true, skips = 99))
    }

    @Test
    fun `a pixel verdict names itself before the bypasses`() {
        assertEquals(GateReason.DIFFERS, decide(verdict(fired = true, volatile = 3), reveal = true, panel = true, skips = 99))
        assertEquals(GateReason.VOLATILE, decide(verdict(volatile = 3), reveal = true, panel = true, skips = 99))
    }

    @Test
    fun `a volatile block reads, over motion and without a bypass`() {
        assertEquals(GateReason.VOLATILE, decide(verdict(volatile = 1)))
        assertEquals(GateReason.VOLATILE, decide(verdict(settle = true, volatile = 1)))
    }

    @Test
    fun `a reveal reads before the waiting panel, the net and over motion`() {
        assertEquals(GateReason.REVEAL, decide(verdict(settle = true), reveal = true, panel = true, skips = 99))
    }

    @Test
    fun `a waiting panel reads before the net and over motion`() {
        assertEquals(GateReason.CONFIRM, decide(verdict(settle = true), panel = true, skips = 99))
        assertEquals(GateReason.CONFIRM, decide(verdict(), panel = true))
    }

    @Test
    fun `the net reads at twenty skips, not before`() {
        assertEquals(GateReason.NET, decide(verdict(settle = true), skips = ChangeGateRules.NET_EVERY_SKIPS))
        assertEquals(GateReason.MOVING, decide(verdict(settle = true), skips = ChangeGateRules.NET_EVERY_SKIPS - 1))
    }

    @Test
    fun `skips are classed moving, then quiet`() {
        assertEquals(GateReason.MOVING, decide(verdict(settle = true)))
        assertEquals(GateReason.QUIET, decide(verdict()))
    }

    @Test
    fun `reads and skips are the two halves of the reasons`() {
        assertEquals(
            setOf(
                GateReason.SEED, GateReason.CLEAN, GateReason.DIFFERS, GateReason.VOLATILE,
                GateReason.REVEAL, GateReason.CONFIRM, GateReason.NET,
            ),
            GateReason.entries.filter { it.reads }.toSet(),
        )
    }

    // ── The tick's word ────────────────────────────────────────────────

    @Test
    fun `a tick is dropped exactly when its reason does not read`() {
        for (reason in GateReason.entries) {
            assertEquals("$reason", !reason.reads, GateTick(reason, "").skips)
        }
    }

    // ── Ledger ─────────────────────────────────────────────────────────

    private var now = 0L
    private val ledger = ChangeGateLedger { now }

    @Test
    fun `a read that found a change is neither waste nor a skip`() {
        assertEquals("", ledger.record(GateReason.DIFFERS, OcrVerdict.KANJI_CHANGED))
        assertEquals(1, ledger.reads)
        assertEquals(0, ledger.skips)
        assertEquals(0, ledger.waste)
    }

    @Test
    fun `a read that found nothing is waste, by its reason`() {
        assertEquals(" WASTE", ledger.record(GateReason.DIFFERS, OcrVerdict.SETTLED))
        assertEquals(" WASTE", ledger.record(GateReason.NET, OcrVerdict.KANA_SHIFT))
        assertEquals(" WASTE", ledger.record(GateReason.CLEAN, OcrVerdict.NO_TEXT))
        assertEquals(3, ledger.waste)
        assertTrue(ledger.summary(), ledger.summary().contains("waste=3 (clean 1, differs 1, net 1)"))
    }

    @Test
    fun `a skipped tick carries no verdict and is never waste`() {
        assertEquals("", ledger.record(GateReason.QUIET, null))
        assertEquals("", ledger.record(GateReason.MOVING, null))
        assertEquals(2, ledger.skips)
        assertEquals(0, ledger.reads)
        assertEquals(0, ledger.waste)
        assertTrue(ledger.summary(), ledger.summary().contains("skips=2 (moving 1, quiet 1)"))
    }

    @Test
    fun `the summary counts reads by reason and reads a minute`() {
        now = 0
        val l = ChangeGateLedger { now }
        l.record(GateReason.SEED, OcrVerdict.FIRST)
        l.record(GateReason.CLEAN, OcrVerdict.FIRST)
        l.record(GateReason.QUIET, null)
        l.record(GateReason.REVEAL, OcrVerdict.EVOLVING)
        now = 120_000
        val s = l.summary()
        assertTrue(s, s.contains("ticks=4 reads=3 (seed 1, clean 1, reveal 1) skips=1 (quiet 1) waste=0 ()"))
        assertTrue(s, s.contains("reads/min=1.5 over 2.0 min"))
    }
}
