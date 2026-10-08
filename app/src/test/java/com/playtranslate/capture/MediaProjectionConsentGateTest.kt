package com.playtranslate.capture

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Looper
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import com.playtranslate.CaptureService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.android.controller.ServiceController
import org.robolectric.shadows.ShadowActivity
import org.robolectric.shadows.ShadowSettings
import java.time.Duration

/**
 * The consent gate and the one activity that answers it
 * ([MediaProjectionController.ensureConsent] / [MediaProjectionConsentActivity]).
 *
 * Field case (Moto, 2026-10-08): the consent activity was stopped behind a
 * Settings page, the system dialog finished with a cancel the platform parked
 * for a resume that never came, and every later Turn On joined the pending
 * gate and did nothing until the process died. The rules pinned here:
 *
 *  - a request joins the gate while its activity is on screen or its launch
 *    is still in flight (one dialog at a time);
 *  - a request that finds the activity hidden with no result supersedes it:
 *    the waiting caller gets false, the stale instance is abandoned and a
 *    fresh prompt opens, and nothing the stale instance reports later
 *    reaches the new request;
 *  - an instance finished with no result ends its request as a cancel; one
 *    destroyed without finishing (a configuration-change relaunch, a memory
 *    reclaim) keeps it for its recreation to answer, and a request arriving
 *    before that recreation supersedes it; an instance with no request to
 *    answer finishes without prompting;
 *  - an instance answers only the request it was launched for: a superseded
 *    record the platform recreates cannot take the replacement request,
 *    whichever reaches onCreate first, nor a later controller's request
 *    after the service was stopped and started again around it;
 *  - stopped alone is not cancelled: the single-app picker stops the
 *    activity before force-delivering its grant.
 *
 * No real projection is involved: a grant is an Intent handed to the
 * activity's result registry the way the system dialog's result arrives
 * (onActivityResult; the AndroidX registry holds it for a stopped activity
 * until it starts, as the platform's forced delivery starts it).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class MediaProjectionConsentGateTest {

    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val app get() = ctx as Application
    private var service: ServiceController<CaptureService>? = null
    private lateinit var svc: CaptureService
    private lateinit var mp: MediaProjectionController
    private val scope = CoroutineScope(Dispatchers.Main)
    private val consentClass = MediaProjectionConsentActivity::class.java.name

    @Before
    fun setUp() {
        Settings.Secure.putString(
            ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, "",
        )
        ShadowSettings.setCanDrawOverlays(true)
        CaptureBackendResolver.reresolve(ctx)
        assertFalse(CaptureBackendResolver.active().requiresAccessibilityService)
        val c = Robolectric.buildService(CaptureService::class.java).create()
        service = c
        svc = c.get()
        mp = svc.mediaProjectionController
        // Robolectric's ActivityManager lists no tasks; model a platform
        // that creates a task for every launch unless a test says otherwise.
        acceptingPlatform()
    }

    private fun advanceClock(ms: Long) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    }

    /** The task census as the platform would answer it: the controller reads
     *  it before and after each launch, and an accepting platform has
     *  created one more consent task by the second read. */
    private fun acceptingPlatform(existing: Set<Int> = emptySet()) {
        val ids = existing.toMutableSet()
        var reads = 0
        mp.consentTaskIdsProbe = {
            if (reads++ % 2 == 1) ids += (ids.maxOrNull() ?: 0) + 1
            ids.toSet()
        }
    }

    /** A platform that refuses every launch: the census never changes,
     *  whatever consent tasks it already holds. */
    private fun refusingPlatform(existing: Set<Int> = emptySet()) {
        mp.consentTaskIdsProbe = { existing }
    }

    @After
    fun tearDown() {
        service?.destroy()
        service = null
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    /** A consent request the way the tile and Turn On make one: on the main
     *  dispatcher, awaiting the gate. */
    private fun request(): Deferred<Boolean> = scope.async { mp.ensureConsent() }.also { idle() }

    /** Every activity launch since the last drain, oldest first. Robolectric
     *  hands them out newest first and includes launches for result. */
    private fun drainLaunches(): List<Intent> {
        val out = mutableListOf<Intent>()
        while (true) out += shadowOf(app).nextStartedActivity ?: break
        return out.asReversed()
    }

    /** The consent-activity launches since the last drain. */
    private fun consentLaunches(): List<Intent> =
        drainLaunches().filter { it.component?.className == consentClass }

    /** The one consent launch the controller just made. */
    private fun theConsentLaunch(): Intent = consentLaunches().single()

    /** The system creates the launched activity with its launch intent; its
     *  onCreate binds it to the request the intent names. [shown] = started
     *  and resumed, as under the translucent system dialog. */
    private fun createConsentActivity(
        launch: Intent,
        shown: Boolean = true,
    ): ActivityController<MediaProjectionConsentActivity> {
        val c = Robolectric.buildActivity(MediaProjectionConsentActivity::class.java, launch).create()
        if (shown) c.start().resume()
        idle()
        return c
    }

    /** The system dialog's launch, as the activity's result launcher made it:
     *  exactly one launch, for result, and not of the consent activity. */
    private fun dialogRequest(
        c: ActivityController<MediaProjectionConsentActivity>,
    ): ShadowActivity.IntentForResult {
        val forResult = shadowOf(c.get()).nextStartedActivityForResult
        assertNotNull("the consent activity must have launched the system dialog", forResult)
        val launches = drainLaunches()
        assertEquals("one launch: the system dialog", 1, launches.size)
        assertFalse(launches.single().component?.className == consentClass)
        return forResult
    }

    /** The dialog's answer arriving at the activity (onActivityResult). */
    private fun answer(
        c: ActivityController<MediaProjectionConsentActivity>,
        dialog: ShadowActivity.IntentForResult,
        granted: Boolean,
    ) {
        c.get().activityResultRegistry.dispatchResult(
            dialog.requestCode,
            if (granted) Activity.RESULT_OK else Activity.RESULT_CANCELED,
            if (granted) Intent() else null,
        )
        idle()
    }

    /** A stopped instance destroyed without finishing: the platform
     *  reclaimed it for memory and keeps its record and saved state. */
    private fun reclaim(
        activity: ActivityController<MediaProjectionConsentActivity>,
    ): Bundle {
        val saved = Bundle()
        activity.pause().stop().saveInstanceState(saved).destroy()
        idle()
        assertFalse(activity.get().isFinishing)
        return saved
    }

    @Test
    fun dialogOnScreen_laterRequestJoinsIt_andOneGrantAnswersBoth() {
        val first = request()
        val activity = createConsentActivity(theConsentLaunch(), shown = true)
        val dialog = dialogRequest(activity)

        val second = request()
        assertTrue("the dialog on screen is the prompt; no second launch", consentLaunches().isEmpty())
        assertFalse(first.isCompleted)
        assertFalse(second.isCompleted)

        answer(activity, dialog, granted = true)
        assertTrue(first.getCompleted())
        assertTrue(second.getCompleted())
        assertTrue(mp.hasConsent)
        assertTrue("a grant on the MediaProjection backend is Turn On", svc.mediaProjectionActivated)
        assertTrue(activity.get().isFinishing)
    }

    @Test
    fun launchInFlight_laterRequestJoins_noSecondLaunch() {
        val first = request()
        theConsentLaunch()
        // The activity has not reached onCreate yet.
        val second = request()
        assertTrue("a launch in flight is joined, not doubled", consentLaunches().isEmpty())
        assertFalse(first.isCompleted)
        assertFalse(second.isCompleted)
    }

    @Test
    fun dialogHidden_laterRequestSupersedesIt() {
        val first = request()
        val stale = createConsentActivity(theConsentLaunch(), shown = true)
        val staleDialog = dialogRequest(stale)
        // Another window covers the consent task: the system dialog finishes
        // with a cancel the platform parks until this activity resumes.
        stale.pause().stop()
        idle()
        assertTrue(stale.get().hidden)
        assertFalse("hidden alone ends nothing", first.isCompleted)

        val second = request()
        assertTrue("the superseded caller is released", first.isCompleted)
        assertFalse(first.getCompleted())
        assertFalse(second.isCompleted)
        val freshLaunch = theConsentLaunch()
        assertTrue("the stale instance is abandoned", stale.get().isFinishing)

        // A grant the stale instance's dialog still delivers (the picker's
        // forced delivery racing the supersede) belongs to no request.
        answer(stale, staleDialog, granted = true)
        stale.start().resume()
        idle()
        assertFalse("a late grant from an abandoned instance is dropped", second.isCompleted)
        assertFalse(mp.hasConsent)

        // The stale instance's late end reaches no request either.
        stale.pause().stop().destroy()
        idle()
        assertFalse(second.isCompleted)

        val fresh = createConsentActivity(freshLaunch, shown = true)
        val dialog = dialogRequest(fresh)
        answer(fresh, dialog, granted = true)
        assertTrue(second.getCompleted())
        assertTrue(mp.hasConsent)
    }

    @Test
    fun finishedWithoutResult_endsTheRequestAsCancel() {
        val first = request()
        val activity = createConsentActivity(theConsentLaunch(), shown = true)
        dialogRequest(activity)
        // The system finished the stopped record (a task removal): no
        // result will ever reach it.
        activity.get().finish()
        activity.pause().stop().destroy()
        idle()
        assertTrue(first.isCompleted)
        assertFalse(first.getCompleted())
        assertFalse(mp.hasConsent)

        // The next request prompts again rather than joining a dead gate.
        val second = request()
        theConsentLaunch()
        assertFalse(second.isCompleted)
    }

    @Test
    fun reclaimedInstance_keepsTheRequest_andTheNextRequestSupersedesIt() {
        val first = request()
        val activity = createConsentActivity(theConsentLaunch(), shown = true)
        dialogRequest(activity)
        reclaim(activity)
        assertFalse("a reclaim is not a cancel", first.isCompleted)

        val second = request()
        assertTrue(first.isCompleted)
        assertFalse(first.getCompleted())
        assertFalse(second.isCompleted)
        theConsentLaunch() // nothing can answer the dormant gate; a fresh prompt opens
    }

    @Test
    fun reclaimedInstance_recreationBindsAndAnswersTheRequest() {
        val first = request()
        val launch = theConsentLaunch()
        val activity = createConsentActivity(launch, shown = true)
        val dialog = dialogRequest(activity)
        val saved = reclaim(activity)

        // The task comes forward: the platform recreates the instance from
        // its saved state, with its launch intent, and delivers the parked
        // result.
        val recreated = Robolectric.buildActivity(MediaProjectionConsentActivity::class.java, launch)
            .create(saved).start().resume()
        idle()
        assertFalse("the recreation answers the open request", recreated.get().isFinishing)
        assertTrue("it launches no second dialog", drainLaunches().isEmpty())
        answer(recreated, dialog, granted = true)
        assertTrue(first.getCompleted())
        assertTrue(mp.hasConsent)
    }

    @Test
    fun recreatedOldRecord_cannotTakeTheReplacementRequest() {
        val first = request()
        val oldLaunch = theConsentLaunch()
        val old = createConsentActivity(oldLaunch, shown = true)
        val oldDialog = dialogRequest(old)
        val saved = reclaim(old)

        val second = request()
        assertFalse(first.getCompleted())
        val newLaunch = theConsentLaunch()

        // The replacement lands in the old record's task and the platform
        // relaunches the old record beneath it; here the old one reaches
        // onCreate first.
        val recreatedOld = Robolectric.buildActivity(MediaProjectionConsentActivity::class.java, oldLaunch)
            .create(saved).start().resume()
        idle()
        assertTrue("an older request's record finds nothing to answer", recreatedOld.get().isFinishing)
        assertTrue(drainLaunches().isEmpty())
        // Its parked result reaches no request.
        answer(recreatedOld, oldDialog, granted = true)
        assertFalse("the old record's grant is not the new request's answer", second.isCompleted)
        assertFalse(mp.hasConsent)

        val fresh = createConsentActivity(newLaunch, shown = true)
        answer(fresh, dialogRequest(fresh), granted = true)
        assertTrue(second.getCompleted())
        assertTrue(mp.hasConsent)
    }

    @Test
    fun serviceRestart_oldRecordCannotTakeTheNewControllersRequest() {
        request()
        val oldLaunch = theConsentLaunch()
        val old = createConsentActivity(oldLaunch, shown = true)
        dialogRequest(old)
        val saved = reclaim(old)

        // The service is stopped (the app-idle stop of a background started
        // service) and started again by the next tap: a fresh controller,
        // whose first request must not be mistaken for the old one.
        service?.destroy()
        val restarted = Robolectric.buildService(CaptureService::class.java).create()
        service = restarted
        svc = restarted.get()
        mp = svc.mediaProjectionController
        val second = request()
        val newLaunch = theConsentLaunch()

        val recreatedOld = Robolectric.buildActivity(MediaProjectionConsentActivity::class.java, oldLaunch)
            .create(saved).start().resume()
        idle()
        assertTrue("an earlier controller's record finds nothing to answer", recreatedOld.get().isFinishing)
        assertTrue(drainLaunches().isEmpty())
        assertFalse(second.isCompleted)

        val fresh = createConsentActivity(newLaunch, shown = true)
        answer(fresh, dialogRequest(fresh), granted = true)
        assertTrue(second.getCompleted())
        assertTrue(mp.hasConsent)
    }

    @Test
    fun recreation_keepsTheRequest_andTheRecreatedInstanceAnswersIt() {
        val first = request()
        val activity = createConsentActivity(theConsentLaunch(), shown = true)
        val dialog = dialogRequest(activity)

        activity.recreate()
        idle()
        assertFalse("a configuration change is not a cancel", first.isCompleted)
        assertTrue("the recreated instance launches nothing", drainLaunches().isEmpty())
        assertFalse(activity.get().isFinishing)

        answer(activity, dialog, granted = true)
        assertTrue(first.getCompleted())
        assertTrue(mp.hasConsent)
    }

    @Test
    fun instanceWithNoRequest_finishesWithoutPrompting() {
        // A request, answered: nothing is pending any more.
        val first = request()
        val launch = theConsentLaunch()
        val answered = createConsentActivity(launch, shown = true)
        answer(answered, dialogRequest(answered), granted = false)
        assertTrue(first.isCompleted)

        // The system restores a record of that same request (its launch
        // intent names it): there is nothing left to answer.
        val restored = createConsentActivity(launch, shown = false)
        assertTrue(restored.get().isFinishing)
        assertTrue(drainLaunches().isEmpty())
        assertFalse(mp.hasConsent)
    }

    @Test
    fun lateReportFromAnAnsweredInstance_doesNotTouchTheNextRequest() {
        val first = request()
        val old = createConsentActivity(theConsentLaunch(), shown = true)
        answer(old, dialogRequest(old), granted = false)
        assertTrue(first.isCompleted)
        assertFalse(first.getCompleted())
        assertTrue(old.get().isFinishing)

        // The next request opens a fresh prompt while the old instance is
        // still being torn down.
        val second = request()
        val freshLaunch = theConsentLaunch()
        old.pause().stop().destroy()
        idle()
        assertFalse("the old instance has already reported; its destroy is silent", second.isCompleted)

        val fresh = createConsentActivity(freshLaunch, shown = true)
        answer(fresh, dialogRequest(fresh), granted = true)
        assertTrue(second.getCompleted())
    }

    @Test
    fun instanceShownAgain_isJoinedNotSuperseded() {
        val first = request()
        val activity = createConsentActivity(theConsentLaunch(), shown = true)
        dialogRequest(activity)
        // Covered, then back on screen (the keyguard lifting, the covering
        // app finishing): the dialog is what the user sees again.
        activity.pause().stop()
        activity.start().resume()
        idle()
        assertFalse(activity.get().hidden)

        val second = request()
        assertTrue("the dialog back on screen is the prompt", consentLaunches().isEmpty())
        assertFalse(activity.get().isFinishing)
        assertFalse(first.isCompleted)
        assertFalse(second.isCompleted)
    }

    @Test
    fun launchRefusedByThePlatform_boundedWait_endsTheRequestAndTheNextOnePromptsAgain() {
        refusingPlatform()
        val first = request()
        theConsentLaunch()
        advanceClock(9_000)
        assertFalse("within the bound the request still waits", first.isCompleted)
        advanceClock(2_000)
        assertTrue(first.isCompleted)
        assertFalse(first.getCompleted())
        assertFalse(mp.hasConsent)

        val second = request()
        theConsentLaunch()
        assertFalse(second.isCompleted)
    }

    @Test
    fun launchAccepted_noBoundIsArmed() {
        val first = request()
        theConsentLaunch()
        // Nothing binds: the user is slow, the device is slow. The request
        // waits for the activity, not for a clock.
        advanceClock(60_000)
        assertFalse(first.isCompleted)
    }

    @Test
    fun launchJoinedAnExistingTask_theInstanceBinds_theBoundStandsDown() {
        // A stale consent task survives (a reclaimed record); the launch
        // joins it, so no new task appears, and the joined instance binds.
        refusingPlatform(existing = setOf(7))
        val first = request()
        val activity = createConsentActivity(theConsentLaunch(), shown = true)
        val dialog = dialogRequest(activity)
        advanceClock(11_000)
        assertFalse("a bound instance answers; the bound is void", first.isCompleted)
        answer(activity, dialog, granted = true)
        assertTrue(first.getCompleted())
        assertTrue(mp.hasConsent)
    }

    @Test
    fun staleTaskPresent_launchRefusedOutright_theStaleTaskVouchesForNothing() {
        // A stale consent task survives AND a ROM refuses the launch before
        // the platform's own logic would have joined it: no activity ever
        // binds, and the old task must not read as this launch's success.
        refusingPlatform(existing = setOf(7))
        val first = request()
        theConsentLaunch()
        advanceClock(11_000)
        assertTrue("the request ends instead of waiting on a task that is not its own", first.isCompleted)
        assertFalse(first.getCompleted())
        val second = request()
        theConsentLaunch()
        assertFalse(second.isCompleted)
    }

    @Test
    fun stoppedIsNotCancelled_thePickerStillAnswersAStoppedInstance() {
        val first = request()
        val activity = createConsentActivity(theConsentLaunch(), shown = true)
        val dialog = dialogRequest(activity)
        // The single-app picker brought the chosen task to the front.
        activity.pause().stop()
        idle()
        assertFalse(first.isCompleted)

        // Its grant is force-delivered to the stopped activity, which the
        // platform starts for the delivery and stops again after it.
        answer(activity, dialog, granted = true)
        activity.start().resume()
        idle()
        assertTrue(first.getCompleted())
        assertTrue(mp.hasConsent)
        assertTrue(svc.mediaProjectionActivated)
        assertTrue(activity.get().isFinishing)
        activity.pause().stop()
        idle()
        assertTrue("the stop after the delivery changes nothing", mp.hasConsent)
    }
}
