package com.playtranslate.cloud

import kotlin.math.roundToLong

/**
 * How many item lines the read after part 1 passed on its way to the final
 * part ("Incremental translations" in docs/api.md), and when, for
 * [PtCloudClocks.finalLine]. [firstMs] and [lastMs] are milliseconds from
 * the frame's send to the first and the last item's arrival. The items'
 * texts are not kept here: they are [FrameResponse.items]. Written and read
 * by the one coroutine that reads part 2, so nothing here is shared.
 */
internal class ItemLines {

    /** Item lines that decoded, duplicates included. */
    var count: Int = 0
        private set
    var firstMs: Long? = null
        private set
    var lastMs: Long? = null
        private set

    fun add(atMs: Long) {
        count++
        if (firstMs == null) firstMs = atMs
        lastMs = atMs
    }
}

/**
 * PlayTranslate Cloud's two clock lines per frame (docs/api.md, "Clocks";
 * docs/client-latency-handoff.md, item 5; docs/client-cache-handoff.md,
 * item 5), keyed by the frame id the client chose, which carries no content.
 * Numbers and status names only: never the key, the URL, a body, or any
 * recognized or translated text. A value the frame did not produce prints
 * as a dash.
 *
 * Both functions are total: no input makes them throw. [finalLine] runs
 * inside the read of part 2, a child of the caller's job that must never
 * fail, so a server number that is not finite (or a header that is not a
 * number) prints as a dash instead of reaching [roundToLong], which throws
 * on NaN.
 */
internal object PtCloudClocks {

    private const val DASH = "-"

    /**
     * The line at part 1. [headersMs] is the send to the response headers,
     * [part1Ms] the send to part 1 decoded, [translated] the number of part
     * 1's groups that carry a non-blank translation (the server's cache
     * answered them), [warmLeadMs] the time between the last warm call and
     * this frame's send (null when none was sent since the previous frame,
     * printed `none`). [connectionReused] and [uploadMs] are the HTTP stack's
     * (null when it never reported them). The two headers are the edge's
     * `x-pt-edge-upload-ms` and `x-pt-edge-ms`, as received; [timings] is
     * part 1's `timings`.
     */
    fun part1Line(
        frameId: String,
        format: FrameFormat,
        bytes: Int,
        width: Int,
        height: Int,
        encodeMs: Long,
        headersMs: Long,
        part1Ms: Long,
        groups: Int,
        translated: Int,
        connectionReused: Boolean?,
        uploadMs: Long?,
        warmLeadMs: Long?,
        edgeUploadHeader: String?,
        edgeHeader: String?,
        timings: Map<String, Double>,
    ): String =
        "part1 id=$frameId fmt=${format.wireName} bytes=$bytes size=${width}x$height encode=$encodeMs " +
            "hdr=$headersMs part1=$part1Ms groups=$groups tr1=$translated reused=${connectionReused ?: DASH} " +
            "upload=${uploadMs ?: DASH} warm=${warmLeadMs ?: "none"} " +
            "edge_upload=${header(edgeUploadHeader)} edge=${header(edgeHeader)} " +
            "recv=${ms(timings["recv_ms"])} ocr=${ms(timings["ocr_ms"])} ocr_total=${ms(timings["ocr_total_ms"])}"

    /**
     * The line at the end of the read after part 1. The status is the final
     * part's `translation_status`, `ended` when the body ended before it, and
     * `unreadable` when the read or a decode failed. [items] counts and
     * times the item lines; [itemTexts] is their texts by group id (the last
     * one, should a group repeat), [FrameResponse.items]'s value at the end
     * of the read. [finalMs] is the send to that moment.
     *
     * `reworded` is a dash unless the final status is `ok`; then it counts
     * the groups whose item text differs from the final list's entry for
     * them. An item whose group the final list does not name counts as
     * reworded: what was drawn from it would not match the final word.
     */
    fun finalLine(
        frameId: String,
        result: Part2Result,
        items: ItemLines,
        itemTexts: Map<Int, String>,
        finalMs: Long,
    ): String {
        val part = (result as? Part2Result.Line)?.part
        val status = when (result) {
            is Part2Result.Line -> result.part.translationStatus
            Part2Result.Ended -> "ended"
            is Part2Result.Failed -> "unreadable"
        }
        val reworded = part?.takeIf { it.translationStatus == "ok" }?.let { reworded(itemTexts, it) }
        return "final id=$frameId status=$status err=${part?.translationError?.cls ?: DASH} " +
            "items=${items.count} item_first=${items.firstMs ?: DASH} item_last=${items.lastMs ?: DASH} " +
            "final=$finalMs reworded=${reworded ?: DASH} " +
            "translate=${ms(part?.timings?.get("translate_ms"))} total=${ms(part?.timings?.get("total_ms"))}"
    }

    private fun reworded(itemTexts: Map<Int, String>, final: TranslationPart): Int {
        val finalById = final.translations.orEmpty().associate { it.id to it.translation }
        return itemTexts.count { (id, text) -> finalById[id] != text }
    }

    /** A header's milliseconds, or a dash when it is absent or not a
     *  finite number. */
    private fun header(value: String?): String = ms(value?.toDoubleOrNull())

    /** Whole milliseconds, or a dash when absent or not finite. */
    private fun ms(value: Double?): String = value?.takeIf { it.isFinite() }?.roundToLong()?.toString() ?: DASH
}
