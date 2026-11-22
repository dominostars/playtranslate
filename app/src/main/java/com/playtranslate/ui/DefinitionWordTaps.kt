package com.playtranslate.ui

import android.content.Context
import android.graphics.Point
import android.graphics.Rect
import android.icu.util.ULocale
import android.view.WindowManager
import androidx.annotation.VisibleForTesting
import com.playtranslate.Prefs
import com.playtranslate.language.ScriptFamily
import com.playtranslate.language.SourceLangId
import com.playtranslate.language.SourceLanguageEngines
import com.playtranslate.language.SourceLanguageProfiles
import com.playtranslate.overlay.OverlayHost
import com.playtranslate.yomitan.YomitanDictionaryStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Whether text in a word page's definitions is text to look one of the
 * source language's words up in. A definition can be written in the
 * language being read (a monolingual dictionary, an example sentence) or in
 * another (a gloss), and a tap on the second must do nothing: looked up as
 * the source language it would open a word the user never touched.
 *
 * What is known of the text, in the order it is trusted:
 *
 *  1. What its markup says ([declared]): the language a dictionary set on
 *     its own content, or the source language on a pack example, which the
 *     page marks. Text marked as another language is never tappable.
 *  2. Its script. A language with a script of its own writes its words in
 *     it, so a word holding no character of that script is none of its
 *     words, whatever the text is marked as.
 *  3. The language it would otherwise be in ([other]): the dictionary's own
 *     declared target language, else the app's target language. When that
 *     is the source language (a monolingual dictionary, a same-language
 *     pair), the text is the source's.
 *  4. Otherwise, only where the script tells the two apart: the other
 *     language is written in another script than the source's
 *     ([writtenScript], the platform's knowledge of the language, which
 *     covers the languages the app translates into and reads none of).
 *     Japanese beside Chinese shares its ideographs, so there the line must
 *     show kana, and Chinese beside Japanese must show none. Two languages
 *     of one script cannot be told apart at all (Russian beside Ukrainian,
 *     Arabic beside Persian, Hindi beside Marathi), a language whose script
 *     is not known is taken as such a one, and a Latin-script source
 *     therefore needs 1 or 3.
 *
 * A dictionary's declared target language is taken only as a yes (3): one
 * that says "English" still holds untagged examples in the source language,
 * which 4 keeps tappable.
 */
internal object DefinitionTapGate {

    /**
     * [token] is the tapped word as written and [run] the line it was
     * tapped in; [declared] and [other] are language codes, "" for unknown.
     */
    fun tappable(source: SourceLangId, declared: String, other: String, run: String, token: String): Boolean {
        val profile = SourceLanguageProfiles[source]
        val own = source.yomitanConsumingLang()
        val marked = declared.isNotEmpty()
        if (marked && primary(declared) != own) return false
        val family = profile.scriptFamily
        val latin = family == ScriptFamily.LATIN
        if (!latin && token.none(profile.isScriptChar)) return false
        if (marked || primary(other) == own) return true
        if (latin) return false
        val script = writtenScript(other) ?: return false
        return when {
            family == ScriptFamily.CJK_JAPANESE && script in scriptsOf(ScriptFamily.CJK_CHINESE) ->
                run.any(::isKana)
            family == ScriptFamily.CJK_CHINESE && script in scriptsOf(ScriptFamily.CJK_JAPANESE) ->
                run.none(::isKana)
            else -> script !in scriptsOf(family)
        }
    }

    /** The ISO 15924 codes [writtenScript] gives the languages of a source
     *  language's script family. */
    private fun scriptsOf(family: ScriptFamily): Set<String> = when (family) {
        ScriptFamily.LATIN -> setOf("Latn")
        ScriptFamily.CJK_JAPANESE -> setOf("Jpan", "Hira", "Kana", "Hrkt")
        ScriptFamily.CJK_CHINESE -> setOf("Hans", "Hant", "Hani")
        ScriptFamily.CJK_KOREAN -> setOf("Kore", "Hang")
        ScriptFamily.ARABIC -> setOf("Arab")
        ScriptFamily.DEVANAGARI -> setOf("Deva")
        ScriptFamily.CYRILLIC -> setOf("Cyrl")
        ScriptFamily.THAI -> setOf("Thai")
    }

    private fun primary(code: String): String =
        code.substringBefore('-').substringBefore('_').lowercase(Locale.ROOT)

    private fun isKana(c: Char): Boolean =
        c in '぀'..'ゟ' || c in '゠'..'ヿ' || c in '･'..'ﾟ'
}

/**
 * The script the language [code] names is ordinarily written in, as its ISO
 * 15924 code ("Cyrl"), from the platform's locale data, which knows the
 * languages the app has no profile of; a script the code itself names
 * ("zh-Hant") is that one. Null when the code names no language the platform
 * knows a script for.
 */
internal fun writtenScript(code: String): String? {
    val tag = code.trim().replace('_', '-')
    if (tag.isEmpty()) return null
    val locale = ULocale.forLanguageTag(tag)
    // No language is a tag that did not parse, and "und" is no language:
    // the likely subtags of either are English's.
    if (locale.language.isEmpty() || locale.language == "und") return null
    return ULocale.addLikelySubtags(locale).script.takeIf { it.isNotEmpty() }
}

/** The window a word page's lens is shown in, and where its speech alert
 *  goes: its host's ([WordDetailBinder.Ui.wordLens]). [overlayHost] null is
 *  the host's own window, as [SourceTextLens] reads it. */
class WordLensHost(
    val wm: WindowManager,
    val displayId: Int,
    val overlayHost: OverlayHost?,
    val ttsAlertTarget: TtsAlertTarget,
    val screenSize: () -> Point,
)

/**
 * A word tapped in the text of a word page's definitions: the lens for that
 * word, anchored on it, the word marked, as a tap on a results page's
 * source line opens one ([SourceTextLens], which this is the [Text] of).
 *
 * The page reports the tap as the line of text it fell in and the tapped
 * character's place in it ([DefinitionDocument.TextTap]). Which word that
 * is, is decided here as the source line decides it: the engine's
 * annotation of the line, its phrase-aware tokens and their tap spans
 * ([definitionTapSpans]), so a phrase and a one-character word behave as
 * they do there. Whether the text is one to look words up in at all is
 * [DefinitionTapGate]'s to say. The page is then asked where the word is
 * ([DefinitionDocument.mark], measuring only), and once it has answered the
 * lens resolves and shows; the mark is painted when the lens comes up, so a
 * lookup that fails leaves nothing on the page.
 *
 * The tapped word is a dictionary word, met in a definition and not in the
 * user's sentence: its rows have no explain button ([explains]), and what
 * their open and Anki buttons do is its owner's to wire ([wireActions]).
 *
 * One tap at a time: a later tap takes the place of an earlier one, its
 * lens if that is up ([onTextTap]) and its lookup if that is still out
 * ([SourceTextLens.onTapAtOffset]), and a rect is the page's answer to this
 * very tap and span, so the lookup of one tap never anchors on the mark of
 * another. A lens is therefore never put up in the place of another, and
 * the tap a showing lens stands on is the one held here ([current]) until
 * that lens goes. The page answers a mark again whenever its rows move, so
 * the rect is where the word is at the page's last layout. Rows drawn again
 * under a showing lens take the mark away and leave the lens. Main thread
 * only.
 */
internal class DefinitionWordTaps(
    private val ctx: Context,
    private val scope: CoroutineScope,
    host: WordLensHost,
    wireActions: (lens: MagnifierLens, resolved: SourceWordLookup.ResolvedAt) -> Unit,
    /** The tap spans of a line of text, the source line's own. */
    private val spansOf: suspend (text: String) -> List<SourceWordLookup.TapSpan> =
        { definitionTapSpans(ctx.applicationContext, it) },
    /** The target language an imported dictionary declares, by its id;
     *  null when it declares none or is not known. */
    private val dictionaryLanguage: suspend (dictId: String) -> String? =
        { declaredTargetLanguage(ctx.applicationContext, it) },
) : SourceTextLens.Text {

    /** One tap that passed the gate: the page's name for its line ([id]),
     *  the line, the word's span in it, and where the page said the word
     *  is, once it has. */
    private class Tap(val id: Int, val text: String, val span: SourceWordLookup.TapSpan) {
        var box: DefinitionDocument.MarkBox? = null
        var asked = false
    }

    private var document: DefinitionDocument? = null
    private var current: Tap? = null
    private var job: Job? = null
    private val languages = HashMap<String, String?>()

    private val lens = SourceTextLens(
        ctx, host.wm, host.displayId, host.overlayHost,
        scope = scope,
        ttsAlertTarget = host.ttsAlertTarget,
        text = this,
        screenSize = host.screenSize,
        // A dictionary word with no entry has no page to open into.
        opensWithoutEntry = false,
        deckBadges = true,
        // No row has an explain button ([explains]).
        onAskNeedsLlm = {},
        wireActions = wireActions,
    ).also { it.onDismissed = { current = null } }

    val isShowing: Boolean get() = lens.isShowing

    /** The document whose rows report their taps here; its answers to a
     *  mark come here too. */
    fun attach(document: DefinitionDocument) {
        this.document = document
        document.onMark = ::onMark
    }

    /** A tap the page reported on a row's text. A lens still up goes
     *  first, whether or not this tap opens one: its own tap is forgotten
     *  with it, and never from under it. */
    fun onTextTap(tap: DefinitionDocument.TextTap) {
        job?.cancel()
        lens.dismiss()
        current = null
        job = scope.launch {
            try {
                val prefs = Prefs(ctx.applicationContext)
                val other = declared(tap.dictId) ?: prefs.targetLang
                val span = spansOf(tap.text).firstOrNull { tap.offset in it.range } ?: return@launch
                val token = tap.text.substring(span.range.first, span.range.last + 1)
                if (!DefinitionTapGate.tappable(prefs.sourceLangId, tap.lang, other, tap.text, token)) return@launch
                current = Tap(tap.id, tap.text, span)
                document?.mark(tap.id, span.range.first, span.range.last + 1, draw = false)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
    }

    private suspend fun declared(dictId: String): String? {
        if (dictId.isEmpty()) return null
        if (dictId !in languages) languages[dictId] = dictionaryLanguage(dictId)
        return languages[dictId]
    }

    /** The page's answer to a mark: where the word is, or that its line is
     *  no longer in the page. The first answer for a tap starts its lens. */
    private fun onMark(tapId: Int, box: DefinitionDocument.MarkBox?) {
        val tap = current?.takeIf { it.id == tapId } ?: return
        tap.box = box
        if (box == null) {
            // Its text left the page: a lookup still out finds no rect and
            // shows nothing; a lens already up stays, without its mark.
            if (!lens.isShowing) current = null
            return
        }
        if (tap.asked) return
        tap.asked = true
        lens.wordSpans = listOf(tap.span)
        lens.onTapAtOffset(tap.span.range.first)
    }

    /** Takes the lens down and forgets the tap; the page keeps nothing. */
    fun release() {
        job?.cancel()
        job = null
        lens.dismiss()
        current = null
        document?.onMark = null
        document = null
    }

    fun dismiss() = lens.dismiss()

    // ── The text the lens stands over ───────────────────────────────────

    override val explains: Boolean get() = false

    override fun displayed(): String = current?.text.orEmpty()

    override fun wordRect(span: IntRange, out: Rect): Boolean {
        val tap = current?.takeIf { it.span.range == span } ?: return false
        val box = tap.box ?: return false
        return document?.markRectOnScreen(box, out) == true
    }

    override fun setHighlight(span: IntRange?) {
        if (span == null) {
            document?.unmark()
            return
        }
        val tap = current?.takeIf { it.span.range == span } ?: return
        document?.mark(tap.id, span.first, span.last + 1, draw = true)
    }

    @VisibleForTesting
    internal fun lensForTest(): SourceTextLens = lens
}

/**
 * The tap spans of [text] in the app's source language, built as a results
 * page builds its source line's: the engine's annotation, the phrase-aware
 * tokens projected from it ([phraseAwareRowTokens]) and their spans
 * ([SourceWordLookup.computeTapSpans]). Not [SourceLanguageEngine.tokenize]
 * alone, which leaves one-character words out as the words list's policy.
 */
internal suspend fun definitionTapSpans(appCtx: Context, text: String): List<SourceWordLookup.TapSpan> {
    val engine = SourceLanguageEngines.get(appCtx, Prefs(appCtx).sourceLangId)
    val annotation = withContext(Dispatchers.IO) { engine.annotate(text) }
    val tokens = phraseAwareRowTokens(engine, text, annotation)
    return SourceWordLookup.computeTapSpans(text, tokens.wordTokens, emptyMap(), tokens.phrases)
}

/** The target language the imported dictionary [dictId] declares in its
 *  index; null when it declares none, or is not in the registry. */
internal suspend fun declaredTargetLanguage(appCtx: Context, dictId: String): String? =
    YomitanDictionaryStore.load(appCtx).dictionaries.firstOrNull { it.id == dictId }?.targetLanguage
