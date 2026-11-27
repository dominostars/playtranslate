package com.playtranslate.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import com.playtranslate.Prefs
import com.playtranslate.R
import com.playtranslate.ask.AskPreset
import com.playtranslate.ask.AskPresets
import com.playtranslate.ask.AskQuestion
import com.playtranslate.ask.AskSession
import com.playtranslate.ask.AskState
import com.playtranslate.themeColor
import com.playtranslate.translation.TranslationBackendRegistry

/**
 * The results' Ask section (section_ask.xml): the user's LLM asked about the
 * sentence on show, with a ready-made question ([AskPresets]) or their own
 * words, and its answer. Host-agnostic like [WordRowsBinder]: every surface
 * that shows the results sections renders its [AskSession] through here, so
 * the section can't drift between them. Views are found from [root].
 *
 * The conversation is the session's and this binder only draws it: the host
 * collects [AskSession.state] into [render], and the last state rendered is
 * the one thing about it kept here. The card shows one of two groups. While
 * nothing is asked, the questions: the presets as chips, a hairline, then
 * the field and its send button. Otherwise, the question on screen: its
 * title with an X that puts it away, in a row as tall as the chips', then
 * who is being asked, the answer and who gave it (and that it was cut
 * short, when the service says so), the service's word that
 * the question is not one it answers, or the failure and Try again. An
 * answer takes the questions' place rather than sitting under them; X
 * brings them back.
 *
 * Who gave an answer is a row with a gear, the twin of the Translation
 * card's "Translated by": a tap opens the picker of who answers
 * ([AskPicker]), and a pick asks the same question again of that one
 * service ([AskSession.askWith]), changing no setting. From there it is a
 * question like any other: the answer on show gives way to "Asking
 * <service>…", and a failure is the card's, with a Try again that asks the
 * service picked. Only an answer has the row; the service's word that a
 * question is not one it answers, and a failure, credit nobody. A long press
 * on an answer copies it, as one on the Translation's text copies the
 * translation; on the card's other words it does nothing.
 *
 * Hidden is DERIVED, never stored here ([isHidden]): the eye's pref AND a
 * session with no question on show. A collapsed section shows its card
 * exactly while a question is on show, so a question asked from the
 * collapsed header shows its answer without the pref being written, and
 * putting the answer away (X) collapses the section again. The eye hides
 * and shows, and discards nothing, as the other sections' eyes: over a
 * question it shelves it ([AskSession.shelved]), so the request goes on and
 * the answer is there when the section is shown again, and an answer that
 * arrives while shelved stays out of sight. Only X, a new question or a new
 * sentence ends a question. The eye acts on what the card is SHOWING:
 *
 * | pref hidden | state    | shelved | card               | eye     | a tap on the eye                   |
 * |-------------|----------|---------|--------------------|---------|------------------------------------|
 * | no          | Idle     | -       | the questions      | open    | pref = hidden                      |
 * | no          | not Idle | no      | the answer         | open    | pref = hidden, question shelved    |
 * | yes         | Idle     | -       | GONE (header only) | crossed | pref = shown                       |
 * | yes         | not Idle | no      | the answer         | open    | question shelved, pref untouched   |
 * | yes         | not Idle | yes     | GONE (header only) | crossed | pref = shown, question unshelved   |
 *
 * (A question is shelved only by the eye's hide, which leaves the pref
 * hidden, and unshelved by its show, so pref shown never meets shelved
 * here; were another surface to show the section meanwhile, the pref wins
 * and the card is up.)
 *
 * The header is a [SectionHeaderRow] without ⋯. Collapsed, it carries the
 * presets as text pills, in their order and as many as fit beside the label
 * and the eye, so a question is one tap away without opening the section; a
 * pill that doesn't fit is simply not shown, since the opened section lists
 * every preset. Over a shelved question they are offered too, and one asked
 * takes the shelved question's place. Expanded, it is the label and the
 * eye, and while a question is on screen a Text size between them, pinned
 * like the eye since nothing else reaches it: the one-handle
 * [FontSizePopover] for the answer's text ([Prefs.askFontSp]). The pills
 * are fixed slots, one per preset, given the preset in their place whenever
 * the order is read ([applyPresets]).
 *
 * Nothing here shows an alert, starts an Activity or touches a window; the
 * size picker goes on the surface's [PopoverHost], as the other headers' ⋯
 * menus do. A question reached for with no LLM to put it to (a preset, the
 * field, a send) is the host's to explain ([Host.onLlmNeeded]), a field
 * that needs a keyboard is the host's to give one ([Host.onFieldFocusChanged]),
 * and the picker of who answers is filled here and shown by the host
 * ([Host.showAlert]), its way to Translation services the host's too
 * ([Host.openTranslationServices]).
 */
class AskSectionBinder(
    root: View,
    private val ctx: Context,
    private val prefs: Prefs,
    private val session: AskSession,
    /** The surface's one popover host: the answer's size picker shows on it. */
    private val popovers: PopoverHost,
    private val host: Host,
    /** Whether the user has an LLM a question can be put to. */
    private val hasLlm: () -> Boolean = { TranslationBackendRegistry.hasCompleter() },
) {
    interface Host {
        /** Any user interaction with the section (live-mode hosts pause on it). */
        fun onInteraction() {}

        /** The user reached for a question (a preset, or the field) with no
         *  LLM set up: tell them. */
        fun onLlmNeeded()

        /** The question field took or lost input focus: a host whose window
         *  must change to take a keyboard does it here. */
        fun onFieldFocusChanged(focused: Boolean) {}

        /** The card's height or visibility may have changed (a host that
         *  sizes itself to its content). It may not have laid out yet. */
        fun onHeightChanged() {}

        /** Show an alert of the section's on this surface: the host makes
         *  the builder for where it lives (an activity, a window over the
         *  game, the workspace's modal layer), [configure] fills it, the
         *  host shows it. The picker of who answers ([AskPicker]). */
        fun showAlert(configure: (OverlayAlert.Builder) -> Unit)

        /** Open the Translation services page from this surface, as the
         *  button of [onLlmNeeded]'s alert does. */
        fun openTranslationServices()
    }

    private val header: SectionHeaderRow = root.findViewById(R.id.askHeaderRow)
    private val card: View = root.findViewById(R.id.cardAsk)
    private val idleGroup: View = root.findViewById(R.id.askIdle)
    private val chipRow: FlowLayout = root.findViewById(R.id.askPresetChips)
    private val questionField: EditText = root.findViewById(R.id.etAskQuestion)
    private val sendButton: ImageButton = root.findViewById(R.id.btnAskSend)
    private val answerGroup: View = root.findViewById(R.id.askAnswer)
    private val titleText: TextView = root.findViewById(R.id.tvAskTitle)
    private val closeButton: ImageButton = root.findViewById(R.id.btnAskClose)
    private val warningText: TextView = root.findViewById(R.id.tvAskWarning)
    private val bodyText: TextView = root.findViewById(R.id.tvAskBody)
    private val retryButton: TextView = root.findViewById(R.id.btnAskRetry)
    private val cutText: TextView = root.findViewById(R.id.tvAskCut)
    // "Answered by <service>" + gear, under an answer: the row the credit
    // sits in, which taps to the picker of who answers.
    private val creditRow: View = root.findViewById(R.id.askCreditRow)
    private val footerText: TextView = root.findViewById(R.id.tvAskFooter)
    private val serviceGear: ImageView = root.findViewById(R.id.btnAskService)

    private val chipGapPx = (CHIP_GAP_DP * ctx.resources.displayMetrics.density).toInt()

    /** The collapsed header's pills: one slot per preset there is now, in
     *  order. A slot is a place, not a preset: [applyPresets] gives each the
     *  preset in its place. The icon is what a ⋯ menu row would show, and
     *  this header has no ⋯, so any glyph serves. */
    private val slots: List<HeaderAction> = AskPresets.ordered(ctx, prefs).map { preset ->
        HeaderAction(
            View.generateViewId(), R.drawable.ic_send, preset.title,
            presentation = HeaderAction.Presentation.TEXT_PILL,
        )
    }

    /** The answer's Text size: offered while a question is on screen
     *  ([applyVisibility]), pinned because a header without ⋯ drops an
     *  action that doesn't fit and nothing else opens the picker. */
    private val textSize = HeaderAction(
        R.id.btnFontSizeAsk, R.drawable.ic_format_size, ctx.getString(R.string.cd_text_size), pinned = true,
    )

    /** The section's eye: pinned, so it sits at the header's end like the
     *  other sections' eyes. The row tints and draws it from this model. */
    private val eye = HeaderAction(
        R.id.btnToggleAsk, R.drawable.ic_visibility,
        ctx.getString(R.string.cd_toggle_ask_visibility), pinned = true,
    )

    /** The answer's size picker, writing the pref live; each change resizes
     *  the text and so the card. */
    private val sizePicker = FontSizePopover(
        read = { prefs.askFontSp },
        write = { prefs.askFontSp = it },
    ).apply {
        onSizeChanged = {
            applyTextSize()
            host.onHeightChanged()
        }
    }

    /** The state [render] last drew: Idle until the first render. */
    private var state: AskState = AskState.Idle

    /** Whether the card is hidden: the pref, and no question on show (class
     *  doc). A question keeps the card up whatever the pref says, unless
     *  the user shelved it. */
    val isHidden: Boolean
        get() = prefs.hideAskSection && (state is AskState.Idle || session.shelved)

    val fieldHasFocus: Boolean get() = questionField.isFocused

    init {
        header.overflowEnabled = false
        header.setActions(slots + textSize + eye, R.id.btnMoreAsk)
        textSize.onClick = { anchor ->
            // Like a chip or X: a live host holds the sentence whose answer
            // is being sized, which the next line would otherwise replace
            // under the open picker.
            host.onInteraction()
            popovers.toggle(sizePicker, anchor)
        }
        eye.onClick = {
            // The opposite of what the card is SHOWING. The pref says so
            // unless it already does (a collapsed section a question keeps
            // open), and a question is shelved with it, or it would keep
            // the card up: kept, not closed, so the eye that hid an answer
            // brings it back.
            val hide = !isHidden
            if (hide) clearFieldFocus()
            if (prefs.hideAskSection != hide) prefs.hideAskSection = hide
            session.shelve(hide)
            // Shelving is no change of the session's state, so nothing
            // re-renders: the card follows from here.
            applyVisibility()
            host.onHeightChanged()
        }

        chipRow.lineSpacingPx = chipGapPx
        // Tinted in code, not with app:tint: the over-game panel's plain
        // inflater drops app:tint (TranslationSectionBinder's gear).
        closeButton.imageTintList = ColorStateList.valueOf(ctx.themeColor(R.attr.ptTextMuted))
        closeButton.setOnClickListener {
            host.onInteraction()
            session.close()
        }
        retryButton.setOnClickListener {
            host.onInteraction()
            session.retry()
        }
        // Tinted in code like the X, and for the same reason.
        serviceGear.imageTintList = ColorStateList.valueOf(ctx.themeColor(R.attr.ptTextHint))
        creditRow.setOnClickListener { chooseService() }
        // A long press on an answer copies it, as one on the Translation's
        // text copies the translation. An answer only: "Asking…", the
        // out-of-scope line and a failure are the app's words on the
        // question, and a press on them is no long press.
        bodyText.setOnLongClickListener {
            val answer = (state as? AskState.Answered)?.text ?: return@setOnLongClickListener false
            copyToClipboard(ctx, answer)
            true
        }

        // With no LLM a touch on the field leads to the host's explanation,
        // not to a keyboard: every event of it is kept from the field, so it
        // neither takes focus nor starts a selection, and its end is a click
        // (below). Decided where the touch begins, for the whole of it: the
        // answer costs a file read per on-device model, which a drag that
        // starts on the field would pay at every move.
        var keptFromField = false
        questionField.setOnTouchListener { v, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) keptFromField = !hasLlm()
            if (!keptFromField) return@setOnTouchListener false
            if (event.actionMasked == MotionEvent.ACTION_UP) v.performClick()
            true
        }
        // A click is how the field is reached without a finger on it: a
        // controller's A clicks its target ([NavAction.activate]), and so
        // does the end of a touch with no LLM. With an LLM a finger's first
        // touch focuses the field itself and clicks nothing.
        questionField.setOnClickListener { reachForField() }
        questionField.setOnFocusChangeListener { _, focused ->
            // Starting to type is an interaction, like a tap on a chip: a
            // live host holds the sentence the question is being written
            // about, which the next line would otherwise replace under it.
            if (focused) host.onInteraction()
            host.onFieldFocusChanged(focused)
        }
        questionField.doAfterTextChanged { syncSend() }
        questionField.setOnEditorActionListener { _, actionId, _ ->
            if (actionId != EditorInfo.IME_ACTION_SEND) return@setOnEditorActionListener false
            send()
            true
        }
        sendButton.setOnClickListener { send() }

        syncSend()
        applyPresets()
        applyVisibility()
    }

    /** Draw [state]: the host's collection of the session's state calls
     *  this with every state, the current one first. */
    fun render(state: AskState) {
        this.state = state
        // The saved size: the first render (a host collects the session
        // from construction, the current state first) applies it, and a
        // later one a change made by another surface's picker.
        applyTextSize()
        // A question on screen takes the field's place.
        if (state !is AskState.Idle) clearFieldFocus()
        idleGroup.isVisible = state is AskState.Idle
        answerGroup.isVisible = state !is AskState.Idle
        // The state in words is [askDisplay]'s, shared with every other
        // surface that draws one; with nothing asked, the questions.
        askDisplay(ctx, state)?.let(::showQuestion) ?: applyPresets()
        applySpoilerWarning()
        applyVisibility()
        host.onHeightChanged()
    }

    /** Re-read whether the Translation section is hidden, which is what
     *  shows the spoiler line over a question. The host calls this when that
     *  section's eye is tapped; the line changes the card's height, so the
     *  host hears of it when it comes or goes. */
    fun syncSpoilerWarning() {
        if (applySpoilerWarning()) host.onHeightChanged()
    }

    /** Drop the field's focus and its keyboard: a host's Back or B, and here
     *  whenever the field is about to go out of sight (a question takes its
     *  place, the eye hides the card). A view that stops showing drops its
     *  focus by itself and leaves the keyboard up, so each of those asks
     *  first. A no-op when the field has no focus. */
    fun clearFieldFocus() {
        if (!questionField.isFocused) return
        // The keyboard first, while the field is still the view it serves.
        ctx.getSystemService(InputMethodManager::class.java)
            ?.hideSoftInputFromWindow(questionField.windowToken, 0)
        questionField.clearFocus()
    }

    /** What a controller cursor can reach here, in visual order, and only
     *  what is on screen: the header's (its shown pills or the Text size,
     *  then the eye), then
     *  the card's. One of the card's two groups shows, so keeping the shown
     *  views of both gives the chips, the field and send, or X and Try
     *  again; send has nothing to do (and is no target) while disabled. The
     *  field's A is a click ([reachForField]). The answer's credit row is
     *  left out, as the Translation card's two credit rows are
     *  ([TranslationSectionBinder.navigableActions]): it opens a picker
     *  window the controller cannot drive. */
    fun navigableActions(): List<NavAction> {
        val chips = (0 until chipRow.childCount).map(chipRow::getChildAt)
        val inCard = chips + listOf(questionField, sendButton, closeButton, retryButton)
        return header.navActions() + inCard.filter { it.isShown && it.isEnabled }.map { NavAction(it) }
    }

    // ── Rendering ────────────────────────────────────────────────────────

    /** Reflect [isHidden]: the card, the eye, the label, and the header's
     *  pills, which are offered only while the card is hidden. The Text size
     *  is offered while a question is on screen: the card up with the text
     *  it sizes (a question keeps even a collapsed section's card up), and
     *  not over a shelved question, whose text is out of sight. */
    private fun applyVisibility() {
        val hidden = isHidden
        card.visibility = if (hidden) View.GONE else View.VISIBLE
        eye.icon = if (hidden) R.drawable.ic_visibility_off else R.drawable.ic_visibility
        header.labelDimmed = hidden
        for (slot in slots) slot.available = hidden
        textSize.available = !hidden && state !is AskState.Idle
    }

    /** The answer's text at its saved size ([Prefs.askFontSp]): the
     *  "Asking…" line, the answer and a failure share the one view. */
    private fun applyTextSize() {
        bodyText.setTextSize(TypedValue.COMPLEX_UNIT_SP, prefs.askFontSp.toFloat())
    }

    /** The spoiler line shows over a question while the Translation section
     *  is hidden, and never with nothing asked. Returns whether it changed. */
    private fun applySpoilerWarning(): Boolean {
        val show = state !is AskState.Idle && prefs.hideTranslationSection
        if (warningText.isVisible == show) return false
        warningText.isVisible = show
        return true
    }

    /** The presets in [AskPresets]' order, read afresh: the card's chips
     *  rebuilt, and each header slot given the preset in its place. Run at
     *  construction and on every Idle render, the first moment a preset just
     *  asked (now leading the order) can be seen again. */
    private fun applyPresets() {
        val presets = AskPresets.ordered(ctx, prefs)
        chipRow.removeAllViews()
        val inflater = LayoutInflater.from(chipRow.context)
        for (preset in presets) {
            val chip = inflater.inflate(R.layout.ask_preset_chip, chipRow, false) as TextView
            (chip.layoutParams as ViewGroup.MarginLayoutParams).marginEnd = chipGapPx
            chip.text = preset.title
            chip.setOnClickListener { askPreset(preset) }
            chipRow.addView(chip)
        }
        for ((slot, preset) in slots.zip(presets)) {
            slot.label = preset.title
            slot.contentDescription = preset.title
            slot.onClick = { askPreset(preset) }
        }
    }

    /** The answer group for a question on screen ([shown]): its title,
     *  then its body, muted where it is the app's word and not an answer,
     *  with the footer under it and Try again where given. */
    private fun showQuestion(shown: AskDisplay) {
        titleText.text = shown.title
        titleText.setTypeface(null, if (readsInItalic(shown.title)) Typeface.ITALIC else Typeface.NORMAL)
        bodyText.text = shown.body
        bodyText.setTextColor(ctx.themeColor(if (shown.muted) R.attr.ptTextMuted else R.attr.ptText))
        cutText.text = shown.notice
        cutText.isVisible = shown.notice != null
        // The credit and its gear come and go together: whatever is
        // credited with an answer can be asked again, or another in its
        // place ([chooseService]).
        footerText.text = shown.credit
        creditRow.isVisible = shown.credit != null
        retryButton.isVisible = shown.retry
    }

    /** The credit tapped, its text or its gear: the picker of who answers
     *  ([AskPicker]) for the answer on show, on the host's alert surface. A
     *  pick asks that answer's question again of the service picked, and of
     *  it alone ([AskSession.askWith]); nothing else changes. An
     *  interaction, like every control of this card: a live host holds the
     *  sentence the answer is about, which the next line would otherwise
     *  replace under the open picker, ending the question a pick is for. */
    private fun chooseService() {
        val answered = state as? AskState.Answered ?: return
        host.onInteraction()
        host.showAlert { builder ->
            AskPicker.populate(
                builder, ctx, answered.answeredBy,
                onPick = { backend -> session.askWith(backend, answered) },
                onOpenServices = { host.openTranslationServices() },
            )
        }
    }

    /** Whether [text] is drawn in italic: when every letter in it is Latin
     *  script, the rule WordDetailBinder's example translations follow by
     *  language (targetSupportsItalics). CJK has no italic, so Android
     *  would slant its glyphs, and Cyrillic's and Greek's italics are
     *  different letterforms. The text decides rather than a language
     *  setting: a preset's title is in the app's language, a question of
     *  the user's own in whatever they typed. */
    private fun readsInItalic(text: CharSequence): Boolean =
        text.codePoints().noneMatch {
            Character.isLetter(it) && Character.UnicodeScript.of(it) != Character.UnicodeScript.LATIN
        }

    // ── Asking ───────────────────────────────────────────────────────────

    private fun askPreset(preset: AskPreset) {
        host.onInteraction()
        if (!hasLlm()) {
            host.onLlmNeeded()
            return
        }
        AskPresets.markAsked(prefs, preset)
        session.ask(AskQuestion.Preset(preset))
    }

    /** The field clicked: a controller's A ([navigableActions]), or the end
     *  of a touch with no LLM. With no LLM, the host's explanation instead
     *  of a keyboard; with one, the focus a finger's touch would give it. */
    private fun reachForField() {
        if (!hasLlm()) {
            host.onInteraction()
            host.onLlmNeeded()
            return
        }
        questionField.requestFocus()
    }

    /** Send the typed question, trimmed: nothing for a blank one. */
    private fun send() {
        val text = questionField.text.toString().trim()
        if (text.isEmpty()) return
        host.onInteraction()
        if (!hasLlm()) {
            host.onLlmNeeded()
            return
        }
        questionField.text.clear()
        session.ask(AskQuestion.Custom(text))
    }

    /** Send is offered while there is a question to send: enabled and in
     *  the accent, otherwise disabled in the hint colour. */
    private fun syncSend() {
        val sendable = questionField.text.isNotBlank()
        sendButton.isEnabled = sendable
        sendButton.imageTintList = ColorStateList.valueOf(
            ctx.themeColor(if (sendable) R.attr.ptAccent else R.attr.ptTextHint),
        )
    }

    private companion object {
        /** Between two chips, and between two lines of them. */
        const val CHIP_GAP_DP = 6f
    }
}
