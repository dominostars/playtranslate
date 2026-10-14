package com.playtranslate.diagnostics

import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Display
import com.playtranslate.Prefs
import com.playtranslate.capture.CaptureBackendResolver

/**
 * The capture-display block of the log-export header (same family as
 * [ProcessExitDiag]): the saved display selection, what the active backend
 * resolves it to, the displays that hold a floating icon, and every display
 * the process can see, so a report can say which screen the capture is on
 * and whether an icon is up at all. Always printed: a
 * selection is always there to read, and the one time it mattered nothing
 * in the log held it. A Galaxy S25 export (2026-10-14) showed the floating
 * icon torn down and placed again, intro and all, on every display event,
 * because the stored selection named a display that no longer existed; the
 * selection is a pref the log never carried, and the displays appeared only
 * in MainActivity's dump when an event happened to fire while it was open.
 *
 * Content-free: ids, flags, states and sizes, and the system's own names
 * for the displays, which MainActivity's display dump already logs.
 */
object DisplayDiag {

    /** One display as the header prints it. */
    data class Row(
        val id: Int,
        val name: String,
        val flags: Int,
        val state: Int,
        val width: Int,
        val height: Int,
    )

    /** The block's lines, read live. Blocking (display queries): call off
     *  the main thread, as the export is. */
    fun lines(context: Context): List<String> {
        val prefs = Prefs(context)
        val backend = CaptureBackendResolver.active()
        val dm = context.getSystemService(DisplayManager::class.java)
        val rows = dm?.displays.orEmpty().map { d ->
            val mode = d.mode
            Row(d.displayId, d.name, d.flags, d.state, mode.physicalWidth, mode.physicalHeight)
        }
        return format(
            saved = prefs.captureDisplayIds,
            stored = prefs.hasDisplaySelection,
            backend = if (backend.requiresAccessibilityService) "accessibility" else "mediaprojection",
            resolved = backend.capturableTargets(context, prefs.captureDisplayIds),
            // No controller (the accessibility service not connected) is no icon.
            icons = CaptureBackendResolver.activeOverlayUi?.floatingIconDisplayIds ?: emptySet(),
            displays = rows,
        )
    }

    /**
     * [saved] is the selection as read ([Prefs.captureDisplayIds]), [stored]
     * whether one was ever written (else the getter's default-display
     * stand-in), [resolved] what [backend] makes of it, [icons] the displays
     * that hold a floating icon ([com.playtranslate.OverlayUiController.floatingIconDisplayIds]),
     * [displays] what is connected. The first line names the selection,
     * which of its displays are not connected (the shim collapses those to
     * the fallback display, so a selection that reads as 3 and resolves to
     * 0 is stale, not picked), the targets (the resolved displays that are
     * connected, where the capture goes, or that none is, which the belt in
     * [com.playtranslate.OverlayUiController.reconcileFloatingIcons] turns
     * into one icon on the fallback display) and the icons as they are, not
     * as the targets imply: the reconcile installs none while the
     * preference is off, the icon is suppressed or MediaProjection is not
     * turned on, and a report of a missing icon needs that said.
     */
    internal fun format(
        saved: Set<Int>,
        stored: Boolean,
        backend: String,
        resolved: Set<Int>,
        icons: Set<Int>,
        displays: List<Row>,
    ): List<String> {
        val connected = displays.map { it.id }
        val notConnected = saved.filter { it !in connected }
        val targets = resolved.filter { it in connected }
        val first = "Capture displays: saved=${saved.joinToString(",")}" +
            (if (stored) "" else " (unstored default)") +
            (if (notConnected.isEmpty()) "" else " (not connected: ${notConnected.joinToString(",")})") +
            " backend=$backend resolved=${resolved.joinToString(",")}" +
            " targets=" + (if (targets.isEmpty()) "none connected" else targets.joinToString(",")) +
            " icons=" + (if (icons.isEmpty()) "none" else icons.sorted().joinToString(","))
        return listOf(first) + displays.map { row ->
            "- id=${row.id} '${row.name}' flags=0x${Integer.toHexString(row.flags)}" +
                flagNames(row.flags) + " state=${stateName(row.state)} ${row.width}x${row.height}"
        }
    }

    /** The flags that decide whether a display is a screen of the user's
     *  ([com.playtranslate.capturableDisplays]) or a presentation surface. */
    private fun flagNames(flags: Int): String {
        val names = buildList {
            if (flags and Display.FLAG_PRIVATE != 0) add("private")
            if (flags and Display.FLAG_PRESENTATION != 0) add("presentation")
        }
        return if (names.isEmpty()) "" else names.joinToString(",", prefix = " (", postfix = ")")
    }

    private fun stateName(state: Int): String = when (state) {
        Display.STATE_OFF -> "OFF"
        Display.STATE_ON -> "ON"
        Display.STATE_DOZE -> "DOZE"
        Display.STATE_DOZE_SUSPEND -> "DOZE_SUSPEND"
        Display.STATE_ON_SUSPEND -> "ON_SUSPEND"
        else -> "STATE_$state"
    }
}
