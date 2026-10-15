package com.playtranslate.cloud

import android.content.Context
import android.util.Log
import androidx.annotation.VisibleForTesting
import com.playtranslate.CaptureService
import com.playtranslate.translation.BackendFailure
import com.playtranslate.translation.BackendFailureKind
import com.playtranslate.translation.BackendId
import com.playtranslate.translation.CooldownCause
import com.playtranslate.translation.CooldownState
import com.playtranslate.translation.OnlineAttempt
import com.playtranslate.translation.TranslationBackendRegistry
import com.playtranslate.translation.TranslationErrors

/**
 * PlayTranslate Cloud as a service the rest of the app can name: its id and
 * display name (the translation-error pill's owner and wording), its one
 * cooldown, and the user gestures that reset it.
 *
 * One [CooldownState] covers the whole service, OCR and translation alike: a
 * client key cannot ask the server to skip translation, so a translation-side
 * cooldown (the server's translator rate-limited) keeps the cloud out of
 * capture too until it expires. It is not in [TranslationBackendRegistry]
 * (it is a frame reader, not a waterfall backend), so the registry's
 * connectivity fan-out and cooldown resets don't reach it; the app wires
 * [onConnectivityRestored], and the settings page calls [onToggled] and
 * [onCredentialsSaved].
 *
 * Beside the cooldown it keeps one live-session fact, [liveSessionBlocked]:
 * the service refused a live read in a way only the user can change.
 */
object PtCloudService {

    const val ID: BackendId = "ptcloud"
    const val DISPLAY_NAME = "PlayTranslate Cloud"

    private const val TAG = "PtCloud"

    @Volatile private var state: CooldownState? = null

    /** Builds the persisted cooldown. Called once from
     *  [com.playtranslate.PlayTranslateApplication.onCreate], before
     *  [com.playtranslate.net.NetworkConnectivity.install], whose
     *  registration replays the current network at once and must find the
     *  state. A repeat call keeps the first state. */
    @Synchronized
    fun init(context: Context) {
        if (state == null) state = CooldownState(context.applicationContext, ID)
    }

    /** Replaces the state for a JVM test ([CooldownState] with a null
     *  context and an injected clock); null uninstalls it. */
    @VisibleForTesting
    @Synchronized
    fun installForTest(testState: CooldownState?) {
        state = testState
    }

    /** The service's cooldown. Throws until [init] has run. */
    val cooldown: CooldownState
        get() = state ?: throw IllegalStateException("PtCloudService.init(context) was not called")

    /** True while a cooldown keeps capture on the device reader. */
    fun isCoolingDown(nowMs: Long = System.currentTimeMillis()): Boolean =
        cooldown.unavailableUntil()?.let { it > nowMs } == true

    fun unavailableUntil(): Long? = cooldown.unavailableUntil()

    fun unavailableDescription(): String? = cooldown.unavailableDescription()

    fun unavailableCause(): CooldownCause? = cooldown.unavailableCause()

    /**
     * True once the service answered a live read with "not running" (its pod
     * is stopped) or "bad key" during the current live session: every later
     * live read of that session stays on the device. Set by
     * [blockForLiveSession], which [recordLiveRead] calls (that rule, and why,
     * is its kdoc's); cleared when live mode starts ([onLiveSessionStarted])
     * and by the user's gestures that make a retry worth it ([onToggled],
     * [onCredentialsSaved]).
     *
     * Capture-once and the press-and-hold overlay never read it: their own
     * rule is to try the cloud and fall back to the device for that capture.
     */
    @Volatile
    var liveSessionBlocked: Boolean = false
        private set

    /**
     * Acts on one live read's outcome ([com.playtranslate.CaptureService.runLiveOcrRead]):
     * [fallbackFrom] is why the cloud could not read the frame, when the
     * device read it instead ([com.playtranslate.ocr.FrameReading.fallbackFrom]).
     * A not-running or bad-key answer ([BackendFailureKind.NOT_RUNNING],
     * [BackendFailureKind.AUTH]) blocks the rest of the session
     * ([blockForLiveSession]): the pod does not start by itself and a key does
     * not fix itself, so a request per live cycle while either holds would be
     * waste, and each would report the same failure again. Any other kind
     * does nothing here (transport failures, server errors and rate limits
     * keep the cooldown's rules, [isCoolingDown]), and neither does null: the
     * cloud read the frame, the read never went to it, or its failure carried
     * no classification.
     */
    fun recordLiveRead(fallbackFrom: BackendFailure?) {
        val kind = fallbackFrom?.kind
        if (kind == BackendFailureKind.NOT_RUNNING || kind == BackendFailureKind.AUTH) {
            blockForLiveSession()
        }
    }

    /** Keeps the rest of the current live session on the device
     *  ([liveSessionBlocked]). Logs the first time per session only. */
    fun blockForLiveSession() {
        if (liveSessionBlocked) return
        liveSessionBlocked = true
        Log.i(TAG, "live session: cloud reads stay on the device")
    }

    /** A live session started ([com.playtranslate.CaptureService.startLive]):
     *  its reads try the cloud again ([liveSessionBlocked]). */
    fun onLiveSessionStarted() {
        liveSessionBlocked = false
    }

    /** The device regained a network: drops a "Connection failed" cooldown
     *  and the network ladder ([CooldownState.onConnectivityRestored]).
     *  Called off the main thread, from the ConnectivityManager callback.
     *  Returns true when an active cooldown was cleared. */
    fun onConnectivityRestored(): Boolean {
        val cleared = cooldown.onConnectivityRestored()
        if (cleared) Log.i(TAG, "connectivity restored: cleared cooldown")
        return cleared
    }

    /** The settings switch flipped, either way: "try it again now". Main
     *  thread. */
    fun onToggled() = resetForUserGesture()

    /** A new key was saved: the old cooldown and errors describe a
     *  configuration that is gone. Main thread. */
    fun onCredentialsSaved() = resetForUserGesture()

    /** As [com.playtranslate.translation.OnlineServiceMutations.saveConfig]
     *  does for a waterfall service: drop the cooldown and both ladders,
     *  forget the pill's errors so a failure after the gesture shows again,
     *  and clear the translation cache, which holds this service's
     *  translations under the old configuration. A running live session
     *  tries the cloud again too ([liveSessionBlocked]). */
    private fun resetForUserGesture() {
        liveSessionBlocked = false
        cooldown.resetCooldown()
        TranslationErrors.forget(ID)
        CaptureService.instance?.clearTranslationCache()
    }

    /** Reports one call's outcome to the translation-error tracker, through
     *  the registry's listener, like a waterfall pass's. */
    fun report(attempt: OnlineAttempt) {
        TranslationBackendRegistry.reportOnlineAttempts(listOf(attempt))
    }
}
