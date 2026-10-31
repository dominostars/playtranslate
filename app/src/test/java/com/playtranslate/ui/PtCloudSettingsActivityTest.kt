package com.playtranslate.ui

import android.app.Activity
import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Build
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.text.Spanned
import android.text.TextPaint
import android.text.style.ClickableSpan
import android.view.Gravity
import android.view.MotionEvent
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
import com.google.android.material.checkbox.MaterialCheckBox
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
import com.playtranslate.cloud.TermsAcceptResult
import com.playtranslate.cloud.TermsResult
import com.playtranslate.cloud.VerifyResult
import com.playtranslate.cloud.deviceLabel
import com.playtranslate.security.SecretCodec
import com.playtranslate.themeColor
import com.playtranslate.translation.CooldownCause
import com.playtranslate.translation.CooldownState
import com.playtranslate.translation.GroupTranslator
import com.playtranslate.translation.TranslationBackendRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext
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
import org.robolectric.shadows.ShadowActivity
import org.robolectric.shadows.ShadowChoreographer
import org.robolectric.shadows.ShadowToast
import java.time.Duration
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The PlayTranslate Cloud page.
 *
 * Above everything, in every state, the intro paragraph naming the service;
 * the ACCOUNT and CONFIGURATION group headers title the cards under them;
 * Try for Free, Start Free Trial and Join share one shape, 56dp and bold.
 * The account card: signed out it shows the email field, the agreement box
 * with its sentence, Try for Free and the privacy sentence; a request's
 * answers move it to the code field or hold Try for Free, each in its
 * wording and for the address it answered,
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
 * key is stored and disabled and off while the account is not entitled
 * (both turned off by a check answering 403, both on again by an Accepted
 * check back from Join), seed from their prefs without resetting the service and
 * keep their state across a recreate, a flip writes its pref and resets the
 * service's cooldown through the service's one mutation for that switch
 * (the pref stored before the reset's reconcile of the shared cache), and
 * they repaint from the service's change signal when flipped elsewhere; and
 * the status row shows the health check's answer.
 *
 * The terms: signed out, the box under the address field is the
 * agreement. Try for Free with it unticked lights it in its error state and
 * sends nothing; a press on its row off the sentence's link toggles it, and
 * the link opens the terms page to read, whose Agree ticks the box and
 * sends nothing and whose Cancel leaves it. With the box ticked, Try for
 * Free fetches the terms for the device's locale and asks for the code at
 * once, the version served at that tap being what the acceptance names; a
 * 404 asks for the code with nothing to accept, a fetch that fails says
 * why (the link's too) and a 410 closes. The box keeps its tick across a
 * recreate and is unticked once a key is stored. The agreement is sent
 * once the verify stored the key, read before the store, a 401 within 10 s
 * asked once more and a 409 opening the newer text. Signed in, a check's
 * 403 terms_required shows Terms not accepted with the terms row in place
 * of Join and Already joined?, and opens the terms once an episode, which
 * only a check's Accepted ends; Agree there sends the acceptance, the
 * resume skipping its check meanwhile, and the check after it turns both
 * switches on. The terms page opens only while the page is resumed, an
 * answer for a flow the key no longer matches is dropped, and Sign out
 * cancels a terms call in flight.
 * The terms page's answers arrive through the page's result registry, in
 * production's order: the page stopped behind it, the answer at its start,
 * then its resume ([answerTerms]).
 *
 * A stored key is asserted through a fake codec installed on the service:
 * the page's own [Prefs] uses the AndroidKeyStore codec, which a JVM test has
 * none of. No call reaches the service: every test answers them through the
 * page's six seams, by default with calls that never end, which [tearDown]
 * cancels with the page, but for the terms' fetch, which answers 404 (a
 * server from before the terms), so Try for Free, which [sendCodeTo] taps
 * with the box ticked, asks for the code as it did before them. The stored
 * key's check reads the key on the IO dispatcher, a real thread, before it
 * asks, so a test waits for that check to arrive ([awaitCalls]).
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
        PtCloudSettingsActivity.termsOverrideForTest = { TermsResult.NotAvailable }
        PtCloudSettingsActivity.acceptTermsOverrideForTest = { _, _, _ -> awaitCancellation() }
    }

    @After fun tearDown() {
        controller?.pause()?.stop()?.destroy()
        controller = null
        PtCloudSettingsActivity.healthOverrideForTest = null
        PtCloudSettingsActivity.keyCheckOverrideForTest = null
        PtCloudSettingsActivity.requestCodeOverrideForTest = null
        PtCloudSettingsActivity.verifyOverrideForTest = null
        PtCloudSettingsActivity.termsOverrideForTest = null
        PtCloudSettingsActivity.acceptTermsOverrideForTest = null
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
    private fun PtCloudSettingsActivity.agreeRow() = findViewById<View>(R.id.rowCloudAgree)
    private fun PtCloudSettingsActivity.agreeBox() = findViewById<MaterialCheckBox>(R.id.cbCloudAgree)
    private fun PtCloudSettingsActivity.agreeSentence() = findViewById<TextView>(R.id.tvCloudAgree)
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

    /** Types [email], ticks the agreement box unless [agreed] is false (a
     *  box ticked already stays so), and taps Send code (Try for Free). */
    private fun PtCloudSettingsActivity.sendCodeTo(email: String = EMAIL, agreed: Boolean = true) {
        emailField().setText(email)
        if (agreed) agreeBox().isChecked = true
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

    /** The two "use for" rows as the page paints them for an account that
     *  is not entitled (2026-10-29): the CONFIGURATION group still shows,
     *  each row in it disabled with its switch off. */
    private fun PtCloudSettingsActivity.assertRowsDisabledAndOff() {
        assertTrue("the CONFIGURATION group stays", shown(R.id.headerCloudConfiguration) && shown(R.id.cardCloudUseFor))
        for (row in listOf(ocrRow(), translateRow())) {
            assertTrue("shown", row.isShown)
            assertFalse("disabled", row.isEnabled)
            assertFalse("disabled", row.switch().isEnabled)
            assertFalse("off", row.switch().isChecked)
        }
    }

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

    /** The form is the email field, the agreement (the box, unticked, and
     *  its sentence, whose last words are the one link, in the accent colour
     *  with no underline), Try for Free (which sends the code) and the one
     *  privacy sentence, centred under the button as a notice there and
     *  Join's line are: no intro line of its own, no device clause, no usage
     *  sentence and no way to paste a key, shown or hidden. */
    @Test fun `signed out shows the email field, the agreement box, Try for Free and the privacy sentence centred under it, with no intro line and no paste link`() {
        val page = open()

        assertTrue(page.shown(R.id.groupSignedOut))
        assertTrue(page.emailField().isShown)
        assertFree(page, page.sendCodeButton(), R.id.progressCloudSendCode, "Try PlayTranslate Cloud for Free")
        val texts = page.shownTexts()
        assertTrue(texts.toString(), "Email address" in texts)
        assertTrue(texts.toString(), "I agree to the Terms and Privacy" in texts)
        assertTrue(texts.toString(), "Your email address is used to send you a sign-in code." in texts)
        // The agreement's row, between the field and Try for Free.
        val group = page.findViewById<ViewGroup>(R.id.groupSignedOut)
        val row = page.agreeRow()
        assertTrue(row.isShown)
        assertEquals("under the field", group.indexOfChild(page.emailField()) + 1, group.indexOfChild(row))
        assertEquals("above Try for Free", group.indexOfChild(row) + 1, group.indexOfChild(page.sendCodeButton().parent as View))
        val box = page.agreeBox()
        assertSame(row, box.parent)
        assertFalse("unticked", box.isChecked)
        assertFalse("not in error", box.isErrorShown)
        assertTrue(box.isEnabled)
        assertEquals("", box.text.toString())
        assertEquals("Agree to the terms to continue.", box.errorAccessibilityLabel.toString())
        val sentence = page.agreeSentence()
        assertSame(row, sentence.parent)
        assertEquals("I agree to the Terms and Privacy", sentence.text.toString())
        val text = sentence.text as Spanned
        val link = page.termsLink()
        assertEquals("Terms and Privacy", text.subSequence(text.getSpanStart(link), text.getSpanEnd(link)).toString())
        val paint = TextPaint().also { link.updateDrawState(it) }
        assertEquals("in the accent colour", page.themeColor(R.attr.ptAccent), paint.color)
        assertFalse("not underlined", paint.isUnderlineText)
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
        assertFalse("nothing entitled: both switches left off", cloudPrefs.ptCloudOcrEnabled || cloudPrefs.ptCloudTranslateEnabled)
        page.assertRowsDisabledAndOff()
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
        val cloudPrefs = signedIn(trial(now() + 5 * DAY))
        val checks = keyChecks()
        val endedAt = now() - 3 * DAY
        val page = open()
        assertEquals("the trial's line", page.themeColor(R.attr.ptWarning), page.accountLineColor())
        assertTrue(page.ocrRow().switch().isChecked && page.translateRow().switch().isChecked)

        awaitCalls(checks, 1)
        checks.answer(KeyCheck.Inactive("trial", endedAt))

        // The resume's check found the account ended: both switches off,
        // the rows disabled under it, the group still there.
        assertFalse(cloudPrefs.ptCloudOcrEnabled)
        assertFalse(cloudPrefs.ptCloudTranslateEnabled)
        page.assertRowsDisabledAndOff()

        assertEquals("Trial ended " + CooldownTimeLabel.formatDate(endedAt), page.accountLine())
        assertEquals("ended, in the alert colour", page.themeColor(R.attr.ptDanger), page.accountLineColor())
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
        assertEquals("inactive, in the alert colour", page.themeColor(R.attr.ptDanger), page.accountLineColor())
        assertTrue(page.shown(R.id.btnCloudJoin))
        assertTrue(page.shown(R.id.rowCloudCheckAgain))
        page.assertRowsDisabledAndOff()

        page.checkAgainButton().performClick()
        awaitCalls(checks, 2)
        checks.answer(KeyCheck.Inactive("kofi", endedAt))
        assertEquals("Membership inactive since " + CooldownTimeLabel.formatDate(endedAt), page.accountLine())
        assertEquals("inactive with a date, in the alert colour", page.themeColor(R.attr.ptDanger), page.accountLineColor())
    }

    /** The clock words the trial's end and ends nothing: the record is the
     *  server's last word, active, so the switches stay on and the rows
     *  live until the resume's check or a route's 403 says otherwise (Codex
     *  adversarial review, 2026-10-29). */
    @Test fun `an expired trial record shows Trial ended with the date, Join and Check again, with the rows still live`() {
        val end = now() - 2 * DAY
        val cloudPrefs = signedIn(trial(end))
        val page = open()

        assertEquals("Trial ended " + CooldownTimeLabel.formatDate(end), page.accountLine())
        assertTrue(page.shown(R.id.btnCloudJoin))
        assertTrue(page.shown(R.id.rowCloudCheckAgain))
        assertTrue("the clock ends nothing", cloudPrefs.ptCloudOcrEnabled && cloudPrefs.ptCloudTranslateEnabled)
        for (row in listOf(page.ocrRow(), page.translateRow())) {
            assertTrue("live", row.isEnabled && row.switch().isEnabled)
            assertTrue("on", row.switch().isChecked)
        }
    }

    /** A lease's end passing on the clock says nothing of the membership
     *  (the next payment's lease is the server's to tell): "Member", the
     *  date left out, no Join, and the rows live. */
    @Test fun `a member whose lease end has passed shows Member with the date left out, and no Join`() {
        val cloudPrefs = signedIn(member(now() - 2 * DAY))
        val page = open()

        assertEquals("Member", page.accountLine())
        assertFalse(page.shown(R.id.btnCloudJoin))
        assertFalse(page.shown(R.id.rowCloudCheckAgain))
        assertTrue(cloudPrefs.ptCloudOcrEnabled && cloudPrefs.ptCloudTranslateEnabled)
        assertTrue(page.ocrRow().switch().isEnabled && page.ocrRow().switch().isChecked)
    }

    /** Both switches turned off while a call was out, and its 403 lands
     *  with the page open and its resume check done: the page repaints
     *  from the service's count to the ended account, its rows disabled,
     *  so no tap can turn a switch on for it (Codex review, 2026-10-29). */
    @Test fun `a 403 landing with both switches already off repaints the open page to the ended account`() {
        val until = now() + 20 * DAY
        val cloudPrefs = signedIn(member(until))
        val checks = keyChecks(KeyCheck.Accepted("kofi", until))
        val page = open()
        awaitCalls(checks, 1)
        PtCloudService.setOcrEnabled(false)
        PtCloudService.setTranslateEnabled(false)
        idle()
        assertFalse(cloudPrefs.ptCloudOcrEnabled || cloudPrefs.ptCloudTranslateEnabled)
        assertTrue("the rows stay live while entitled", page.ocrRow().isEnabled && page.ocrRow().switch().isEnabled)
        val endedAt = now() - DAY

        PtCloudService.onAccountWithheld(KEY, PtCloudService.accountGeneration, KeyCheck.Inactive("kofi", endedAt))
        idle()

        assertEquals("Membership inactive since " + CooldownTimeLabel.formatDate(endedAt), page.accountLine())
        page.assertRowsDisabledAndOff()
        assertTrue(page.shown(R.id.btnCloudJoin))
    }

    /** The resume's check is out when a route's 403 ends the account: its
     *  answer, an Accepted evaluated before the ending, is dropped by the
     *  service and the page asks once more; the second answer stands. A
     *  second drop reads as not checked, and Check again asks afresh
     *  (Codex review, 2026-10-29). */
    @Test fun `a check answered after a route's 403 is asked once more, and a second drop reads as not checked`() {
        val until = now() + 20 * DAY
        val cloudPrefs = signedIn(member(until))
        val checks = keyChecks()
        val page = open()
        awaitCalls(checks, 1)
        val endedAt = now() - DAY
        PtCloudService.onAccountWithheld(KEY, PtCloudService.accountGeneration, KeyCheck.Inactive("kofi", endedAt))
        idle()
        assertFalse(cloudPrefs.ptCloudOcrEnabled || cloudPrefs.ptCloudTranslateEnabled)

        checks.answer(KeyCheck.Accepted("kofi", until))

        awaitCalls(checks, 2)
        assertFalse("the stale Accepted stood for nothing", cloudPrefs.ptCloudOcrEnabled || cloudPrefs.ptCloudTranslateEnabled)
        assertEquals("Membership inactive since " + CooldownTimeLabel.formatDate(endedAt), page.accountLine())

        // The record moves again under the second check: dropped too, and
        // the page says it could not check, asking no third time.
        PtCloudService.onAccountWithheld(KEY, PtCloudService.accountGeneration, KeyCheck.Inactive("kofi", endedAt))
        checks.answer(KeyCheck.Accepted("kofi", until))
        settleIo()
        assertEquals(2, checks.sent.size)
        assertTrue(page.shown(R.id.tvAccountUnchecked))
        assertFalse(cloudPrefs.ptCloudOcrEnabled || cloudPrefs.ptCloudTranslateEnabled)
        page.assertRowsDisabledAndOff()

        // Check again asks afresh, and an answer built against the current
        // record stands.
        page.checkAgainButton().performClick()
        awaitCalls(checks, 3)
        checks.answer(KeyCheck.Accepted("kofi", until))
        assertFalse(page.shown(R.id.tvAccountUnchecked))
        assertTrue("entitled again: both on", cloudPrefs.ptCloudOcrEnabled && cloudPrefs.ptCloudTranslateEnabled)
        assertEquals("Member until " + CooldownTimeLabel.formatDateTime(ctx, until), page.accountLine())
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
        // A member again: the check that made the account entitled is the
        // first key's twin, whose reset drops every cooldown (2026-10-29),
        // and a cooldown recorded after it shows as on the trial.
        assertFalse("the regaining check dropped the rate limit", page.cooldownLine().isShown)
        assertNull(PtCloudService.unavailableUntil())
        recordCooldown()
        resume()
        assertTrue("a member's cooldown shows", page.cooldownLine().isShown)
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

    /** The Accepted check back from Join is the first key's twin: both
     *  switches come back on, the rows live again (2026-10-29). */
    @Test fun `back from Join, a membership on the first check needs no second and turns both switches on`() {
        val endedAt = now() - DAY
        val cloudPrefs = signedIn(ended("trial", endedAt))
        val checks = keyChecks(KeyCheck.Inactive("trial", endedAt), KeyCheck.Accepted("kofi", now() + 38 * DAY))
        val page = open()
        awaitCalls(checks, 1)
        assertFalse(cloudPrefs.ptCloudOcrEnabled || cloudPrefs.ptCloudTranslateEnabled)
        page.assertRowsDisabledAndOff()

        page.joinButton().performClick()
        resume()
        awaitCalls(checks, 2)
        idleFor(15)
        settleIo()

        assertEquals(2, checks.sent.size)
        assertFalse(page.shown(R.id.btnCloudJoin))
        assertTrue(cloudPrefs.ptCloudOcrEnabled)
        assertTrue(cloudPrefs.ptCloudTranslateEnabled)
        for (row in listOf(page.ocrRow(), page.translateRow())) {
            assertTrue("live", row.switch().isEnabled)
            assertTrue("on", row.switch().isChecked)
        }
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

    // ── The terms ─────────────────────────────────────────────────────────

    private fun termsFetches(vararg answers: TermsResult) = Calls<String, TermsResult>(*answers)
        .also { c -> PtCloudSettingsActivity.termsOverrideForTest = { c.take(it) } }

    private fun accepts(vararg answers: TermsAcceptResult) = Calls<List<String>, TermsAcceptResult>(*answers)
        .also { c ->
            PtCloudSettingsActivity.acceptTermsOverrideForTest = { key, version, locale -> c.take(listOf(key, version, locale)) }
        }

    /** The terms as the server serves them at [version], in English. */
    private fun terms(version: String = TERMS_VERSION) = TermsResult.Text(version, "en", TERMS_MARKDOWN)

    /** The terms page's launches since the last look, oldest first (the
     *  instrumentation hands every launch back newest first). */
    private fun PtCloudSettingsActivity.termsLaunches(): List<ShadowActivity.IntentForResult> =
        generateSequence { shadowOf(this).nextStartedActivityForResult }.toList().reversed()
            .filter { it.intent.component?.className == PtCloudTermsActivity::class.java.name }

    /** The terms page's Agree for [launch]: the version and the locale it was
     *  given, echoed. */
    private fun agreement(launch: ShadowActivity.IntentForResult): Intent = Intent()
        .putExtra(PtCloudTermsActivity.EXTRA_VERSION, launch.intent.getStringExtra(PtCloudTermsActivity.EXTRA_VERSION))
        .putExtra(PtCloudTermsActivity.EXTRA_LOCALE, launch.intent.getStringExtra(PtCloudTermsActivity.EXTRA_LOCALE))

    /** The terms page answers [launch] in production's order: this page
     *  paused and stopped behind it, the answer dispatched, then this page
     *  started (where the registry delivers it) and resumed. */
    private fun answerTerms(launch: ShadowActivity.IntentForResult, resultCode: Int, data: Intent?) {
        val c = controller!!
        c.pause().stop()
        c.get().activityResultRegistry.dispatchResult(launch.requestCode, resultCode, data)
        c.start().resume()
        idle()
    }

    private fun agreeTo(launch: ShadowActivity.IntentForResult) =
        answerTerms(launch, Activity.RESULT_OK, agreement(launch))

    private fun cancelTerms(launch: ShadowActivity.IntentForResult) =
        answerTerms(launch, Activity.RESULT_CANCELED, null)

    /** Try for Free with the box ticked, the terms served and the code
     *  request answered 200: the page in code sent, holding the agreement to
     *  the version served, with no terms page opened (the box is the
     *  agreement). */
    private fun PtCloudSettingsActivity.agreedAndCodeSent() {
        sendCodeTo()
        assertTrue("no page", termsLaunches().isEmpty())
        assertTrue(shown(R.id.groupCodeSent))
    }

    /** The agreement sentence's one link. */
    private fun PtCloudSettingsActivity.termsLink(): ClickableSpan {
        val text = agreeSentence().text as Spanned
        return text.getSpans(0, text.length, ClickableSpan::class.java).single()
    }

    /** Taps the agreement sentence's link, as its movement does. */
    private fun PtCloudSettingsActivity.tapTermsLink() {
        termsLink().onClick(agreeSentence())
        idle()
    }

    private fun PtCloudSettingsActivity.showTermsChip() = findViewById<View>(R.id.btnCloudShowTerms)

    /** Ticking the box is the agreement: Try for Free with it unticked,
     *  never held for it, lights the box in its error state, shakes it (a
     *  sideways wobble that settles where it was) and sends nothing, as a
     *  refused address is told on its field; a tap on the row ticks it and
     *  clears the error, and the next Try for Free goes. */
    @Test fun `Try for Free with the box unticked lights and shakes the box and sends nothing, and ticking it clears the error`() {
        val fetches = termsFetches(TermsResult.NotAvailable)
        val requests = requests()
        val page = open()

        // The shake plays out frame by frame as the looper's clock moves
        // only while Robolectric's Choreographer is paused (otherwise its
        // frames never come, and an animator stays at its first value).
        ShadowChoreographer.setPaused(true)
        ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))
        val shake = try {
            page.sendCodeTo(agreed = false)
            (1..20).map {
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(30))
                page.agreeBox().translationX
            }
        } finally {
            ShadowChoreographer.setPaused(false)
        }
        assertTrue("the box shook: $shake", shake.any { it != 0f })
        assertEquals("and settled where it was", 0f, shake.last())
        assertTrue("the box in its error state", page.agreeBox().isErrorShown)
        assertFalse(page.agreeBox().isChecked)
        assertTrue("nothing fetched", fetches.sent.isEmpty())
        assertTrue("nothing asked", requests.sent.isEmpty())
        assertTrue(page.termsLaunches().isEmpty())
        assertTrue(page.shown(R.id.groupSignedOut))
        assertNull(page.notice())
        assertFree(page, page.sendCodeButton(), R.id.progressCloudSendCode, "Try PlayTranslate Cloud for Free")

        page.agreeRow().performClick()
        idle()

        assertTrue("the row ticks it", page.agreeBox().isChecked)
        assertFalse("the tick clears the error", page.agreeBox().isErrorShown)
        page.sendCodeTo(agreed = false)
        assertEquals(1, fetches.sent.size)
        assertEquals(listOf(EMAIL), requests.sent)
    }

    /** A press as the window delivers one, through the row: off the
     *  sentence's link, and beside the text, the row takes it and toggles
     *  the box (the framework's link movement alone would take every press
     *  on the text); on the link it opens the terms and leaves the box
     *  alone. */
    @Test fun `a press on the sentence off the link toggles the box, and one on the link opens the terms without toggling it`() {
        val fetches = termsFetches()
        val page = open()
        val row = page.agreeRow() as ViewGroup
        val sentence = page.agreeSentence()
        val layout = sentence.layout
        val linkAt = sentence.text.toString().indexOf("Terms and Privacy")
        // In the row's coordinates: the middle of the character at an
        // offset, on the sentence's one line.
        fun xOf(offset: Int) = sentence.left + sentence.totalPaddingLeft +
            (layout.getPrimaryHorizontal(offset) + layout.getPrimaryHorizontal(offset + 1)) / 2
        val y = sentence.top + sentence.totalPaddingTop + (layout.getLineTop(0) + layout.getLineBottom(0)) / 2f
        fun press(x: Float) {
            val at = SystemClock.uptimeMillis()
            row.dispatchTouchEvent(MotionEvent.obtain(at, at, MotionEvent.ACTION_DOWN, x, y, 0))
            row.dispatchTouchEvent(MotionEvent.obtain(at, at + 50, MotionEvent.ACTION_UP, x, y, 0))
            idle()
        }
        assertEquals("one line", 1, layout.lineCount)

        press(xOf(2))
        assertTrue("off the link: ticked", page.agreeBox().isChecked)
        press(sentence.left + sentence.width - 1f)
        assertFalse("beside the text: unticked", page.agreeBox().isChecked)
        assertTrue(fetches.sent.isEmpty())

        press(xOf(linkAt + 3))
        assertEquals("on the link: the terms fetched", 1, fetches.sent.size)
        assertFalse("the box left alone", page.agreeBox().isChecked)
    }

    /** With the box ticked, the terms are fetched for the device's locale
     *  with Try for Free busy and the field and the agreement locked, then
     *  the code is asked for at once, with no page: the version and the
     *  locale served are what the acceptance after the verify names. */
    @Test fun `Try for Free fetches the terms for the device locale with Try for Free busy, then requests the code with the version served`() {
        cloudPrefs()
        val fetches = termsFetches()
        val requests = requests(CodeRequestResult.Sent(600, 60))
        val accepts = accepts()
        val page = open()

        page.sendCodeTo()

        assertEquals(listOf(Locale.getDefault().toLanguageTag()), fetches.sent)
        assertBusy(page, page.sendCodeButton(), R.id.progressCloudSendCode)
        assertFalse("the field locked while it runs", page.emailField().isEnabled)
        for (view in listOf(page.agreeRow(), page.agreeBox(), page.agreeSentence())) {
            assertFalse("the agreement locked too", view.isEnabled)
        }
        assertTrue("no code before the terms", requests.sent.isEmpty())

        fetches.answer(terms())

        assertEquals(listOf(EMAIL), requests.sent)
        assertTrue("no page", page.termsLaunches().isEmpty())
        assertTrue(page.shown(R.id.groupCodeSent))
        verifies(VerifyResult.SignedIn(KEY, KeyCheck.Accepted("trial", now() + 7 * DAY)))
        page.signInWith("482913")
        assertEquals(listOf(listOf(KEY, TERMS_VERSION, "en")), accepts.sent)
    }

    /** The link opens the terms to read: fetched for the device's locale
     *  (Try for Free busy meanwhile, the form's one spinner), then the terms
     *  page with the text. Agree there ticks the box and sends nothing, and
     *  reaches a page rebuilt behind the terms page (a theme change); Cancel
     *  leaves the box as it was, ticked or not. */
    @Test fun `the link opens the terms page to read, Agree there ticks the box and sends nothing, and Cancel leaves it unticked`() {
        val fetches = termsFetches()
        val requests = requests()
        val accepts = accepts()
        val page = open()

        page.tapTermsLink()

        assertEquals(listOf(Locale.getDefault().toLanguageTag()), fetches.sent)
        assertBusy(page, page.sendCodeButton(), R.id.progressCloudSendCode)
        assertTrue(page.termsLaunches().isEmpty())
        fetches.answer(terms())
        val launch = page.termsLaunches().single()
        assertEquals(TERMS_VERSION, launch.intent.getStringExtra(PtCloudTermsActivity.EXTRA_VERSION))
        assertEquals("en", launch.intent.getStringExtra(PtCloudTermsActivity.EXTRA_LOCALE))
        assertEquals(TERMS_MARKDOWN, launch.intent.getStringExtra(PtCloudTermsActivity.EXTRA_TEXT))
        assertFree(page, page.sendCodeButton(), R.id.progressCloudSendCode, "Try PlayTranslate Cloud for Free")
        assertFalse(page.agreeBox().isChecked)

        controller!!.recreate()
        idle()
        agreeTo(launch)

        val rebuilt = controller!!.get()
        assertTrue("Agree ticks the box", rebuilt.agreeBox().isChecked)
        assertTrue(rebuilt.shown(R.id.groupSignedOut))
        assertNull(rebuilt.notice())
        settleIo()
        assertTrue("nothing asked", requests.sent.isEmpty())
        assertTrue("nothing accepted", accepts.sent.isEmpty())

        rebuilt.tapTermsLink()
        fetches.answer(terms())
        cancelTerms(rebuilt.termsLaunches().single())
        assertTrue("Cancel leaves it ticked", rebuilt.agreeBox().isChecked)

        rebuilt.agreeRow().performClick()
        idle()
        assertFalse("the row unticks it", rebuilt.agreeBox().isChecked)
        rebuilt.tapTermsLink()
        fetches.answer(terms())
        cancelTerms(rebuilt.termsLaunches().single())

        assertFalse("Cancel leaves it unticked", rebuilt.agreeBox().isChecked)
        assertTrue(rebuilt.shown(R.id.groupSignedOut))
        assertEquals(3, fetches.sent.size)
        settleIo()
        assertTrue(requests.sent.isEmpty())
        assertTrue(accepts.sent.isEmpty())
    }

    /** The terms are fetched at every Try for Free and never skipped, and
     *  the agreement is the version served at the last tap: a newer text
     *  served at the second tap is the one the acceptance names. */
    @Test fun `each Try for Free fetches the terms and the acceptance after the verify carries the version served at the last tap`() {
        cloudPrefs()
        val fetches = termsFetches(terms(), terms(NEWER_TERMS))
        val requests = requests(CodeRequestResult.Sent(600, 60), CodeRequestResult.Sent(600, 60))
        val accepts = accepts()
        val page = open()
        page.agreedAndCodeSent()
        page.findViewById<View>(R.id.btnCloudChangeEmail).performClick()
        idle()

        page.sendCodeTo("other@example.com")

        assertEquals("fetched at each tap", 2, fetches.sent.size)
        assertTrue("no page either time", page.termsLaunches().isEmpty())
        assertEquals(listOf(EMAIL, "other@example.com"), requests.sent)
        assertTrue(page.shown(R.id.groupCodeSent))
        verifies(VerifyResult.SignedIn(KEY, KeyCheck.Accepted("trial", now() + 7 * DAY)))
        page.signInWith("482913")
        assertEquals("the version served at the last tap", listOf(listOf(KEY, NEWER_TERMS, "en")), accepts.sent)
    }

    /** The box keeps its tick across a recreate as the address field keeps
     *  its text, by its id with no fact of the page's (its error is not
     *  kept, as the field's error is not), and Use a different email leaves
     *  it, so the next Try for Free asks with no page; the agreement held
     *  for the verify rides the recreate with the pending sign-in. */
    @Test fun `the box and the pending sign-in survive a recreate and Use a different email`() {
        cloudPrefs()
        val fetches = termsFetches(terms(), terms(NEWER_TERMS))
        val requests = requests(CodeRequestResult.Sent(600, 60), CodeRequestResult.Sent(600, 60))
        val accepts = accepts()
        val page = open()
        page.sendCodeTo(agreed = false)
        assertTrue(page.agreeBox().isErrorShown)

        controller!!.recreate()
        idle()
        val rebuilt = controller!!.get()
        assertFalse("the error is not kept", rebuilt.agreeBox().isErrorShown)
        assertFalse(rebuilt.agreeBox().isChecked)
        rebuilt.agreedAndCodeSent()

        controller!!.recreate()
        idle()
        val again = controller!!.get()
        assertTrue("the pending sign-in", again.shown(R.id.groupCodeSent))
        again.findViewById<View>(R.id.btnCloudChangeEmail).performClick()
        idle()
        assertTrue("the box kept its tick", again.agreeBox().isChecked)
        again.sendCodeTo(agreed = false)

        assertEquals(2, fetches.sent.size)
        assertEquals(listOf(EMAIL, EMAIL), requests.sent)
        assertTrue("no page", again.termsLaunches().isEmpty())
        assertTrue(again.shown(R.id.groupCodeSent))

        controller!!.recreate()
        idle()
        verifies(VerifyResult.SignedIn(KEY, KeyCheck.Accepted("trial", now() + 7 * DAY)))
        controller!!.get().signInWith("482913")
        assertEquals("the agreement rode the recreate", listOf(listOf(KEY, NEWER_TERMS, "en")), accepts.sent)
    }

    /** The link's fetch says what Try for Free's says, under the button: a
     *  503 and no answer each their notice, which the text clears; a server
     *  from before the terms (a 404) has nothing to show, and the form stays
     *  as it is with nothing asked; a 410 shows the closed card. */
    @Test fun `a link's fetch that fails says why under the button, its 404 shows nothing, and its 410 the closed card`() {
        termsFetches(
            TermsResult.Unavailable, TermsResult.Unreachable, terms(), TermsResult.NotAvailable,
            TermsResult.Closed("PlayTranslate Cloud closed on Nov 30. Thank you for using it."),
        )
        val requests = requests()
        val page = open()

        page.tapTermsLink()
        assertEquals("Couldn't load the terms. Try again in a minute.", page.notice())
        page.tapTermsLink()
        assertEquals("Couldn't reach PlayTranslate Cloud. Make sure you're online and try again.", page.notice())
        assertTrue(page.termsLaunches().isEmpty())
        assertFree(page, page.sendCodeButton(), R.id.progressCloudSendCode, "Try PlayTranslate Cloud for Free")

        page.tapTermsLink()
        assertNull("the text came: the notice is gone", page.notice())
        cancelTerms(page.termsLaunches().single())

        page.tapTermsLink()
        assertNull("a 404 says nothing", page.notice())
        assertTrue("and opens nothing", page.termsLaunches().isEmpty())
        assertTrue(page.shown(R.id.groupSignedOut))
        assertFalse(page.agreeBox().isChecked)
        assertFree(page, page.sendCodeButton(), R.id.progressCloudSendCode, "Try PlayTranslate Cloud for Free")

        page.tapTermsLink()
        assertTrue(page.shown(R.id.cardCloudClosed))
        assertEquals("PlayTranslate Cloud closed on Nov 30. Thank you for using it.", page.text(R.id.tvCloudClosedMessage))
        assertFalse(page.shown(R.id.cardCloudAccount))
        settleIo()
        assertTrue("no code asked for", requests.sent.isEmpty())
    }

    /** The box, like the fields, is not wanted at the next sign-in, which
     *  may be another person's on this device: the verify's stored key
     *  unticks it, so Sign out shows the form empty and unticked. */
    @Test fun `storing the key unticks the box`() {
        cloudPrefs()
        val page = open()
        page.codeSent()
        assertTrue(page.agreeBox().isChecked)
        verifies(VerifyResult.SignedIn(KEY, KeyCheck.Accepted("trial", now() + 7 * DAY)))
        page.signInWith("482913")
        assertTrue(page.shown(R.id.groupSignedIn))

        page.signOut()

        assertTrue(page.shown(R.id.groupSignedOut))
        assertFalse("the box unticked", page.agreeBox().isChecked)
        assertFalse(page.agreeBox().isErrorShown)
        assertEquals("the address empty", "", page.emailField().text.toString())
    }

    /** A server from before the terms answers their route 404: there is
     *  nothing to show, so the code is asked for at once, and nothing to
     *  accept after the verify. */
    @Test fun `a 404 from the terms proceeds to the code request and sends no acceptance after the verify`() {
        cloudPrefs()
        val fetches = termsFetches(TermsResult.NotAvailable)
        val accepts = accepts()
        val page = open()
        val requests = page.codeSent()
        assertEquals(1, fetches.sent.size)
        assertEquals(listOf(EMAIL), requests.sent)
        assertTrue("no page", page.termsLaunches().isEmpty())
        verifies(VerifyResult.SignedIn(KEY, KeyCheck.Accepted("trial", now() + 7 * DAY)))

        page.signInWith("482913")

        assertTrue(page.shown(R.id.groupSignedIn))
        settleIo()
        assertTrue("nothing to accept", accepts.sent.isEmpty())
    }

    /** The code request would fail as the fetch did, so none is sent; the
     *  notice says why, and Try for Free is not held. The notice is the
     *  fetch's own: the fetch that brings the text clears it as the code is
     *  asked for. */
    @Test fun `an unavailable or unreachable terms fetch shows its notice and sends no code request, and the fetch that brings the text clears it`() {
        termsFetches(TermsResult.Unavailable, TermsResult.Unreachable, terms())
        val requests = requests()
        val page = open()

        page.sendCodeTo()
        assertEquals("Couldn't load the terms. Try again in a minute.", page.notice())
        assertTrue(page.shown(R.id.groupSignedOut))
        assertFree(page, page.sendCodeButton(), R.id.progressCloudSendCode, "Try PlayTranslate Cloud for Free")

        page.sendCodeTo()
        assertEquals("Couldn't reach PlayTranslate Cloud. Make sure you're online and try again.", page.notice())
        assertTrue("no hold", page.sendCodeButton().isEnabled)

        assertTrue(requests.sent.isEmpty())
        assertTrue(page.termsLaunches().isEmpty())

        page.sendCodeTo()
        assertNull("the text came: the failed fetch's notice is gone", page.notice())
        assertEquals("the code asked for", listOf(EMAIL), requests.sent)
        assertTrue("no page", page.termsLaunches().isEmpty())
    }

    @Test fun `a 410 from the terms shows the closed card`() {
        termsFetches(TermsResult.Closed("PlayTranslate Cloud closed on Nov 30. Thank you for using it."))
        val requests = requests()
        val page = open()

        page.sendCodeTo()

        assertTrue(page.shown(R.id.cardCloudClosed))
        assertEquals("PlayTranslate Cloud closed on Nov 30. Thank you for using it.", page.text(R.id.tvCloudClosedMessage))
        assertFalse(page.shown(R.id.cardCloudAccount))
        assertTrue(requests.sent.isEmpty())
        assertTrue(page.termsLaunches().isEmpty())
    }

    /** The acceptance goes with the key the verify answered, the version and
     *  the locale the text came with, and the page is signed in at once.
     *  The verify answers off the main thread, as the client does, so its
     *  answer comes back through the main looper and the store's change
     *  signal repaints inside the store, as in production: an agreement read
     *  after the store would be gone, and nothing would be sent. */
    @Test fun `a verify 200 after a ticked Try for Free sends the acceptance with the new key, the version and the locale, and shows the signed-in card without waiting on it`() {
        val cloudPrefs = cloudPrefs()
        termsFetches(terms())
        requests(CodeRequestResult.Sent(600, 60))
        val accepts = accepts()
        val page = open()
        page.agreedAndCodeSent()
        PtCloudSettingsActivity.verifyOverrideForTest = { _, _, _ ->
            withContext(Dispatchers.IO) { VerifyResult.SignedIn(KEY, KeyCheck.Accepted("trial", now() + 7 * DAY)) }
        }

        page.codeField().setText("482913")
        page.signInButton().performClick()
        awaitCalls(accepts, 1)

        assertEquals(listOf(listOf(KEY, TERMS_VERSION, "en")), accepts.sent)
        assertEquals(KEY, cloudPrefs.ptCloudClientKey)
        assertTrue("signed in, the acceptance still out", page.shown(R.id.groupSignedIn))
        assertTrue(page.accountLine()!!.startsWith("Free trial until "))
        assertTrue(page.ocrRow().switch().isEnabled && page.ocrRow().switch().isChecked)
        assertFalse("no terms row", page.shown(R.id.rowCloudTerms))
        assertFalse(page.shown(R.id.progressCloudSignIn))
    }

    /** A key just minted can answer 401 while the nearest replica catches
     *  up, so the acceptance has the key check's one retry, a second later,
     *  and no more. */
    @Test fun `a 401 on that acceptance within 10 s of the store is retried once`() {
        cloudPrefs()
        termsFetches(terms())
        requests(CodeRequestResult.Sent(600, 60))
        val accepts = accepts(TermsAcceptResult.Refused, TermsAcceptResult.Refused)
        val page = open()
        page.agreedAndCodeSent()
        verifies(VerifyResult.SignedIn(KEY, KeyCheck.Accepted("trial", now() + 7 * DAY)))

        page.signInWith("482913")
        assertEquals(1, accepts.sent.size)
        idleFor(1)

        assertEquals("asked once more a second later", listOf(listOf(KEY, TERMS_VERSION, "en"), listOf(KEY, TERMS_VERSION, "en")), accepts.sent)
        idleFor(30)
        settleIo()
        assertEquals("and no more", 2, accepts.sent.size)
        assertEquals("a 401 there clears nothing", KEY, PtCloudService.config.clientKey)
        assertTrue(page.shown(R.id.groupSignedIn))
    }

    /** The send outlives the page: a back press or a recreate inside the
     *  acceptance's round trip (here, inside its retry's second) would
     *  otherwise drop the agreement unrecorded, for the gate's 403 to meet
     *  the first capture. The answer that comes to a page gone acts on
     *  nothing. */
    @Test fun `the acceptance after a verify completes after the page is destroyed`() {
        cloudPrefs()
        termsFetches(terms())
        requests(CodeRequestResult.Sent(600, 60))
        val accepts = accepts(TermsAcceptResult.Refused)
        val page = open()
        page.agreedAndCodeSent()
        verifies(VerifyResult.SignedIn(KEY, KeyCheck.Accepted("trial", now() + 7 * DAY)))
        page.signInWith("482913")
        assertEquals(1, accepts.sent.size)

        controller!!.pause().stop().destroy()
        controller = null
        idleFor(1)

        assertEquals("the retry sent after the page is gone", 2, accepts.sent.size)
        accepts.answer(TermsAcceptResult.Accepted)
        assertEquals("the key stays stored", KEY, PtCloudService.config.clientKey)
    }

    /** A resume inside the acceptance's round trip after a verify checks no
     *  key: a check sent then could reach the server before the acceptance's
     *  write and answer the terms owed, turning both switches off with
     *  nothing to turn them on again, since that acceptance asks for no
     *  check. The next resume checks as ever. */
    @Test fun `a pause and resume during the acceptance after a verify sends no key check, and the next resume does`() {
        cloudPrefs()
        termsFetches(terms())
        requests(CodeRequestResult.Sent(600, 60))
        val accepts = accepts()
        val checks = keyChecks(KeyCheck.Accepted("trial", now() + 7 * DAY))
        val page = open()
        page.agreedAndCodeSent()
        verifies(VerifyResult.SignedIn(KEY, KeyCheck.Accepted("trial", now() + 7 * DAY)))
        page.signInWith("482913")
        assertEquals(1, accepts.sent.size)

        resume()
        settleIo()
        assertTrue("no check while the acceptance runs", checks.sent.isEmpty())
        assertTrue(page.shown(R.id.groupSignedIn))

        accepts.answer(TermsAcceptResult.Accepted)
        resume()
        awaitCalls(checks, 1)
        assertEquals(listOf(KEY), checks.sent)
    }

    /** The text changed between the fetch and the verify: the newer one is
     *  fetched and opened, for the signed-in account now. A Cancel there
     *  leaves the terms owed, and the next resume's 403 says so without
     *  opening them again. */
    @Test fun `a 409 on that acceptance fetches the text and opens the page again, and a Cancel there does not reopen them on the next resume's 403`() {
        val cloudPrefs = cloudPrefs()
        val fetches = termsFetches(terms(), terms(NEWER_TERMS))
        requests(CodeRequestResult.Sent(600, 60))
        accepts(TermsAcceptResult.Outdated(NEWER_TERMS))
        val checks = keyChecks(KeyCheck.TermsRequired(NEWER_TERMS))
        val page = open()
        page.agreedAndCodeSent()
        verifies(VerifyResult.SignedIn(KEY, KeyCheck.Accepted("trial", now() + 7 * DAY)))

        page.signInWith("482913")

        assertEquals("the newer text fetched", 2, fetches.sent.size)
        val newer = page.termsLaunches().single()
        assertEquals(NEWER_TERMS, newer.intent.getStringExtra(PtCloudTermsActivity.EXTRA_VERSION))

        cancelTerms(newer)
        awaitCalls(checks, 1)

        assertFalse("the 403 turned both switches off", cloudPrefs.ptCloudOcrEnabled || cloudPrefs.ptCloudTranslateEnabled)
        assertEquals("Terms not accepted", page.accountLine())
        assertTrue(page.shown(R.id.rowCloudTerms))
        settleIo()
        assertEquals("not fetched again", 2, fetches.sent.size)
        assertTrue("not opened again", page.termsLaunches().isEmpty())
    }

    /** An acceptance the server did not record after the verify changes
     *  nothing here: the page is signed in, the switches on, nothing is
     *  shown, fetched or sent again; the server's gate asks at the next
     *  check or capture. */
    @Test fun `a failed acceptance after the verify changes nothing on the page`() {
        val cloudPrefs = cloudPrefs()
        val fetches = termsFetches(terms())
        requests(CodeRequestResult.Sent(600, 60))
        val accepts = accepts(TermsAcceptResult.NotRecorded)
        val page = open()
        page.agreedAndCodeSent()
        verifies(VerifyResult.SignedIn(KEY, KeyCheck.Accepted("trial", now() + 7 * DAY)))

        page.signInWith("482913")

        assertEquals(1, accepts.sent.size)
        assertTrue(page.shown(R.id.groupSignedIn))
        assertTrue(page.accountLine()!!.startsWith("Free trial until "))
        assertFalse(page.shown(R.id.rowCloudTerms))
        assertFalse(page.shown(R.id.tvTermsNotice))
        assertTrue("both switches on", cloudPrefs.ptCloudOcrEnabled && cloudPrefs.ptCloudTranslateEnabled)
        idleFor(30)
        settleIo()
        assertEquals("not sent again", 1, accepts.sent.size)
        assertEquals("nothing fetched again", 1, fetches.sent.size)
        assertTrue(page.termsLaunches().isEmpty())
    }

    /** The service turns both switches off for the 403 (the check's answer
     *  is its word); the page says Terms not accepted in the alert colour,
     *  with the terms row and its chip in place of Join and Already joined?,
     *  hides the cooldown line, and opens the terms by itself, once: a
     *  second resume's same answer opens nothing more. */
    @Test fun `a resume's 403 terms_required turns both switches off, shows Terms not accepted in the alert colour with the terms row and no Join, and opens the terms once`() {
        val cloudPrefs = signedIn(trial(now() + 5 * DAY))
        recordCooldown()
        val fetches = termsFetches(terms())
        val checks = keyChecks()
        val page = open()
        assertTrue("on the trial, Join", page.shown(R.id.btnCloudJoin))
        assertTrue(page.cooldownLine().isShown)

        awaitCalls(checks, 1)
        checks.answer(KeyCheck.TermsRequired(TERMS_VERSION))

        assertFalse(cloudPrefs.ptCloudOcrEnabled || cloudPrefs.ptCloudTranslateEnabled)
        page.assertRowsDisabledAndOff()
        assertEquals("Terms not accepted", page.accountLine())
        assertEquals("in the alert colour", page.themeColor(R.attr.ptDanger), page.accountLineColor())
        assertTrue(page.shown(R.id.dividerCloudTerms))
        assertTrue(page.shown(R.id.rowCloudTerms))
        assertEquals("Read and accept the terms to keep using PlayTranslate Cloud.", page.text(R.id.tvTermsPending))
        assertFalse("no notice", page.shown(R.id.tvTermsNotice))
        assertFalse("no Join", page.shown(R.id.btnCloudJoin))
        assertFalse(page.shown(R.id.dividerCloudJoin))
        assertFalse("no Already joined?", page.shown(R.id.rowCloudCheckAgain))
        assertFalse("the cooldown line hidden", page.cooldownLine().isShown)
        // The chip is the Check again chip's twin, at the right end of its
        // row, the row under Already joined?'s place in the account card.
        val chip = page.findViewById<ViewGroup>(R.id.btnCloudShowTerms)
        val signOut = page.findViewById<ViewGroup>(R.id.btnCloudSignOut)
        assertEquals("Show terms", chip.contentDescription.toString())
        assertTrue("no text on it", chip.allViews.none { it is TextView })
        assertEquals("the sign-out chip's size", signOut.layoutParams.width, chip.layoutParams.width)
        assertEquals(signOut.layoutParams.height, chip.layoutParams.height)
        val glyph = chip.allViews.filterIsInstance<ImageView>().single()
        assertEquals(page.themeColor(R.attr.ptTextHint), glyph.imageTintList!!.defaultColor)
        val row = chip.parent as LinearLayout
        assertSame(chip, row.getChildAt(row.childCount - 1))
        val group = page.findViewById<ViewGroup>(R.id.groupSignedIn)
        val afterCheckAgain = group.indexOfChild(page.findViewById(R.id.rowCloudCheckAgain))
        assertEquals(afterCheckAgain + 1, group.indexOfChild(page.findViewById(R.id.dividerCloudTerms)))
        assertEquals(afterCheckAgain + 2, group.indexOfChild(row))
        assertEquals(afterCheckAgain + 3, group.indexOfChild(page.findViewById(R.id.tvTermsNotice)))
        // Opened by itself, with the text fetched for the device's locale.
        assertEquals(listOf(Locale.getDefault().toLanguageTag()), fetches.sent)
        assertEquals(TERMS_MARKDOWN, page.termsLaunches().single().intent.getStringExtra(PtCloudTermsActivity.EXTRA_TEXT))

        resume()
        awaitCalls(checks, 2)
        checks.answer(KeyCheck.TermsRequired(TERMS_VERSION))
        settleIo()
        assertEquals("once", 1, fetches.sent.size)
        assertTrue(page.termsLaunches().isEmpty())
    }

    /** Opened once an episode by itself: after a Cancel the resume's same
     *  403 shows the row and opens nothing, the chip opens the terms on
     *  demand, and a Cancel after the chip opens nothing by itself either. */
    @Test fun `Cancel then a resume's same 403 does not reopen the terms, the chip does, and a Cancel after the chip does not reopen them either`() {
        signedIn(trial(now() + 5 * DAY))
        val fetches = termsFetches(terms(), terms())
        val checks = keyChecks(
            KeyCheck.TermsRequired(TERMS_VERSION), KeyCheck.TermsRequired(TERMS_VERSION), KeyCheck.TermsRequired(TERMS_VERSION),
        )
        val accepts = accepts()
        val page = open()
        awaitCalls(checks, 1)

        cancelTerms(page.termsLaunches().single())
        awaitCalls(checks, 2)
        settleIo()
        assertEquals(1, fetches.sent.size)
        assertTrue("not reopened", page.termsLaunches().isEmpty())
        assertTrue(page.shown(R.id.rowCloudTerms))
        assertTrue(page.showTermsChip().isEnabled)

        page.showTermsChip().performClick()
        idle()
        assertEquals(2, fetches.sent.size)
        cancelTerms(page.termsLaunches().single())
        awaitCalls(checks, 3)
        settleIo()

        assertEquals(2, fetches.sent.size)
        assertTrue("not reopened", page.termsLaunches().isEmpty())
        assertTrue(accepts.sent.isEmpty())
    }

    /** The answer arrives at the page's start, so the acceptance is out when
     *  the resume would check the key: that check could reach the server
     *  before the acceptance's write and answer the terms owed, so the resume
     *  asks nothing, and the acceptance's own check is the next word. */
    @Test fun `Agree while signed in, delivered before the resume, sends one acceptance and one check and no second launch, the resume skipping its key check while the acceptance runs`() {
        val cloudPrefs = signedIn(trial(now() + 5 * DAY))
        val fetches = termsFetches(terms())
        val checks = keyChecks(KeyCheck.TermsRequired(TERMS_VERSION))
        val accepts = accepts()
        val page = open()
        awaitCalls(checks, 1)

        agreeTo(page.termsLaunches().single())
        awaitCalls(accepts, 1)
        settleIo()

        assertEquals(listOf(listOf(KEY, TERMS_VERSION, "en")), accepts.sent)
        assertEquals("the resume asked nothing", 1, checks.sent.size)
        assertFalse("the chip waits on the acceptance", page.showTermsChip().isEnabled)
        assertTrue(page.shown(R.id.progressCloudShowTerms))

        accepts.answer(TermsAcceptResult.Accepted)
        awaitCalls(checks, 2)
        checks.answer(KeyCheck.Accepted("trial", now() + 5 * DAY))
        settleIo()

        assertEquals("one acceptance", 1, accepts.sent.size)
        assertEquals("one check after it", 2, checks.sent.size)
        assertEquals(1, fetches.sent.size)
        assertTrue("no second launch", page.termsLaunches().isEmpty())
        assertTrue(cloudPrefs.ptCloudOcrEnabled && cloudPrefs.ptCloudTranslateEnabled)
    }

    /** The acceptance's 200 does not end the episode; its check's Accepted
     *  does. A stale 403 to that check (a word from before the write) opens
     *  nothing; the next check's Accepted turns both switches on through the
     *  service, the row goes and Join is back for the trial; and a later
     *  change of the terms opens them once again. */
    @Test fun `an Accepted check after the acceptance turns both switches on, hides the row and clears the shown fact`() {
        val cloudPrefs = signedIn(trial(now() + 5 * DAY))
        val fetches = termsFetches(terms(), terms(NEWER_TERMS))
        val checks = keyChecks(KeyCheck.TermsRequired(TERMS_VERSION))
        accepts(TermsAcceptResult.Accepted)
        val page = open()
        awaitCalls(checks, 1)
        agreeTo(page.termsLaunches().single())

        awaitCalls(checks, 2)
        checks.answer(KeyCheck.TermsRequired(TERMS_VERSION))
        settleIo()
        assertTrue("a stale 403 opens nothing", page.termsLaunches().isEmpty())
        assertEquals(1, fetches.sent.size)
        assertTrue(page.shown(R.id.rowCloudTerms))

        resume()
        awaitCalls(checks, 3)
        checks.answer(KeyCheck.Accepted("trial", now() + 5 * DAY))

        assertTrue("both on again", cloudPrefs.ptCloudOcrEnabled && cloudPrefs.ptCloudTranslateEnabled)
        for (row in listOf(page.ocrRow(), page.translateRow())) {
            assertTrue("live", row.switch().isEnabled)
            assertTrue("on", row.switch().isChecked)
        }
        assertFalse(page.shown(R.id.rowCloudTerms))
        assertFalse(page.shown(R.id.dividerCloudTerms))
        assertTrue(page.accountLine()!!.startsWith("Free trial until "))
        assertTrue("Join is back for the trial", page.shown(R.id.btnCloudJoin))

        resume()
        awaitCalls(checks, 4)
        checks.answer(KeyCheck.TermsRequired(NEWER_TERMS))
        assertEquals(2, fetches.sent.size)
        assertEquals(NEWER_TERMS, page.termsLaunches().single().intent.getStringExtra(PtCloudTermsActivity.EXTRA_VERSION))
    }

    /** Each answer of a signed-in terms call, under the terms row: a 409 on
     *  the acceptance fetches the newer text and opens it; an acceptance not
     *  recorded, a fetch's 503 and a fetch with no answer each say so, the
     *  last naming the service, and a fetch that opens the page clears the
     *  notice; a 401 on the acceptance acts only through the check that
     *  follows it, which clears the key. */
    @Test fun `a 409 while signed in fetches and reopens, a 503 shows its notice under the row, an unreachable fetch shows the unreachable notice with the service's name, and a 401 clears the key through the check`() {
        val cloudPrefs = signedIn(trial(now() + 5 * DAY))
        val fetches = termsFetches(
            terms(), terms(NEWER_TERMS), TermsResult.Unavailable, TermsResult.Unreachable, terms(NEWER_TERMS),
        )
        val checks = keyChecks(KeyCheck.TermsRequired(TERMS_VERSION), KeyCheck.Refused)
        val accepts = accepts(TermsAcceptResult.Outdated(NEWER_TERMS), TermsAcceptResult.NotRecorded, TermsAcceptResult.Refused)
        val page = open()
        awaitCalls(checks, 1)

        agreeTo(page.termsLaunches().single())
        awaitCalls(accepts, 1)
        assertEquals("the newer text fetched", 2, fetches.sent.size)
        val newer = page.termsLaunches().single()
        assertEquals(NEWER_TERMS, newer.intent.getStringExtra(PtCloudTermsActivity.EXTRA_VERSION))

        agreeTo(newer)
        awaitCalls(accepts, 2)
        assertEquals(listOf(KEY, NEWER_TERMS, "en"), accepts.sent[1])
        assertTrue(page.shown(R.id.tvTermsNotice))
        assertEquals("Couldn't record your acceptance. Try again.", page.text(R.id.tvTermsNotice))
        assertTrue(page.shown(R.id.rowCloudTerms))
        settleIo()
        assertEquals("no check after it", 1, checks.sent.size)

        page.showTermsChip().performClick()
        idle()
        assertEquals("Couldn't load the terms. Try again in a minute.", page.text(R.id.tvTermsNotice))
        assertTrue(page.shown(R.id.tvTermsNotice))
        page.showTermsChip().performClick()
        idle()
        assertEquals("Couldn't reach PlayTranslate Cloud. Make sure you're online and try again.", page.text(R.id.tvTermsNotice))
        assertTrue(page.termsLaunches().isEmpty())

        page.showTermsChip().performClick()
        idle()
        assertFalse("the fetch that opens the page clears the notice", page.shown(R.id.tvTermsNotice))
        agreeTo(page.termsLaunches().single())
        awaitCalls(accepts, 3)
        awaitCalls(checks, 2)

        assertEquals("", cloudPrefs.ptCloudClientKey)
        assertTrue(page.shown(R.id.groupSignedOut))
        assertTrue(page.shown(R.id.tvKeyRefused))
    }

    /** A fetch that completes while the page is not in front (a fetch
     *  outlives a pause) launches nothing, so the terms never open over
     *  another screen, and leaves the shown fact as it was: the next
     *  resume's 403 opens them. */
    @Test fun `a fetch that completes while the page is paused launches nothing, and the next resume's 403 opens the terms`() {
        signedIn(trial(now() + 5 * DAY))
        val fetches = termsFetches()
        val checks = keyChecks(KeyCheck.TermsRequired(TERMS_VERSION), KeyCheck.TermsRequired(TERMS_VERSION))
        val page = open()
        awaitCalls(checks, 1)
        assertEquals("the fetch is out", 1, fetches.sent.size)
        assertTrue(page.shown(R.id.progressCloudShowTerms))

        controller!!.pause()
        fetches.answer(terms())

        assertTrue("nothing launched while paused", page.termsLaunches().isEmpty())

        controller!!.resume()
        idle()
        awaitCalls(checks, 2)
        assertEquals(2, fetches.sent.size)
        fetches.answer(terms())
        assertEquals(TERMS_VERSION, page.termsLaunches().single().intent.getStringExtra(PtCloudTermsActivity.EXTRA_VERSION))
    }

    /** An answer acts only for the flow that opened the terms page while
     *  that flow still matches the key: the account's Agree after another
     *  copy signed out sends nothing (and nothing waits on it), and the
     *  link's Agree after another copy signed in ticks no box and asks for
     *  nothing. */
    @Test fun `an agreement whose flow no longer matches the key (a sign-out in another copy while the terms were up) is dropped`() {
        signedIn(trial(now() + 5 * DAY))
        termsFetches(terms(), terms())
        val checks = keyChecks(KeyCheck.TermsRequired(TERMS_VERSION))
        val accepts = accepts()
        val requests = requests()
        val page = open()
        awaitCalls(checks, 1)
        val account = page.termsLaunches().single()
        PtCloudService.saveClientKey("")
        idle()

        page.activityResultRegistry.dispatchResult(account.requestCode, Activity.RESULT_OK, agreement(account))

        assertFree(page, page.sendCodeButton(), R.id.progressCloudSendCode, "Try PlayTranslate Cloud for Free")
        idle()
        settleIo()
        assertTrue("no acceptance for a key that is gone", accepts.sent.isEmpty())
        assertTrue(page.shown(R.id.groupSignedOut))

        page.tapTermsLink()
        val signIn = page.termsLaunches().single()
        assertTrue(PtCloudService.saveClientKey(KEY, trial(now() + 5 * DAY)))
        idle()

        page.activityResultRegistry.dispatchResult(signIn.requestCode, Activity.RESULT_OK, agreement(signIn))
        idle()
        settleIo()

        assertFalse("no box ticked for a page signed in", page.agreeBox().isChecked)
        assertTrue("no code asked for while signed in", requests.sent.isEmpty())
        assertTrue(accepts.sent.isEmpty())
        assertTrue(page.shown(R.id.groupSignedIn))
    }

    /** Sign out cancels the terms calls for the key it forgets, a fetch and
     *  an acceptance after a signed-in Agree, whose answers would otherwise
     *  act for a key that is gone; the acceptance after a verify completes
     *  for the key it was for (the agreement is the person's, recorded
     *  whatever the page does next), its answer acting on nothing here. */
    @Test fun `Sign out cancels a terms call in flight, and the acceptance after a verify completes with its answer acting on nothing`() {
        val cancelled = mutableListOf<String>()
        val fetches = Calls<String, TermsResult>()
        PtCloudSettingsActivity.termsOverrideForTest = { locale ->
            try {
                fetches.take(locale)
            } catch (e: CancellationException) {
                cancelled += "fetch"
                throw e
            }
        }
        val accepts = Calls<List<String>, TermsAcceptResult>()
        PtCloudSettingsActivity.acceptTermsOverrideForTest = { key, version, locale ->
            try {
                accepts.take(listOf(key, version, locale))
            } catch (e: CancellationException) {
                cancelled += "acceptance"
                throw e
            }
        }
        val cloudPrefs = signedIn(trial(now() + 5 * DAY))
        val checks = keyChecks(KeyCheck.TermsRequired(TERMS_VERSION), KeyCheck.TermsRequired(TERMS_VERSION))
        val page = open()
        awaitCalls(checks, 1)
        assertEquals("a fetch is out", 1, fetches.sent.size)

        page.signOut()

        assertEquals(listOf("fetch"), cancelled)
        assertTrue(page.shown(R.id.groupSignedOut))
        assertFree(page, page.sendCodeButton(), R.id.progressCloudSendCode, "Try PlayTranslate Cloud for Free")

        // Signed in again: the acceptance after Agree.
        assertTrue(PtCloudService.saveClientKey(KEY, trial(now() + 5 * DAY)))
        idleFor(11)
        resume()
        awaitCalls(checks, 2)
        fetches.answer(terms())
        agreeTo(page.termsLaunches().single())
        awaitCalls(accepts, 1)

        page.signOut()

        assertEquals(listOf("fetch", "acceptance"), cancelled)
        assertTrue(page.shown(R.id.groupSignedOut))
        assertFree(page, page.sendCodeButton(), R.id.progressCloudSendCode, "Try PlayTranslate Cloud for Free")

        // The acceptance after a verify, agreed by the ticked tap.
        requests(CodeRequestResult.Sent(600, 60))
        page.sendCodeTo()
        fetches.answer(terms())
        assertTrue(page.shown(R.id.groupCodeSent))
        verifies(VerifyResult.SignedIn(KEY, KeyCheck.Accepted("trial", now() + 7 * DAY)))
        page.signInWith("482913")
        awaitCalls(accepts, 2)

        page.signOut()

        assertEquals("the send after a verify completes for the key it was for", listOf("fetch", "acceptance"), cancelled)
        assertEquals("", cloudPrefs.ptCloudClientKey)
        assertTrue(page.shown(R.id.groupSignedOut))
        // Its answer, to a page signed out meanwhile, acts on nothing: a
        // newer text fetches and opens no terms.
        val fetchesBefore = fetches.sent.size
        page.termsLaunches()
        accepts.answer(TermsAcceptResult.Outdated(NEWER_TERMS))
        settleIo()
        assertEquals("no fetch for a key that is gone", fetchesBefore, fetches.sent.size)
        assertTrue(page.termsLaunches().isEmpty())
        assertTrue(page.shown(R.id.groupSignedOut))
    }

    @Test fun `the hub digest and the account line agree on Terms not accepted`() {
        signedIn(PtCloudAccount(email = EMAIL, active = true, termsRequired = true, checkedAtMs = now()))
        val page = open()

        assertEquals("Terms not accepted", page.accountLine())
        assertEquals("Terms not accepted", RootSettingsViewModel(ctx as Application).state.value.cloudSummary)
        assertTrue(page.shown(R.id.rowCloudTerms))
        page.assertRowsDisabledAndOff()
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
        const val TERMS_VERSION = "2026-10-30"
        const val NEWER_TERMS = "2026-11-15"
        const val TERMS_MARKDOWN = "# PlayTranslate Cloud: Terms and Privacy\n\nVersion: 2026-10-30\n\n" +
            "**Your account.** One account per person."
    }

    /** A codec whose every encryption fails, as a keystore that refuses the
     *  write: [Prefs] then keeps what was stored (here nothing). */
    private object RefusingCodec : SecretCodec {
        override fun encrypt(context: String, plaintext: String): String? = null
        override fun decrypt(context: String, stored: String): String? = null
    }
}
