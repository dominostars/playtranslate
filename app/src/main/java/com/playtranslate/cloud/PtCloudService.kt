package com.playtranslate.cloud

import android.content.Context
import android.util.Log
import androidx.annotation.VisibleForTesting
import com.playtranslate.CaptureService
import com.playtranslate.TranslationCache
import com.playtranslate.translation.GroupTranslator
import com.playtranslate.language.SourceLangId
import com.playtranslate.net.NetworkConnectivity.InternetState
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
 * capture too until it expires. A stopped pod and a refused key cool down
 * too ([com.playtranslate.cloud.PtCloudErrors.classifyHttp]), so every
 * reader that reads again and again (a live tier, the camera's acquires)
 * and every capture stay on the device together until the cooldown ends or
 * a gesture resets it. It is not in [TranslationBackendRegistry] (it is a
 * frame reader, not a waterfall backend), so the registry's connectivity
 * fan-out and cooldown resets don't reach it; the app wires
 * [onConnectivityRestored], and the settings page calls [onToggled],
 * [onCredentialsSaved] and [onHealthRunning].
 *
 * The cooldown feeds the router's decision, which lives here ([refusal]) so
 * that every reader asks the same question: the router
 * ([com.playtranslate.ocr.FrameReaders]), its warm call, and the floating
 * menu's header ([wouldTakeNextCapture]).
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
     * The router's one decision: why a read of [srcId] with [deviceOnly] would
     * stay on the device, or null when it would go to PlayTranslate Cloud
     * ([com.playtranslate.ocr.FrameReaders.readerFor]'s table). The router
     * routes by it, [com.playtranslate.ocr.FrameReaders.warm] warms by it
     * (so a warm goes out exactly when the read that follows would go to the
     * cloud), and the floating menu's header shows it
     * ([wouldTakeNextCapture]).
     *
     * The two facts that cost nothing decide first, each ending the decision
     * when it keeps the read on the device, so a read that could never go to
     * the cloud reads nothing else, the key's AndroidKeyStore decryption
     * included: the switch, off in every release build, then the caller's
     * device-only statement (a re-OCR, a hold that needs no translation or
     * runs beside live mode). Only then are the
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
     * The next capture is a one-shot's read, or, while live mode runs, the
     * running tier's next read ([com.playtranslate.CaptureService.runLiveOcrRead]);
     * neither states device-only (a re-OCR does, but re-reads a capture
     * already made; a hold beside live mode does, but is not the next
     * capture), so the decision is the same for both. The network is read
     * first, so a device with none decrypts no key.
     */
    fun wouldTakeNextCapture(
        config: PtCloudConfig,
        srcId: SourceLangId,
        internet: InternetState,
    ): Boolean {
        if (internet == InternetState.NONE) return false
        return refusal(config, srcId, deviceOnly = false) == null
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

    /** The cloud page's health check found the service running: a cooldown
     *  recorded while it was stopped ([CooldownCause.NOT_RUNNING]) describes
     *  a state that is over, so it is dropped and a running live session
     *  looks again now, as after a gesture. Nothing else is touched: the
     *  check takes no key and asks for no translation, so Running says
     *  nothing about a refused key or a spent quota, and the configuration
     *  did not change, so the translation cache and the pill's errors stay.
     *  Main thread. */
    fun onHealthRunning() {
        if (cooldown.unavailableCause() != CooldownCause.NOT_RUNNING) return
        cooldown.resetCooldown()
        CaptureService.instance?.refreshLiveOverlay()
    }

    /** As [com.playtranslate.translation.OnlineServiceMutations.saveConfig]
     *  does for a waterfall service: drop the cooldown and both ladders,
     *  forget the pill's errors so a failure after the gesture shows again,
     *  and clear the translation cache (the process's one,
     *  [TranslationCache.shared], so the camera's and the import tool's
     *  translations under the old configuration go too). A running live
     *  session looks again now: the gesture changes which reader the next
     *  read goes to, and the furigana tier's change gate would otherwise
     *  keep the old reader's result until the pixels move. */
    private fun resetForUserGesture() {
        cooldown.resetCooldown()
        TranslationErrors.forget(ID)
        GroupTranslator.clearShared()
        CaptureService.instance?.refreshLiveOverlay()
    }

    /** Reports one call's outcome to the translation-error tracker, through
     *  the registry's listener, like a waterfall pass's. */
    fun report(attempt: OnlineAttempt) {
        TranslationBackendRegistry.reportOnlineAttempts(listOf(attempt))
    }
}

/** Why a read stays on the device ([PtCloudService.refusal]). [logWord] names
 *  it in the router's log line ([com.playtranslate.ocr.FrameReaders.readerFor]);
 *  the switch has none, so a release build, where it is always off, logs
 *  nothing per read. */
enum class CloudRefusal(val logWord: String?) {
    SWITCH_OFF(null),
    DEVICE_ONLY("device-only"),
    NO_KEY("no key"),
    SOURCE("source"),
    COOLING_DOWN("cooling down"),
}
