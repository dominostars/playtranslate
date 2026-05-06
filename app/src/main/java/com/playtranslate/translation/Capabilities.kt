package com.playtranslate.translation

/**
 * Side interfaces that backends opt into to declare extra capabilities
 * beyond the base [TranslationBackend] contract.
 *
 * The capability-interface pattern lets new metadata land without
 * disturbing existing backends — code that wants the capability does a
 * smart-cast (`val q = backend as? QuotaAware`) and skips backends that
 * don't implement it.
 *
 * Future capabilities will land here following the same pattern.
 * For example, a `Downloadable` interface will surface backends with
 * downloadable assets (model files, ngram tables, etc.) along with
 * progress reporting and a `ensureDownloadedFor(source, target)`
 * method. ML Kit's download story stays internal to MlKitBackend for
 * now; if/when the UI needs to surface model-download progress, the
 * interface lands then.
 */

/**
 * A snapshot of API quota usage for a backend (e.g. DeepL's monthly
 * character limit on the free tier). Implementers read this from
 * the provider's usage endpoint; values may be slightly stale.
 *
 * @param used      Characters / units consumed in the current period.
 * @param limit     Quota cap for the current period.
 * @param resetEpochMs Wall-clock millis when the quota resets, or null
 *                  if the provider doesn't expose a reset boundary.
 */
data class QuotaStatus(
    val used: Long,
    val limit: Long,
    val resetEpochMs: Long?,
)

/**
 * Backends with a queryable quota / rate limit. Today only DeepL
 * implements this; ML Kit (offline) and Google's gtx endpoint
 * have no quota concept exposed to clients.
 */
interface QuotaAware {
    /** Returns the current quota snapshot, or null if the provider's
     *  usage endpoint is not yet wired up or the call fails. Callers
     *  should treat null as "unknown" — not as "unlimited". */
    suspend fun currentQuota(): QuotaStatus?
}
