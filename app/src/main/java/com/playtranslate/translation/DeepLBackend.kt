package com.playtranslate.translation

import com.google.gson.Gson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.Locale

/** Thrown when the DeepL free quota for the month has been exhausted (HTTP 456). */
class DeepLQuotaExceededException : IOException("DeepL monthly quota exceeded")

/** Thrown when the API key is rejected (HTTP 403). */
class DeepLAuthException : IOException("Invalid DeepL API key")

/** Thrown when DeepL rate-limits the call (HTTP 429). DeepL exposes no
 *  retry-after or X-RateLimit-* headers, so callers fall through silently
 *  like the other backends' rate-limit exceptions; the [CooldownState]
 *  ladder governs how long the backend stays skipped. */
class DeepLRateLimitException : IOException("DeepL rate limit exceeded")

/**
 * DeepL REST API backend.
 *
 * Free API keys end with ":fx" and use api-free.deepl.com.
 * Paid API keys use api.deepl.com. Host is chosen per-call from the
 * key returned by [keyProvider] so a Settings change propagates without
 * rebuilding the registry.
 *
 * [keyProvider] and [enabledProvider] are invoked on the calling
 * coroutine's thread (Dispatchers.IO inside [translate] / [refreshStatus]).
 * Implementations must be cheap and thread-safe; `Prefs(context).deeplApiKey`
 * / `Prefs(context).deeplEnabled` qualify.
 *
 * [enabledProvider] reflects the user's explicit on/off state from
 * Settings, which is independent of whether the key is set. The user
 * may disable DeepL while keeping the saved key (for later re-enable),
 * so `isUsable` AND's both signals.
 *
 * [client] is injectable for tests (canned-response interceptor); in
 * production we instantiate a default [OkHttpClient].
 */
class DeepLBackend(
    private val keyProvider: () -> String?,
    private val enabledProvider: () -> Boolean,
    /** Default is a non-persisting test-mode state; production wiring in
     *  [com.playtranslate.PlayTranslateApplication] passes a Context-backed
     *  instance so cooldowns survive process restart. Kept defaulted to
     *  avoid forcing every unit-test constructor call to pass one. */
    private val cooldownState: CooldownState = CooldownState(context = null, backendId = "deepl"),
    private val client: OkHttpClient = OkHttpClient(),
) : TranslationBackend, QuotaAware, BatchTranslator, Cooldownable {

    override val id: BackendId = "deepl"
    override val displayName: String = "DeepL"
    override val priority: Int = 10
    override val requiresInternet: Boolean = true
    override val isDegradedFallback: Boolean = false
    override val quality: BackendQuality = BackendQuality.Better

    private val gson = Gson()

    override fun unavailableUntil(): Long? {
        clearCooldownIfCredentialsChanged()
        return cooldownState.unavailableUntil()
    }
    override fun unavailableDescription(): String? = cooldownState.unavailableDescription()
    override fun recordSuccess(attemptStartedAtMs: Long) =
        cooldownState.recordSuccess(attemptStartedAtMs)

    /** Last-seen key fingerprint. When the user replaces the DeepL key
     *  (free → pro, or any swap), any persisted cooldown — including
     *  a Free-tier "Monthly quota used · Retry on Jun 1" that may be
     *  weeks away — is cleared because it belonged to the prior key. */
    @Volatile private var lastCredentialsFingerprint: String? = null

    private fun clearCooldownIfCredentialsChanged() {
        val current = keyProvider() ?: ""
        val prev = lastCredentialsFingerprint
        lastCredentialsFingerprint = current
        if (prev != null && prev != current) {
            cooldownState.recordSuccess(System.currentTimeMillis())
        }
    }

    // ── Status state ─────────────────────────────────────────────────────
    //
    // [status] is a synchronous getter that reads configuration (key /
    // enabled state) and falls through to the cached dynamic state
    // produced by the most recent [refreshStatus]. The cache lives for
    // the lifetime of the backend (process-scoped via the registry).
    //
    // [refreshMutex] single-flights concurrent refreshes; nothing
    // strictly requires it today but a parallel toggle + Settings open
    // could double-fire.

    @Volatile private var cachedDynamic: BackendStatus = BackendStatus.Loading
    private val refreshMutex = Mutex()

    override val status: BackendStatus
        get() {
            val key = keyProvider()
            if (key.isNullOrBlank()) {
                return BackendStatus.Info("API Key Required (Free option)", Tone.Neutral)
            }
            // Surface the cached fetch result regardless of [enabledProvider]
            // so the user sees their DeepL quota even when the toggle is
            // off (helps them decide whether to re-enable).
            return cachedDynamic
        }

    override suspend fun refreshStatus(): BackendStatus = refreshMutex.withLock {
        val key = keyProvider()?.takeIf { it.isNotBlank() } ?: run {
            // Reset to Loading so a future re-key triggers a fresh fetch
            // via the renderer's "render → if Loading, refresh" loop.
            cachedDynamic = BackendStatus.Loading
            return@withLock status
        }
        cachedDynamic = BackendStatus.Loading
        val newState = try {
            val raw = fetchUsageRaw(key)
            BackendStatus.Quota(
                used = raw.character_count,
                limit = raw.character_limit,
                resetEpochMs = parseIso8601(raw.end_time),
            )
        } catch (e: CancellationException) {
            // Honour structured concurrency: the renderer's coroutine may
            // cancel us when Settings tears down. Don't swallow.
            throw e
        } catch (e: DeepLAuthException) {
            BackendStatus.Info("Invalid API Key", Tone.Danger)
        } catch (e: IOException) {
            // Approximate: any IO failure surfaces as "no internet". A
            // ConnectivityManager check could disambiguate offline vs
            // endpoint-down, but a single italic muted line is enough
            // for the user to understand the state.
            BackendStatus.Info("No internet — can't check usage", italic = true)
        } catch (e: Exception) {
            // Defensive: malformed JSON, OOM during parse, or any other
            // unexpected runtime exception from the network/parse path.
            // Map to a safe "couldn't check" state instead of letting
            // the exception escape into the renderer's launched
            // coroutine and crash Settings.
            BackendStatus.Info("Couldn't check usage", italic = true)
        }
        cachedDynamic = newState
        newState
    }

    override fun isUsable(source: String, target: String): Boolean =
        enabledProvider() && !keyProvider().isNullOrBlank()

    override suspend fun translate(text: String, source: String, target: String): String {
        // Single-text path now delegates to the same request helper as
        // the batch path — keeps the body shape identical and avoids
        // drift between the two.
        val out = postTranslate(listOf(text), source, target)
        return out.firstOrNull() ?: throw IOException("No translation in DeepL response")
    }

    override suspend fun translateBatch(
        texts: List<String>,
        source: String,
        target: String,
    ): List<String> {
        if (texts.size > MAX_DEEPL_BATCH) {
            // DeepL's documented cap is 50 strings (or 128 KiB) per
            // request. OCR rarely produces more than ~10 groups so this
            // is defensive — refuse oversized batches and let the
            // registry fall through to the next backend.
            throw BatchParseException("DeepL batch exceeds $MAX_DEEPL_BATCH strings (got ${texts.size})")
        }
        val out = postTranslate(texts, source, target)
        if (out.size != texts.size) {
            throw BatchParseException("DeepL returned ${out.size} translations for ${texts.size} inputs")
        }
        return out
    }

    /** Shared request body builder + response parser. Used by both the
     *  single-text [translate] and the batched [translateBatch] paths.
     *  Returns the raw list of translation strings from DeepL's
     *  positional `translations` array. */
    private suspend fun postTranslate(
        texts: List<String>,
        source: String,
        target: String,
    ): List<String> = withContext(Dispatchers.IO) {
        clearCooldownIfCredentialsChanged()
        val apiKey = keyProvider()?.takeIf { it.isNotBlank() }
            ?: throw IOException("DeepL API key not configured")

        val host = hostFor(apiKey)
        val body = gson.toJson(
            mapOf(
                "text"        to texts,
                "target_lang" to toDeepLCode(target),
                "source_lang" to toDeepLCode(source),
            )
        )
        val request = Request.Builder()
            .url("https://$host/v2/translate")
            .addHeader("Authorization", "DeepL-Auth-Key $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()

        try {
            client.newCall(request).execute().use { response ->
                when (response.code) {
                    403 -> throw DeepLAuthException()
                    429 -> {
                        // DeepL exposes no Retry-After or X-RateLimit-*
                        // headers — use a fixed 10s cooldown matching their
                        // own CLI's lowest backoff rung. Repeated 429s within
                        // the window are absorbed by the in-cooldown guard.
                        cooldownState.recordParsedFailure(
                            retryAt = System.currentTimeMillis() + DEEPL_RATE_LIMIT_MS,
                            description = "Rate limited",
                        )
                        throw DeepLRateLimitException()
                    }
                    456 -> {
                        // Synthesize "first of next month UTC" — DeepL Free
                        // doesn't return end_time, and we'd rather not block
                        // postTranslate on a /v2/usage round-trip. The
                        // refreshStatus path (called on Settings open or
                        // explicit re-probe) catches drift and updates the
                        // cached Quota with the real end_time when available.
                        cooldownState.recordParsedFailure(
                            retryAt = firstOfNextMonthUtc(),
                            description = "Monthly quota used",
                        )
                        throw DeepLQuotaExceededException()
                    }
                    else -> if (!response.isSuccessful) {
                        if (response.code >= 500) {
                            cooldownState.recordLadderFailure(
                                CooldownLadder.RateLimit, "Server error"
                            )
                        }
                        throw StructuralFailureException("DeepL error ${response.code}")
                    }
                }
                val responseBody = response.body?.string()
                    ?: throw StructuralFailureException("Empty response from DeepL")
                gson.fromJson(responseBody, DeepLResponse::class.java).translations.map { it.text }
            }
        } catch (e: DeepLAuthException) { throw e }
        catch (e: DeepLRateLimitException) { throw e }
        catch (e: DeepLQuotaExceededException) { throw e }
        catch (e: BatchParseException) { throw e }
        catch (e: StructuralFailureException) {
            // 4xx / 5xx (already recorded above) / empty payload —
            // deterministic upstream, no network-ladder bump.
            throw e
        }
        catch (e: IOException) {
            cooldownState.recordNetworkFailure("Connection failed")
            throw e
        }
    }

    /** Thin wrapper over [fetchUsageRaw] for the [QuotaAware] capability.
     *  Used by callers other than the Settings status renderer (e.g. a
     *  future quota meter UI). Returns null when no key is configured. */
    override suspend fun currentQuota(): QuotaStatus? {
        val apiKey = keyProvider()?.takeIf { it.isNotBlank() } ?: return null
        val raw = fetchUsageRaw(apiKey)
        return QuotaStatus(
            used = raw.character_count,
            limit = raw.character_limit,
            resetEpochMs = parseIso8601(raw.end_time),
        )
    }

    private suspend fun fetchUsageRaw(apiKey: String): DeepLUsageResponse =
        withContext(Dispatchers.IO) {
            val host = hostFor(apiKey)
            val request = Request.Builder()
                .url("https://$host/v2/usage")
                .addHeader("Authorization", "DeepL-Auth-Key $apiKey")
                .build()
            client.newCall(request).execute().use { response ->
                when (response.code) {
                    403 -> throw DeepLAuthException()
                    else -> if (!response.isSuccessful) throw IOException("DeepL usage error ${response.code}")
                }
                val body = response.body?.string() ?: throw IOException("Empty usage response")
                gson.fromJson(body, DeepLUsageResponse::class.java)
            }
        }

    override fun close() {
        // Background daemon thread — evictAll() synchronously writes a
        // TLS close-notify on any live socket, which StrictMode flags as
        // NetworkOnMainThreadException when close() is invoked from the
        // UI thread. Fire-and-forget on a daemon to dodge that.
        val c = client
        Thread {
            c.dispatcher.executorService.shutdown()
            c.connectionPool.evictAll()
        }.apply { isDaemon = true; name = "DeepLBackend-close" }.start()
    }

    private fun hostFor(apiKey: String): String =
        if (apiKey.endsWith(":fx")) "api-free.deepl.com" else "api.deepl.com"

    private fun toDeepLCode(mlKitCode: String): String = when (mlKitCode) {
        "ja" -> "JA"
        "en" -> "EN"
        "zh" -> "ZH"
        "ko" -> "KO"
        "fr" -> "FR"
        "de" -> "DE"
        "es" -> "ES"
        "it" -> "IT"
        "pt" -> "PT-PT"
        "ru" -> "RU"
        else -> mlKitCode.uppercase(Locale.ROOT)
    }

    private data class DeepLResponse(val translations: List<Translation>) {
        data class Translation(val text: String = "", val detected_source_language: String = "")
    }

    companion object {
        /** DeepL's documented per-request cap is 50 strings (or 128 KiB).
         *  OCR rarely produces this many groups; the cap exists so a
         *  pathological capture refuses the batch cleanly rather than
         *  hitting an opaque 4xx from the server. */
        private const val MAX_DEEPL_BATCH = 50

        /** Fixed cooldown for DeepL 429. DeepL exposes no rate-limit
         *  headers — matches their own CLI's lowest backoff rung. */
        private const val DEEPL_RATE_LIMIT_MS = 10L * 1000
    }

    private data class DeepLUsageResponse(
        val character_count: Long = 0,
        val character_limit: Long = 0,
        val end_time: String? = null,   // ISO 8601, Pro only
    )
}
