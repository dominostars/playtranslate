package com.playtranslate.ui

import android.os.Build

/**
 * What the "Fix disappearing icon" page lists, decided from the ROM and
 * the current state so the page carries only the settings likely to stop
 * the phone from closing PlayTranslate while it is on, or to bring it back
 * when it does. The ROM's own switches come first (no app can read them,
 * so those cards stay), then what holds everywhere: the battery exemption,
 * accessibility mode (measured priority 100 against 200 on the Moto G,
 * below the level that phone's kill wave reached) and the Quick Settings
 * tile as the way back. The accessibility card needs Android 11 and the
 * tile card Android 13, the versions where each exists. The words on
 * every card are sourced in the string comments.
 */
object KeepRunningItems {

    enum class Rom { XIAOMI, HUAWEI, OPPO, VIVO, SAMSUNG, OTHER }

    enum class Id {
        XIAOMI_AUTOSTART, XIAOMI_BATTERY, XIAOMI_LOCK_RECENTS,
        HUAWEI_APP_LAUNCH,
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
                // Xiaomi's own per-app battery entry replaces the generic
                // card and, like every ROM switch, stays: no app can read
                // it. The standard exemption is no proxy. One decompile of
                // China HyperOS on Android 16 (secondary) says "No
                // restrictions" also adds the exemption, but nothing says
                // the reverse, and the exemption can be granted from the
                // system's own screens with Xiaomi's entry left restrictive.
                add(Id.XIAOMI_BATTERY)
                add(Id.XIAOMI_LOCK_RECENTS)
            }
            Rom.HUAWEI -> add(Id.HUAWEI_APP_LAUNCH)
            Rom.OPPO -> add(Id.OPPO_AUTO_LAUNCH)
            Rom.VIVO -> add(Id.VIVO_AUTOSTART)
            Rom.SAMSUNG -> add(Id.SAMSUNG_NEVER_SLEEPING)
            Rom.OTHER -> Unit
        }
        if (rom != Rom.XIAOMI && !batteryUnrestricted) add(Id.BATTERY)
        // Below Android 11 the service is disabled in the manifest (no
        // takeScreenshot before API 30, res/values/bools.xml), so the
        // phone's accessibility list never shows PlayTranslate and the
        // card would send the user to a mode the phone cannot have.
        if (!accessibilityOn && sdkInt >= Build.VERSION_CODES.R) add(Id.ACCESSIBILITY)
        if (!tileAdded && sdkInt >= Build.VERSION_CODES.TIRAMISU) add(Id.QUICK_TILE)
    }
}
