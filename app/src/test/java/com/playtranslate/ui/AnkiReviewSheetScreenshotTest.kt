package com.playtranslate.ui

import android.content.Context
import android.os.Bundle
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.FragmentActivity
import androidx.test.core.app.ApplicationProvider
import com.playtranslate.R
import com.playtranslate.language.SourceLangId
import com.playtranslate.vocab.HiddenWordsStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * The sentence review sheet's screenshot across a saved-state recreation:
 * the crop the user set is what the restored sheet sends, and a removal
 * stays removed (the sentence tab once brought the photo back on restore,
 * its removal living in an unsaved flag). The state rides the sheet's
 * arguments ([AnkiScreenshotState.open] writes every edit back), so the
 * same pin is adopted, not copied.
 */
@RunWith(RobolectricTestRunner::class)
class AnkiReviewSheetScreenshotTest {

    class Host : FragmentActivity() {
        override fun onCreate(savedInstanceState: Bundle?) {
            setTheme(R.style.Theme_PlayTranslate)
            super.onCreate(savedInstanceState)
        }
    }

    private val ctx: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp(): Unit = runBlocking {
        HiddenWordsStore.resetForTest(ctx)
        LastSentenceCache.clear()
    }

    @After
    fun tearDown() {
        shadowOf(Looper.getMainLooper()).idle()
        LastSentenceCache.clear()
        runBlocking { HiddenWordsStore.resetForTest(ctx) }
    }

    private fun screenshot(): File {
        val f = File(ctx.cacheDir, "screenshots/capture-d0.png")
        f.parentFile!!.mkdirs()
        ImageIO.write(BufferedImage(200, 100, BufferedImage.TYPE_INT_RGB), "png", f)
        return f
    }

    private fun show(): ActivityController<Host> {
        val controller = Robolectric.buildActivity(Host::class.java).setup()
        AnkiReviewBottomSheet.newInstance(
            "猫が好きです。", "I like cats.",
            mapOf("猫" to Triple("ねこ", "cat", 3)), emptyMap(), emptyMap(),
            screenshot().absolutePath, SourceLangId.JA,
        ).showNow(controller.get().supportFragmentManager, AnkiReviewBottomSheet.TAG)
        settle()
        return controller
    }

    private fun restore(saved: Bundle): ActivityController<Host> {
        val controller = Robolectric.buildActivity(Host::class.java).setup(saved)
        settle()
        return controller
    }

    private fun sheet(controller: ActivityController<Host>): AnkiReviewBottomSheet =
        controller.get().supportFragmentManager
            .findFragmentByTag(AnkiReviewBottomSheet.TAG) as AnkiReviewBottomSheet

    /** The sheet's screenshot state, through its private field (deliberately
     *  private prod API; read rather than widened). */
    private fun state(controller: ActivityController<Host>): AnkiScreenshotState? =
        AnkiReviewBottomSheet::class.java.getDeclaredField("screenshot")
            .apply { isAccessible = true }
            .get(sheet(controller)) as AnkiScreenshotState?

    private fun content(controller: ActivityController<Host>): SentenceAnkiContentView =
        AnkiReviewBottomSheet::class.java.getDeclaredField("contentView")
            .apply { isAccessible = true }
            .get(sheet(controller)) as SentenceAnkiContentView

    /** The Screenshot group's ✕, found by its content description. */
    private fun removeButton(controller: ActivityController<Host>): View? {
        val label = ctx.getString(R.string.anki_screenshot_remove_content_description)
        fun walk(v: View): View? {
            if (v.contentDescription == label) return v
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))?.let { return it }
            return null
        }
        return walk(sheet(controller).requireView())
    }

    private fun settle() {
        repeat(3) {
            shadowOf(Looper.getMainLooper()).idle()
            runBlocking { HiddenWordsStore.loaded(ctx, SourceLangId.JA) }
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun aCropSurvivesASavedStateRecreation_onTheSamePin() {
        val first = show()
        val pinned = content(first).getCardData().screenshot!!.path
        assertTrue(AnkiScreenshotPin.isPin(ctx, pinned))
        val crop = ScreenshotCrop.of(0.1f, 0.2f, 0.9f, 0.8f)
        state(first)!!.setCrop(crop)

        val saved = Bundle()
        first.pause().saveInstanceState(saved).stop().destroy()
        assertTrue("a saved-state teardown keeps the pin", File(pinned).isFile)

        val restored = restore(saved)
        val sent = content(restored).getCardData().screenshot!!
        assertEquals(pinned, sent.path)
        assertEquals(crop, sent.crop)
        assertNotNull(removeButton(restored))

        restored.get().finish()
        restored.pause().stop().destroy()
        assertTrue("the final teardown releases the pin", !File(pinned).isFile)
    }

    @Test
    fun aRemovalStaysRemovedAcrossASavedStateRecreation() {
        val first = show()
        val pinned = content(first).getCardData().screenshot!!.path
        removeButton(first)!!.performClick()
        assertNull(content(first).getCardData().screenshot)
        assertNull(removeButton(first))
        assertTrue("the pin stays for a send in flight", File(pinned).isFile)

        val saved = Bundle()
        first.pause().saveInstanceState(saved).stop().destroy()
        assertTrue("a saved-state teardown keeps it", File(pinned).isFile)
        val restored = restore(saved)
        assertNull(content(restored).getCardData().screenshot)
        assertNull(removeButton(restored))
        assertNull("the restored editor finds the removal", state(restored)!!.attachment)
        restored.get().finish()
        restored.pause().stop().destroy()
        assertTrue("the final teardown releases the pin", !File(pinned).isFile)
    }
}
