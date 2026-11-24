package com.playtranslate.translation

import android.content.SharedPreferences
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/**
 * Test fakes for [TranslationBackend]. All counters are exposed so
 * tests can assert which backends actually got called (vs. skipped or
 * short-circuited by the waterfall).
 */

internal class FakeOnlineBackend(
    override val id: BackendId,
    override val priority: Int,
    override val displayName: String = "fake-$id",
    override val status: BackendStatus = BackendStatus.Hidden,
    private val response: String = "translated-by-$id",
    private val usable: Boolean = true,
) : TranslationBackend {
    override val requiresInternet: Boolean = true
    override val isDegradedFallback: Boolean = false
    val translateCalls = AtomicInteger(0)
    var closed = false

    override fun isUsable(source: String, target: String): Boolean = usable
    override suspend fun translate(text: String, source: String, target: String): String {
        translateCalls.incrementAndGet()
        return response
    }
    override fun close() { closed = true }
}

internal class FakeThrowingBackend(
    override val id: BackendId,
    override val priority: Int,
    override val displayName: String = "fake-$id",
    override val status: BackendStatus = BackendStatus.Hidden,
    private val exception: Exception = IOException("synthetic failure"),
) : TranslationBackend {
    override val requiresInternet: Boolean = true
    override val isDegradedFallback: Boolean = false
    val translateCalls = AtomicInteger(0)

    override fun isUsable(source: String, target: String): Boolean = true
    override suspend fun translate(text: String, source: String, target: String): String {
        translateCalls.incrementAndGet()
        throw exception
    }
}

internal class FakeDegradedBackend(
    override val id: BackendId = "mlkit-fake",
    override val priority: Int = 99,
    override val displayName: String = "fake-degraded",
    override val status: BackendStatus = BackendStatus.Hidden,
    private val response: String = "offline-fallback",
) : TranslationBackend {
    override val requiresInternet: Boolean = false
    override val isDegradedFallback: Boolean = true
    val translateCalls = AtomicInteger(0)

    override fun isUsable(source: String, target: String): Boolean = true
    override suspend fun translate(text: String, source: String, target: String): String {
        translateCalls.incrementAndGet()
        return response
    }
}

/** Online [BatchTranslator] whose batch call throws [batchException]
 *  and whose per-text call succeeds, for pinning what the registry does
 *  after a failed batch: retry per-text on this backend, or move on. */
internal class FakeFailingBatchBackend(
    override val id: BackendId,
    override val priority: Int,
    private val batchException: Exception,
    override val displayName: String = "fake-batch-$id",
    override val status: BackendStatus = BackendStatus.Hidden,
) : TranslationBackend, BatchTranslator {
    override val requiresInternet: Boolean = true
    override val isDegradedFallback: Boolean = false
    val batchCalls = AtomicInteger(0)
    val translateCalls = AtomicInteger(0)

    override fun isUsable(source: String, target: String): Boolean = true
    override suspend fun translateBatch(texts: List<String>, source: String, target: String): List<String> {
        batchCalls.incrementAndGet()
        throw batchException
    }
    override suspend fun translate(text: String, source: String, target: String): String {
        translateCalls.incrementAndGet()
        return "translated-by-$id"
    }
}

/** Offline backend (Bergamot / on-device LLM / ML Kit stand-in) with a
 *  controllable [usable], so offline-readiness tests can model enabled/disabled +
 *  installed state. [requiresInternet] is false — it's the offline tier. */
internal class FakeOfflineBackend(
    override val id: BackendId,
    override val priority: Int = 10,
    override val displayName: String = "fake-offline-$id",
    override val status: BackendStatus = BackendStatus.Hidden,
    private val usable: Boolean = true,
) : TranslationBackend {
    override val requiresInternet: Boolean = false
    override val isDegradedFallback: Boolean = false
    override fun isUsable(source: String, target: String): Boolean = usable
    override suspend fun translate(text: String, source: String, target: String): String = "offline-$id"
}

/** Fake backend that implements [Cooldownable], for verifying the
 *  registry's skip-when-down logic. Tests set [cooldownState] up front
 *  with the desired in-cooldown / ready state. */
internal class FakeCooldownableBackend(
    override val id: BackendId,
    override val priority: Int,
    override val displayName: String = "fake-cool-$id",
    override val status: BackendStatus = BackendStatus.Hidden,
    private val response: String = "translated-by-$id",
    val cooldownState: CooldownState = CooldownState(context = null, backendId = id),
    private val usable: Boolean = true,
) : TranslationBackend, Cooldownable {
    override val requiresInternet: Boolean = true
    override val isDegradedFallback: Boolean = false
    val translateCalls = AtomicInteger(0)
    val recordSuccessCalls = AtomicInteger(0)

    override fun isUsable(source: String, target: String): Boolean = usable
    override suspend fun translate(text: String, source: String, target: String): String {
        translateCalls.incrementAndGet()
        return response
    }

    override fun unavailableUntil(): Long? = cooldownState.unavailableUntil()
    override fun unavailableDescription(): String? = cooldownState.unavailableDescription()
    override fun unavailableCause(): CooldownCause? = cooldownState.unavailableCause()
    override fun recordSuccess(attemptStartedAtMs: Long) {
        recordSuccessCalls.incrementAndGet()
        cooldownState.recordSuccess(attemptStartedAtMs)
    }
    override fun onConnectivityRestored(): Boolean = cooldownState.onConnectivityRestored()
    override fun resetCooldown() = cooldownState.resetCooldown()
}

/** Cooldownable backend that succeeds on some texts and fails (with a
 *  recorded cooldown) on others. Exists specifically to verify the
 *  per-text fan-out's "only clear cooldown on full success" rule —
 *  the failing text records a cooldown, the sibling succeeds, and the
 *  registry must NOT call recordSuccess. */
internal class FakeMixedResultCooldownableBackend(
    override val id: BackendId,
    override val priority: Int,
    /** Set of texts that should fail with a recorded cooldown. */
    private val failingTexts: Set<String>,
    override val displayName: String = "fake-mixed-$id",
    override val status: BackendStatus = BackendStatus.Hidden,
    val cooldownState: CooldownState = CooldownState(context = null, backendId = id),
) : TranslationBackend, Cooldownable {
    override val requiresInternet: Boolean = true
    override val isDegradedFallback: Boolean = false
    val translateCalls = AtomicInteger(0)
    val recordSuccessCalls = AtomicInteger(0)

    override fun isUsable(source: String, target: String): Boolean = true
    override suspend fun translate(text: String, source: String, target: String): String {
        translateCalls.incrementAndGet()
        if (text in failingTexts) {
            cooldownState.recordParsedFailure(
                retryAt = System.currentTimeMillis() + 60_000,
                description = "Rate limited",
            )
            throw IOException("synthetic 429 for text=$text")
        }
        return "ok-$text"
    }

    override fun unavailableUntil(): Long? = cooldownState.unavailableUntil()
    override fun unavailableDescription(): String? = cooldownState.unavailableDescription()
    override fun recordSuccess(attemptStartedAtMs: Long) {
        recordSuccessCalls.incrementAndGet()
        cooldownState.recordSuccess(attemptStartedAtMs)
    }
    override fun onConnectivityRestored(): Boolean = cooldownState.onConnectivityRestored()
    override fun resetCooldown() = cooldownState.resetCooldown()
}

/** A [PromptCompleter] for the registry's completion pass and the Ask
 *  session: [answer] decides each call (a reply, a blank, a throw, a
 *  suspension), [canComplete] is fixed, and it takes part in cooldowns
 *  through its own [cooldownState]. It also translates, so the same fake
 *  can stand on both sides of a "complete versus translate" contrast.
 *  [requiresInternet] picks the online or the on-device shape. Counters
 *  say what the registry called. */
internal class FakeCompleterBackend(
    override val id: BackendId,
    override val priority: Int,
    override val displayName: String = "fake-llm-$id",
    override val requiresInternet: Boolean = true,
    private val canComplete: Boolean = true,
    val cooldownState: CooldownState = CooldownState(context = null, backendId = id),
    private val answer: suspend (system: String, user: String) -> String = { _, _ -> "answer-from-$id" },
) : TranslationBackend, PromptCompleter, Cooldownable {
    override val status: BackendStatus = BackendStatus.Hidden
    override val isDegradedFallback: Boolean = false
    val completeCalls = AtomicInteger(0)
    val translateCalls = AtomicInteger(0)
    val recordSuccessCalls = AtomicInteger(0)
    /** The messages of the last [complete] call. */
    @Volatile var lastPrompt: Pair<String, String>? = null

    override fun isUsable(source: String, target: String): Boolean = true
    override suspend fun translate(text: String, source: String, target: String): String {
        translateCalls.incrementAndGet()
        return "translated-by-$id"
    }

    override fun canComplete(): Boolean = canComplete
    override suspend fun complete(system: String, user: String): String {
        completeCalls.incrementAndGet()
        lastPrompt = system to user
        return answer(system, user)
    }

    override fun unavailableUntil(): Long? = cooldownState.unavailableUntil()
    override fun unavailableDescription(): String? = cooldownState.unavailableDescription()
    override fun unavailableCause(): CooldownCause? = cooldownState.unavailableCause()
    override fun recordSuccess(attemptStartedAtMs: Long) {
        recordSuccessCalls.incrementAndGet()
        cooldownState.recordSuccess(attemptStartedAtMs)
    }
    override fun onConnectivityRestored(): Boolean = cooldownState.onConnectivityRestored()
    override fun resetCooldown() = cooldownState.resetCooldown()
}

/** A [SentenceAnswerer] for the registry's completion pass: [answer]
 *  decides each call from the question it is put (an answer, out of scope,
 *  a throw), [canAnswer] is fixed, [pausedUntil] is the pause of its answers
 *  alone, and it takes part in cooldowns through its own [cooldownState].
 *  Online, as the one real answerer is. Counters say what the registry
 *  called. */
internal class FakeAnswererBackend(
    override val id: BackendId,
    override val priority: Int,
    override val displayName: String = "fake-answerer-$id",
    private val canAnswer: Boolean = true,
    @Volatile var pausedUntil: Long? = null,
    val cooldownState: CooldownState = CooldownState(context = null, backendId = id),
    private val answer: suspend (SentenceQuestion) -> SentenceAnswer = { SentenceAnswer.Text("answer-from-$id") },
) : TranslationBackend, SentenceAnswerer, Cooldownable {
    override val status: BackendStatus = BackendStatus.Hidden
    override val requiresInternet: Boolean = true
    override val isDegradedFallback: Boolean = false
    val answerCalls = AtomicInteger(0)
    val translateCalls = AtomicInteger(0)
    val recordSuccessCalls = AtomicInteger(0)
    /** The question of the last [answer] call. */
    @Volatile var lastQuestion: SentenceQuestion? = null

    override fun isUsable(source: String, target: String): Boolean = true
    override suspend fun translate(text: String, source: String, target: String): String {
        translateCalls.incrementAndGet()
        return "translated-by-$id"
    }

    override fun canAnswer(): Boolean = canAnswer
    override fun answersUnavailableUntil(): Long? = pausedUntil
    override suspend fun answer(question: SentenceQuestion): SentenceAnswer {
        answerCalls.incrementAndGet()
        lastQuestion = question
        return answer.invoke(question)
    }

    override fun unavailableUntil(): Long? = cooldownState.unavailableUntil()
    override fun unavailableDescription(): String? = cooldownState.unavailableDescription()
    override fun unavailableCause(): CooldownCause? = cooldownState.unavailableCause()
    override fun recordSuccess(attemptStartedAtMs: Long) {
        recordSuccessCalls.incrementAndGet()
        cooldownState.recordSuccess(attemptStartedAtMs)
    }
    override fun onConnectivityRestored(): Boolean = cooldownState.onConnectivityRestored()
    override fun resetCooldown() = cooldownState.resetCooldown()
}

/**
 * Minimal in-memory [SharedPreferences] for tests that need to
 * construct production classes that take a SharedPreferences without
 * pulling Robolectric. Only the read/edit/apply path is exercised
 * here — listeners, commits, and stringsets pass through but aren't
 * load-bearing in any current test.
 */
internal class FakeSharedPreferences : SharedPreferences {
    private val data = HashMap<String, Any?>()
    private val listeners = mutableSetOf<SharedPreferences.OnSharedPreferenceChangeListener>()

    override fun getAll(): Map<String, *> = data.toMap()
    override fun getString(key: String?, defValue: String?): String? =
        data[key] as? String ?: defValue
    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String?, defValues: Set<String>?): Set<String>? =
        data[key] as? Set<String> ?: defValues
    override fun getInt(key: String?, defValue: Int): Int = data[key] as? Int ?: defValue
    override fun getLong(key: String?, defValue: Long): Long = data[key] as? Long ?: defValue
    override fun getFloat(key: String?, defValue: Float): Float = data[key] as? Float ?: defValue
    override fun getBoolean(key: String?, defValue: Boolean): Boolean =
        data[key] as? Boolean ?: defValue
    override fun contains(key: String?): Boolean = data.containsKey(key)
    override fun edit(): SharedPreferences.Editor = FakeEditor()
    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener
    ) { listeners.add(listener) }
    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener
    ) { listeners.remove(listener) }

    private inner class FakeEditor : SharedPreferences.Editor {
        private val puts = HashMap<String, Any?>()
        private val removes = HashSet<String>()
        private var clearFlag = false
        override fun putString(key: String, value: String?) = apply { puts[key] = value }
        override fun putStringSet(key: String, values: Set<String>?) = apply { puts[key] = values }
        override fun putInt(key: String, value: Int) = apply { puts[key] = value }
        override fun putLong(key: String, value: Long) = apply { puts[key] = value }
        override fun putFloat(key: String, value: Float) = apply { puts[key] = value }
        override fun putBoolean(key: String, value: Boolean) = apply { puts[key] = value }
        override fun remove(key: String) = apply { removes.add(key) }
        override fun clear() = apply { clearFlag = true }
        override fun commit(): Boolean { apply(); return true }
        override fun apply() {
            if (clearFlag) data.clear()
            data.putAll(puts)
            removes.forEach { data.remove(it) }
        }
    }
}

/** A service with a switch of its own, for the run the user asks one
 *  service for ([TranslationBackendRegistry.translateWith]) and the list
 *  they pick it from: [enabled] is the switch, [setUp] whether it could
 *  translate if asked (its key saved, its model on the device), and
 *  [isUsable] is both, as on the real backends. It takes part in cooldowns
 *  through its own [cooldownState]; [reply] decides each text's call (a
 *  translation, a throw, a suspension). [requiresInternet] picks the online
 *  or the offline shape. Counters say what the registry called. */
internal open class FakeSwitchedBackend(
    final override val id: BackendId,
    final override val priority: Int,
    final override val displayName: String = "fake-switched-$id",
    final override val requiresInternet: Boolean = true,
    @Volatile var enabled: Boolean = true,
    @Volatile var setUp: Boolean = true,
    val cooldownState: CooldownState = CooldownState(context = null, backendId = id),
    private val reply: suspend (String) -> String = { "$id:$it" },
) : TranslationBackend, Cooldownable {
    override val status: BackendStatus = BackendStatus.Hidden
    override val isDegradedFallback: Boolean = false
    val translateCalls = AtomicInteger(0)
    val recordSuccessCalls = AtomicInteger(0)

    override fun isUsable(source: String, target: String): Boolean = enabled && isSetUpFor(source, target)
    override fun isSetUpFor(source: String, target: String): Boolean = setUp
    override suspend fun translate(text: String, source: String, target: String): String {
        translateCalls.incrementAndGet()
        return reply(text)
    }

    override fun unavailableUntil(): Long? = cooldownState.unavailableUntil()
    override fun unavailableDescription(): String? = cooldownState.unavailableDescription()
    override fun unavailableCause(): CooldownCause? = cooldownState.unavailableCause()
    override fun recordSuccess(attemptStartedAtMs: Long) {
        recordSuccessCalls.incrementAndGet()
        cooldownState.recordSuccess(attemptStartedAtMs)
    }
    override fun onConnectivityRestored(): Boolean = cooldownState.onConnectivityRestored()
    override fun resetCooldown() = cooldownState.resetCooldown()
}

/** [FakeSwitchedBackend] that also takes several texts in one call:
 *  [batchReply] decides the batch call, the per-text calls stay [reply]'s
 *  (the registry's retry after a batch reply it could not use). */
internal class FakeSwitchedBatchBackend(
    id: BackendId,
    priority: Int,
    displayName: String = "fake-switched-batch-$id",
    enabled: Boolean = true,
    setUp: Boolean = true,
    reply: suspend (String) -> String = { "$id:$it" },
    private val batchReply: suspend (List<String>) -> List<String> = { texts -> texts.map { "$id:$it" } },
) : FakeSwitchedBackend(
    id = id, priority = priority, displayName = displayName, enabled = enabled, setUp = setUp, reply = reply,
), BatchTranslator {
    val batchCalls = AtomicInteger(0)

    override suspend fun translateBatch(texts: List<String>, source: String, target: String): List<String> {
        batchCalls.incrementAndGet()
        return batchReply(texts)
    }
}
