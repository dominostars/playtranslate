package com.playtranslate.ui

import android.content.ClipboardManager
import android.graphics.Rect
import android.icu.text.BreakIterator
import android.text.Editable
import android.text.Selection
import android.text.Spannable
import android.text.method.PasswordTransformationMethod
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewTreeObserver
import android.view.inputmethod.InputMethodManager
import android.widget.TextView
import androidx.annotation.VisibleForTesting
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * A selection the app makes in a text field of an overlay window, where
 * Android refuses the field one: while the keyboard runs full screen.
 *
 * In that mode Android leaves selecting to the keyboard's own full-screen
 * editor, and the field selects nothing at a long press or a double tap and
 * starts no Cut / Copy / Paste bar. The fields over the game ask the keyboard
 * for no full-screen editor (so the game and the sheet stay in sight while
 * the user types), and a keyboard may run full screen all the same: Gboard
 * does on the AYN Thor's top screen. Selecting is then nobody's. So the
 * window's root ([OverlayWindowRoot]) hands such a field's long press and
 * double tap to this class instead.
 *
 * It picks what the platform's field would: at a long press on the text, the
 * word under the finger (the character there when it is in no word); off the
 * text, the cursor there; at a double tap on the text, the word. Then the
 * window's bar ([OverlayTextToolbar]) with the items the platform's field
 * would offer: Cut, Copy, Paste, Select all, each done by the field itself
 * ([TextView.onTextContextMenuItem]). What it does not have is the
 * platform's handles, which Android shows only around a selection of its
 * own: a selection is widened with Select all, not dragged.
 *
 * The bar is for the selection it was started with. It ends when that
 * selection goes (a tap moving the cursor, typing over it), when the field
 * loses focus, its window loses focus, or it leaves its window, after Cut,
 * Copy or Paste, and before a clean capture (the root takes it down). As the platform's bar does, it leaves a selection it
 * ends collapsed at its end.
 */
internal class OverlayTextSelection private constructor(
    private val field: TextView,
    private val onEnd: (OverlayTextSelection) -> Unit,
) : ActionMode.Callback2() {

    private var mode: ActionMode? = null

    @get:VisibleForTesting
    internal val barForTest: ActionMode? get() = mode

    /** The selection the bar is for. */
    private var selStart = 0
    private var selEnd = 0

    private var observer: ViewTreeObserver? = null
    private var ended = false

    private val watch = ViewTreeObserver.OnPreDrawListener {
        if (!field.isFocused || field.selectionStart != selStart || field.selectionEnd != selEnd) {
            finish()
        } else {
            // The selection moves with the field's own scroll.
            mode?.invalidateContentRect()
        }
        true
    }

    private val windowFocus = ViewTreeObserver.OnWindowFocusChangeListener { has -> if (!has) finish() }

    private val detach = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) {}
        override fun onViewDetachedFromWindow(v: View) = finish()
    }

    /** Take the bar off, and end the selection's watch. */
    fun finish() {
        mode?.finish()
    }

    private fun follow(bar: ActionMode) {
        mode = bar
        selStart = field.selectionStart
        selEnd = field.selectionEnd
        observer = field.viewTreeObserver.also {
            it.addOnPreDrawListener(watch)
            it.addOnWindowFocusChangeListener(windowFocus)
        }
        field.addOnAttachStateChangeListener(detach)
    }

    override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
        fill(menu)
        return menu.hasVisibleItems()
    }

    override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
        fill(menu)
        return true
    }

    /** The platform field's items for the selection as it now is, in its
     *  order: Cut and Copy while there is a selection, Paste while the
     *  clipboard holds something, Select all while not everything is
     *  selected; nothing of a password but Paste. */
    private fun fill(menu: Menu) {
        menu.clear()
        val text = field.text
        val selected = field.hasSelection()
        val secret = field.transformationMethod is PasswordTransformationMethod
        val editable = text is Editable && field.keyListener != null
        val all = min(field.selectionStart, field.selectionEnd) == 0 &&
            max(field.selectionStart, field.selectionEnd) == text.length
        if (selected && editable && !secret) menu.add(Menu.NONE, android.R.id.cut, 0, android.R.string.cut)
        if (selected && !secret) menu.add(Menu.NONE, android.R.id.copy, 1, android.R.string.copy)
        if (editable && field.context.getSystemService(ClipboardManager::class.java)?.hasPrimaryClip() == true) {
            menu.add(Menu.NONE, android.R.id.paste, 2, android.R.string.paste)
        }
        if (text.isNotEmpty() && !secret && !all) {
            menu.add(Menu.NONE, android.R.id.selectAll, 3, android.R.string.selectAll)
        }
    }

    override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.selectAll -> {
                field.onTextContextMenuItem(android.R.id.selectAll)
                selStart = field.selectionStart
                selEnd = field.selectionEnd
                mode.invalidate()
            }
            android.R.id.cut, android.R.id.copy, android.R.id.paste -> {
                field.onTextContextMenuItem(item.itemId)
                mode.finish()
            }
            else -> return false
        }
        return true
    }

    override fun onDestroyActionMode(mode: ActionMode) {
        if (ended) return
        ended = true
        observer?.let {
            if (it.isAlive) {
                it.removeOnPreDrawListener(watch)
                it.removeOnWindowFocusChangeListener(windowFocus)
            }
        }
        observer = null
        field.removeOnAttachStateChangeListener(detach)
        val text = field.text
        if (selStart != selEnd && field.selectionStart == selStart && field.selectionEnd == selEnd &&
            text is Spannable
        ) {
            Selection.setSelection(text, selEnd)
        }
        onEnd(this)
    }

    /** The selection's lines, in the field's coordinates: the bar goes over it. */
    override fun onGetContentRect(mode: ActionMode, view: View, outRect: Rect) {
        val layout = field.layout ?: run {
            outRect.set(0, 0, field.width, field.height)
            return
        }
        val s = min(field.selectionStart, field.selectionEnd)
        val e = max(field.selectionStart, field.selectionEnd)
        val first = layout.getLineForOffset(s)
        val last = layout.getLineForOffset(e)
        val left: Float
        val right: Float
        if (first == last) {
            val a = layout.getPrimaryHorizontal(s)
            val b = if (e == s) a + 1f else layout.getPrimaryHorizontal(e)
            left = min(a, b)
            right = max(a, b)
        } else {
            left = 0f
            right = layout.width.toFloat()
        }
        val dx = field.totalPaddingLeft - field.scrollX
        val dy = field.totalPaddingTop - field.scrollY
        outRect.set(
            floor(left).toInt() + dx, layout.getLineTop(first) + dy,
            ceil(right).toInt() + dx, layout.getLineBottom(last) + dy,
        )
    }

    companion object {
        /** Whether Android refuses [field] a selection of its own: an
         *  enabled text field while the keyboard runs full screen (the gate
         *  the platform's field checks before it selects). */
        fun refused(field: TextView): Boolean =
            field.isEnabled && field.onCheckIsTextEditor() &&
                field.context.getSystemService(InputMethodManager::class.java)?.isFullscreenMode == true

        /** A long press at [x], [y] (the field's coordinates): on the text,
         *  the word there; off it, the cursor there; then the bar, when there
         *  is anything to offer. Null when there is no bar. */
        fun atLongPress(
            field: TextView, x: Float, y: Float, onEnd: (OverlayTextSelection) -> Unit,
        ): OverlayTextSelection? {
            if (!field.requestFocus()) return null
            val text = field.text as? Spannable ?: return null
            val offset = field.getOffsetForPosition(x, y)
            if (onText(field, x, y)) {
                val (s, e) = wordAt(text, offset, field.textLocale)
                Selection.setSelection(text, s, e)
            } else {
                Selection.setSelection(text, offset)
            }
            return show(field, onEnd)
        }

        /** A double tap lifted at [x], [y] (the field's coordinates): on the
         *  text, the word there and the bar; off it, nothing (null). */
        fun atDoubleTap(
            field: TextView, x: Float, y: Float, onEnd: (OverlayTextSelection) -> Unit,
        ): OverlayTextSelection? {
            if (!onText(field, x, y)) return null
            val text = field.text as? Spannable ?: return null
            val (s, e) = wordAt(text, field.getOffsetForPosition(x, y), field.textLocale)
            Selection.setSelection(text, s, e)
            return show(field, onEnd)
        }

        /** The bar asked for as the platform's field asks for its own, so it
         *  is the window root's ([OverlayWindowRoot.startActionModeForChild]),
         *  which knows every bar it has up. */
        private fun show(field: TextView, onEnd: (OverlayTextSelection) -> Unit): OverlayTextSelection? {
            val selection = OverlayTextSelection(field, onEnd)
            val bar = field.startActionMode(selection, ActionMode.TYPE_FLOATING) ?: return null
            selection.follow(bar)
            return selection
        }

        /** Whether [x], [y] is on the field's text, as the platform's field
         *  decides it: within its line's extent, the point first pulled into
         *  the text area. */
        private fun onText(field: TextView, x: Float, y: Float): Boolean {
            val layout = field.layout ?: return false
            if (field.text.isEmpty()) return false
            val ly = (y - field.totalPaddingTop).coerceAtLeast(0f)
                .coerceAtMost((field.height - field.totalPaddingBottom - 1).toFloat()) + field.scrollY
            val lx = (x - field.totalPaddingLeft).coerceAtLeast(0f)
                .coerceAtMost((field.width - field.totalPaddingRight - 1).toFloat()) + field.scrollX
            val line = layout.getLineForVertical(ly.toInt())
            return lx >= layout.getLineLeft(line) && lx <= layout.getLineRight(line)
        }

        /** The word of [text] at [offset], as the platform's field picks it:
         *  the word the offset is in, else the one it ends; in no word, the
         *  character there. Words are the locale's (dictionary words in
         *  Japanese and Chinese). */
        @VisibleForTesting
        internal fun wordAt(text: CharSequence, offset: Int, locale: Locale): Pair<Int, Int> {
            val s = text.toString()
            if (s.isEmpty()) return 0 to 0
            val at = offset.coerceIn(0, s.length)
            val words = BreakIterator.getWordInstance(locale).apply { setText(s) }
            fun wordHolding(i: Int): Pair<Int, Int>? {
                if (i !in s.indices) return null
                val end = words.following(i)
                val isWord = words.ruleStatus >= BreakIterator.WORD_NONE_LIMIT
                val start = words.previous()
                return if (isWord) start to end else null
            }
            wordHolding(at)?.let { return it }
            wordHolding(at - 1)?.let { return it }
            val chars = BreakIterator.getCharacterInstance(locale).apply { setText(s) }
            val end = chars.following(at.coerceAtMost(s.length - 1))
            return chars.previous() to end
        }
    }
}
