package com.playtranslate.ui

import com.playtranslate.ui.KeepRunningItems.Id
import com.playtranslate.ui.KeepRunningItems.Rom
import org.junit.Assert.assertEquals
import org.junit.Test

/** Which cards the Keep-running page shows for which phone and state. */
class KeepRunningItemsTest {

    private fun ids(
        rom: Rom,
        batteryUnrestricted: Boolean = false,
        accessibilityOn: Boolean = false,
        tileAdded: Boolean = false,
        sdk: Int = 34,
    ) = KeepRunningItems.ids(rom, batteryUnrestricted, accessibilityOn, tileAdded, sdk)

    @Test fun `brands map to their ROM, case-insensitively`() {
        assertEquals(Rom.XIAOMI, KeepRunningItems.detectRom("Xiaomi", "Redmi"))
        assertEquals(Rom.XIAOMI, KeepRunningItems.detectRom("Xiaomi", "POCO"))
        assertEquals(Rom.HUAWEI, KeepRunningItems.detectRom("HUAWEI", "HONOR"))
        assertEquals(Rom.HUAWEI, KeepRunningItems.detectRom("HONOR", "HONOR"))
        assertEquals(Rom.OPPO, KeepRunningItems.detectRom("realme", "realme"))
        assertEquals(Rom.OPPO, KeepRunningItems.detectRom("OnePlus", "OnePlus"))
        assertEquals(Rom.VIVO, KeepRunningItems.detectRom("vivo", "iQOO"))
        assertEquals(Rom.SAMSUNG, KeepRunningItems.detectRom("samsung", "samsung"))
        assertEquals(Rom.OTHER, KeepRunningItems.detectRom("motorola", "motorola"))
        assertEquals(Rom.OTHER, KeepRunningItems.detectRom("SHENQIJIYUAN", "nubia"))
    }

    @Test fun `a stock phone gets the three generic cards`() {
        assertEquals(listOf(Id.BATTERY, Id.ACCESSIBILITY, Id.QUICK_TILE), ids(Rom.OTHER))
    }

    @Test fun `xiaomi gets its own three cards and its battery card replaces the generic one`() {
        assertEquals(
            listOf(Id.XIAOMI_AUTOSTART, Id.XIAOMI_BATTERY, Id.XIAOMI_LOCK_RECENTS, Id.ACCESSIBILITY, Id.QUICK_TILE),
            ids(Rom.XIAOMI),
        )
    }

    @Test fun `xiaomi's battery card outlives the exemption and the generic one never shows there`() {
        assertEquals(
            listOf(Id.XIAOMI_AUTOSTART, Id.XIAOMI_BATTERY, Id.XIAOMI_LOCK_RECENTS, Id.ACCESSIBILITY, Id.QUICK_TILE),
            ids(Rom.XIAOMI, batteryUnrestricted = true),
        )
    }

    @Test fun `the other ROMs get their one card before the generic ones`() {
        assertEquals(
            listOf(Id.HUAWEI_APP_LAUNCH, Id.BATTERY, Id.ACCESSIBILITY, Id.QUICK_TILE),
            ids(Rom.HUAWEI),
        )
        assertEquals(Id.OPPO_AUTO_LAUNCH, ids(Rom.OPPO).first())
        assertEquals(Id.VIVO_AUTOSTART, ids(Rom.VIVO).first())
        assertEquals(Id.SAMSUNG_NEVER_SLEEPING, ids(Rom.SAMSUNG).first())
    }

    @Test fun `settled cards drop out`() {
        assertEquals(
            emptyList<Id>(),
            ids(Rom.OTHER, batteryUnrestricted = true, accessibilityOn = true, tileAdded = true),
        )
    }

    @Test fun `the tile card needs Android 13`() {
        assertEquals(listOf(Id.BATTERY, Id.ACCESSIBILITY), ids(Rom.OTHER, sdk = 32))
    }

    /** The service is disabled in the manifest below API 30, so an Android
     *  10 phone has no accessibility mode to offer. */
    @Test fun `the accessibility card needs Android 11`() {
        assertEquals(listOf(Id.BATTERY), ids(Rom.OTHER, sdk = 29))
        assertEquals(listOf(Id.BATTERY, Id.ACCESSIBILITY), ids(Rom.OTHER, sdk = 30))
    }
}
