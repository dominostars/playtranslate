package com.playtranslate

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.playtranslate.ui.PtModels
import java.io.File
import androidx.core.net.toUri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

private const val TAG = "AnkiManager"

/**
 * Communicates with AnkiDroid via its public content provider.
 * No external library dependency — we call the content provider directly.
 *
 * All methods that perform I/O must be called from a background thread (IO dispatcher).
 */
class AnkiManager(private val context: Context) {

    /**
     * Lightweight snapshot of an AnkiDroid note type, returned by [getModels].
     * `type` is AnkiDroid's model_type column (0 = standard, 1 = cloze).
     */
    data class ModelInfo(
        val id: Long,
        val name: String,
        val fieldNames: List<String>,
        val type: Int,
    )

    companion object {
        const val PERMISSION = "com.ichi2.anki.permission.READ_WRITE_DATABASE"

        private const val AUTHORITY = "com.ichi2.anki.flashcards"
        private const val FILE_PROVIDER_AUTHORITY = "com.playtranslate.fileprovider"

        /** AnkiDroid field separator (ASCII 31, unit separator) */
        private const val SEP = "\u001f"

        private val DECK_URI  = "content://$AUTHORITY/decks".toUri()
        private val NOTE_URI  = "content://$AUTHORITY/notes".toUri()
        /** Raw-SQL counterpart to [NOTE_URI]: `selection` is a WHERE clause on
         *  the notes table (columns _id, mid, flds, sfld, csum, tags…) rather
         *  than Anki browser-search syntax. Used to hit the csum index. */
        private val NOTES_V2_URI = "content://$AUTHORITY/notes_v2".toUri()
        private val MODEL_URI = "content://$AUTHORITY/models".toUri()
        private val MEDIA_URI = "content://$AUTHORITY/media".toUri()

        private val _noteAddedTick = MutableStateFlow(0)
        /** Bumped after each successful note insert. UI surfaces that show
         *  "already in Anki" badges collect this to refresh without lifecycle
         *  plumbing — it fires even while a review dialog is on top (the host
         *  fragment stays STARTED), which an onResume hook does not. */
        val noteAddedTick: StateFlow<Int> = _noteAddedTick.asStateFlow()

        /** Field names commonly holding the target vocab across mining
         *  templates (Yomitan / Migaku / JP·ZH·KO decks). Used by
         *  [decksByWord]'s field-name search so a word counts even when it
         *  isn't the note's first field (e.g. sentence cards). */
        private val MATCH_FIELDS = listOf(
            "Expression", "TargetWord", "Target", "Word", "Vocabulary", "Vocab",
            "Front", "Term", "Reading", "Kanji", "Hanzi", "Hangul", "Headword",
            "単語", "言葉", "表現", "词", "单词", "단어", "표현",
        )

        /** Escapes a value for use inside a quoted Anki browser-search term. */
        private fun ankiSearchEscape(s: String): String =
            s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("*", "\\*").replace("_", "\\_")
    }

    fun isAnkiDroidInstalled(): Boolean = try {
        context.packageManager.getPackageInfo("com.ichi2.anki", 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, PERMISSION) == PackageManager.PERMISSION_GRANTED

    /** Returns a map of deckId → deckName from AnkiDroid. */
    fun getDecks(): Map<Long, String> {
        val result = linkedMapOf<Long, String>()
        try {
            context.contentResolver.query(DECK_URI, null, null, null, null)?.use { cursor ->
                // Try both naming conventions used across AnkiDroid versions
                val idCol   = cursor.getColumnIndex("deck_id").takeIf { it >= 0 }
                    ?: cursor.getColumnIndex("_id")
                val nameCol = cursor.getColumnIndex("deckName").takeIf { it >= 0 }
                    ?: cursor.getColumnIndex("deck_name")
                while (cursor.moveToNext()) {
                    val id   = if (idCol   >= 0) cursor.getLong(idCol)   else continue
                    val name = if (nameCol >= 0) cursor.getString(nameCol) ?: continue else continue
                    result[id] = name
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "getDecks failed: ${e.message}", e)
        }
        return result
    }

    /**
     * For each of [words], returns the distinct names of the Anki decks that
     * already contain a note whose **first field** (HTML-stripped) exactly
     * equals that word — across every deck and note type, not just
     * PlayTranslate's. Words with no match are omitted from the result.
     *
     * Detection rides Anki's `csum` index (first-field checksum) via the
     * raw-SQL [NOTES_V2_URI]; each candidate is then verified by comparing
     * the stripped first field (guarding the 32-bit-hash collision), and the
     * owning deck(s) are read from each note's `cards` sub-URI.
     *
     * Best-effort and silent: returns an empty map when AnkiDroid is absent,
     * permission is missing, or any query fails. Callers should gate on
     * [isAnkiDroidInstalled] + [hasPermission] and must invoke this from a
     * background thread (binder IPC).
     */
    fun decksByWord(words: List<String>): Map<String, List<String>> {
        val cleaned = words.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (cleaned.isEmpty()) return emptyMap()

        val cleanedSet = cleaned.toHashSet()

        // Note ids PER queried word, from two complementary sources. Each hit is
        // attributed to the specific word it matched, so a batched query for
        // many words never cross-credits one word's note to another:
        //  A) csum — the note's FIRST field equals the word (Anki's duplicate
        //     key; HTML-stripped, so it catches PlayTranslate's own HTML cards
        //     and any note type whose first/sort field is the plain word).
        //     Attributed only to the word equal to field[0] — which is also the
        //     32-bit-hash collision guard.
        //  B) field-name search, run PER WORD so Anki's exact field match ties
        //     each hit to that word — catches the word in a recognized vocab
        //     field that isn't the first field (e.g. a sentence card's
        //     TargetWord / Word field). Either query failing is non-fatal.
        val noteIdsByWord = HashMap<String, MutableSet<Long>>()

        try {
            val csums = cleaned.map { AnkiCsum.checksum(AnkiCsum.stripHtml(it)) }.distinct()
            val sel = "csum IN (${csums.joinToString(",") { "?" }})"
            context.contentResolver.query(
                NOTES_V2_URI, arrayOf("_id", "flds"), sel,
                csums.map { it.toString() }.toTypedArray(), null,
            )?.use { c ->
                val idCol = c.getColumnIndex("_id")
                val fCol = c.getColumnIndex("flds")
                if (idCol >= 0 && fCol >= 0) while (c.moveToNext()) {
                    val first = AnkiCsum.stripHtml((c.getString(fCol) ?: "").substringBefore(SEP))
                    if (first in cleanedSet) {
                        noteIdsByWord.getOrPut(first) { mutableSetOf() }.add(c.getLong(idCol))
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "decksByWord csum query failed: ${e.message}", e)
        }

        for (w in cleaned) {
            try {
                val q = ankiSearchEscape(w)
                val sel = MATCH_FIELDS.joinToString(" OR ") { f -> "$f:\"$q\"" }
                context.contentResolver.query(
                    NOTE_URI, arrayOf("_id"), sel, null, null,
                )?.use { c ->
                    val idCol = c.getColumnIndex("_id")
                    if (idCol >= 0) while (c.moveToNext()) {
                        noteIdsByWord.getOrPut(w) { mutableSetOf() }.add(c.getLong(idCol))
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "decksByWord field search failed for '$w': ${e.message}", e)
            }
        }

        if (noteIdsByWord.isEmpty()) return emptyMap()

        val deckNames = getDecks()
        val result = HashMap<String, List<String>>()
        for ((word, noteIds) in noteIdsByWord) {
            val deckIds = linkedSetOf<Long>()
            for (noteId in noteIds) {
                try {
                    val cardsUri = Uri.withAppendedPath(NOTE_URI, "$noteId/cards")
                    context.contentResolver.query(
                        cardsUri, arrayOf("deck_id"), null, null, null,
                    )?.use { c ->
                        val didCol = c.getColumnIndex("deck_id")
                        if (didCol < 0) return@use
                        while (c.moveToNext()) deckIds.add(c.getLong(didCol))
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "decksByWord cards query failed (note $noteId): ${e.message}", e)
                }
            }
            val names = deckIds.mapNotNull { deckNames[it] }.distinct()
            Log.d(TAG, "decksByWord '$word': notes=$noteIds deckIds=$deckIds names=$names")
            if (names.isNotEmpty()) result[word] = names
        }
        return result
    }

    /**
     * Returns the model backing [spec] — creating it if absent — or
     * null on failure. Called from the send path when the user's card
     * type is "Default (PlayTranslate)", once per [spec] per install
     * in practice (later sends find the existing model).
     *
     * Matched by NAME ONLY, deliberately unlike the retired blob-model
     * lookup's exact-field match: users are encouraged to edit these
     * note types (that's the point of field-based cards), and an
     * added/reordered field must not spawn a duplicate model. The
     * caller assembles values against the returned [ModelInfo]'s
     * actual field names.
     *
     * Creation is TWO provider calls: the models insert carries
     * name/fields/css, then a separate update on `models/{id}/templates/0`
     * sets qfmt/afmt. The insert-side `qfmt`/`afmt` keys the old code
     * passed were silently dropped by AnkiDroid (the models table has
     * no template columns) — the v005 cards only worked because Anki's
     * auto-generated template happened to show field 0 / field 1.
     *
     * The two steps are NOT atomic and the provider can't delete a
     * model, so a template-install failure must not be sticky. Repair
     * eligibility is decided from ANKIDROID-SIDE state only (an
     * app-local flag wouldn't survive reinstall or a second device,
     * and would authorize clobbering user-edited templates): on every
     * name-match reuse the stored question format is read back and
     * classified via [PtModels.classifyStoredTemplate] — our marker or
     * a user rewrite is reused untouched; only AnkiDroid's
     * auto-generated template (the failed-install fingerprint) is
     * repaired. User edits are theirs; template fixes ship as a
     * version bump in [spec]'s name.
     */
    fun getOrCreatePtModel(spec: PtModels.Spec): ModelInfo? {
        val existing = try {
            queryAllModels().firstOrNull { it.name == spec.name }
        } catch (e: Exception) {
            // Bail rather than fall through: a transient query failure
            // must not create a duplicate model.
            Log.e(TAG, "Model query failed: ${e.message}", e)
            return null
        }
        if (existing != null) {
            val (storedQfmt, storedAfmt) = try {
                readStoredTemplate(existing.id)
            } catch (e: Exception) {
                // Can't inspect ⇒ don't touch. Worst case is a
                // still-broken auto template rendering ugly cards —
                // recoverable — versus rewriting templates we never saw.
                Log.w(TAG, "Template read-back failed for '${spec.name}' id=${existing.id} " +
                    "— reusing untouched: ${e.message}")
                return existing
            }
            return when (PtModels.classifyStoredTemplate(storedQfmt, storedAfmt, spec)) {
                PtModels.TemplateState.OURS -> {
                    Log.d(TAG, "Reusing model '${existing.name}' id=${existing.id}")
                    existing
                }
                PtModels.TemplateState.FOREIGN -> {
                    Log.i(TAG, "Model '${spec.name}' id=${existing.id} has foreign templates " +
                        "— user-owned, reusing untouched")
                    existing
                }
                PtModels.TemplateState.AUTO_GENERATED -> {
                    // A previous run's template install failed after the
                    // insert — the one state repair exists for.
                    Log.w(TAG, "Model '${spec.name}' id=${existing.id} wears the auto template " +
                        "— repairing")
                    if (installTemplates(existing.id, spec)) existing else null
                }
            }
        }
        Log.i(TAG, "Creating Anki model '${spec.name}'")
        val cv = ContentValues().apply {
            put("name", spec.name)
            put("field_names", spec.fields.joinToString(SEP))
            put("num_cards", 1)
            // Field 0 is the duplicate key ("already in Anki" csum +
            // provider dup rejection). 0 is also the provider default;
            // explicit because it's load-bearing.
            put("sort_field_index", 0)
            // The user's CURRENT accent rides the model CSS as a trailing
            // :root override of the template's Aqua default (later rule
            // wins). Creation-time snapshot only: model CSS is never
            // rewritten, so an accent change reaches new note types
            // (version bumps / recreations), not existing ones.
            put("css", spec.css + accentCssOverride())
        }
        val modelId = try {
            val uri = context.contentResolver.insert(MODEL_URI, cv) ?: run {
                Log.e(TAG, "Model insert returned null URI")
                return null
            }
            uri.lastPathSegment?.toLongOrNull() ?: run {
                Log.e(TAG, "Model insert URI has no id: $uri")
                return null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Model create failed: ${e.message}", e)
            return null
        }
        // The model now exists regardless of what happens next (no
        // provider delete). Failing here leaves it wearing the auto
        // template — the repair branch above retries on the next send.
        if (!installTemplates(modelId, spec)) return null
        // Read back the stored model so callers assemble against the
        // field names AnkiDroid actually kept.
        return try {
            queryAllModels().firstOrNull { it.id == modelId }
                ?: ModelInfo(modelId, spec.name, spec.fields, type = 0)
        } catch (e: Exception) {
            Log.e(TAG, "Model read-back failed: ${e.message}", e)
            ModelInfo(modelId, spec.name, spec.fields, type = 0)
        }
    }

    /** `:root{--pt-hl:…;--pt-hl-bg:…;}` carrying the user's current
     *  accent (theme-invariant, like the app's own accent; tint = the
     *  0x1F-alpha `pt_accent_*_tint` convention). "" on any resolution
     *  failure — the template's baked default accent then stands. */
    private fun accentCssOverride(): String = try {
        val argb = androidx.core.content.ContextCompat.getColor(
            context, com.playtranslate.Prefs(context).accent.color)
        val rgb = String.format("#%06X", argb and 0xFFFFFF)
        ":root{--pt-hl:$rgb;--pt-hl-bg:${rgb}1F;}"
    } catch (e: Exception) {
        Log.w(TAG, "accent CSS resolution failed: ${e.message}")
        ""
    }

    /** The stored (question format, answer format) of
     *  `models/{modelId}/templates/0`, either null when the row/column
     *  is absent. Throws on query failure so the caller can distinguish
     *  "couldn't look" from "looked, empty". */
    private fun readStoredTemplate(modelId: Long): Pair<String?, String?> {
        val templateUri = Uri.withAppendedPath(MODEL_URI, "$modelId/templates/0")
        var qfmt: String? = null
        var afmt: String? = null
        context.contentResolver.query(
            templateUri, arrayOf("question_format", "answer_format"), null, null, null,
        )?.use { c ->
            val qCol = c.getColumnIndex("question_format")
            val aCol = c.getColumnIndex("answer_format")
            if (c.moveToFirst()) {
                if (qCol >= 0) qfmt = c.getString(qCol)
                if (aCol >= 0) afmt = c.getString(aCol)
            }
        } ?: throw IllegalStateException("template query returned null cursor")
        return qfmt to afmt
    }

    /**
     * Writes [spec]'s qfmt/afmt onto `models/{modelId}/templates/0` and
     * verifies they landed. Only the two format keys go in the update —
     * the provider throws IllegalArgumentException on template keys it
     * doesn't know, and the template's name is already "Card 1" from
     * the insert.
     *
     * Verification reads the stored question format back and checks for
     * the `pt-q` wrapper class (pinned by PtModelsTest as present in
     * every PlayTranslate qfmt) rather than exact string equality —
     * proof that OUR template replaced Anki's auto-generated one,
     * robust to any provider-side whitespace normalization. A read-back
     * QUERY failure alone doesn't fail the install (older providers may
     * not support it) — a positive update row count is then trusted,
     * loudly.
     */
    private fun installTemplates(modelId: Long, spec: PtModels.Spec): Boolean {
        val templateUri = Uri.withAppendedPath(MODEL_URI, "$modelId/templates/0")
        val tv = ContentValues().apply {
            put("question_format", spec.qfmt)
            put("answer_format", spec.afmt)
        }
        val rows = try {
            context.contentResolver.update(templateUri, tv, null, null)
        } catch (e: Exception) {
            Log.e(TAG, "Template update FAILED for '${spec.name}' id=$modelId: ${e.message}", e)
            return false
        }
        if (rows <= 0) {
            Log.e(TAG, "Template update for '${spec.name}' id=$modelId touched 0 rows")
            return false
        }
        return try {
            val (qfmt, afmt) = readStoredTemplate(modelId)
            // Both sides must carry their marker — a partial write that
            // landed only the front would otherwise verify, and the
            // reuse classifier would then read the model as OURS forever.
            val ok = qfmt?.contains("pt-q") == true && afmt?.contains("pt-a") == true
            if (!ok) {
                Log.e(TAG, "Template read-back mismatch for '${spec.name}' id=$modelId: " +
                    "qfmt=${qfmt?.take(80)} afmt=${afmt?.take(80)}")
            }
            ok
        } catch (e: Exception) {
            Log.w(TAG, "Template read-back query failed for '${spec.name}' id=$modelId — " +
                "trusting the $rows-row update: ${e.message}")
            true
        }
    }

    /**
     * All note types as AnkiDroid reports them — including cloze and
     * the synthetic PlayTranslate models. Throws on provider failure
     * so callers can distinguish "query broke" from "model absent" —
     * including a NULL cursor, which ContentResolver uses for
     * provider-missing/permission/remote failures rather than
     * throwing. Treating that as an empty list would let
     * [getOrCreatePtModel] read failure as absence and create a
     * duplicate model.
     */
    private fun queryAllModels(): List<ModelInfo> {
        val result = mutableListOf<ModelInfo>()
        val cursor = context.contentResolver.query(MODEL_URI, null, null, null, null)
            ?: throw IllegalStateException("models query returned null cursor")
        cursor.use { cursor ->
            val idCol     = cursor.getColumnIndex("_id")
            val nameCol   = cursor.getColumnIndex("name")
            val fieldsCol = cursor.getColumnIndex("field_names")
            val typeCol   = cursor.getColumnIndex("type")
            while (cursor.moveToNext()) {
                val id   = if (idCol   >= 0) cursor.getLong(idCol)     else continue
                val name = if (nameCol >= 0) cursor.getString(nameCol) ?: continue else continue
                val rawFields = if (fieldsCol >= 0) cursor.getString(fieldsCol) ?: "" else ""
                val fieldNames = rawFields.split(SEP).filter { it.isNotBlank() }
                val type  = if (typeCol  >= 0) cursor.getInt(typeCol)  else 0
                result += ModelInfo(id, name, fieldNames, type)
            }
        }
        return result
    }

    /**
     * Returns the standard (non-cloze) note types available in
     * AnkiDroid, minus the synthetic PlayTranslate models (blob v003…
     * v005 and the field-based Word/Sentence types) — those are
     * reached via the "Default (PlayTranslate)" sentinel and shouldn't
     * appear in the Card Type picker. Returns empty list on query
     * failure or when AnkiDroid is absent — callers treat empty as
     * "transient" and avoid healing destructively.
     */
    fun getModels(): List<ModelInfo> = try {
        queryAllModels().filter {
            it.type != 1 &&                       // cloze — out of scope
                it.fieldNames.isNotEmpty() &&
                !PtModels.isSyntheticName(it.name)
        }
    } catch (e: Exception) {
        Log.e(TAG, "getModels failed: ${e.message}", e)
        emptyList()
    }

    /**
     * Generalised insert: writes [fields] (joined with the field separator)
     * into a new note of [modelId], then moves the resulting card to
     * [deckId]. The "did" key in the insert ContentValues is ignored by
     * AnkiDroid 2.23.x, so we patch the deck via an update on notes/{id}/cards/0.
     */
    fun addNote(
        modelId: Long,
        deckId: Long,
        fields: List<String>,
        tags: String = "playtranslate",
    ): Boolean {
        if (fields.isEmpty()) {
            Log.e(TAG, "addNote called with empty fields list")
            return false
        }
        val flds = fields.joinToString(SEP)
        val cv = ContentValues().apply {
            put("mid", modelId)
            put("flds", flds)
            put("tags", tags)
            put("did", deckId)
        }
        return try {
            val noteUri = context.contentResolver.insert(NOTE_URI, cv) ?: return false
            val cardValues = ContentValues().apply { put("deck_id", deckId) }

            // Baseline: move cards/0 directly. AnkiDroid 2.23.x ignores
            // the insert-side `did`, so we have to relocate the card
            // ourselves. This direct update worked on every AnkiDroid
            // version that supported the structured insert at all, so
            // we keep it as an unconditional fallback in case the
            // enumeration step below (newer query path) fails for any
            // reason. For single-template note types (the dominant
            // case — the PlayTranslate models, Basic, Lapis, JPMN,
            // Migaku) this also moves
            // the only generated card, so enumeration is a pure
            // additive enhancement.
            try {
                val zeroUri = Uri.withAppendedPath(noteUri, "cards/0")
                context.contentResolver.update(zeroUri, cardValues, null, null)
            } catch (e: Exception) {
                Log.e(TAG, "card deck update failed for ord=0: ${e.message}", e)
            }

            // Multi-card enhancement: enumerate any other generated
            // cards (ord>=1) and move them too. Note types like
            // "Basic (and reversed card)" emit a second card at ord=1
            // that the baseline above doesn't reach. Failures here are
            // non-fatal — the common single-card case is already
            // handled and the worst-case degradation is "second card
            // lands in default deck", which is the pre-multi-card
            // behavior anyway.
            val cardsUri = Uri.withAppendedPath(noteUri, "cards")
            try {
                context.contentResolver.query(cardsUri, arrayOf("ord"), null, null, null)
                    ?.use { cursor ->
                        val ordCol = cursor.getColumnIndex("ord")
                        if (ordCol < 0) return@use
                        while (cursor.moveToNext()) {
                            val ord = cursor.getInt(ordCol)
                            if (ord == 0) continue  // already moved above
                            val cardUri = Uri.withAppendedPath(noteUri, "cards/$ord")
                            try {
                                context.contentResolver.update(cardUri, cardValues, null, null)
                            } catch (e: Exception) {
                                Log.e(TAG, "card deck update failed for ord=$ord: ${e.message}", e)
                            }
                        }
                    }
            } catch (e: Exception) {
                Log.e(TAG, "cards enumeration failed: ${e.message}", e)
            }
            // Note landed — wake any "already in Anki" badge surfaces.
            _noteAddedTick.update { it + 1 }
            true
        } catch (e: Exception) {
            Log.e(TAG, "addNote failed: ${e.message}", e)
            false
        }
    }

    /**
     * Copies [file] into AnkiDroid's media store via FileProvider.
     * Returns the actual filename AnkiDroid assigned, or null on failure.
     */
    fun addMediaFromFile(file: File): String? {
        return try {
            val fileUri = FileProvider.getUriForFile(context, FILE_PROVIDER_AUTHORITY, file)
            context.grantUriPermission(
                "com.ichi2.anki", fileUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            // AnkiDroid builds the stored name as "<preferred>_<unique>.<ext>",
            // so a preferred_name that still carries its own extension yields a
            // doubled "<base>.wav_<unique>.wav". Pass the base name only.
            val cv = ContentValues().apply {
                put("file_uri", fileUri.toString())
                put("preferred_name", file.nameWithoutExtension)
            }
            val resultUri = context.contentResolver.insert(MEDIA_URI, cv) ?: run {
                Log.e(TAG, "addMedia insert returned null")
                return null
            }
            val assignedName = resultUri.lastPathSegment
            // AnkiDroid derives the STORED extension from the URI's MIME type,
            // not from our filename — log both directions of the platform mime
            // map so a mismatch (e.g. .m4a stored as .mp3) is readable here.
            val mime = context.contentResolver.getType(fileUri)
            Log.i(TAG, "addMedia ok: preferred='${file.nameWithoutExtension}' " +
                "ext='${file.extension}' mime='$mime' " +
                "extFromMime='${android.webkit.MimeTypeMap.getSingleton()
                    .getExtensionFromMimeType(mime ?: "")}' " +
                "result='$resultUri' assigned='$assignedName'")
            assignedName
        } catch (e: Exception) {
            Log.e(TAG, "addMedia failed: ${e.message}", e)
            null
        }
    }
}
