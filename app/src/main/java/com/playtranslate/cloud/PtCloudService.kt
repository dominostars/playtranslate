package com.playtranslate.cloud

import android.content.Context
import androidx.annotation.VisibleForTesting
import com.playtranslate.CaptureService
import com.playtranslate.Prefs
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * PlayTranslate Cloud's composition root and the name the rest of the app
 * knows it by: its id and display name (the translation-error pill's owner
 * and wording), its settings ([config]), its one HTTP client ([client]), its
 * one cooldown, and the user gestures that reset it.
 *
 * Two routes read through it, one object each, both built on [client] and
 * [config]: the frame reader, which the router
 * ([com.playtranslate.ocr.FrameReaders]) routes captures to by [refusal], and
 * the waterfall backend [PtCloudBackend], which IS in
 * [TranslationBackendRegistry], first among the online services, and is this
 * cooldown's face there: the registry's cooldown skip, its success
 * recording, its degraded messaging and its connectivity fan-out reach the
 * cooldown through it, read at each call.
 *
 * One [CooldownState] covers the whole service, OCR and translation, frame
 * and translate call alike: the server counts both against one budget and
 * one rate bucket, and a client key cannot ask it to skip translation, so a
 * translation-side cooldown (the server's translator rate-limited) keeps the
 * cloud out of capture too until it expires, and out of the waterfall. A
 * stopped pod, a refused key and an account with no membership cool down
 * too ([com.playtranslate.cloud.PtCloudErrors.classifyHttp]), so every reader
 * that reads again and again (a live tier, the camera's acquires), every
 * capture and every waterfall pass stay off the cloud together until the
 * cooldown ends or a gesture resets it. The user's gestures stay here:
 * [setEnabled] (the switch, on the cloud page and on the service's
 * Translation services row), [onCredentialsSaved] and [onHealthRunning].
 *
 * The cooldown feeds the router's decision, which lives here ([refusal]) so
 * that every reader asks the same question: the router
 * ([com.playtranslate.ocr.FrameReaders]), its warm call, and the floating
 * menu's header ([wouldTakeNextCapture]).
 */
object PtCloudService {

    const val ID: BackendId = "ptcloud"
    const val DISPLAY_NAME = "PlayTranslate Cloud"

    @Volatile private var state: CooldownState? = null
    @Volatile private var appContext: Context? = null
    @Volatile private var testConfig: PtCloudConfig? = null

    private val changed = MutableStateFlow(0)

    /**
     * Every change a gesture or the health check makes to the service's
     * state, as a count: the switch ([setEnabled]), the key
     * ([onCredentialsSaved]) and the cooldown those two reset, and the
     * cooldown [onHealthRunning] drops. The two surfaces that show the
     * service, the cloud page and its row on the Translation services page,
     * repaint from it while resumed, so the mutation announces itself and no
     * caller of one has to repaint after it; and since a StateFlow replays
     * its value to a new collector, each resume repaints too. A cooldown a
     * failed call records is no gesture and is not counted: it shows at the
     * next resume, as a store instance's does.
     */
    val changes: StateFlow<Int> = changed.asStateFlow()

    /** The settings as the app stores them, built at each [init] on that
     *  init's context: the same application in production; under
     *  Robolectric each test's own, so a config read through [config] never
     *  belongs to a torn-down application's preferences. */
    @Volatile private var defaultConfig: PtCloudConfig? = null

    /** Builds the persisted cooldown and the stored settings ([config]), and
     *  keeps the application context the gestures use: the switch is stored
     *  ([setEnabled]) and the translation cache reconciled with it. Called
     *  once from
     *  [com.playtranslate.PlayTranslateApplication.onCreate], before the
     *  translation registry, whose cloud backend reads the settings, the
     *  client and the cooldown at each call, and before
     *  [com.playtranslate.net.NetworkConnectivity.install], whose
     *  registration replays the current network at once and must find the
     *  state. A repeat call keeps the first state and takes the new context
     *  and settings. */
    @Synchronized
    fun init(context: Context) {
        val app = context.applicationContext
        appContext = app
        defaultConfig = PtCloudConfig(Prefs(app))
        if (state == null) state = CooldownState(app, ID)
    }

    /** Replaces the state and the settings for a JVM test ([CooldownState]
     *  with a null context and an injected clock; a [PtCloudConfig] on a
     *  fake codec, since production [Prefs] stores no key without the
     *  AndroidKeyStore). Null for [testState] uninstalls the state; null for
     *  [testConfig] restores the stored settings. The [client] is not
     *  swapped: it is the process's one and sends to the real address, so a
     *  test that sends requests builds its own [PtCloudClient] on a canned
     *  OkHttp client and hands it to the code under test. */
    @VisibleForTesting
    @Synchronized
    fun installForTest(testState: CooldownState?, testConfig: PtCloudConfig? = null) {
        state = testState
        this.testConfig = testConfig
    }

    /** The service's cooldown. Throws until [init] has run. */
    val cooldown: CooldownState
        get() = state ?: throw IllegalStateException("PtCloudService.init(context) was not called")

    /** The service's settings, read from [Prefs] at each use
     *  ([PtCloudConfig]), so a change applies to the next read or
     *  translation with nothing to rebuild. Throws until [init] has run. */
    val config: PtCloudConfig
        get() = testConfig ?: requireDefaultConfig()

    /** The one client, for every frame, warm and translate call, so a warm
     *  opens the connection both routes reuse (one connection pool). Built
     *  once, on the stored settings (never a test's: it is the process's
     *  client and sends to the real address) of the init current at its
     *  first use. Throws until [init] has run. */
    val client: PtCloudClient by lazy { PtCloudClient(requireDefaultConfig()) }

    private fun requireContext(): Context =
        appContext ?: throw IllegalStateException("PtCloudService.init(context) was not called")

    private fun requireDefaultConfig(): PtCloudConfig =
        defaultConfig ?: throw IllegalStateException("PtCloudService.init(context) was not called")

    /** True while a cooldown keeps capture on the device reader and the
     *  waterfall off the cloud. */
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

    /** The service's switch, the one mutation behind both of its faces:
     *  the cloud page's master switch and the service's row on the
     *  Translation services page. Stores it ([Prefs.ptCloudEnabled]) and
     *  resets the service, since either direction means "try it again now";
     *  the store comes first because the reset's reconcile reads the switch
     *  (whether the waterfall's first backend is this one). Main thread. */
    fun setEnabled(enabled: Boolean) {
        Prefs(requireContext()).ptCloudEnabled = enabled
        resetForUserGesture()
    }

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
        changed.update { it + 1 }
    }

    /** As [com.playtranslate.translation.OnlineServiceMutations.saveConfig]
     *  does for a waterfall service: drop the cooldown and both ladders,
     *  forget the pill's errors so a failure after the gesture shows again,
     *  clear the translation cache (the process's one,
     *  [TranslationCache.shared], so the camera's and the import tool's
     *  translations under the old configuration go too), and reconcile the
     *  cache's routing identity, after the reset so that it is computed
     *  against the cleared state (the order
     *  [com.playtranslate.translation.OnlineServiceMutations.setEnabled]
     *  uses): the switch and the key decide whether the waterfall's first
     *  backend is this one. A running live session looks again now: the
     *  gesture changes which reader the next read goes to, and the furigana
     *  tier's change gate would otherwise keep the old reader's result until
     *  the pixels move. Last, the gesture is counted on [changes], so the
     *  surfaces that show the service repaint. */
    private fun resetForUserGesture() {
        cooldown.resetCooldown()
        TranslationErrors.forget(ID)
        GroupTranslator.clearShared()
        GroupTranslator.reconcileShared(requireContext())
        CaptureService.instance?.refreshLiveOverlay()
        changed.update { it + 1 }
    }

    /** Reports one frame's outcome to the translation-error tracker, through
     *  the registry's listener, like a waterfall pass's. The frame route's
     *  only: the translate route's attempts are the registry's own reports,
     *  since its caller is a registry backend. */
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
