package com.playtranslate.ocr.mangaocr

import org.junit.Assert.assertEquals
import org.junit.Test

/** Unit tests for the manga-ocr greedy / no-repeat-3gram decode step (pure). */
class MangaOcrDecodeTest {

    @Test
    fun greedyArgmaxWithNoHistory() {
        // Single start token → no n-gram banning; pick the max-logit token.
        val logits = floatArrayOf(0.1f, 0.2f, 0.9f, 0.3f)
        assertEquals(2, MangaOcrSession.nextToken(listOf(2), logits, base = 0, vocabSize = 4))
    }

    @Test
    fun blocksRepeated3gramAndPicksNextBest() {
        // ids end (…,5,7) and a prior (5,7)→9 trigram exists, so 9 is banned;
        // the highest-logit token (9) is skipped for the next best (8).
        val ids = listOf(2, 5, 7, 9, 5, 7)
        val logits = FloatArray(10).also { it[9] = 1.0f; it[8] = 0.5f; it[3] = 0.2f }
        assertEquals(8, MangaOcrSession.nextToken(ids, logits, base = 0, vocabSize = 10))
    }

    @Test
    fun honorsBaseOffsetForLastTimestep() {
        // logits is [L, vocab] flattened; base selects the last timestep's slice.
        val vocab = 4
        val logits = FloatArray(2 * vocab).also {
            it[0 * vocab + 1] = 9f   // timestep 0 argmax = 1 (ignored)
            it[1 * vocab + 3] = 9f   // timestep 1 argmax = 3 (selected)
        }
        assertEquals(3, MangaOcrSession.nextToken(listOf(2, 5), logits, base = 1 * vocab, vocabSize = vocab))
    }
}
