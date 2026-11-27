package com.playtranslate.ui

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.PixelFormat
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.view.ActionMode
import android.view.Display
import android.view.Gravity
import android.view.InputDevice
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.view.textclassifier.TextClassifier
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.playtranslate.R
import com.playtranslate.overlay.OverlayHost
import com.playtranslate.overlay.OverlayPlacement
import com.playtranslate.overlayThemedContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowInputMethodManager
import java.time.Duration
import java.util.Locale

/**
 * The keyboard, as the test sets it: full screen or not. Robolectric's own
 * keyboard never is; a full-screen one is what the platform's text field
 * checks before it selects, and refuses a selection under (Gboard runs so on
 * the AYN Thor's top screen, with the field asking it not to).
 */
@Implements(InputMethodManager::class)
class FullScreenKeyboard : ShadowInputMethodManager() {
    @Implementation
    public override fun isFullscreenMode(): Boolean = on

    companion object {
        @JvmStatic var on = false
    }
}

/**
 * Pins [OverlayTextSelection] and its two ways in through [OverlayWindowRoot]:
 * the selection the app makes in an overlay window's text field while the
 * keyboard runs full screen, when the platform's field (the real one here)
 * selects nothing and starts no bar.
 *
 * A long press on a word selects it and shows the window's bar with Cut,
 * Copy (and Paste with something to paste) and Select all, none of it the
 * platform's (whose own selection never starts); Copy, Cut and Paste do what
 * they say and end the bar, Copy leaving the cursor at the word's end; Select
 * all selects everything and keeps the bar, less its Select all. A long press
 * past the text puts the cursor there with Paste and Select all; one in an
 * empty field offers Paste, which pastes, and with nothing to paste shows no
 * bar. A double tap on a word selects it with the bar; one off the text does
 * nothing. The bar ends when its selection goes (the cursor moved, the text
 * typed over) and when the field loses focus; and a clean capture takes it
 * down before the screenshot, the app's and the platform's alike, each
 * selection ending with its bar.
 *
 * Where the platform does select, the root stays out: with the keyboard not
 * full screen the platform's own selection and items run, at a long press and
 * at a double tap; and a root under a decor (hosted in an activity) leaves the
 * long press to the decor. And the word picked is the locale's: an English
 * word, the word an offset ends, a character in no word, a Japanese
 * dictionary word.
 */
@RunWith(RobolectricTestRunner::class)
@Config(shadows = [FullScreenKeyboard::class])
class OverlayTextSelectionTest {

    class Host : Activity() {
        override fun onCreate(savedInstanceState: Bundle?) {
            setTheme(R.style.Theme_PlayTranslate)
            super.onCreate(savedInstanceState)
        }
    }

    private val app: Context = ApplicationProvider.getApplicationContext()
    private val ctx = overlayThemedContext(app)
    private val wm = app.getSystemService(WindowManager::class.java)
    private val clipboard = app.getSystemService(ClipboardManager::class.java)
    private lateinit var root: OverlayWindowRoot
    private lateinit var field: EditText

    /** The bar the platform's own field starts, when it starts one: caught
     *  through the field's custom callbacks, which add nothing. */
    private var platformBar: ActionMode? = null
    private val catcher = object : ActionMode.Callback {
        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
            platformBar = mode
            return true
        }
        override fun onPrepareActionMode(mode: ActionMode, menu: Menu) = false
        override fun onActionItemClicked(mode: ActionMode, item: MenuItem) = false
        override fun onDestroyActionMode(mode: ActionMode) {}
    }

    @Before fun setUp() {
        FullScreenKeyboard.on = true
        root = OverlayWindowRoot(ctx)
        field = EditText(ctx).apply {
            setTextClassifier(TextClassifier.NO_OP)
            customSelectionActionModeCallback = catcher
            customInsertionActionModeCallback = catcher
        }
        root.addView(field, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.CENTER))
        wm.addView(root, WindowManager.LayoutParams(
            MATCH, MATCH, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, 0, PixelFormat.TRANSLUCENT,
        ))
        idle()
    }

    @After fun tearDown() {
        FullScreenKeyboard.on = false
        if (root.isAttachedToWindow) wm.removeViewImmediate(root)
        idle()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))

    /** The bar of the selection the root made, while it has one; asserted
     *  on screen. */
    private fun bar(): OverlayTextToolbar? {
        val mode = root.selectionForTest?.barForTest ?: return null
        val bar = mode as? OverlayTextToolbar ?: throw AssertionError("not the window's bar: $mode")
        assertTrue("on screen", bar.isShowing)
        return bar
    }

    private fun OverlayTextToolbar.labels(): List<String> =
        (0 until rowForTest.childCount).map { (rowForTest.getChildAt(it) as TextView).text.toString() }

    private fun OverlayTextToolbar.tap(label: String) {
        (0 until rowForTest.childCount).map { rowForTest.getChildAt(it) as TextView }
            .single { it.text == label }.performClick()
        idle()
    }

    /** Text set, and the field focused with its cursor in it, as a tap leaves it. */
    private fun type(text: String) {
        field.setText(text)
        field.requestFocus()
        idle()
    }

    private fun send(target: View, action: Int, x: Float, y: Float, down: Long, at: Long = SystemClock.uptimeMillis()) {
        val e = MotionEvent.obtain(down, at, action, x, y, 0)
        e.source = InputDevice.SOURCE_TOUCHSCREEN
        target.dispatchTouchEvent(e)
        e.recycle()
    }

    /** A finger held on the field at [x], [y] past the long-press timeout, then lifted. */
    private fun longPress(x: Float, y: Float) {
        val down = SystemClock.uptimeMillis()
        send(root, MotionEvent.ACTION_DOWN, x + field.left, y + field.top, down)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ViewConfiguration.getLongPressTimeout() + 100L))
        send(root, MotionEvent.ACTION_UP, x + field.left, y + field.top, down)
        idle()
    }

    /** Two quick taps on the field at [x], [y], through the window's root:
     *  100 ms apart, inside the platform's double-tap window (40 to 300 ms
     *  between the first lift and the second press). */
    private fun doubleTap(x: Float, y: Float) {
        val wx = x + field.left
        val wy = y + field.top
        repeat(2) {
            val down = SystemClock.uptimeMillis()
            send(root, MotionEvent.ACTION_DOWN, wx, wy, down)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(30))
            send(root, MotionEvent.ACTION_UP, wx, wy, down)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
        }
        idle()
    }

    /** A frame, as a selection's redraw brings one on a device: the test's
     *  window schedules none for an invalidate of a view it never drew. */
    private fun frame() {
        field.requestLayout()
        idle()
    }

    /** The y of the field's first line's middle, in the field's coordinates. */
    private fun lineY(): Float {
        val layout = field.layout
        return (layout.getLineTop(0) + layout.getLineBottom(0)) / 2f + field.totalPaddingTop
    }

    /** A point of the field's first line that the field places in [start]
     *  until [end]: found by the field's own hit test, as the test's text
     *  layout puts every character after the first at one x. */
    private fun xIn(start: Int, end: Int): Float {
        val y = lineY()
        return (0 until field.width).map { it.toFloat() }
            .firstOrNull { field.getOffsetForPosition(it, y) in start..end }
            ?: throw AssertionError("no point of the field lands in $start..$end")
    }

    private fun selected() = field.text.substring(field.selectionStart, field.selectionEnd)

    private fun clip() = clipboard.primaryClip?.getItemAt(0)?.text?.toString()

    private val cut get() = app.getString(android.R.string.cut)
    private val copy get() = app.getString(android.R.string.copy)
    private val paste get() = app.getString(android.R.string.paste)
    private val selectAll get() = app.getString(android.R.string.selectAll)

    // ── A long press ─────────────────────────────────────────────────────

    @Test fun `with the keyboard full screen, a long press on a word selects it and shows the window's bar, the platform's own selection never starting`() {
        type("hello world")

        longPress(xIn(0, 5), lineY())

        assertEquals("hello", selected())
        val bar = bar()
        assertNotNull("the window's bar", bar)
        assertEquals(listOf(cut, copy, selectAll), bar!!.labels())
        assertNull("the platform's field started no bar of its own", platformBar)
    }

    @Test fun `Copy copies the word, keeps the text and ends the bar, the cursor left at the word's end`() {
        type("hello world")
        longPress(xIn(0, 5), lineY())

        val b = bar()!!
        b.tap(copy)

        assertEquals("hello", clip())
        assertEquals("hello world", field.text.toString())
        assertFalse("the bar is off", b.isShowing)
        assertNull(root.selectionForTest)
        assertEquals(5, field.selectionStart)
        assertEquals(5, field.selectionEnd)
    }

    @Test fun `Cut takes the word out and onto the clipboard, and ends the bar`() {
        type("hello world")
        longPress(xIn(0, 5), lineY())

        val b = bar()!!
        b.tap(cut)

        assertEquals("hello", clip())
        assertEquals(" world", field.text.toString())
        assertFalse(b.isShowing)
        assertNull(root.selectionForTest)
    }

    @Test fun `Paste puts the clipboard's text in the word's place, and ends the bar`() {
        clipboard.setPrimaryClip(ClipData.newPlainText("test", "猫"))
        type("hello world")
        longPress(xIn(6, 11), lineY())
        assertEquals("world", selected())
        val b = bar()!!
        assertEquals(listOf(cut, copy, paste, selectAll), b.labels())

        b.tap(paste)

        assertEquals("hello 猫", field.text.toString())
        assertFalse(b.isShowing)
        assertNull(root.selectionForTest)
    }

    @Test fun `Select all selects everything and keeps the bar, less its Select all`() {
        type("hello world")
        longPress(xIn(0, 5), lineY())

        bar()!!.tap(selectAll)
        frame()

        assertEquals("hello world", selected())
        val bar = bar()
        assertNotNull("the bar stays, past the next frame", bar)
        assertEquals(listOf(cut, copy), bar!!.labels())
    }

    @Test fun `a long press past the text puts the cursor there, with Paste and Select all`() {
        clipboard.setPrimaryClip(ClipData.newPlainText("test", "x"))
        type("hello")

        longPress(field.width - field.totalPaddingRight - 4f, lineY())

        assertEquals("the cursor, no selection", field.selectionStart, field.selectionEnd)
        assertEquals("at the text's end", 5, field.selectionEnd)
        assertEquals(listOf(paste, selectAll), bar()!!.labels())
    }

    @Test fun `a long press in an empty field offers Paste, which pastes`() {
        clipboard.setPrimaryClip(ClipData.newPlainText("test", "この文の文法は？"))
        type("")

        longPress(field.width / 2f, field.height / 2f)
        val b = bar()!!
        assertEquals(listOf(paste), b.labels())
        b.tap(paste)

        assertEquals("この文の文法は？", field.text.toString())
        assertFalse(b.isShowing)
        assertNull(root.selectionForTest)
    }

    @Test fun `a long press in an empty field with nothing to paste shows no bar`() {
        clipboard.clearPrimaryClip()
        type("")

        longPress(field.width / 2f, field.height / 2f)

        assertNull(root.selectionForTest)
        assertNull(platformBar)
    }

    // ── A double tap ─────────────────────────────────────────────────────

    @Test fun `with the keyboard full screen, a double tap on a word selects it and shows the window's bar`() {
        type("hello world")

        doubleTap(xIn(6, 11), lineY())

        assertEquals("world", selected())
        assertEquals(listOf(cut, copy, selectAll), bar()!!.labels())
        assertNull(platformBar)
    }

    @Test fun `a double tap past the text selects nothing and shows no bar`() {
        type("hello")

        doubleTap(field.width - field.totalPaddingRight - 4f, lineY())

        assertFalse(field.hasSelection())
        assertNull(root.selectionForTest)
    }

    // ── The bar's end ────────────────────────────────────────────────────

    @Test fun `the bar ends when its selection goes, the cursor moved or the word typed over`() {
        type("hello world")
        longPress(xIn(0, 5), lineY())
        val first = bar()!!
        field.setSelection(8)
        frame()
        assertFalse("the cursor moved", first.isShowing)
        assertNull(root.selectionForTest)

        longPress(xIn(0, 5), lineY())
        val second = bar()!!
        field.text.replace(0, 5, "bye")
        frame()
        assertFalse("typed over", second.isShowing)
        assertNull(root.selectionForTest)
        assertEquals("bye world", field.text.toString())
    }

    @Test fun `the bar ends when the field loses focus`() {
        val other = EditText(ctx)
        (field.parent as FrameLayout).addView(other, FrameLayout.LayoutParams(100, 100))
        type("hello world")
        longPress(xIn(0, 5), lineY())
        val b = bar()!!

        other.requestFocus()
        idle()

        assertFalse(b.isShowing)
        assertNull(root.selectionForTest)
    }

    // ── A clean capture ──────────────────────────────────────────────────

    /** The test's window again, registered with an overlay host as the
     *  over-game windows are, for its clean captures to blank. */
    private fun registered(): OverlayHost {
        wm.removeViewImmediate(root)
        idle()
        val host = OverlayHost(app, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        assertTrue(host.addOverlayWindow(
            root, wm,
            WindowManager.LayoutParams(MATCH, MATCH, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, 0, PixelFormat.TRANSLUCENT),
            Display.DEFAULT_DISPLAY, OverlayPlacement.Content,
        ))
        idle()
        return host
    }

    @Test fun `a clean capture takes the bar of the app's selection down before the screenshot, the cursor left at the word's end`() {
        val host = registered()
        type("hello world")
        longPress(xIn(0, 5), lineY())
        val b = bar()!!

        val state = host.prepareForCleanCapture(Display.DEFAULT_DISPLAY)

        assertFalse(b.isShowing)
        assertNull(root.selectionForTest)
        assertEquals(5, field.selectionStart)
        assertEquals(5, field.selectionEnd)
        host.restoreAfterCapture(state)
        idle()
        assertFalse("not back after it", b.isShowing)
    }

    @Test fun `a clean capture takes the platform's own bar down too, its selection ending`() {
        FullScreenKeyboard.on = false
        val host = registered()
        type("hello world")
        longPress(xIn(0, 5), lineY())
        val b = platformBar as? OverlayTextToolbar ?: throw AssertionError("the window's bar: $platformBar")
        assertTrue(b.isShowing)

        host.restoreAfterCapture(host.prepareForCleanCapture(Display.DEFAULT_DISPLAY))
        idle()

        assertFalse(b.isShowing)
        assertFalse("the platform's selection ended with its bar", field.hasSelection())
    }

    // ── Where the platform selects ───────────────────────────────────────

    @Test fun `with the keyboard not full screen, the platform's own selection and items run at a long press and a double tap`() {
        FullScreenKeyboard.on = false
        type("hello world")

        longPress(xIn(0, 5), lineY())
        assertEquals("hello", selected())
        assertNotNull("the platform's bar, at a long press", platformBar)
        platformBar!!.finish()
        platformBar = null
        field.setSelection(0)
        idle()

        doubleTap(xIn(6, 11), lineY())
        assertEquals("world", selected())
        assertNotNull("the platform's bar, at a double tap", platformBar)
        platformBar!!.finish()
    }

    @Test fun `a root under a decor leaves a long press to the decor, selecting nothing itself`() {
        val activity = Robolectric.buildActivity(Host::class.java).setup().get()
        val inApp = OverlayWindowRoot(activity)
        val f = EditText(activity).apply { setTextClassifier(TextClassifier.NO_OP) }
        inApp.addView(f, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.CENTER))
        activity.setContentView(inApp)
        f.setText("hello world")
        f.requestFocus()
        idle()

        inApp.showContextMenuForChild(f, 1f, 1f)
        idle()

        assertNull("no selection of the root's", inApp.selectionForTest)
        assertFalse(f.hasSelection())
    }

    // ── The word picked ──────────────────────────────────────────────────

    @Test fun `the word picked is the one the offset is in, else the one it ends, else the character there`() {
        val en = Locale.ENGLISH
        assertEquals("in a word", 6 to 11, OverlayTextSelection.wordAt("hello world", 8, en))
        assertEquals("at a word's start", 6 to 11, OverlayTextSelection.wordAt("hello world", 6, en))
        assertEquals("at a word's end", 0 to 5, OverlayTextSelection.wordAt("hello world", 5, en))
        assertEquals("at the text's end", 6 to 11, OverlayTextSelection.wordAt("hello world", 11, en))
        assertEquals("in no word", 2 to 3, OverlayTextSelection.wordAt("a  b", 2, en))
    }

    @Test fun `a Japanese word is the dictionary's`() {
        // 日本語を勉強します: 勉強 is one word.
        assertEquals(4 to 6, OverlayTextSelection.wordAt("日本語を勉強します", 5, Locale.JAPANESE))
    }

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    }
}
