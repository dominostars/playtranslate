package com.playtranslate.ui

import android.app.Activity
import android.os.Bundle
import android.view.Display
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.playtranslate.R
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController

/**
 * The split body over a primary with no entry: the tapped unit's column
 * renders the "No definitions found." placeholder where its senses would
 * be, above the member sections, and only the sections that open are
 * controller targets. This is the lens a fused phrase no dictionary lists
 * gets once its members are offered anyway.
 */
@RunWith(RobolectricTestRunner::class)
class MagnifierLensNotFoundSplitTest {

    class Host : Activity() {
        override fun onCreate(savedInstanceState: Bundle?) {
            setTheme(R.style.Theme_PlayTranslate)
            super.onCreate(savedInstanceState)
        }
    }

    private lateinit var controller: ActivityController<Host>
    private lateinit var lens: MagnifierLens

    @Before
    fun setUp() {
        controller = Robolectric.buildActivity(Host::class.java).setup()
        val activity = controller.get()
        lens = MagnifierLens(activity, activity.windowManager, Display.DEFAULT_DISPLAY)
        val dm = activity.resources.displayMetrics
        lens.show(dm.widthPixels / 2, dm.heightPixels * 3 / 4, dm.widthPixels, dm.heightPixels)
        lens.makeInteractive()
    }

    @After
    fun tearDown() {
        lens.dismiss()
        controller.pause().stop().destroy()
    }

    private fun textViews(v: View): List<TextView> = when (v) {
        is TextView -> listOf(v)
        is ViewGroup -> (0 until v.childCount).flatMap { textViews(v.getChildAt(it)) }
        else -> emptyList()
    }

    @Test
    fun `a primary with no entry shows the placeholder above its member sections`() {
        val placeholder = controller.get().getString(R.string.word_detail_no_definitions)
        val primary = LensSection(
            WordDefinitionData("星と深淵を目指せ", "ほしとしんえんをめざせ", emptyList(), freqScore = 0, isCommon = false),
            label = null, opens = false,
        )
        val member = LensSection(
            WordDefinitionData(
                "星", "ほし", listOf(SenseDisplay(pos = emptyList(), definition = "star", misc = emptyList())),
                freqScore = 0, isCommon = false,
            ),
            label = null, opens = true,
        )
        lens.setSplitDefinitions(primary, listOf(member), secondariesOnTop = false)

        // Only the member opens, so it is the one controller target; the
        // primary's column is the split body's first child, above it.
        val targets = lens.navSectionsForTest()
        assertEquals(1, targets.size)
        val memberColumn = targets.single()
        val split = memberColumn.parent as ViewGroup
        val primaryColumn = split.getChildAt(0)
        assertTrue(primaryColumn !== memberColumn)
        assertTrue(split.indexOfChild(primaryColumn) < split.indexOfChild(memberColumn))
        assertTrue(textViews(primaryColumn).any { it.text.toString() == placeholder })
        assertFalse(textViews(memberColumn).any { it.text.toString() == placeholder })
        assertTrue(textViews(memberColumn).any { it.text.toString().contains("star") })
    }
}
