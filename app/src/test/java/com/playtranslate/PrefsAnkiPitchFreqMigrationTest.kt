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
 * Unit tests for the Anki pitch/frequency field-mapping migration
 * (`Prefs.migrateAnkiPitchFreqFieldMappings`, run from
 * [Prefs.migrateLegacyPrefs]).
 *
 * The Yomitan integration made pitch position, the frequency list, and the
 * frequency-sort number fillable, and [com.playtranslate.ui.AnkiCardTypeMapper]
 * now auto-maps the corresponding Lapis / JPMN fields. A card type configured
 * before then carries a saved mapping where those slots sit at NONE (and
 * Lapis's `Frequency` sits at the old ★-stars default `FREQUENCY`). The
 * migration rewrites each field still at its OLD auto-default exactly once.
 *
 * Raw JSON is seeded directly (bypassing [Prefs.setAnkiFieldMapping]) so the
 * migration sees exactly the shape an older build persisted.
 */
@RunWith(RobolectricTestRunner::class)
class PrefsAnkiPitchFreqMigrationTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()

    private fun sp() =
        ctx.getSharedPreferences("playtranslate_prefs", Context.MODE_PRIVATE)

    @Before fun clearPrefs() { sp().edit().clear().commit() }
    @After fun tearDown() { sp().edit().clear().commit() }

    private fun seedRawMappings(root: JSONObject) {
        sp().edit().putString("anki_field_mappings", root.toString()).commit()
    }

    private fun mappingsOf(vararg models: Pair<Long, JSONObject>) =
        JSONObject().apply { models.forEach { (id, obj) -> put(id.toString(), obj) } }

    private fun fields(vararg pairs: Pair<String, String>) =
        JSONObject().apply { pairs.forEach { (k, v) -> put(k, v) } }

    @Test fun `backfills Lapis pitch and freq-sort and upgrades the stars frequency`() {
        val id = 1700000001L
        seedRawMappings(mappingsOf(id to fields(
            "Expression"    to "EXPRESSION",
            "Frequency"     to "FREQUENCY",   // old ★-stars auto-default
            "PitchPosition" to "NONE",
            "FreqSort"      to "NONE",
        )))

        val mapping = Prefs(ctx).getAnkiFieldMapping(id)

        assertEquals(ContentSource.FREQUENCY_VALUES,   mapping["Frequency"])
        assertEquals(ContentSource.PITCH_POSITION,     mapping["PitchPosition"])
        assertEquals(ContentSource.FREQUENCY_HARMONIC, mapping["FreqSort"])
        // Unrelated fields untouched.
        assertEquals(ContentSource.EXPRESSION,         mapping["Expression"])
    }

    @Test fun `backfills JPMN pitch override, frequency-stylized, and frequency-sort`() {
        val id = 2L
        seedRawMappings(mappingsOf(id to fields(
            "Word"                to "EXPRESSION",
            "PAOverride"          to "NONE",
            "FrequenciesStylized" to "NONE",
            "FrequencySort"       to "NONE",
        )))

        val mapping = Prefs(ctx).getAnkiFieldMapping(id)

        assertEquals(ContentSource.PITCH_POSITION,     mapping["PAOverride"])
        assertEquals(ContentSource.FREQUENCY_STYLIZED, mapping["FrequenciesStylized"])
        assertEquals(ContentSource.FREQUENCY_HARMONIC, mapping["FrequencySort"])
    }

    @Test fun `leaves a deliberately-cleared Frequency at NONE`() {
        val id = 3L
        // Frequency's OLD auto-default was the ★ stars (FREQUENCY), not NONE —
        // so a saved NONE is a deliberate clear and must NOT be upgraded.
        seedRawMappings(mappingsOf(id to fields(
            "Frequency"     to "NONE",
            "PitchPosition" to "NONE",
        )))

        val mapping = Prefs(ctx).getAnkiFieldMapping(id)

        assertEquals(ContentSource.NONE,           mapping["Frequency"])
        // PitchPosition's old default WAS NONE, so it still upgrades.
        assertEquals(ContentSource.PITCH_POSITION, mapping["PitchPosition"])
    }

    @Test fun `leaves customized pitch and frequency choices untouched`() {
        val id = 4L
        seedRawMappings(mappingsOf(id to fields(
            "Frequency"     to "DEFINITION",  // user pointed it elsewhere
            "PitchPosition" to "READING",     // non-NONE → not the old default
        )))

        val mapping = Prefs(ctx).getAnkiFieldMapping(id)

        assertEquals(ContentSource.DEFINITION, mapping["Frequency"])
        assertEquals(ContentSource.READING,    mapping["PitchPosition"])
    }

    @Test fun `does not add a pitch or freq field absent from the saved mapping`() {
        val id = 5L
        seedRawMappings(mappingsOf(id to fields(
            "Word"       to "EXPRESSION",
            "PAOverride" to "NONE",
        )))

        val mapping = Prefs(ctx).getAnkiFieldMapping(id)

        assertEquals(ContentSource.PITCH_POSITION, mapping["PAOverride"])
        assertNull(mapping["FrequencySort"])
        assertNull(mapping["FrequenciesStylized"])
    }

    @Test fun `runs once - a choice made after the migration is preserved`() {
        val id = 6L
        seedRawMappings(mappingsOf(id to fields("PitchPosition" to "NONE")))

        // First construction migrates PitchPosition → PITCH_POSITION.
        assertEquals(
            ContentSource.PITCH_POSITION,
            Prefs(ctx).getAnkiFieldMapping(id)["PitchPosition"],
        )

        // User then deliberately clears it back to NONE.
        Prefs(ctx).setAnkiFieldMapping(id, mapOf("PitchPosition" to ContentSource.NONE))

        // A later construction must NOT re-migrate it — the one-shot marker
        // is set, so the deliberate NONE stands.
        assertEquals(
            ContentSource.NONE,
            Prefs(ctx).getAnkiFieldMapping(id)["PitchPosition"],
        )
    }

    @Test fun `with no saved mappings the migration still marks itself done`() {
        Prefs(ctx)  // fresh install — no anki_field_mappings blob

        val id = 7L
        // A user on the new build deliberately declines pitch on a card type.
        Prefs(ctx).setAnkiFieldMapping(id, mapOf("PitchPosition" to ContentSource.NONE))

        assertEquals(
            ContentSource.NONE,
            Prefs(ctx).getAnkiFieldMapping(id)["PitchPosition"],
        )
    }
}
