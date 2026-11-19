package com.playtranslate.ui

import android.view.WindowManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [turnOffSystemMoveAnimation]: the platform's switch where it has one (API
 * 34 on), the private flag behind that switch below it, and on a device that
 * does not have the hidden field or refuses it, nothing set and nothing
 * thrown.
 */
@RunWith(RobolectricTestRunner::class)
class SystemMoveAnimationTest {

    private fun privateFlagsOf(p: WindowManager.LayoutParams): Int =
        WindowManager.LayoutParams::class.java.getField("privateFlags").getInt(p)

    @Test fun `from API 34 the platform's own switch is used`() {
        val p = WindowManager.LayoutParams()
        assertTrue(p.canPlayMoveAnimation())

        assertTrue(p.turnOffSystemMoveAnimation())

        assertFalse(p.canPlayMoveAnimation())
    }

    @Config(sdk = [31])
    @Test fun `below API 34 the private flag is set, the flags already there kept`() {
        val p = WindowManager.LayoutParams()
        val field = WindowManager.LayoutParams::class.java.getField("privateFlags")
        field.setInt(p, 0x4)

        assertTrue(p.turnOffSystemMoveAnimation())

        assertEquals(0x4 or PRIVATE_FLAG_NO_MOVE_ANIMATION, privateFlagsOf(p))
        assertEquals("the bit the platform documents", 0x40, PRIVATE_FLAG_NO_MOVE_ANIMATION)
    }

    @Test fun `a device without the field sets nothing and throws nothing`() {
        val p = WindowManager.LayoutParams()
        val before = privateFlagsOf(p)

        val done = p.turnOffSystemMoveAnimation(sdk = 33) { throw NoSuchFieldException("privateFlags") }

        assertFalse(done)
        assertEquals(before, privateFlagsOf(p))
    }

    @Test fun `a device that refuses the field, by an exception or by an error, sets nothing and throws nothing`() {
        val p = WindowManager.LayoutParams()
        val before = privateFlagsOf(p)

        assertFalse(p.turnOffSystemMoveAnimation(sdk = 29) { throw SecurityException("blocked") })
        assertFalse(p.turnOffSystemMoveAnimation(sdk = 29) { throw NoSuchFieldError("privateFlags") })
        // A field that is there but will not take the value.
        val wrongField = String::class.java.getDeclaredField("hash")
        assertFalse(p.turnOffSystemMoveAnimation(sdk = 29) { wrongField })

        assertEquals(before, privateFlagsOf(p))
    }
}
