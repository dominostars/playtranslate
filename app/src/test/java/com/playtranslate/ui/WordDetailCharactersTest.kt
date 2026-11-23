package com.playtranslate.ui

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.playtranslate.Prefs
import com.playtranslate.R
import com.playtranslate.language.PreloadResult
import com.playtranslate.language.SourceLangId
import com.playtranslate.language.SourceLanguageEngine
import com.playtranslate.language.SourceLanguageEngines
import com.playtranslate.language.SourceLanguageProfiles
import com.playtranslate.language.TokenSpan
import com.playtranslate.model.CharacterDetail
import com.playtranslate.model.DictionaryEntry
import com.playtranslate.model.DictionaryResponse
import com.playtranslate.model.HanziDetail
import com.playtranslate.model.HanziEntry
import com.playtranslate.model.Headword
import com.playtranslate.model.KanjiDetail
import com.playtranslate.model.Sense
import com.playtranslate.translation.BackendId
import com.playtranslate.translation.TranslationBackend
import com.playtranslate.translation.TranslationBackendRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.util.ReflectionHelpers
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * The word page's Hanzi and Kanji group as the page builds it: the real
 * [WordDetailBinder] bound on the page's layout in an Activity, over an
 * engine of the test's that answers three words and breaks each down into
 * the characters the test names.
 *
 * Pins: the page hands the engine its headword as written with the
 * headword's reading, which is what lets the engine lead a hanzi with the
 * reading this word uses; a hanzi with several entries shows every one, in
 * the engine's order, each entry's meanings directly over its own pinyin;
 * the gap between two entries is wider than the one between an entry's
 * meanings and its pinyin, and the row's meta line stands the same gap
 * below the last entry, so it reads with none of them; the tile of such a
 * row stands at the top. A row with one entry, and a kanji's, is laid out
 * as it was. For a target language the meanings are not written in, each
 * entry's meanings are machine translated apart and land on their own
 * line, and a character whose meanings are already in that language is
 * left as it is.
 */
@RunWith(RobolectricTestRunner::class)
class WordDetailCharactersTest {

    class Host : Activity() {
        override fun onCreate(savedInstanceState: Bundle?) {
            setTheme(R.style.Theme_PlayTranslate)
            super.onCreate(savedInstanceState)
        }
    }

    private companion object {
        fun word(written: String, reading: String, id: Long, gloss: String) = DictionaryEntry(
            slug = written, packId = id, isCommon = false, tags = emptyList(), jlpt = emptyList(),
            headwords = listOf(Headword(written, reading)),
            senses = listOf(Sense(listOf(gloss), emptyList(), emptyList(), emptyList(), emptyList())),
        )

        val BANK = word("银行", "yín háng", 1L, "bank")
        val STUDENT = word("学生", "がくせい", 2L, "student")
        val SAND = word("沙", "shā", 3L, "sand")

        val YIN = HanziDetail(
            literal = '银', entries = listOf(HanziEntry(listOf("yín"), listOf("silver", "money"))),
            isCommon = true, freqScore = 3,
        )
        val HANG = HanziDetail(
            literal = '行',
            entries = listOf(
                HanziEntry(listOf("háng"), listOf("row", "profession")),
                HanziEntry(listOf("xíng"), listOf("to walk", "to go")),
                HanziEntry(listOf("Xíng"), listOf("surname Xing")),
            ),
            isCommon = false, freqScore = 4,
        )

        /** 沙 with an imported German dictionary's meanings, which are one
         *  block under every reading. */
        val SHA_IMPORTED = HanziDetail(
            literal = '沙', entries = listOf(HanziEntry(listOf("shā", "shà", "Shā"), listOf("Sand", "Kies"))),
            isCommon = false, freqScore = 0, meaningsLang = "de",
        )
        val GAKU = KanjiDetail(
            literal = '学', meanings = listOf("study", "learning"), meaningsLang = "en",
            onReadings = listOf("ガク"), kunReadings = listOf("まな.ぶ"), jlpt = 5, grade = 1, strokeCount = 8,
        )
    }

    /** The test's dictionary: three words, each broken down as the test
     *  says, and every breakdown asked for is recorded. */
    private class FakeEngine : SourceLanguageEngine {
        val breakdowns = mutableListOf<Triple<String, String?, String>>()
        var characters: Map<String, List<CharacterDetail>> = mapOf(
            "银行" to listOf(YIN, HANG), "学生" to listOf(GAKU), "沙" to listOf(SHA_IMPORTED),
        )
        override val profile = SourceLanguageProfiles[SourceLangId.ZH]
        override suspend fun preload(): PreloadResult = PreloadResult.Success
        override suspend fun tokenize(text: String): List<TokenSpan> = listOf(TokenSpan(text, text))
        override suspend fun lookup(word: String, reading: String?): DictionaryResponse? =
            listOf(BANK, STUDENT, SAND).firstOrNull { it.slug == word }?.let { DictionaryResponse(listOf(it)) }
        override suspend fun characterBreakdown(written: String, reading: String?, targetLang: String): List<CharacterDetail> {
            synchronized(breakdowns) { breakdowns += Triple(written, reading, targetLang) }
            return characters[written].orEmpty()
        }
        override fun close() {}
    }

    private class QuietUi : WordDetailBinder.Ui {
        var alive = true
        override val isAlive: Boolean get() = alive
        override fun sentenceContext(): SentenceContext? = null
        override fun wordLens(): WordLensHost? = null
        override fun openDictionaryWord(word: String, reading: String?, surface: String?, lookupForm: String, lookupReading: String?) {}
        override fun openWordDetail(word: String, reading: String?, surface: String?, lookupForm: String, lookupReading: String?) {}
        override fun openAnkiReview(args: WordDetailBinder.WordAnkiArgs) {}
        override fun showAnkiNotInstalled() {}
        override fun openFieldMapping(result: AnkiSendResult.NeedsMapping, mode: CardMode, fallback: WordDetailBinder.WordAnkiArgs) {}
        override fun present(builder: OverlayAlert.Builder) {}
        override fun launchOneTap(
            send: suspend () -> Pair<AnkiSendResult, CardMode>,
            presentResult: (Pair<AnkiSendResult, CardMode>) -> Unit,
        ) {}
        override fun onAskNeedsLlm() {}
    }

    /** An offline translator of the test's: English to German is the text
     *  in capitals, so each line's translation names the line it is of. */
    private class Shouting(override val id: BackendId = "characters-test-mt") : TranslationBackend {
        override val displayName = "Test MT"
        override val priority = -1
        override val requiresInternet = false
        override val isDegradedFallback = false
        override val usableAsOfflineFallback = true
        override fun isUsable(source: String, target: String) = source == "en" && target == "de"
        override suspend fun translate(text: String, source: String, target: String): String =
            text.uppercase(Locale.ROOT)
    }

    private val app: Context = ApplicationProvider.getApplicationContext()
    private lateinit var controller: ActivityController<Host>
    private lateinit var activity: Host
    private lateinit var root: View
    private lateinit var binder: WordDetailBinder
    private val ui = QuietUi()
    private val engine = FakeEngine()
    private val mt = Shouting()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private fun sp() = app.getSharedPreferences("playtranslate_prefs", Context.MODE_PRIVATE)

    @Suppress("UNCHECKED_CAST")
    private val engines: ConcurrentHashMap<SourceLangId, SourceLanguageEngine>
        get() = ReflectionHelpers.getStaticField(SourceLanguageEngines::class.java, "cache")

    @Before fun setUp() {
        sp().edit().clear().commit()
        Prefs(app).sourceLang = "zh"
        Prefs(app).setTarget("en")
        engines[SourceLangId.ZH] = engine
        controller = Robolectric.buildActivity(Host::class.java).setup()
        activity = controller.get()
    }

    @After fun tearDown() {
        ui.alive = false
        if (::binder.isInitialized) binder.release()
        scope.cancel()
        controller.pause().stop().destroy()
        idle()
        TranslationBackendRegistry.removeOnlineBackend(mt.id)
        SourceLanguageEngines.release(SourceLangId.ZH)
        sp().edit().clear().commit()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun awaitUntil(what: String, done: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (true) {
            idle()
            if (done()) return
            check(System.currentTimeMillis() < deadline) { "never: $what" }
            Thread.sleep(5)
        }
    }

    private val content: LinearLayout get() = root.findViewById(R.id.detailContent)

    private fun children(g: ViewGroup = content) = (0 until g.childCount).map(g::getChildAt)

    private fun all(v: View): List<View> =
        listOf(v) + ((v as? ViewGroup)?.let { g -> (0 until g.childCount).flatMap { all(g.getChildAt(it)) } }.orEmpty())

    private fun headerIndex(title: String): Int = children().indexOfFirst { child ->
        all(child).any { it.id == R.id.tvGroupTitle && (it as TextView).text == title }
    }

    private val hanzi get() = activity.getString(R.string.word_detail_group_hanzi)
    private val kanji get() = activity.getString(R.string.word_detail_group_kanji)

    /** The page of [written], built through its character group [title]. */
    private fun open(written: String, reading: String, title: String) {
        root = LayoutInflater.from(activity).inflate(R.layout.bottom_sheet_word_detail, null)
        activity.setContentView(root)
        binder = WordDetailBinder(activity, scope, ui)
        binder.bind(root, WordDetailBinder.Args(word = written, reading = reading, surface = null, screenshotPath = null))
        awaitUntil("the page of $written is built through its $title group") { headerIndex(title) >= 0 }
    }

    /** The character rows of the group under [title]: the card's children
     *  that are rows, the dividers between them left out. */
    private fun rows(title: String): List<LinearLayout> =
        children(children()[headerIndex(title) + 1] as ViewGroup).filterIsInstance<LinearLayout>()

    /** The texts a row shows, top to bottom, the tile's character first. */
    private fun texts(row: View): List<String> = all(row).filterIsInstance<TextView>().map { it.text.toString() }

    /** The lines of a row's column: its direct children. */
    private fun lines(row: LinearLayout): List<View> = children(row.getChildAt(1) as ViewGroup)

    private fun topMargin(v: View) = (v.layoutParams as LinearLayout.LayoutParams).topMargin

    private fun dp(value: Int) = (value * activity.resources.displayMetrics.density).toInt()

    // Mutations: the page hands the engine no reading; the row shows the
    // first entry alone; an entry's pinyin stands over its meanings; the
    // surname's capital is lost.
    @Test fun `a hanzi with several entries shows each one's meanings over its own pinyin, in the engine's order`() {
        open("银行", "yín háng", hanzi)

        assertEquals(listOf(Triple("银行", "yín háng", "en")), engine.breakdowns)
        val (yin, hang) = rows(hanzi)
        assertEquals(listOf("银", "silver, money", "PINYIN:", "yín", "Common  ·  ★★★"), texts(yin))
        assertEquals(
            listOf(
                "行",
                "row, profession", "PINYIN:", "háng",
                "to walk, to go", "PINYIN:", "xíng",
                "surname Xing", "PINYIN:", "Xíng",
                "★★★★",
            ),
            texts(hang),
        )
        // The header counts characters, not entries.
        val header = children()[headerIndex(hanzi)]
        assertEquals(
            activity.resources.getQuantityString(R.plurals.word_detail_chars_count, 2, 2),
            header.findViewById<TextView>(R.id.tvGroupBadge).text.toString(),
        )
    }

    // Mutations: no gap between two entries; the gap no wider than the one
    // inside an entry; the meta line 3dp under the last entry of several;
    // the tile centred in a row of several entries; a one-entry row laid
    // out as a stacked one.
    @Test fun `entries stand apart by more than a pinyin stands from its meanings, and the meta line apart from the last`() {
        open("银行", "yín háng", hanzi)
        val (yin, hang) = rows(hanzi)

        // 行: meanings, pinyin, meanings, pinyin, meanings, pinyin, meta.
        val stacked = lines(hang)
        assertEquals(7, stacked.size)
        val inside = dp(3)
        val between = dp(12)
        assertTrue("the gap between entries must be the wider one", between > inside)
        assertEquals(
            listOf(0, inside, between, inside, between, inside, between),
            stacked.map(::topMargin),
        )
        assertEquals(Gravity.TOP, (hang.getChildAt(0).layoutParams as LinearLayout.LayoutParams).gravity)

        // 银: one entry, as a row always was.
        val single = lines(yin)
        assertEquals(listOf(0, inside, inside), single.map(::topMargin))
        assertEquals(-1, (yin.getChildAt(0).layoutParams as LinearLayout.LayoutParams).gravity)
        assertEquals(Gravity.CENTER_VERTICAL, yin.gravity and Gravity.VERTICAL_GRAVITY_MASK)
    }

    // Mutations: the kanji row loses a reading line; it is laid out as a
    // stacked row.
    @Test fun `a kanji's row is one block, its meanings over its on and kun lines`() {
        open("学生", "がくせい", kanji)

        val gaku = rows(kanji).single()
        assertEquals(
            listOf("学", "study, learning", "ON:", "ガク", "KUN:", "まな.ぶ", "JLPT N5  ·  Grade 1  ·  8 strokes"),
            texts(gaku),
        )
        assertEquals(listOf(0, dp(3), dp(3), dp(3)), lines(gaku).map(::topMargin))
        assertEquals(-1, (gaku.getChildAt(0).layoutParams as LinearLayout.LayoutParams).gravity)
        assertEquals(listOf(Triple("学生", "がくせい", "en")), engine.breakdowns)
    }

    // Mutations: a row's meanings are translated as one string into its
    // first line; only the first entry is translated; a translation lands
    // on another entry's line.
    @Test fun `each entry's meanings are machine translated apart and land on their own line`() {
        Prefs(app).setTarget("de")
        TranslationBackendRegistry.addOnlineBackend(mt)
        open("银行", "yín háng", hanzi)

        awaitUntil("every meanings line is translated") {
            rows(hanzi).flatMap(::texts).containsAll(listOf("SILVER, MONEY", "ROW, PROFESSION", "TO WALK, TO GO", "SURNAME XING"))
        }
        val (yin, hang) = rows(hanzi)
        assertEquals(listOf("银", "SILVER, MONEY", "PINYIN:", "yín", "Common  ·  ★★★"), texts(yin))
        assertEquals(
            listOf(
                "行",
                "ROW, PROFESSION", "PINYIN:", "háng",
                "TO WALK, TO GO", "PINYIN:", "xíng",
                "SURNAME XING", "PINYIN:", "Xíng",
                "★★★★",
            ),
            texts(hang),
        )
        assertEquals(listOf(Triple("银行", "yín háng", "de")), engine.breakdowns)
    }

    // Mutations: every row is translated whatever language its meanings
    // are in; the header's note is dropped when one row needs none.
    @Test fun `meanings already in the target language are left as they are, under every reading they cover`() {
        Prefs(app).setTarget("de")
        TranslationBackendRegistry.addOnlineBackend(mt)
        // 沙 with an imported German dictionary's meanings beside a
        // character the pack alone knows, whose English is translated.
        engine.characters = mapOf("沙" to listOf(SHA_IMPORTED, YIN))
        open("沙", "shā", hanzi)
        awaitUntil("the English row is translated") { "SILVER, MONEY" in rows(hanzi).flatMap(::texts) }

        val (sha, yin) = rows(hanzi)
        assertEquals(listOf("沙", "Sand, Kies", "PINYIN:", "shā, shà, Shā"), texts(sha))
        assertEquals(listOf("银", "SILVER, MONEY", "PINYIN:", "yín", "Common  ·  ★★★"), texts(yin))
        assertEquals(
            activity.getString(
                R.string.word_detail_char_meanings_mt,
                activity.resources.getQuantityString(R.plurals.word_detail_chars_count, 2, 2),
            ),
            children()[headerIndex(hanzi)].findViewById<TextView>(R.id.tvGroupBadge).text.toString(),
        )
    }
}
