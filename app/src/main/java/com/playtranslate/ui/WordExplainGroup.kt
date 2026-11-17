package com.playtranslate.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.AttrRes
import androidx.core.view.isVisible
import com.playtranslate.R
import com.playtranslate.ask.AskPass
import com.playtranslate.ask.AskQuestion
import com.playtranslate.ask.AskSession
import com.playtranslate.ask.AskState
import com.playtranslate.ask.Explanation
import com.playtranslate.ask.WordInSentence
import com.playtranslate.ask.answering
import com.playtranslate.themeColor
import com.playtranslate.translation.TranslationBackendRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * The word page's "In this sentence" group ([WordDetailBinder]): the card
 * under the definitions where the user's AI model is asked how the page's
 * word is used in the sentence the page was opened from, and its answer.
 * One question only, a word row's explain question ([AskQuestion.Word])
 * about [subject]. Host-agnostic like [AskSectionBinder]: it fills the card
 * the page hands it with views of its own and draws its [session]; the
 * page decides whether there is a group and where its card goes.
 *
 * With nothing asked the card is one row, the explain glyph and a label,
 * whose tap asks; with nothing that could answer (asked at the tap, as the
 * Ask section asks) the tap is the host's alert instead and nothing is
 * asked. Once asked, the card is what came of it, in the words every
 * surface draws a question's state in ([askDisplay]): who is being asked,
 * the answer with who gave it (and before that, that it was cut), the
 * service's word that the question is not one it answers, or the failure
 * and Try again. An X puts it away, cancelling a question in flight, and
 * the row comes back.
 *
 * [carried] is the answer the page was opened with about its word: the
 * session takes it up as if it had been asked here ([AskSession.restore]),
 * so it is drawn at once, with the same X, and nothing is asked for it.
 *
 * Every control is a tap target of at least 48dp. One that a press takes
 * off the screen (the row gives way to the X, the X to the row, Try again
 * to the X) hands on to the one standing in its place: Android's focus,
 * where it had it, and the host's own controller cursor
 * ([Host.onControlGone]).
 *
 * The question runs on [scope], the page's main-thread immediate one
 * (both hosts' are), so the state a press sets is drawn before the press
 * returns. [release] ends the question; a cancelled request writes
 * nothing.
 */
internal class WordExplainGroup(
    private val ctx: Context,
    card: LinearLayout,
    scope: CoroutineScope,
    subject: WordInSentence,
    carried: Explanation?,
    private val host: Host,
    /** Whether anything could answer the question, asked at each tap of
     *  the row ([TranslationBackendRegistry.hasCompleter]). */
    private val hasLlm: () -> Boolean = { TranslationBackendRegistry.hasCompleter() },
    /** Puts the question to the user's services ([AskSession]'s seam). The
     *  registry's pass in production; a test substitutes it. */
    complete: AskPass = AskSession.registryPass,
) {
    interface Host {
        /** The row was tapped with nothing that could answer: show the
         *  "needs an AI model" alert ([askNeedsLlm]) on the page's surface. */
        fun onAskNeedsLlm()

        /** A control took itself off the screen as it was pressed: a host
         *  that draws a controller's cursor of its own moves it to [next],
         *  the control standing in its place. Default: nothing to do, for a
         *  host whose window keeps Android's focus, which the group hands
         *  on itself. */
        fun onControlGone(next: View) {}
    }

    /** The page's one question and what came of it; what the page's own
     *  word's card takes as its note ([WordDetailBinder.pageSentence]). */
    val session = AskSession(scope, complete)

    private val question = AskQuestion.Word(
        subject.word, subject.reading, subject.surfaces, ctx.getString(R.string.word_explain_title),
    )

    private val hPad = ctx.resources.getDimensionPixelSize(R.dimen.pt_row_h_padding)
    private val vPad = ctx.resources.getDimensionPixelSize(R.dimen.pt_row_v_padding)

    /** Nothing asked: the explain glyph and the label, the whole row the
     *  tap target, as tall as the page's other rows. */
    private val askRow = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = ctx.resources.getDimensionPixelSize(R.dimen.pt_row_min_height)
        setPadding(hPad, vPad, hPad, vPad)
        isClickable = true
        isFocusable = true
        setBackgroundResource(themeResource(android.R.attr.selectableItemBackground))
        addView(ImageView(ctx).apply {
            setImageResource(R.drawable.ic_ai_info)
            imageTintList = ColorStateList.valueOf(ctx.themeColor(R.attr.ptAccent))
            layoutParams = LinearLayout.LayoutParams(dp(20), dp(20)).also { it.marginEnd = dp(12) }
        })
        addView(TextView(ctx).apply {
            text = ctx.getString(R.string.word_explain_ask)
            textSize = 15f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(ctx.themeColor(R.attr.ptAccent))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
    }

    /** "Asking <service>…", the answer, the out-of-scope line or the
     *  failure; muted where it is the app's word and not an answer. */
    private val bodyText = TextView(ctx).apply {
        textSize = 15f
        setLineSpacing(0f, 1.3f)
    }

    /** A text button: asks the failed question again. */
    private val retryButton = TextView(ctx).apply {
        text = ctx.getString(R.string.ask_retry)
        textSize = 13f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setTextColor(ctx.themeColor(R.attr.ptAccent))
        gravity = Gravity.CENTER_VERTICAL
        minHeight = dp(48)
        minWidth = dp(48)
        isClickable = true
        isFocusable = true
        setBackgroundResource(themeResource(android.R.attr.selectableItemBackground))
        isVisible = false
    }

    /** Who answered, and before that, for an answer cut at the service's
     *  length limit, a line saying so; styled as the Ask card's footer. */
    private val footerText = TextView(ctx).apply {
        textSize = 11f
        setTypeface(null, Typeface.ITALIC)
        setTextColor(ctx.themeColor(R.attr.ptTextHint))
        isVisible = false
    }

    /** Puts the question away: the Ask card's glyph and tint, drawn at
     *  18dp in a 48dp target. */
    private val closeButton = ImageButton(ctx).apply {
        setImageResource(R.drawable.ic_close)
        imageTintList = ColorStateList.valueOf(ctx.themeColor(R.attr.ptTextMuted))
        scaleType = ImageView.ScaleType.FIT_CENTER
        setPadding(dp(15), dp(15), dp(15), dp(15))
        setBackgroundResource(themeResource(android.R.attr.selectableItemBackgroundBorderless))
        contentDescription = ctx.getString(R.string.cd_ask_close)
    }

    /** A question on screen: what came of it, with the X at its top end. */
    private val answerRow = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        isVisible = false
        addView(
            LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPaddingRelative(hPad, vPad, 0, vPad)
                addView(bodyText, wrapLine())
                addView(retryButton, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                ).also { it.topMargin = dp(4) })
                addView(footerText, wrapLine().also { it.topMargin = dp(6) })
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        addView(closeButton, LinearLayout.LayoutParams(dp(48), dp(48)).also { it.marginEnd = dp(4) })
    }

    private val collector: Job

    init {
        card.addView(askRow, wrapLine())
        card.addView(answerRow, wrapLine())
        askRow.setOnClickListener {
            if (!hasLlm()) {
                host.onAskNeedsLlm()
                return@setOnClickListener
            }
            press(askRow) { session.ask(question) }
        }
        closeButton.setOnClickListener { press(closeButton) { session.close() } }
        retryButton.setOnClickListener { press(retryButton) { session.retry() } }
        if (carried != null) {
            session.restore(subject.sentence, carried.answering(question))
        } else {
            session.bind(subject.sentence)
        }
        collector = scope.launch { session.state.collect(::render) }
    }

    /** Ends the question: a request in flight is cancelled and writes
     *  nothing, and the card is drawn no more. */
    fun release() {
        collector.cancel()
        session.close()
    }

    /** [state] on the card: the row with nothing asked, else what came of
     *  the question in [askDisplay]'s words, the X beside it. The title it
     *  gives is the group's header, so the card does not repeat it. */
    private fun render(state: AskState) {
        val shown = askDisplay(ctx, state)
        askRow.isVisible = shown == null
        answerRow.isVisible = shown != null
        if (shown == null) return
        bodyText.text = shown.body
        bodyText.setTextColor(ctx.themeColor(if (shown.muted) R.attr.ptTextMuted else R.attr.ptText))
        footerText.text = shown.footer
        footerText.isVisible = shown.footer != null
        retryButton.isVisible = shown.retry
    }

    /** [action], a press of [control]. Its state is drawn by the time it
     *  returns (the class doc's scope), and every press here takes its
     *  control off the screen: the row and Try again give way to a question
     *  being asked, the X to the row. The control standing in its place
     *  (the row with nothing asked, else the X) takes Android's focus if
     *  [control] had it, and the host hears of it for a cursor of its own. */
    private fun press(control: View, action: () -> Unit) {
        val hadFocus = control.isFocused
        action()
        val next = if (askRow.isVisible) askRow else closeButton
        if (hadFocus) next.requestFocus()
        host.onControlGone(next)
    }

    private fun wrapLine() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
    )

    private fun themeResource(@AttrRes attr: Int): Int =
        TypedValue().also { ctx.theme.resolveAttribute(attr, it, true) }.resourceId

    private fun dp(value: Int) = (value * ctx.resources.displayMetrics.density).toInt()
}
