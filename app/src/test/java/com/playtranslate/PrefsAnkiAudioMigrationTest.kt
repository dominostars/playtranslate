package com.playtranslate

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.playtranslate.ui.ContentSource
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Unit tests for the v2.1.0 → v2.2.0 Anki audio field-mapping migration
 * (`Prefs.migrateAnkiAudioFieldMappings`, run from [Prefs.migrateLegacyPrefs]).
 *
 * v2.2.0 added the WORD_AUDIO / SENTENCE_AUDIO content sources and made
 * [com.playtranslate.ui.AnkiCardTypeMapper] auto-map the Lapis / JPMN /
 * Migaku audio fields. A card type configured on v2.1.0 carries a saved
 * mapping that predates those sources, so its audio fields sit at NONE
 * and the synthesized WAV is silently dropped on send. The migration
 * back-fills those NONE audio fields exactly once.
 *
 * The saved JSON is seeded directly (bypassing [Prefs.setAnkiFieldMapping])
 * so the migration sees exactly the shape a v2.1.0 build persisted.
 */
@RunWith(RobolectricTestRunner::class)
class PrefsAnkiAudioMigrationTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()

    private fun sp() =
        ctx.getSharedPreferences("playtranslate_prefs", Context.MODE_PRIVATE)

    @Before fun clearPrefs() { sp().edit().clear().commit() }
    @After fun tearDown() { sp().edit().clear().commit() }

    /** Writes a raw `anki_field_mappings` blob, the way a v2.1.0 build did. */
    private fun seedRawMappings(root: JSONObject) {
        sp().edit().putString("anki_field_mappings", root.toString()).commit()
    }

    private fun mappingsOf(vararg models: Pair<Long, JSONObject>) =
        JSONObject().apply { models.forEach { (id, obj) -> put(id.toString(), obj) } }

    private fun fields(vararg pairs: Pair<String, String>) =
        JSONObject().apply { pairs.forEach { (k, v) -> put(k, v) } }

    @Test fun `backfills Lapis audio fields saved as NONE`() {
        val id = 1700000001L
        seedRawMappings(mappingsOf(id to fields(
            "Expression"      to "EXPRESSION",
            "MainDefinition"  to "DEFINITION",
            "ExpressionAudio" to "NONE",
            "SentenceAudio"   to "NONE",
        )))

        val mapping = Prefs(ctx).getAnkiFieldMapping(id)

        assertEquals(ContentSource.WORD_AUDIO,     mapping["ExpressionAudio"])
        assertEquals(ContentSource.SENTENCE_AUDIO, mapping["SentenceAudio"])
        // Non-audio fields are left exactly as they were.
        assertEquals(ContentSource.EXPRESSION, mapping["Expression"])
        assertEquals(ContentSource.DEFINITION, mapping["MainDefinition"])
    }

    @Test fun `backfills JPMN and Migaku audio field names`() {
        val jpmn = 2L
        val migaku = 3L
        seedRawMappings(mappingsOf(
            jpmn to fields(
                "Word"          to "EXPRESSION",
                "WordAudio"     to "NONE",
                "SentenceAudio" to "NONE",
            ),
            migaku to fields(
                "Target Word"    to "EXPRESSION_FURIGANA",
                "Word Audio"     to "NONE",
                "Sentence Audio" to "NONE",
            ),
        ))

        val prefs = Prefs(ctx)

        val jpmnMap = prefs.getAnkiFieldMapping(jpmn)
        assertEquals(ContentSource.WORD_AUDIO,     jpmnMap["WordAudio"])
        assertEquals(ContentSource.SENTENCE_AUDIO, jpmnMap["SentenceAudio"])

        val migakuMap = prefs.getAnkiFieldMapping(migaku)
        assertEquals(ContentSource.WORD_AUDIO,     migakuMap["Word Audio"])
        assertEquals(ContentSource.SENTENCE_AUDIO, migakuMap["Sentence Audio"])
    }

    @Test fun `leaves an already-mapped audio field untouched`() {
        val id = 4L
        // The user hand-pointed ExpressionAudio somewhere non-NONE.
        seedRawMappings(mappingsOf(id to fields(
            "ExpressionAudio" to "EXPRESSION",
            "SentenceAudio"   to "NONE",
        )))

        val mapping = Prefs(ctx).getAnkiFieldMapping(id)

        // Untouched: the migration only rewrites NONE.
        assertEquals(ContentSource.EXPRESSION, mapping["ExpressionAudio"])
        // The genuine NONE is still back-filled.
        assertEquals(ContentSource.SENTENCE_AUDIO, mapping["SentenceAudio"])
    }

    @Test fun `does not add an audio field absent from the saved mapping`() {
        val id = 5L
        // SentenceAudio is absent — e.g. the model lacked it when saved.
        seedRawMappings(mappingsOf(id to fields(
            "Expression"      to "EXPRESSION",
            "ExpressionAudio" to "NONE",
        )))

        val mapping = Prefs(ctx).getAnkiFieldMapping(id)

        assertEquals(ContentSource.WORD_AUDIO, mapping["ExpressionAudio"])
        assertNull(mapping["SentenceAudio"])
    }

    @Test fun `runs once - a NONE chosen after the migration is preserved`() {
        val id = 6L
        seedRawMappings(mappingsOf(id to fields(
            "Expression"      to "EXPRESSION",
            "ExpressionAudio" to "NONE",
        )))

        // First construction migrates ExpressionAudio → WORD_AUDIO.
        assertEquals(
            ContentSource.WORD_AUDIO,
            Prefs(ctx).getAnkiFieldMapping(id)["ExpressionAudio"],
        )

        // The user then deliberately turns that field back to NONE.
        Prefs(ctx).setAnkiFieldMapping(id, mapOf(
            "Expression"      to ContentSource.EXPRESSION,
            "ExpressionAudio" to ContentSource.NONE,
        ))

        // A later construction must NOT re-migrate it — the one-shot
        // marker is already set, so the deliberate NONE stands.
        assertEquals(
            ContentSource.NONE,
            Prefs(ctx).getAnkiFieldMapping(id)["ExpressionAudio"],
        )
    }

    @Test fun `with no saved mappings the migration still marks itself done`() {
        // Fresh install: no anki_field_mappings blob at all. Construction
        // must not throw, and the marker must still be set so a mapping
        // created later (already on v2.2.0 defaults) is never retro-
        // touched.
        Prefs(ctx)

        val id = 7L
        // A v2.2.0 user deliberately declines audio on a new card type.
        Prefs(ctx).setAnkiFieldMapping(id, mapOf(
            "ExpressionAudio" to ContentSource.NONE,
        ))

        assertEquals(
            ContentSource.NONE,
            Prefs(ctx).getAnkiFieldMapping(id)["ExpressionAudio"],
        )
    }
}
