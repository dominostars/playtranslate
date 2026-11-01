package com.playtranslate.ui

import android.content.ClipData
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.text.InputFilter
import android.text.Spannable
import android.text.SpannableString
import android.text.Spanned
import android.text.TextPaint
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.text.style.TypefaceSpan
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.core.graphics.ColorUtils
import androidx.core.net.toUri
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.materialswitch.MaterialSwitch
import com.playtranslate.R
import com.playtranslate.cloud.CodeRequestResult
import com.playtranslate.cloud.HealthStatus
import com.playtranslate.cloud.KeyCheck
import com.playtranslate.cloud.PtCloudAccount
import com.playtranslate.cloud.PtCloudAccount.Standing
import com.playtranslate.cloud.PtCloudClient
import com.playtranslate.cloud.PtCloudConfig
import com.playtranslate.cloud.PtCloudService
import com.playtranslate.cloud.SignInAnswer
import com.playtranslate.cloud.TermsAcceptResult
import com.playtranslate.cloud.TermsAgreement
import com.playtranslate.cloud.TermsResult
import com.playtranslate.cloud.VerifyResult
import com.playtranslate.cloud.deviceLabel
import com.playtranslate.themeColor
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Settings → Configure → PlayTranslate Cloud (debug builds only, like the
 * cell that opens it): what the service does, the account and the service's
 * status. The translation-error pill's gear opens it too, for a
 * PlayTranslate Cloud error, and so do the Capture & overlay page's cloud row
 * and the OCR picker's cloud entry when the switch is turned on with no key
 * saved. The service's address is fixed ([PtCloudClient]); the page has no
 * field for it. The server's side is docs/api.md in the playtranslate-cloud
 * repository ("Sign-in", "Key check", "Terms", "Accepting the terms",
 * "Service closed"), and the page's states and transitions its
 * docs/client-signin-handoff.md, "The page", less the key card for a key
 * given by hand, which this page does not have, and its
 * docs/client-integration-brief.md, the terms gate (2026-10-30). The page's
 * look is the cloud page handoff of 2026-10-31 (pt-cloud-page.md): one
 * feature list in every state, a description signed out, the two switches
 * signed in, and the thing the Join card turns back on once access lapses.
 *
 *  - Signed out, the headline and the intro paragraph say what the service
 *    is; under them the sign-in card, with no header: an email address, a
 *    six-digit code mailed to it (Try for Free, [PtCloudClient.requestCode]),
 *    and the code exchanged for the client key (Start Free Trial, or the
 *    keyboard's Done, [PtCloudClient.verifyCode]); under it the FEATURES
 *    group as a description, its rows with no switch, and the privacy
 *    footnote. Signed in, neither the headline nor the intro shows.
 *    The key is stored before anything else, through the service
 *    ([PtCloudService.saveClientKey], with the record of the verify's own
 *    fields; a first key turns both switches on), since no route answers it
 *    again, and a write the keystore refused says so. The verify is the
 *    key's check: none is sent after it. The pending sign-in (the address,
 *    when the code went out, how long it works, when a new one may be asked
 *    for) survives a recreate; Send a new code waits for the server's
 *    window, and a refused one keeps the code before it, which still works.
 *  - Signed in, the ACCOUNT group's card titles itself with the standing
 *    read from the record kept beside the key ([PtCloudAccount.standing]):
 *    until when the trial or the membership runs (a date and the time of
 *    day), or since when it ended (in the alert colour), the account's
 *    address under it; with no standing to say (no details, the terms owed,
 *    whose 403 carries no dates) the address is the title. The sign-out chip at its right forgets
 *    the key on this device after a confirmation and sends nothing (there is
 *    no revoke route).
 *  - Join, the Ko-fi tiers page, in a card of its own: on the trial, under
 *    the AFTER THE TRIAL header below the service status, the outlined
 *    button (the trial still serves); once the trial ended or no membership
 *    is active, right under the features it turns back on, the accent
 *    button, and under a divider "Already joined?" with the Check again
 *    chip at its right. Its centred line names the address to pay with. The
 *    service status hides once the account lapsed, since the account card
 *    says why the service refuses; a tap on a greyed feature then brings the
 *    Join card into view and flashes Join ([pointAtJoin]).
 *  - Each resume checks the stored key beside health ([PtCloudClient.checkKey]
 *    on the key read off the main thread, never sent when it reads back
 *    unusable) and hands the answer to the service
 *    ([PtCloudService.onStoredKeyChecked]: the record rewritten, an account
 *    cooldown it disproves dropped, both switches off for an account that
 *    is not admitted (an entitlement ended, the terms not accepted) and on
 *    again for one an answer admits again, a key the server no longer
 *    accepts cleared, each only while the key checked is still the stored
 *    one), except while a terms call runs, whose acceptance checks the key
 *    itself once answered. A
 *    401 that comes within about 10 s of a key's store is retried once a
 *    second later (a key just minted can meet a replica that has not seen
 *    it yet), and after Join the key is checked once more 10 s later while
 *    Join is still due (the payment's webhook lands seconds after it).
 *  - The terms ("Terms" and "Accepting the terms"), one text the server
 *    serves by locale and versions by date, shown on the terms page
 *    ([PtCloudTermsActivity]), which this page launches for its answer
 *    while it is resumed, never from behind another screen. Signed out,
 *    the box under the address field is the agreement (ruled 2026-10-30):
 *    a tap on its row off the sentence's link toggles it, and the link
 *    opens the terms to read, Agree there ticking the box and sending
 *    nothing, Cancel leaving it as it was. Try for Free first checks the
 *    address's shape (x@y.z), then the box, and sends nothing while either
 *    fails, saying why under the button, where a server's answer goes too,
 *    until the next keystroke or toggle (an unticked box also lights in its
 *    error state); with both passing, Try for Free fetches the
 *    terms ([PtCloudClient.fetchTerms], for the device's locale) and asks
 *    for the code at once, the version and the locale served being what
 *    was agreed to ([agreedTerms]). A server from before the terms (a 404)
 *    asks for the code with nothing to accept, and a fetch that fails asks
 *    for none and says why under the button. The agreement rides the
 *    sign-in and is sent once the verify stored the key
 *    ([PtCloudClient.acceptTerms], with the key check's one retry for a
 *    replica's lag), the page waiting on nothing: the server holds the
 *    record, and its gate asks again if the send failed; the stored key
 *    unticks the box for the next sign-in. Signed in, a check that answers
 *    the terms not accepted (the service turned both switches off) shows
 *    the terms banner above everything, "Terms not accepted" in the warning
 *    colour with its Show terms button, the page's one call to action (no
 *    Join, no service status), and the paused line under the greyed
 *    features, and opens the terms by itself once per episode
 *    ([termsShown]); Show terms opens them again. Agree there sends the
 *    acceptance with the stored key and then checks the key, whose Accepted
 *    turns both switches back on.
 *  - A 410 `service_closed` from any call shows the closed card in place of
 *    the sign-in card or of the ACCOUNT group, with the server's message
 *    verbatim and the switches off, until a health answer that is neither
 *    closed nor unreachable says the service is open again.
 *  - The FEATURES group's two rows, one per route: reading the screen
 *    ([PtCloudService.setOcrEnabled], which the Capture & overlay page's
 *    cloud row and the OCR picker write too) and translating
 *    ([PtCloudService.setTranslateEnabled], which the service's row on the
 *    Translation services page writes too), each with its icon. Signed in
 *    each row's switch is its route's, independent, no master, and goes
 *    through its one mutation, which stores the pref and resets the service
 *    (its cooldown and the pill's errors), since either direction means "try
 *    it again now". Signed out the rows describe the service, with no switch
 *    and no tap: signing in is the first step, and its stored key turns both
 *    on. While the service is closed both rows show disabled and off, in the
 *    disabled colours (no alpha), and so they do while the account is not
 *    admitted (the trial ended, no membership, the terms not accepted;
 *    [com.playtranslate.cloud.PtCloudConfig.admitted]): the withholding
 *    turned both switches off, and the check that answers Accepted, back
 *    from Join or after the terms' acceptance, turns both on (2026-10-29).
 *    Painted with their listeners detached, so building or restoring the
 *    page writes nothing and resets nothing, and repainted, with the rest of
 *    the page, from the service's own change signal
 *    ([PtCloudService.changes]) on each gesture and each resume, as the
 *    services row is, so a switch flipped from another surface (the picker
 *    over the game, a sign-in) shows here without the page repainting after
 *    a mutation itself.
 *  - The stored key is never on screen (nobody needs to see it, and a key
 *    on screen is a key in a screenshot): no field takes or shows a key.
 *  - The status row is the server's answer to `GET /health`
 *    ([PtCloudClient.health], on the service's one client,
 *    [PtCloudService.client]), asked on resume and after a key is stored;
 *    capture never asks it. Its dot is the accent while the service runs and
 *    the warning colour otherwise. It shows signed in while the account is
 *    served (on the trial, a member, no details) once a check has run, never
 *    while closed. A sign-in step's `service_offline` reads there as offline
 *    until the next answer. Below it, while the service's cooldown runs, the
 *    line a Translation services row shows for its service's cooldown
 *    ([cooldownLine]).
 *
 * What the page knows of its own lives in a few facts ([pending],
 * [closedMessage], [hold], [notice], [keyRefused], [accountUnchecked],
 * [recheckAfterJoin], [agreedTerms], [termsFlow], [termsShown],
 * [termsNotice], [busy], [status]), each set by the event that learns
 * it; [render] is the only painter: every event sets its facts and calls
 * it, and it derives every visibility, every text that changes, the status
 * dot's colour and every enabled state from them, the stored key and its
 * record (the fixed texts are set once, when the page is built).
 * What is typed and ticked is input, the views' own and kept by them
 * across a recreate (the address, the code, the agreement box), which
 * render never paints: a few events reset it (a stored key clears the
 * fields and unticks the box, Agree on the terms opened from the link
 * ticks it).
 * One job per kind of call ([requestJob], [verifyJob], [keyCheckJob],
 * [termsJob], [verifyAcceptJob], [healthJob], and [holdJob] for the next
 * hold's end), each cancelling its predecessor, all on lifecycleScope, as
 * the health check always was.
 *
 * Neither the email address, the code, the key nor the terms' text is
 * logged here.
 */
class PtCloudSettingsActivity : SettingsSubPageActivity() {

    override val layoutResId = R.layout.activity_pt_cloud_settings

    private lateinit var cardTerms: View
    private lateinit var showTermsButton: BusyButton
    private lateinit var tvTermsNotice: TextView
    private lateinit var tvHeadline: View
    private lateinit var tvIntro: View
    private lateinit var cardClosed: View
    private lateinit var tvClosedTitle: TextView
    private lateinit var tvClosedMessage: TextView
    private lateinit var cardSignIn: View
    private lateinit var groupSignedOut: View
    private lateinit var tvKeyRefused: View
    private lateinit var etEmail: EditText
    private lateinit var rowAgree: View
    private lateinit var cbAgree: MaterialCheckBox
    private lateinit var tvAgree: TextView
    private lateinit var sendCodeButton: BusyButton
    private lateinit var tvSignInNotice: TextView
    private lateinit var groupCodeSent: View
    private lateinit var tvCodeSentTo: TextView
    private lateinit var etCode: EditText
    private lateinit var signInButton: BusyButton
    private lateinit var btnSendNewCode: MaterialButton
    private lateinit var btnChangeEmail: MaterialButton
    private lateinit var tvCodeNotice: TextView
    private lateinit var headerAccount: View
    private lateinit var cardAccount: View
    private lateinit var tvAccountTitle: TextView
    private lateinit var tvAccountSubtitle: TextView
    private lateinit var tvAccountUnchecked: View
    private lateinit var cardFeatures: View
    private lateinit var rowPrivacy: View
    private lateinit var tvFeaturesPaused: View
    private lateinit var cardJoinLapsed: View
    private lateinit var btnJoinLapsed: MaterialButton
    private lateinit var tvJoinHintLapsed: TextView
    private lateinit var btnCheckAgain: View
    private lateinit var ivCheckAgain: View
    private lateinit var progressCheckAgain: View
    private lateinit var cardStatus: View
    private lateinit var statusDot: View
    private lateinit var tvStatus: TextView
    private lateinit var tvCooldown: TextView
    private lateinit var headerJoin: View
    private lateinit var cardJoinTrial: View
    private lateinit var tvJoinHintTrial: TextView

    /** The two feature rows, bound once and repainted by [render]. */
    private lateinit var featureRows: List<FeatureRow>

    // ── Facts: what the page knows of its own, beside the stored key ──────

    /** The sign-in whose code is on its way, null while none is: set by a
     *  request's 200 (or a first request's 429, which means a code may
     *  already be on its way), replaced only by a later 200, and dropped by
     *  Use a different email, by a verify that ends the sign-in, and by a
     *  stored key ([render]). Kept across a recreate ([onSaveInstanceState]). */
    private var pending: PendingSignIn? = null

    /** The server's word that the service has closed: null while it is
     *  open, the operator's message once any call answered 410
     *  `service_closed`, empty when that answer carried none. Cleared only by
     *  a health answer that is neither closed nor unreachable ([checkHealth]):
     *  the Worker answers `/health` 410 too while closed, so health alone can
     *  say it reopened, and an unreachable one says nothing. Not kept across
     *  a recreate: the resume's checks learn it again. */
    private var closedMessage: String? = null

    /** The hold a refusal's Retry-After put on asking for a code, null
     *  while none: Try for Free is held while its field holds the hold's
     *  address, and Send a new code while the pending sign-in's address is
     *  it (beyond its own resend window). A hold is for the address it
     *  answered: the server's limits per address (five codes a day, the
     *  resend cool-down) say nothing of another, and a service-wide refusal
     *  answers another address again with the same hold, so another address
     *  in the field asks at once. Set by a refused request (and a verify's
     *  rate limit), with null when the answer gave no Retry-After, cleared
     *  by a request's 200, and kept across a recreate ([onSaveInstanceState]). */
    private var hold: Hold? = null

    /** The wording of the last sign-in step's answer (a terms fetch's
     *  signed out too, Try for Free's or the link's), or of Try for Free's
     *  own check (the address's shape, the box), under its button in
     *  whichever sign-in group shows; formatted with the display name, which
     *  the strings that name the service take as `%1$s` and the others
     *  ignore. Answered by typing an address or toggling the box
     *  ([answerNotice]). Dropped by a stored key ([render]): it describes the
     *  sign-in that key ended. Kept across a recreate with the hold it
     *  explains. */
    @StringRes private var notice: Int? = null

    /** The resume's check of the stored key met a 401 (after its retry, if
     *  one was due), so the service cleared the key: "no longer accepted"
     *  above the signed-out form. Dropped by a stored key ([render]). */
    private var keyRefused = false

    /** The last check of the stored key had no verdict (no answer, a 429, a
     *  503): "couldn't check" in the address's place under the account
     *  card's title, which keeps the record's last word. Dropped with the
     *  key ([render]). */
    private var accountUnchecked = false

    /** Join was tapped, so the next check of the stored key started after
     *  the tap (the resume's, back from the browser) runs once more 10 s
     *  later while Join is still due: the payment's webhook reaches the
     *  server seconds after the payment. A check already in flight at the
     *  tap is not that one ([checkStoredKey]). Cleared after that one
     *  re-check, by a first answer that makes Join no longer due (a
     *  membership), and with the key ([render]). Kept across a recreate:
     *  the page can be rebuilt behind the browser ([onSaveInstanceState]). */
    private var recheckAfterJoin = false

    /** The agreement to the terms, given by ticking the box ([cbAgree]):
     *  the version and the locale of the text the server served when Try
     *  for Free was tapped with the box ticked ([requestCodeAfterTerms], its
     *  one writer), sent once the verify has stored the key
     *  ([acceptAfterVerify]). It is the person's, not the address's, so Use
     *  a different email and Send a new code keep it, and the next tap
     *  replaces it. Dropped by a stored key ([render]): the verify that
     *  stored it read it first. Kept across a recreate ([onSaveInstanceState]). */
    private var agreedTerms: TermsAgreement? = null

    /** The flow the terms page in front was opened for, null while none is:
     *  set when it is launched ([launchTerms]) and consumed by its answer
     *  ([onTermsResult]), which acts only while the flow still matches the
     *  key (the link's with no key stored, the account's with one). Kept
     *  across a recreate ([onSaveInstanceState]): the page can be rebuilt
     *  behind the terms page, and the answer reaches the new one. */
    private var termsFlow: TermsFlow? = null

    /** The terms page was opened for the signed-in account in this episode
     *  of the terms being outstanding (by itself after a check's
     *  TermsRequired, by the banner's Show terms, after a verify's acceptance
     *  met a newer text), so a check's TermsRequired opens it by itself no
     *  more; Show terms still does. Set by every such launch ([launchTerms]); cleared
     *  only by a check's Accepted ([checkStoredKeyOnce], never by an
     *  acceptance's 200, so a later change of the terms opens them once
     *  again) and with the key ([render]). Kept across a recreate
     *  ([onSaveInstanceState]). */
    private var termsShown = false

    /** The wording of the last terms call that failed while signed in (a
     *  fetch's 429, 503 or no answer, an acceptance not recorded), shown
     *  under the terms banner, so only while the terms are not accepted;
     *  formatted with the display name, as [notice] is. Cleared by a fetch
     *  that opens the terms page, by an acceptance's 200, and with the key
     *  ([render]). Not kept across a recreate: the next call says it again. */
    @StringRes private var termsNotice: Int? = null

    /** The step whose call is in flight, whose button shows the spinner;
     *  while one runs every other step's button waits, so one sign-in step,
     *  one key check or one terms call runs at a time. Set and cleared by
     *  the step's job (and by Sign out, which cancels the key check and the
     *  terms calls). */
    private var busy: Step? = null

    /** The status row outside the closed state ([ServiceStatus]). */
    private var status: ServiceStatus = ServiceStatus.NotChecked

    // ── Jobs: one per kind, each new one cancelling its predecessor ──────

    /** Try for Free and Send a new code. */
    private var requestJob: Job? = null

    /** Start Free Trial. */
    private var verifyJob: Job? = null

    /** The stored key's check (a resume's, Check again's), with its retry
     *  and the re-check after Join. */
    private var keyCheckJob: Job? = null

    /** A terms call the page waits on ([Step.TERMS]): Try for Free's fetch
     *  before the code is asked for, the agreement link's fetch, a signed-in
     *  fetch (Show terms', a check's, after a verify's acceptance met a newer
     *  text), and the acceptance after a signed-in Agree with the check it
     *  leads to. */
    private var termsJob: Job? = null

    /** The acceptance sent after a verify stored the key
     *  ([acceptAfterVerify]), with no busy step: the page is signed in and
     *  waits on nothing. Its own job, so that a fetch cannot cancel it. */
    private var verifyAcceptJob: Job? = null

    /** The health check in flight. A new check cancels it, so only the
     *  latest check writes the status row. */
    private var healthJob: Job? = null

    /** Re-renders when the hold [render] found ends, so a held button comes
     *  back by itself (and after a recreate). */
    private var holdJob: Job? = null

    /** The Join button's flash in flight ([pointAtJoin]), cancelled by the
     *  next one and with the page. */
    private var joinFlash: ValueAnimator? = null

    /** The terms page, launched for its answer ([launchTerms]) and answered
     *  in [onTermsResult]. Registered at construction, as the result API
     *  requires before the page starts, so an answer that arrives at the
     *  start of a page rebuilt behind the terms page (a theme or locale
     *  change) reaches the new one. */
    private val termsLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        onTermsResult(it)
    }

    override fun onContentCreated(savedInstanceState: Bundle?) {
        val service = getString(R.string.pt_cloud_display_name)
        cardTerms = findViewById(R.id.cardCloudTerms)
        showTermsButton = BusyButton(findViewById(R.id.btnCloudShowTerms), findViewById(R.id.progressCloudShowTerms))
        tvTermsNotice = findViewById(R.id.tvTermsNotice)
        tvHeadline = findViewById(R.id.tvCloudHeadline)
        tvIntro = findViewById(R.id.tvCloudIntro)
        cardClosed = findViewById(R.id.cardCloudClosed)
        tvClosedTitle = findViewById(R.id.tvCloudClosedTitle)
        tvClosedMessage = findViewById(R.id.tvCloudClosedMessage)
        cardSignIn = findViewById(R.id.cardCloudSignIn)
        groupSignedOut = findViewById(R.id.groupSignedOut)
        tvKeyRefused = findViewById(R.id.tvKeyRefused)
        etEmail = findViewById(R.id.etCloudEmail)
        rowAgree = findViewById(R.id.rowCloudAgree)
        cbAgree = findViewById(R.id.cbCloudAgree)
        tvAgree = findViewById(R.id.tvCloudAgree)
        sendCodeButton = BusyButton(findViewById(R.id.btnCloudSendCode), findViewById(R.id.progressCloudSendCode))
        tvSignInNotice = findViewById(R.id.tvSignInNotice)
        groupCodeSent = findViewById(R.id.groupCodeSent)
        tvCodeSentTo = findViewById(R.id.tvCodeSentTo)
        etCode = findViewById(R.id.etCloudCode)
        signInButton = BusyButton(findViewById(R.id.btnCloudSignIn), findViewById(R.id.progressCloudSignIn))
        btnSendNewCode = findViewById(R.id.btnCloudSendNewCode)
        btnChangeEmail = findViewById(R.id.btnCloudChangeEmail)
        tvCodeNotice = findViewById(R.id.tvCodeNotice)
        headerAccount = findViewById(R.id.headerCloudAccount)
        cardAccount = findViewById(R.id.cardCloudAccount)
        tvAccountTitle = findViewById(R.id.tvAccountTitle)
        tvAccountSubtitle = findViewById(R.id.tvAccountSubtitle)
        tvAccountUnchecked = findViewById(R.id.tvAccountUnchecked)
        cardFeatures = findViewById(R.id.cardCloudFeatures)
        rowPrivacy = findViewById(R.id.rowCloudPrivacy)
        tvFeaturesPaused = findViewById(R.id.tvFeaturesPaused)
        cardJoinLapsed = findViewById(R.id.cardCloudJoinLapsed)
        btnJoinLapsed = findViewById(R.id.btnCloudJoinLapsed)
        tvJoinHintLapsed = findViewById(R.id.tvJoinHintLapsed)
        btnCheckAgain = findViewById(R.id.btnCloudCheckAgain)
        ivCheckAgain = findViewById(R.id.ivCloudCheckAgain)
        progressCheckAgain = findViewById(R.id.progressCloudCheckAgain)
        cardStatus = findViewById(R.id.cardCloudStatus)
        statusDot = findViewById(R.id.viewCloudStatusDot)
        tvStatus = findViewById(R.id.tvCloudStatus)
        tvCooldown = findViewById(R.id.tvCloudCooldown)
        headerJoin = findViewById(R.id.headerCloudJoin)
        cardJoinTrial = findViewById(R.id.cardCloudJoinTrial)
        tvJoinHintTrial = findViewById(R.id.tvJoinHintTrial)

        // The page's fixed texts, those naming the service among them; the
        // group headers' style shows theirs in capitals.
        findViewById<TextView>(R.id.tvCloudIntro).text = getString(R.string.pt_cloud_intro, service)
        findViewById<TextView>(R.id.tvTermsPending).text = getString(R.string.pt_cloud_terms_pending, service)
        sendCodeButton.button.text = getString(R.string.pt_cloud_free_trial, service)
        headerAccount.findViewById<TextView>(R.id.tvGroupTitle).setText(R.string.pt_cloud_account_title)
        findViewById<View>(R.id.headerCloudFeatures).findViewById<TextView>(R.id.tvGroupTitle)
            .setText(R.string.pt_cloud_features_title)
        headerJoin.findViewById<TextView>(R.id.tvGroupTitle).setText(R.string.pt_cloud_after_trial_title)
        findViewById<TextView>(R.id.tvJoinNudgeTrial).text = getString(R.string.pt_cloud_join_nudge_trial, service)
        findViewById<TextView>(R.id.tvJoinTitleLapsed).text = getString(R.string.pt_cloud_join_title_lapsed, service)
        bindAgreeSentence()

        featureRows = listOf(
            bindFeatureRow(
                R.id.rowCloudOcr, R.drawable.ic_capture,
                R.string.pt_cloud_ocr_toggle_title, R.string.pt_cloud_ocr_toggle_subtitle,
                isOn = { PtCloudService.config.ocrEnabled }, write = PtCloudService::setOcrEnabled,
            ),
            bindFeatureRow(
                R.id.rowCloudTranslate, R.drawable.ic_translate,
                R.string.pt_cloud_translate_toggle_title, R.string.pt_cloud_translate_toggle_subtitle,
                isOn = { PtCloudService.config.translateEnabled }, write = PtCloudService::setTranslateEnabled,
            ),
        )
        bindJoinButton(findViewById(R.id.btnCloudJoinTrial), service)
        bindJoinButton(btnJoinLapsed, service)
        // A tap on a greyed feature once the account lapsed points at Join:
        // the rows, disabled, take no touch, so it reaches their card, which
        // render makes clickable only then.
        cardFeatures.setOnClickListener { pointAtJoin() }

        // Digits only, at most six, whatever is typed or pasted. The typed
        // code is the field's own state (EditText saves its text, so a
        // recreate keeps it), and Start Free Trial waits for all six digits:
        // the server refuses any other code as a malformed request.
        etCode.filters = arrayOf<InputFilter>(DigitsFilter(CODE_DIGITS))
        etCode.doAfterTextChanged { render() }
        // Try for Free follows the address: a hold is for the one it answered.
        etEmail.doAfterTextChanged { render() }
        // The keyboard's Done is Start Free Trial: it signs in when the button
        // would, whose enabled state is render's one rule (six digits, no step
        // busy), read here rather than restated; performClick would ignore
        // it. False in every case, so the framework still hides the keyboard,
        // as it does for any Done.
        etCode.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE && signInButton.button.isEnabled) signIn()
            false
        }

        // Try for Free checks what it can before anything is sent (the
        // handoff, 2026-10-31): the address, trimmed, in the shape x@y.z,
        // then the box. Ticking the box is the agreement: with it unticked Try
        // for Free, never held for it, lights it in its error state, shakes it
        // and sends nothing. Either check that fails says why under the
        // button, the notice a server's answer also takes, which typing or
        // toggling answers ([onPostCreate]). The box is input, as the address
        // is (its tick kept across a recreate by its id); the row toggles it.
        sendCodeButton.button.setOnClickListener {
            val email = etEmail.text.toString().trim()
            when {
                !looksLikeEmail(email) -> {
                    notice = R.string.pt_cloud_signin_bad_email
                    render()
                }
                !cbAgree.isChecked -> {
                    cbAgree.isErrorShown = true
                    shakeAgreeBox()
                    notice = R.string.pt_cloud_agree_required
                    render()
                }
                else -> requestCodeAfterTerms(email)
            }
        }
        rowAgree.setOnClickListener { cbAgree.toggle() }
        signInButton.button.setOnClickListener { signIn() }
        btnSendNewCode.setOnClickListener { pending?.let { requestCode(it.email) } }
        btnChangeEmail.setOnClickListener {
            // The address stays in its field, for editing.
            pending = null
            notice = null
            render()
        }
        btnCheckAgain.setOnClickListener { checkStoredKey() }
        showTermsButton.button.setOnClickListener { showTerms() }
        findViewById<View>(R.id.btnCloudSignOut).setOnClickListener { confirmSignOut() }

        pending = savedInstanceState?.let(::restorePending)
        hold = savedInstanceState?.let(::restoreHold)
        notice = savedInstanceState?.getInt(STATE_NOTICE)?.takeIf { it != 0 }
        recheckAfterJoin = savedInstanceState?.getBoolean(STATE_RECHECK_AFTER_JOIN) == true
        agreedTerms = savedInstanceState?.let(::restoreAgreedTerms)
        termsFlow = savedInstanceState?.let(::restoreTermsFlow)
        termsShown = savedInstanceState?.getBoolean(STATE_TERMS_SHOWN) == true

        // The page follows the service: each gesture (a switch here or on
        // another surface, a stored or cleared key, a running health check)
        // counts on PtCloudService.changes, and the StateFlow replays to this
        // collector on each resume.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                PtCloudService.changes.collect { render() }
            }
        }
        render()
    }

    /** Typing an address or toggling the box answers the notice under Try
     *  for Free, whatever it says (the address's shape, the box, a server's
     *  answer), and a tick answers the box's error. Attached here, once the
     *  views restored their own text and tick (onRestoreInstanceState runs
     *  between the page's start and this), which are no edits: attached when
     *  the page is built, a recreate's restore would drop the notice the
     *  saved state brought back. */
    override fun onPostCreate(savedInstanceState: Bundle?) {
        super.onPostCreate(savedInstanceState)
        etEmail.doAfterTextChanged { answerNotice() }
        cbAgree.setOnCheckedChangeListener { _, checked ->
            if (checked) cbAgree.isErrorShown = false
            answerNotice()
        }
    }

    /** Drops the sign-in notice and repaints; nothing while none shows. */
    private fun answerNotice() {
        if (notice == null) return
        notice = null
        render()
    }

    override fun onDestroy() {
        joinFlash?.cancel()
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        checkHealth()
        // Not while a terms call runs: the acceptance after a signed-in
        // Agree, whose answer arrives at this page's start, checks the key
        // itself once it is answered, and a check sent now could reach the
        // server before the acceptance's write and find the terms owed. Nor
        // while the acceptance after a verify is in flight, for the same
        // reason: a stale 403 would turn both switches off, and that
        // acceptance's 200 asks for no check (the verify's record is fresh).
        if (PtCloudService.config.hasKey && busy != Step.TERMS && verifyAcceptJob?.isActive != true) checkStoredKey()
    }

    /** The pending sign-in, the hold and its notice, the re-check due after
     *  Join, the agreement to the terms, the flow the terms page in front
     *  was opened for and whether the terms were shown: the facts no view
     *  keeps for itself (the hold's moment is on
     *  [SystemClock.elapsedRealtime], which a recreate does not move); the
     *  fields keep their own text (the address, the code typed so far), and
     *  the agreement box its tick. */
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        pending?.let {
            outState.putString(STATE_PENDING_EMAIL, it.email)
            outState.putLong(STATE_PENDING_SENT_AT, it.sentAtMs)
            outState.putInt(STATE_PENDING_EXPIRES_IN, it.expiresInS)
            outState.putInt(STATE_PENDING_RESEND_AFTER, it.resendAfterS)
        }
        hold?.let {
            outState.putString(STATE_HOLD_EMAIL, it.email)
            outState.putLong(STATE_HOLD_UNTIL, it.untilMs)
        }
        notice?.let { outState.putInt(STATE_NOTICE, it) }
        if (recheckAfterJoin) outState.putBoolean(STATE_RECHECK_AFTER_JOIN, true)
        agreedTerms?.let {
            outState.putString(STATE_AGREED_TERMS_VERSION, it.version)
            outState.putString(STATE_AGREED_TERMS_LOCALE, it.locale)
        }
        when (termsFlow) {
            TermsFlow.SignIn -> outState.putString(STATE_TERMS_FLOW, TERMS_FLOW_SIGN_IN)
            TermsFlow.Account -> outState.putString(STATE_TERMS_FLOW, TERMS_FLOW_ACCOUNT)
            null -> Unit
        }
        if (termsShown) outState.putBoolean(STATE_TERMS_SHOWN, true)
    }

    private fun restoreHold(state: Bundle): Hold? =
        state.getString(STATE_HOLD_EMAIL)?.let { Hold(email = it, untilMs = state.getLong(STATE_HOLD_UNTIL)) }

    private fun restorePending(state: Bundle): PendingSignIn? = state.getString(STATE_PENDING_EMAIL)?.let { email ->
        PendingSignIn(
            email = email,
            sentAtMs = state.getLong(STATE_PENDING_SENT_AT),
            expiresInS = state.getInt(STATE_PENDING_EXPIRES_IN),
            resendAfterS = state.getInt(STATE_PENDING_RESEND_AFTER),
        )
    }

    private fun restoreAgreedTerms(state: Bundle): TermsAgreement? {
        val version = state.getString(STATE_AGREED_TERMS_VERSION) ?: return null
        val locale = state.getString(STATE_AGREED_TERMS_LOCALE) ?: return null
        return TermsAgreement(version, locale)
    }

    private fun restoreTermsFlow(state: Bundle): TermsFlow? = when (state.getString(STATE_TERMS_FLOW)) {
        TERMS_FLOW_SIGN_IN -> TermsFlow.SignIn
        TERMS_FLOW_ACCOUNT -> TermsFlow.Account
        else -> null
    }

    // ── Sign-in ─────────────────────────────────────────────────────────

    /** Asks for a code to be mailed to [email]: Try for Free once the terms
     *  were fetched for the agreement ([requestCodeAfterTerms]: the field,
     *  trimmed, when it was tapped with the box ticked), or Send a new code
     *  while one is pending (its address went through Try for Free
     *  already). A refused Send a new code keeps the pending sign-in, since
     *  the code before it still works (only a 200 replaces it), and only a
     *  410 leaves for the closed card. */
    private fun requestCode(email: String) {
        val resend = pending != null
        requestJob?.cancel()
        busy = Step.REQUEST
        render()
        requestJob = lifecycleScope.launch {
            val answer = requestCodeOverrideForTest?.invoke(email) ?: PtCloudService.client.requestCode(email)
            busy = null
            val now = SystemClock.elapsedRealtime()
            when (answer) {
                is CodeRequestResult.Sent -> {
                    pending = PendingSignIn(email, now, answer.expiresInS, answer.resendAfterS)
                    notice = null
                    hold = null
                    // The digits in the field were for the code this one
                    // replaces (or another address's): the new code is typed.
                    etCode.text.clear()
                }
                is SignInAnswer.RateLimited -> {
                    notice = R.string.pt_cloud_signin_rate_limited
                    // A first request's 429 may follow a lost 200: the code
                    // field, with Send a new code held for the Retry-After.
                    if (resend) {
                        hold = holdFor(email, now, answer.retryAfterS)
                    } else {
                        pending = PendingSignIn(
                            email, now, DEFAULT_EXPIRES_IN_S, answer.retryAfterS ?: DEFAULT_RESEND_AFTER_S,
                        )
                    }
                }
                is CodeRequestResult.TooManyCodes -> {
                    notice = R.string.pt_cloud_signin_too_many_codes
                    hold = holdFor(email, now, answer.retryAfterS)
                }
                is SignInAnswer.Offline -> {
                    notice = R.string.pt_cloud_signin_offline_new
                    hold = holdFor(email, now, answer.retryAfterS)
                    status = ServiceStatus.Answered(HealthStatus.Offline)
                }
                is CodeRequestResult.MailFailed -> {
                    notice = R.string.pt_cloud_signin_mail_failed
                    hold = holdFor(email, now, answer.retryAfterS)
                }
                is SignInAnswer.Unavailable -> {
                    notice = R.string.pt_cloud_signin_unavailable
                    hold = holdFor(email, now, answer.retryAfterS)
                }
                // Under the button, as the page's own check of the shape says
                // it: the server's rules are the last word.
                SignInAnswer.BadAddress -> notice = R.string.pt_cloud_signin_bad_email
                // An older Worker's 404, which has no sign-in: with no other
                // way in, the member waits as for any sign-in not available.
                SignInAnswer.NotAvailable -> notice = R.string.pt_cloud_signin_unavailable
                is SignInAnswer.Closed -> closedMessage = answer.message.orEmpty()
                SignInAnswer.Unreachable -> notice = R.string.pt_cloud_signin_unreachable
            }
            render()
        }
    }

    /** Exchanges the typed code for the key. A 200's key is stored first,
     *  with the record of the verify's own fields, and the page is drawn
     *  from that record with no key check (the verify is the check, and a
     *  check sent at once can meet a replica that has not seen the key yet).
     *  Once it is stored, the agreement to the terms given before the code
     *  was asked for, if any, is sent with it ([acceptAfterVerify]), the page
     *  waiting on nothing. The key cannot be fetched again, so a write the
     *  keystore refused ([PtCloudService.saveClientKey] false) returns to the
     *  signed-out form and says so; the lost key is harmless on the server. */
    private fun signIn() {
        val signIn = pending ?: return
        // Read before the store: the store counts on the service's change
        // signal, whose collector can repaint inside it, and the repaint
        // drops the agreement once a key is stored.
        val agreed = agreedTerms
        val code = etCode.text.toString()
        verifyJob?.cancel()
        busy = Step.VERIFY
        render()
        verifyJob = lifecycleScope.launch {
            val label = deviceLabel(Build.MANUFACTURER, Build.MODEL)
            val answer = verifyOverrideForTest?.invoke(signIn.email, code, label)
                ?: PtCloudService.client.verifyCode(signIn.email, code, label)
            busy = null
            when (answer) {
                is VerifyResult.SignedIn -> {
                    val stored = PtCloudService.saveClientKey(
                        answer.key, PtCloudAccount.of(answer.account, signIn.email, System.currentTimeMillis()),
                    )
                    pending = null
                    if (stored) {
                        notice = null
                        // The code is spent; neither field nor the box is
                        // wanted at the next sign-in, which may be another
                        // person's on this device.
                        etEmail.text.clear()
                        etCode.text.clear()
                        cbAgree.isChecked = false
                        checkHealth()
                        if (agreed != null) acceptAfterVerify(answer.key, agreed)
                    } else {
                        notice = R.string.pt_cloud_key_not_stored
                    }
                }
                is VerifyResult.CodeInvalid -> {
                    notice = R.string.pt_cloud_code_invalid
                    // At 0 no live code is left: Send a new code is the way on.
                    if (answer.attemptsLeft == 0) etCode.text.clear()
                }
                VerifyResult.BadAnswer -> notice = R.string.pt_cloud_signin_unavailable
                VerifyResult.Disabled -> {
                    pending = null
                    notice = R.string.pt_cloud_signin_disabled
                }
                is SignInAnswer.RateLimited -> {
                    notice = R.string.pt_cloud_signin_rate_limited
                    hold = holdFor(signIn.email, SystemClock.elapsedRealtime(), answer.retryAfterS)
                }
                is SignInAnswer.Offline -> {
                    notice = R.string.pt_cloud_verify_offline
                    status = ServiceStatus.Answered(HealthStatus.Offline)
                }
                is SignInAnswer.Unavailable -> notice = R.string.pt_cloud_signin_unavailable
                SignInAnswer.BadAddress -> {
                    pending = null
                    notice = R.string.pt_cloud_signin_bad_email
                }
                // An older Worker's 404: no code it sent can be verified.
                SignInAnswer.NotAvailable -> {
                    pending = null
                    notice = R.string.pt_cloud_signin_unavailable
                }
                is SignInAnswer.Closed -> closedMessage = answer.message.orEmpty()
                SignInAnswer.Unreachable -> notice = R.string.pt_cloud_signin_unreachable
            }
            render()
        }
    }

    /** Sign out, confirmed first: the key and the record beside it are
     *  forgotten on this device ([PtCloudService.saveClientKey] with a blank
     *  key, both switches off), and nothing is sent (the key stays valid on
     *  the server, where the operator can revoke it). A key check in flight
     *  is cancelled, so its answer cannot write a record for the key that
     *  is gone, and so are the terms calls (a fetch, an acceptance and the
     *  check it leads to, the acceptance after a verify), whose answers
     *  would act for that key. */
    private fun confirmSignOut() {
        OverlayAlert.Builder(this)
            .setTitle(getString(R.string.pt_cloud_sign_out_title, getString(R.string.pt_cloud_display_name)))
            .setMessage(getString(R.string.pt_cloud_sign_out_message))
            .addButton(
                getString(R.string.pt_cloud_sign_out),
                themeColor(R.attr.ptDanger),
                themeColor(R.attr.ptAccentOn),
            ) {
                keyCheckJob?.cancel()
                termsJob?.cancel()
                verifyAcceptJob?.cancel()
                if (busy == Step.KEY_CHECK || busy == Step.TERMS) busy = null
                PtCloudService.saveClientKey("")
                render()
            }
            .addCancelButton(getString(R.string.btn_cancel))
            .show()
    }

    // ── The stored key ──────────────────────────────────────────────────

    /** The stored key's check, on a resume and on Check again; after Join,
     *  once more 10 s later while Join is still due. The re-check is a
     *  check started after the tap's: one in flight when Join is tapped
     *  (the resume's, a second old) would wait out the 10 s while the
     *  browser is in front and spend the flag before the return. A newer
     *  check cancels this one, its re-check wait included, and Sign out
     *  cancels it. */
    private fun checkStoredKey() {
        keyCheckJob?.cancel()
        val afterJoin = recheckAfterJoin
        keyCheckJob = lifecycleScope.launch {
            checkStoredKeyOnce()
            if (afterJoin && joinDue(PtCloudService.config.standing())) {
                delay(JOIN_RECHECK_DELAY_MS)
                checkStoredKeyOnce()
            }
            if (afterJoin) recheckAfterJoin = false
        }
    }

    /**
     * One check of the stored key ([askStoredKey]), its answer to the
     * page's facts. An answer the service would not take, because the key
     * or the record changed while the check was out (a route's 403 ending
     * the account, say; [PtCloudService.onStoredKeyChecked]), describes a
     * state that is gone, so the key is asked once more; a second such
     * answer reads as not checked, which the page says beside the account
     * line, and the next resume or Check again asks afresh. A TermsRequired
     * opens the terms by itself unless they were opened in this episode
     * ([termsShown]), and an Accepted ends the episode. Also the check that
     * follows an acceptance of the terms ([acceptStored]).
     */
    private suspend fun checkStoredKeyOnce() {
        busy = Step.KEY_CHECK
        render()
        val check = askStoredKey() ?: askStoredKey() ?: KeyCheck.NotChecked
        busy = null
        when (check) {
            // The server's word that the terms are accepted ends the episode
            // of their being owed: a later change opens them again.
            is KeyCheck.Accepted -> {
                accountUnchecked = false
                termsShown = false
            }
            is KeyCheck.Inactive -> accountUnchecked = false
            // The service turned both switches off; the terms open by
            // themselves once an episode, and Show terms opens them again.
            is KeyCheck.TermsRequired -> {
                accountUnchecked = false
                if (!termsShown) showTerms()
            }
            KeyCheck.Refused -> keyRefused = true
            is KeyCheck.Closed -> closedMessage = check.message.orEmpty()
            KeyCheck.NotChecked -> accountUnchecked = true
        }
        render()
    }

    /**
     * One `GET /v1/key` for the stored key, read off the main thread (the
     * read decrypts it), with the record's generation read before the send
     * ([PtCloudService.accountGeneration]). A key that reads back unusable
     * is not sent and reads as not checked (a keystore that failed this
     * once must not clear a good key). A 401 within [VERIFY_WINDOW_MS] of
     * the key's store ([PtCloudService.keyStoredAtMs]) is asked once more a
     * second later: a key just minted can answer 401 while the database's
     * nearest replica catches up. The final answer goes to the service with
     * the key and the generation: the answer when the service took it, null
     * when the key or the record changed under the check.
     */
    private suspend fun askStoredKey(): KeyCheck? {
        val key = withContext(Dispatchers.IO) { PtCloudService.config.clientKey }
        val generation = PtCloudService.accountGeneration
        var check = if (PtCloudConfig.isUsableKey(key)) checkKey(key) else KeyCheck.NotChecked
        if (check == KeyCheck.Refused && storedJustNow()) {
            delay(VERIFY_RETRY_DELAY_MS)
            check = checkKey(key)
        }
        return check.takeIf { PtCloudService.onStoredKeyChecked(key, generation, it) }
    }

    /** Whether the stored key was stored within [VERIFY_WINDOW_MS]. */
    private fun storedJustNow(): Boolean =
        PtCloudService.keyStoredAtMs?.let { SystemClock.elapsedRealtime() - it < VERIFY_WINDOW_MS } == true

    /** `GET /v1/key` for the stored [key]: a resume's check, Check again's. */
    private suspend fun checkKey(key: String): KeyCheck =
        keyCheckOverrideForTest?.invoke(key) ?: PtCloudService.client.checkKey(key)

    // ── The terms ───────────────────────────────────────────────────────

    /** The agreement's sentence beside the box, its link the words that
     *  open the terms to read ([openTermsToRead]), wherever a locale puts
     *  them: in the link colour, medium weight, with no underline (the
     *  weight a span of its own, which the text measures by, where the
     *  link's draw state alone would draw wider glyphs than were measured).
     *  Its movement takes a press on the link alone
     *  ([LinkOnlyMovement]) and makes the sentence clickable and
     *  long-clickable; it is neither, so a press off the link falls through
     *  to the row, which toggles the box. It stays focusable, so a
     *  controller reaches it (its background shows the focus), selects the
     *  link with a press down (the text highlight shows the selection; a
     *  touch never draws it, the sentence being neither focused nor pressed
     *  in touch mode) and opens it with the centre press. */
    private fun bindAgreeSentence() {
        val link = getString(R.string.pt_cloud_terms_link)
        val sentence = getString(R.string.pt_cloud_agree_terms, link)
        val linkAt = sentence.indexOf(link)
        val linkColor = themeColor(R.attr.ptTextLink)
        tvAgree.text = SpannableString(sentence).apply {
            setSpan(
                object : ClickableSpan() {
                    override fun onClick(widget: View) = openTermsToRead()

                    override fun updateDrawState(ds: TextPaint) {
                        ds.color = linkColor
                        ds.isUnderlineText = false
                    }
                },
                linkAt, linkAt + link.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
            setSpan(TypefaceSpan(LINK_FONT_FAMILY), linkAt, linkAt + link.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        tvAgree.movementMethod = LinkOnlyMovement
        tvAgree.isClickable = false
        tvAgree.isLongClickable = false
    }

    /** The box shaken once where it stands as its error colour comes on
     *  (Try for Free tapped with it unticked): a short sideways wobble that
     *  settles where it was, taking the eye to the box the tap needs. A tap
     *  while it still wobbles starts it again from rest. Off, like every
     *  animator, where the system's animations are. */
    private fun shakeAgreeBox() {
        val reach = 6 * resources.displayMetrics.density
        ObjectAnimator.ofFloat(cbAgree, View.TRANSLATION_X, 0f, -reach, reach, -reach, reach, -reach / 2, reach / 2, 0f).apply {
            duration = 400
            start()
        }
    }

    /** Try for Free with the box ticked: the terms fetched for the version
     *  and the locale the agreement names ([agreedTerms]), then the code for
     *  [email] (the field, trimmed, when it was tapped) at once, with no
     *  page: the box is the agreement. A server from before the terms (a
     *  404) asks for the code with nothing to accept after the verify; a
     *  fetch that fails asks for no code, which would fail the same way. */
    private fun requestCodeAfterTerms(email: String) {
        fetchTermsThen(TermsFlow.SignIn, onNotAvailable = { requestCode(email) }) { terms ->
            agreedTerms = TermsAgreement(terms.version, terms.locale)
            requestCode(email)
        }
    }

    /** The agreement's link, signed out: the terms fetched and opened to
     *  read on the terms page, whose Agree ticks the box ([onTermsResult]).
     *  A server from before the terms (a 404) has nothing to show, and the
     *  form stays as it is. */
    private fun openTermsToRead() {
        fetchTermsThen(TermsFlow.SignIn) { launchTerms(it, TermsFlow.SignIn) }
    }

    /**
     * The terms fetched for [flow] ([fetchTerms]) while the page waits
     * ([Step.TERMS], a terms call before it cancelled): their text to
     * [onText], a server from before them (a 404) to [onNotAvailable]. The
     * one fetch of Try for Free and the link, signed out, and of the
     * account's [showTerms]. A 410 leaves for the closed card; a fetch that
     * fails says why where its flow's tap was, under Try for Free signed out
     * ([notice]) and under the terms banner signed in ([termsNotice]), and the
     * text clears what a failed fetch said there before it.
     */
    private fun fetchTermsThen(
        flow: TermsFlow,
        onNotAvailable: () -> Unit = {},
        onText: (TermsResult.Text) -> Unit,
    ) {
        fun say(@StringRes text: Int?) {
            when (flow) {
                TermsFlow.SignIn -> notice = text
                TermsFlow.Account -> termsNotice = text
            }
        }
        termsJob?.cancel()
        busy = Step.TERMS
        render()
        termsJob = lifecycleScope.launch {
            val terms = fetchTerms()
            busy = null
            when (terms) {
                is TermsResult.Text -> {
                    say(null)
                    onText(terms)
                }
                TermsResult.NotAvailable -> onNotAvailable()
                is TermsResult.Closed -> closedMessage = terms.message.orEmpty()
                TermsResult.Unavailable -> say(R.string.pt_cloud_terms_unavailable)
                TermsResult.Unreachable -> say(R.string.pt_cloud_signin_unreachable)
            }
            render()
        }
    }

    /** Opens the terms page with [terms] for [flow], only while this page is
     *  resumed: a fetch started here outlives a pause, and a launch from a
     *  page behind another screen would put the terms over it, over the
     *  game even (the app's overlay windows exempt it from the limits on
     *  starts from the background). A launch dropped so leaves the facts as
     *  they were: signed in, the next resume's check opens the terms then;
     *  signed out, the link is there to tap again. Notes the flow the
     *  answer is for ([termsFlow]) and, for the account, that the terms were
     *  shown in this episode ([termsShown]). */
    private fun launchTerms(terms: TermsResult.Text, flow: TermsFlow) {
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        termsFlow = flow
        if (flow == TermsFlow.Account) termsShown = true
        termsLauncher.launch(PtCloudTermsActivity.intent(this, terms.version, terms.locale, terms.markdown))
    }

    /** The terms page's answer, delivered at this page's start, before its
     *  resume. An agreement acts only for the flow that opened the page, and
     *  only while that flow still matches the key: the link's (no key
     *  stored) ticks the box, which is the agreement, and sends nothing, the
     *  box's error going with the tick; the account's (a key stored) sends
     *  the acceptance ([acceptStored]). Cancel, back, or a flow the key no
     *  longer matches (another copy of this page signed out or in meanwhile:
     *  the pill's gear opens one with NEW_TASK | SINGLE_TOP, which finds no
     *  copy under the terms page) leaves the state as it is, repainted. */
    private fun onTermsResult(result: ActivityResult) {
        val flow = termsFlow
        termsFlow = null
        val agreement = PtCloudTermsActivity.agreement(result)
        val hasKey = PtCloudService.config.hasKey
        when {
            agreement != null && flow == TermsFlow.SignIn && !hasKey -> {
                cbAgree.isChecked = true
                render()
            }
            agreement != null && flow == TermsFlow.Account && hasKey -> acceptStored(agreement)
            else -> render()
        }
    }

    /** Agree while signed in: the acceptance of [agreement] with the stored
     *  key, read off the main thread and sent only when usable (one that
     *  reads back unusable is not sent, and the acceptance reads as not
     *  recorded), then the key's check, the server's next word: its Accepted
     *  turns both switches on through the service and ends the episode
     *  ([termsShown]), which the acceptance's 200 itself never does. A 409
     *  means the text changed since it was fetched, so it is fetched and
     *  shown again; a 401 acts only through the check, as every 401 does. */
    private fun acceptStored(agreement: TermsAgreement) {
        termsJob?.cancel()
        busy = Step.TERMS
        render()
        termsJob = lifecycleScope.launch {
            val key = withContext(Dispatchers.IO) { PtCloudService.config.clientKey }
            val answer = if (PtCloudConfig.isUsableKey(key)) acceptTerms(key, agreement) else TermsAcceptResult.NotRecorded
            busy = null
            when (answer) {
                TermsAcceptResult.Accepted -> {
                    termsNotice = null
                    checkStoredKeyOnce()
                }
                is TermsAcceptResult.Outdated -> showTerms()
                TermsAcceptResult.Refused -> checkStoredKeyOnce()
                is TermsAcceptResult.Closed -> closedMessage = answer.message.orEmpty()
                TermsAcceptResult.NotRecorded -> termsNotice = R.string.pt_cloud_terms_not_recorded
            }
            render()
        }
    }

    /** The terms fetched for the signed-in account and opened on the terms
     *  page ([launchTerms]): the banner's Show terms, a check's
     *  TermsRequired while they were not opened in this episode, and an
     *  acceptance that met a newer text (a 409). A 404 has nothing to show; a
     *  fetch that fails says why under the banner. */
    private fun showTerms() {
        fetchTermsThen(TermsFlow.Account) { launchTerms(it, TermsFlow.Account) }
    }

    /** The agreement given before the code was asked for, sent once the
     *  verify stored [key], whatever the verify said of the entitlement (the
     *  server records an acceptance for an account with none), with the key
     *  check's one retry for a replica's lag: a 401 within
     *  [VERIFY_WINDOW_MS] of the store is asked once more a second later.
     *  No busy step and a job of its own ([verifyAcceptJob]): the page is
     *  signed in and waits on nothing. A 200, a 401 after the retry and an
     *  acceptance not recorded (a 429, a 503, no answer) change nothing here:
     *  the server holds the record, and its gate asks again at the next check
     *  or capture. A 409 (the text changed since it was fetched) opens the
     *  newer text, for the signed-in account now. */
    private fun acceptAfterVerify(key: String, agreement: TermsAgreement) {
        verifyAcceptJob?.cancel()
        verifyAcceptJob = lifecycleScope.launch {
            // The send outlives this page: a back press or a recreate inside
            // its round trip would otherwise drop the agreement unrecorded,
            // for the gate's 403 to meet the first capture. The page acts on
            // the answer only while it is still here (a sign-out cancels the
            // job too, and the send still completes for the key it was for).
            val answer = withContext(NonCancellable) {
                var answer = acceptTerms(key, agreement)
                if (answer == TermsAcceptResult.Refused && storedJustNow()) {
                    delay(VERIFY_RETRY_DELAY_MS)
                    answer = acceptTerms(key, agreement)
                }
                answer
            }
            if (!isActive) return@launch
            when (answer) {
                TermsAcceptResult.Accepted, TermsAcceptResult.Refused, TermsAcceptResult.NotRecorded -> Unit
                is TermsAcceptResult.Outdated -> showTerms()
                is TermsAcceptResult.Closed -> closedMessage = answer.message.orEmpty()
            }
            render()
        }
    }

    /** `GET /v1/terms` for the device's locale, as its BCP 47 tag (`pt-BR`,
     *  `zh-Hant-HK`), which the server matches to the texts it holds. */
    private suspend fun fetchTerms(): TermsResult {
        val locale = Locale.getDefault().toLanguageTag()
        return termsOverrideForTest?.invoke(locale) ?: PtCloudService.client.fetchTerms(locale)
    }

    /** `POST /v1/terms/accept` of [agreement] with [key]. */
    private suspend fun acceptTerms(key: String, agreement: TermsAgreement): TermsAcceptResult =
        acceptTermsOverrideForTest?.invoke(key, agreement.version, agreement.locale)
            ?: PtCloudService.client.acceptTerms(key, agreement.version, agreement.locale)

    // ── Health ──────────────────────────────────────────────────────────

    /** Asks the service's `GET /health` and shows the answer in the status
     *  row. Its answer is also the one source that says a closed service
     *  reopened ([closedMessage]); a Running one ends a cooldown recorded
     *  while the service was stopped (a pod start is no gesture the app can
     *  see; this check is where the app learns of it). A check never ends a
     *  step's busy state: each step's job owns its own. */
    private fun checkHealth() {
        healthJob?.cancel()
        status = ServiceStatus.Checking
        render()
        healthJob = lifecycleScope.launch {
            val health = healthOverrideForTest?.invoke() ?: PtCloudService.client.health()
            status = ServiceStatus.Answered(health)
            when (health) {
                is HealthStatus.Closed -> closedMessage = health.message.orEmpty()
                HealthStatus.Unreachable -> Unit
                HealthStatus.Running, HealthStatus.Starting, HealthStatus.NotRunning, HealthStatus.Offline,
                is HealthStatus.Other -> closedMessage = null
            }
            render()
            if (health == HealthStatus.Running) PtCloudService.onHealthRunning()
        }
    }

    // ── Join ────────────────────────────────────────────────────────────

    /** Join: the Ko-fi tiers page in the browser, whose return the resume's
     *  check meets ([recheckAfterJoin]); a long press copies the address.
     *  Labelled with the [service]'s name; the line under it, the address to
     *  pay with, is [render]'s. Both Join buttons, the trial card's and the
     *  lapsed one's, are this. */
    private fun bindJoinButton(button: MaterialButton, service: String) {
        button.text = getString(R.string.pt_cloud_join_title, service)
        button.setOnClickListener {
            recheckAfterJoin = true
            try {
                startActivity(Intent(Intent.ACTION_VIEW, JOIN_URL.toUri()))
            } catch (_: Exception) {
                Toast.makeText(this, getString(R.string.toast_no_browser_available), Toast.LENGTH_SHORT).show()
            }
        }
        button.setOnLongClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("URL", JOIN_URL))
            Toast.makeText(this, getString(R.string.toast_link_copied), Toast.LENGTH_SHORT).show()
            true
        }
    }

    /** Whether Join shows for [standing]: on the trial, after it, and with no
     *  active membership; never while closed. */
    private fun joinDue(standing: Standing?): Boolean =
        closedMessage == null && (standing == Standing.TRIAL || endedOrInactive(standing))

    private fun endedOrInactive(standing: Standing?): Boolean =
        standing == Standing.TRIAL_ENDED || standing == Standing.INACTIVE

    /** The line under Join: the address to pay with, [email], or the key's
     *  own when the record has none. */
    private fun joinHint(email: String?): String =
        email?.let { getString(R.string.pt_cloud_join_email_hint, it) }
            ?: getString(R.string.pt_cloud_join_email_hint_no_email)

    /** A tap on a greyed feature once the account lapsed: the Join card under
     *  the features brought into view (a smooth scroll while it is not all on
     *  screen), then the Join button flashed with the app's attention flash
     *  ([startAttentionFlash]), its fill pulsing toward its ink, once the
     *  scroll has settled. Focus moves to Join too, for a controller (in touch
     *  mode a button takes none) and for a screen reader. A tap while it
     *  flashes starts it again. */
    private fun pointAtJoin() {
        val card = cardJoinLapsed
        val scrolled = card.requestRectangleOnScreen(Rect(0, 0, card.width, card.height), false)
        val join = btnJoinLapsed
        join.requestFocus()
        join.performAccessibilityAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null)
        joinFlash?.cancel()
        val rest = join.backgroundTintList
        val fill = themeColor(R.attr.ptAccent)
        val ink = themeColor(R.attr.ptAccentOn)
        joinFlash = startAttentionFlash(
            startDelayMs = if (scrolled) SCROLL_SETTLE_MS else 0L,
            onIntensity = {
                join.backgroundTintList = ColorStateList.valueOf(ColorUtils.blendARGB(fill, ink, JOIN_FLASH_PEAK * it))
            },
            onEnd = {
                join.backgroundTintList = rest
                joinFlash = null
            },
        )
    }

    // ── The one painter ─────────────────────────────────────────────────

    /**
     * Paints the whole page from the facts, the stored key and its record.
     * First the facts that would contradict the key: a stored key (signed in
     * here or through another copy of the page) drops the sign-in under way,
     * its notice and the refused-key line, which belong to the signed-out
     * form, and the agreement to the terms, which the verify that stored the
     * key read first; no key drops what describes a stored key, the
     * unchecked line, the re-check after Join, and the terms' shown fact and
     * notice. Then the page, top to bottom: the terms banner while the terms
     * are owed; signed out, the headline and the intro; the closed card while
     * closed, in place of the sign-in card or of the ACCOUNT group; the
     * sign-in card (the form, or the code) signed out; the ACCOUNT group
     * signed in; the features in every state, a description signed out, live
     * while the account is admitted and the service open, else disabled and
     * off; signed out the privacy footnote, with the terms owed the paused
     * line; the lapsed Join card once the trial ended or no membership is
     * active; the service status while the account is served; the trial's
     * Join card. Every control is painted whether or not its card shows.
     * Last, the next hold's end is armed ([holdJob]).
     */
    private fun render() {
        val hasKey = PtCloudService.config.hasKey
        val admitted = PtCloudService.config.admitted
        if (hasKey) {
            pending = null
            notice = null
            keyRefused = false
            agreedTerms = null
        } else {
            accountUnchecked = false
            recheckAfterJoin = false
            termsShown = false
            termsNotice = null
        }
        val closed = closedMessage != null
        val signIn = pending
        val account = PtCloudService.account
        val nowMs = System.currentTimeMillis()
        val standing = PtCloudService.config.standing(nowMs)
        val elapsedNow = SystemClock.elapsedRealtime()
        val service = getString(R.string.pt_cloud_display_name)
        // The signed-in states, each a key on an open service, by the
        // record's standing.
        val signedIn = hasKey && !closed
        val termsDue = signedIn && standing == Standing.TERMS_REQUIRED
        val lapsed = signedIn && endedOrInactive(standing)
        val onTrial = signedIn && standing == Standing.TRIAL

        // The terms banner, the page's one call to action while they are
        // owed, a failed terms call's notice under it, and Show terms'
        // spinner in its label's place while a terms call runs.
        cardTerms.isVisible = termsDue
        val termsNoticeText = termsNotice?.let { getString(it, service) }
        tvTermsNotice.text = termsNoticeText
        tvTermsNotice.isVisible = termsDue && termsNoticeText != null
        showTermsButton.render(busy = busy == Step.TERMS, enabled = busy == null)

        // Signed out: what the service is.
        tvHeadline.isVisible = !hasKey
        tvIntro.isVisible = !hasKey
        cardClosed.isVisible = closed
        tvClosedTitle.text = getString(R.string.pt_cloud_closed_title, service)
        tvClosedMessage.text = closedMessage?.takeIf { it.isNotBlank() } ?: getString(R.string.pt_cloud_closed_default)

        // Signed out, and code sent.
        cardSignIn.isVisible = !hasKey && !closed
        groupSignedOut.isVisible = signIn == null
        groupCodeSent.isVisible = signIn != null
        tvKeyRefused.isVisible = keyRefused
        val noticeText = notice?.let { getString(it, service) }
        for (view in listOf(tvSignInNotice, tvCodeNotice)) {
            view.text = noticeText
            view.isVisible = noticeText != null
        }
        // The field and the agreement wait while a step runs; the box's tick
        // is its own, as the field's text is, and never painted here.
        for (view in listOf(etEmail, rowAgree, cbAgree, tvAgree)) view.isEnabled = busy == null
        val typed = etEmail.text.toString().trim()
        val sendCodeHold = hold?.takeIf { it.email == typed && it.untilMs > elapsedNow }?.untilMs
        // Busy for its request and for a terms fetch signed out: its own
        // before the request, or the link's.
        sendCodeButton.render(
            busy = (busy == Step.REQUEST || busy == Step.TERMS) && !hasKey && signIn == null,
            enabled = busy == null && sendCodeHold == null,
        )
        if (signIn != null) {
            val minutes = (signIn.expiresInS + 59) / 60
            tvCodeSentTo.text =
                resources.getQuantityString(R.plurals.pt_cloud_code_sent_to, minutes, signIn.email, minutes)
        }
        etCode.isEnabled = busy == null
        signInButton.render(busy = busy == Step.VERIFY, enabled = busy == null && etCode.length() == CODE_DIGITS)
        val resendHold = signIn
            ?.let { maxOf(it.sentAtMs + it.resendAfterS * 1_000L, hold?.takeIf { h -> h.email == it.email }?.untilMs ?: 0L) }
            ?.takeIf { it > elapsedNow }
        btnSendNewCode.isEnabled = busy == null && signIn != null && resendHold == null
        btnChangeEmail.isEnabled = busy == null

        // Signed in: the standing is the title and the address under it;
        // with no standing to say, the address is the title. A check with
        // no verdict says so in the address's place.
        headerAccount.isVisible = signedIn
        cardAccount.isVisible = signedIn
        val identity = account?.email ?: getString(R.string.pt_cloud_signed_in_with_key)
        val line = account?.let { accountLine(it, nowMs) }
        tvAccountTitle.text = line ?: identity
        // An ended trial and an inactive membership in the alert colour, as
        // each names something the member has to act on (ruled 2026-10-29,
        // kept for the title 2026-10-31); every other title in its own.
        tvAccountTitle.setTextColor(themeColor(if (endedOrInactive(standing)) R.attr.ptDanger else R.attr.ptText))
        tvAccountSubtitle.text = identity
        tvAccountSubtitle.isVisible = line != null && !accountUnchecked
        tvAccountUnchecked.isVisible = accountUnchecked

        // The features: what the service does signed out, the two switches
        // signed in.
        val mode = when {
            !hasKey -> FeatureMode.DESCRIPTION
            admitted && !closed -> FeatureMode.LIVE
            else -> FeatureMode.DISABLED
        }
        featureRows.forEach { it.render(mode) }
        // Greyed because the account lapsed: a tap on them points at the Join
        // card under them ([pointAtJoin]); a controller reaches the card.
        val pointsAtJoin = lapsed && mode == FeatureMode.DISABLED
        cardFeatures.isClickable = pointsAtJoin
        cardFeatures.isFocusable = pointsAtJoin
        rowPrivacy.isVisible = !hasKey
        tvFeaturesPaused.isVisible = termsDue

        // Join: right under the features once the account lapsed, with
        // Already joined? and its Check again chip, whose spinner takes the
        // glyph's place while the check runs; after the service status on
        // the trial.
        cardJoinLapsed.isVisible = lapsed
        headerJoin.isVisible = onTrial
        cardJoinTrial.isVisible = onTrial
        val hint = joinHint(account?.email)
        tvJoinHintLapsed.text = hint
        tvJoinHintTrial.text = hint
        val checking = busy == Step.KEY_CHECK
        btnCheckAgain.isEnabled = busy == null
        ivCheckAgain.isVisible = !checking
        progressCheckAgain.isVisible = checking

        // The service's status while the account is served, once a check
        // has run: the lapsed card and the terms banner say why it refuses.
        cardStatus.isVisible = signedIn && !lapsed && !termsDue && status != ServiceStatus.NotChecked
        tvStatus.text = statusText()
        val running = (status as? ServiceStatus.Answered)?.health == HealthStatus.Running
        statusDot.backgroundTintList =
            ColorStateList.valueOf(themeColor(if (running) R.attr.ptAccent else R.attr.ptWarning))
        renderCooldown()

        armHold((if (signIn != null) resendHold else sendCodeHold)?.let { it - elapsedNow })
    }

    /** The account card's title for [account] at [nowMs], its standing; null
     *  when there is none to say: no details, a trial whose end is unknown,
     *  the terms owed (the 403 that said so carries no dates, and the banner
     *  says it). Active dates read as a date with the time of day
     *  ([CooldownTimeLabel.formatDateTime]), ended ones as a date
     *  ([CooldownTimeLabel.formatDate]). */
    private fun accountLine(account: PtCloudAccount, nowMs: Long): String? = when (account.standing(nowMs)) {
        Standing.NO_DETAILS, Standing.TERMS_REQUIRED -> null
        Standing.TRIAL -> account.entitledUntilMs?.let {
            getString(R.string.pt_cloud_trial_until, CooldownTimeLabel.formatDateTime(this, it, nowMs))
        }
        // A lease end that has passed is left out: the payment that renews
        // the membership wrote a lease the device has not read, and the
        // resume's check reads it ([PtCloudAccount.standing]); until then
        // "Member", never "until" a date gone by.
        Standing.MEMBER -> account.entitledUntilMs?.takeIf { it > nowMs }?.let {
            getString(R.string.pt_cloud_member_until, CooldownTimeLabel.formatDateTime(this, it, nowMs))
        } ?: getString(R.string.pt_cloud_member)
        Standing.TRIAL_ENDED -> account.endedAt(nowMs)?.let {
            getString(R.string.pt_cloud_trial_ended_on, CooldownTimeLabel.formatDate(it, nowMs))
        } ?: getString(R.string.pt_cloud_trial_ended)
        Standing.INACTIVE -> account.endedAt(nowMs)?.let {
            getString(R.string.pt_cloud_inactive_since, CooldownTimeLabel.formatDate(it, nowMs))
        } ?: getString(R.string.pt_cloud_inactive)
    }

    private fun statusText(): String = when (val s = status) {
        ServiceStatus.NotChecked -> getString(R.string.pt_cloud_status_unchecked)
        ServiceStatus.Checking -> getString(R.string.pt_cloud_status_checking)
        is ServiceStatus.Answered -> healthStatusText(this, s.health)
    }

    /** The cooldown line under the status: shown while the service's
     *  cooldown runs (an expired one reads as null), within the status card,
     *  which shows only while the account is served. */
    private fun renderCooldown() {
        val until = PtCloudService.unavailableUntil()
        if (until == null) {
            tvCooldown.isVisible = false
            return
        }
        tvCooldown.text = cooldownLine(this, PtCloudService.unavailableDescription(), until)
        tvCooldown.isVisible = true
    }

    /** Arms [holdJob] to re-render in [waitMs], when the button held until
     *  then comes back; none for null. */
    private fun armHold(waitMs: Long?) {
        holdJob?.cancel()
        holdJob = waitMs?.let { wait ->
            lifecycleScope.launch {
                delay(wait)
                render()
            }
        }
    }

    /** How a feature row shows ([FeatureRow.render]). */
    private enum class FeatureMode {
        /** Signed out: what the service does, with no switch and no tap. */
        DESCRIPTION,
        /** Signed in, the account admitted and the service open: its switch,
         *  live, the row its tap target. */
        LIVE,
        /** Signed in, the account not admitted or the service closed: its
         *  switch disabled and off, the row in the disabled colours. */
        DISABLED,
    }

    /** One feature row: its switch, what it reads and what it writes.
     *  [render] repaints the row with the listener detached, so a repaint
     *  never writes (the services row's rule). Disabled, the row's switch is
     *  off whatever is stored (with a withheld account nothing is, since a
     *  withholding turns both switches off), so Join or the terms is the next
     *  step; the row's enabled state carries the colours to its icon and
     *  texts (settings_row_feature), with no alpha, and the row, neither
     *  enabled nor clickable, lets a touch through to its card (which points
     *  at Join once the account lapsed). */
    private class FeatureRow(
        private val row: View,
        private val toggle: MaterialSwitch,
        private val isOn: () -> Boolean,
        private val write: (Boolean) -> Unit,
    ) {
        fun render(mode: FeatureMode) {
            val live = mode == FeatureMode.LIVE
            toggle.setOnCheckedChangeListener(null)
            row.isEnabled = mode != FeatureMode.DISABLED
            row.isClickable = live
            row.isFocusable = live
            toggle.isVisible = mode != FeatureMode.DESCRIPTION
            toggle.isEnabled = live
            toggle.isChecked = live && isOn()
            toggle.setOnCheckedChangeListener { _, checked -> write(checked) }
        }
    }

    private fun bindFeatureRow(
        rowId: Int,
        iconRes: Int,
        titleRes: Int,
        subtitleRes: Int,
        isOn: () -> Boolean,
        write: (Boolean) -> Unit,
    ): FeatureRow {
        val row = findViewById<View>(rowId)
        row.findViewById<ImageView>(R.id.ivRowIcon).setImageResource(iconRes)
        row.findViewById<TextView>(R.id.tvRowTitle).setText(titleRes)
        row.findViewById<TextView>(R.id.tvRowSubtitle).setText(subtitleRes)
        val toggle = row.findViewById<MaterialSwitch>(R.id.switchRowToggle)
        // Seeded with the listener detached (the page's render attaches it):
        // seeding through it would reset the service's cooldown every time
        // the page is built or restored. SINGLE persistence path, as on the
        // History page: the row tap only toggles, and every change funnels
        // through the listener. A disabled row takes no touch, and a
        // description's switch is disabled too; the guard keeps a
        // programmatic click (a test's) from writing through a switch that is
        // not live, so the rule does not rest on touch dispatch.
        row.setOnClickListener { if (toggle.isEnabled) toggle.toggle() }
        return FeatureRow(row, toggle, isOn, write)
    }

    /** A step's button with the spinner that sits on it, the LLM page's
     *  Save treatment: while its step's call runs the button is disabled
     *  under the spinner, its label kept but drawn in no colour, so a button
     *  as wide as its label (Show terms) keeps its width. */
    private class BusyButton(val button: MaterialButton, private val progress: ProgressBar) {
        private val labelColors: ColorStateList = button.textColors

        fun render(busy: Boolean, enabled: Boolean) {
            button.setTextColor(if (busy) HIDDEN_LABEL else labelColors)
            button.isEnabled = enabled && !busy
            progress.isVisible = busy
        }

        private companion object {
            val HIDDEN_LABEL: ColorStateList = ColorStateList.valueOf(Color.TRANSPARENT)
        }
    }

    /** A sign-in whose code is on its way: [email] as sent, when the
     *  request was answered ([sentAtMs], [SystemClock.elapsedRealtime], so a
     *  clock change moves no window), how long the code works and when a new
     *  one may be asked for, both counted from that answer. */
    private data class PendingSignIn(
        val email: String,
        val sentAtMs: Long,
        val expiresInS: Int,
        val resendAfterS: Int,
    )

    /** A refusal's hold ([hold]): the address it was answered for, and when
     *  its Retry-After ends ([SystemClock.elapsedRealtime]). */
    private data class Hold(
        val email: String,
        val untilMs: Long,
    )

    /** The flow the terms page was opened for ([termsFlow]), which its
     *  Agree answers ([onTermsResult]). */
    private sealed interface TermsFlow {
        /** The agreement's link, signed out: Agree ticks the box. */
        data object SignIn : TermsFlow

        /** The signed-in account's: Agree sends the acceptance with the
         *  stored key. */
        data object Account : TermsFlow
    }

    /** The step a call in flight belongs to ([busy]). */
    private enum class Step {
        /** Try for Free, or Send a new code. */
        REQUEST,
        /** Start Free Trial: the code exchanged for the key. */
        VERIFY,
        /** The stored key's check, a resume's or Check again's (the chip's
         *  spinner in its glyph's place while it runs). */
        KEY_CHECK,
        /** A terms call the page waits on: signed out, the fetch of Try for
         *  Free or of the agreement's link (Try for Free's spinner); signed
         *  in, a fetch or the acceptance after Agree (Show terms' spinner in
         *  its label's place). A resume does not check the key
         *  while one runs ([onResume]). */
        TERMS,
    }

    /** What the status row says outside the closed state. */
    private sealed interface ServiceStatus {
        /** Before the first health check: the page is built, not resumed. */
        data object NotChecked : ServiceStatus
        /** A health check is in flight. */
        data object Checking : ServiceStatus
        /** The last health answer; a sign-in step's `service_offline` also
         *  reads as [HealthStatus.Offline] here, until the next answer. */
        data class Answered(val health: HealthStatus) : ServiceStatus
    }

    companion object {
        /** Test seam: answers the status check in place of `GET /health`, so
         *  a JVM test of the page never reaches the service. Never set in
         *  production. */
        @Volatile internal var healthOverrideForTest: (suspend () -> HealthStatus)? = null

        /** Test seam: answers the stored key's check in place of
         *  `GET /v1/key`, so a JVM test of the page never reaches the
         *  service. Never set in production. */
        @Volatile internal var keyCheckOverrideForTest: (suspend (String) -> KeyCheck)? = null

        /** Test seam: answers Try for Free and Send a new code in place of
         *  `POST /v1/signin`, given the address. Never set in production. */
        @Volatile internal var requestCodeOverrideForTest: (suspend (String) -> CodeRequestResult)? = null

        /** Test seam: answers Start Free Trial in place of
         *  `POST /v1/signin/verify`, given the address, the code and the
         *  device's label. Never set in production. */
        @Volatile internal var verifyOverrideForTest: (suspend (String, String, String) -> VerifyResult)? = null

        /** Test seam: answers the terms' fetch in place of `GET /v1/terms`,
         *  given the locale. Never set in production. */
        @Volatile internal var termsOverrideForTest: (suspend (String) -> TermsResult)? = null

        /** Test seam: answers an acceptance of the terms in place of
         *  `POST /v1/terms/accept`, given the key, the version and the
         *  locale. Never set in production. */
        @Volatile internal var acceptTermsOverrideForTest: (suspend (String, String, String) -> TermsAcceptResult)? = null

        /** Ko-fi's page of the service's membership tiers. */
        private const val JOIN_URL = "https://ko-fi.com/playtranslate/tiers"

        /** The sign-in code's length. */
        private const val CODE_DIGITS = 6

        /** The agreement link's face: the medium weight the row titles use
         *  (Text.PT.RowTitle). */
        private const val LINK_FONT_FAMILY = "sans-serif-medium"

        /** The server's default windows (`expires_in`, `resend_after`), for a
         *  first request's 429, which states neither. */
        private const val DEFAULT_EXPIRES_IN_S = 600
        private const val DEFAULT_RESEND_AFTER_S = 60

        /** How soon after a key's store a 401 may be the replica lag, and how
         *  long to wait before asking once more. */
        private const val VERIFY_WINDOW_MS = 10_000L
        private const val VERIFY_RETRY_DELAY_MS = 1_000L

        /** How long after the check back from Join to check once more. */
        private const val JOIN_RECHECK_DELAY_MS = 10_000L

        /** A smooth scroll's length (NestedScrollView's), which the Join
         *  flash waits out when [pointAtJoin] scrolled. */
        private const val SCROLL_SETTLE_MS = 250L

        /** How far toward its ink the Join button's fill goes at a pulse's
         *  peak: the Anki sheet's flash peaks near 30% accent over its cell. */
        private const val JOIN_FLASH_PEAK = 0.3f

        private const val STATE_PENDING_EMAIL = "state_pending_email"
        private const val STATE_PENDING_SENT_AT = "state_pending_sent_at"
        private const val STATE_PENDING_EXPIRES_IN = "state_pending_expires_in"
        private const val STATE_PENDING_RESEND_AFTER = "state_pending_resend_after"
        private const val STATE_HOLD_EMAIL = "state_hold_email"
        private const val STATE_HOLD_UNTIL = "state_hold_until"
        private const val STATE_NOTICE = "state_notice"
        private const val STATE_RECHECK_AFTER_JOIN = "state_recheck_after_join"
        private const val STATE_AGREED_TERMS_VERSION = "state_agreed_terms_version"
        private const val STATE_AGREED_TERMS_LOCALE = "state_agreed_terms_locale"
        private const val STATE_TERMS_FLOW = "state_terms_flow"
        private const val STATE_TERMS_SHOWN = "state_terms_shown"

        /** [STATE_TERMS_FLOW]'s values, one per [TermsFlow]. */
        private const val TERMS_FLOW_SIGN_IN = "signin"
        private const val TERMS_FLOW_ACCOUNT = "account"

        /** The hold on [email] of [retryAfterS] seconds from [nowMs], null
         *  for an answer that gave none. */
        private fun holdFor(email: String, nowMs: Long, retryAfterS: Int?): Hold? =
            retryAfterS?.let { Hold(email, nowMs + it * 1_000L) }
    }
}

/** Whether [address] has an email address's shape, x@y.z: something, an
 *  @, and a domain with a dot inside it, with no space and no second @. Try
 *  for Free's own check before anything is sent; the server's rules (its 400
 *  `invalid_email`) stay the last word. */
private fun looksLikeEmail(address: String): Boolean = EMAIL_SHAPE.matches(address)

private val EMAIL_SHAPE = Regex("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")

/** The code field's filter: keeps the digits of what is typed or pasted,
 *  as many as fit within [max] beside what the field keeps, so a pasted
 *  "482 913" lands as "482913". */
private class DigitsFilter(private val max: Int) : InputFilter {
    override fun filter(source: CharSequence, start: Int, end: Int, dest: Spanned, dstart: Int, dend: Int): CharSequence? {
        val room = max - (dest.length - (dend - dstart))
        val digits = source.subSequence(start, end).filter { it in '0'..'9' }.take(room.coerceAtLeast(0))
        // Null keeps the source as given, when nothing was dropped.
        return if (digits.length == end - start) null else digits
    }
}

/** The agreement sentence's movement: [LinkMovementMethod] for its link,
 *  and nothing for the rest of the sentence. The framework's takes every
 *  press on the text (its scrolling fallback answers each one), so a tap
 *  off the link would never reach the row that toggles the box; this one
 *  declines a press that is not on a link, tested as [LinkMovementMethod]
 *  tests a touch against the text, and the row takes it. A press anywhere
 *  in the sentence's height counts, above or below the words as on them,
 *  the nearest line taking it (the sentence fills its row, so the link's
 *  target is the row's height, not one line of text); across, only the
 *  link's words do. Keys are the framework's: a controller selects the link
 *  and opens it. */
private object LinkOnlyMovement : LinkMovementMethod() {
    override fun onTouchEvent(widget: TextView, buffer: Spannable, event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN && !onLink(widget, buffer, event)) return false
        return super.onTouchEvent(widget, buffer, event)
    }

    private fun onLink(widget: TextView, buffer: Spannable, event: MotionEvent): Boolean {
        val layout = widget.layout ?: return false
        val x = event.x.toInt() - widget.totalPaddingLeft + widget.scrollX
        val y = event.y.toInt() - widget.totalPaddingTop + widget.scrollY
        // Above the first line or below the last, the nearest line's.
        val line = layout.getLineForVertical(y.coerceIn(0, layout.height))
        if (x < layout.getLineLeft(line) || x > layout.getLineRight(line)) return false
        val offset = layout.getOffsetForHorizontal(line, x.toFloat())
        return buffer.getSpans(offset, offset, ClickableSpan::class.java).isNotEmpty()
    }
}

/** The status row's value for a health answer. */
internal fun healthStatusText(context: Context, status: HealthStatus): String = when (status) {
    HealthStatus.Running -> context.getString(R.string.pt_cloud_status_running)
    HealthStatus.Starting -> context.getString(R.string.pt_cloud_status_starting)
    HealthStatus.NotRunning -> context.getString(R.string.pt_cloud_status_not_running)
    HealthStatus.Offline -> context.getString(R.string.pt_cloud_status_offline)
    is HealthStatus.Closed -> context.getString(R.string.pt_cloud_status_closed)
    HealthStatus.Unreachable -> context.getString(R.string.pt_cloud_status_unreachable)
    // The code as plain digits (not %d, which a locale could render in its
    // own numerals): it is a protocol value, not a quantity.
    is HealthStatus.Other -> context.getString(R.string.pt_cloud_status_other, status.code.toString())
}
