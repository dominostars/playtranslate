package com.playtranslate

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckerTest {

    // ── Basic numeric ordering ────────────────────────────────────────

    @Test fun `equal versions are not newer`() {
        assertFalse(UpdateChecker.isNewer("1.1.1", "1.1.1"))
    }

    @Test fun `patch bump is newer`() {
        assertTrue(UpdateChecker.isNewer("1.1.2", "1.1.1"))
    }

    @Test fun `minor bump is newer`() {
        assertTrue(UpdateChecker.isNewer("1.2.0", "1.1.9"))
    }

    @Test fun `major bump is newer`() {
        assertTrue(UpdateChecker.isNewer("2.0.0", "1.9.9"))
    }

    @Test fun `older version is not newer`() {
        assertFalse(UpdateChecker.isNewer("1.0.0", "1.1.0"))
    }

    @Test fun `older patch is not newer`() {
        assertFalse(UpdateChecker.isNewer("1.1.0", "1.1.1"))
    }

    // ── Leading v / V ─────────────────────────────────────────────────

    @Test fun `leading lowercase v is stripped`() {
        assertTrue(UpdateChecker.isNewer("v1.2.0", "1.1.0"))
        assertFalse(UpdateChecker.isNewer("v1.1.0", "1.1.0"))
    }

    @Test fun `leading uppercase V is stripped`() {
        assertTrue(UpdateChecker.isNewer("V1.2.0", "1.1.0"))
    }

    @Test fun `v on both sides compares as equal`() {
        assertFalse(UpdateChecker.isNewer("v1.1.1", "v1.1.1"))
    }

    // ── Missing segments ──────────────────────────────────────────────

    @Test fun `shorter version with equal prefix is not newer`() {
        assertFalse(UpdateChecker.isNewer("1.2", "1.2.0"))
        assertFalse(UpdateChecker.isNewer("1.2.0", "1.2"))
    }

    @Test fun `shorter newer major still wins`() {
        assertTrue(UpdateChecker.isNewer("2", "1.9.9"))
    }

    // ── SemVer prerelease stripping ───────────────────────────────────

    @Test fun `prerelease suffix is stripped before compare`() {
        // 1.2.0-beta1 → [1,2,0]; 1.1.0 → [1,1,0]
        assertTrue(UpdateChecker.isNewer("1.2.0-beta1", "1.1.0"))
    }

    @Test fun `prerelease with dot is stripped cleanly`() {
        // Previously 1.2.0-rc.1 parsed to [1,2,1] because "1" leaked through;
        // now stripping at '-' gives [1,2,0]
        assertTrue(UpdateChecker.isNewer("1.2.0-rc.1", "1.1.0"))
    }

    @Test fun `prerelease of same version compares as equal`() {
        // Not strict SemVer (which would say 1.2.0 > 1.2.0-rc1) but
        // adequate for a nudge feature.
        assertFalse(UpdateChecker.isNewer("1.2.0", "1.2.0-rc1"))
        assertFalse(UpdateChecker.isNewer("1.2.0-rc1", "1.2.0"))
    }

    @Test fun `prerelease of older version is not newer than release`() {
        // Regression guard for the original parser: "1.2.0-rc.1" used to
        // parse as [1,2,1], which would wrongly beat [1,2,0].
        assertFalse(UpdateChecker.isNewer("1.2.0-rc.1", "1.2.0"))
    }

    // ── SemVer build metadata stripping ───────────────────────────────

    @Test fun `build metadata suffix is stripped`() {
        // 1.2.0+5 → [1,2,0]
        assertTrue(UpdateChecker.isNewer("1.2.0+5", "1.1.0"))
        assertFalse(UpdateChecker.isNewer("1.2.0+5", "1.2.0"))
    }

    @Test fun `combined prerelease and build metadata are stripped`() {
        // 1.2.0-rc1+5 → strip at '-' first, yielding "1.2.0" → [1,2,0]
        assertFalse(UpdateChecker.isNewer("1.2.0-rc1+5", "1.2.0"))
        assertTrue(UpdateChecker.isNewer("1.3.0-rc1+5", "1.2.0"))
    }

    // ── Whitespace and edge cases ─────────────────────────────────────

    @Test fun `surrounding whitespace is trimmed`() {
        assertTrue(UpdateChecker.isNewer("  1.2.0  ", "1.1.0"))
    }

    @Test fun `empty string compares as zero`() {
        assertFalse(UpdateChecker.isNewer("", ""))
        assertTrue(UpdateChecker.isNewer("1.0.0", ""))
        assertFalse(UpdateChecker.isNewer("", "1.0.0"))
    }

    @Test fun `garbage tag compares as zero`() {
        // "abc" has no numeric segments → parses as empty → treated as 0
        assertFalse(UpdateChecker.isNewer("abc", "0.0.0"))
        assertTrue(UpdateChecker.isNewer("1.0.0", "abc"))
    }

    // ── Real-world tag scenarios (regression guards) ──────────────────

    @Test fun `github style v prefix vs build gradle style no prefix`() {
        assertFalse(UpdateChecker.isNewer("v1.1.1", "1.1.1"))
        assertTrue(UpdateChecker.isNewer("v1.1.2", "1.1.1"))
    }
}
