package com.playtranslate.ui

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Rect
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
import java.time.Duration

/**
 * Pins [OverlayWindowRoot] and [OverlayTextToolbar], the Cut / Copy / Paste
 * bar of a text field in an overlay window, where Android draws none.
 *
 * The root: a text view's ask for a floating bar, in a window whose root it
 * is, gets the app's bar; a view that is not a text view, and an ask for a
 * primary mode, get nothing, as they did; a callback that declines at the
 * start gets no bar; and a root under a decor (a sheet hosted in an
 * activity) leaves the bar to the decor. The workspace's window is such a
 * root, and hosted in an activity it is not.
 *
 * The bar, driven by a callback the test writes: filled by the callback at
 * its start and prepared once, as the decor starts its own; its items as
 * buttons in the menu's order, a tap on one handed to the callback; above
 * the selection, centred on it, or under it where there is no room above,
 * and inside the window, off its sides by the platform bar's margin;
 * following the field as it moves, and off while none of the selection
 * shows (the field out of the window, or the selection out of the field's
 * visible part) or the field itself does not; off for the while a hide asks
 * and back at once at a hide of 0; the row, and the bar's size, following
 * the callback's prepare at each invalidate; inside the window with room
 * neither above nor under the selection; and finish taking it off and
 * telling the callback, for good even when it lands in a frame's pre-draw
 * that goes on to call the bar's own listener, and only once however often
 * it is asked; and a clean capture of the window's display taking it down
 * before the screenshot, the callback told once (one of another display
 * leaving it).
 *
 * And with the platform's own EditText behind it, whose callback is the
 * platform's: a long press on a word selects it and shows the platform's
 * items, and Copy, Cut and Paste do what they say and end the bar; a long
 * press in an empty field offers Paste, which pastes.
 */
@RunWith(RobolectricTestRunner::class)
class OverlayTextToolbarTest {

    class Host : Activity() {
        override fun onCreate(savedInstanceState: Bundle?) {
            setTheme(R.style.Theme_PlayTranslate)
            super.onCreate(savedInstanceState)
        }
    }

    /** A field's callback as the test writes it: the items it puts in the
     *  menu, whether it takes the bar at all, the selection it reports (in
     *  the field's coordinates), and everything the bar asked of it. */
    private class Recording(
        private val items: List<Pair<Int, String>> = listOf(android.R.id.cut to "Cut", android.R.id.copy to "Copy"),
        private val accept: Boolean = true,
    ) : ActionMode.Callback2() {
        var selection = Rect(10, 10, 60, 40)
        var created = 0
        var prepared = 0
        var destroyed = 0
        val clicked = mutableListOf<Int>()

        /** Run at each prepare, before it returns: a test's change of the
         *  menu, as the platform's prepare adds and removes items. */
        var onPrepare: (Menu) -> Unit = {}

        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
            created++
            items.forEachIndexed { i, (id, title) -> menu.add(Menu.NONE, id, i, title) }
            return accept
        }

        override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
            prepared++
            onPrepare(menu)
            return true
        }

        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
            clicked += item.itemId
            return true
        }

        override fun onDestroyActionMode(mode: ActionMode) {
            destroyed++
        }

        override fun onGetContentRect(mode: ActionMode, view: View, outRect: Rect) {
            outRect.set(selection)
        }
    }

    private val app: Context = ApplicationProvider.getApplicationContext()

    /** Themed as the overlays' views are. */
    private val ctx = overlayThemedContext(app)
    private val wm = app.getSystemService(WindowManager::class.java)
    private val clipboard = app.getSystemService(ClipboardManager::class.java)
    private lateinit var root: OverlayWindowRoot
    private lateinit var field: EditText

    @Before fun setUp() {
        root = OverlayWindowRoot(ctx)
        field = EditText(ctx).apply {
            // No smart selection: the platform then starts its bar at once,
            // not after a classifier's answer.
            setTextClassifier(TextClassifier.NO_OP)
        }
        // Across the window's middle, so there is room above the field.
        root.addView(field, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.CENTER))
        wm.addView(root, overlayParams())
        idle()
    }

    @After fun tearDown() {
        if (root.isAttachedToWindow) wm.removeViewImmediate(root)
        idle()
    }

    private fun overlayParams() = WindowManager.LayoutParams(
        MATCH, MATCH, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, 0, PixelFormat.TRANSLUCENT,
    )

    /** Run what is due, frames included. */
    private fun idle() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))

    private fun ActionMode?.bar(): OverlayTextToolbar =
        this as? OverlayTextToolbar ?: throw AssertionError("not the app's bar: $this")

    private fun OverlayTextToolbar.buttons(): List<TextView> =
        (0 until rowForTest.childCount).map { rowForTest.getChildAt(it) as TextView }

    private fun OverlayTextToolbar.labels(): List<String> = buttons().map { it.text.toString() }

    private fun OverlayTextToolbar.button(label: String): TextView = buttons().single { it.text == label }

    /** The bar's window in the field's window, as its window's params place it. */
    private fun OverlayTextToolbar.frame(): Rect {
        val p = rowForTest.rootView.layoutParams as WindowManager.LayoutParams
        return Rect(p.x, p.y, p.x + p.width, p.y + p.height)
    }

    /** [r], in the field's coordinates, in its window's. */
    private fun inWindow(r: Rect): Rect {
        val at = IntArray(2)
        field.getLocationInWindow(at)
        return Rect(r).apply { offset(at[0], at[1]) }
    }

    /** A selection 50 px wide across the field's middle. */
    private fun middleOfField() = Rect(field.width / 2 - 25, 10, field.width / 2 + 25, 40)

    // ── The root ─────────────────────────────────────────────────────────

    @Test fun `a text field asking for a floating bar gets the app's, filled by its callback, prepared once and shown above the selection, centred`() {
        val cb = Recording().apply { selection = middleOfField() }
        val bar = field.startActionMode(cb, ActionMode.TYPE_FLOATING).bar()
        idle()

        assertEquals(ActionMode.TYPE_FLOATING, bar.type)
        assertEquals("created once", 1, cb.created)
        assertEquals("prepared once, at its start", 1, cb.prepared)
        assertTrue(bar.isShowing)
        assertEquals(listOf("Cut", "Copy"), bar.labels())
        val selection = inWindow(cb.selection)
        val frame = bar.frame()
        assertTrue("above the selection: $frame over $selection", frame.bottom <= selection.top)
        assertEquals("centred on it", selection.exactCenterX(), frame.exactCenterX(), 1f)
        bar.finish()
    }

    @Test fun `a callback that declines at its start gets no bar`() {
        val cb = Recording(accept = false)
        assertNull(field.startActionMode(cb, ActionMode.TYPE_FLOATING))
        assertEquals(1, cb.created)
        assertEquals("nothing to prepare", 0, cb.prepared)
    }

    @Test fun `a view that is not a text view, and an ask for a primary mode, get nothing, as before`() {
        val plain = View(ctx)
        root.addView(plain, FrameLayout.LayoutParams(100, 100))
        idle()

        val cb = Recording()
        assertNull("a view that is not a text view", plain.startActionMode(cb, ActionMode.TYPE_FLOATING))
        assertNull("a primary mode", field.startActionMode(cb, ActionMode.TYPE_PRIMARY))
        assertEquals("no bar was started for either", 0, cb.created)
    }

    @Test fun `a root under a decor, as a sheet hosted in an activity, leaves the bar to the decor`() {
        val activity = Robolectric.buildActivity(Host::class.java).setup().get()
        val inApp = OverlayWindowRoot(activity)
        val f = EditText(activity)
        inApp.addView(f, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.CENTER))
        activity.setContentView(inApp)
        idle()

        val cb = Recording()
        val mode = f.startActionMode(cb, ActionMode.TYPE_FLOATING)
        assertNotNull("the decor's bar", mode)
        assertFalse("not the app's: $mode", mode is OverlayTextToolbar)
        assertEquals("started once, by the decor", 1, cb.created)
        mode!!.finish()
    }

    @Test fun `the workspace's window gives its fields the app's bar, and hosted in an activity leaves it to the decor`() {
        val overlayHost = OverlayHost(app, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        val activity = Robolectric.buildActivity(Host::class.java).setup().get()
        val container = FrameLayout(activity)
        activity.setContentView(container)
        val hosts = listOf(
            "over the game" to null,
            "in an activity" to ActivitySheetHost(container),
        )
        for ((where, sheetHost) in hosts) {
            val page = FieldPage()
            val workspace = if (sheetHost == null) {
                OverlayWorkspace(app, wm, Display.DEFAULT_DISPLAY, overlayHost)
            } else {
                OverlayWorkspace(activity, activity.windowManager, Display.DEFAULT_DISPLAY, overlayHost, sheetHost)
            }
            workspace.show(1000, 2000) { page }
            idle()

            val cb = Recording()
            val mode = page.field.startActionMode(cb, ActionMode.TYPE_FLOATING)
            assertNotNull(where, mode)
            assertEquals("the app's bar exactly over the game: $where", sheetHost == null, mode is OverlayTextToolbar)
            mode!!.finish()
            workspace.dismiss()
            idle()
        }
    }

    /** A workspace page that is one text field. */
    private class FieldPage : WorkspacePage {
        lateinit var field: EditText
        override fun title(ctx: Context): CharSequence = "page"
        override fun onCreateView(ctx: Context, parent: ViewGroup, host: WorkspaceHost): View =
            EditText(ctx).also { field = it }
    }

    // ── The bar ──────────────────────────────────────────────────────────

    @Test fun `a tap on an item hands that item to the callback`() {
        val cb = Recording()
        val bar = field.startActionMode(cb, ActionMode.TYPE_FLOATING).bar()
        bar.button("Copy").performClick()
        bar.button("Cut").performClick()
        assertEquals(listOf(android.R.id.copy, android.R.id.cut), cb.clicked)
        bar.finish()
    }

    @Test fun `finish takes the bar off and tells the callback`() {
        val cb = Recording()
        val bar = field.startActionMode(cb, ActionMode.TYPE_FLOATING).bar()
        assertTrue(bar.isShowing)
        bar.finish()
        idle()
        assertFalse(bar.isShowing)
        assertEquals(1, cb.destroyed)
        bar.finish()
        assertEquals("a second finish does nothing", 1, cb.destroyed)
    }

    @Test fun `a clean capture of the window's display takes its bar down first, the callback told once, and one of another display leaves it`() {
        val host = OverlayHost(app, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        val registered = OverlayWindowRoot(ctx)
        val f = EditText(ctx)
        registered.addView(f, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.CENTER))
        assertTrue(host.addOverlayWindow(registered, wm, overlayParams(), Display.DEFAULT_DISPLAY, OverlayPlacement.Content))
        idle()
        val cb = Recording()
        val bar = f.startActionMode(cb, ActionMode.TYPE_FLOATING).bar()
        idle()
        assertTrue(bar.isShowing)

        host.restoreAfterCapture(host.prepareForCleanCapture(Display.DEFAULT_DISPLAY + 1))
        idle()
        assertTrue("another display's capture leaves it", bar.isShowing)

        val state = host.prepareForCleanCapture(Display.DEFAULT_DISPLAY)
        assertFalse("down before the screenshot", bar.isShowing)
        assertEquals(1, cb.destroyed)
        host.restoreAfterCapture(state)
        idle()
        assertFalse("and not back after it", bar.isShowing)
        assertEquals(1, cb.destroyed)
        wm.removeViewImmediate(registered)
    }

    @Test fun `under the selection when there is no room above it`() {
        (field.layoutParams as FrameLayout.LayoutParams).gravity = Gravity.TOP
        field.requestLayout()
        idle()
        assertEquals("precondition: the field is at the window's top", 0, inWindow(Rect()).top)

        val cb = Recording().apply { selection = middleOfField() }
        val bar = field.startActionMode(cb, ActionMode.TYPE_FLOATING).bar()
        val selection = inWindow(cb.selection)
        val frame = bar.frame()
        assertTrue("under the selection: $frame under $selection", frame.top >= selection.bottom)
        bar.finish()
    }

    @Test fun `a selection at the window's side has the bar inside the window, off its side by the platform bar's margin`() {
        val margin = (16 * app.resources.displayMetrics.density).toInt()
        for ((side, selection) in listOf("left" to Rect(0, 10, 10, 40), "right" to Rect(field.width - 10, 10, field.width, 40))) {
            val cb = Recording().apply { this.selection = selection }
            val bar = field.startActionMode(cb, ActionMode.TYPE_FLOATING).bar()
            val frame = bar.frame()
            if (side == "left") assertEquals("off the left side", margin, frame.left)
            else assertEquals("off the right side", root.width - margin, frame.right)
            bar.finish()
        }
    }

    @Test fun `the bar follows the field as it moves, and is off while none of the selection shows`() {
        val cb = Recording().apply { selection = middleOfField() }
        val bar = field.startActionMode(cb, ActionMode.TYPE_FLOATING).bar()
        idle()
        val before = bar.frame()

        field.translationY = -100f
        idle()
        assertTrue(bar.isShowing)
        assertEquals("moved with the field", before.top - 100, bar.frame().top)

        // Wholly below the window's bottom edge.
        field.translationY = root.height.toFloat()
        idle()
        assertFalse("off with none of the selection in sight", bar.isShowing)

        field.translationY = 0f
        idle()
        assertTrue("back with it", bar.isShowing)
        assertEquals(before, bar.frame())
        bar.finish()
    }

    @Test fun `a selection out of the field's visible part has no bar, while the field still shows, until it is back in sight`() {
        val cb = Recording().apply { selection = middleOfField() }
        val bar = field.startActionMode(cb, ActionMode.TYPE_FLOATING).bar()
        assertTrue(bar.isShowing)

        // Scrolled within the field, past its bottom edge.
        cb.selection = Rect(field.width / 2 - 25, field.height + 100, field.width / 2 + 25, field.height + 130)
        bar.invalidateContentRect()
        assertTrue("precondition: the field shows", field.isShown)
        assertFalse(bar.isShowing)

        cb.selection = middleOfField()
        bar.invalidateContentRect()
        assertTrue(bar.isShowing)
        bar.finish()
    }

    @Test fun `a hide takes the bar off for the while asked, and a hide of 0 brings it back at once`() {
        val bar = field.startActionMode(Recording(), ActionMode.TYPE_FLOATING).bar()

        bar.hide(500)
        assertFalse(bar.isShowing)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400))
        assertFalse("still off before the while ends", bar.isShowing)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200))
        assertTrue("back once it ends", bar.isShowing)

        bar.hide(ActionMode.DEFAULT_HIDE_DURATION.toLong())
        assertFalse(bar.isShowing)
        bar.hide(0)
        assertTrue("back at once", bar.isShowing)
        bar.finish()
    }

    @Test fun `at each invalidate the callback prepares the menu again, and the row and the bar's size follow it`() {
        val cb = Recording(items = listOf(android.R.id.copy to "Copy", android.R.id.selectAll to "Select all"))
            .apply { selection = middleOfField() }
        val bar = field.startActionMode(cb, ActionMode.TYPE_FLOATING).bar()
        assertEquals(listOf("Copy", "Select all"), bar.labels())
        val wide = bar.frame().width()

        cb.onPrepare = { it.removeItem(android.R.id.selectAll) }
        bar.invalidate()
        assertEquals(2, cb.prepared)
        assertEquals(listOf("Copy"), bar.labels())
        assertTrue("measured afresh: ${bar.frame().width()} after $wide", bar.frame().width() < wide)
        bar.finish()
    }

    @Test fun `a field that stops showing takes the bar off, and showing again brings it back`() {
        val bar = field.startActionMode(Recording(), ActionMode.TYPE_FLOATING).bar()
        assertTrue(bar.isShowing)

        field.visibility = View.INVISIBLE
        idle()
        assertFalse(bar.isShowing)

        field.visibility = View.VISIBLE
        idle()
        assertTrue(bar.isShowing)
        bar.finish()
    }

    @Test fun `a finish during a frame's pre-draw keeps the bar off, though the frame still calls the bar's own listener`() {
        // Registered before the bar's, so it runs first in the frame; the
        // frame calls the listeners it started with, the bar's included.
        var bar: OverlayTextToolbar? = null
        var finishNext = false
        root.viewTreeObserver.addOnPreDrawListener {
            if (finishNext) {
                finishNext = false
                bar?.finish()
            }
            true
        }
        bar = field.startActionMode(Recording(), ActionMode.TYPE_FLOATING).bar()
        assertTrue(bar.isShowing)

        finishNext = true
        // A frame: a layout pass always schedules one (an invalidate of a
        // view the test's window never drew does not).
        field.requestLayout()
        idle()
        assertFalse("precondition: the frame ran the finish", finishNext)
        assertFalse("off for good", bar.isShowing)
    }

    @Test fun `with room neither above nor under the selection, the bar stays inside the window`() {
        field.layoutParams = FrameLayout.LayoutParams(MATCH, MATCH)
        idle()
        val cb = Recording().apply { selection = Rect(field.width / 2 - 25, 0, field.width / 2 + 25, field.height) }
        val bar = field.startActionMode(cb, ActionMode.TYPE_FLOATING).bar()
        val frame = bar.frame()
        assertTrue("inside the window: $frame in ${root.width}x${root.height}", frame.top >= 0 && frame.bottom <= root.height)
        bar.finish()
    }

    // ── With the platform's own text field behind it ─────────────────────

    /** The bar the platform's field starts, caught through the field's own
     *  custom callback, which adds no item and leaves every tap to it. */
    private var caught: ActionMode? = null
    private val catcher = object : ActionMode.Callback {
        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
            caught = mode
            return true
        }
        override fun onPrepareActionMode(mode: ActionMode, menu: Menu) = false
        override fun onActionItemClicked(mode: ActionMode, item: MenuItem) = false
        override fun onDestroyActionMode(mode: ActionMode) {}
    }

    /** A finger held on the field at [x], [y] past the long-press timeout,
     *  then lifted, as a touch screen sends it. */
    private fun longPress(x: Float, y: Float) {
        val down = SystemClock.uptimeMillis()
        fun send(action: Int, at: Long) {
            val e = MotionEvent.obtain(down, at, action, x, y, 0)
            e.source = InputDevice.SOURCE_TOUCHSCREEN
            field.dispatchTouchEvent(e)
            e.recycle()
        }
        send(MotionEvent.ACTION_DOWN, down)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ViewConfiguration.getLongPressTimeout() + 100L))
        send(MotionEvent.ACTION_UP, SystemClock.uptimeMillis())
        idle()
    }

    /** A long press on the word at [start] until [end] of the field's
     *  text: at the first point of its line that the field itself places in
     *  the word (its hit test, which the platform's selection reads at the
     *  press). Found by the field rather than worked out from character
     *  widths: the test's text layout puts every character after the first
     *  at one x. */
    private fun longPressOn(start: Int, end: Int) {
        val layout = field.layout
        val y = (layout.getLineTop(0) + layout.getLineBottom(0)) / 2f + field.totalPaddingTop
        val x = (0 until field.width).map { it.toFloat() }
            .firstOrNull { field.getOffsetForPosition(it, y) in start..end }
            ?: throw AssertionError("no point of the field lands in $start..$end")
        longPress(x, y)
    }

    private fun selected() = field.text.substring(field.selectionStart, field.selectionEnd)

    private fun clip() = clipboard.primaryClip?.getItemAt(0)?.text?.toString()

    private val cut get() = app.getString(android.R.string.cut)
    private val copy get() = app.getString(android.R.string.copy)
    private val paste get() = app.getString(android.R.string.paste)

    @Test fun `a long press on a word selects it and shows the platform's items, and Copy copies it and ends the bar`() {
        field.setText("hello world")
        field.customSelectionActionModeCallback = catcher
        idle()

        longPressOn(0, 5)
        val bar = caught.bar()
        assertTrue(bar.isShowing)
        assertEquals("hello", selected())
        assertTrue("Cut and Copy: ${bar.labels()}", bar.labels().containsAll(listOf(cut, copy)))
        assertFalse("no Paste with nothing to paste: ${bar.labels()}", paste in bar.labels())

        bar.button(copy).performClick()
        idle()
        assertEquals("hello", clip())
        assertEquals("the text is kept", "hello world", field.text.toString())
        assertFalse("the field ends the bar once Copy is done", bar.isShowing)
    }

    @Test fun `Cut takes the selected word out of the field and onto the clipboard`() {
        field.setText("hello world")
        field.customSelectionActionModeCallback = catcher
        idle()

        longPressOn(0, 5)
        val bar = caught.bar()
        bar.button(cut).performClick()
        idle()
        assertEquals("hello", clip())
        assertEquals(" world", field.text.toString())
        assertFalse(bar.isShowing)
    }

    @Test fun `Paste over a selected word puts the clipboard's text in its place`() {
        clipboard.setPrimaryClip(ClipData.newPlainText("test", "猫"))
        field.setText("hello world")
        field.customSelectionActionModeCallback = catcher
        idle()

        longPressOn(6, 11)
        val bar = caught.bar()
        assertEquals("world", selected())
        bar.button(paste).performClick()
        idle()
        assertEquals("hello 猫", field.text.toString())
        assertFalse(bar.isShowing)
    }

    @Test fun `a long press in an empty field offers Paste, which pastes`() {
        clipboard.setPrimaryClip(ClipData.newPlainText("test", "この文の文法は？"))
        field.setText("")
        field.customInsertionActionModeCallback = catcher
        idle()

        longPress(field.width / 2f, field.height / 2f)
        val bar = caught.bar()
        assertTrue("Paste: ${bar.labels()}", paste in bar.labels())
        bar.button(paste).performClick()
        idle()
        assertEquals("この文の文法は？", field.text.toString())
        assertFalse(bar.isShowing)
    }

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    }
}
