package com.playtranslate.ui

import com.playtranslate.ask.AskQuestion
import com.playtranslate.ask.AskSentence
import com.playtranslate.ask.AskSession
import com.playtranslate.ask.AskState
import com.playtranslate.ask.Explanation
import com.playtranslate.language.SourceLangId
import com.playtranslate.model.PendingTranslation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
 * [WordDetailBottomSheetArgsTest]). And what was answered about a word of
 * that sentence ([SentenceContext.explanations]): part of the sentence a
 * card reads, under the word it is about, and there only while the
 * sentence is the one it was asked about. Once the page has asked about
 * its own word (its "In this sentence" group's session), that word's entry
 * is the session's alone: its answer while the sentence is the one it was
 * asked about, else none, whatever the launch arguments and the host still
 * carry, and every other word's entry is as they give it.
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

    // ── What was answered about a word of the sentence ──────────────────

    private val answer = Explanation("Here it is the potential of 弾く.", "Gemini")
    private val explained = launch.copy(explanation = answer)
    private val ichiban = CardTarget("一番", "一番", "いちばん")
    private val mostOfAll = Explanation("Most of all.", "Gemini")
    private val none = emptyMap<CardTarget, Explanation>()

    // Mutations: name the page's word by its display, or by its key alone.
    @Test
    fun `a page names its own word by the text it was found as and the key it resolves with`() {
        assertEquals(CardTarget("弾けた", "弾ける", "はじける"), launch.target)
        val keyed = launch.copy(lookupForm = "弾く", lookupReading = "ひく")
        assertEquals(CardTarget("弾けた", "弾く", "ひく"), keyed.target)
        // An opener that had only the lemma: the key is the text.
        assertEquals(CardTarget("弾く", "弾く", "ひく"), keyed.copy(surface = null).target)
        assertEquals(keyed.target, pageCardTarget("弾けた", "弾く", "ひく"))
    }

    // Mutations: leave the launch answer out of the sentence; put it under
    // another word; put a blank one in.
    @Test
    fun `the answer a page was opened with is in its sentence, under the page's own word`() {
        assertEquals(mapOf(launch.target to answer), WordDetailBinder.pageSentence(null, explained).explanations)
        assertEquals(none, WordDetailBinder.pageSentence(null, launch).explanations)
        assertEquals(none, WordDetailBinder.pageSentence(null, launch.copy(explanation = Explanation(" ", "Gemini"))).explanations)
    }

    // Mutations: keep the launch answer whatever the host's sentence is;
    // drop it whenever a host answers.
    @Test
    fun `the answer a page was opened with goes once the host's sentence is another`() {
        val same = SentenceContext(original = "一番うまく弾けた！", translation = null, wordResults = null)
        assertEquals(mapOf(launch.target to answer), WordDetailBinder.pageSentence(same, explained).explanations)

        val edited = SentenceContext(original = "一番うまく弾いた！", translation = null, wordResults = null)
        val sentence = WordDetailBinder.pageSentence(edited, explained)
        assertEquals("一番うまく弾いた！", sentence.original)
        assertEquals("an answer about the sentence the page was opened with", none, sentence.explanations)
    }

    // Mutations: take the host's answers alone, or the launch's alone;
    // keep the answers of a host that has no sentence of its own.
    @Test
    fun `the host's answers ride with the host's sentence, beside the page's own`() {
        val host = SentenceContext(
            original = "一番うまく弾けた！", translation = null, wordResults = null,
            explanations = mapOf(ichiban to mostOfAll),
        )
        assertEquals(
            mapOf(ichiban to mostOfAll, launch.target to answer),
            WordDetailBinder.pageSentence(host, explained).explanations,
        )
        // A host with answers and no sentence: the page's sentence is the
        // launch's, which the host's answers are not about.
        val textless = host.copy(original = null)
        assertEquals(mapOf(launch.target to answer), WordDetailBinder.pageSentence(textless, explained).explanations)
    }

    // Mutation: read a page's note from its launch arguments alone.
    @Test
    fun `a page with no launch sentence finds its answer in the host's context`() {
        val embedded = WordDetailBinder.Args(
            word = "弾ける", reading = "はじける", surface = "弾けた", screenshotPath = null, embedded = true,
        )
        val host = SentenceContext(
            original = "一番うまく弾けた！", translation = null, wordResults = null,
        ).explaining(embedded.target, answer)
        assertEquals(answer, WordDetailBinder.pageSentence(host, embedded).explanations[embedded.target])
    }

    // Mutations: explaining keeps a blank answer; it drops the answers the
    // context already held.
    @Test
    fun `a context takes an answer under its word, beside those it holds, and no blank one`() {
        val base = SentenceContext("一番うまく弾けた！", null, null, explanations = mapOf(ichiban to mostOfAll))
        assertEquals(
            mapOf(ichiban to mostOfAll, launch.target to answer),
            base.explaining(launch.target, answer).explanations,
        )
        assertEquals(base, base.explaining(launch.target, null))
        assertEquals(base, base.explaining(launch.target, Explanation("", "Gemini")))
    }

    // Mutations: an answer rides beside any sentence; beside none.
    @Test
    fun `answers stand beside the text they were asked about and no other`() {
        val base = SentenceContext("一番うまく弾けた！", null, null, explanations = mapOf(ichiban to mostOfAll))
        assertEquals(base.explanations, base.explanationsAbout("一番うまく弾けた！"))
        assertEquals(none, base.explanationsAbout("一番うまく弾いた！"))
        assertEquals(none, base.explanationsAbout(null))
    }

    // Mutations: read a review's note from anywhere but its sentence; give
    // it the answer of another word of that sentence.
    @Test
    fun `a word's review takes its sentence and its note from the one context`() {
        val sentence = WordDetailBinder.pageSentence(null, explained).explaining(ichiban, mostOfAll)
        fun review(target: CardTarget) = WordDetailBinder.wordAnkiArgs(
            sentence, target, word = "弾ける", reading = "はじける", pos = "Ichidan verb", definition = "to burst",
            screenshotPath = "/shot.png", freqScore = 2, isCommon = true, sourceLangId = SourceLangId.JA,
        )
        val own = review(launch.target)
        assertEquals(answer.text, own.notes)
        assertEquals("一番うまく弾けた！", own.sentenceOriginal)
        assertEquals("I played it best!", own.sentenceTranslation)
        assertEquals(launch.sentenceWordResults, own.sentenceWordResults)
        assertEquals(launchPending, own.sentencePending)
        assertEquals(launch.target, own.target)
        assertEquals("Most of all.", review(ichiban).notes)
        assertEquals("a word nothing was asked about", "", review(CardTarget("うまく", "うまい", null)).notes)

        // The sentence edited under the page: the review is on the new
        // text and has no note.
        val edited = SentenceContext(original = "一番うまく弾いた！", translation = "", wordResults = null)
        val after = WordDetailBinder.wordAnkiArgs(
            WordDetailBinder.pageSentence(edited, explained), launch.target, word = "弾ける", reading = "はじける",
            pos = "", definition = "", screenshotPath = null, freqScore = 0, isCommon = false, sourceLangId = SourceLangId.JA,
        )
        assertEquals("一番うまく弾いた！", after.sentenceOriginal)
        assertEquals("", after.notes)
    }

    // Mutation: a cell's review takes the page's explanation.
    @Test
    fun `a cell's review starts with no note, whatever was explained of the page's word`() {
        val hiku = RowState(
            displayWord = "弾く", reading = "ひく", meaning = "to play", senses = emptyList(),
            freqScore = 3, isCommon = true, surface = "弾けた", lookupForm = "弾く", lookupReading = "ひく",
        )
        val args = WordDetailBinder.rowAnkiArgs(hiku, WordDetailBinder.pageSentence(null, explained), null, SourceLangId.JA)
        assertEquals("", args.notes)
    }

    // Mutation: a cell's review reads no note at all.
    @Test
    fun `a cell's review starts with what the sentence holds about the cell's own word`() {
        val hiku = RowState(
            displayWord = "弾く", reading = "ひく", meaning = "to play", senses = emptyList(),
            freqScore = 3, isCommon = true, surface = "弾けた", lookupForm = "弾く", lookupReading = "ひく",
        )
        val sentence = WordDetailBinder.pageSentence(null, launch)
            .explaining(CardTarget("弾けた", "弾く", "ひく"), Explanation("To play an instrument.", "Gemini"))
        assertEquals("To play an instrument.", WordDetailBinder.rowAnkiArgs(hiku, sentence, null, SourceLangId.JA).notes)
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

    // ── What the page asked about its own word ─────────────────────────

    private val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @After fun cancelSessions() = sessionScope.cancel()

    private val pageQuestion = AskQuestion.Word("弾ける", "はじける", listOf("弾けた"), "In this sentence")

    /** The page's session about [text]: never answering what it is asked. */
    private fun session(text: String = "一番うまく弾けた！") = AskSession(sessionScope) { _, _, _ -> awaitCancellation() }
        .apply { bind(AskSentence(text, "ja", "en")) }

    private fun AskSession.answered(text: String, by: String = "Claude", cut: Boolean = false) = apply {
        restore(sentence!!, AskState.Answered(pageQuestion, text, by, cut))
    }

    private val carriedHost = SentenceContext("一番うまく弾けた！", null, null)
        .explaining(launch.target, answer)
        .explaining(ichiban, mostOfAll)

    // Mutations: keep a carried answer under the page's word beside its
    // session's; take the carried one over the session's.
    @Test
    fun `once the page asks, its own word's entry is its session's answer, over both carried ones`() {
        val asked = session().answered("It is the potential form here.", cut = true)
        val sentence = WordDetailBinder.pageSentence(carriedHost, explained, asked)
        assertEquals(
            mapOf(ichiban to mostOfAll, launch.target to Explanation("It is the potential form here.", "Claude", cut = true)),
            sentence.explanations,
        )
    }

    // Mutations: a session with no answer leaves the carried answer in
    // place; one put away takes the other words' with it.
    @Test
    fun `a page whose session holds no answer has none under its own word, whatever is carried, and keeps every other word's`() {
        val idle = session()
        assertEquals(
            mapOf(ichiban to mostOfAll),
            WordDetailBinder.pageSentence(carriedHost, explained, idle).explanations,
        )
        val asking = session().apply { ask(pageQuestion) }
        assertTrue("precondition: asking", asking.state.value is AskState.Asking)
        assertEquals(mapOf(ichiban to mostOfAll), WordDetailBinder.pageSentence(carriedHost, explained, asking).explanations)
        val closed = session().answered("It is the potential form here.").apply { close() }
        assertEquals(mapOf(ichiban to mostOfAll), WordDetailBinder.pageSentence(carriedHost, explained, closed).explanations)
    }

    // Mutation: the session's answer stands beside a sentence it was not
    // asked about.
    @Test
    fun `the session's answer stands only beside the sentence it was asked about`() {
        val asked = session().answered("It is the potential form here.")
        val edited = SentenceContext(original = "一番うまく弾いた！", translation = null, wordResults = null)
        assertEquals(
            emptyMap<CardTarget, Explanation>(),
            WordDetailBinder.pageSentence(edited, explained, asked).explanations,
        )
    }

    // Mutations: the review's note is read from anywhere but the sentence
    // the session stands in; a cell's note follows the page's own.
    @Test
    fun `the page's own review takes its session's answer as its note, and a cell's review its own word's`() {
        val hiku = RowState(
            displayWord = "弾く", reading = "ひく", meaning = "to play", senses = emptyList(),
            freqScore = 3, isCommon = true, surface = "弾けた", lookupForm = "弾く", lookupReading = "ひく",
        )
        val hikuAnswer = Explanation("To play an instrument.", "Gemini")
        val host = carriedHost.explaining(CardTarget("弾けた", "弾く", "ひく"), hikuAnswer)
        fun own(asked: AskSession) = WordDetailBinder.wordAnkiArgs(
            WordDetailBinder.pageSentence(host, explained, asked), launch.target, word = "弾ける", reading = "はじける",
            pos = "", definition = "", screenshotPath = null, freqScore = 0, isCommon = false, sourceLangId = SourceLangId.JA,
        ).notes
        fun cell(asked: AskSession) =
            WordDetailBinder.rowAnkiArgs(hiku, WordDetailBinder.pageSentence(host, explained, asked), null, SourceLangId.JA).notes

        val asked = session().answered("It is the potential form here.")
        assertEquals("It is the potential form here.", own(asked))
        assertEquals(hikuAnswer.text, cell(asked))
        asked.close()
        assertEquals("put away, no note", "", own(asked))
        assertEquals(hikuAnswer.text, cell(asked))
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
