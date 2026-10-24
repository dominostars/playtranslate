package com.playtranslate.ui

import android.app.Activity
import android.os.Looper
import android.webkit.WebView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * The page's imported-row ✕ ([DefinitionsDocument.ImportedCuration]) must
 * reach the binder's [YomitanDefinitionsView.onRemoveImported] hook as the
 * row's index and never as a body tap: the Anki word sheet removes the row
 * that index names, and a body tap removes nothing. Catches the bridge
 * method invoking the wrong hook.
 */
@RunWith(RobolectricTestRunner::class)
class YomitanDefinitionsViewBridgeTest {

    @Test
    fun `a row's remove tap reaches the remove hook with its index, not the body tap`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val v = YomitanDefinitionsView(
            activity,
            DefinitionsDocument.Tokens(
                text = 0xFFEFEFEF.toInt(),
                textMuted = 0xFFA0A0A0.toInt(),
                textHint = 0xFF606060.toInt(),
                accent = 0xFF00BCD4.toInt(),
                panel = 0xFF242424.toInt(),
                baseFontSizePx = 14f,
            ),
        )
        assumeTrue("Robolectric image without a shadow WebView", v.isUsable())
        // Attached, so the bridge's post{} runs on the main looper rather
        // than queueing until an attach that never comes.
        activity.setContentView(v)
        var removed: Int? = null
        var bodyTaps = 0
        v.onRemoveImported = { removed = it }
        v.onBodyTap = { bodyTaps++ }

        val webView = v.getChildAt(0) as WebView
        val bridge = shadowOf(webView).getJavascriptInterface("PTBridge")
        bridge.javaClass.getMethod("onRemoveImported", Int::class.javaPrimitiveType)
            .invoke(bridge, 3)
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(3, removed)
        assertFalse("a row's remove tap fired the body tap", bodyTaps > 0)
    }
}
