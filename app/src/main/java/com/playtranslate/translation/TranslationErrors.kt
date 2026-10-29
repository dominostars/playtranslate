package com.playtranslate.translation

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.annotation.VisibleForTesting
import com.playtranslate.cloud.PtCloudService
import com.playtranslate.net.NetworkConnectivity
import com.playtranslate.net.NetworkConnectivity.InternetState

/**
 * Identity of one error the pill can show: whose it is ([owner]: a service
 * id, or [CONNECTION_OWNER]) and what went wrong ([kind]: a
 * [BackendFailureKind] name, or [CONNECTION_KIND]). "The same error" in
 * the once-per-outage rule means the same key.
 */
data class TranslationErrorKey(val owner: String, val kind: String) {

    companion object {
        /** Owner of the connection error. Service ids are UUIDs or the
         *  legacy names ("gemini", "deepl"), so a leading "~" can't clash. */
        const val CONNECTION_OWNER = "~connection"
        const val CONNECTION_KIND = "CONNECTION"
    }
}

/** One error, as the pill words it. */
sealed interface TranslationError {
    val key: TranslationErrorKey

    /** The device can't reach the internet. Every online service is
     *  affected, so the pill names none. */
    data object Connection : TranslationError {
        override val key = TranslationErrorKey(
            TranslationErrorKey.CONNECTION_OWNER, TranslationErrorKey.CONNECTION_KIND,
        )
    }

    /** One service failed for a reason of its own. */
    data class Service(
        val serviceId: BackendId,
        /** The user-facing name ([TranslationBackend.displayName]). */
        val serviceName: String,
        val failure: BackendFailure,
    ) : TranslationError {
        override val key: TranslationErrorKey
            get() = TranslationErrorKey(serviceId, failure.kind.name)
    }
}

/**
 * The UI side of the pill: shows, rewords and hides it. Main thread.
 */
interface TranslationErrorPresenter {
    /** Put [error]'s pill on screen, or reword its owner's pill if one is
     *  up. True when it is on screen afterwards (false only when no overlay
     *  window can be added); only then does the error count as shown. */
    fun show(error: TranslationError): Boolean

    /** If [error]'s owner has a pill up, make it say [error]; otherwise
     *  nothing. Not a showing: no new pill, whatever the display is doing,
     *  just the words of one the user can already see. */
    fun update(error: TranslationError)

    /** Take down the pills for [keys]: their service works again. Keys with
     *  no pill up are ignored. */
    fun hide(keys: Collection<TranslationErrorKey>)
}

/**
 * Decides which translation errors reach the user as a pill, from each
 * waterfall pass's online outcomes ([OnlineAttempt]). Rules, as decided on
 * 2026-09-26:
 *
 *  - **Once per outage.** After an error's pill has appeared, the same error
 *    (same [TranslationErrorKey]) stays quiet until its owner has recovered
 *    STEADILY and then fails again. Steadily means: a success after its last
 *    failure, then no failure of it for [STEADY_RECOVERY_MS]. So a service
 *    that keeps flipping between working and failing (Gemini's free tier
 *    under auto-translate, flaky wifi) shows its pill once, and a real fix
 *    re-arms it. A different kind from the same service is a different
 *    error and shows. A pill still up says its owner's newest failure, even
 *    one already seen this outage.
 *  - **Per capture session.** When capture ends (Turn Off, MediaProjection
 *    not turned on, the capture backend switching or torn down), everything
 *    here is forgotten, as at an app restart ([reset]): an error still in
 *    effect shows once in the next session. Without this, a service that
 *    never works again (out of credits, a bad key) would stay silent for the
 *    rest of the process. Hide for Now is not an end: capture carries on
 *    without the floating icons, and so do the pills.
 *  - **Hidden on recovery.** A pill comes down the moment its owner works
 *    again: the service's own success, or for the connection's, any server
 *    answering at all, even with an error (a 429 proves the network works).
 *    The × closes it any time. Either way the error still counts as shown.
 *  - **The gear tries again.** The pill's gear closes it and opens the
 *    Translation services page (2026-09-28): its owner's errors stop
 *    counting as shown, so its next failure shows, even the same one
 *    ([settingsOpened]).
 *  - **Shown means on screen.** A pill shows whenever there's an error,
 *    whatever is in front (2026-09-27), and an error counts as shown
 *    only once [TranslationErrorPresenter.show] actually put it up. A pill
 *    taken down because its display left the capture set ([withdrawn]) stops
 *    counting: nobody closed it, so it shows again at its next failure.
 *  - **Transport failures** ([BackendFailureKind.isTransport]) belong to the
 *    connection when no service reached a server in the pass and the
 *    network is missing or not validated (airplane mode, a captive portal,
 *    wifi without internet); otherwise to the service. A pass that reached
 *    a server never blames the connection, even if the network reads as
 *    gone by the time the pass is judged: the answer is the fresher proof.
 *    The network is read when the report arrives, so it only speaks for a
 *    request nothing newer is known about: a transport failure whose pass
 *    reached no server, reported after a request sent later reached a
 *    server or failed on the connection, is the connection's (a request
 *    that hung through an outage and reported once it was over). Every
 *    failure shows, the first one included (2026-09-27): a one-off
 *    hiccup's pill comes down at the next success anyway.
 *
 * **Evidence is dated by when its request went out** ([OnlineAttempt.sentAtMs]),
 * never by when it arrived: passes overlap, and a slow request reports after
 * a newer one. So an owner's pill follows its newest request, whatever order
 * the reports come in: a failure puts the pill up or rewords it only if
 * nothing sent after it has reported (a success or another failure), and a
 * success takes the pill down only if no failure was sent after it. The
 * steady clock runs on the same dates. A
 * report older than what it would speak for is dropped:
 *  - sent before the capture session's history began ([reset]);
 *  - about a service, sent before the user last changed it ([forget]): the
 *    request was made with the key, model or address they replaced. Its
 *    server answer still proves the connection worked; nothing else about
 *    it counts.
 *
 * And a failure sent before the user last tapped its owner's gear
 * ([settingsOpened]) can't put the pill back up: it was already out when
 * they tapped, so it isn't the next try. It still counts as a failure.
 *
 * Nothing is persisted (2026-09-26): a fresh process starts with no
 * history, so an error still in effect after a restart shows again at its
 * next failure.
 *
 * Main thread only; [TranslationErrors] posts every pass here.
 */
class TranslationErrorTracker(
    /** The clock [OnlineAttempt.sentAtMs] is on
     *  ([android.os.SystemClock.elapsedRealtime] in the app). Read only to
     *  date a [forget], a [reset] or a [settingsOpened]. */
    private val now: () -> Long,
    private val internet: () -> InternetState,
    /** Whether a service still exists and is switched on, read as its
     *  report arrives. A request that went out just after the user deleted
     *  or switched off its service, from a pass that had already picked its
     *  services, reports afterwards; that service won't be asked again, so
     *  no success of it could ever take the pill down. PlayTranslate Cloud
     *  is active while a key is stored, whatever its switches say
     *  ([TranslationErrors.install]). */
    private val isActive: (BackendId) -> Boolean = { true },
) {
    var presenter: TranslationErrorPresenter? = null

    /** When the current session's history began ([reset]). */
    private var sessionStartedAt = Long.MIN_VALUE

    /** Everything known about one owner (a service, or the connection) this
     *  session, in send times. One record per owner, so [forget], [reset],
     *  [withdrawn] and [settingsOpened] act on the whole of it and nothing
     *  about an owner can be left behind in a map of its own. */
    private class Owner(
        /** When the user last changed this service ([forget]). Its reports of
         *  requests sent before then are dropped: those went out with what
         *  the user replaced. */
        val forgottenAt: Long = Long.MIN_VALUE,
    ) {
        /** Its newest request that worked (for the connection: the newest
         *  that reached a server). */
        var workedAt = Long.MIN_VALUE

        /** Its newest failure. A success sent after it takes the pill down. */
        var failedAt = Long.MIN_VALUE

        /** While it has a shown error and no failure since: when its current
         *  run of health began (its first success after its last failure). */
        var recoveredAt: Long? = null

        /** The kinds of its errors whose pill has appeared in its current
         *  outage. */
        val shown = LinkedHashSet<String>()

        /** When the user last tapped the gear on its pill ([settingsOpened]).
         *  Its failures sent before then can't put a pill up. */
        var settingsOpenedAt = Long.MIN_VALUE
    }

    private val owners = HashMap<String, Owner>()

    private fun owner(id: String): Owner = owners.getOrPut(id) { Owner() }

    private fun keys(id: String, o: Owner) = o.shown.map { TranslationErrorKey(id, it) }

    fun onPass(reported: List<OnlineAttempt>) {
        val attempts = reported.filter { it.sentAtMs > sessionStartedAt }
        if (attempts.isEmpty()) return
        // A server answered in this pass (a translation, or an error reply
        // such as a 429): the connection worked when that request went out,
        // whatever else failed, and whatever the user has done to the
        // service since.
        val reachedAt = attempts.filter { it.reachedServer }.maxOfOrNull { it.sentAtMs }
        if (reachedAt != null) worked(TranslationErrorKey.CONNECTION_OWNER, reachedAt)
        var net: InternetState? = null
        val network = { net ?: internet().also { net = it } }
        for (attempt in attempts) {
            val id = attempt.serviceId
            if (attempt.sentAtMs <= (owners[id]?.forgottenAt ?: Long.MIN_VALUE) || !isActive(id)) continue
            val failure = attempt.failure
            if (failure == null) {
                worked(id, attempt.sentAtMs)
                continue
            }
            val error = attribute(attempt, failure, passReachedServer = reachedAt != null, network)
            if (failed(error.key.owner, attempt.sentAtMs)) surface(error, attempt.sentAtMs)
        }
    }

    /**
     * Whose failure [attempt]'s is: the connection's or its service's. Decided
     * here, once; every rule after it (ordering, the steady clock, once per
     * outage) reads only the owner this returns.
     */
    private fun attribute(
        attempt: OnlineAttempt,
        failure: BackendFailure,
        passReachedServer: Boolean,
        network: () -> InternetState,
    ): TranslationError {
        val service = TranslationError.Service(attempt.serviceId, attempt.serviceName, failure)
        // The service's own answer (a 429, out of credits) is the service's,
        // and so is any failure in a pass that reached a server: the
        // connection worked.
        if (!failure.kind.isTransport || passReachedServer) return service
        // What the network reads now speaks for this request only if nothing
        // newer is known about the connection: a request that hung while the
        // network was down can report after it came back. If a request sent
        // later reached a server or failed on the connection, this failure
        // can't be pinned on the service; it goes to the connection and is
        // judged against that news.
        val connection = owners[TranslationErrorKey.CONNECTION_OWNER]
        val laterNews = connection != null && maxOf(connection.workedAt, connection.failedAt) > attempt.sentAtMs
        return if (laterNews || network() != InternetState.VALIDATED) TranslationError.Connection else service
    }

    /** The user asked to try [serviceId] again (services-page toggle, config
     *  save, model pick) or deleted it: forget its errors, so a failure from
     *  here on shows even if it's the same one, and take its pill down.
     *  Reports of its requests sent before now are dropped when they
     *  arrive, since those went out with what the user just replaced. */
    fun forget(serviceId: String) {
        val old = owners.put(serviceId, Owner(forgottenAt = now())) ?: return
        if (old.shown.isEmpty()) return
        Log.i(TAG, "forget $serviceId: ${old.shown.joinToString()}")
        presenter?.hide(keys(serviceId, old))
    }

    /** The capture session ended: forget everything, as an app restart
     *  would, so the next session shows any error still in effect once.
     *  Reports of requests sent before now are dropped when they arrive.
     *  The pills themselves are already down (the session's teardown took
     *  them). */
    fun reset() {
        sessionStartedAt = now()
        val shownCount = owners.values.sumOf { it.shown.size }
        if (shownCount > 0) Log.i(TAG, "capture session ended: forgot $shownCount shown error(s)")
        owners.clear()
    }

    /** The pills of [ownerIds] came down without the user closing them and
     *  without their owners recovering: their display left the capture set.
     *  Their errors no longer count as shown, so each owner's next failure
     *  shows again. */
    fun withdrawn(ownerIds: Collection<String>) {
        var count = 0
        for (id in ownerIds) {
            val o = owners[id] ?: continue
            count += o.shown.size
            o.shown.clear()
            o.recoveredAt = null
        }
        if (count > 0) Log.i(TAG, "pills withdrawn with their display: $count error(s) may show again")
    }

    /** The user tapped the gear on [ownerId]'s pill, which took the pill
     *  down and opened the Translation services page (2026-09-28):
     *  its errors no longer count as shown, so its next failure shows
     *  again, even the same one. Only a failure sent from now on can put
     *  the pill back up; one already out when the user tapped (the last
     *  pass of the auto-translate the gear stopped, say) is not the next
     *  try. Everything else known about the owner is still true, so it
     *  stays: when it last worked and failed, which orders its later
     *  reports and, for the connection, decides whose a transport failure
     *  is ([attribute]). */
    fun settingsOpened(ownerId: String) {
        val o = owner(ownerId)
        Log.i(TAG, "settings opened from $ownerId's pill: its next failure shows")
        o.shown.clear()
        o.recoveredAt = null
        o.settingsOpenedAt = now()
    }

    /** [error], sent at [sentAt], is its owner's newest news: put its pill
     *  up, or reword the one that is up. */
    private fun surface(error: TranslationError, sentAt: Long) {
        val presenter = presenter ?: return
        val o = owner(error.key.owner)
        if (error.key.kind !in o.shown && sentAt > o.settingsOpenedAt && presenter.show(error)) {
            // Content-free: service name and kind only.
            Log.i(TAG, "pill shown: ${describe(error)}")
            o.shown += error.key.kind
        } else {
            // Seen this outage, sent before the user last tapped the owner's
            // gear, or no pill could go up: no new pill, but one still up
            // for this owner (left there by another of its errors) says this
            // one, its newest.
            presenter.update(error)
        }
    }

    private fun describe(error: TranslationError): String = when (error) {
        TranslationError.Connection -> "connection"
        is TranslationError.Service ->
            "${error.serviceName} ${error.failure.kind}" +
                (error.failure.httpCode?.let { " http=$it" } ?: "")
    }

    /**
     * Owner [id] failed on a request sent at [t]. A run of health that began
     * before [t] ended there: one that lasted [STEADY_RECOVERY_MS] ended the
     * outage its shown errors belong to, so they may show again. Returns
     * whether [t] is the owner's newest news, the only time its pill may go
     * up or change: a failure that arrives after a success sent later, or
     * after another failure sent later (overlapping requests failing two
     * ways), changes nothing on screen.
     */
    private fun failed(id: String, t: Long): Boolean {
        val o = owner(id)
        val newest = t > o.workedAt && t >= o.failedAt
        o.failedAt = maxOf(o.failedAt, t)
        val since = o.recoveredAt
        if (since != null && t > since) {
            if (t - since >= STEADY_RECOVERY_MS) {
                Log.i(TAG, "$id failed after ${(t - since) / 1000}s of health: its errors may show again")
                o.shown.clear()
                o.recoveredAt = null
            } else {
                // A success sent after this failure (its report came first)
                // began the run again. Exactly when is not known; the newest
                // success is the latest it can be, the strict choice.
                o.recoveredAt = if (o.workedAt > t) o.workedAt else null
            }
        }
        return newest
    }

    /**
     * Owner [id] worked on a request sent at [t]. If no failure was sent
     * after [t], its pill comes down and a run of health is under way from
     * [t] at the latest. Nothing to do for an owner with no shown error.
     */
    private fun worked(id: String, t: Long) {
        val o = owner(id)
        o.workedAt = maxOf(o.workedAt, t)
        if (o.shown.isEmpty() || t < o.failedAt) return
        if (o.recoveredAt == null) Log.i(TAG, "$id works again: steady clock started")
        o.recoveredAt = minOf(o.recoveredAt ?: t, t)
        presenter?.hide(keys(id, o))
    }

    companion object {
        private const val TAG = "TranslationErrors"

        /** How long an owner must work without a failure before its shown
         *  errors may show again (2026-09-26: five minutes). */
        const val STEADY_RECOVERY_MS = 5 * 60_000L
    }
}

/**
 * The process-wide wiring: one [TranslationErrorTracker], fed every
 * waterfall pass by [TranslationBackendRegistry.onlineAttemptListener]
 * (posted to the main thread, where the tracker lives) and presenting
 * through the presenter [install] was given.
 */
object TranslationErrors {

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    @Volatile private var tracker: TranslationErrorTracker? = null

    /** Wire once from Application.onCreate, after the registry exists. */
    fun install(context: Context, presenter: TranslationErrorPresenter) {
        val app = context.applicationContext
        val t = TranslationErrorTracker(
            // The registry stamps sentAtMs on this clock.
            now = SystemClock::elapsedRealtime,
            internet = { NetworkConnectivity.internetState(app) },
            // Read on the main thread, where OnlineServiceMutations deletes
            // and toggles. Every online backend is built from a store
            // instance; PlayTranslate Cloud, which reports without being one,
            // is active while a usable key is stored, read through the
            // service's own settings: its frame route and its translate route
            // report under the one id, and outside debug builds both switches
            // read off, so nothing of it reports. The key, not the switches
            // (2026-10-29): a user's switch-off still drops the reports of
            // requests sent before it, since every gesture on the service
            // forgets it first (PtCloudService.resetForUserGesture calls
            // forget), while the service's own switch-off at an ended
            // entitlement forgets nothing, and the attempt that found the
            // ending, whose report can arrive after that switch-off, is the
            // one pill the user gets.
            isActive = {
                OnlineServiceStore.byId(it)?.enabled == true ||
                    (it == PtCloudService.ID && PtCloudService.config.hasKey)
            },
        )
        t.presenter = presenter
        install(t)
    }

    /** Make [t] the process's tracker and feed it every pass; [install]
     *  builds the real one, tests their own. */
    @VisibleForTesting
    internal fun install(t: TranslationErrorTracker) {
        tracker = t
        TranslationBackendRegistry.onlineAttemptListener = { attempts ->
            mainHandler.post { t.onPass(attempts) }
        }
    }

    /** See [TranslationErrorTracker.forget]. Main thread. No-op before
     *  [install] (unit tests). */
    fun forget(serviceId: String) {
        tracker?.forget(serviceId)
    }

    /** Capture ended (the floating icons went away, or the capture backend
     *  switched): see [TranslationErrorTracker.reset]. Main thread. */
    fun onCaptureSessionEnded() {
        tracker?.reset()
    }

    /** Pills came down with their display: see
     *  [TranslationErrorTracker.withdrawn]. Main thread. */
    fun onPillsWithdrawn(owners: List<String>) {
        tracker?.withdrawn(owners)
    }

    /** The user tapped the gear on [owner]'s pill: see
     *  [TranslationErrorTracker.settingsOpened]. Main thread. */
    fun onPillSettingsOpened(owner: String) {
        tracker?.settingsOpened(owner)
    }

    /** Test hook: undo [install]. */
    @VisibleForTesting
    internal fun resetForTest() {
        tracker = null
        TranslationBackendRegistry.onlineAttemptListener = null
    }
}
