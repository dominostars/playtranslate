package com.playtranslate.language

import android.database.sqlite.SQLiteDatabase
import java.io.File

/**
 * One sense from the target-language gloss pack. [senseOrd] is the 0-based
 * sense index in the target pack's own ordering — it does NOT reliably
 * align with the source entry's sense.position. JMdict's per-language
 * sense blocks (German/French/etc.) are appended after the English ones
 * in arbitrary order, so by-ordinal merging with the source dict was
 * always producing wrong attributions. Renderers iterate target senses
 * directly (target-driven render) for non-English targets and only fall
 * back to ordinal alignment for English-target paths.
 *
 * [examples] are pulled from the same kaikki entry the glosses came from
 * (for Wiktionary-derived rows) — properly attached per-target-sense, no
 * alignment guesswork. JMdict-derived rows have empty examples (JMdict
 * ships zero <example> tags). PanLex-derived rows also have empty
 * examples (CC0 dump is gloss-only). [misc] flags carry editorial labels
 * like "informal", "archaic", "honorific" — sourced from JMdict <misc>
 * for JA rows or kaikki tags/raw_tags for Wiktionary rows.
 */
data class TargetSense(
    val senseOrd: Int,
    val pos: List<String>,
    val glosses: List<String>,
    val source: String,
    val examples: List<com.playtranslate.model.Example> = emptyList(),
    val misc: List<String> = emptyList(),
)

/** Lookup interface extracted for testability (no Android dependency). */
interface TargetGlossLookup {
    fun lookup(sourceLang: String, written: String, reading: String? = null): List<TargetSense>?
}

/**
 * Read-only accessor for a target-language gloss pack's `glosses.sqlite`.
 * One instance per target language, managed by [TargetGlossDatabaseProvider].
 */
class TargetGlossDatabase private constructor(private val db: SQLiteDatabase) : TargetGlossLookup {

    /**
     * Look up target-language senses for a source headword. Tries a
     * reading-specific match first — numbered↔tone-marked-insensitive via
     * [PinyinFormatter.readingsEqual], so a tone-marked ZH hint matches the
     * numbered CFDICT/HanDeDict keys — then falls back to reading-agnostic.
     */
    override fun lookup(sourceLang: String, written: String, reading: String?): List<TargetSense>? {
        val rows = allRows(sourceLang, written)
        if (rows.isEmpty()) return null
        if (reading != null) {
            val matched = rows.filter { PinyinFormatter.readingsEqual(it.first, reading) }.map { it.second }
            if (matched.isNotEmpty()) return matched
        }
        // Fall back to empty-reading entries (WITHOUT ROWID tables can't have NULL in PK)
        return rows.filter { it.first.isEmpty() }.map { it.second }.ifEmpty { null }
    }

    /** All rows for the headword as (reading, sense), in sense_ord order.
     *  The reading column is matched in Kotlin so numbered ZH pinyin can be
     *  compared tone-mark-insensitively (SQL can't normalize). */
    private fun allRows(sourceLang: String, written: String): List<Pair<String, TargetSense>> {
        val out = mutableListOf<Pair<String, TargetSense>>()
        db.rawQuery(
            "SELECT sense_ord, pos, glosses, source, reading FROM glosses " +
                "WHERE source_lang=? AND written=? ORDER BY sense_ord",
            arrayOf(sourceLang, written),
        ).use { c ->
            while (c.moveToNext()) {
                out += (c.getString(4) ?: "") to TargetSense(
                    senseOrd = c.getInt(0),
                    pos = c.getString(1).split(',').filter { it.isNotBlank() },
                    glosses = c.getString(2).split('\t').filter { it.isNotBlank() },
                    source = c.getString(3),
                )
            }
        }
        return out
    }

    fun close() { db.close() }

    companion object {
        fun open(dbFile: File): TargetGlossDatabase? {
            if (!dbFile.exists()) return null
            val db = SQLiteDatabase.openDatabase(
                dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY
            )
            return TargetGlossDatabase(db)
        }
    }
}
