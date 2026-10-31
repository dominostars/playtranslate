package com.playtranslate.cloud

/*
 * PlayTranslate Cloud's key check and sign-in results (docs/api.md in the
 * playtranslate-cloud repository: "Key check", "Sign-in" and "Service
 * closed"; docs/client-signin-handoff.md, items 1, 2 and 7). Each is what one
 * call answered, cloud-only and read by the cloud page: none is a fact about
 * the service that the waterfall acts on, so none records a cooldown or
 * reports an attempt. PtCloudErrors maps every answer to one of them.
 */

/** The `source` and `last_source` of the free trial; any other value is a
 *  membership (`kofi`, `manual`, `play`). */
const val SOURCE_TRIAL = "trial"

/**
 * What `GET /v1/key` said of a key ([PtCloudClient.checkKey], each answer
 * read by [PtCloudErrors.classifyKeyCheck]). Cloud-only, in place of the
 * shared [com.playtranslate.translation.KeyStatus], which has no room for the
 * account: the answers that describe the account ([Account]) become the
 * record kept beside the key ([PtCloudAccount.of]).
 */
sealed interface KeyCheck {

    /** The answers that describe the account: the 200 ([Accepted]) and the
     *  two 403s of a known, good key ([Withheld]). */
    sealed interface Account : KeyCheck

    /** The 200: the key is known, not revoked, its user active and entitled.
     *  [source] (`trial`, `kofi`, `manual` or `play`) and [entitledUntilMs]
     *  name the entitlement that runs longest, [entitledUntilMs] null beside a
     *  [source] being open-ended. [source] null is an older Worker, the new
     *  one after its read failed, or a 200 that did not decode: the key is
     *  good, and nothing is known of the account. */
    data class Accepted(val source: String?, val entitledUntilMs: Long?) : Account

    /** The two 403s of a known, good key: the server withholds service from
     *  the account now, and says why ([Inactive], [TermsRequired]). Read from
     *  the body in one place ([PtCloudErrors.withheldAccount]) for the two
     *  routes and the key check, handed to the service in one
     *  ([PtCloudService.onAccountWithheld], [PtCloudService.onStoredKeyChecked])
     *  and made a record in one ([PtCloudAccount.of]). */
    sealed interface Withheld : Account

    /** The 403 `no_entitlement`: a known key whose account has no active
     *  entitlement. [lastSource] and [endedAtMs] name the one that ended most
     *  recently ([SOURCE_TRIAL] is the free trial having run out); both null
     *  when none has ended or the Worker left them out, which reads as no
     *  active membership, never as a trial that ended. */
    data class Inactive(val lastSource: String?, val endedAtMs: Long?) : Withheld

    /** The 403 `terms_required`: a known key whose account is entitled and
     *  has not accepted the current terms, whose [version] the body names
     *  (null when it left it out). The server tells an ended entitlement
     *  first ([Inactive]), so this answer says the account is entitled.
     *  [version] is carried for logs and tests only: the cloud page accepts
     *  the version of the text it fetched and showed, never one from a 403. */
    data class TermsRequired(val version: String?) : Withheld

    /** The HTTP 401: the key is unknown, revoked, or its user disabled or
     *  deleted. */
    data object Refused : KeyCheck

    /** The 410 `service_closed`: the service has closed for good. [message]
     *  is the operator's text, for the page only. */
    data class Closed(val message: String?) : KeyCheck

    /** No verdict on the key: the per-address 429, the 503 while the
     *  accounts database did not answer, any other answer (a 403 without
     *  either of its words and a 410 without its word among them), and no
     *  answer at all. */
    data object NotChecked : KeyCheck
}

/** What `POST /v1/signin` answered ([PtCloudClient.requestCode], each answer
 *  read by [PtCloudErrors.classifyRequestCode]): its own three here, and the
 *  ones it shares with verify ([SignInAnswer]). */
sealed interface CodeRequestResult {

    /** The 200: a code is on its way. It works for [expiresInS] seconds, and
     *  a new one can be asked for [resendAfterS] seconds after this answer. */
    data class Sent(val expiresInS: Int, val resendAfterS: Int) : CodeRequestResult

    /** The 429 `too_many_codes`: the address has had its codes for the UTC
     *  day, and [retryAfterS] is the seconds to 00:00 UTC. Worded apart from
     *  [SignInAnswer.RateLimited]'s "a code may already be on its way". */
    data class TooManyCodes(val retryAfterS: Int?) : CodeRequestResult

    /** The 503 `mail_failed`: the mail could not be sent. The server keeps
     *  the address's cool-down after a send that failed, so [retryAfterS] is
     *  when to try again. */
    data class MailFailed(val retryAfterS: Int?) : CodeRequestResult
}

/** What `POST /v1/signin/verify` answered ([PtCloudClient.verifyCode], each
 *  answer read by [PtCloudErrors.classifyVerifyCode]): its own four here, and
 *  the ones it shares with the request ([SignInAnswer]). */
sealed interface VerifyResult {

    /** The 200: signed in. [key] is the client key, a usable bearer value
     *  ([PtCloudConfig.isUsableKey]), answered once and never again by any
     *  route; [account] is the account's state from verify's own fields, as
     *  the key check would answer it ([PtCloudAccount.of] makes the record). */
    data class SignedIn(val key: String, val account: KeyCheck.Account) : VerifyResult

    /** The 401 `code_invalid`: a wrong code with [attemptsLeft] tries left on
     *  it, or, at 0, no live code at all (expired, used, replaced by a newer
     *  one, never sent, or out of tries). */
    data class CodeInvalid(val attemptsLeft: Int) : VerifyResult

    /** The server answered with nothing the app can use: a 200 that did not
     *  decode or whose key is not a usable bearer value (nothing is stored),
     *  or the 400 `bad request` for a code or a label the app never sends. */
    data object BadAnswer : VerifyResult

    /** The 403 `account_disabled`: the code is spent and no key was made. */
    data object Disabled : VerifyResult
}

/**
 * The answers both sign-in calls share, declared once: each is a
 * [CodeRequestResult] and a [VerifyResult], and one helper of
 * [PtCloudErrors] reads them for both routes. A [retryAfterS] is the answer's
 * `Retry-After` in seconds as given, null when it is missing or unreadable.
 */
sealed interface SignInAnswer : CodeRequestResult, VerifyResult {

    /** A 429 other than `too_many_codes`: the per-address limit (`rate
     *  limited`, met before the body is read) or, on the request, the
     *  `cooldown` of a code sent to the address less than a minute ago, which
     *  still works. Both read as "a code may already be on its way". */
    data class RateLimited(val retryAfterS: Int?) : SignInAnswer

    /** The 503 `service_offline`: the service is offline, and a brand-new
     *  address cannot sign in while it is (a member's address can). */
    data class Offline(val retryAfterS: Int?) : SignInAnswer

    /** Sign-in cannot be served now: the 503 `auth unavailable` (the accounts
     *  database did not answer), on the request the 503 `daily_cap` (the
     *  day's codes for new addresses are spent) and a 200 that did not
     *  decode, and any answer the tables do not name, a 413 or a 415 (a
     *  malformed request, a bug) among them. */
    data class Unavailable(val retryAfterS: Int?) : SignInAnswer

    /** The 400 `invalid_email`: not an address the server accepts. */
    data object BadAddress : SignInAnswer

    /** A 404: a Worker from before the sign-in routes, which answers every
     *  path it does not know so. Sign-in is not available yet (the page says
     *  so, with no other way in); never a refusal of the address. */
    data object NotAvailable : SignInAnswer

    /** The 410 `service_closed`; [message] is the operator's text, for the
     *  page only. */
    data class Closed(val message: String?) : SignInAnswer

    /** No answer: DNS, connect, TLS, a timeout. */
    data object Unreachable : SignInAnswer
}

/**
 * The label a verify sends for this device ([VerifyRequestBody.label]), which
 * the operator's `user show` reads to tell a member's keys apart: [model]
 * after [manufacturer] (the caller passes `Build.MODEL` and
 * `Build.MANUFACTURER`), each trimmed, the manufacturer left out when the
 * model already starts with it, cut at the server's 64 characters. Nothing
 * else about the device, and no text the user typed. Blank when both are,
 * which the server stores as `sign-in`.
 */
fun deviceLabel(manufacturer: String?, model: String?): String {
    val maker = manufacturer.orEmpty().trim()
    val name = model.orEmpty().trim()
    val label = if (maker.isEmpty() || name.startsWith(maker, ignoreCase = true)) name else "$maker $name".trim()
    return label.take(MAX_LABEL_CHARS).trimEnd()
}

/** The longest label the server keeps; it cuts a longer one there. */
private const val MAX_LABEL_CHARS = 64
