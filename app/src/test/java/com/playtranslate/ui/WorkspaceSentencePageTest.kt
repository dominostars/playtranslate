package com.playtranslate.ui

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import com.playtranslate.R
import com.playtranslate.overlay.OverlayHost
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController

/**
 * [WorkspaceSentencePage], the results page as a workspace page, shown with
 * the drag flow's cached translation (no backend is asked): it shows the
 * floating Anki pill its content draws, and the controller's A on the pill
 * is a hold, as on the word page, so held it sends the card at once.
 */
@RunWith(RobolectricTestRunner::class)
class WorkspaceSentencePageTest {

    class Host : Activity() {
        override fun onCreate(savedInstanceState: Bundle?) {
            setTheme(R.style.Theme_PlayTranslate)
            super.onCreate(savedInstanceState)
        }
    }

    private class FakeHost(activity: Activity) : WorkspaceHost {
        override val ctx: Context = activity
        override val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        override val displayId: Int = 0
        override val wm: WindowManager = activity.windowManager
        override val overlayHost: OverlayHost =
            OverlayHost(activity, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        override val modalLayer: FrameLayout = FrameLayout(activity)

        override fun push(page: WorkspacePage) = Unit
        override fun pop() = Unit
        override fun dismiss() = Unit
        override fun setTitle(title: CharSequence) = Unit
        override fun setHeaderView(view: View?) = Unit
        override fun setImeMode(wantsIme: Boolean) = Unit
        override fun setParkedForActivity(parked: Boolean) = Unit
        override fun showProgress(title: String, onDismiss: (DismissReason) -> Unit): OverlayProgress =
            error("unused")
        override fun alert(): OverlayAlert.Builder = OverlayAlert.Builder(ctx)
        override fun invalidateNav(prefer: View?) = Unit
    }

    private lateinit var controller: ActivityController<Host>
    private lateinit var activity: Host
    private lateinit var host: FakeHost
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private fun clearPrefs() {
        androidx.test.core.app.ApplicationProvider.getApplicationContext<Context>()
            .getSharedPreferences("playtranslate_prefs", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Before
    fun setUp() {
        clearPrefs()
        controller = Robolectric.buildActivity(Host::class.java).setup()
        activity = controller.get()
        host = FakeHost(activity)
    }

    @After
    fun tearDown() {
        scope.cancel()
        host.scope.cancel()
        controller.pause().stop().destroy()
        shadowOf(Looper.getMainLooper()).idle()
        clearPrefs()
    }

    // Mutation: collect the page's targets without the pill's hold.
    @Test
    fun `the page shows the Anki pill with its result, and A on it is a hold`() {
        val page = WorkspaceSentencePage(
            TranslationResultViewModel(scope),
            LensDetailArgs(
                word = "猫", reading = "ねこ", surface = null, lookupForm = "猫", lookupReading = "ねこ",
                sentence = "猫が食べる。", screenshotPath = null,
                audioAnchorMs = null,
                // The drag flow's translation: the page binds it, asking no backend.
                sentenceContext = SentenceContext("猫が食べる。", "The cat eats.", null),
            ),
        )
        val parent = FrameLayout(activity)
        activity.setContentView(parent)
        val view = page.onCreateView(activity, parent, host)
        parent.addView(view)
        shadowOf(Looper.getMainLooper()).idle()

        val pill = view.findViewById<View>(R.id.btnAnkiPill)
        assertEquals("shown with the result", View.VISIBLE, pill.visibility)
        val actions = page.navActions()
        assertTrue("the pill is a hold", actions.single { it.view === pill }.holdActivates)
        assertTrue("nothing else is", actions.filter { it.view !== pill }.none { it.holdActivates })
        page.onDestroy()
    }
}
