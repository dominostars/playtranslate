package com.playtranslate

import com.playtranslate.audio.AudioRequest
import com.playtranslate.audio.sources.CommonsClient
import com.playtranslate.language.SourceLangId
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the language-evidence gate (adversarial-review finding): a global
 * Commons text search must not promote a same-spelling recording from another
 * language. Only exact language-tagged filenames or a Lingua Libre QID for the
 * requested language count as evidence; everything else is dropped (→ TTS).
 */
class CommonsClientLanguageEvidenceTest {

    private val client = CommonsClient()
    // French Lingua Libre QID is Q150 (see WikimediaLangCodes).
    private fun fr() = AudioRequest.word("chat", null, SourceLangId.FR)

    @Test fun exact_language_tagged_filename_is_evidence() {
        assertTrue(
            client.hasLanguageEvidence(
                "File:Fr-chat.ogg", fr(), listOf("File:fr-chat.ogg", "File:Fr-chat.ogg"),
            ),
        )
    }

    @Test fun lingua_libre_qid_for_language_is_evidence() {
        assertTrue(client.hasLanguageEvidence("File:LL-Q150 (fra)-Speaker-chat.wav", fr(), emptyList()))
    }

    @Test fun unrelated_file_without_evidence_is_rejected() {
        assertFalse(client.hasLanguageEvidence("File:En-cat.ogg", fr(), listOf("File:fr-chat.ogg")))
    }

    @Test fun other_language_qid_is_rejected() {
        // English is Q1860, not French's Q150.
        assertFalse(client.hasLanguageEvidence("File:LL-Q1860 (eng)-Speaker-chat.wav", fr(), emptyList()))
    }

    @Test fun qid_prefix_collision_is_rejected() {
        // Q1500 shares a prefix with Q150 but is a different language → reject.
        assertFalse(client.hasLanguageEvidence("File:LL-Q1500 (xxx)-Speaker-chat.wav", fr(), emptyList()))
    }
}
