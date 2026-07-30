package com.playtranslate.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [PtModels.assemble] maps canonical note values onto a model's ACTUAL
 * field list (read back from AnkiDroid) — these tests pin the
 * tolerance contract for user-edited models: added fields get "",
 * reordered fields keep their values, renamed fields drop their value
 * without crashing.
 */
class PtAssembleTest {

    private val note = PtNote.Sentence(
        sentence = "SEN", sentenceFurigana = "FUR", translation = "TRA",
        targetWord = "TGT", wordsTable = "TAB", picture = "PIC",
        sentenceAudio = "AUD", audioCredit = "CRE",
    )

    @Test fun `canonical field list assembles in order`() {
        assertEquals(
            listOf("SEN", "FUR", "TRA", "TGT", "TAB", "PIC", "AUD", "CRE"),
            PtModels.assemble(PtModels.SENTENCE.fields, note),
        )
    }

    @Test fun `user-added field gets empty string`() {
        val fields = PtModels.SENTENCE.fields + "MyNotes"
        assertEquals(
            listOf("SEN", "FUR", "TRA", "TGT", "TAB", "PIC", "AUD", "CRE", ""),
            PtModels.assemble(fields, note),
        )
    }

    @Test fun `reordered fields keep their values`() {
        assertEquals(
            listOf("TRA", "SEN"),
            PtModels.assemble(listOf("Translation", "Sentence"), note),
        )
    }

    @Test fun `renamed field drops its value without crashing`() {
        assertEquals(
            listOf("SEN", ""),
            PtModels.assemble(listOf("Sentence", "Uebersetzung"), note),
        )
    }
}
