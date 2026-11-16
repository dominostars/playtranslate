package com.playtranslate.ui

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.playtranslate.R
import com.playtranslate.audio.AudioCache
import com.playtranslate.audio.localaudio.LocalAudioDb
import com.playtranslate.audio.localaudio.LocalAudioFixture
import com.playtranslate.audio.localaudio.LocalAudioImportStep
import com.playtranslate.audio.localaudio.LocalAudioStore
import com.playtranslate.audio.sources.LocalAudioSource
import com.playtranslate.language.SourceLangId
import com.playtranslate.themeColor
import com.playtranslate.translation.llm.humanSize
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.shadows.ShadowLog
import org.robolectric.shadows.ShadowStatFs
import java.io.File
import java.io.RandomAccessFile

/**
 * [ImportedRecordingsSection], on the Text-to-speech page that hosts it:
 * the one row that loads a file while there is none; the file and the row
 * that removes it while there is one; a load that lands, one that is
 * refused with its cause, a cancel, and a removal. And what its text says
 * and does not say.
 */
@RunWith(RobolectricTestRunner::class)
class ImportedRecordingsSectionTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val picked get() = File(ctx.cacheDir, "local-audio-picked")
    private var controller: ActivityController<TtsVoiceActivity>? = null

    private val loadSubtext = "If you have an android.db file of voice recordings, you can load it here. " +
        "You must source this file yourself. This only works for words, and any word without an entry " +
        "in the android.db file will fall back to your other TTS settings."

    @Before fun setUp() {
        LocalAudioFixture.clear(ctx)
        picked.deleteRecursively()
        File(ctx.cacheDir, "audioCache").deleteRecursively()
        ShadowStatFs.registerStats(ctx.noBackupFilesDir, 1 shl 24, 1 shl 21, 1 shl 21)
    }

    @After fun tearDown() {
        controller?.let { runCatching { it.pause().stop().destroy() } }
        LocalAudioFixture.clear(ctx)
        picked.deleteRecursively()
        File(ctx.cacheDir, "audioCache").deleteRecursively()
        ShadowStatFs.reset()
    }

    private fun open(lang: SourceLangId = SourceLangId.JA): TtsVoiceActivity =
        Robolectric.buildActivity(TtsVoiceActivity::class.java, TtsVoiceActivity.intent(ctx, lang))
            .also { controller = it }.setup().get()

    private fun pumpUntil(what: String, done: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!done()) {
            assertTrue("timed out waiting for $what", System.currentTimeMillis() < deadline)
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
    }

    private fun descendants(root: View): Sequence<View> = sequence {
        yield(root)
        if (root is ViewGroup) for (i in 0 until root.childCount) yieldAll(descendants(root.getChildAt(i)))
    }

    /** Every text drawn in the page's window, a popup's or an alert's included. */
    private fun TtsVoiceActivity.texts(): List<String> =
        descendants(window.decorView).filterIsInstance<TextView>()
            .filter { it.visibility == View.VISIBLE }.map { it.text.toString() }.toList()

    private fun TtsVoiceActivity.group(): LinearLayout = findViewById(R.id.recordingRows)

    /** The group's rows, top to bottom. */
    private fun TtsVoiceActivity.rows(): List<View> =
        (0 until group().childCount).map { group().getChildAt(it) }
            .filter { it.findViewById<TextView>(R.id.tvRowTitle) != null }

    /** The texts of the group's rows, in order: a row's title, then its
     *  subtitle when it shows one. */
    private fun TtsVoiceActivity.rowTexts(): List<String> =
        descendants(group()).filterIsInstance<TextView>()
            .filter { it.visibility == View.VISIBLE }.map { it.text.toString() }.toList()

    private fun TtsVoiceActivity.row(title: String): View =
        rows().single { it.findViewById<TextView>(R.id.tvRowTitle).text.toString() == title }

    private fun TtsVoiceActivity.pressAlertButton(label: String) {
        descendants(window.decorView).filterIsInstance<Button>().single { it.text.toString() == label }.performClick()
        shadowOf(Looper.getMainLooper()).idle()
    }

    /** What the group reads with [file] loaded: one row, the file and its
     *  size. Removing is the trash at that row's end, which has no text. */
    private fun loadedRows(file: File) = listOf("android.db file", humanSize(ctx, file.length()))

    private fun TtsVoiceActivity.trash(): ImageView = row("android.db file").findViewById(R.id.ivRowDelete)

    // ── The two states ───────────────────────────────────────────────────

    @Test fun `with no file the group is the row that loads one, saying what a file does and that the user brings it`() {
        val page = open()
        assertEquals(listOf("Load android.db file", loadSubtext), page.rowTexts())
    }

    @Test fun `the row that loads opens the system's file picker on files of any type`() {
        val page = open()
        page.row("Load android.db file").performClick()
        val asked = shadowOf(page).nextStartedActivityForResult.intent
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, asked.action)
        // The types offered travel as an extra; the intent's own type is
        // every type whatever is asked for.
        assertEquals(listOf("*/*"), asked.getStringArrayExtra(Intent.EXTRA_MIME_TYPES)?.toList())
    }

    @Test fun `the file picked in the picker is the one loaded`() {
        val source = LocalAudioFixture.build(File(picked, "android.db"))
        val page = open()
        page.row("Load android.db file").performClick()
        val asked = shadowOf(page).nextStartedActivityForResult
        shadowOf(page).receiveResult(asked.intent, Activity.RESULT_OK, Intent().setData(Uri.fromFile(source)))
        pumpUntil("the load to land") { "android.db file" in page.rowTexts() }
        assertEquals(source.length(), LocalAudioStore.sizeBytes(ctx))
    }

    @Test fun `leaving the picker with nothing picked loads nothing`() {
        val page = open()
        page.row("Load android.db file").performClick()
        val asked = shadowOf(page).nextStartedActivityForResult
        shadowOf(page).receiveResult(asked.intent, Activity.RESULT_CANCELED, null)
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse("no popup", "Loading android.db file" in page.texts())
        assertEquals("Load android.db file", page.rowTexts().first())
    }

    @Test fun `the page handles a rotation itself, so turning the device does not end a copy`() {
        val info = ctx.packageManager.getActivityInfo(ComponentName(ctx, TtsVoiceActivity::class.java), 0)
        val handled = ActivityInfo.CONFIG_ORIENTATION or ActivityInfo.CONFIG_SCREEN_SIZE or
            ActivityInfo.CONFIG_SCREEN_LAYOUT or ActivityInfo.CONFIG_SMALLEST_SCREEN_SIZE
        assertEquals(handled, info.configChanges and handled)
    }

    @Test fun `with a file the group is one row, the file with its size and a trash in the alert colour at its end`() {
        val file = LocalAudioFixture.install(ctx)
        val page = open()
        assertEquals(loadedRows(file), page.rowTexts())
        assertEquals("one row: removing is not a row of its own", 1, page.rows().size)
        assertFalse("the file's row is nothing to tap", page.row("android.db file").isClickable)

        val trash = page.trash()
        assertEquals(View.VISIBLE, trash.visibility)
        assertTrue("the trash is what is tapped", trash.isClickable)
        assertEquals(page.themeColor(R.attr.ptDanger), trash.imageTintList?.defaultColor)
        assertEquals(R.drawable.ic_delete, shadowOf(trash.drawable).createdFromResId)
        assertEquals("what it does, for a screen reader", "Remove android.db file", trash.contentDescription)
        val row = page.row("android.db file") as ViewGroup
        assertEquals("at the row's end", row.childCount - 1, row.indexOfChild(trash))
    }

    @Test fun `a sound file has no warning under it`() {
        val file = LocalAudioFixture.install(ctx)
        val page = open()
        shadowOf(Looper.getMainLooper()).idle()
        pumpUntil("the check of the file") { LocalAudioDb.problem == null && page.rowTexts() == loadedRows(file) }
        assertEquals(View.GONE, page.row("android.db file").findViewById<View>(R.id.tvRowWarning).visibility)
    }

    @Test fun `a loaded file that no longer opens says so under its size, in the alert colour, with the cause`() {
        val file = LocalAudioStore.file(ctx).apply { parentFile?.mkdirs(); writeText("not a database\n".repeat(100)) }
        val page = open()
        val warning = "Reading this file failed: file is not a database"
        pumpUntil("the check of the file") { warning in page.rowTexts() }
        assertEquals(listOf("android.db file", humanSize(ctx, file.length()), warning), page.rowTexts())
        assertEquals(
            page.themeColor(R.attr.ptDanger),
            page.row("android.db file").findViewById<TextView>(R.id.tvRowWarning).currentTextColor,
        )
        assertTrue("the file is still the user's to remove", page.trash().isClickable)
    }

    @Test fun `a read that failed while the page was away is said on the return to it`() {
        val file = LocalAudioFixture.install(ctx)
        val page = open()
        pumpUntil("the check of the file") { page.rowTexts() == loadedRows(file) }
        controller!!.pause()
        // A word is tapped elsewhere, and its recording is on a damaged page.
        RandomAccessFile(file, "rw").use { raf ->
            raf.seek(4096)
            raf.write(ByteArray((file.length() - 4096).toInt()) { 0x5A })
        }
        runBlocking { LocalAudioDb.rows(ctx, "食べる") }
        controller!!.resume()
        pumpUntil("the warning") { page.rowTexts().any { it.startsWith("Reading this file failed: ") } }
        assertTrue("Reading this file failed: database disk image is malformed" in page.rowTexts())
    }

    @Test fun `removing a damaged file removes its warning with it`() {
        LocalAudioStore.file(ctx).apply { parentFile?.mkdirs(); writeText("not a database\n".repeat(100)) }
        val page = open()
        pumpUntil("the check of the file") { page.rowTexts().any { it.startsWith("Reading this file failed: ") } }
        page.trash().performClick()
        shadowOf(Looper.getMainLooper()).idle()
        page.pressAlertButton("Remove android.db file")
        pumpUntil("the file to be removed") { "Load android.db file" in page.rowTexts() }
        assertNull(LocalAudioDb.problem)
        assertEquals(listOf("Load android.db file", loadSubtext), page.rowTexts())
    }

    @Test fun `the group is the same on the page of another language`() {
        val file = LocalAudioFixture.install(ctx)
        assertEquals(loadedRows(file), open(SourceLangId.ES).rowTexts())
    }

    // ── What the text says and does not say ──────────────────────────────

    @Test fun `no text of the feature names a language or calls the file local audio`() {
        val names = R.string::class.java.fields.map { it.name }
            .filter { it.startsWith("local_audio_") || it == "audio_source_local_name" || it == "tts_recordings_section_header" }
        assertTrue("the feature's strings are found by their names", names.size >= 10)
        for (name in names) {
            val text = ctx.getString(R.string::class.java.getField(name).getInt(null))
            assertFalse("$name: $text", text.contains("Japanese", ignoreCase = true))
            assertFalse("$name: $text", text.contains("local audio", ignoreCase = true))
            assertFalse("$name says where a file is found: $text", text.contains("http", ignoreCase = true))
        }
    }

    // ── Loading ──────────────────────────────────────────────────────────

    @Test fun `a load lands and the group becomes the file's`() {
        val source = LocalAudioFixture.build(File(picked, "android.db"))
        val page = open()
        page.recordings.startImport(Uri.fromFile(source))
        pumpUntil("the load to land") { "android.db file" in page.rowTexts() }
        assertTrue(LocalAudioStore.isImported(ctx))
        assertEquals(loadedRows(source), page.rowTexts())
        assertFalse("the progress popup is gone", "Loading android.db file" in page.texts())
        assertFalse("and no alert stands in its place", "Couldn't load the file" in page.texts())
        // What the file holds goes to the log, after the popup.
        pumpUntil("the load's line in the log") {
            ShadowLog.getLogsForTag("PtAudio").any {
                it.msg == "Local audio imported: bytes=${source.length()} " +
                    "sources=[nhk16, shinmeikai8, forvo, my_recordings] clips={mp3=2, ogg=1, opus=3}"
            }
        }
    }

    @Test fun `a second load asked for while one is running is not started`() {
        val source = LocalAudioFixture.build(File(picked, "android.db"))
        val page = open()
        page.recordings.startImport(Uri.fromFile(source))
        page.recordings.startImport(Uri.fromFile(source))
        assertEquals("one popup", 1, page.texts().count { it == "Loading android.db file" })
        pumpUntil("the load to land") { "android.db file" in page.rowTexts() }
    }

    @Test fun `Cancel on the popup ends the load with no alert, and the group shows what is on disk`() {
        // Megabytes to copy, so that the copy is still running at the press.
        val source = LocalAudioFixture.build(
            File(picked, "android.db"),
            (1..12).map { n ->
                LocalAudioFixture.Recording("語$n", null, "forvo", "clip$n.mp3", data = ByteArray(1 shl 20) { n.toByte() })
            },
        )
        val page = open()
        page.recordings.startImport(Uri.fromFile(source))
        assertTrue("the popup is up", "Loading android.db file" in page.texts())
        page.pressAlertButton("Cancel")
        pumpUntil("the cancelled load to end") {
            "Loading android.db file" !in page.texts() &&
                !File(LocalAudioStore.file(ctx).path + ".part").exists()
        }
        assertFalse("a cancel is not a failure", "Couldn't load the file" in page.texts())
        // The press and the copy's last step race. Whichever won, the group
        // and the disk agree.
        assertEquals(LocalAudioStore.isImported(ctx), "android.db file" in page.rowTexts())
        assertEquals(!LocalAudioStore.isImported(ctx), "Load android.db file" in page.rowTexts())
    }

    @Test fun `a picked file that is no database is refused in an alert and nothing changes`() {
        val notes = File(picked, "notes.txt").apply { parentFile?.mkdirs(); writeText("some notes\n".repeat(100)) }
        val page = open()
        page.recordings.startImport(Uri.fromFile(notes))
        pumpUntil("the refusal") { "Couldn't load the file" in page.texts() }
        assertTrue("This is not an android.db file of voice recordings." in page.texts())
        assertFalse(LocalAudioStore.isImported(ctx))
        assertEquals("Load android.db file", page.rowTexts().first())
        page.pressAlertButton("OK")
        assertFalse("Couldn't load the file" in page.texts())
    }

    @Test fun `a database of something else is refused with what SQLite found missing`() {
        val other = LocalAudioFixture.build(File(picked, "other.db"), schema = listOf(LocalAudioFixture.CREATE_ENTRIES))
        val page = open()
        page.recordings.startImport(Uri.fromFile(other))
        pumpUntil("the refusal") { "Couldn't load the file" in page.texts() }
        assertTrue(
            "This database is not an android.db file of voice recordings (no such table: android)." in page.texts(),
        )
        assertFalse(LocalAudioStore.isImported(ctx))
    }

    @Test fun `a file that does not fit is refused with the space it needs and the space there is`() {
        val source = LocalAudioFixture.build(File(picked, "android.db"))
        ShadowStatFs.registerStats(ctx.noBackupFilesDir, 1 shl 24, 100, 100)
        val page = open()
        page.recordings.startImport(Uri.fromFile(source))
        pumpUntil("the refusal") { "Couldn't load the file" in page.texts() }
        val needed = humanSize(ctx, source.length() + 100_000_000L)
        val free = humanSize(ctx, 100L * 4096)
        assertTrue("Not enough free space ($needed needed, $free available)" in page.texts())
    }

    @Test fun `a file cut short is refused with its size and the size it should have`() {
        val source = LocalAudioFixture.build(File(picked, "android.db"))
        val whole = source.length()
        RandomAccessFile(source, "rw").use { it.setLength(whole - 8192) }
        val page = open()
        page.recordings.startImport(Uri.fromFile(source))
        pumpUntil("the refusal") { "Couldn't load the file" in page.texts() }
        val have = humanSize(ctx, whole - 8192)
        val expected = humanSize(ctx, whole)
        assertTrue("This file is incomplete: it is $have of $expected. Get the whole file, then load it again." in page.texts())
        assertFalse(LocalAudioStore.isImported(ctx))
    }

    @Test fun `a copy that failed is said with the system's own words beneath`() {
        val page = open()
        page.recordings.startImport(Uri.fromFile(File(picked, "missing.db")))
        pumpUntil("the failure") { "Couldn't load the file" in page.texts() }
        val message = page.texts().single { it.startsWith("The file could not be copied.") }
        assertTrue(message, message.startsWith("The file could not be copied.\n\n"))
        assertTrue("the path the system could not open", "missing.db" in message)
    }

    @Test fun `the popup counts a copy against the whole, and only counts when there is no whole`() {
        val section = open().recordings
        val mb = 1L shl 20
        val half = LocalAudioImportStep.Copying(copiedBytes = 512 * mb, totalBytes = 1024 * mb)
        assertEquals("${humanSize(ctx, 512 * mb)} of ${humanSize(ctx, 1024 * mb)} copied", section.stepMessage(half))
        assertEquals(50, section.stepPercent(half))
        assertEquals(100, section.stepPercent(LocalAudioImportStep.Copying(copiedBytes = 3 * mb, totalBytes = 2 * mb)))

        val sizeless = LocalAudioImportStep.Copying(copiedBytes = 512 * mb, totalBytes = -1)
        assertEquals(humanSize(ctx, 512 * mb), section.stepMessage(sizeless))
        assertNull("a bar that only moves", section.stepPercent(sizeless))

        assertEquals("Checking the file…", section.stepMessage(LocalAudioImportStep.Checking))
        assertNull(section.stepPercent(LocalAudioImportStep.Checking))
    }

    // ── Removing ─────────────────────────────────────────────────────────

    @Test fun `the trash asks first, and confirmed it deletes the file and its clips and the group offers to load again`() {
        LocalAudioFixture.install(ctx)
        runBlocking { AudioCache(ctx).putClip(LocalAudioSource.ID, "nhk16/audio/taberu.opus", byteArrayOf(1), null) }
        val page = open()

        page.trash().performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue("Remove the android.db file?" in page.texts())
        assertTrue("The file is deleted from the app, and every word is spoken by text-to-speech again." in page.texts())
        assertTrue("nothing is deleted by the tap alone", LocalAudioStore.isImported(ctx))

        page.pressAlertButton("Cancel")
        assertTrue(LocalAudioStore.isImported(ctx))

        page.trash().performClick()
        shadowOf(Looper.getMainLooper()).idle()
        // The alert's red button says what the trash does.
        page.pressAlertButton("Remove android.db file")
        pumpUntil("the file to be removed") { !LocalAudioStore.isImported(ctx) && "Load android.db file" in page.rowTexts() }
        assertEquals(listOf("Load android.db file", loadSubtext), page.rowTexts())
        assertNull(runBlocking { AudioCache(ctx).getClip(LocalAudioSource.ID, "nhk16/audio/taberu.opus") })
    }

    @Test fun `loading and removing leave the voice that is chosen alone`() {
        val page = open()
        val before = ctx.getSharedPreferences("playtranslate_prefs", Context.MODE_PRIVATE).all.toMap()
        page.recordings.startImport(Uri.fromFile(LocalAudioFixture.build(File(picked, "android.db"))))
        pumpUntil("the load to land") { "android.db file" in page.rowTexts() }
        assertEquals(before, ctx.getSharedPreferences("playtranslate_prefs", Context.MODE_PRIVATE).all.toMap())
        assertFalse("the page is still open: a load is not Save", page.isFinishing)
    }
}
