package com.playtranslate

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.WindowManager
import com.playtranslate.capture.CaptureLifecycle

/**
 * Translucent hop for the Quick Settings tile's turn-on tap. The tile
 * launches it through `startActivityAndCollapse`, the one public way a tile
 * can close the shade (the close-system-dialogs broadcast throws for apps
 * targeting Android 12+; the accessibility global action needs the
 * accessibility service, which the MediaProjection backend does not have).
 * The activity performs the activation the tile used to perform itself, so
 * the MediaProjection consent dialog appears over the game rather than over
 * the shade and a grant lands the user in the game with the icon up. The
 * accessibility turn-on takes the same hop so both backends leave the shade
 * the same way (Gilad, 2026-10-09).
 *
 * It acts when its window gains focus, not in onCreate. The expanded shade
 * holds window focus and releases it as it collapses, so focus arriving
 * here is the signal that the shade has let go. Acting earlier put the
 * dialog over the open shade: a tap in the panel hands SystemUI a launch
 * animation controller, and with one the collapse is driven by the launch
 * animation (LegacyActivityStarterInternalImpl, 16.0.0_r1: `collapse =
 * dismissShade && !animate`), so a hop that finished in onCreate without a
 * window sent SystemUI down the cancel path, up to its 1 s transition
 * timeout before the fling even began, while a warm service showed the
 * consent dialog about 100 ms after the tap (Moto G 2025 event log,
 * 2026-10-09; Codex review). Staying alive also gives the animation its
 * window. A shade that is not focusable hands focus over at once, which is
 * the old timing and no worse. A focusable window above the hop (a foreign
 * overlay, a system dialog) would hold focus off indefinitely, so a bounded
 * fallback acts anyway; leaving before either (home, screen off) abandons
 * the tap rather than starting the service from the background. A
 * configuration change inside the wait is not leaving: the manifest keeps
 * rotation, size, layout and keyboard changes in place, and for the rest
 * (locale, theme, density) the stop is let through unsettled, so the
 * recreated instance waits for focus again. The saved state is collected
 * after onStop on these Android versions, so an abandon there would have
 * reached the new instance as settled (Codex review, 2026-10-09).
 *
 * The service start moves from the tile to a foreground activity, the most
 * permitted start context there is; the consent activity's own launch from
 * the service is unchanged (SYSTEM_ALERT_WINDOW, see there).
 */
class TileActivateActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())

    /** True once the tap is settled: acted on, or abandoned. */
    private var settled = false

    private val fallback = Runnable { act("fallback") }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settled = savedInstanceState?.getBoolean(KEY_SETTLED) ?: false
        if (settled) {
            finish()
            return
        }
        // The window exists for the focus signal and SystemUI's launch
        // animation; the game under it keeps its touches meanwhile.
        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
        handler.postDelayed(fallback, FOCUS_FALLBACK_MS)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_SETTLED, settled)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) act("focus")
    }

    override fun onStop() {
        if (!settled && !isChangingConfigurations) {
            settled = true
            handler.removeCallbacks(fallback)
            Log.i(PlayTranslateTileService.TAG, "activate abandoned: left before the shade let go")
            finish()
        }
        super.onStop()
    }

    override fun onDestroy() {
        handler.removeCallbacks(fallback)
        super.onDestroy()
    }

    private fun act(signal: String) {
        if (settled) return
        settled = true
        handler.removeCallbacks(fallback)
        val action = intent?.action
        Log.i(PlayTranslateTileService.TAG, "activate($signal): $action")
        when (action) {
            ACTION_MEDIA_PROJECTION -> startForegroundService(
                Intent(this, CaptureService::class.java)
                    .setAction(CaptureService.ACTION_MP_ACTIVATE)
            )
            ACTION_ACCESSIBILITY -> CaptureLifecycle.activateAccessibility(this)
        }
        finish()
    }

    companion object {
        /** Obtain screen-capture consent and bring the icon up on grant
         *  ([CaptureService.ACTION_MP_ACTIVATE], cold start included). */
        const val ACTION_MEDIA_PROJECTION = "com.playtranslate.action.TILE_ACTIVATE_MP"

        /** [CaptureLifecycle.activateAccessibility]: the bound accessibility
         *  service shows the icon. */
        const val ACTION_ACCESSIBILITY = "com.playtranslate.action.TILE_ACTIVATE_A11Y"

        /** Above SystemUI's own worst case for releasing the shade after a
         *  cancelled launch animation (1 s transition timeout, 120 ms
         *  posted fling, 600 ms fling at most; 16.0.0_r1). */
        private const val FOCUS_FALLBACK_MS = 2_000L

        private const val KEY_SETTLED = "settled"

        /** The launch intent for [action]; NEW_TASK because SystemUI starts
         *  it outside any activity of ours. The manifest gives the activity
         *  its own task, excluded from recents. */
        fun intent(ctx: Context, action: String): Intent =
            Intent(ctx, TileActivateActivity::class.java)
                .setAction(action)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
