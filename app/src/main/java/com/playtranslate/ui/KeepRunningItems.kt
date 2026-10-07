package com.playtranslate.ui

import android.os.Build

/**
 * What the "Keep PlayTranslate running" page lists, decided from the ROM
 * and the current state so the page carries only the settings likely to
 * stop the phone from closing PlayTranslate while it is on. The ROM's own
 * kill switches come first, then what holds everywhere: the battery
 * exemption, accessibility mode (measured priority 100 against 200 on the
 * Moto G, below the level that kill wave reached) and the Quick Settings
 * tile as the one-tap way back.
 */
object KeepRunningItems {

    enum class Rom { XIAOMI, HUAWEI, OPPO, VIVO, SAMSUNG, OTHER }

    enum class Id {
        XIAOMI_AUTOSTART, XIAOMI_BATTERY, XIAOMI_LOCK_RECENTS,
        HUAWEI_APP_LAUNCH, HUAWEI_CLOSE_AFTER_LOCK,
        OPPO_AUTO_LAUNCH,
        VIVO_AUTOSTART,
        SAMSUNG_NEVER_SLEEPING,
        BATTERY, ACCESSIBILITY, QUICK_TILE,
    }

    /** Brand detection over the Build strings, lower-cased by the caller. */
    fun detectRom(manufacturer: String, brand: String): Rom {
        val m = manufacturer.lowercase()
        val b = brand.lowercase()
        return when {
            "xiaomi" in m || "redmi" in b || "poco" in b -> Rom.XIAOMI
            "huawei" in m || "honor" in m || "honor" in b -> Rom.HUAWEI
            "oppo" in m || "realme" in m || "realme" in b || "oneplus" in m -> Rom.OPPO
            "vivo" in m || "iqoo" in b -> Rom.VIVO
            "samsung" in m -> Rom.SAMSUNG
            else -> Rom.OTHER
        }
    }

    fun ids(
        rom: Rom,
        batteryUnrestricted: Boolean,
        accessibilityOn: Boolean,
        tileAdded: Boolean,
        sdkInt: Int = Build.VERSION.SDK_INT,
    ): List<Id> = buildList {
        when (rom) {
            Rom.XIAOMI -> {
                add(Id.XIAOMI_AUTOSTART)
                // Xiaomi's own battery manager replaces the generic item.
                add(Id.XIAOMI_BATTERY)
                add(Id.XIAOMI_LOCK_RECENTS)
            }
            Rom.HUAWEI -> {
                add(Id.HUAWEI_APP_LAUNCH)
                add(Id.HUAWEI_CLOSE_AFTER_LOCK)
            }
            Rom.OPPO -> add(Id.OPPO_AUTO_LAUNCH)
            Rom.VIVO -> add(Id.VIVO_AUTOSTART)
            Rom.SAMSUNG -> add(Id.SAMSUNG_NEVER_SLEEPING)
            Rom.OTHER -> Unit
        }
        if (rom != Rom.XIAOMI && !batteryUnrestricted) add(Id.BATTERY)
        if (!accessibilityOn) add(Id.ACCESSIBILITY)
        if (!tileAdded && sdkInt >= Build.VERSION_CODES.TIRAMISU) add(Id.QUICK_TILE)
    }
}
