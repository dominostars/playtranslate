package com.playtranslate.camera

import android.app.Activity
import android.view.ViewGroup
import android.widget.FrameLayout
import com.playtranslate.OcrManager
import com.playtranslate.Prefs
import com.playtranslate.language.SourceLangId
import com.playtranslate.language.SourceLanguageEngines
import com.playtranslate.language.TokenSpan
import com.playtranslate.ui.LookupLensKit
import com.playtranslate.ui.WordBoxesView
import com.playtranslate.ui.WordLookupSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/** A word the user picked before a review's lines were read (a tap on a
 *  word of the live preview, whose snapshot is a frame of its own, read
 *  afresh): its text and where on the screen it was tapped. The review
 *  opens that word's card once its own read has boxed it
 *  ([FrozenReviewPanel.startReview]). */
class ReviewWordRequest(val surface: String, val x: Float, val y: Float)

/**
 * The Definitions presentation of a frozen review (the camera's snapshot,
 * the import tool's page): the capture sheet's own word boxes and their
 * card ([WordBoxesView], [WordLookupSession]) over the tool's still frame,
 * so a word looks and behaves here as it does over a game. A box around
 * every word of the review's lines; a tap on or near one opens its card, a
 * tap on the same word closes it, a tap on another moves it.
 *
 * What differs from the over-game host is where the words are: the frame is
 * fitted into the tool's host (cropped to fill, or letterboxed) and the
 * review can zoom it, so the lines come from [linesInView], which gives
 * them where the host shows them NOW; [place] boxes them again when the
 * picture moves. The card is an activity window ([LookupLensKit.inActivity]).
 * Touch only: the tools give the D-pad no cursor over the words.
 *
 * Main thread only.
 */
internal class ReviewWordBoxes(
    private val activity: Activity,
    /** The tool's full-bleed host over the frame and under the sheet. */
    private val host: ViewGroup,
    /** The review's lines where the host shows them now (fit and zoom
     *  applied) and the frame's saved path, or null while no read owns the
     *  display. */
    private val linesInView: () -> Scene?,
    /** The words' build landed, with boxes or with none to draw: the frame
     *  is theirs now, and the host takes down whatever else it had over it
     *  (the camera keeps its live overlays up until then, so the frame is
     *  never bare in between). */
    private val onBuilt: () -> Unit,
) {
    /** A review's lines in view space and its frame's saved path (a card's
     *  Anki screenshot). */
    class Scene(val lines: List<OcrManager.OcrLine>, val screenshotPath: String?)

    /** The tokens of a line read in a source language: the drag lens's
     *  lookup tokens, through the language's engine; tests swap it. */
    internal var tokenizeLine: suspend (String, SourceLangId) -> List<TokenSpan> =
        { text, src -> SourceLanguageEngines.get(activity, src).tokenize(text) }

    private val prefs = Prefs(activity)
    private val density = activity.resources.displayMetrics.density
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var view: WordBoxesView? = null
    private var session: WordLookupSession? = null

    /** The lines the session last took, to tell the same read shown again
     *  (a fill, the Done promotion) from a new one. */
    private var loaded: List<OcrManager.OcrLine>? = null

    private var request: ReviewWordRequest? = null

    /** The boxes are up or being built. */
    val isShown: Boolean get() = session != null

    /** Box the words of the review's current read. The same lines shown
     *  again keep their boxes and a card that is up, and take the frame's
     *  path as it is now: a re-read of the frame (a crop, an OCR pick) can
     *  come back with the very same lines, but every read saves a frame
     *  file of its own and deletes the one before it, so a card opened
     *  afterwards must cite the new one. New lines are boxed; whatever
     *  brings them (a re-read, a refresh, another page) has already closed
     *  a card of the old ones ([closeCard], [hide]). Nothing without a
     *  read. */
    fun show() {
        val scene = linesInView() ?: return
        val current = session
        if (current != null && scene.lines == loaded) {
            current.setFrame(scene.screenshotPath, System.currentTimeMillis())
            return
        }
        val s = current ?: newSession()
        loaded = scene.lines
        s.load(scene.lines, scene.screenshotPath, System.currentTimeMillis())
    }

    private fun newSession(): WordLookupSession {
        val v = WordBoxesView(activity).also {
            view = it
            host.addView(it, FrameLayout.LayoutParams(MATCH, MATCH))
        }
        val src = prefs.sourceLangId
        lateinit var s: WordLookupSession
        s = WordLookupSession(
            // The card takes the keys while it is up, as the frozen frame's
            // drag lens does: Back closes it, and no cursor of the tool's
            // needs them (the words are touch's here).
            kit = LookupLensKit.inActivity(activity),
            density = density,
            view = v,
            scope = scope,
            tokenize = { text -> tokenizeLine(text, src) },
            // A card that ended by its own means (Back, a page or Anki it
            // opened over the review, which stays up behind it): only the
            // selection goes.
            onEnded = { s.deselect() },
            onBoxesSettled = { if (session === s) onSettled(s) },
        )
        session = s
        return s
    }

    private fun onSettled(s: WordLookupSession) {
        onBuilt()
        val r = request ?: return
        request = null
        s.boxFor(r.surface, r.x, r.y, REQUEST_RADIUS_DP * density)?.let { s.open(it) }
    }

    /** The picture moved under the words (a pinch, a pan): box them where
     *  the host shows them now. A card that is up closes. */
    fun place() {
        val s = session ?: return
        val scene = linesInView() ?: return
        loaded = scene.lines
        if (!s.relocate(scene.lines)) {
            // Not the lines the session holds (a new read the sheet has not
            // shown yet): they are loaded as any new read is.
            s.load(scene.lines, scene.screenshotPath, System.currentTimeMillis())
        }
    }

    /** Open [word]'s card once this review's boxes are up: on the box with
     *  its text nearest to where it was tapped, else on the box under that
     *  point; nothing when the read has no such word. Dropped with the
     *  boxes ([hide]). */
    fun openWhenShown(word: ReviewWordRequest) {
        request = word
    }

    /** A tap on the frame. True when it was a word's (its card opens,
     *  moves or closes) or closed a card; false when it hit no word and no
     *  card was up, which is the host's. */
    fun onTap(x: Float, y: Float): Boolean = session?.onTapAt(x, y) == true

    fun closeCard() {
        session?.closeCard()
    }

    /** Take the boxes and their card down. */
    fun hide() {
        request = null
        loaded = null
        session?.release()
        session = null
        view?.let { host.removeView(it) }
        view = null
    }

    /** The activity is going. */
    fun release() {
        hide()
        scope.cancel()
    }

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT

        /** How far from the tapped point a word of the same text may sit in
         *  the snapshot's own read: the frame is one from just before the
         *  tap, so the hand's drift in that time separates the two. */
        const val REQUEST_RADIUS_DP = 96f
    }
}
