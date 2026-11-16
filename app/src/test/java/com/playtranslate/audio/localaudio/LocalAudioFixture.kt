package com.playtranslate.audio.localaudio

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * A small local audio database for tests, made with the statements the
 * desktop add-on that writes the real file makes it with
 * (`plugin/db_utils.py` of yomidevs/local-audio-yomichan, read 2026-11-14):
 * the two tables and every index. The real file is several gigabytes and is
 * not in the repository; this is its shape.
 */
internal object LocalAudioFixture {

    const val CREATE_ENTRIES = "CREATE TABLE entries (" +
        "id integer PRIMARY KEY NOT NULL, expression text NOT NULL, reading text, " +
        "source text NOT NULL, speaker text, display text, file text NOT NULL)"

    const val CREATE_ANDROID = "CREATE TABLE android (" +
        "id integer PRIMARY KEY NOT NULL, file text NOT NULL, source text NOT NULL, data blob NOT NULL)"

    val CREATE_INDEXES = listOf(
        "CREATE INDEX idx_speaker ON entries(speaker)",
        "CREATE INDEX idx_reading ON entries(reading)",
        "CREATE INDEX idx_expr_reading ON entries(expression, reading)",
        "CREATE INDEX idx_reading_speaker ON entries(expression, reading, speaker)",
        "CREATE INDEX idx_all ON entries(expression, reading, source)",
        "CREATE INDEX idx_android ON android(file, source)",
    )

    val SCHEMA = listOf(CREATE_ENTRIES, CREATE_ANDROID) + CREATE_INDEXES

    /** One recording: its row in `entries` and its clip in `android`. */
    class Recording(
        val expression: String,
        val reading: String?,
        val source: String,
        val file: String,
        val speaker: String? = null,
        val display: String? = null,
        val data: ByteArray = "$source/$file".toByteArray(),
    )

    /** The recordings most tests read. */
    val RECORDINGS = listOf(
        // A reading in katakana, as NHK's are, with the accent form as its label.
        Recording("食べる", "タベル", "nhk16", "audio/taberu.opus", display = "タベ＼ル"),
        Recording("食べる", "たべる", "shinmeikai8", "media/taberu.mp3"),
        // Forvo: no reading, a speaker.
        Recording("食べる", null, "forvo", "strawberrybrown/食べる.mp3", speaker = "strawberrybrown", display = "strawberrybrown"),
        // One written form, two readings.
        Recording("日本", "ニホン", "nhk16", "audio/nihon.opus", display = "ニホ＼ン"),
        Recording("日本", "ニッポン", "nhk16", "audio/nippon.opus", display = "ニッポ＼ン"),
        // A source the app has no name for.
        Recording("犬", "いぬ", "my_recordings", "inu.ogg"),
    )

    /**
     * Writes a database of [recordings] at [file], replacing what is there.
     * [schema] is the statements it is made with; [wal] leaves it in
     * write-ahead-log mode, which the file's header then records.
     */
    fun build(
        file: File,
        recordings: List<Recording> = RECORDINGS,
        schema: List<String> = SCHEMA,
        wal: Boolean = false,
    ): File {
        file.parentFile?.mkdirs()
        SQLiteDatabase.deleteDatabase(file)
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            if (wal) db.enableWriteAheadLogging()
            schema.forEach(db::execSQL)
            val hasClips = schema.any { it.startsWith("CREATE TABLE android") }
            recordings.forEach { r ->
                db.insertOrThrow(
                    "entries",
                    null,
                    ContentValues().apply {
                        put("expression", r.expression)
                        put("reading", r.reading)
                        put("source", r.source)
                        put("speaker", r.speaker)
                        put("display", r.display)
                        put("file", r.file)
                    },
                )
                if (hasClips) {
                    db.insertOrThrow(
                        "android",
                        null,
                        ContentValues().apply {
                            put("file", r.file)
                            put("source", r.source)
                            put("data", r.data)
                        },
                    )
                }
            }
        }
        return file
    }

    /** Puts a database of [recordings] where an imported file lives, as an
     *  import would have, without the copy: through the one step a loaded
     *  file is replaced in ([LocalAudioDb.replacing]). */
    fun install(ctx: Context, recordings: List<Recording> = RECORDINGS): File =
        runBlocking { LocalAudioDb.replacing(ctx) { build(LocalAudioStore.file(ctx), recordings) } }

    /** Everything the store keeps on disk, gone, and the reader let go of
     *  it: a test starts and ends with no file loaded. */
    fun clear(ctx: Context) {
        runBlocking { LocalAudioDb.replacing(ctx) { LocalAudioStore.file(ctx).parentFile?.deleteRecursively() } }
    }
}
