package com.playtranslate.ui

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Build
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.allViews
import androidx.core.view.children
import androidx.core.view.isVisible
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.playtranslate.Prefs
import com.playtranslate.R
import com.playtranslate.TranslationCache
import com.playtranslate.cloud.CodeRequestResult
import com.playtranslate.cloud.HealthStatus
import com.playtranslate.cloud.KeyCheck
import com.playtranslate.cloud.PtCloudAccount
import com.playtranslate.cloud.PtCloudConfig
import com.playtranslate.cloud.PtCloudService
import com.playtranslate.cloud.PtCloudTestSupport
import com.playtranslate.cloud.PtCloudTestSupport.KEY
import com.playtranslate.cloud.SignInAnswer
import com.playtranslate.cloud.VerifyResult
import com.playtranslate.cloud.deviceLabel
import com.playtranslate.security.SecretCodec
import com.playtranslate.themeColor
import com.playtranslate.translation.CooldownCause
import com.playtranslate.translation.CooldownState
import com.playtranslate.translation.GroupTranslator
import com.playtranslate.translation.TranslationBackendRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.shadows.ShadowToast
import java.time.Duration
import kotlin.math.roundToInt

/**
 * The PlayTranslate Cloud page.
 *
 * Above everything, in every state, the intro paragraph naming the service;
 * the ACCOUNT and CONFIGURATION group headers title the cards under them;
 * Try for Free, Start Free Trial and Join share one shape, 56dp and bold.
 * The account card: signed out it shows the email field, Try for Free and
 * the privacy sentence; a request's answers move it to the code field or
 * hold Try for Free, each in its wording and for the address it answered,
 * a refused Send a new code staying where it is; the hold and its notice
 * outlive a recreate, as the pending sign-in does; the code field keeps
 * six digits of whatever is pasted, and
 * the keyboard's Done is Start Free Trial; a verify's 200 stores the key
 * first and draws the page from its own fields with no key check, a
 * refused keystore write saying the key was not stored; the pending
 * sign-in survives a recreate. Signed in, its title is the account's
 * address and under it the account from the record (trial, in the warning
 * colour, and member until a date and a time of day, no details, trial
 * ended, inactive), with Join in the card under a divider where it is due
 * and the line under Join, centred, for the standing, and the sign-out
 * chip, which confirms, forgets the key and the record and sends nothing;
 * each resume checks the stored key (a 401 clears it, retried once within
 * 10 s of a verify; back from Join, once more 10 s later). A 410 shows the
 * closed card until a health answer that is neither closed nor
 * unreachable. The stored key is never on the page.
 *
 * Its two "use for" switches (Read captures, Translate), shown only while a
 * key is stored, seed from their prefs without resetting the service and
 * keep their state across a recreate, a flip writes its pref and resets the
 * service's cooldown through the service's one mutation for that switch
 * (the pref stored before the reset's reconcile of the shared cache), and
 * they repaint from the service's change signal when flipped elsewhere; and
 * the status row shows the health check's answer.
 *
 * A stored key is asserted through a fake codec installed on the service:
 * the page's own [Prefs] uses the AndroidKeyStore codec, which a JVM test has
 * none of. No call reaches the service: every test answers them through the
 * page's four seams, by default with calls that never end, which [tearDown]
 * cancels with the page. The stored key's check reads the key on the IO
 * dispatcher, a real thread, before it asks, so a test waits for that check
 * to arrive ([awaitCalls]).
 */
@RunWith(RobolectricTestRunner::class)
class PtCloudSettingsActivityTest {

    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val prefs = Prefs(ctx)
    private lateinit var cooldown: CooldownState
    private var controller: ActivityController<PtCloudSettingsActivity>? = null

    @Before fun setUp() {
        clearPrefs()
        cooldown = CooldownState(null, PtCloudService.ID).also { PtCloudService.installForTest(it) }
        PtCloudSettingsActivity.healthOverrideForTest = { awaitCancellation() }
        PtCloudSettingsActivity.keyCheckOverrideForTest = { awaitCancellation() }
        PtCloudSettingsActivity.requestCodeOverrideForTest = { awaitCancellation() }
        PtCloudSettingsActivity.verifyOverrideForTest = { _, _, _ -> awaitCancellation() }
    }

    @After fun tearDown() {
        controller?.pause()?.stop()?.destroy()
        controller = null
        PtCloudSettingsActivity.healthOverrideForTest = null
        PtCloudSettingsActivity.keyCheckOverrideForTest = null
        PtCloudSettingsActivity.requestCodeOverrideForTest = null
        PtCloudSettingsActivity.verifyOverrideForTest = null
        // A flip reconciles the shared cache's routing identity.
        TranslationCache.shared.resetForTest()
        PtCloudService.installForTest(null)
        clearPrefs()
    }

    private fun clearPrefs() {
        ctx.getSharedPreferences("playtranslate_prefs", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun idleFor(seconds: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(seconds))

    private fun open(): PtCloudSettingsActivity {
        val c = Robolectric.buildActivity(PtCloudSettingsActivity::class.java).setup()
        controller = c
        idle()
        return c.get()
    }

    /** Pauses and resumes the page, as a return from another app does. */
    private fun resume() {
        controller!!.pause().resume()
        idle()
    }

    private fun recordCooldown(cause: CooldownCause = CooldownCause.RATE_LIMITED) =
        cooldown.recordParsedFailure(System.currentTimeMillis() + 10 * 60_000L, "Rate limited", cause)

    private fun PtCloudSettingsActivity.ocrRow() = findViewById<View>(R.id.rowCloudOcr)
    private fun PtCloudSettingsActivity.translateRow() = findViewById<View>(R.id.rowCloudTranslate)
    private fun View.switch() = findViewById<MaterialSwitch>(R.id.switchRowToggle)
    private fun View.title() = findViewById<TextView>(R.id.tvRowTitle).text.toString()
    private fun PtCloudSettingsActivity.emailField() = findViewById<EditText>(R.id.etCloudEmail)
    private fun PtCloudSettingsActivity.codeField() = findViewById<EditText>(R.id.etCloudCode)
    private fun PtCloudSettingsActivity.sendCodeButton() = findViewById<MaterialButton>(R.id.btnCloudSendCode)
    private fun PtCloudSettingsActivity.signInButton() = findViewById<MaterialButton>(R.id.btnCloudSignIn)
    private fun PtCloudSettingsActivity.sendNewCodeButton() = findViewById<MaterialButton>(R.id.btnCloudSendNewCode)
    private fun PtCloudSettingsActivity.checkAgainButton() = findViewById<View>(R.id.btnCloudCheckAgain)
    private fun PtCloudSettingsActivity.joinButton() = findViewById<MaterialButton>(R.id.btnCloudJoin)
    private fun PtCloudSettingsActivity.status() = findViewById<TextView>(R.id.tvCloudStatus).text.toString()
    private fun PtCloudSettingsActivity.cooldownLine() = findViewById<TextView>(R.id.tvCloudCooldown)
    private fun PtCloudSettingsActivity.text(id: Int) = findViewById<TextView>(id).text.toString()
    private fun PtCloudSettingsActivity.shown(id: Int) = findViewById<View>(id).isShown

    /** The account line, null while it does not show. */
    private fun PtCloudSettingsActivity.accountLine(): String? =
        findViewById<TextView>(R.id.tvAccountLine).takeIf { it.isShown }?.text?.toString()

    /** The account line's text colour. */
    private fun PtCloudSettingsActivity.accountLineColor(): Int =
        findViewById<TextView>(R.id.tvAccountLine).currentTextColor

    /** The line under Join, null while it does not show. */
    private fun PtCloudSettingsActivity.joinSubtitle(): String? =
        findViewById<TextView>(R.id.tvJoinSubtitle).takeIf { it.isShown }?.text?.toString()

    /** The ids of the page column's children that show, top to bottom: the
     *  intro, the closed card, each group's header and card, and the status
     *  card, which has no id ([View.NO_ID]). */
    private fun PtCloudSettingsActivity.sections(): List<Int> =
        (findViewById<View>(R.id.cardCloudAccount).parent as ViewGroup).children
            .filter { it.isVisible }.map { it.id }.toList()

    /** The sign-in notice showing under its step's button, null for none. */
    private fun PtCloudSettingsActivity.notice(): String? =
        listOf(R.id.tvSignInNotice, R.id.tvCodeNotice).map { findViewById<TextView>(it) }
            .singleOrNull { it.isShown }?.text?.toString()

    /** Every text on the page's window, an alert attached to it and hidden
     *  views included. */
    private fun PtCloudSettingsActivity.texts() =
        window.decorView.allViews.filterIsInstance<TextView>().map { it.text.toString() }.toList()

    /** The texts that show. */
    private fun PtCloudSettingsActivity.shownTexts() =
        window.decorView.allViews.filterIsInstance<TextView>().filter { it.isShown }.map { it.text.toString() }.toList()

    /** An alert's button labelled [label]: a plain Button with no id, where
     *  the page's own buttons are Material buttons with ids. */
    private fun PtCloudSettingsActivity.alertButton(label: String): Button =
        window.decorView.allViews.filterIsInstance<Button>()
            .single { it.id == View.NO_ID && it.text.toString() == label }

    /** Types [email] and taps Send code (Try for Free). */
    private fun PtCloudSettingsActivity.sendCodeTo(email: String = EMAIL) {
        emailField().setText(email)
        sendCodeButton().performClick()
        idle()
    }

    /** Moves the page to code sent: a request answered 200 with the
     *  server's default windows, [later] queued for the requests after it. */
    private fun PtCloudSettingsActivity.codeSent(vararg later: CodeRequestResult): Calls<String, CodeRequestResult> {
        val requests = requests(CodeRequestResult.Sent(600, 60), *later)
        sendCodeTo()
        assertTrue(shown(R.id.groupCodeSent))
        return requests
    }

    /** Types [code] and taps Sign in (Start Free Trial). */
    private fun PtCloudSettingsActivity.signInWith(code: String) {
        codeField().setText(code)
        signInButton().performClick()
        idle()
    }

    /** Taps Sign out (the chip) and confirms it. */
    private fun PtCloudSettingsActivity.signOut() {
        findViewById<View>(R.id.btnCloudSignOut).performClick()
        idle()
        alertButton("Sign out").performClick()
        idle()
    }

    /** A step's button while its call runs: blanked and disabled under its
     *  spinner. */
    private fun assertBusy(page: PtCloudSettingsActivity, button: MaterialButton, progressId: Int) {
        assertFalse(button.isEnabled)
        assertEquals("", button.text.toString())
        assertTrue(page.findViewById<View>(progressId).isVisible)
    }

    private fun assertFree(page: PtCloudSettingsActivity, button: MaterialButton, progressId: Int, label: String) {
        assertTrue(button.isEnabled)
        assertEquals(label, button.text.toString())
        assertFalse(page.findViewById<View>(progressId).isVisible)
    }

    /** The Check again chip at rest: enabled, its glyph showing, no spinner. */
    private fun assertCheckAgainFree(page: PtCloudSettingsActivity) {
        assertTrue(page.checkAgainButton().isEnabled)
        assertTrue(page.findViewById<View>(R.id.ivCloudCheckAgain).isVisible)
        assertFalse(page.findViewById<View>(R.id.progressCloudCheckAgain).isVisible)
    }

    /** The Check again chip while its check runs: disabled, the spinner in
     *  the glyph's place. */
    private fun assertCheckAgainBusy(page: PtCloudSettingsActivity) {
        assertFalse(page.checkAgainButton().isEnabled)
        assertFalse(page.findViewById<View>(R.id.ivCloudCheckAgain).isVisible)
        assertTrue(page.findViewById<View>(R.id.progressCloudCheckAgain).isVisible)
    }

    /** The service's settings on a fake codec, so a key can be stored and
     *  read back (the page writes through the service). Returns its Prefs. */
    private fun cloudPrefs(): Prefs =
        PtCloudTestSupport.prefs(ctx).also { PtCloudService.installForTest(cooldown, PtCloudConfig(it)) }

    /** [KEY] stored through the service with [account] beside it, as a
     *  sign-in stores them (both switches on), more than 10 s ago: a 401 on
     *  it is not a fresh key's replica lag. Returns the settings' Prefs. */
    private fun signedIn(account: PtCloudAccount?): Prefs {
        val p = cloudPrefs()
        assertTrue(PtCloudService.saveClientKey(KEY, account))
        idleFor(11)
        return p
    }

    private fun now() = System.currentTimeMillis()

    private fun trial(untilMs: Long?, email: String? = EMAIL) =
        PtCloudAccount(email = email, active = true, source = "trial", entitledUntilMs = untilMs, checkedAtMs = now())

    private fun member(untilMs: Long?, email: String? = EMAIL) =
        PtCloudAccount(email = email, active = true, source = "kofi", entitledUntilMs = untilMs, checkedAtMs = now())

    private fun ended(lastSource: String?, endedAtMs: Long?, email: String? = EMAIL) =
        PtCloudAccount(email = email, active = false, lastSource = lastSource, endedAtMs = endedAtMs, checkedAtMs = now())

    /** Counts the page's health checks, each one never ending. */
    private fun countHealthChecks(): () -> Int {
        var count = 0
        PtCloudSettingsActivity.healthOverrideForTest = { count++; awaitCancellation() }
        return { count }
    }

    /** A seam's calls, each recorded, and its answers, taken in turn: queued
     *  up front or sent later ([answer]). With none left a call waits until
     *  one is sent or the page is gone. */
    private inner class Calls<A, R>(vararg queued: R) {
        val sent = mutableListOf<A>()
        val answers = Channel<R>(Channel.UNLIMITED).apply { queued.forEach { trySend(it) } }

        suspend fun take(arg: A): R {
            sent += arg
            return answers.receive()
        }

        fun answer(r: R) {
            assertTrue(answers.trySend(r).isSuccess)
            idle()
        }
    }

    private fun keyChecks(vararg answers: KeyCheck) =
        Calls<String, KeyCheck>(*answers).also { c -> PtCloudSettingsActivity.keyCheckOverrideForTest = { c.take(it) } }

    private fun requests(vararg answers: CodeRequestResult) = Calls<String, CodeRequestResult>(*answers)
        .also { c -> PtCloudSettingsActivity.requestCodeOverrideForTest = { c.take(it) } }

    private fun verifies(vararg answers: VerifyResult) = Calls<List<String>, VerifyResult>(*answers)
        .also { c -> PtCloudSettingsActivity.verifyOverrideForTest = { email, code, label -> c.take(listOf(email, code, label)) } }

    /** Idles the main looper until [calls] holds [count] calls: the stored
     *  key's check reads the key on the IO dispatcher, a real thread, before
     *  it asks. */
    private fun awaitCalls(calls: Calls<*, *>, count: Int) {
        val deadline = System.currentTimeMillis() + 5_000
        while (calls.sent.size < count) {
            check(System.currentTimeMillis() < deadline) { "${calls.sent.size} of $count calls" }
            Thread.sleep(5)
            idle()
        }
        idle()
    }

    /** Gives a stored key's check that must not happen the time to show
     *  (its read of the key on the IO dispatcher comes first). */
    private fun settleIo() = repeat(20) {
        Thread.sleep(5)
        idle()
    }

    // ── The page ──────────────────────────────────────────────────────────

    /** The intro says what the service is before anything else, whatever
     *  the state: the page column's first child, in no card. */
    @Test fun `the intro paragraph shows in every state, outside the cards, naming the service`() {
        cloudPrefs()
        var health: HealthStatus = HealthStatus.Running
        PtCloudSettingsActivity.healthOverrideForTest = { health }
        val page = open()
        val intro = page.findViewById<TextView>(R.id.tvCloudIntro)
        val column = page.findViewById<View>(R.id.cardCloudAccount).parent as ViewGroup
        assertSame("in the page's column, in no card", column, intro.parent)
        assertEquals("first in it", 0, column.indexOfChild(intro))
        assertEquals(
            "The most accurate text recognition and translation models are too heavy to run on a mobile " +
                "device. PlayTranslate Cloud runs them on a server instead for better results, and your " +
                "device does none of the work!",
            intro.text.toString(),
        )
        assertTrue("signed out", intro.isShown)

        page.codeSent()
        assertTrue("code sent", intro.isShown)

        verifies(VerifyResult.SignedIn(KEY, KeyCheck.Accepted("trial", now() + 7 * DAY)))
        page.signInWith("482913")
        assertTrue(page.shown(R.id.groupSignedIn))
        assertTrue("signed in", intro.isShown)

        health = HealthStatus.Closed(null)
        resume()
        assertTrue(page.shown(R.id.cardCloudClosed))
        assertTrue("closed", intro.isShown)
    }

    /** Each header is right above the card it titles (its style shows it in
     *  capitals), and the account card has no title of its own. */
    @Test fun `ACCOUNT and CONFIGURATION are group headers above their cards, and the account card has no title of its own`() {
        withKey()
        val page = open()
        val column = page.findViewById<View>(R.id.cardCloudAccount).parent as ViewGroup
        for ((headerId, title, cardId) in listOf(
            Triple(R.id.headerCloudAccount, "Account", R.id.cardCloudAccount),
            Triple(R.id.headerCloudConfiguration, "Configuration", R.id.cardCloudUseFor),
        )) {
            val header = page.findViewById<View>(headerId)
            assertTrue(title, header.isShown)
            assertEquals(title, header.findViewById<TextView>(R.id.tvGroupTitle).text.toString())
            assertEquals("$title, right above its card", column.indexOfChild(header) + 1, column.indexOfChild(page.findViewById(cardId)))
        }
        val inCard = page.findViewById<ViewGroup>(R.id.cardCloudAccount).allViews
            .filterIsInstance<TextView>().map { it.text.toString() }.toList()
        assertFalse(inCard.toString(), "Account" in inCard)
    }

    /** The page's three calls to action share one shape: 56dp tall (Try
     *  for Free and Start Free Trial fill the box that holds each with its
     *  spinner, so the box carries the height) and bold. */
    @Test fun `Try for Free, Start Free Trial and Join are 56dp tall and bold`() {
        val page = open()
        val height = (56 * page.resources.displayMetrics.density).roundToInt()
        assertEquals("Join", height, page.joinButton().layoutParams.height)
        for (button in listOf(page.sendCodeButton(), page.signInButton())) {
            val name = page.resources.getResourceEntryName(button.id)
            assertEquals("$name's box", height, (button.parent as View).layoutParams.height)
            assertEquals("$name fills it", ViewGroup.LayoutParams.MATCH_PARENT, button.layoutParams.height)
        }
        // Robolectric answers Typeface.isBold false for every face, a bold one
        // too; the style it does answer carries the same bit.
        for (button in listOf(page.sendCodeButton(), page.signInButton(), page.joinButton())) {
            val name = page.resources.getResourceEntryName(button.id)
            assertEquals("$name is bold", Typeface.BOLD, button.typeface.style and Typeface.BOLD)
        }
    }

    // ── The two switches ──────────────────────────────────────────────────

    /** A usable key stored on the service with no record, so the rows are
     *  live. */
    private fun withKey() = PtCloudService.installForTest(cooldown, PtCloudTestSupport.config(ctx))

    @Test fun `the switches seed from their prefs, keep them across a recreate, and reset nothing`() {
        withKey()
        prefs.ptCloudOcrEnabled = true
        prefs.ptCloudTranslateEnabled = false
        recordCooldown()
        val page = open()
        assertTrue(page.ocrRow().switch().isChecked)
        assertFalse(page.translateRow().switch().isChecked)
        assertTrue(page.ocrRow().switch().isEnabled)
        assertEquals("Read the screen", page.ocrRow().title())
        assertEquals("Translate the text", page.translateRow().title())

        controller!!.recreate()
        idle()

        assertTrue(controller!!.get().ocrRow().switch().isChecked)
        assertFalse(controller!!.get().translateRow().switch().isChecked)
        assertTrue(prefs.ptCloudOcrEnabled)
        assertFalse(prefs.ptCloudTranslateEnabled)
        // Seeding through the listeners would have reset the service.
        assertNotNull("building or restoring the page resets nothing", PtCloudService.unavailableUntil())
    }

    @Test fun `the switches seed off from the prefs`() {
        withKey()
        val page = open()
        assertFalse(page.ocrRow().switch().isChecked)
        assertFalse(page.translateRow().switch().isChecked)
        assertFalse(prefs.ptCloudOcrEnabled)
        assertFalse(prefs.ptCloudTranslateEnabled)
    }

    /** No usable key saved: the CONFIGURATION group, header and card, is
     *  gone, signing in being the first step. Closed with a key it shows,
     *  the rows disabled and their switches off whatever is stored, and a
     *  tap on them writes and resets nothing. */
    @Test fun `with no key the CONFIGURATION group is gone, and closed with a key its rows show disabled and off and a tap does nothing`() {
        val cloudPrefs = cloudPrefs()
        var health: HealthStatus = HealthStatus.Running
        PtCloudSettingsActivity.healthOverrideForTest = { health }
        val page = open()
        assertFalse(page.shown(R.id.headerCloudConfiguration))
        assertFalse(page.shown(R.id.cardCloudUseFor))

        assertTrue(PtCloudService.saveClientKey(KEY, trial(now() + 5 * DAY)))
        idle()
        assertTrue(page.shown(R.id.headerCloudConfiguration))
        assertTrue(page.shown(R.id.cardCloudUseFor))

        health = HealthStatus.Closed(null)
        resume()
        assertTrue(page.shown(R.id.cardCloudClosed))
        assertTrue("closed with a key, the group stays", page.shown(R.id.cardCloudUseFor))
        recordCooldown()
        for (row in listOf(page.ocrRow(), page.translateRow())) {
            assertFalse(row.switch().isEnabled)
            assertFalse(row.switch().isChecked)
            assertFalse(row.isEnabled)
            assertEquals(0.5f, row.alpha)
        }

        page.ocrRow().performClick()
        page.translateRow().performClick()
        idle()

        assertFalse(page.ocrRow().switch().isChecked)
        assertFalse(page.translateRow().switch().isChecked)
        assertTrue("nothing written", cloudPrefs.ptCloudOcrEnabled && cloudPrefs.ptCloudTranslateEnabled)
        assertNotNull("nothing reset", PtCloudService.unavailableUntil())
    }

    @Test fun `a row tap writes its pref alone and resets the cooldown, either way`() {
        withKey()
        recordCooldown()
        val page = open()
        assertTrue("the cooldown line shows while it runs", page.cooldownLine().isVisible)
        assertTrue(
            "in the services page's wording",
            page.cooldownLine().text.toString().startsWith("Rate limited · Retry at "),
        )

        page.ocrRow().performClick()
        idle()
        assertTrue(page.ocrRow().switch().isChecked)
        assertTrue(prefs.ptCloudOcrEnabled)
        assertFalse("the other switch is its own", prefs.ptCloudTranslateEnabled)
        assertNull(PtCloudService.unavailableUntil())
        assertFalse(page.cooldownLine().isVisible)

        recordCooldown()
        page.ocrRow().performClick()
        assertFalse(page.ocrRow().switch().isChecked)
        assertFalse(prefs.ptCloudOcrEnabled)
        assertNull(PtCloudService.unavailableUntil())

        recordCooldown()
        page.translateRow().performClick()
        idle()
        assertTrue(page.translateRow().switch().isChecked)
        assertTrue(prefs.ptCloudTranslateEnabled)
        assertFalse("the other switch is its own", prefs.ptCloudOcrEnabled)
        assertNull(PtCloudService.unavailableUntil())

        recordCooldown()
        page.translateRow().performClick()
        assertFalse(page.translateRow().switch().isChecked)
        assertFalse(prefs.ptCloudTranslateEnabled)
        assertNull(PtCloudService.unavailableUntil())
    }

    /** The cooldown line and the switches repaint from the service's change
     *  signal: a gesture made elsewhere (the services row's switch, the OCR
     *  picker over the game) while the page is resumed reaches them with no
     *  call of the page's own. */
    @Test fun `the cooldown line and the switches repaint from the service's change signal`() {
        withKey()
        recordCooldown()
        val page = open()
        assertTrue(page.cooldownLine().isVisible)
        assertFalse(page.ocrRow().switch().isChecked)

        PtCloudService.setOcrEnabled(true)
        idle()

        assertFalse(page.cooldownLine().isVisible)
        assertTrue("flipped elsewhere, shown here", page.ocrRow().switch().isChecked)
        assertFalse(page.translateRow().switch().isChecked)
        // The repaint wrote nothing: a second gesture would have counted.
        val changes = PtCloudService.changes.value
        idle()
        assertEquals(changes, PtCloudService.changes.value)
    }

    /** The Translate switch goes through [PtCloudService.setTranslateEnabled],
     *  the mutation the service's Translation services row shares: the pref
     *  is stored before the reset, whose reconcile then reads it, so the
     *  shared cache's routing identity is the cloud's once a flip turns it on
     *  with a key stored. A page that reset first and stored after would
     *  leave the identity on the old switch. */
    @Test fun `the Translate switch goes through the service's one mutation, the pref stored before the reset's reconcile`() {
        PtCloudService.installForTest(cooldown, PtCloudTestSupport.config(ctx))
        recordCooldown()
        val page = open()
        // Off and cooling down: the identity is not the cloud's.
        GroupTranslator.reconcileShared(ctx)
        assertNotEquals(PtCloudService.ID, TranslationBackendRegistry.preferredOnlineId("ja", "en"))

        page.translateRow().performClick()

        assertTrue(prefs.ptCloudTranslateEnabled)
        assertNull(PtCloudService.unavailableUntil())
        assertEquals(PtCloudService.ID, TranslationBackendRegistry.preferredOnlineId("ja", "en"))
        // The flip's reconcile already moved the identity onto the cloud, so
        // a reconcile now finds nothing changed and keeps what the cache holds.
        val key = TranslationCache.Key("一", "ja", "en")
        TranslationCache.shared[key] = "one" to PtCloudService.DISPLAY_NAME
        GroupTranslator.reconcileShared(ctx)
        assertEquals("one" to PtCloudService.DISPLAY_NAME, TranslationCache.shared[key])
    }

    @Test fun `the page's two switches are the service's two, and no master`() {
        val page = open()
        val switches = page.window.decorView.allViews.filterIsInstance<MaterialSwitch>().toList()
        assertEquals(listOf(page.ocrRow().switch(), page.translateRow().switch()), switches)
    }

    /** The first key, a verify's, turns both switches on through the
     *  service ([PtCloudService.saveClientKey]); its group shows them at
     *  once, live, from the change signal, with no resume. Sign out turns
     *  them off, and the group goes. */
    @Test fun `a verify 200 turns both switches on and live, shown at once, and Sign out turns them off and hides their group`() {
        val cloudPrefs = cloudPrefs()
        val page = open()
        assertFalse(page.shown(R.id.cardCloudUseFor))
        page.codeSent()
        verifies(VerifyResult.SignedIn(KEY, KeyCheck.Accepted("trial", now() + 7 * DAY)))

        page.signInWith("482913")

        assertEquals(KEY, cloudPrefs.ptCloudClientKey)
        assertTrue(cloudPrefs.ptCloudOcrEnabled)
        assertTrue(cloudPrefs.ptCloudTranslateEnabled)
        assertTrue(page.shown(R.id.headerCloudConfiguration))
        assertTrue(page.shown(R.id.cardCloudUseFor))
        assertTrue(page.ocrRow().switch().isEnabled)
        assertTrue(page.ocrRow().switch().isChecked)
        assertTrue(page.translateRow().switch().isChecked)

        page.signOut()

        assertFalse(cloudPrefs.ptCloudOcrEnabled)
        assertFalse(cloudPrefs.ptCloudTranslateEnabled)
        assertFalse(page.shown(R.id.headerCloudConfiguration))
        assertFalse(page.shown(R.id.cardCloudUseFor))
        assertFalse("painted off behind it", page.ocrRow().switch().isChecked)
        assertFalse(page.translateRow().switch().isChecked)
    }

    // ── The fields ────────────────────────────────────────────────────────

    /** The email address and the code: no field for the server, whose
     *  address is fixed, and none for a key. The email field autofills an
     *  address; the code field takes digits and no autofill. */
    @Test fun `the page's text fields are the email address and the code, with none for the server or a key`() {
        val page = open()
        val fields = page.window.decorView.allViews.filterIsInstance<EditText>().map { it.id }.toList()
        assertEquals(listOf(R.id.etCloudEmail, R.id.etCloudCode), fields)

        val email = page.emailField()
        assertEquals(
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
            email.inputType and (InputType.TYPE_MASK_CLASS or InputType.TYPE_MASK_VARIATION),
        )
        assertEquals(listOf("emailAddress"), email.autofillHints?.toList())
        assertEquals(View.IMPORTANT_FOR_AUTOFILL_YES, email.importantForAutofill)
        assertEquals("you@example.com", email.hint.toString())
        assertEquals(InputType.TYPE_CLASS_NUMBER, page.codeField().inputType and InputType.TYPE_MASK_CLASS)
        assertEquals(View.IMPORTANT_FOR_AUTOFILL_NO, page.codeField().importantForAutofill)
    }

    /** Nobody has a reason to see the stored key, and a key on screen is a
     *  key in a screenshot: nothing on the page shows it, neither right
     *  after the verify that brought it nor on a page built with it stored
     *  (a recreate). */
    @Test fun `the stored key is never on the page`() {
        cloudPrefs()
        val page = open()
        page.codeSent()
        verifies(VerifyResult.SignedIn(KEY, KeyCheck.Accepted("trial", now() + 7 * DAY)))
        page.signInWith("482913")

        assertEquals(KEY, PtCloudService.config.clientKey)
        assertTrue(page.shown(R.id.groupSignedIn))
        assertFalse(page.texts().toString(), page.texts().any { KEY in it })

        controller!!.recreate()
        idle()
        val again = controller!!.get()
        assertTrue(again.shown(R.id.groupSignedIn))
        assertFalse(again.texts().toString(), again.texts().any { KEY in it })
    }

    @Test fun `a pasted 482 913 keeps its six digits`() {
        val page = open()
        page.codeSent()
        val field = page.codeField()

        val clipboard = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("code", "482 913"))
        field.onTextContextMenuItem(android.R.id.paste)
        assertEquals("482913", field.text.toString())

        field.setText("48a2-91 37")
        assertEquals("digits only, at most six", "482913", field.text.toString())
        field.text.append("5")
        assertEquals("a full field takes no more", "482913", field.text.toString())
        field.setText("48")
        field.text.insert(1, "7 7")
        assertEquals("4778", field.text.toString())
    }

    // ── Signed out ────────────────────────────────────────────────────────

    /** The form is the email field, Try for Free (which sends the code) and
     *  the one privacy sentence, centred under the button as a notice there
     *  and Join's line are: no intro line of its own, no device clause,
     *  no usage sentence and no way to paste a key, shown or hidden. */
    @Test fun `signed out shows the email field, Try for Free and the privacy sentence centred under it, with no intro line and no paste link`() {
        val page = open()

        assertTrue(page.shown(R.id.groupSignedOut))
        assertTrue(page.emailField().isShown)
        assertFree(page, page.sendCodeButton(), R.id.progressCloudSendCode, "Try PlayTranslate Cloud for Free")
        val texts = page.shownTexts()
        assertTrue(texts.toString(), "Email address" in texts)
        assertTrue(texts.toString(), "Your email address is used to send you a sign-in code." in texts)
        for (line in listOf(R.id.tvSignInPrivacy, R.id.tvSignInNotice)) {
            assertEquals(
                page.resources.getResourceEntryName(line) + " centred",
                Gravity.CENTER_HORIZONTAL,
                page.findViewById<TextView>(line).gravity and Gravity.HORIZONTAL_GRAVITY_MASK,
            )
        }
        val all = page.texts()
        for (gone in listOf("A new account starts with a free trial", "model name", "usage record", "Paste")) {
            assertFalse(gone, all.any { gone in it })
        }
        for (hidden in listOf(
            R.id.groupCodeSent, R.id.groupSignedIn, R.id.cardCloudClosed, R.id.tvKeyRefused,
            R.id.headerCloudConfiguration, R.id.cardCloudUseFor,
        )) {
            assertFalse(page.resources.getResourceEntryName(hidden), page.shown(hidden))
        }
        assertNull(page.notice())
    }

    // ── Send code ─────────────────────────────────────────────────────────

    @Test fun `a request's 200 shows the code field and holds Send a new code for resend_after`() {
        val page = open()
        val requests = page.codeSent()

        assertEquals("the address as typed, trimmed", listOf(EMAIL), requests.sent)
        assertFalse(page.shown(R.id.groupSignedOut))
        assertEquals("We sent a code to you@example.com. It works for 10 minutes.", page.text(R.id.tvCodeSentTo))
        assertTrue(page.codeField().isShown)
        assertTrue(page.shown(R.id.btnCloudSignIn))
        assertTrue(page.shown(R.id.btnCloudChangeEmail))
        assertFalse("held for resend_after", page.sendNewCodeButton().isEnabled)
        assertNull(page.notice())
    }

    @Test fun `Send code sends the address trimmed, and a 200's own windows are honoured`() {
        val requests = requests(CodeRequestResult.Sent(60, 30))
        val page = open()

        page.sendCodeTo("  $EMAIL  ")

        assertEquals(listOf(EMAIL), requests.sent)
        assertEquals("We sent a code to you@example.com. It works for 1 minute.", page.text(R.id.tvCodeSentTo))
        idleFor(29)
        assertFalse(page.sendNewCodeButton().isEnabled)
        idleFor(1)
        assertTrue(page.sendNewCodeButton().isEnabled)
    }

    /** Decision 10: the hold is re-armed by render, so Send a new code comes
     *  back with no event once resend_after has passed. */
    @Test fun `Send a new code comes back by itself once resend_after has passed`() {
        val page = open()
        page.codeSent()

        idleFor(59)
        assertFalse(page.sendNewCodeButton().isEnabled)
        idleFor(1)
        assertTrue(page.sendNewCodeButton().isEnabled)
    }

    /** A 429 `cooldown` (a code went out less than a minute ago) or `rate
     *  limited` may follow a lost 200: the code field, with Send a new code
     *  held for the Retry-After, or a minute without one. */
    @Test fun `a 429 cooldown moves to the code field`() {
        requests(SignInAnswer.RateLimited(42))
        val page = open()

        page.sendCodeTo()

        assertTrue(page.shown(R.id.groupCodeSent))
        assertEquals("We sent a code to you@example.com. It works for 10 minutes.", page.text(R.id.tvCodeSentTo))
        assertEquals(
            "Too many requests. If a code arrived, enter it below; otherwise send a new one in a minute.",
            page.notice(),
        )
        idleFor(41)
        assertFalse(page.sendNewCodeButton().isEnabled)
        idleFor(1)
        assertTrue(page.sendNewCodeButton().isEnabled)
    }

    @Test fun `a 429 with no Retry-After holds Send a new code for a minute`() {
        requests(SignInAnswer.RateLimited(null))
        val page = open()

        page.sendCodeTo()

        assertTrue(page.shown(R.id.groupCodeSent))
        idleFor(59)
        assertFalse(page.sendNewCodeButton().isEnabled)
        idleFor(1)
        assertTrue(page.sendNewCodeButton().isEnabled)
    }

    @Test fun `too_many_codes holds Send code with its own wording`() {
        requests(CodeRequestResult.TooManyCodes(3_600))
        val page = open()

        page.sendCodeTo()

        assertTrue("still signed out", page.shown(R.id.groupSignedOut))
        assertEquals("Too many codes sent to this address today. Try again tomorrow.", page.notice())
        assertFalse(page.sendCodeButton().isEnabled)
        idleFor(3_599)
        assertFalse(page.sendCodeButton().isEnabled)
        idleFor(1)
        assertTrue(page.sendCodeButton().isEnabled)
    }

    /** The server's limits are per address, so the hold is: another address
     *  in the field asks at once, and the held one waits for its Retry-After
     *  whenever it is back in the field. */
    @Test fun `a hold is for its address, so another address asks at once and the held one waits`() {
        val requests = requests(CodeRequestResult.TooManyCodes(3_600), CodeRequestResult.Sent(600, 60))
        val page = open()

        page.sendCodeTo()
        assertFalse(page.sendCodeButton().isEnabled)

        page.emailField().setText("other@example.com")
        assertTrue(page.sendCodeButton().isEnabled)
        page.emailField().setText(EMAIL)
        assertFalse(page.sendCodeButton().isEnabled)

        page.sendCodeTo("other@example.com")
        assertEquals(listOf(EMAIL, "other@example.com"), requests.sent)
        assertTrue(page.shown(R.id.groupCodeSent))
        assertEquals("We sent a code to other@example.com. It works for 10 minutes.", page.text(R.id.tvCodeSentTo))
    }

    /** A hold left by one address says nothing of another's resend window:
     *  a second address whose first request met a 429 resends after that
     *  429's own window, not after the first address's hold. */
    @Test fun `a hold on one address does not hold another's Send a new code`() {
        requests(CodeRequestResult.TooManyCodes(3_600), SignInAnswer.RateLimited(null))
        val page = open()
        page.sendCodeTo()
        assertFalse(page.sendCodeButton().isEnabled)

        page.sendCodeTo("other@example.com")

        assertTrue(page.shown(R.id.groupCodeSent))
        idleFor(59)
        assertFalse(page.sendNewCodeButton().isEnabled)
        idleFor(1)
        assertTrue(page.sendNewCodeButton().isEnabled)
    }

    @Test fun `mail_failed holds Send code for its Retry-After, and an unavailable sign-in without one holds nothing`() {
        requests(CodeRequestResult.MailFailed(60), SignInAnswer.Unavailable(10), SignInAnswer.Unavailable(null))
        val page = open()

        page.sendCodeTo()
        assertEquals("Couldn't send the email. Try again in a minute.", page.notice())
        assertFalse(page.sendCodeButton().isEnabled)
        idleFor(60)
        assertTrue(page.sendCodeButton().isEnabled)

        page.sendCodeTo()
        assertEquals("Sign-in isn't available right now. Try again later.", page.notice())
        assertFalse(page.sendCodeButton().isEnabled)
        idleFor(10)
        assertTrue(page.sendCodeButton().isEnabled)

        page.sendCodeTo()
        assertTrue(page.shown(R.id.groupSignedOut))
        assertEquals("Sign-in isn't available right now. Try again later.", page.notice())
        assertTrue(page.sendCodeButton().isEnabled)
    }

    /** "Offline" modifies the state: the request's own wording, Send code
     *  held for the Retry-After, and the status row offline until the next
     *  health answer. */
    @Test fun `service_offline on the request holds Send code and says Offline in the status until the next health answer`() {
        requests(SignInAnswer.Offline(60))
        var health: HealthStatus = HealthStatus.NotRunning
        PtCloudSettingsActivity.healthOverrideForTest = { health }
        val page = open()
        assertEquals("Not running", page.status())

        page.sendCodeTo()

        assertTrue(page.shown(R.id.groupSignedOut))
        assertEquals(
            "PlayTranslate Cloud is offline right now, so a new account can't be made. Try again later.",
            page.notice(),
        )
        assertEquals("Offline right now", page.status())
        assertFalse(page.sendCodeButton().isEnabled)

        health = HealthStatus.Running
        resume()
        assertEquals("Running", page.status())
    }

    @Test fun `an address the server refuses is told on the field`() {
        requests(SignInAnswer.BadAddress)
        val page = open()

        page.sendCodeTo("not an address")

        assertTrue(page.shown(R.id.groupSignedOut))
        assertEquals("That doesn't look like an email address.", page.emailField().error?.toString())
        assertNull("on the field, not under the button", page.notice())
        assertEquals("not an address", page.emailField().text.toString())
    }

    /** An older Worker's 404: it has no sign-in, and with no other way in
     *  the notice is the unavailable one; nothing else opens. */
    @Test fun `a 404 on Send code shows the unavailable notice and opens nothing`() {
        requests(SignInAnswer.NotAvailable)
        val page = open()

        page.sendCodeTo()

        assertEquals("Sign-in isn't available right now. Try again later.", page.notice())
        assertTrue(page.shown(R.id.groupSignedOut))
        assertTrue("no hold", page.sendCodeButton().isEnabled)
        assertEquals(
            "the intro, the account and the status, as before",
            listOf(R.id.tvCloudIntro, R.id.headerCloudAccount, R.id.cardCloudAccount, View.NO_ID),
            page.sections(),
        )
    }

    @Test fun `no answer says the service could not be reached, at either step, and moves nothing`() {
        val page = open()
        val requests = requests(SignInAnswer.Unreachable)
        page.sendCodeTo()
        assertEquals("Couldn't reach PlayTranslate Cloud. Make sure you're online and try again.", page.notice())
        assertTrue(page.shown(R.id.groupSignedOut))
        assertTrue("no hold", page.sendCodeButton().isEnabled)

        requests.answer(CodeRequestResult.Sent(600, 60))
        page.sendCodeTo()
        verifies(SignInAnswer.Unreachable)
        page.signInWith("482913")
        assertTrue(page.shown(R.id.groupCodeSent))
        assertEquals("Couldn't reach PlayTranslate Cloud. Make sure you're online and try again.", page.notice())
        assertEquals("the code kept", "482913", page.codeField().text.toString())
    }

    @Test fun `while a request runs Send code is busy and the email field locked`() {
        val requests = requests()
        val page = open()

        page.sendCodeTo()

        assertEquals(1, requests.sent.size)
        assertBusy(page, page.sendCodeButton(), R.id.progressCloudSendCode)
        assertFalse(page.emailField().isEnabled)
        resume()
        assertBusy(page, page.sendCodeButton(), R.id.progressCloudSendCode)
        assertEquals("one request, still running", 1, requests.sent.size)
    }

    // ── Code sent ─────────────────────────────────────────────────────────

    /** Decision 11: the code before still works, so a refused Send a new
     *  code stays in code sent, with its wording and its Retry-After hold. */
    @Test fun `a failed Send a new code stays in code sent`() {
        val page = open()
        val requests = page.codeSent(CodeRequestResult.TooManyCodes(3_600))
        idleFor(60)

        page.sendNewCodeButton().performClick()
        idle()

        assertEquals(listOf(EMAIL, EMAIL), requests.sent)
        assertTrue(page.shown(R.id.groupCodeSent))
        assertEquals("We sent a code to you@example.com. It works for 10 minutes.", page.text(R.id.tvCodeSentTo))
        assertEquals("Too many codes sent to this address today. Try again tomorrow.", page.notice())
        assertFalse(page.sendNewCodeButton().isEnabled)
        page.codeField().setText("482913")
        assertTrue("the code sent before can still be tried", page.signInButton().isEnabled)
        idleFor(3_600)
        assertTrue(page.sendNewCodeButton().isEnabled)
    }

    /** Decision 11 for every refusal a Send a new code can meet, not only
     *  too_many_codes: the code before still works, so each keeps the
     *  pending sign-in, with the code field and Start Free Trial. */
    @Test fun `every refused Send a new code stays in code sent`() {
        val refusals = listOf(
            CodeRequestResult.TooManyCodes(60), SignInAnswer.RateLimited(60), SignInAnswer.Offline(60),
            CodeRequestResult.MailFailed(60), SignInAnswer.Unavailable(60), SignInAnswer.BadAddress,
            SignInAnswer.NotAvailable, SignInAnswer.Unreachable,
        )
        val page = open()
        val requests = page.codeSent(*refusals.toTypedArray())
        for ((i, refusal) in refusals.withIndex()) {
            // Past resend_after, and past the hold the refusal before set.
            idleFor(60)
            assertTrue("$refusal: Send a new code is back", page.sendNewCodeButton().isEnabled)
            page.sendNewCodeButton().performClick()
            idle()
            assertEquals("$refusal: sent", i + 2, requests.sent.size)
            assertTrue("$refusal keeps code sent", page.shown(R.id.groupCodeSent))
            assertFalse("$refusal: not signed out", page.shown(R.id.groupSignedOut))
            assertEquals("$refusal: the address", "We sent a code to you@example.com. It works for 10 minutes.", page.text(R.id.tvCodeSentTo))
        }
    }

    /** The digits typed for the old code are not the new one's: a 200
     *  clears the field, so Start Free Trial waits for the new code. */
    @Test fun `a new code's 200 replaces the pending sign-in and its window, and clears the old code's digits`() {
        val page = open()
        page.codeSent(CodeRequestResult.Sent(600, 120))
        idleFor(60)
        page.codeField().setText("482913")
        assertTrue(page.signInButton().isEnabled)

        page.sendNewCodeButton().performClick()
        idle()

        assertEquals("", page.codeField().text.toString())
        assertFalse(page.signInButton().isEnabled)
        idleFor(119)
        assertFalse(page.sendNewCodeButton().isEnabled)
        idleFor(1)
        assertTrue(page.sendNewCodeButton().isEnabled)
    }

    /** A code typed for one address is not another's: after Use a different
     *  email, the next address's 200 shows an empty code field. */
    @Test fun `another address's 200 shows an empty code field`() {
        val page = open()
        page.codeSent(CodeRequestResult.Sent(600, 60))
        page.codeField().setText("482913")
        page.findViewById<View>(R.id.btnCloudChangeEmail).performClick()
        idle()

        page.sendCodeTo("other@example.com")

        assertTrue(page.shown(R.id.groupCodeSent))
        assertEquals("", page.codeField().text.toString())
        assertFalse(page.signInButton().isEnabled)
    }

    @Test fun `Use a different email returns to signed out with the address kept for editing`() {
        val page = open()
        page.codeSent()

        page.findViewById<View>(R.id.btnCloudChangeEmail).performClick()
        idle()

        assertTrue(page.shown(R.id.groupSignedOut))
        assertFalse(page.shown(R.id.groupCodeSent))
        assertEquals(EMAIL, page.emailField().text.toString())
        assertTrue(page.sendCodeButton().isEnabled)
    }

    @Test fun `the code field's hint is XXXXXX and its button says Start Free Trial`() {
        val page = open()
        page.codeSent()

        assertEquals("XXXXXX", page.codeField().hint.toString())
        page.codeField().setText("482913")
        assertFree(page, page.signInButton(), R.id.progressCloudSignIn, "Start Free Trial")
    }

    /** The keyboard's Done is Start Free Trial: with six digits and no step
     *  running it signs in as the button does, and otherwise it does
     *  nothing, as the button would not. */
    @Test fun `Done on the code field signs in with six digits, and with five or while a step runs does nothing`() {
        val page = open()
        page.codeSent()
        val verifies = verifies()
        val field = page.codeField()
        assertEquals(EditorInfo.IME_ACTION_DONE, field.imeOptions and EditorInfo.IME_MASK_ACTION)

        field.setText("48291")
        field.onEditorAction(EditorInfo.IME_ACTION_DONE)
        idle()
        assertTrue("five digits: nothing sent", verifies.sent.isEmpty())

        field.setText("482913")
        field.onEditorAction(EditorInfo.IME_ACTION_DONE)
        idle()
        assertEquals(listOf(listOf(EMAIL, "482913", deviceLabel(Build.MANUFACTURER, Build.MODEL))), verifies.sent)
        assertBusy(page, page.signInButton(), R.id.progressCloudSignIn)

        field.onEditorAction(EditorInfo.IME_ACTION_DONE)
        idle()
        assertEquals("while the verify runs, nothing more is sent", 1, verifies.sent.size)
    }

    @Test fun `Sign in waits for six digits and sends the pending address, the code and the device's label`() {
        val page = open()
        page.codeSent()
        val verifies = verifies()

        page.codeField().setText("48291")
        assertFalse(page.signInButton().isEnabled)
        page.codeField().setText("482913")
        assertTrue(page.signInButton().isEnabled)
        page.signInButton().performClick()
        idle()

        assertEquals(listOf(listOf(EMAIL, "482913", deviceLabel(Build.MANUFACTURER, Build.MODEL))), verifies.sent)
        assertBusy(page, page.signInButton(), R.id.progressCloudSignIn)
        assertFalse(page.codeField().isEnabled)
        assertFalse("one step at a time", page.sendNewCodeButton().isEnabled)
        assertFalse(page.findViewById<View>(R.id.btnCloudChangeEmail).isEnabled)
    }

    /** The verify's 200 is the key check: the key stored first, with the
     *  record of its own fields, and the page drawn from that record with
     *  no `GET /v1/key` after it. */
    @Test fun `a verify 200 stores the key and draws from its fields with no key check`() {
        val cloudPrefs = cloudPrefs()
        val healthChecks = countHealthChecks()
        val keyChecks = keyChecks()
        val until = now() + 7 * DAY
        val page = open()
        assertEquals(1, healthChecks())
        page.codeSent()
        verifies(VerifyResult.SignedIn(KEY, KeyCheck.Accepted("trial", until)))

        page.signInWith("482913")

        assertEquals(KEY, cloudPrefs.ptCloudClientKey)
        assertEquals(
            PtCloudAccount(EMAIL, active = true, source = "trial", entitledUntilMs = until, checkedAtMs = PtCloudService.account!!.checkedAtMs),
            PtCloudService.account,
        )
        assertTrue("a first key turns both switches on", cloudPrefs.ptCloudOcrEnabled && cloudPrefs.ptCloudTranslateEnabled)
        assertTrue(page.shown(R.id.groupSignedIn))
        assertEquals(EMAIL, page.text(R.id.tvSignedInAs))
        assertEquals("Free trial until " + CooldownTimeLabel.formatDateTime(ctx, until), page.accountLine())
        assertTrue(page.shown(R.id.btnCloudJoin))
        assertEquals("", page.emailField().text.toString())
        assertEquals("", page.codeField().text.toString())
        assertEquals("health checked after the store", 2, healthChecks())
        settleIo()
        assertTrue("no key check after a verify", keyChecks.sent.isEmpty())
    }

    /** `entitled` can be false in verify's 200 (a trial used before, a lease
     *  run out): the key is stored anyway, and Join is next. */
    @Test fun `a verify 200 for an account with nothing active stores the key and shows Join`() {
        val cloudPrefs = cloudPrefs()
        val endedAt = now() - 30 * DAY
        val page = open()
        page.codeSent()
        verifies(VerifyResult.SignedIn(KEY, KeyCheck.Inactive("trial", endedAt)))

        page.signInWith("482913")

        assertEquals(KEY, cloudPrefs.ptCloudClientKey)
        assertEquals(EMAIL, page.text(R.id.tvSignedInAs))
        assertEquals("Trial ended " + CooldownTimeLabel.formatDate(endedAt), page.accountLine())
        assertTrue(page.shown(R.id.btnCloudJoin))
        assertTrue(page.shown(R.id.rowCloudCheckAgain))
        assertEquals(
            "Add a Ko-fi membership to keep PlayTranslate Cloud going beyond the trial. For convenience, " +
                "subscribe with you@example.com so it automatically reaches this account.",
            page.joinSubtitle(),
        )
    }

    @Test fun `a refused keystore write says the key was not stored`() {
        val refusing = Prefs(ctx, RefusingCodec)
        PtCloudService.installForTest(cooldown, PtCloudConfig(refusing))
        val page = open()
        page.codeSent()
        verifies(VerifyResult.SignedIn(KEY, KeyCheck.Accepted("trial", now() + 7 * DAY)))

        page.signInWith("482913")

        assertFalse(PtCloudService.config.hasKey)
        assertNull(refusing.ptCloudAccountJson)
        assertTrue(page.shown(R.id.groupSignedOut))
        assertEquals("Couldn't store the key on this device. Send a new code.", page.notice())
    }

    @Test fun `a 401 with attempts left keeps the code, and with 0 clears it`() {
        val page = open()
        page.codeSent()
        verifies(VerifyResult.CodeInvalid(3), VerifyResult.CodeInvalid(0))

        page.signInWith("482913")
        assertTrue(page.shown(R.id.groupCodeSent))
        assertEquals("That code didn't work. It may be mistyped or expired. Check it, or send a new code.", page.notice())
        assertEquals("kept for correction", "482913", page.codeField().text.toString())

        page.signInWith("482914")
        assertTrue(page.shown(R.id.groupCodeSent))
        assertEquals("That code didn't work. It may be mistyped or expired. Check it, or send a new code.", page.notice())
        assertEquals("no live code is left", "", page.codeField().text.toString())
    }

    @Test fun `service_offline at verify keeps the code`() {
        val page = open()
        page.codeSent()
        verifies(SignInAnswer.Offline(60))

        page.signInWith("482913")

        assertTrue(page.shown(R.id.groupCodeSent))
        assertEquals(
            "PlayTranslate Cloud is offline right now. Your code still works, so try again in a minute.",
            page.notice(),
        )
        assertEquals("482913", page.codeField().text.toString())
        assertEquals("Offline right now", page.status())
        assertTrue("tried again at will", page.signInButton().isEnabled)
    }

    @Test fun `account_disabled returns to signed out`() {
        val page = open()
        page.codeSent()
        verifies(VerifyResult.Disabled)

        page.signInWith("482913")

        assertTrue(page.shown(R.id.groupSignedOut))
        assertEquals(
            "This account can't sign in. If you think that's a mistake, contact PlayTranslate support.",
            page.notice(),
        )
        assertFalse(PtCloudService.config.hasKey)
    }

    @Test fun `a verify's 429 keeps the code and holds Send a new code for its Retry-After`() {
        val page = open()
        page.codeSent()
        idleFor(60)
        verifies(SignInAnswer.RateLimited(30))

        page.signInWith("482913")

        assertTrue(page.shown(R.id.groupCodeSent))
        assertEquals(
            "Too many requests. If a code arrived, enter it below; otherwise send a new one in a minute.",
            page.notice(),
        )
        assertEquals("482913", page.codeField().text.toString())
        assertFalse(page.sendNewCodeButton().isEnabled)
        idleFor(30)
        assertTrue(page.sendNewCodeButton().isEnabled)
    }

    @Test fun `a verify answer the app cannot use keeps the code`() {
        val page = open()
        page.codeSent()
        verifies(VerifyResult.BadAnswer, SignInAnswer.Unavailable(10))

        page.signInWith("482913")
        assertTrue(page.shown(R.id.groupCodeSent))
        assertEquals("Sign-in isn't available right now. Try again later.", page.notice())
        assertEquals("482913", page.codeField().text.toString())

        page.signInWith("482913")
        assertTrue(page.shown(R.id.groupCodeSent))
        assertEquals("Sign-in isn't available right now. Try again later.", page.notice())
        assertFalse(PtCloudService.config.hasKey)
    }

    /** A verify's 404 (an older Worker) returns to signed out too, with the
     *  unavailable notice: no code it sent can be verified. */
    @Test fun `a verify's refused address returns to signed out, and so does its 404, with the unavailable notice`() {
        val page = open()
        page.codeSent()
        verifies(SignInAnswer.BadAddress)
        page.signInWith("482913")
        assertTrue(page.shown(R.id.groupSignedOut))
        assertEquals("That doesn't look like an email address.", page.notice())

        page.codeSent()
        verifies(SignInAnswer.NotAvailable)
        page.signInWith("482913")
        assertTrue(page.shown(R.id.groupSignedOut))
        assertEquals("Sign-in isn't available right now. Try again later.", page.notice())
        assertEquals(
            listOf(R.id.tvCloudIntro, R.id.headerCloudAccount, R.id.cardCloudAccount, View.NO_ID),
            page.sections(),
        )
    }

    @Test fun `a 410 from either sign-in step shows the closed card`() {
        requests(SignInAnswer.Closed("PlayTranslate Cloud closed on Nov 30. Thank you for using it."))
        val page = open()
        page.sendCodeTo()
        assertTrue(page.shown(R.id.cardCloudClosed))
        assertEquals("PlayTranslate Cloud closed on Nov 30. Thank you for using it.", page.text(R.id.tvCloudClosedMessage))
        assertFalse(page.shown(R.id.cardCloudAccount))

        val again = run {
            controller!!.pause().stop().destroy()
            open()
        }
        again.codeSent()
        verifies(SignInAnswer.Closed(null))
        again.signInWith("482913")
        assertTrue(again.shown(R.id.cardCloudClosed))
        assertEquals("The service isn't available any more.", again.text(R.id.tvCloudClosedMessage))
    }

    /** A theme or locale change recreates the page (rotation does not): the
     *  pending sign-in comes back from the saved state, the code typed so
     *  far from the field's own, and the resend hold is re-armed. */
    @Test fun `the pending sign-in survives a recreate`() {
        val page = open()
        page.codeSent()
        idleFor(20)
        page.codeField().setText("4829")

        controller!!.recreate()
        idle()

        val again = controller!!.get()
        assertTrue(again.shown(R.id.groupCodeSent))
        assertEquals("We sent a code to you@example.com. It works for 10 minutes.", again.text(R.id.tvCodeSentTo))
        assertEquals("4829", again.codeField().text.toString())
        assertFalse(again.sendNewCodeButton().isEnabled)
        idleFor(39)
        assertFalse(again.sendNewCodeButton().isEnabled)
        idleFor(1)
        assertTrue(again.sendNewCodeButton().isEnabled)
    }

    /** The hold and its notice come back from the saved state too: a
     *  too-many-codes hold that runs to midnight outlives a theme change. */
    @Test fun `a hold and its notice survive a recreate`() {
        requests(CodeRequestResult.TooManyCodes(3_600))
        val page = open()
        page.sendCodeTo()
        idleFor(10)

        controller!!.recreate()
        idle()

        val again = controller!!.get()
        assertTrue(again.shown(R.id.groupSignedOut))
        assertEquals("Too many codes sent to this address today. Try again tomorrow.", again.notice())
        assertFalse(again.sendCodeButton().isEnabled)
        idleFor(3_589)
        assertFalse(again.sendCodeButton().isEnabled)
        idleFor(1)
        assertTrue(again.sendCodeButton().isEnabled)
    }

    /** A resend's Retry-After beyond the resend window holds Send a new code
     *  after a recreate as before it, not just to the window's end. */
    @Test fun `a resend's hold survives a recreate`() {
        val page = open()
        page.codeSent(SignInAnswer.RateLimited(300))
        idleFor(60)
        page.sendNewCodeButton().performClick()
        idle()
        assertFalse(page.sendNewCodeButton().isEnabled)

        controller!!.recreate()
        idle()

        val again = controller!!.get()
        assertTrue(again.shown(R.id.groupCodeSent))
        assertEquals(
            "Too many requests. If a code arrived, enter it below; otherwise send a new one in a minute.",
            again.notice(),
        )
        idleFor(299)
        assertFalse(again.sendNewCodeButton().isEnabled)
        idleFor(1)
        assertTrue(again.sendNewCodeButton().isEnabled)
    }

    // ── Signed in ─────────────────────────────────────────────────────────

    @Test fun `a trial's title is its address, the line under it Free trial until with the date and the time of day, and Join is in the card`() {
        val until = now() + 5 * DAY
        signedIn(trial(until))
        val page = open()

        assertTrue(page.shown(R.id.groupSignedIn))
        assertEquals("the address itself, not Signed in as", EMAIL, page.text(R.id.tvSignedInAs))
        assertEquals("Free trial until " + CooldownTimeLabel.formatDateTime(ctx, until), page.accountLine())
        assertEquals("in the warning colour", page.themeColor(R.attr.ptWarning), page.accountLineColor())
        assertTrue(page.shown(R.id.btnCloudJoin))
        assertNotNull(
            "in the account card",
            page.findViewById<ViewGroup>(R.id.cardCloudAccount).findViewById<View>(R.id.btnCloudJoin),
        )
        assertFalse(page.shown(R.id.rowCloudCheckAgain))
        assertFalse(page.shown(R.id.tvJoinPending))
        assertTrue(page.shown(R.id.btnCloudSignOut))
        assertFalse(page.shown(R.id.groupSignedOut))
    }

    @Test fun `a trial with no end date shows no account line`() {
        signedIn(trial(null))
        val page = open()

        assertNull(page.accountLine())
        assertTrue(page.shown(R.id.btnCloudJoin))
    }

    @Test fun `a member shows Member until with the date and the time of day, or Member when open-ended, and no Join`() {
        val until = now() + 30 * DAY
        signedIn(member(until))
        val checks = keyChecks()
        val page = open()
        assertEquals("Member until " + CooldownTimeLabel.formatDateTime(ctx, until), page.accountLine())
        assertEquals("in the subtitle's colour", page.themeColor(R.attr.ptTextMuted), page.accountLineColor())
        assertFalse(page.shown(R.id.btnCloudJoin))

        awaitCalls(checks, 1)
        checks.answer(KeyCheck.Accepted("manual", null))

        assertEquals("the record rewritten by the resume's check", "Member", page.accountLine())
        assertEquals(page.themeColor(R.attr.ptTextMuted), page.accountLineColor())
        assertEquals(EMAIL, PtCloudService.account!!.email)
        assertFalse(page.shown(R.id.btnCloudJoin))
    }

    @Test fun `a key with no record shows Signed in with a key`() {
        withKey()
        val page = open()

        assertTrue(page.shown(R.id.groupSignedIn))
        assertEquals("Signed in with a key", page.text(R.id.tvSignedInAs))
        assertNull(page.accountLine())
        assertFalse(page.shown(R.id.btnCloudJoin))
        assertFalse(page.shown(R.id.rowCloudCheckAgain))
    }

    @Test fun `an older Worker's 200 shows no account line`() {
        signedIn(trial(now() + 5 * DAY))
        val checks = keyChecks()
        val page = open()
        assertNotNull(page.accountLine())

        awaitCalls(checks, 1)
        checks.answer(KeyCheck.Accepted(null, null))

        assertNull(page.accountLine())
        assertEquals("the email kept", EMAIL, page.text(R.id.tvSignedInAs))
        assertFalse(page.shown(R.id.btnCloudJoin))
        assertFalse(page.shown(R.id.tvAccountUnchecked))
    }

    @Test fun `a 403 whose last_source is trial shows Trial ended, Check again and Join in the card`() {
        signedIn(trial(now() + 5 * DAY))
        val checks = keyChecks()
        val endedAt = now() - 3 * DAY
        val page = open()
        assertEquals("the trial's line", page.themeColor(R.attr.ptWarning), page.accountLineColor())

        awaitCalls(checks, 1)
        checks.answer(KeyCheck.Inactive("trial", endedAt))

        assertEquals("Trial ended " + CooldownTimeLabel.formatDate(endedAt), page.accountLine())
        assertEquals("ended, in the subtitle's colour", page.themeColor(R.attr.ptTextMuted), page.accountLineColor())
        assertTrue(page.shown(R.id.rowCloudCheckAgain))
        assertCheckAgainFree(page)
        assertTrue(page.shownTexts().contains("Already joined? It can take a few seconds to process."))
        assertTrue(page.shown(R.id.btnCloudJoin))
        assertTrue(page.shown(R.id.btnCloudSignOut))
        // Join sits under the account line that says why, the divider right
        // between them, and the page has no Join card of its own.
        val group = page.findViewById<ViewGroup>(R.id.groupSignedIn)
        val accountRow = group.indexOfChild(page.findViewById<View>(R.id.tvSignedInAs).parent.parent as View)
        assertTrue(page.shown(R.id.dividerCloudJoin))
        assertEquals("the divider", accountRow + 1, group.indexOfChild(page.findViewById(R.id.dividerCloudJoin)))
        assertEquals("Join", accountRow + 2, group.indexOfChild(page.findViewById(R.id.btnCloudJoin)))
        assertEquals("the Join line", accountRow + 3, group.indexOfChild(page.findViewById(R.id.tvJoinSubtitle)))
        assertTrue(page.shown(R.id.dividerCloudCheckAgain))
        assertEquals("its own divider", accountRow + 4, group.indexOfChild(page.findViewById(R.id.dividerCloudCheckAgain)))
        assertEquals("Already joined?", accountRow + 5, group.indexOfChild(page.findViewById(R.id.rowCloudCheckAgain)))
        assertEquals(
            listOf(
                R.id.tvCloudIntro, R.id.headerCloudAccount, R.id.cardCloudAccount,
                R.id.headerCloudConfiguration, R.id.cardCloudUseFor, View.NO_ID,
            ),
            page.sections(),
        )
    }

    @Test fun `a 403 with nulls shows No active membership, and with a date Membership inactive since it`() {
        signedIn(member(now() + 5 * DAY))
        val checks = keyChecks()
        val endedAt = now() - 3 * DAY
        val page = open()

        awaitCalls(checks, 1)
        checks.answer(KeyCheck.Inactive(null, null))
        assertEquals("No active membership", page.accountLine())
        assertTrue(page.shown(R.id.btnCloudJoin))
        assertTrue(page.shown(R.id.rowCloudCheckAgain))

        page.checkAgainButton().performClick()
        awaitCalls(checks, 2)
        checks.answer(KeyCheck.Inactive("kofi", endedAt))
        assertEquals("Membership inactive since " + CooldownTimeLabel.formatDate(endedAt), page.accountLine())
    }

    @Test fun `an expired trial record shows Trial ended with the date`() {
        val end = now() - 2 * DAY
        signedIn(trial(end))
        val page = open()

        assertEquals("Trial ended " + CooldownTimeLabel.formatDate(end), page.accountLine())
        assertTrue(page.shown(R.id.btnCloudJoin))
        assertTrue(page.shown(R.id.rowCloudCheckAgain))
    }

    @Test fun `the cooldown line hides while the account line says ended or inactive`() {
        signedIn(trial(now() + 5 * DAY))
        recordCooldown()
        val checks = keyChecks()
        val page = open()
        assertTrue("on the trial the line shows", page.cooldownLine().isShown)

        awaitCalls(checks, 1)
        checks.answer(KeyCheck.Inactive("trial", now() - DAY))
        assertFalse("trial ended", page.cooldownLine().isShown)

        page.checkAgainButton().performClick()
        awaitCalls(checks, 2)
        checks.answer(KeyCheck.Inactive("kofi", now() - DAY))
        assertFalse("membership inactive", page.cooldownLine().isShown)

        page.checkAgainButton().performClick()
        awaitCalls(checks, 3)
        checks.answer(KeyCheck.Accepted("kofi", now() + 30 * DAY))
        assertTrue("a member again: a rate limit's cooldown stays, and shows", page.cooldownLine().isShown)
    }

    @Test fun `a check that could not be made says so beside the account line, which keeps the record`() {
        val until = now() + 5 * DAY
        signedIn(trial(until))
        val checks = keyChecks(KeyCheck.NotChecked)
        val page = open()

        awaitCalls(checks, 1)
        assertTrue(page.shown(R.id.tvAccountUnchecked))
        assertEquals("Couldn't check the account just now.", page.text(R.id.tvAccountUnchecked))
        assertEquals("Free trial until " + CooldownTimeLabel.formatDateTime(ctx, until), page.accountLine())

        resume()
        awaitCalls(checks, 2)
        checks.answer(KeyCheck.Accepted("trial", until))
        assertFalse(page.shown(R.id.tvAccountUnchecked))
    }

    @Test fun `Check again checks the stored key, busy on its button while it runs`() {
        signedIn(ended("trial", now() - DAY))
        val checks = keyChecks(KeyCheck.Inactive("trial", now() - DAY))
        val page = open()
        awaitCalls(checks, 1)
        assertCheckAgainFree(page)

        page.checkAgainButton().performClick()

        assertCheckAgainBusy(page)
        awaitCalls(checks, 2)
        assertEquals(listOf(KEY, KEY), checks.sent)
        assertCheckAgainBusy(page)
    }

    /** Check again is the sign-out chip's twin: a square of the same size
     *  with the sync glyph in the same tint and no text, its description
     *  read as Check again, at the right end of the Already joined? row,
     *  under a divider of that row's own. */
    @Test fun `Check again is the icon chip at the right of the Already joined line, under its own divider`() {
        signedIn(ended("trial", now() - DAY))
        val checks = keyChecks(KeyCheck.Inactive("trial", now() - DAY))
        val page = open()
        awaitCalls(checks, 1)

        val chip = page.findViewById<ViewGroup>(R.id.btnCloudCheckAgain)
        val signOut = page.findViewById<ViewGroup>(R.id.btnCloudSignOut)
        assertTrue(chip.isShown)
        assertEquals("Check again", chip.contentDescription.toString())
        assertTrue("no text on it", chip.allViews.none { it is TextView })
        assertEquals("square", chip.layoutParams.width, chip.layoutParams.height)
        assertEquals("the sign-out chip's size", signOut.layoutParams.width, chip.layoutParams.width)
        assertEquals(signOut.layoutParams.height, chip.layoutParams.height)
        val glyph = chip.allViews.filterIsInstance<ImageView>().single()
        assertEquals("the sign-out glyph's tint", page.themeColor(R.attr.ptTextHint), glyph.imageTintList!!.defaultColor)
        val row = chip.parent as LinearLayout
        assertEquals(LinearLayout.HORIZONTAL, row.orientation)
        assertSame("at the row's right end", chip, row.getChildAt(row.childCount - 1))
        assertSame("after the line", row, page.findViewById<View>(R.id.tvJoinPending).parent)
        assertTrue(page.shown(R.id.dividerCloudCheckAgain))
    }

    @Test fun `a 401 on resume says the key is no longer accepted`() {
        val cloudPrefs = signedIn(trial(now() + 5 * DAY))
        val checks = keyChecks(KeyCheck.Refused)
        val page = open()

        awaitCalls(checks, 1)

        assertEquals("", cloudPrefs.ptCloudClientKey)
        assertNull(cloudPrefs.ptCloudAccountJson)
        assertFalse(cloudPrefs.ptCloudOcrEnabled)
        assertFalse(cloudPrefs.ptCloudTranslateEnabled)
        assertTrue(page.shown(R.id.groupSignedOut))
        assertTrue(page.shown(R.id.tvKeyRefused))
        assertEquals("This device's key is no longer accepted. Sign in again.", page.text(R.id.tvKeyRefused))
        idleFor(2)
        settleIo()
        assertEquals("no retry long after the key's store", 1, checks.sent.size)
    }

    /** A key just minted can answer 401 while the nearest replica catches
     *  up: a 401 within 10 s of the store is asked once more a second
     *  later, and only that answer counts. */
    @Test fun `a 401 within 10 s of a verify is retried once`() {
        val cloudPrefs = cloudPrefs()
        val page = open()
        page.codeSent()
        verifies(VerifyResult.SignedIn(KEY, KeyCheck.Accepted("trial", now() + 7 * DAY)))
        page.signInWith("482913")
        val checks = keyChecks(KeyCheck.Refused, KeyCheck.Accepted("trial", now() + 7 * DAY))

        resume()
        awaitCalls(checks, 1)

        assertEquals("not cleared on the first 401", KEY, cloudPrefs.ptCloudClientKey)
        idleFor(1)
        assertEquals(listOf(KEY, KEY), checks.sent)
        assertEquals(KEY, cloudPrefs.ptCloudClientKey)
        assertTrue(page.shown(R.id.groupSignedIn))
        assertFalse(page.shown(R.id.tvKeyRefused))
    }

    @Test fun `a 401 within 10 s of a verify that is refused again clears the key`() {
        val cloudPrefs = cloudPrefs()
        val page = open()
        page.codeSent()
        verifies(VerifyResult.SignedIn(KEY, KeyCheck.Accepted("trial", now() + 7 * DAY)))
        page.signInWith("482913")
        val checks = keyChecks(KeyCheck.Refused, KeyCheck.Refused)

        resume()
        awaitCalls(checks, 1)
        idleFor(1)

        assertEquals(2, checks.sent.size)
        assertEquals("", cloudPrefs.ptCloudClientKey)
        assertTrue(page.shown(R.id.tvKeyRefused))
    }

    /** The payment's webhook lands seconds after it: back from Join the key
     *  is checked on resume and once more 10 s later while Join is still
     *  due, and no more; a resume with no Join tap has no second check. */
    @Test fun `back from Join checks on resume and once more 10 s later while 403`() {
        val endedAt = now() - DAY
        signedIn(ended("trial", endedAt))
        val checks = keyChecks(
            KeyCheck.Inactive("trial", endedAt),
            KeyCheck.Inactive("trial", endedAt),
            KeyCheck.Accepted("kofi", now() + 38 * DAY),
        )
        val page = open()
        awaitCalls(checks, 1)
        idleFor(15)
        settleIo()
        assertEquals("no Join, no second check", 1, checks.sent.size)
        recordCooldown(CooldownCause.BILLING)

        page.joinButton().performClick()
        resume()
        awaitCalls(checks, 2)
        assertTrue(page.shown(R.id.btnCloudJoin))
        idleFor(9)
        settleIo()
        assertEquals(2, checks.sent.size)

        idleFor(1)
        awaitCalls(checks, 3)

        assertTrue(page.accountLine()!!.startsWith("Member until "))
        assertFalse(page.shown(R.id.btnCloudJoin))
        assertNull("the accepted check ends the account's cooldown", PtCloudService.unavailableUntil())
        idleFor(30)
        settleIo()
        assertEquals("one re-check only", 3, checks.sent.size)
    }

    /** A check in flight when Join is tapped (the resume's, a second old)
     *  is not the one back from the browser: it ends as it would have, with
     *  no 10 s wait while the browser is in front, and the return's resume
     *  check gets the follow-up. */
    @Test fun `Join tapped during a check leaves the follow-up to the resume's check`() {
        val endedAt = now() - DAY
        signedIn(ended("trial", endedAt))
        val checks = keyChecks()
        val page = open()
        awaitCalls(checks, 1)

        page.joinButton().performClick()
        checks.answer(KeyCheck.Inactive("trial", endedAt))
        idleFor(15)
        settleIo()
        assertEquals("no follow-up while the browser is in front", 1, checks.sent.size)

        resume()
        awaitCalls(checks, 2)
        checks.answer(KeyCheck.Inactive("trial", endedAt))
        idleFor(10)
        awaitCalls(checks, 3)
        checks.answer(KeyCheck.Accepted("kofi", now() + 38 * DAY))

        assertFalse(page.shown(R.id.btnCloudJoin))
        idleFor(30)
        settleIo()
        assertEquals("one follow-up only", 3, checks.sent.size)
    }

    /** A page the system rebuilds behind the browser (memory pressure, a
     *  theme change) keeps the re-check due after Join: the return's check
     *  runs, and once more 10 s later. */
    @Test fun `the re-check due after Join survives a recreate`() {
        val endedAt = now() - DAY
        signedIn(ended("trial", endedAt))
        val checks = keyChecks(
            KeyCheck.Inactive("trial", endedAt),
            KeyCheck.Inactive("trial", endedAt),
            KeyCheck.Accepted("kofi", now() + 38 * DAY),
        )
        val page = open()
        awaitCalls(checks, 1)
        page.joinButton().performClick()

        controller!!.recreate()
        idle()

        val again = controller!!.get()
        awaitCalls(checks, 2)
        idleFor(10)
        awaitCalls(checks, 3)
        assertFalse(again.shown(R.id.btnCloudJoin))
        idleFor(30)
        settleIo()
        assertEquals("one re-check only", 3, checks.sent.size)
    }

    @Test fun `back from Join, a membership on the first check needs no second`() {
        val endedAt = now() - DAY
        signedIn(ended("trial", endedAt))
        val checks = keyChecks(KeyCheck.Inactive("trial", endedAt), KeyCheck.Accepted("kofi", now() + 38 * DAY))
        val page = open()
        awaitCalls(checks, 1)

        page.joinButton().performClick()
        resume()
        awaitCalls(checks, 2)
        idleFor(15)
        settleIo()

        assertEquals(2, checks.sent.size)
        assertFalse(page.shown(R.id.btnCloudJoin))
    }

    @Test fun `Join opens Ko-fi, its long press copies the address, and the line under it names the email to pay with`() {
        signedIn(trial(now() + 5 * DAY))
        val page = open()
        val join = page.joinButton()
        assertEquals("Join PlayTranslate Cloud", join.text.toString())
        assertEquals(
            "Add a Ko-fi membership to keep PlayTranslate Cloud going beyond the trial. For convenience, " +
                "subscribe with you@example.com so it automatically reaches this account.",
            page.joinSubtitle(),
        )
        assertEquals(
            "centred",
            Gravity.CENTER_HORIZONTAL,
            page.findViewById<TextView>(R.id.tvJoinSubtitle).gravity and Gravity.HORIZONTAL_GRAVITY_MASK,
        )

        join.performClick()
        val opened = shadowOf(page).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, opened.action)
        assertEquals("https://ko-fi.com/playtranslate/tiers", opened.dataString)

        join.performLongClick()
        val clipboard = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        assertEquals("https://ko-fi.com/playtranslate/tiers", clipboard.primaryClip!!.getItemAt(0).text.toString())
        assertEquals("Link copied", ShadowToast.getTextOfLatestToast())
    }

    @Test fun `a record with no email asks for the key's own address under Join, and with no browser Join says so`() {
        signedIn(ended(null, null, email = null))
        val page = open()
        assertEquals("no address to title it with", "Signed in with a key", page.text(R.id.tvSignedInAs))
        assertEquals(
            "Add a Ko-fi membership to keep PlayTranslate Cloud going. For convenience, subscribe with the " +
                "email address your key belongs to so it automatically reaches this account.",
            page.joinSubtitle(),
        )

        shadowOf(ctx as Application).checkActivities(true)
        page.joinButton().performClick()

        assertEquals("No browser available", ShadowToast.getTextOfLatestToast())
    }

    /** One Join, in the account card, for each standing that shows it: on
     *  the trial and after it the line says "beyond the trial", with no
     *  active membership just "going"; each names the record's address, or
     *  the key's own when the record has none. A member and an account with
     *  no details have no Join. */
    @Test fun `Join shows for a trial, an ended trial and an inactive membership with the line for each, and not for a member or no details`() {
        cloudPrefs()
        val page = open()
        val trialLine = "Add a Ko-fi membership to keep PlayTranslate Cloud going beyond the trial. For " +
            "convenience, subscribe with you@example.com so it automatically reaches this account."
        val trialNoEmail = "Add a Ko-fi membership to keep PlayTranslate Cloud going beyond the trial. For " +
            "convenience, subscribe with the email address your key belongs to so it automatically reaches " +
            "this account."
        val inactiveLine = "Add a Ko-fi membership to keep PlayTranslate Cloud going. For convenience, " +
            "subscribe with you@example.com so it automatically reaches this account."
        val inactiveNoEmail = "Add a Ko-fi membership to keep PlayTranslate Cloud going. For convenience, " +
            "subscribe with the email address your key belongs to so it automatically reaches this account."
        val cells = listOf(
            trial(now() + 5 * DAY) to trialLine,
            trial(now() + 5 * DAY, email = null) to trialNoEmail,
            trial(now() - DAY) to trialLine,
            ended("trial", now() - DAY) to trialLine,
            ended("trial", now() - DAY, email = null) to trialNoEmail,
            ended("kofi", now() - DAY) to inactiveLine,
            ended(null, null) to inactiveLine,
            ended("kofi", now() - DAY, email = null) to inactiveNoEmail,
            member(now() + 30 * DAY) to null,
            member(null) to null,
            PtCloudAccount(email = EMAIL, active = true, checkedAtMs = now()) to null,
            null to null,
        )
        for ((account, line) in cells) {
            assertTrue(PtCloudService.saveClientKey(KEY, account))
            idle()
            val cell = "${account?.standing(now()) ?: PtCloudAccount.Standing.NO_DETAILS} $account"
            assertEquals(cell, line != null, page.shown(R.id.btnCloudJoin))
            assertEquals("$cell: the divider with Join", line != null, page.shown(R.id.dividerCloudJoin))
            assertEquals(cell, line, page.joinSubtitle())
        }
    }

    /** Sign out is the chip beside the account's title, with no text of its
     *  own: its description reads Sign out, and it confirms first. */
    @Test fun `Sign out is the chip beside the title, which confirms, clears the key and the account, and sends nothing`() {
        val cloudPrefs = signedIn(trial(now() + 5 * DAY))
        val healthChecks = countHealthChecks()
        val checks = keyChecks()
        val requests = requests()
        val verifies = verifies()
        val page = open()
        awaitCalls(checks, 1)

        val chip = page.findViewById<ViewGroup>(R.id.btnCloudSignOut)
        assertTrue(chip.isShown)
        assertEquals("Sign out", chip.contentDescription.toString())
        assertTrue("no text on it", chip.allViews.none { it is TextView })
        val row = chip.parent as LinearLayout
        assertEquals(LinearLayout.HORIZONTAL, row.orientation)
        assertSame("beside the title", row, page.findViewById<View>(R.id.tvSignedInAs).parent.parent)

        chip.performClick()
        idle()
        val texts = page.shownTexts()
        assertTrue(texts.toString(), "Sign out of PlayTranslate Cloud?" in texts)
        assertTrue(
            "This device forgets its key. Your account stays as it is, and you can sign in again with your " +
                "email address." in texts,
        )
        page.alertButton("Cancel").performClick()
        idle()
        assertEquals("cancelled: nothing changes", KEY, cloudPrefs.ptCloudClientKey)
        assertTrue(page.shown(R.id.groupSignedIn))

        page.signOut()

        assertEquals("", cloudPrefs.ptCloudClientKey)
        assertNull(cloudPrefs.ptCloudAccountJson)
        assertNull(PtCloudService.account)
        assertFalse(cloudPrefs.ptCloudOcrEnabled)
        assertFalse(cloudPrefs.ptCloudTranslateEnabled)
        assertTrue(page.shown(R.id.groupSignedOut))
        assertFalse(page.shown(R.id.tvKeyRefused))
        settleIo()
        assertEquals("only the resume's check", 1, checks.sent.size)
        assertEquals("only the resume's health check", 1, healthChecks())
        assertTrue(requests.sent.isEmpty())
        assertTrue(verifies.sent.isEmpty())
    }

    /** Decision 1: Sign out cancels the check in flight, so its answer
     *  cannot write a record for the key that is gone. */
    @Test fun `Sign out during a check leaves no record behind`() {
        val cloudPrefs = signedIn(trial(now() + 5 * DAY))
        var cancelled = false
        val answers = Channel<KeyCheck>(Channel.UNLIMITED)
        val sent = mutableListOf<String>()
        PtCloudSettingsActivity.keyCheckOverrideForTest = { key ->
            sent += key
            try {
                answers.receive()
            } catch (e: CancellationException) {
                cancelled = true
                throw e
            }
        }
        val page = open()
        val deadline = System.currentTimeMillis() + 5_000
        while (sent.isEmpty()) {
            check(System.currentTimeMillis() < deadline)
            Thread.sleep(5)
            idle()
        }

        page.signOut()
        answers.trySend(KeyCheck.Accepted("trial", now() + 5 * DAY))
        idle()

        assertTrue("the check was cancelled", cancelled)
        assertEquals("", cloudPrefs.ptCloudClientKey)
        assertNull(cloudPrefs.ptCloudAccountJson)
        assertTrue(page.shown(R.id.groupSignedOut))
    }

    // ── Closed ────────────────────────────────────────────────────────────

    @Test fun `a 410 shows the closed card with the server's message and hides sign-in`() {
        val p = signedIn(trial(now() + 5 * DAY))
        recordCooldown()
        PtCloudSettingsActivity.healthOverrideForTest = { HealthStatus.Closed("PlayTranslate Cloud closed on Nov 30. Thank you.") }
        val page = open()

        assertTrue(page.shown(R.id.cardCloudClosed))
        assertEquals("PlayTranslate Cloud has closed", page.text(R.id.tvCloudClosedTitle))
        assertEquals("PlayTranslate Cloud closed on Nov 30. Thank you.", page.text(R.id.tvCloudClosedMessage))
        assertFalse("the sign-in form and the account hidden", page.shown(R.id.cardCloudAccount))
        assertFalse("with their header", page.shown(R.id.headerCloudAccount))
        assertFalse(page.shown(R.id.btnCloudJoin))
        assertTrue("with a key, the switches' group shows", page.shown(R.id.headerCloudConfiguration))
        for (row in listOf(page.ocrRow(), page.translateRow())) {
            assertTrue("shown", row.isShown)
            assertFalse("off", row.switch().isChecked)
            assertFalse("disabled", row.switch().isEnabled)
        }
        assertTrue("the switches' prefs kept", p.ptCloudOcrEnabled && p.ptCloudTranslateEnabled)
        assertEquals("Closed", page.status())
        assertFalse(page.cooldownLine().isShown)
    }

    @Test fun `the closed card clears on the next health answer that is not closed and stays through an unreachable one`() {
        var health: HealthStatus = HealthStatus.Closed(null)
        PtCloudSettingsActivity.healthOverrideForTest = { health }
        val page = open()
        assertTrue(page.shown(R.id.cardCloudClosed))
        assertEquals("The service isn't available any more.", page.text(R.id.tvCloudClosedMessage))

        health = HealthStatus.Unreachable
        resume()
        assertTrue("an unreachable health says nothing", page.shown(R.id.cardCloudClosed))
        assertEquals("Closed", page.status())

        health = HealthStatus.NotRunning
        resume()
        assertFalse(page.shown(R.id.cardCloudClosed))
        assertTrue(page.shown(R.id.groupSignedOut))
        assertEquals("Not running", page.status())
    }

    @Test fun `a resume key check's 410 shows the closed card`() {
        signedIn(trial(now() + 5 * DAY))
        val checks = keyChecks(KeyCheck.Closed("Closed for good."))
        val page = open()

        awaitCalls(checks, 1)

        assertTrue(page.shown(R.id.cardCloudClosed))
        assertEquals("Closed for good.", page.text(R.id.tvCloudClosedMessage))
        assertEquals("the key stays", KEY, PtCloudService.config.clientKey)
    }

    // ── Status ────────────────────────────────────────────────────────────

    @Test fun `the status reads Not checked before the first check`() {
        // Built but not resumed: the check runs from onResume.
        val c = Robolectric.buildActivity(PtCloudSettingsActivity::class.java).create().start()
        try {
            assertEquals("Not checked", c.get().status())
        } finally {
            c.stop().destroy()
        }
    }

    @Test fun `the check on resume shows the service's answer`() {
        PtCloudSettingsActivity.healthOverrideForTest = { HealthStatus.Running }
        val page = open()
        assertEquals("Running", page.status())
        assertTrue("a resume check leaves Try for Free alone", page.sendCodeButton().isEnabled)
    }

    @Test fun `each health answer has its status wording`() {
        val cells = mapOf(
            HealthStatus.Running to "Running",
            HealthStatus.Starting to "Starting",
            HealthStatus.NotRunning to "Not running",
            HealthStatus.Offline to "Offline right now",
            HealthStatus.Closed("PlayTranslate Cloud has closed.") to "Closed",
            HealthStatus.Unreachable to "Can't connect",
            HealthStatus.Other(500) to "Unexpected answer (500)",
        )
        cells.forEach { (status, text) -> assertEquals(status.toString(), text, healthStatusText(ctx, status)) }
    }

    private companion object {
        const val EMAIL = "you@example.com"
        const val DAY = 24L * 60 * 60 * 1000
    }

    /** A codec whose every encryption fails, as a keystore that refuses the
     *  write: [Prefs] then keeps what was stored (here nothing). */
    private object RefusingCodec : SecretCodec {
        override fun encrypt(context: String, plaintext: String): String? = null
        override fun decrypt(context: String, stored: String): String? = null
    }
}
