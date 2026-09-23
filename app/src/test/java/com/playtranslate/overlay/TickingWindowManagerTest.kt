package com.playtranslate.overlay

import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Proxy

/**
 * Every mutating verb of the app's only WindowManager records itself on
 * [OwnWindowClock] AFTER the platform call returns; reads do not; a verb
 * that throws records nothing (no window changed). The delegate is a
 * recording proxy over the platform interface, so this pins the wrapper's
 * contract without a device. The two-frame re-tick needs a Choreographer
 * and is not observable here; the immediate tick is.
 */
class TickingWindowManagerTest {

    private var t = 1_000L
    private val calls = mutableListOf<String>()
    private var failAdd = false
    private var failAll = false

    private val delegate: WindowManager = Proxy.newProxyInstance(
        WindowManager::class.java.classLoader,
        arrayOf(WindowManager::class.java),
    ) { _, method, _ ->
        calls += method.name
        if (failAll || (method.name == "addView" && failAdd)) throw IllegalStateException("${method.name} refused")
        null
    } as WindowManager

    private val wm = TickingWindowManager(delegate)
    private val view = View(null)
    private val params = ViewGroup.LayoutParams(1, 1)

    @Before
    fun setUp() {
        OwnWindowClock.reset()
        OwnWindowClock.now = { t }
    }

    @After
    fun tearDown() {
        OwnWindowClock.reset()
        OwnWindowClock.now = { android.os.SystemClock.uptimeMillis() }
    }

    @Test
    fun `add ticks after the platform add succeeds`() {
        wm.addView(view, params)
        assertEquals(listOf("addView"), calls)
        assertEquals(0L, OwnWindowClock.sinceLastEventMs())
    }

    @Test
    fun `a failed add records nothing`() {
        failAdd = true
        runCatching { wm.addView(view, params) }
        assertNull(OwnWindowClock.sinceLastEventMs())
    }

    @Test
    fun `a verb that throws records nothing, whichever verb`() {
        failAll = true
        runCatching { wm.updateViewLayout(view, params) }
        runCatching { wm.removeView(view) }
        runCatching { wm.removeViewImmediate(view) }
        assertEquals(listOf("updateViewLayout", "removeView", "removeViewImmediate"), calls)
        assertNull(OwnWindowClock.sinceLastEventMs())
    }

    @Test
    fun `the record follows the platform call`() {
        // The delegate advances the clock while it runs; the tick must be
        // stamped at return, i.e. at the later time.
        val slow: WindowManager = Proxy.newProxyInstance(
            WindowManager::class.java.classLoader,
            arrayOf(WindowManager::class.java),
        ) { _, _, _ -> t += 400; null } as WindowManager
        TickingWindowManager(slow).removeViewImmediate(view)
        assertEquals(0L, OwnWindowClock.sinceLastEventMs())
    }

    @Test
    fun `update ticks`() {
        wm.updateViewLayout(view, params)
        assertEquals(listOf("updateViewLayout"), calls)
        assertEquals(0L, OwnWindowClock.sinceLastEventMs())
    }

    @Test
    fun `remove ticks`() {
        wm.removeView(view)
        assertEquals(listOf("removeView"), calls)
        assertEquals(0L, OwnWindowClock.sinceLastEventMs())
    }

    @Test
    fun `immediate remove ticks`() {
        wm.removeViewImmediate(view)
        assertEquals(listOf("removeViewImmediate"), calls)
        assertEquals(0L, OwnWindowClock.sinceLastEventMs())
    }

    /** The regression guard for 2026-09-23: `by delegate` forwards only the
     *  interface's abstract members, and WindowManager's Java defaults for
     *  the metrics getters THROW. Every method the platform interface
     *  declares must be declared by the wrapper, so a compileSdk bump that
     *  adds a default method fails here instead of on a device. */
    @Test
    fun `the wrapper declares every method of the platform interface`() {
        val missing = WindowManager::class.java.methods
            .filter { it.declaringClass.isInterface }
            .filter { m ->
                runCatching { TickingWindowManager::class.java.getDeclaredMethod(m.name, *m.parameterTypes) }.isFailure
            }
            .map { "${it.name}(${it.parameterTypes.joinToString { p -> p.simpleName }})" }
            .sorted()
        assertEquals("forward these explicitly in TickingWindowManager:\n" + missing.joinToString("\n"), emptyList<String>(), missing)
    }

    @Test
    fun `reads are forwarded, not answered by the interface default`() {
        // The proxy records the call; the interface default would have thrown
        // without ever reaching it.
        runCatching { wm.currentWindowMetrics }
        runCatching { wm.maximumWindowMetrics }
        assertEquals(listOf("getCurrentWindowMetrics", "getMaximumWindowMetrics"), calls)
        assertNull(OwnWindowClock.sinceLastEventMs())
    }

    @Test
    fun `each verb is a fresh event`() {
        wm.addView(view, params)
        t += 300
        wm.removeView(view)
        assertEquals(0L, OwnWindowClock.sinceLastEventMs())
        t += 40
        assertEquals(40L, OwnWindowClock.sinceLastEventMs())
    }
}
