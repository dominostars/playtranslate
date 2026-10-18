package com.playtranslate.cloud

/**
 * How a batch of texts is cut into `POST /v1/translate` calls, by the
 * route's caps ("Translation only" in docs/api.md): at most [MAX_TEXTS]
 * texts a call, none blank, none over [MAX_TEXT_CODE_POINTS], and a body
 * under [MAX_BODY_BYTES]; plus this client's own [MAX_CHUNK_CODE_POINTS]
 * across a call, which keeps every call inside the server's scaled deadline
 * (5 s plus 2.5 s a thousand code points, capped at 20 s: 6,000 is where the
 * cap binds) and so inside [PtCloudClient]'s 25 s call timeout. Pure, so
 * every cap is pinned by a test without a server; [PtCloudBackend] owns the
 * calls and what their answers mean.
 */
internal object PtCloudBatches {

    /** The server's cap on texts in one call. */
    const val MAX_TEXTS = 64

    /** The server's cap on one text, in code points (a blank or oversize
     *  text makes the server refuse the whole call with a 422). */
    const val MAX_TEXT_CODE_POINTS = 4_000

    /** This client's cap on code points across one call. */
    const val MAX_CHUNK_CODE_POINTS = 6_000

    /** The server's and the edge's cap on one body, in bytes as sent. A
     *  call's body stays strictly under it. */
    const val MAX_BODY_BYTES = 65_536

    /** One call's texts, and the position of each in the caller's list
     *  ([positions] and [texts] have the same size, in increasing
     *  position). */
    data class Chunk(val positions: List<Int>, val texts: List<String>)

    /** True when a text that would be sent ([chunk] sends every text that is
     *  not blank) is over [MAX_TEXT_CODE_POINTS]: the server would refuse
     *  the whole call, so the batch is refused before any request. */
    fun oversize(texts: List<String>): Boolean =
        texts.any { it.isNotBlank() && codePoints(it) > MAX_TEXT_CODE_POINTS }

    /**
     * True when a text that would be sent has a body of [MAX_BODY_BYTES] or
     * more by itself, as [bodyBytesOf] measures a call's whole body for a
     * list of texts (persona and context included): no call could carry it
     * and the server would answer 413, so the batch is refused before any
     * request, as an [oversize] one is. Only the persona can take a body
     * there: a text is capped by [MAX_TEXT_CODE_POINTS] and the context by
     * its ring, while the prompt editor only advises against a long persona.
     */
    fun bodyOversize(texts: List<String>, bodyBytesOf: (List<String>) -> Int): Boolean =
        texts.any { it.isNotBlank() && bodyBytesOf(listOf(it)) >= MAX_BODY_BYTES }

    /**
     * The calls [texts] go out in, in order. A blank text is in none of
     * them: the server refuses a blank text, and the caller answers it
     * itself, at its own position. The rest fill each call in their order
     * until the next would make it [MAX_TEXTS] + 1 texts, more than
     * [MAX_CHUNK_CODE_POINTS] code points, or a body of [MAX_BODY_BYTES] or
     * more, as [bodyBytesOf] measures the call's whole body for a list of
     * texts (persona and context included, since every call carries them).
     * Assumes no text is [oversize] and none is [bodyOversize], so every
     * call holds at least one text and stays under the cap.
     */
    fun chunk(texts: List<String>, bodyBytesOf: (List<String>) -> Int): List<Chunk> {
        val chunks = ArrayList<Chunk>()
        var positions = ArrayList<Int>()
        var batch = ArrayList<String>()
        var batchCodePoints = 0
        for ((position, text) in texts.withIndex()) {
            if (text.isBlank()) continue
            val codePoints = codePoints(text)
            val full = batch.isNotEmpty() && (
                batch.size == MAX_TEXTS ||
                    batchCodePoints + codePoints > MAX_CHUNK_CODE_POINTS ||
                    bodyBytesOf(batch + text) >= MAX_BODY_BYTES
                )
            if (full) {
                chunks += Chunk(positions, batch)
                positions = ArrayList()
                batch = ArrayList()
                batchCodePoints = 0
            }
            positions += position
            batch += text
            batchCodePoints += codePoints
        }
        if (batch.isNotEmpty()) chunks += Chunk(positions, batch)
        return chunks
    }

    /** The server's measure of a text's size. */
    fun codePoints(text: String): Int = text.codePointCount(0, text.length)
}
