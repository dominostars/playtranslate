package com.playtranslate

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File

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
     * `sortf` is the model's sort-field index (which field AnkiDroid uses
     * for duplicate detection + browser sorting).
     */
    data class ModelInfo(
        val id: Long,
        val name: String,
        val fieldNames: List<String>,
        val type: Int,
        val sortf: Int,
    )

    companion object {
        const val PERMISSION = "com.ichi2.anki.permission.READ_WRITE_DATABASE"

        private const val AUTHORITY = "com.ichi2.anki.flashcards"
        private const val FILE_PROVIDER_AUTHORITY = "com.playtranslate.fileprovider"
        const val MODEL_NAME = "PlayTranslate v004"

        /** AnkiDroid field separator (ASCII 31, unit separator) */
        private const val SEP = "\u001f"

        private val DECK_URI  = Uri.parse("content://$AUTHORITY/decks")
        private val NOTE_URI  = Uri.parse("content://$AUTHORITY/notes")
        private val MODEL_URI = Uri.parse("content://$AUTHORITY/models")
        private val MEDIA_URI = Uri.parse("content://$AUTHORITY/media")
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
     * Returns the ID of the "PlayTranslate" model, creating it if it doesn't exist yet.
     * Returns null on failure.
     */
    fun getOrCreateModel(): Long? {
        val expectedFields = listOf("Expression", "Back").joinToString(SEP)

        // Find any existing PlayTranslate model whose field_names match the expected 2-field schema.
        // Name-only matching is unreliable because old models with the same name but different
        // fields may already exist in AnkiDroid from a previous version.
        try {
            context.contentResolver.query(MODEL_URI, null, null, null, null)?.use { cursor ->
                val idCol     = cursor.getColumnIndex("_id")
                val nameCol   = cursor.getColumnIndex("name")
                val fieldsCol = cursor.getColumnIndex("field_names")
                while (cursor.moveToNext()) {
                    val name   = if (nameCol   >= 0) cursor.getString(nameCol)   else continue
                    val fields = if (fieldsCol >= 0) cursor.getString(fieldsCol) else ""
                    if (name == MODEL_NAME && fields == expectedFields) {
                        val id = if (idCol >= 0) cursor.getLong(idCol) else null
                        Log.d(TAG, "Reusing existing model id=$id")
                        return id
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Model query failed: ${e.message}", e)
            return null
        }
        Log.d(TAG, "Creating new Anki model '$MODEL_NAME'")

        // 2-field model: Expression (source text front) + Back (full HTML blob).
        // qfmt centers and enlarges the expression text.
        // afmt shows only {{Back}} — the Back field already contains the full card back
        // (image, annotated sentence, translation, definitions), so no FrontSide duplication
        // and no auto-generated \n\n separator artifacts.
        val fieldNames = listOf("Expression", "Back").joinToString(SEP)
        val qfmt = """<div style="text-align:center;font-size:1.5em;padding:20px;line-height:1.5;">{{Expression}}</div>"""
        val afmt = """{{Back}}"""
        val css = """
            @media(prefers-color-scheme:light){
              .card{background-color:#F0F0F0;color:#1C1C1C}
              .gl-secondary{color:#505050}
              .gl-hint{color:#909090}
              .gl-hl{color:#B34700}
              .gl-hl-bg{background:#B3470026}
            }
            @media(prefers-color-scheme:dark){
              .card{background-color:#1A1A1A;color:#EFEFEF}
              .gl-secondary{color:#A0A0A0}
              .gl-hint{color:#606060}
              .gl-hl{color:#E8C07A}
              .gl-hl-bg{background:#E8C07A26}
            }
        """.trimIndent().replace("\n", " ")

        val cv = ContentValues().apply {
            put("name", MODEL_NAME)
            put("field_names", fieldNames)
            put("num_cards", 1)
            put("css", css)
            put("qfmt", qfmt)
            put("afmt", afmt)
        }

        return try {
            val uri = context.contentResolver.insert(MODEL_URI, cv) ?: run {
                Log.e(TAG, "Model insert returned null URI")
                return null
            }
            val id = uri.lastPathSegment?.toLongOrNull()
            Log.d(TAG, "Created model id=$id")
            id
        } catch (e: Exception) {
            Log.e(TAG, "Model create failed: ${e.message}", e)
            null
        }
    }

    /**
     * Returns the standard (non-cloze) note types available in AnkiDroid,
     * minus the synthetic v004 model (which is reached via the "Default
     * (PlayTranslate)" sentinel and shouldn't appear in the Card Type
     * picker). Returns empty list on query failure or when AnkiDroid is
     * absent — callers treat empty as "transient" and avoid healing
     * destructively.
     */
    fun getModels(): List<ModelInfo> {
        val result = mutableListOf<ModelInfo>()
        try {
            context.contentResolver.query(MODEL_URI, null, null, null, null)?.use { cursor ->
                val idCol     = cursor.getColumnIndex("_id")
                val nameCol   = cursor.getColumnIndex("name")
                val fieldsCol = cursor.getColumnIndex("field_names")
                val typeCol   = cursor.getColumnIndex("type")
                // The documented FlashCardsContract column is
                // `sort_field_index`; older AnkiDroid revisions exposed
                // the column as `sortf` matching Anki desktop's
                // database schema. Probe the documented name first
                // and fall back to the legacy alias — without this
                // probe, `getColumnIndex` returns -1 on modern
                // AnkiDroid and our sort-field guard would always
                // inspect field index 0 regardless of the model's
                // real sort field.
                val sortfCol = cursor.getColumnIndex("sort_field_index")
                    .takeIf { it >= 0 }
                    ?: cursor.getColumnIndex("sortf")
                while (cursor.moveToNext()) {
                    val id   = if (idCol   >= 0) cursor.getLong(idCol)     else continue
                    val name = if (nameCol >= 0) cursor.getString(nameCol) ?: continue else continue
                    val rawFields = if (fieldsCol >= 0) cursor.getString(fieldsCol) ?: "" else ""
                    val fieldNames = rawFields.split(SEP).filter { it.isNotBlank() }
                    if (fieldNames.isEmpty()) continue
                    val type  = if (typeCol  >= 0) cursor.getInt(typeCol)  else 0
                    val sortf = if (sortfCol >= 0) cursor.getInt(sortfCol) else 0
                    if (type == 1) continue                  // cloze — out of scope
                    if (name == MODEL_NAME) continue         // synthetic v004 reached via Default sentinel
                    result += ModelInfo(id, name, fieldNames, type, sortf)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "getModels failed: ${e.message}", e)
        }
        return result
    }

    /**
     * Adds a note to AnkiDroid for the legacy v004 path. Resolves v004's
     * model id (creating it if necessary), then forwards to
     * [addNote(modelId, deckId, fields, tags)].
     */
    fun addNote(deckId: Long, front: String, back: String): Boolean {
        val modelId = getOrCreateModel() ?: return false
        Log.d(TAG, "addNote front(${front.length}) back(${back.length})")
        return addNote(modelId, deckId, listOf(front, back))
    }

    /**
     * Generalised insert: writes [fields] (joined with the field separator)
     * into a new note of [modelId], then moves the resulting card to
     * [deckId]. The "did" key in the insert ContentValues is ignored by
     * AnkiDroid 2.23.x, so we patch the deck via an update on notes/{id}/cards/0
     * — same workaround as the legacy 2-arg overload.
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
            // case — v004, Basic, Lapis, JPMN, Migaku) this also moves
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
            val cv = ContentValues().apply {
                put("file_uri", fileUri.toString())
                put("preferred_name", file.name)
            }
            val resultUri = context.contentResolver.insert(MEDIA_URI, cv) ?: run {
                Log.e(TAG, "addMedia insert returned null")
                return null
            }
            Log.d(TAG, "addMedia ok")
            resultUri.lastPathSegment
        } catch (e: Exception) {
            Log.e(TAG, "addMedia failed: ${e.message}", e)
            null
        }
    }
}
