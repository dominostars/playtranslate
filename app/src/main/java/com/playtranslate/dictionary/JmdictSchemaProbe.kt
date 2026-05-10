package com.playtranslate.dictionary

import android.database.sqlite.SQLiteDatabase
import java.io.File

/**
 * Single source of truth for "is this JMdict pack at the schema version the
 * current app expects?" Both [DictionaryManager.isSchemaUpToDate] and
 * [com.playtranslate.language.LanguagePackStore.isJmdictSchemaCurrent]
 * delegate here so they can't drift apart (they historically did — one
 * probed 5 things, the other 4).
 *
 * Read-only — opens with [SQLiteDatabase.OPEN_READONLY] and never mutates
 * disk state. Returns false on any probe failure (missing table, missing
 * column, or open error). Callers handle the false case (auto-delete in
 * [com.playtranslate.language.LanguagePackStore.isInstalled], stale-list
 * inclusion in `staleInstalledPacks`).
 *
 * The probed columns are the **runtime contract**: every column queried by
 * [DictionaryManager] in the app's normal lookup path must appear here.
 * When you add a new column to the JMdict pack schema and start querying
 * it, add the probe here too — that's how this method enforces "old packs
 * get rejected and re-downloaded."
 */
internal object JmdictSchemaProbe {

    fun isCurrent(db: SQLiteDatabase): Boolean = try {
        // Entry-level frequency for the star display in result rows.
        db.rawQuery("SELECT freq_score FROM entry LIMIT 1", null).use { }
        // Kanji headword form lookups.
        db.rawQuery("SELECT text FROM headword LIMIT 1", null).use { }
        // Sense-level misc tags (Kana only, etc.) used by the renderer.
        db.rawQuery("SELECT misc FROM sense LIMIT 1", null).use { }
        // KANJIDIC2 single-char lookups.
        db.rawQuery("SELECT literal FROM kanjidic LIMIT 1", null).use { }
        // Multilingual KANJIDIC2 meanings for the user's target language.
        db.rawQuery(
            "SELECT literal, lang, meanings FROM kanji_meaning LIMIT 1",
            null,
        ).use { }
        // ja-v2: per-headword and per-reading rank score for the cross-entry
        // ranking SQL. Plus the uk_applicable flag the pure-kana fallback
        // uses for the +1.5M Kana-only bonus. Old ja-v1 packs lack these
        // columns; querying them throws and returns false here, which
        // routes the user through the upgrade flow.
        db.rawQuery("SELECT rank_score FROM headword LIMIT 1", null).use { }
        db.rawQuery(
            "SELECT rank_score, uk_applicable FROM reading LIMIT 1",
            null,
        ).use { }
        true
    } catch (_: Exception) {
        false
    }

    fun isCurrent(dbFile: File): Boolean = try {
        SQLiteDatabase.openDatabase(
            dbFile.absolutePath,
            null,
            SQLiteDatabase.OPEN_READONLY,
        ).use { isCurrent(it) }
    } catch (_: Exception) {
        false
    }
}
