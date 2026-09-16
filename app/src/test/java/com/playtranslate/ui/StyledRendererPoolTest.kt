package com.playtranslate.ui

import android.app.ActivityManager
import android.content.Context
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * [StyledRendererPool]'s contract: the cap bounds minting, a returned
 * renderer is reused, a death retires the pool, teardown is terminal, and
 * a returned renderer carries no cell hooks. Robolectric constructs the
 * WebView, so [YomitanDefinitionsView.isUsable] and [YomitanDefinitionsView.destroy]
 * are the real ones; nothing here asserts rendering.
 */
@RunWith(RobolectricTestRunner::class)
class StyledRendererPoolTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()

    private val tokens = DefinitionsDocument.Tokens(
        text = 0, textMuted = 0, textHint = 0, accent = 0, panel = 0, baseFontSizePx = 13f,
    )

    private fun view() = YomitanDefinitionsView(ctx, tokens)

    @Test
    fun `the cap bounds minting and a returned renderer is handed out again`() {
        val pool = StyledRendererPool(cap = 2)
        val a = pool.acquire(::view)!!
        val b = pool.acquire(::view)!!
        assertNull(pool.acquire(::view))
        assertEquals(2, pool.liveCount)
        pool.release(a)
        assertSame(a, pool.acquire(::view))
        assertEquals(2, pool.liveCount)
        assertTrue(b.isUsable())
    }

    @Test
    fun `a renderer that died while lent retires the pool`() {
        val pool = StyledRendererPool(cap = 2)
        val a = pool.acquire(::view)!!
        val b = pool.acquire(::view)!!
        pool.release(a)
        // Renderer death: the view destroys itself before the host hears.
        b.destroy()
        pool.release(b)
        assertFalse(a.isUsable())
        assertEquals(0, pool.liveCount)
        assertNull(pool.acquire(::view))
    }

    @Test
    fun `a renderer that died while idle retires the pool on the next ask`() {
        val pool = StyledRendererPool(cap = 2)
        val a = pool.acquire(::view)!!
        pool.release(a)
        a.destroy()
        assertNull(pool.acquire(::view))
        assertEquals(0, pool.liveCount)
        assertNull(pool.acquire(::view))
    }

    @Test
    fun `destroyAll destroys the idle renderers and a late return`() {
        val pool = StyledRendererPool(cap = 2)
        val a = pool.acquire(::view)!!
        val b = pool.acquire(::view)!!
        pool.release(a)
        pool.destroyAll()
        assertFalse(a.isUsable())
        assertEquals(1, pool.liveCount)
        pool.release(b)
        assertFalse(b.isUsable())
        assertEquals(0, pool.liveCount)
        assertNull(pool.acquire(::view))
        pool.destroyAll()
    }

    @Test
    fun `a returned renderer is detached and carries no cell hooks`() {
        val pool = StyledRendererPool(cap = 1)
        val a = pool.acquire(::view)!!
        a.onContentHeight = { }
        a.onRendererGone = { }
        a.onBodyTap = { }
        FrameLayout(ctx).addView(a)
        pool.release(a)
        assertNull(a.parent)
        assertNull(a.onContentHeight)
        assertNull(a.onRendererGone)
        assertNull(a.onBodyTap)
    }

    @Test
    fun `the row cap is zero on a low-RAM device and the constant elsewhere`() {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        shadowOf(am).setIsLowRamDevice(false)
        assertEquals(STYLED_WORD_ROW_CAP, styledWordRowCap(ctx))
        shadowOf(am).setIsLowRamDevice(true)
        assertEquals(0, styledWordRowCap(ctx))
    }
}
