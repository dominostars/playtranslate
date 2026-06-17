package com.playtranslate.yomitan

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [YomitanUpdater.shouldUpdate] — Yomitan's revision-INEQUALITY
 * update rule (remote present AND != installed ⇒ update; not ordered). Pure JVM.
 */
class YomitanUpdaterTest {

    @Test fun `equal revisions are not an update`() {
        assertFalse(YomitanUpdater.shouldUpdate("2024-01-01", "2024-01-01"))
    }

    @Test fun `a different remote revision is an update`() {
        assertTrue(YomitanUpdater.shouldUpdate("2024-01-01", "2024-02-01"))
        // Not ordered — an "older"-looking remote string still counts.
        assertTrue(YomitanUpdater.shouldUpdate("2024-02-01", "2024-01-01"))
    }

    @Test fun `installed-null with a real remote revision is an update`() {
        assertTrue(YomitanUpdater.shouldUpdate(null, "r1"))
    }

    @Test fun `a blank or absent remote revision is never an update`() {
        assertFalse(YomitanUpdater.shouldUpdate("r1", null))
        assertFalse(YomitanUpdater.shouldUpdate("r1", ""))
        assertFalse(YomitanUpdater.shouldUpdate("r1", "   "))
        assertFalse(YomitanUpdater.shouldUpdate(null, null))
    }

    @Test fun `revisions are compared trimmed`() {
        assertFalse(YomitanUpdater.shouldUpdate("r1", "  r1  "))
        assertTrue(YomitanUpdater.shouldUpdate(" r1 ", "r2"))
    }
}
