package com.playtranslate.cloud

import android.content.Context
import android.util.Log
import androidx.annotation.VisibleForTesting
import com.playtranslate.CaptureService
import com.playtranslate.OverlayFlavor
import com.playtranslate.language.SourceLangId
import com.playtranslate.net.NetworkConnectivity.InternetState
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
 *
 * Both feed the router's decision, which lives here ([refusal]) so that
 * every reader asks the same question: the router, its warm call, and the
 * floating menu's header ([wouldTakeNextCapture]).
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
     * The floating menu's header reads it only while a live tier runs, as
     * every tier's live read does ([wouldTakeNextCapture]).
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

    /**
     * The router's one decision: why a read of [srcId] with [deviceOnly] would
     * stay on the device, or null when it would go to PlayTranslate Cloud
     * ([com.playtranslate.CaptureService.frameReaderFor]'s table). The router
     * routes by it, [com.playtranslate.CaptureService.warmCloud] warms by it
     * (so a warm goes out exactly when the read that follows would go to the
     * cloud), and the floating menu's header shows it
     * ([wouldTakeNextCapture]).
     *
     * The two facts that cost nothing decide first, each ending the decision
     * when it keeps the read on the device, so a read that could never go to
     * the cloud reads nothing else, the key's AndroidKeyStore decryption
     * included: the switch, off in every release build, then the caller's
     * device-only statement, which a blocked live session makes about once a
     * second. Only then are the
     * key (whose read decrypts it), the source and the cooldown read, all
     * three, and judged by [PtCloudRouting.eligible]; the first of them, in
     * that order, that refuses is the reason.
     */
    fun refusal(config: PtCloudConfig, srcId: SourceLangId, deviceOnly: Boolean): CloudRefusal? {
        val enabled = config.enabled
        if (!enabled) return CloudRefusal.SWITCH_OFF
        if (deviceOnly) return CloudRefusal.DEVICE_ONLY
        val hasKey = config.hasKey
        val sourceSupported = PtCloudRouting.isSupportedSource(srcId)
        val coolingDown = isCoolingDown()
        if (PtCloudRouting.eligible(enabled, hasKey, sourceSupported, coolingDown, deviceOnly)) {
            return null
        }
        return when {
            !hasKey -> CloudRefusal.NO_KEY
            !sourceSupported -> CloudRefusal.SOURCE
            else -> CloudRefusal.COOLING_DOWN
        }
    }

    /**
     * Whether the next capture would go to PlayTranslate Cloud now, which the
     * floating menu's header shows (wifi, or wifi_off): the device has a
     * network ([internet] is not [InternetState.NONE], the app's one "no
     * internet" rule, [com.playtranslate.net.NetworkConnectivity.isAvailable])
     * and [refusal] finds nothing against that capture's read of [srcId].
     *
     * While live mode runs ([liveFlavor], [com.playtranslate.CaptureService.liveFlavor],
     * not null), the next capture is its next read, which every tier makes
     * through [com.playtranslate.CaptureService.runLiveOcrRead]: device-only
     * once the service refused the session ([liveSessionBlocked]). The
     * furigana tier's change gate may skip raw ticks, in which case the
     * next capture is the first one it lets through, read the same way.
     * Without live mode the next capture is a one-shot, whose read states
     * nothing (a re-OCR states device-only, but re-reads a capture already
     * made). The network is read first, so a device with none decrypts no
     * key.
     */
    fun wouldTakeNextCapture(
        config: PtCloudConfig,
        srcId: SourceLangId,
        liveFlavor: OverlayFlavor?,
        internet: InternetState,
    ): Boolean {
        if (internet == InternetState.NONE) return false
        val deviceOnly = liveFlavor != null && liveSessionBlocked
        return refusal(config, srcId, deviceOnly) == null
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
     *  tries the cloud again too ([liveSessionBlocked]), and looks again
     *  now: the gesture changes which reader the next read goes to, and the
     *  furigana tier's change gate would otherwise keep the old reader's
     *  result until the pixels move. */
    private fun resetForUserGesture() {
        liveSessionBlocked = false
        cooldown.resetCooldown()
        TranslationErrors.forget(ID)
        CaptureService.instance?.clearTranslationCache()
        CaptureService.instance?.refreshLiveOverlay()
    }

    /** Reports one call's outcome to the translation-error tracker, through
     *  the registry's listener, like a waterfall pass's. */
    fun report(attempt: OnlineAttempt) {
        TranslationBackendRegistry.reportOnlineAttempts(listOf(attempt))
    }
}

/** Why a read stays on the device ([PtCloudService.refusal]). [logWord] names
 *  it in the router's log line ([com.playtranslate.CaptureService.frameReaderFor]);
 *  the switch has none, so a release build, where it is always off, logs
 *  nothing per read. */
enum class CloudRefusal(val logWord: String?) {
    SWITCH_OFF(null),
    DEVICE_ONLY("device-only"),
    NO_KEY("no key"),
    SOURCE("source"),
    COOLING_DOWN("cooling down"),
}
