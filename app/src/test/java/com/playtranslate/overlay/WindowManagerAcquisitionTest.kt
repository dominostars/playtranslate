package com.playtranslate.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Population guard for the own-window clock, at the root: the only way to
 * obtain a WindowManager in this app is [OwnWindows], which hands out a
 * [TickingWindowManager]. Any raw acquisition — the system service by class
 * or by name, or an Activity's `windowManager` property, dotted OR as the
 * bare identifier an Activity subclass can write — outside OwnWindows.kt is
 * a WindowManager whose adds, updates and removes neither
 * [OwnWindowClock] nor [WindowChurnGate] can see: on the AYN firmware the
 * deadlock the guard exists to avoid, and for the churn gate a regression
 * of the shipped 3.2.0 crash mitigation (Codex native review 2026-09-22
 * found exactly that in the process-text lens path, which the first cut of
 * this test missed by matching only the dotted form). The wrapper is also
 * the only caller of [WindowChurnGate.noteWindowAdded], so that claim is
 * checked here too. Comment lines are ignored; code lines are not.
 */
class WindowManagerAcquisitionTest {

    private val allowedFile = "overlay/OwnWindows.kt"
    private val forbidden = listOf(
        "WindowManager::class.java",
        "WINDOW_SERVICE",
        "getSystemService(\"window\"",
        "WindowChurnGate.noteWindowAdded(",
    )

    /** `windowManager` as a property access with any receiver, or bare
     *  (inside an Activity, `windowManager` alone is the Activity's). A
     *  preceding letter, digit or underscore means another identifier. */
    private val windowManagerAccess = Regex("(?<![A-Za-z0-9_])windowManager\\b")

    @Test
    fun `no raw WindowManager acquisition outside OwnWindows`() {
        val root = listOf("src/main/java", "app/src/main/java")
            .map(::File)
            .firstOrNull { it.isDirectory }
            ?: error("main source root not found from ${File(".").absolutePath}")
        val offenders = mutableListOf<String>()
        var allowedHits = 0
        root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .forEach { file ->
                file.readLines().forEachIndexed { index, raw ->
                    val code = raw.substringBefore("//").trim()
                    if (code.startsWith("*") || code.startsWith("/*")) return@forEachIndexed
                    val hit = forbidden.any { code.contains(it) } || windowManagerAccess.containsMatchIn(code)
                    if (!hit) return@forEachIndexed
                    val rel = file.relativeTo(root).path.replace(File.separatorChar, '/')
                        .removePrefix("com/playtranslate/")
                    if (rel == allowedFile) allowedHits++ else offenders += "$rel:${index + 1}: $raw"
                }
            }
        assertEquals(
            "obtain WindowManagers through OwnWindows.manager / managerOf:\n" + offenders.joinToString("\n"),
            emptyList<String>(), offenders,
        )
        assertTrue("OwnWindows.kt itself must acquire the service, found $allowedHits hits", allowedHits >= 2)
    }
}
