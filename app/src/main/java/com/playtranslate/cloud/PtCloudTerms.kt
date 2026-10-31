package com.playtranslate.cloud

/*
 * PlayTranslate Cloud's terms on the client (docs/api.md in the
 * playtranslate-cloud repository: "Terms" and "Accepting the terms";
 * docs/client-integration-brief.md, the update of 2026-10-30): what the two
 * terms calls answered, and the agreement the cloud page sends. Each result is
 * what one call answered, cloud-only and read by the cloud page: neither call
 * is a capture, so none records a cooldown or reports an attempt.
 * PtCloudErrors maps every answer to one of them. The server holds the
 * record of an acceptance; the app holds none.
 */

/** What the cloud page sends after Agree ([PtCloudClient.acceptTerms]): the
 *  [version] and the [locale] the text came with ([TermsResult.Text]), never
 *  a version from a 403 ([KeyCheck.TermsRequired]). */
data class TermsAgreement(val version: String, val locale: String)

/** What `GET /v1/terms` answered ([PtCloudClient.fetchTerms], each answer
 *  read by [PtCloudErrors.classifyTerms]). */
sealed interface TermsResult {

    /** The 200: the current terms. [markdown] is the whole text in the
     *  server's Markdown subset ([com.playtranslate.ui.TermsMarkdown]),
     *  [version] the date an acceptance names, and [locale] the locale
     *  served, which the acceptance echoes. */
    data class Text(val version: String, val locale: String, val markdown: String) : TermsResult

    /** A 404: a Worker from before the terms routes, which answers every
     *  path it does not know so. No terms to show, and the page goes on;
     *  never a refusal, and it cannot happen once the routes are deployed. */
    data object NotAvailable : TermsResult

    /** The 410 `service_closed`; [message] is the operator's text, for the
     *  page only. */
    data class Closed(val message: String?) : TermsResult

    /** The terms cannot be had now: the per-address 429, the 503
     *  `terms_misconfigured`, a 410 without its word, any other answer, and
     *  a 200 that does not decode (one longer than the client reads
     *  included). */
    data object Unavailable : TermsResult

    /** No answer: DNS, connect, TLS, a timeout. */
    data object Unreachable : TermsResult
}

/** What `POST /v1/terms/accept` answered ([PtCloudClient.acceptTerms], each
 *  answer read by [PtCloudErrors.classifyTermsAccept]). */
sealed interface TermsAcceptResult {

    /** The 200: the acceptance is recorded, now or before (the account
     *  already held the current version or a later one). */
    data object Accepted : TermsAcceptResult

    /** The 409 `terms_outdated`: the text changed since it was fetched, and
     *  [version] is the current one (null when the body left it out). The
     *  page fetches the text again and shows it. */
    data class Outdated(val version: String?) : TermsAcceptResult

    /** The 401: the key is unknown, revoked, or its user disabled or
     *  deleted, whatever the body. */
    data object Refused : TermsAcceptResult

    /** The 410 `service_closed`; [message] is the operator's text, for the
     *  page only. */
    data class Closed(val message: String?) : TermsAcceptResult

    /** Nothing was recorded, and the answer says nothing more the page acts
     *  on: the per-address 429, a 503 (`terms_unavailable`, `auth
     *  unavailable`, `terms_misconfigured`), a 400, 413 or 415 (a malformed
     *  request, a bug), a 409 or a 410 without its word, any other answer,
     *  and no answer at all. */
    data object NotRecorded : TermsAcceptResult
}
