package com.playtranslate.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Population guard for view-state collisions in layouts. View state is saved
 * and restored by view id, so two stateful widgets that share an id in one
 * hierarchy hand each other their state on a recreate (rotation, a dark-mode
 * or language change, a restore after the process was killed), and a switch
 * whose listener persists writes the other one's value into its own setting.
 * The switch rows hit exactly that: settings_row_switch is included 14 times
 * in the Settings dialog, 4 in Capture and overlay, 3 in Translation History,
 * and inflated per audio row in the Anki sheet, every copy with the id
 * switchRowToggle; recreating Translation History turned history recording
 * off. Two rules:
 *
 *  1. No layout, with its includes expanded, holds two save-enabled stateful
 *     widgets under one id.
 *  2. A checkable whose id several layout files declare (a row family the
 *     hosts look up by that id: settings_row_switch and its siblings) opts
 *     out of view-state saving, because code stacks those rows too
 *     ([addCompactAudioToggleRow], [DownloadableToggleRow]) and the stacking
 *     is invisible here.
 *
 * Residual: a new single-file row inflated several times from code under an
 * id of its own is caught by neither rule.
 */
class SwitchRowSaveStateTest {

    private val checkable = setOf(
        "Switch", "SwitchCompat", "MaterialSwitch", "SwitchMaterial",
        "CheckBox", "AppCompatCheckBox", "MaterialCheckBox",
        "RadioButton", "AppCompatRadioButton", "MaterialRadioButton",
        "ToggleButton", "CompoundButton", "Chip",
    )
    private val stateful = checkable + setOf(
        "EditText", "AppCompatEditText", "TextInputEditText",
        "AutoCompleteTextView", "AppCompatAutoCompleteTextView", "MaterialAutoCompleteTextView",
        "SeekBar", "AppCompatSeekBar", "Slider", "RangeSlider", "RatingBar",
        "Spinner", "AppCompatSpinner",
    )

    private data class Widget(val id: String, val saveEnabled: Boolean)

    private val layoutDir: File by lazy {
        listOf("src/main/res/layout", "app/src/main/res/layout")
            .map(::File)
            .firstOrNull { it.isDirectory }
            ?: error("layout dir not found from ${File(".").absolutePath}")
    }

    private val roots: Map<String, Element> by lazy {
        val builder = DocumentBuilderFactory.newInstance().newDocumentBuilder()
        layoutDir.listFiles { f -> f.extension == "xml" }!!
            .associate { it.nameWithoutExtension to builder.parse(it).documentElement }
    }

    private fun Element.androidId(): String? =
        getAttribute("android:id").takeIf { it.isNotEmpty() }?.substringAfter('/')

    private fun Element.simpleName(): String = tagName.substringAfterLast('.')

    private fun Element.childElements(): List<Element> =
        (0 until childNodes.length).mapNotNull { childNodes.item(it) as? Element }

    /** The stateful widgets with an id under [el], includes expanded. An
     *  include's own id replaces its layout's root id (not a merge's). */
    private fun expand(el: Element, idOverride: String? = null, depth: Int = 0): List<Widget> {
        check(depth < 32) { "include cycle at ${el.tagName}" }
        if (el.tagName == "include") {
            val name = el.getAttribute("layout").substringAfter("@layout/")
            val included = roots[name] ?: error("include of missing layout $name")
            return expand(included, el.androidId(), depth + 1)
        }
        val out = mutableListOf<Widget>()
        val id = if (el.tagName != "merge") idOverride ?: el.androidId() else null
        if (id != null && el.simpleName() in stateful) {
            out += Widget(id, el.getAttribute("android:saveEnabled") != "false")
        }
        el.childElements().forEach { out += expand(it, depth = depth + 1) }
        return out
    }

    @Test
    fun `no layout stacks two save-enabled stateful widgets under one id`() {
        val offenders = roots.flatMap { (name, root) ->
            expand(root).filter { it.saveEnabled }
                .groupingBy { it.id }.eachCount()
                .filter { it.value > 1 }
                .map { (id, n) -> "$name.xml: $id x$n" }
        }
        assertEquals(
            "stacked widgets would restore each other's state; give them distinct ids or " +
                "android:saveEnabled=\"false\" and bind them from their owner:\n" +
                offenders.joinToString("\n"),
            emptyList<String>(), offenders,
        )
        // The expansion is live: the Settings dialog's switch rows are seen.
        val dialogRows = expand(roots.getValue("dialog_settings")).count { it.id == "switchRowToggle" }
        assertTrue("expected the Settings dialog's stacked switch rows, saw $dialogRows", dialogRows >= 2)
    }

    @Test
    fun `a checkable id shared across layout files never saves view state`() {
        val declared = roots.flatMap { (name, root) ->
            fun walk(el: Element): List<Pair<String, Element>> =
                listOfNotNull(
                    el.takeIf { it.simpleName() in checkable && it.androidId() != null }?.let { name to it },
                ) + el.childElements().flatMap(::walk)
            walk(root)
        }
        val shared = declared.groupBy { it.second.androidId()!! }
            .filterValues { decls -> decls.map { it.first }.distinct().size > 1 }
        val offenders = shared.flatMap { (id, decls) ->
            decls.filter { it.second.getAttribute("android:saveEnabled") != "false" }
                .map { "${it.first}.xml: $id" }
        }
        assertEquals(
            "a row family's checkable must set android:saveEnabled=\"false\":\n" +
                offenders.joinToString("\n"),
            emptyList<String>(), offenders,
        )
        val rowFiles = shared["switchRowToggle"]?.map { it.first }?.distinct()?.size ?: 0
        assertTrue("expected the switchRowToggle row family, saw $rowFiles files", rowFiles >= 4)
    }
}
