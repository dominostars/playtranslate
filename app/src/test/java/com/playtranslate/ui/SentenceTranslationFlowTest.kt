package com.playtranslate.ui

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.playtranslate.Prefs
import com.playtranslate.language.SourceLangId
import com.playtranslate.language.SourceLanguageProfiles
import com.playtranslate.model.PendingTranslation
import com.playtranslate.model.TextSegments
import com.playtranslate.model.TranslationResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * Pins [SentenceTranslationFlow], the one owner of "translate a looked-up
 * sentence into the results VM" (MainActivity's drag sentence, the results
 * activity's sentence mode, the workspace's Sentence tab): a cached
 * translation binds Ready with no backend call; a hidden translation
 * section lands a deferred Ready (pending carrying the lookup-time
 * eligibility snapshot) with no backend call; otherwise the placeholder,
 * then the outcome with the by-key History attach under the pair captured
 * before the call; a backend failure or a missing backend lands "—" on the
 * bound result; a newer show() drops the older call's outcome (generation,
 * not cancellation); completeDeferred runs a sentence-shaped pending once
 * (a repeat call while in flight is one batch), lands it identity-guarded
 * with the pending's eligibility, refuses a capture-shaped pending, and
 * keeps a pending without a backend; a History row tap attaches to the
 * exact row only when the pair matches. A superseded outcome still
 * attaches to its own sentence's row (only its display is dropped).
 */
@RunWith(RobolectricTestRunner::class)
class SentenceTranslationFlowTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val prefs get() = Prefs(ctx)
    private val sourceCode get() = SourceLanguageProfiles[prefs.sourceLangId].translationCode

    private class FakeBackend : SentenceTranslationFlow.Backend {
        /** Each translate() awaits the next gate; a gate completed ahead of
         *  time returns immediately. */
        val gates = ArrayDeque<CompletableDeferred<SentenceTranslationFlow.Outcome>>()
        val calls = mutableListOf<String>()
        val lookups = mutableListOf<List<Any?>>()
        val rows = mutableListOf<List<Any?>>()

        fun gate(): CompletableDeferred<SentenceTranslationFlow.Outcome> =
            CompletableDeferred<SentenceTranslationFlow.Outcome>().also { gates.addLast(it) }

        fun ready(text: String, note: String? = null, backend: String? = "DeepL") =
            gate().also { it.complete(SentenceTranslationFlow.Outcome(text, note, backend)) }

        override suspend fun translate(text: String): SentenceTranslationFlow.Outcome {
            calls += text
            val g = gates.removeFirstOrNull() ?: error("no gate queued for '$text'")
            return g.await()
        }

        override fun attachLookup(
            source: String, translation: String, sourceLang: String, targetLang: String,
            backendDisplayName: String?, historyEligible: Boolean, contextEligible: Boolean,
        ) {
            lookups += listOf(source, translation, sourceLang, targetLang, backendDisplayName, historyEligible, contextEligible)
        }

        override fun attachHistoryRow(
            rowId: Long, source: String, translation: String, sourceLang: String, targetLang: String,
            backendDisplayName: String?, contextEligible: Boolean,
        ) {
            rows += listOf(rowId, source, translation, sourceLang, targetLang, backendDisplayName, contextEligible)
        }
    }

    private lateinit var scope: CoroutineScope
    private lateinit var vm: TranslationResultViewModel

    @Before
    fun setUp() {
        clearPrefs()
        prefs.targetLang = "en"
        prefs.hideTranslationSection = false
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        vm = TranslationResultViewModel(scope)
    }

    @After
    fun tearDown() {
        scope.cancel()
        idle()
        clearPrefs()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun flow(
        backend: SentenceTranslationFlow.Backend?,
        historyRow: SentenceTranslationFlow.HistoryRow? = null,
    ) = SentenceTranslationFlow(ctx, vm, scope, backend = { backend }, historyRow = historyRow)

    private fun ready(): TranslationResult = (vm.result.value as ResultState.Ready).result

    @Test
    fun `cached translation binds Ready with no backend call`() {
        val b = FakeBackend()
        flow(b).show("こんにちは", "/shot.jpg", SentenceTranslationFlow.Cached("Hello", "Lens"))
        idle()
        val r = ready()
        assertEquals("Hello", r.translatedText)
        assertEquals("Lens", r.backendDisplayName)
        assertEquals("/shot.jpg", r.screenshotPath)
        assertNull(r.pendingTranslation)
        assertTrue(b.calls.isEmpty())
    }

    @Test
    fun `hidden translation section defers with the lookup-time eligibility snapshot`() {
        prefs.hideTranslationSection = true
        prefs.translationHistoryEnabled = true
        prefs.llmContextEnabled = false
        val b = FakeBackend()
        flow(b).show("こんにちは", null)
        idle()
        val r = ready()
        assertEquals("", r.translatedText)
        val p = r.pendingTranslation
        assertNotNull(p)
        assertEquals(listOf("こんにちは"), p!!.groupTexts)
        assertEquals("en", p.targetLang)
        assertFalse(p.isCapture)
        assertTrue(p.historyEligible)
        assertFalse(p.contextEligible)
        assertTrue("no backend call while deferred", b.calls.isEmpty())
    }

    @Test
    fun `placeholder then outcome with the by-key attach under the captured pair`() {
        val b = FakeBackend()
        val gate = b.gate()
        flow(b).show("こんにちは", null)
        assertTrue(vm.result.value is ResultState.Translating)
        assertEquals(listOf("こんにちは"), b.calls)
        // The pair is captured before the call: a target change mid-flight
        // must not relabel the attach.
        val capturedSource = sourceCode
        prefs.targetLang = "de"
        gate.complete(SentenceTranslationFlow.Outcome("Hello", "note", "DeepL"))
        idle()
        val r = ready()
        assertEquals("Hello", r.translatedText)
        assertEquals("note", r.note)
        assertEquals("DeepL", r.backendDisplayName)
        assertEquals(listOf(listOf<Any?>("こんにちは", "Hello", capturedSource, "en", "DeepL", true, true)), b.lookups)
    }

    @Test
    fun `backend failure lands the failed marker instead of a stuck placeholder`() {
        val b = FakeBackend()
        val gate = b.gate()
        flow(b).show("こんにちは", null)
        gate.completeExceptionally(IllegalStateException("boom"))
        idle()
        assertEquals(SentenceTranslationFlow.FAILED_TRANSLATION, ready().translatedText)
        assertTrue(b.lookups.isEmpty())
    }

    @Test
    fun `no backend lands the failed marker`() {
        flow(null).show("こんにちは", null)
        idle()
        assertEquals(SentenceTranslationFlow.FAILED_TRANSLATION, ready().translatedText)
    }

    @Test
    fun `a newer show drops the older outcome`() {
        val b = FakeBackend()
        val first = b.gate()
        val second = b.gate()
        val f = flow(b)
        f.show("一", null)
        f.show("二", null)
        first.complete(SentenceTranslationFlow.Outcome("one", null, null))
        idle()
        assertEquals("二", (vm.result.value as ResultState.Translating).originalText)
        second.complete(SentenceTranslationFlow.Outcome("two", null, null))
        idle()
        assertEquals("two", ready().translatedText)
        assertEquals("二", ready().originalText)
        // Only the DISPLAY is dropped: each outcome still attaches to its
        // own sentence's History row (the attach is keyed by the sentence).
        assertEquals(listOf("一", "二"), b.lookups.map { it[0] })
    }

    @Test
    fun `completeDeferred runs a sentence-shaped pending once and lands it`() {
        prefs.hideTranslationSection = true
        prefs.translationHistoryEnabled = false
        prefs.llmContextEnabled = true
        val b = FakeBackend()
        val f = flow(b)
        f.show("こんにちは", null)
        idle()
        val pending = ready().pendingTranslation!!
        val gate = b.gate()
        assertTrue(f.completeDeferred())
        // A repeat trigger for the same pending while in flight is one batch.
        assertTrue(f.completeDeferred())
        assertEquals(listOf("こんにちは"), b.calls)
        gate.complete(SentenceTranslationFlow.Outcome("Hello", null, "DeepL"))
        idle()
        val r = ready()
        assertEquals("Hello", r.translatedText)
        assertNull(r.pendingTranslation)
        // The attach honors the pending's LOOKUP-time snapshot, not prefs.
        assertEquals(listOf(false, true), b.lookups.single().let { listOf(it[5], it[6]) })
        assertEquals(pending.groupTexts, listOf("こんにちは"))
    }

    @Test
    fun `completeDeferred refuses a capture-shaped pending and keeps a backend-less one`() {
        val b = FakeBackend()
        val f = flow(b)
        val capture = PendingTranslation(listOf("x"), SourceLangId.JA, "en", isCapture = true)
        vm.displayResult(
            TranslationResult(
                originalText = "x", segments = TextSegments.ofText("x"), translatedText = "",
                timestamp = "", pendingTranslation = capture, langContext = prefs.langContext(),
            ),
            ctx,
        )
        assertFalse(f.completeDeferred())
        assertTrue(b.calls.isEmpty())

        prefs.hideTranslationSection = true
        flow(null).apply {
            show("こんにちは", null)
            idle()
            assertTrue("sentence-shaped: handled by keeping the pending", completeDeferred())
        }
        assertNotNull(ready().pendingTranslation)
    }

    @Test
    fun `completeDeferred failure lands the marker on the still-current pending only`() {
        prefs.hideTranslationSection = true
        val b = FakeBackend()
        val f = flow(b)
        f.show("こんにちは", null)
        idle()
        val gate = b.gate()
        f.completeDeferred()
        gate.completeExceptionally(IllegalStateException("boom"))
        idle()
        val r = ready()
        assertEquals(SentenceTranslationFlow.FAILED_TRANSLATION, r.translatedText)
        assertNull(r.pendingTranslation)
    }

    @Test
    fun `history row attaches to the exact row only when the pair matches`() {
        val b = FakeBackend()
        b.ready("Hello")
        flow(b, SentenceTranslationFlow.HistoryRow(42L, sourceCode, "en")).show("こんにちは", null)
        idle()
        assertEquals(listOf(42L, "こんにちは", "Hello", sourceCode, "en", "DeepL", true), b.rows.single())
        assertTrue(b.lookups.isEmpty())

        val b2 = FakeBackend()
        b2.ready("Hallo")
        flow(b2, SentenceTranslationFlow.HistoryRow(42L, sourceCode, "de")).show("こんにちは", null)
        idle()
        assertTrue("cross-pair result stays display-only", b2.rows.isEmpty())
        assertTrue(b2.lookups.isEmpty())
        assertEquals("Hallo", ready().translatedText)
    }

    private fun clearPrefs() {
        ctx.getSharedPreferences("playtranslate_prefs", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }
}
