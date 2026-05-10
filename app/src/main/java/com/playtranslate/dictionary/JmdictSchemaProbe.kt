package com.playtranslate.dictionary

import android.database.sqlite.SQLiteDatabase
import java.io.File

/**
 * Single source of truth for "is this JMdict pack STRUCTURALLY intact?"
 * Both [DictionaryManager.isSchemaUpToDate] and
 * [com.playtranslate.language.LanguagePackStore.isJmdictSchemaCurrent]
 * delegate here so they can't drift apart.
 *
 * **Scope: corruption backstop, NOT a version check.** This deliberately
 * does NOT probe ja-v2-introduced columns (`headword.rank_score`,
 * `reading.rank_score`, `reading.uk_applicable`) — those are handled at
 * runtime by `DictionaryManager.supportsV2Schema` (column-existence guard
 * via `PRAGMA table_info`). If we re-tightened this probe to require the
 * v2 columns, the additive-upgrade path would be unreachable: every v1
 * pack on disk would be `schemaStale=true` → classified as FORCE → the
 * additive flow is dead code. See
 * `~/.claude/plans/cheerful-yawning-donut.md`.
 *
 * Returns false only when a TABLE is missing or a pre-v2 column has
 * gone away — both signal genuine corruption, in which case the caller
 * (auto-delete in `isInstalled` / `ensureOpen`) is right to wipe and
 * re-route through onboarding.
 *
 * Read-only — opens with [SQLiteDatabase.OPEN_READONLY] and never
 * mutates disk state.
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
        // NOTE: do NOT probe rank_score / uk_applicable here. See class
        // docstring — those are runtime-guarded by supportsV2Schema, not
        // version-gated. Adding them here breaks the additive-upgrade path.
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
