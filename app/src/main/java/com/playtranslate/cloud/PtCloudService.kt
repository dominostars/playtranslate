package com.playtranslate.cloud

import android.content.Context
import android.os.SystemClock
import androidx.annotation.VisibleForTesting
import com.playtranslate.CaptureService
import com.playtranslate.Prefs
import com.playtranslate.PtJson
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
 * the cloud page), [saveClientKey], and the cloud page's two checks,
 * [onHealthRunning] and [onStoredKeyChecked].
 *
 * The key and the account record beside it ([account]) are written here
 * alone, by [saveClientKey] and [onStoredKeyChecked], so a record is only
 * ever written beside a key that was stored and changes with it.
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
     * Every change a gesture or the cloud page's checks make to the
     * service's state, as a count: a switch ([setOcrEnabled],
     * [setTranslateEnabled]), the key ([saveClientKey], which can turn both
     * switches on, and which a refused stored key clears) and the cooldown
     * those reset, and the cooldown [onHealthRunning] or an accepted key
     * check ([onKeyAccepted]) drops. The surfaces that show the service, the
     * cloud page, its row on the Translation services page and its row in
     * the Capture & overlay page's OCR section, repaint from it while
     * resumed, so the mutation announces itself and no caller of one has to
     * repaint after it; and since a StateFlow replays its value to a new
     * collector, each resume repaints too. A cooldown a failed call records
     * is no gesture and is not counted: it shows at the next resume, as a
     * store instance's does. Nor is a record a check rewrites with nothing
     * else changed ([onStoredKeyChecked]): the page that asked repaints from
     * its own answer, the Translation services row at its next resume, and
     * the Settings sheet observes the record's pref.
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
     *  OkHttp client and hands it to the code under test. Forgets
     *  [keyStoredAtMs], so no test inherits another's. */
    @VisibleForTesting
    @Synchronized
    fun installForTest(testState: CooldownState?, testConfig: PtCloudConfig? = null) {
        state = testState
        this.testConfig = testConfig
        keyStoredAtMs = null
    }

    /** The service's cooldown. Throws until [init] has run. */
    val cooldown: CooldownState
        get() = state ?: throw IllegalStateException("PtCloudService.init(context) was not called")

    /** The service's settings, read from [Prefs] at each use
     *  ([PtCloudConfig]), so a change applies to the next read or
     *  translation with nothing to rebuild. Throws until [init] has run. */
    val config: PtCloudConfig
        get() = testConfig ?: requireDefaultConfig()

    /** The account record beside the stored key ([PtCloudConfig.account]):
     *  null with no usable key, with none stored, or for one that does not
     *  decode, each of which every reader shows as no details. */
    val account: PtCloudAccount? get() = config.account

    /**
     * When a non-blank [saveClientKey] last stored its key
     * ([SystemClock.elapsedRealtime]), null before any in this process: the
     * moment a sign-in's key was stored, which the cloud page's check of the
     * stored key reads to retry, once, a 401 that comes within about 10 s of
     * it (a key just minted can answer 401 while the database's nearest
     * replica catches up). Kept here, in memory, rather than on the page, so
     * a recreated page or a second copy of it keeps the rule.
     */
    @Volatile var keyStoredAtMs: Long? = null
        private set

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

    /**
     * Saves the client key, with the account record beside it: a sign-in's
     * key with the record of its verify, the cloud page's Save with a key the
     * server accepted ([PtCloudClient.checkKey]; the page stores no other)
     * and the record of that check, or a blank key, which clears the key and
     * the record together (Sign out, a blank Save, a stored key the server no
     * longer accepts). Returns whether the key asked for is the key stored
     * now ([PtCloudConfig.clientKey] read back; for a blank key, that none
     * is): a write the keystore refuses keeps the old key
     * ([Prefs.writeSecret]'s fail-closed rule), and a sign-in's key cannot
     * be fetched again, so the caller draws a signed-in state only on true.
     * Only a key that was stored gets [account] as its record (null clears
     * the old one: a record never outlives the key it describes) and, when
     * not blank, stamps [keyStoredAtMs]; a refused write leaves both as
     * they were, beside the old key.
     *
     * The key's presence moves both switches together ([PtCloudConfig.hasKey]
     * before and after the write): the first key turns both on, since the
     * save is the sign-up moment and the bundle, reading and translating on
     * the cloud, is what the user came for (a sign-in's key is a first key
     * like a pasted one); a key cleared (a blank or unusable save over a
     * usable key) turns both off, since without a key the switches are shown
     * off and disabled everywhere and nothing may stay stored behind them.
     * Replacing one usable key with another leaves the switches alone, so a
     * route the user turned off stays off across a key rotation after an
     * auth error. A refused write leaves `hasKey` unchanged, so nothing
     * flips. Then the reset every gesture makes, the translation cache
     * cleared with it: the old cooldown and errors describe a configuration
     * that is gone. Main thread.
     */
    fun saveClientKey(key: String, account: PtCloudAccount? = null): Boolean {
        val prefs = config.prefs
        val hadKey = config.hasKey
        prefs.ptCloudClientKey = key
        val asked = key.trim()
        val stored = if (asked.isEmpty()) !config.hasKey else config.clientKey == asked
        if (stored) {
            writeAccount(account.takeIf { asked.isNotEmpty() })
            if (asked.isNotEmpty()) keyStoredAtMs = SystemClock.elapsedRealtime()
        }
        val hasKey = config.hasKey
        if (hadKey != hasKey) {
            prefs.ptCloudOcrEnabled = hasKey
            prefs.ptCloudTranslateEnabled = hasKey
        }
        resetForUserGesture(clearTranslations = true)
        return stored
    }

    /**
     * The cloud page's check of the stored key ([PtCloudClient.checkKey], on
     * a resume or Check again) answered [check] for [checkedKey], the key the
     * page read and sent. It acts only while [checkedKey] is a usable key
     * ([PtCloudConfig.isUsableKey]) and still the stored one: a sign-out or
     * another copy of the page can change the key during the round trip,
     * and the answer describes a key that is gone; and a stored key that
     * read back unusable (a decryption the keystore refused this once reads
     * as blank) was never the key checked, so no answer for it, a
     * [KeyCheck.Refused] least of all, may touch the key that is stored.
     * Then, each answer decided here:
     *  - [KeyCheck.Accepted] and [KeyCheck.Inactive]: the record is rewritten
     *    from the answer ([PtCloudAccount.of]), with the email the record had
     *    (a check does not carry it, and a pasted key's record has none); an
     *    Accepted then clears the account cooldown it disproves
     *    ([onKeyAccepted]).
     *  - [KeyCheck.Refused], the HTTP 401 and the one answer that clears a
     *    stored key: the key and the record are cleared as a sign-out clears
     *    them ([saveClientKey] with a blank key), both switches off.
     *  - [KeyCheck.Closed] and [KeyCheck.NotChecked]: nothing; the record
     *    keeps what it last knew.
     * Main thread.
     */
    fun onStoredKeyChecked(checkedKey: String, check: KeyCheck) {
        if (!PtCloudConfig.isUsableKey(checkedKey) || config.clientKey != checkedKey) return
        when (check) {
            is KeyCheck.Accepted -> {
                rewriteAccount(check)
                onKeyAccepted()
            }
            is KeyCheck.Inactive -> rewriteAccount(check)
            KeyCheck.Refused -> saveClientKey("")
            is KeyCheck.Closed, KeyCheck.NotChecked -> Unit
        }
    }

    /**
     * A check of the stored key was accepted: the account behind it is
     * entitled now. A cooldown whose cause that answer disproves
     * ([clearedByAcceptedKey]: a refused key, an account with no membership)
     * describes a state that is over; this is the way out of the ladder a
     * 403 records ([CooldownAction.NoMembershipLadder]) once the member has
     * joined. It is reset as a gesture resets it ([resetForUserGesture]: the
     * cooldown and its ladders dropped, the pill's errors forgotten, the
     * routing identity reconciled, the change counted on [changes]), with
     * the translation cache kept, since the configuration did not change.
     * Any other cooldown stays: a key check says nothing about a rate limit,
     * a quota, or a service that is down or closed. Main thread.
     */
    fun onKeyAccepted() {
        val cause = cooldown.unavailableCause() ?: return
        if (!clearedByAcceptedKey(cause)) return
        resetForUserGesture(clearTranslations = false)
    }

    /**
     * Whether a check that accepted the stored key disproves a cooldown
     * recorded for [cause], each cause decided here (a new one fails to
     * compile until it is):
     *  - AUTH, a refused key: yes, the edge accepts it now.
     *  - BILLING, an account with no membership: yes, the check's 200 says
     *    the account is entitled now.
     *  - RATE_LIMITED, DAILY_QUOTA and MONTHLY_QUOTA: no, the check spends no
     *    budget and says nothing of the limits.
     *  - SERVER_ERROR, CONNECTION_FAILED and NOT_RUNNING: no, the edge
     *    answers the check alone, whatever the state of the service behind
     *    it.
     *  - CLOSED: no; a closed service answers the check 410, and only a
     *    Running health check says it reopened ([disprovedByRunning]).
     */
    private fun clearedByAcceptedKey(cause: CooldownCause): Boolean = when (cause) {
        CooldownCause.AUTH, CooldownCause.BILLING -> true
        CooldownCause.RATE_LIMITED, CooldownCause.DAILY_QUOTA, CooldownCause.MONTHLY_QUOTA,
        CooldownCause.SERVER_ERROR, CooldownCause.CONNECTION_FAILED, CooldownCause.NOT_RUNNING,
        CooldownCause.CLOSED -> false
    }

    /** The record rewritten from [answer], beside the stored key, keeping
     *  the email the record had. */
    private fun rewriteAccount(answer: KeyCheck.Account) =
        writeAccount(PtCloudAccount.of(answer, config.account?.email, System.currentTimeMillis()))

    /** [account] stored as the record ([Prefs.ptCloudAccountJson]), or the
     *  record cleared for null. Only [saveClientKey] and
     *  [onStoredKeyChecked] reach it, beside a key that is stored. */
    private fun writeAccount(account: PtCloudAccount?) {
        config.prefs.ptCloudAccountJson = account?.let { PtJson.lenient.encodeToString(PtCloudAccount.serializer(), it) }
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
     *  - NOT_RUNNING, an empty 404 (the pod stopped) or a 502 (the edge could
     *    not reach the host, the pod booting say): yes, it runs now.
     *  - CONNECTION_FAILED, no connection or no answer in time: yes, the
     *    check just reached the service and had its answer.
     *  - SERVER_ERROR, a 500, or a frame's 502 `ocr_unavailable`: yes, the
     *    origin answers with its OCR ready. The translator's own server_error
     *    in a final part shares the cause and is not what the check asks;
     *    dropping it costs at most one translating frame whose final part
     *    fails again and records it anew.
     *  - CLOSED, a 410 `service_closed`: yes, the service is open again (a
     *    closed service answers `/health` 410 too).
     *  - RATE_LIMITED, the edge's per-key limit or the translator's: no, the
     *    check takes no key and translates nothing.
     *  - DAILY_QUOTA and MONTHLY_QUOTA, the account's quota: no, for the same
     *    reason.
     *  - BILLING, an account with no membership: no, only the account fixes it.
     *  - AUTH, a refused key: no, the check takes no key.
     */
    private fun disprovedByRunning(cause: CooldownCause): Boolean = when (cause) {
        CooldownCause.NOT_RUNNING, CooldownCause.CONNECTION_FAILED, CooldownCause.SERVER_ERROR,
        CooldownCause.CLOSED -> true
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
