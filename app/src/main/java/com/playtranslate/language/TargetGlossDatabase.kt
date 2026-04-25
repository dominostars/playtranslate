package com.playtranslate.language

import android.database.sqlite.SQLiteDatabase
import java.io.File

/**
 * One sense from the target-language gloss pack. [senseOrd] is the 0-based
 * sense index matching the source entry's sense list — the UI aligns by
 * ordinal, not list position, so sparse target coverage (e.g. French glosses
 * for senses 0 and 2 but not 1) renders correctly.
 */
data class TargetSense(
    val senseOrd: Int,
    val pos: List<String>,
    val glosses: List<String>,
    val source: String,
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
     * Look up target-language senses for a source headword.
     * Tries reading-specific match first, falls back to reading-agnostic.
     */
    override fun lookup(sourceLang: String, written: String, reading: String?): List<TargetSense>? {
        if (reading != null) {
            val result = query(sourceLang, written, reading)
            if (result != null) return result
        }
        // Fall back to empty-reading entries (WITHOUT ROWID tables can't have NULL in PK)
        return query(sourceLang, written, "")
    }

    private fun query(sourceLang: String, written: String, reading: String?): List<TargetSense>? {
        val sql = if (reading != null)
            "SELECT sense_ord, pos, glosses, source FROM glosses WHERE source_lang=? AND written=? AND reading=? ORDER BY sense_ord"
        else
            "SELECT sense_ord, pos, glosses, source FROM glosses WHERE source_lang=? AND written=? AND reading='' ORDER BY sense_ord"
        val args = if (reading != null) arrayOf(sourceLang, written, reading) else arrayOf(sourceLang, written)
        db.rawQuery(sql, args).use { c ->
            if (!c.moveToFirst()) return null
            val senses = mutableListOf<TargetSense>()
            do {
                senses += TargetSense(
                    senseOrd = c.getInt(0),
                    pos = c.getString(1).split(',').filter { it.isNotBlank() },
                    glosses = c.getString(2).split('\t').filter { it.isNotBlank() },
                    source = c.getString(3),
                )
            } while (c.moveToNext())
            return senses
        }
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
