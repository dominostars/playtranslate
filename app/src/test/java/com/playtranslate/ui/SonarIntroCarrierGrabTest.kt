package com.playtranslate.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [sonarIntroCarrierGrab] — the pure hit math that keeps the
 * sonar intro's grab target on the animating carrier instead of the whole
 * ring field. No Android types involved, so it runs as a plain JVM test.
 *
 * Conventions (matching [SonarPingIntroView]'s real constants at density 1):
 * carrier radius 28, docked radius 28, grab pad 12, and the SHIPPED
 * [SonarPingIntroView.MIN_GRAB_OPACITY] (0.25) rather than a copy of it, so
 * moving that bar in either direction fails here;
 * carrier at the entry pose — 108 from the dock edge (28 anchor + 80 entry
 * offset) — at the 1.286 entry-settle scale and fully faded in, so a drawn
 * radius of 36 and a grab radius of 48. The rings around it reach
 * 3.7 x 28 = 103.6.
 *
 * Fade-in poses use the spring's real pairing of opacity and scale over the
 * 240 ms ramp: scale = 0.40 + t/240, opacity = t/240. So 0.25 opacity is
 * 60 ms in, at 0.65x.
 */
class SonarIntroCarrierGrabTest {

    private val docked = 28f
    private val pad = 12f
    private val cx = 108f
    private val cy = 140f

    private fun grab(
        touchX: Float,
        touchY: Float,
        scale: Float = 1.286f,
        opacity: Float = 1f,
    ) = sonarIntroCarrierGrab(
        touchX = touchX,
        touchY = touchY,
        carrierCx = cx,
        carrierCy = cy,
        carrierRadiusPx = docked * scale,
        carrierOpacity = opacity,
        dockedRadiusPx = docked,
        padPx = pad,
        minOpacityToGrab = SonarPingIntroView.MIN_GRAB_OPACITY,
    )

    @Test
    fun pressOnTheCarrierGrabs() {
        assertTrue(grab(cx, cy))
        // Just inside the drawn carrier's edge.
        assertTrue(grab(cx + 35f, cy))
        assertTrue(grab(cx, cy - 35f))
    }

    @Test
    fun grabPaddingExtendsPastTheDrawnCircle() {
        // Drawn radius 36; the pad carries the target to 48.
        assertTrue(grab(cx + 47f, cy))
        assertFalse(grab(cx + 49f, cy))
    }

    @Test
    fun pressInTheRingFieldMisses() {
        // Rings sweep out to 103.6 from the same centre — none of that is a
        // target. Sampled along both axes and on the diagonal.
        assertFalse(grab(cx + 70f, cy))
        assertFalse(grab(cx - 100f, cy))
        assertFalse(grab(cx, cy + 90f))
        assertFalse(grab(cx + 60f, cy + 60f))
    }

    @Test
    fun pressAtTheDockMissesWhileTheCarrierHoversInboard() {
        // The dock edge is 108 away from the entry pose; the icon is at
        // alpha 0 there for the whole hover phase, so it isn't a target.
        assertFalse(grab(0f, cy))
    }

    @Test
    fun springingInCarrierKeepsTheDockedIconsTarget() {
        // The gate opens 60 ms in, where the carrier is still drawn at 0.65x
        // (radius 18.2). The floor holds the target at the docked 28 + 12
        // so it never becomes a pinprick on the way up to 1.0x at 144 ms.
        assertTrue(grab(cx + 39f, cy, scale = 0.65f, opacity = 0.25f))
        assertFalse(grab(cx + 41f, cy, scale = 0.65f, opacity = 0.25f))
    }

    @Test
    fun carrierStillFadingUpIsNotATargetAtAll() {
        // t=0: the grab circle would otherwise sit at full size over a
        // carrier that hasn't been painted yet. Dead centre, so this can
        // only be the opacity gate refusing.
        assertFalse(grab(cx, cy, scale = 0.4f, opacity = 0f))
        // Just under the gate — 0.2 is ~48 ms in, still nothing to aim at.
        assertFalse(grab(cx, cy, scale = 0.6f, opacity = 0.2f))
    }

    @Test
    fun gateOpensAsSoonAsTheCarrierIsPainted() {
        // Inclusive at the threshold, and open for the whole rest of the
        // fade — a carrier the user can see never refuses a press.
        assertTrue(grab(cx, cy, scale = 0.65f, opacity = 0.25f))
        assertTrue(grab(cx, cy, scale = 0.9f, opacity = 0.5f))
    }

    @Test
    fun undulationPeakGrowsTheTargetWithTheCarrier() {
        // 1.40x -> drawn radius 39.2, target 51.2.
        assertTrue(grab(cx + 51f, cy, scale = 1.40f))
        assertFalse(grab(cx + 52f, cy, scale = 1.40f))
    }
}
