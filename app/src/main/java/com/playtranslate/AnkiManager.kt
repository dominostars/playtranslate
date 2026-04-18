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

    companion object {
        const val PERMISSION = "com.ichi2.anki.permission.READ_WRITE_DATABASE"

        private const val AUTHORITY = "com.ichi2.anki.flashcards"
        private const val FILE_PROVIDER_AUTHORITY = "com.playtranslate.fileprovider"
        private const val MODEL_NAME = "PlayTranslate v004"

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
     * Adds a note to AnkiDroid and moves its cards to [deckId].
     * [front] and [back] are pre-built HTML strings.
     * Returns true on success.
     */
    fun addNote(deckId: Long, front: String, back: String): Boolean {
        val modelId = getOrCreateModel() ?: return false

        Log.d(TAG, "addNote front(${front.length}) back(${back.length})")

        val fields = listOf(front, back).joinToString(SEP)
        val cv = ContentValues().apply {
            put("mid", modelId)
            put("flds", fields)
            put("tags", "playtranslate")
            put("did", deckId)
        }

        return try {
            val noteUri = context.contentResolver.insert(NOTE_URI, cv) ?: return false
            // "did" in the insert ContentValues is ignored by AnkiDroid 2.23.x.
            // Move the card by updating notes/{id}/cards/0 with deck_id.
            val cardUri = Uri.withAppendedPath(noteUri, "cards/0")
            val cardValues = ContentValues().apply { put("deck_id", deckId) }
            try {
                context.contentResolver.update(cardUri, cardValues, null, null)
            } catch (e: Exception) {
                Log.e(TAG, "card deck update failed: ${e.message}", e)
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
