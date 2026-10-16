package com.playtranslate

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import java.util.EnumMap

/**
 * The furigana tier's change gate in front of its read ([FuriganaMode]): a
 * raw tick is read, on PlayTranslate Cloud or on the device, only when this
 * gate says so ([GateTick.skips]); a requested clean frame is always read.
 * The gate was first measured in shadow beside the device OCR (the stage 0
 * run, 2026-10-15), where a skip could be checked against what the OCR
 * found; enforced, a skipped tick reads nothing, so the tally counts reads,
 * skips and waste, never misses.
 *
 * The gate's premise is that unchanged pixels mean an unchanged read. It
 * skips only where that holds: nothing differs ([GateReason.QUIET]), or only
 * moving blocks differ and the frame is a smear ([GateReason.MOVING], which
 * claims one floor-paced follow-up). A screen with a persistently animating
 * block reads at full cadence ([GateReason.VOLATILE]), as the grid's
 * contract asks and the pinhole tier does: text inside such a block is
 * invisible to pixel evidence, and the live-mode rule is that no content
 * class waits longer than the shipped cadence. The tier's own facts bypass
 * the pixels the other way, forcing a read the pixels would not: a
 * typewriter hold to release ([GateReason.REVEAL]), a panel waiting for the
 * confirming read that offers it the page ([GateReason.CONFIRM]), and a
 * net after [ChangeGateRules.NET_EVERY_SKIPS] skips ([GateReason.NET]).
 * Everything that changes what a read returns without changing pixels (an
 * OCR engine or filter switch, a language or region change) must reach the
 * tier as a refresh, which resets this gate ([reset]) so the next tick
 * seeds and reads.
 *
 * The verdict is [ChangeGateRules.decide] over [OutsideChangeGate] in grid
 * mode ([OutsideBlockGrid]): a strided, brightness-normalized luma diff of
 * the bitmap the OCR reads (the patched raw frame, or the raw frame where no
 * patch applies, or a requested clean frame) against an anchor, the bitmap
 * of the last tick read, with this app's own chrome excluded on contaminated
 * streams. The patch holds the ruby rects equal between frames (they carry
 * the clean reference's pixels on every tick), so they need no exclusion and
 * the grid's block membership holds across rebuilds. The anchor moves on
 * every read, the grid is fed on every tick, read or skipped, so its motion
 * state stays continuous, and a requested clean frame is always a read
 * ([cleanRead]).
 *
 * Every tick is tallied ([ChangeGateLedger]): a read whose OCR saw nothing is
 * WASTE, by the reason it read for; [summary] at stop is the measurement.
 * Per-tick lines go to [DetectionLog] while [verbose] says so (debug live
 * mode). Main thread, like the tier. The gate's own cost is about a megabyte
 * of row reads per tick and a bitmap copy per read tick; a skip saves the
 * whole read, on the device the OCR pass itself.
 */
class FuriganaChangeGate(
    private val displayId: Int,
    private val log: (String) -> Unit = DetectionLog::log,
    nowMs: () -> Long = SystemClock::uptimeMillis,
    private val verbose: () -> Boolean = { true },
) {
    private var anchor: Bitmap? = null
    private val buffers = OutsideChangeGate.Buffers()
    private val grid = OutsideBlockGrid()
    private var skipStreak = 0
    private var lastServedSeq: Long? = null
    val ledger = ChangeGateLedger(nowMs)

    /** The last raw tick's stamped fact ([com.playtranslate.capture.CapturedFrame.includesOwnOverlays]):
     *  a clean frame of the same stream excludes the chrome rects too, so the
     *  grid's block membership is the same on both kinds of read. */
    var contaminated: Boolean = false
        private set

    /**
     * One raw tick on [bitmap], the bitmap the OCR is about to read, sampled
     * over [crop] with [exclude] (bitmap coordinates) left out. The verdict is
     * returned as a [GateTick]; the caller hands it back with what the OCR
     * found ([report]), or with nothing when it skipped the tick.
     * [revealInFlight] and [panelWaiting] are the tier's two bypasses (a
     * typewriter hold to release, a panel offer that needs a confirming
     * read). [servedSeq] is the MediaProjection delivery cursor at this
     * tick, null off that stream; whether it moved since the last tick goes
     * in the log line only.
     */
    fun tick(
        bitmap: Bitmap,
        crop: Rect,
        exclude: List<Rect>,
        includesOwnOverlays: Boolean,
        revealInFlight: Boolean,
        panelWaiting: Boolean,
        servedSeq: Long?,
    ): GateTick {
        contaminated = includesOwnOverlays
        val delivery = when {
            servedSeq == null -> "n/a"
            lastServedSeq == null -> "first"
            servedSeq != lastServedSeq -> "new"
            else -> "same"
        }
        lastServedSeq = servedSeq
        val current = anchor
        val hasAnchor = current != null && current.width == bitmap.width && current.height == bitmap.height
        if (!hasAnchor) {
            // Dimensions changed under a kept anchor: the grid's blocks are
            // void too. The seed tick samples the bitmap against itself, so
            // the grid seeds its sums now and the next tick can fire.
            if (current != null) grid.reset()
            setAnchor(bitmap)
        }
        val result = sample(bitmap, crop, exclude)
        val reason = ChangeGateRules.decide(hasAnchor, revealInFlight, panelWaiting, skipStreak, grid.lastVerdict)
        if (reason.reads) {
            skipStreak = 0
            if (hasAnchor) setAnchor(bitmap)
        } else {
            skipStreak++
        }
        val v = grid.lastVerdict
        return GateTick(
            reason,
            "fired=${v.firedBlocks} mv=${v.movingBlocks} vol=${v.volatileBlocks} " +
                "outside ${result.changedSamples}/${result.totalSamples} ${result.fitLabel()} " +
                "deliv=$delivery streak=$skipStreak",
        )
    }

    /** A requested clean frame, which the tier always reads: the anchor
     *  moves to it and the grid is fed from it. */
    fun cleanRead(bitmap: Bitmap, crop: Rect, exclude: List<Rect>): GateTick {
        if (anchor?.let { it.width != bitmap.width || it.height != bitmap.height } == true) grid.reset()
        setAnchor(bitmap)
        sample(bitmap, crop, exclude)
        skipStreak = 0
        return GateTick(GateReason.CLEAN, "")
    }

    /** The tier's OCR verdict for [tick], or null when the tier skipped the
     *  tick on this gate's word: tallied, and logged while [verbose]. Once per
     *  tick; a tick whose read was cancelled never reports. */
    fun report(tick: GateTick, verdict: OcrVerdict?) {
        if (tick.reported) return
        tick.reported = true
        val outcome = ledger.record(tick.reason, verdict)
        if (!verbose()) return
        val word = if (tick.reason.reads) "READ" else "skip"
        val ocr = verdict?.name?.lowercase() ?: "-"
        log("D$displayId gate: $word ${tick.reason.name.lowercase()} ${tick.detail} | ocr=$ocr$outcome")
    }

    /** Forgets the anchor and the grid's state: the next tick seeds. For the
     *  tier's geometry resets and restarts, never for a no-text read, after
     *  which the textless screen is exactly what the anchor should hold. */
    fun reset() {
        anchor?.recycle()
        anchor = null
        grid.reset()
        skipStreak = 0
    }

    fun release() = reset()

    fun summary(): String = ledger.summary()

    private fun setAnchor(bitmap: Bitmap) {
        val copy = bitmap.copy(bitmap.config ?: Bitmap.Config.ARGB_8888, false)
        anchor?.recycle()
        anchor = copy
    }

    private fun sample(bitmap: Bitmap, crop: Rect, exclude: List<Rect>): OutsideChangeGate.Result =
        OutsideChangeGate.check(
            bitmap, anchor!!, crop,
            exclude.map { OutsideChangeGate.Exclusion(it) },
            buffers, grid,
        )
}

/** One tick's verdict, handed back to [FuriganaChangeGate.report] with what
 *  the OCR found. [detail] is the log line's diagnostics. */
class GateTick(val reason: GateReason, val detail: String) {
    internal var reported = false

    /** The tier skips this tick: nothing is read. */
    val skips: Boolean get() = !reason.reads
}

/** What the gate does with one tick, and why. [reads] is the outcome: upload
 *  and read, or skip. */
enum class GateReason(val reads: Boolean) {
    /** No anchor yet (the first tick, or the first after a reset): read, and anchor. */
    SEED(true),
    /** A requested clean frame: always read. */
    CLEAN(true),
    /** A still block differs from the anchor. */
    DIFFERS(true),
    /** Some block animates persistently: text inside it is invisible to the
     *  gate, so the tier reads at full cadence while it lasts. */
    VOLATILE(true),
    /** A frontier hold is open: the typewriter bypass. */
    REVEAL(true),
    /** The panel is waiting for the text on screen: the confirming read
     *  that offers it (a page's first build never offers; the settled
     *  repeat does). */
    CONFIRM(true),
    /** [ChangeGateRules.NET_EVERY_SKIPS] skips in a row: the reconcile read. */
    NET(true),
    /** Only moving blocks differ: the frame is a smear, the grid holds. */
    MOVING(false),
    /** Nothing differs. */
    QUIET(false),
}

/** What the tier's OCR found on the same tick, as the tier classifies it;
 *  [change] is whether the tier needed that read. */
enum class OcrVerdict(val change: Boolean) {
    /** No text, and there was none before. */
    NO_TEXT(false),
    /** No text where there was text: the overlay comes down. */
    TEXT_GONE(true),
    /** A raw tick after a kanji change, before the requested clean frame: nothing is done with it. */
    AWAITING_CLEAN(false),
    /** The first build, or the first after a clean capture. */
    FIRST(true),
    /** A typewriter step. */
    EVOLVING(true),
    /** The raw path's kanji change: selective invalidation and a clean capture. */
    KANJI_CHANGED(true),
    /** The clean path's significant change: a rebuild. */
    CHANGED(true),
    /** Text unchanged under an open hold: the read that releases the frontier's ruby. */
    RELEASE(true),
    /** Text unchanged: the dedup fast path, or a settled repeat. */
    SETTLED(false),
    /** A change below the kanji gate (kana, jitter): nothing is done with it. */
    KANA_SHIFT(false),
}

/** The gate's decision, pure: the grid's verdict for this tick and the
 *  tier's facts, in the order they are consulted. A pixel verdict that reads
 *  ([GateReason.DIFFERS], [GateReason.VOLATILE]) names itself before the
 *  bypasses, so a tick counted under [GateReason.REVEAL],
 *  [GateReason.CONFIRM] or [GateReason.NET] read for that reason alone. */
object ChangeGateRules {
    /** Skips in a row before a reconcile read: the furigana tier's own
     *  stale-reference net, 20 raw frames without a detected change. */
    const val NET_EVERY_SKIPS = 20

    fun decide(
        hasAnchor: Boolean,
        revealInFlight: Boolean,
        panelWaiting: Boolean,
        skipStreak: Int,
        verdict: OutsideBlockGrid.Verdict,
    ): GateReason = when {
        !hasAnchor -> GateReason.SEED
        verdict.fired -> GateReason.DIFFERS
        verdict.volatileBlocks > 0 -> GateReason.VOLATILE
        revealInFlight -> GateReason.REVEAL
        panelWaiting -> GateReason.CONFIRM
        skipStreak >= NET_EVERY_SKIPS -> GateReason.NET
        verdict.pendingSettle -> GateReason.MOVING
        else -> GateReason.QUIET
    }
}

/**
 * The gate's tally, pure: ticks by reason, and WASTE, a reading tick whose
 * OCR saw nothing, by the reason it read for; a bypass read
 * ([GateReason.REVEAL], [GateReason.CONFIRM], [GateReason.NET],
 * [GateReason.CLEAN], [GateReason.SEED]) counts there too, so the summary
 * says what each rule cost. A skipped tick carries no verdict (nothing was read), so what a skip
 * hid is not measured here; the shadow run that measured it is on record.
 */
class ChangeGateLedger(private val nowMs: () -> Long) {
    var ticks = 0
        private set
    private val byReason = EnumMap<GateReason, Int>(GateReason::class.java)
    private val wasteByReason = EnumMap<GateReason, Int>(GateReason::class.java)
    private val startedAtMs = nowMs()

    val reads: Int get() = byReason.entries.sumOf { if (it.key.reads) it.value else 0 }
    val skips: Int get() = ticks - reads
    val waste: Int get() = wasteByReason.values.sum()

    /** Records one tick and returns the log line's suffix: empty or " WASTE".
     *  [ocr] is what the read found, null for a skipped tick. */
    fun record(reason: GateReason, ocr: OcrVerdict?): String {
        ticks++
        byReason[reason] = (byReason[reason] ?: 0) + 1
        if (reason.reads && ocr != null && !ocr.change) {
            wasteByReason[reason] = (wasteByReason[reason] ?: 0) + 1
            return " WASTE"
        }
        return ""
    }

    fun summary(): String {
        val minutes = (nowMs() - startedAtMs) / 60_000.0
        fun counts(map: Map<GateReason, Int>, reads: Boolean?) = map.entries
            .filter { reads == null || it.key.reads == reads }
            .filter { it.value > 0 }
            .joinToString(", ") { "${it.key.name.lowercase()} ${it.value}" }
        return "change gate: ticks=$ticks reads=$reads (${counts(byReason, true)}) " +
            "skips=$skips (${counts(byReason, false)}) waste=$waste (${counts(wasteByReason, null)}) " +
            "reads/min=${if (minutes > 0) "%.1f".format(java.util.Locale.US, reads / minutes) else "-"} over %.1f min".format(java.util.Locale.US, minutes)
    }
}
