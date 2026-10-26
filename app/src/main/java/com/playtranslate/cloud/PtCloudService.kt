package com.playtranslate.cloud

import android.content.Context
import androidx.annotation.VisibleForTesting
import com.playtranslate.CaptureService
import com.playtranslate.Prefs
import com.playtranslate.TranslationCache
import com.playtranslate.translation.GroupTranslator
import com.playtranslate.language.SourceLangId
import com.playtranslate.net.NetworkConnectivity
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
 * Two switches, one per route ([PtCloudConfig.ocrEnabled],
 * [PtCloudConfig.translateEnabled]), independent of each other: the OCR
 * switch decides whether a capture is read here ([refusal]), the translation
 * switch whether the cloud translates, in the waterfall
 * ([PtCloudBackend.isUsable]) and within a frame it reads
 * ([PtCloudFrameReader.read] asks for no translation while it is off). A
 * switch means what the page it sits on is about (the OCR section's row
 * reads captures; the Translation services row translates), so no surface
 * flips both at once; the first key saved turns both on ([saveClientKey]).
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
 * [setOcrEnabled] (the OCR section's row, the OCR picker's cloud entry and
 * the cloud page), [setTranslateEnabled] (the Translation services row and
 * the cloud page), [saveClientKey] and [onHealthRunning].
 *
 * The cooldown feeds the router's decision, which lives here ([refusal]) so
 * that every reader asks the same question: the router
 * ([com.playtranslate.ocr.FrameReaders]), its warm call, the floating
 * menu's header ([wouldTakeNextCapture]), and the cloud reader once more
 * before each upload ([PtCloudFrameReader.read]).
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
     * state, as a count: a switch ([setOcrEnabled], [setTranslateEnabled]),
     * the key ([saveClientKey], which can turn both switches on) and the
     * cooldown those reset, and the cooldown [onHealthRunning] drops. The
     * surfaces that show the service, the cloud page, its row on the
     * Translation services page and its row in the Capture & overlay page's
     * OCR section, repaint from it while resumed, so the mutation announces
     * itself and no caller of one has to repaint after it; and since a
     * StateFlow replays its value to a new collector, each resume repaints
     * too. A cooldown a failed call records is no gesture and is not
     * counted: it shows at the next resume, as a store instance's does.
     */
    val changes: StateFlow<Int> = changed.asStateFlow()

    /** The settings as the app stores them, built at each [init] on that
     *  init's context: the same application in production; under
     *  Robolectric each test's own, so a config read through [config] never
     *  belongs to a torn-down application's preferences. */
    @Volatile private var defaultConfig: PtCloudConfig? = null

    /** Builds the persisted cooldown and the stored settings ([config]), and
     *  keeps the application context the gestures use (the translation
     *  cache is reconciled with it). Called
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
     * cloud), the floating menu's header shows it ([wouldTakeNextCapture]),
     * and the cloud reader asks it again once the frame is encoded and
     * before the upload ([PtCloudFrameReader.read]), so a change during the
     * encode keeps that frame on the device.
     *
     * The three facts that cost nothing decide first, each ending the
     * decision when it keeps the read on the device, so a read that could
     * never go to the cloud reads nothing else, the key's AndroidKeyStore
     * decryption included: the OCR switch ([PtCloudConfig.ocrEnabled]; the
     * translation switch has no say over where a frame is read), off in
     * every release build, then the
     * caller's device-only statement (a re-OCR on a device engine the user
     * picked, and a re-read of the camera's frozen frame or the import tool's
     * page after an engine pick), then the
     * network: [internet], by default the default network as
     * [com.playtranslate.net.NetworkConnectivity.state] last heard it (an
     * in-memory read), where none ([InternetState.NONE], the app's one "no
     * internet" rule) is [CloudRefusal.OFFLINE], since a read sent then would
     * only fail its lookup and fall back. Only then are the key (whose read
     * decrypts it), the source and the cooldown read, all three, and judged
     * by [PtCloudRouting.eligible]; the first of them, in that order, that
     * refuses is the reason.
     */
    fun refusal(
        config: PtCloudConfig,
        srcId: SourceLangId,
        deviceOnly: Boolean,
        internet: InternetState = NetworkConnectivity.state.value.internet,
    ): CloudRefusal? {
        val enabled = config.ocrEnabled
        if (!enabled) return CloudRefusal.SWITCH_OFF
        if (deviceOnly) return CloudRefusal.DEVICE_ONLY
        val online = internet != InternetState.NONE
        if (!online) return CloudRefusal.OFFLINE
        val hasKey = config.hasKey
        val sourceSupported = PtCloudRouting.isSupportedSource(srcId)
        val coolingDown = isCoolingDown()
        if (PtCloudRouting.eligible(enabled, hasKey, sourceSupported, coolingDown, deviceOnly, online)) {
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
     * floating menu's header shows (wifi, or wifi_off): [refusal] finds
     * nothing against that capture's read of [srcId] on [internet], the
     * network the header follows, so the header and the router answer one
     * question, the network included (with none, [CloudRefusal.OFFLINE], and
     * no key is decrypted).
     *
     * The next capture is a one-shot's read, or, while live mode runs, the
     * running tier's next read ([com.playtranslate.CaptureService.runLiveOcrRead]);
     * neither states device-only (a re-OCR and a frozen camera or import
     * frame's re-read after an engine pick do, but re-read a frame already
     * taken), so the decision is the same for both.
     */
    fun wouldTakeNextCapture(
        config: PtCloudConfig,
        srcId: SourceLangId,
        internet: InternetState,
    ): Boolean = refusal(config, srcId, deviceOnly = false, internet) == null

    /** The OCR switch ([Prefs.ptCloudOcrEnabled]), the one mutation behind
     *  its faces: the Capture & overlay page's cloud row, the OCR picker's
     *  cloud entry (and its device engines, which turn it off), and the
     *  cloud page's Read captures row. Stores it, then resets the service
     *  with the translation cache left alone: either direction means "try
     *  it again now" for the cooldown, but which reader a capture goes to
     *  does not move the waterfall's routing identity, which keys the
     *  cache, so a frame's cached translations stay valid across the flip.
     *  The store comes first because the reset's tail re-routes a running
     *  live session and repaints the surfaces, both of which read the
     *  switch. Main thread. */
    fun setOcrEnabled(enabled: Boolean) {
        config.prefs.ptCloudOcrEnabled = enabled
        resetForUserGesture(clearTranslations = false)
    }

    /** The translation switch ([Prefs.ptCloudTranslateEnabled]), the one
     *  mutation behind its faces: the service's row on the Translation
     *  services page and the cloud page's Translate row. Stores it, then
     *  resets the service and clears the translation cache, since the flip
     *  moves the waterfall's first backend; the store comes first because
     *  the reset's reconcile reads the switch ([PtCloudBackend.isUsable]).
     *  Main thread. */
    fun setTranslateEnabled(enabled: Boolean) {
        config.prefs.ptCloudTranslateEnabled = enabled
        resetForUserGesture(clearTranslations = true)
    }

    /** Saves the client key, the cloud page's Save. The key's presence
     *  moves both switches together ([PtCloudConfig.hasKey] before and after
     *  the write): the first key turns both on, since the save is the
     *  sign-up moment and the bundle, reading and translating on the cloud,
     *  is what the user came for; a key cleared (a blank or unusable save
     *  over a usable key) turns both off, since without a key the switches
     *  are shown off and disabled everywhere and nothing may stay stored
     *  behind them. Replacing one usable key with another leaves the
     *  switches alone, so a route the user turned off stays off across a
     *  key rotation after an auth error. A write the keystore refuses keeps
     *  the old key ([Prefs.writeSecret]'s fail-closed rule), so `hasKey` is
     *  unchanged and nothing flips. Then the reset every gesture makes, the
     *  translation cache cleared with it: the old cooldown and errors
     *  describe a configuration that is gone. Main thread. */
    fun saveClientKey(key: String) {
        val prefs = config.prefs
        val hadKey = config.hasKey
        prefs.ptCloudClientKey = key
        val hasKey = config.hasKey
        if (hadKey != hasKey) {
            prefs.ptCloudOcrEnabled = hasKey
            prefs.ptCloudTranslateEnabled = hasKey
        }
        resetForUserGesture(clearTranslations = true)
    }

    /** The cloud page's health check found the service running (`GET /health`
     *  answered 200: the origin is up and its OCR ready). A cooldown whose
     *  cause that answer disproves ([disprovedByRunning]) describes a state
     *  that is over, so it is dropped, its ladders with it, and what follows
     *  every reset follows ([afterCooldownReset]): the shared cache's routing
     *  identity is reconciled, since the service is the waterfall's first
     *  backend again, and a running live session looks again now, as after a
     *  gesture. Nothing else is touched: the check takes no key and asks for
     *  no translation, so Running says nothing about a refused key, a rate
     *  limit or a spent quota, and the configuration did not change, so the
     *  translation cache's entries and the pill's errors stay. Main thread. */
    fun onHealthRunning() {
        val cause = cooldown.unavailableCause() ?: return
        if (!disprovedByRunning(cause)) return
        cooldown.resetCooldown()
        afterCooldownReset()
    }

    /**
     * Whether a health check answering Running disproves a cooldown recorded
     * for [cause], each cause decided here (a new one fails to compile until
     * it is):
     *  - NOT_RUNNING, an empty 404 (the pod stopped): yes, it runs now.
     *  - CONNECTION_FAILED, no connection or no answer in time: yes, the
     *    check just reached the service and had its answer.
     *  - SERVER_ERROR, a 500, or the edge's 502 while the origin was
     *    unreachable (the pod booting): yes, the origin answers with its OCR
     *    ready. The translator's own server_error in a final part shares the
     *    cause and is not what the check asks; dropping it costs at most one
     *    translating frame whose final part fails again and records it anew.
     *  - RATE_LIMITED, the edge's per-key limit or the translator's: no, the
     *    check takes no key and translates nothing.
     *  - DAILY_QUOTA and MONTHLY_QUOTA, the account's quota: no, for the same
     *    reason.
     *  - BILLING, an account with no membership: no, only the account fixes it.
     *  - AUTH, a refused key: no, the check takes no key.
     */
    private fun disprovedByRunning(cause: CooldownCause): Boolean = when (cause) {
        CooldownCause.NOT_RUNNING, CooldownCause.CONNECTION_FAILED, CooldownCause.SERVER_ERROR -> true
        CooldownCause.RATE_LIMITED, CooldownCause.DAILY_QUOTA, CooldownCause.MONTHLY_QUOTA,
        CooldownCause.BILLING, CooldownCause.AUTH -> false
    }

    /** Drops the cooldown and both ladders, then does what
     *  [com.playtranslate.translation.OnlineServiceMutations.saveConfig]
     *  does for a waterfall service: forget the pill's errors so a failure
     *  after the gesture shows again (both routes report under [ID], so any
     *  gesture on the service forgets them) and, with [clearTranslations],
     *  clear the translation cache (the process's one,
     *  [TranslationCache.shared], so the camera's and the import tool's
     *  translations under the old configuration go too), then what follows
     *  every reset ([afterCooldownReset]), the routing identity reconciled
     *  against the cleared state (the order
     *  [com.playtranslate.translation.OnlineServiceMutations.setEnabled]
     *  uses: the translation switch and the key decide whether the
     *  waterfall's first backend is this one). The cache is cleared by the
     *  gestures that change what a translation is made by (the translation
     *  switch, the key) and kept by the OCR switch, which changes only
     *  where a frame is read. saveConfig itself resets no cooldown: a store
     *  backend clears its own when one instance of it sees its credentials
     *  change ([com.playtranslate.translation.GeminiBackend],
     *  [com.playtranslate.translation.DeepLBackend]), while this service's
     *  one cooldown, which both routes share, is reset here, by the gesture. */
    private fun resetForUserGesture(clearTranslations: Boolean) {
        cooldown.resetCooldown()
        TranslationErrors.forget(ID)
        if (clearTranslations) GroupTranslator.clearShared()
        afterCooldownReset()
    }

    /** What follows every reset of the cooldown, a gesture's
     *  ([resetForUserGesture]) or the health check's ([onHealthRunning]),
     *  one tail for both so they cannot diverge. The shared cache's routing
     *  identity is reconciled, after the reset so that it is computed
     *  against the cleared state: the reset can make this service the
     *  waterfall's first backend again, and the identity's move clears the
     *  entries the fallback's routing made, which a frame the cache answers
     *  in full would otherwise keep showing, its cloud translations never
     *  awaited, until some text missed (the identity otherwise reconciles
     *  at the next waterfall call, the rule a cooldown's natural expiry and
     *  the registry's connectivity fan-out rely on). A running live session
     *  looks again now: the reset changes which reader the next read goes
     *  to, and the furigana tier's change gate would otherwise keep the old
     *  reader's result until the pixels move. Last, the change is counted on
     *  [changes], so the surfaces that show the service repaint. Codex
     *  review, 2026-10-22: the health check reset the cooldown and
     *  reconciled nothing. */
    private fun afterCooldownReset() {
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
    OFFLINE("no network"),
    NO_KEY("no key"),
    SOURCE("source"),
    COOLING_DOWN("cooling down"),
}
