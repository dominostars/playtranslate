package com.playtranslate.ui

import com.playtranslate.language.SourceLangId
import com.playtranslate.model.PendingTranslation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The word detail page's Anki context: one sentence for every Anki action on
 * the page ([WordDetailBinder.pageSentence], the host's live context field by
 * field over the launch arguments, the pending never crossed between them),
 * which the Words and Other matches cells ride with the page's screenshot
 * and their own word as the card's target ([WordDetailBinder.rowAnkiArgs]);
 * and the nested pages those cells open, which carry the page's sentence,
 * screenshot and audio anchor ([WorkspaceWordDetailPage.nested]; the
 * fragment host's [WordDetailBottomSheet.nestedInstance] is pinned in
 * [WordDetailBottomSheetArgsTest]).
 */
class WordDetailAnkiContextTest {

    private val launchPending = PendingTranslation(listOf("一番うまく弾けた！"), SourceLangId.JA, "en", isCapture = true)
    private val hostPending = PendingTranslation(listOf("一番うまく弾けた！"), SourceLangId.JA, "en", isCapture = false)

    private val launch = WordDetailBinder.Args(
        word = "弾ける", reading = "はじける", surface = "弾けた", screenshotPath = "/shot.png",
        sentenceOriginal = "一番うまく弾けた！",
        sentenceTranslation = "I played it best!",
        sentenceWordResults = mapOf("弾ける" to Triple("はじける", "to burst", 0)),
        sentencePending = launchPending,
    )

    @Test
    fun `with no host context the launch arguments are the sentence`() {
        val sentence = WordDetailBinder.pageSentence(null, launch)
        assertEquals("一番うまく弾けた！", sentence.original)
        assertEquals("I played it best!", sentence.translation)
        assertEquals(launch.sentenceWordResults, sentence.wordResults)
        assertEquals(launchPending, sentence.pending)
    }

    @Test
    fun `the host's live context wins field by field`() {
        val host = SentenceContext(
            original = "一番うまく弾けた！", translation = null,
            wordResults = mapOf("弾く" to Triple("ひく", "to play", 3)), pending = hostPending,
        )
        val sentence = WordDetailBinder.pageSentence(host, launch)
        assertEquals("一番うまく弾けた！", sentence.original)
        // A field the host lacks falls back to the launch argument.
        assertEquals("I played it best!", sentence.translation)
        assertEquals(host.wordResults, sentence.wordResults)
        assertEquals(hostPending, sentence.pending)
    }

    @Test
    fun `the pending rides only with the source that supplied it`() {
        // The host answers but has no pending: the launch arguments' pending
        // belongs to their own original and must not cross over.
        val host = SentenceContext(original = null, translation = null, wordResults = null)
        assertNull(WordDetailBinder.pageSentence(host, launch).pending)
    }

    @Test
    fun `a cell's review rides the page's sentence and screenshot, targeting the cell's word`() {
        val hiku = RowState(
            displayWord = "弾く", reading = "ひく", meaning = "to play (an instrument)",
            senses = emptyList(), freqScore = 3, isCommon = true, surface = "弾けた",
            ankiPos = "Godan verb with 'ku' ending", lookupForm = "弾く", lookupReading = "ひく",
        )
        val sentence = WordDetailBinder.pageSentence(null, launch)
        val args = WordDetailBinder.rowAnkiArgs(hiku, sentence, "/shot.png", SourceLangId.JA)

        assertEquals("弾く" to "ひく", args.word to args.reading)
        assertEquals("to play (an instrument)", args.definition)
        assertEquals("一番うまく弾けた！", args.sentenceOriginal)
        assertEquals("I played it best!", args.sentenceTranslation)
        assertEquals(launch.sentenceWordResults, args.sentenceWordResults)
        assertEquals(launchPending, args.sentencePending)
        assertEquals("/shot.png", args.screenshotPath)
        assertEquals(CardTarget("弾けた", "弾く", "ひく"), args.target)
    }

    @Test
    fun `a kana cell's review carries no reading`() {
        val naze = RowState(
            displayWord = "なぜ", reading = "", meaning = "why", senses = emptyList(),
            freqScore = 5, isCommon = true, surface = "なぜ", lookupForm = "何故",
        )
        val args = WordDetailBinder.rowAnkiArgs(naze, WordDetailBinder.pageSentence(null, launch), null, SourceLangId.JA)
        assertEquals("", args.reading)
        assertEquals(CardTarget("なぜ", "何故", null), args.target)
    }

    @Test
    fun `a workspace page's nested page carries its sentence, screenshot and anchor`() {
        var live = SentenceContext(original = "一番うまく弾けた！", translation = null, wordResults = null)
        val page = WorkspaceWordDetailPage(
            "弾ける", "はじける", "弾けた",
            screenshotPath = "/shot.png", audioAnchorMs = 42L, sentenceContext = { live },
        )
        val nested = page.nested("弾く", "ひく", "弾けた", "弾く", "ひく")

        assertEquals("/shot.png", nested.screenshotPath)
        assertEquals(42L, nested.audioAnchorMs)
        // The parent's provider itself, so a translation landing later is
        // on the nested page's card too.
        live = live.copy(translation = "I played it best!")
        assertEquals("I played it best!", nested.sentenceContext()?.translation)
    }
}
