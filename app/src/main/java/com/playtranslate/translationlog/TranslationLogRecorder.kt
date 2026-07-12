package com.playtranslate.translationlog

import android.content.Context
import android.graphics.Rect
import android.util.Log
import com.playtranslate.Prefs
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

private const val TAG = "TranslationLogRecorder"

/**
 * The shared recording backend for Text History and LLM context: one
 * session-scoped consumer of the "shown" translation stream (source +
 * translation + rect co-located at every tap seam), gated once by
 * [LogWriteGate], feeding two independent sinks:
 *
 *  - [TranslationHistoryStore] when [Prefs.translationHistoryEnabled] —
 *    durable, user-managed;
 *  - [ContextRing] when [Prefs.llmContextEnabled] — ephemeral pairs read
 *    by [com.playtranslate.translation.llm.LlmPromptTemplates.contextProvider].
 *
 * Passive by design (no thread, no run-gate): the pipeline seams call in.
 * All mutating entry points are MAIN-THREAD ONLY (every seam already runs
 * on Main), which keeps the gate and the row-id map lock-free — and it is
 * also the CLEAR-ORDERING INVARIANT: because every history write launches
 * from Main and the store runs one FIFO dispatcher, a Clear tapped after a
 * commit always lands after that commit's insert; no pre-clear write can
 * resurrect cleared history. A future non-Main caller would break that
 * guarantee and would need a write-generation barrier here;
 * [contextBlockFor] is the one cross-thread read and is safe via the
 * ring's snapshot semantics and live pref reads. Every entry point
 * swallows its own failures — a recorder bug must never reach a capture
 * loop (the LogTraceRecorder contract).
 *
 * Two different lifetimes, on purpose — don't "unify" them:
 *  - [sessionId] is a row-grouping LABEL, re-minted at construction, on
 *    [onLiveStarted], and on a language-pair change.
 *  - DEDUPE lifetime (gate seen/near-dup/region-mute state, tracked rows)
 *    is the recorder's lifetime within one language pair. Live stop/start
 *    deliberately does NOT reset it: a restart in the same scene re-OCRs
 *    the whole visible screen, and a reset would re-log every line as
 *    fresh and force muted UI regions to re-earn their mutes after every
 *    toggle. Dedupe resets ONLY on a pair change or an explicit history
 *    clear/delete. (Long-range dedupe is a deliberate improvement over
 *    LunaTranslator's consecutive-only and GSM's 2-second window.)
 * [onLiveStopped] clears the ring — the DB persists across sessions,
 * LLM context never does.
 */
class TranslationLogRecorder(
    private val appContext: Context,
    private val sink: HistorySink = StoreSink(appContext),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
) {

    /** Store operations the recorder needs — seam for tests. */
    interface HistorySink {
        suspend fun insert(
            atMs: Long, sourceText: String, translation: String?, sourceLang: String,
            targetLang: String, provenance: String, sessionId: String, normKey: String,
            rect: Rect?, backendDisplayName: String?,
        ): Long

        suspend fun update(rowId: Long, sourceText: String, translation: String?, atMs: Long, normKey: String)

        suspend fun attachByKey(
            normKey: String, translation: String,
            sourceLang: String, targetLang: String, backendDisplayName: String?,
        ): Int

        suspend fun attachById(rowId: Long, translation: String, backendDisplayName: String?)
    }

    private class StoreSink(private val ctx: Context) : HistorySink {
        override suspend fun insert(
            atMs: Long, sourceText: String, translation: String?, sourceLang: String,
            targetLang: String, provenance: String, sessionId: String, normKey: String,
            rect: Rect?, backendDisplayName: String?,
        ): Long = TranslationHistoryStore.insert(
            ctx, atMs, sourceText, translation, sourceLang, targetLang,
            provenance, sessionId, normKey, rect, backendDisplayName,
        )

        override suspend fun update(rowId: Long, sourceText: String, translation: String?, atMs: Long, normKey: String) =
            TranslationHistoryStore.update(ctx, rowId, sourceText, translation, atMs, normKey)

        override suspend fun attachByKey(
            normKey: String, translation: String,
            sourceLang: String, targetLang: String, backendDisplayName: String?,
        ): Int = TranslationHistoryStore.attachTranslationByKey(
            ctx, normKey, translation, sourceLang, targetLang, backendDisplayName,
        )

        override suspend fun attachById(rowId: Long, translation: String, backendDisplayName: String?) =
            TranslationHistoryStore.attachTranslationById(ctx, rowId, translation, backendDisplayName)
    }

    private val prefs = Prefs(appContext)
    private val ring = ContextRing()

    private var gate: LogWriteGate? = null

    /** The FULL language pair the current session state was built under.
     *  Pair identity is state identity: gate dedupe memory, tracked rows,
     *  ring pairs, and the session id are all meaningless across a pair
     *  change — source OR target (the target-blind version of this let a
     *  re-read line die as an old-target "duplicate" and a supersession
     *  update cross pairs). */
    private var gateSourceLang: String? = null
    private var gateTargetLang: String? = null

    @Volatile
    private var sessionId: String = UUID.randomUUID().toString()

    /** A tracked pending row: its (async) id plus the language pair it was
     *  RECORDED under — late attaches must match that pair or record fresh
     *  (a target-language switch mid-flight must never write a new-pair
     *  translation onto an old-pair row). */
    private class PendingRow(
        val id: CompletableDeferred<Long>,
        val sourceLang: String,
        val targetLang: String,
    )

    /** normKey → pending/known row for the gate's supersession window.
     *  Deferred ids, because inserts are async: a Replace awaits its
     *  Append's row id, and the store's single-thread dispatcher keeps the
     *  UPDATE strictly after the INSERT. Access-ordered, hard-capped. */
    private val rowIds = object : LinkedHashMap<String, PendingRow>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, PendingRow>?) =
            size > ROW_ID_CAP
    }

    /** AUTO-provenance stream (live modes): full gate policy. Main only. */
    fun onShown(
        source: String,
        translation: String,
        bounds: Rect,
        sourceLang: String,
        targetLang: String,
        backendDisplayName: String? = null,
    ) = guarded {
        if (source.isBlank()) return@guarded
        val historyOn = prefs.translationHistoryEnabled
        val contextOn = prefs.llmContextEnabled
        if (!historyOn && !contextOn) return@guarded
        val gate = ensureGate(sourceLang, targetLang)
        val now = System.currentTimeMillis()
        apply(
            gate.offer(source, bounds, now, cycle = 0),
            source, translation, bounds, sourceLang, targetLang,
            TranslationHistoryStore.PROVENANCE_AUTO, backendDisplayName, historyOn, contextOn, now,
        )
    }

    /** Deliberate-action stream (one-shot, dual-screen drag lookup):
     *  exact-dedupe only. Main only. */
    fun onShownDeliberate(
        source: String,
        translation: String?,
        bounds: Rect?,
        sourceLang: String,
        targetLang: String,
        provenance: String,
        backendDisplayName: String? = null,
    ) = guarded {
        if (source.isBlank()) return@guarded
        val historyOn = prefs.translationHistoryEnabled
        val contextOn = prefs.llmContextEnabled
        if (!historyOn && !contextOn) return@guarded
        val gate = ensureGate(sourceLang, targetLang)
        val now = System.currentTimeMillis()
        apply(
            gate.offerDeliberate(source, now, cycle = 0),
            source, translation, bounds, sourceLang, targetLang,
            provenance, backendDisplayName, historyOn, contextOn, now,
        )
    }

    /** The `{context}` block for [LlmPromptTemplates.contextProvider].
     *  Called from translation-backend threads — thread-safe. */
    fun contextBlockFor(sourceLang: String, targetLang: String): String {
        return try {
            if (!prefs.llmContextEnabled) ""
            else ring.block(sourceLang, targetLang, System.currentTimeMillis())
        } catch (e: Exception) {
            Log.w(TAG, "contextBlockFor failed: ${e.message}")
            ""
        }
    }

    /** New live session: fresh grouping label ONLY. Deliberately does not
     *  touch the gate/tracked rows — see the class doc's two-lifetimes
     *  contract before "fixing" that. */
    fun onLiveStarted() = guarded {
        sessionId = UUID.randomUUID().toString()
    }

    fun onLiveStopped() = guarded {
        // Session over: context must not leak into the next play session.
        ring.clear()
    }

    /** The user cleared History: reset every dedupe memory so lines can
     *  record again (the store is empty — nothing is a duplicate of it).
     *  The context ring is deliberately untouched: independent contract. */
    fun onHistoryCleared() = guarded { resetDedupeState() }

    /** History flipped ON mid-session: any seen-state accumulated during
     *  context-only use marked lines as duplicates while nothing was
     *  persisted — re-sightings must be recordable now. Ring untouched:
     *  context continuity is unaffected by the history switch (the
     *  independence contract cuts both ways). */
    fun onHistoryEnabled() = guarded { resetDedupeState() }

    private fun resetDedupeState() {
        gate = null
        gateSourceLang = null
        gateTargetLang = null
        rowIds.clear()
    }

    /** One entry deleted from History: its next sighting must record again. */
    fun onEntryDeleted(normKey: String) = guarded {
        gate?.forget(normKey)
        rowIds.remove(normKey)
    }

    /** A History row the user tapped got its translation on the results
     *  page: attach to EXACTLY that row (twin rows can share a normKey
     *  across sessions — key matching must never pick for the user). The
     *  caller guarantees the language pair matches the row's stored pair;
     *  cross-pair translations are display-only and never attach. */
    fun onHistoryEntryTranslated(
        rowId: Long,
        source: String,
        translation: String,
        sourceLang: String,
        targetLang: String,
        backendDisplayName: String? = null,
    ) = guarded {
        if (translation.isBlank()) return@guarded
        val historyOn = prefs.translationHistoryEnabled
        val contextOn = prefs.llmContextEnabled
        if (historyOn) {
            scope.launch {
                runCatching { sink.attachById(rowId, translation, backendDisplayName) }
                    .onFailure { Log.w(TAG, "attach-by-id failed: ${it.message}") }
            }
        }
        if (contextOn) {
            val key = LogWriteGate.normalizedKey(source, sourceLang)
            ring.push(
                ContextRing.ContextPair(
                    source, translation, System.currentTimeMillis(), key, sourceLang, targetLang,
                )
            )
        }
    }

    /** A translation arrived for a deliberate entry recorded earlier
     *  translation-less (drag lookups record at the lookup; the dual-screen
     *  flow translates later and only when MainActivity is foreground).
     *  Attaches in place when the entry's row is still tracked — going
     *  through the gate instead would exact-dedupe the pair against its
     *  own source-only entry. Falls back to a normal deliberate offer when
     *  the row is unknown (recorder recreated, map evicted). Main only. */
    fun onDeliberateTranslation(
        source: String,
        translation: String,
        sourceLang: String,
        targetLang: String,
        provenance: String,
        backendDisplayName: String? = null,
    ) = guarded {
        if (source.isBlank() || translation.isBlank()) return@guarded
        val historyOn = prefs.translationHistoryEnabled
        val contextOn = prefs.llmContextEnabled
        if (!historyOn && !contextOn) return@guarded
        val key = LogWriteGate.normalizedKey(source, sourceLang)
        val now = System.currentTimeMillis()
        // Pair equality is part of row identity: a tracked row recorded
        // under a different pair (target switched mid-flight) must NOT
        // receive this translation — fall through to the pair-constrained
        // store attach, which then records fresh under the new pair.
        val tracked = rowIds[key]?.takeIf {
            it.sourceLang == sourceLang && it.targetLang == targetLang
        }
        if (tracked != null) {
            if (historyOn) {
                scope.launch {
                    runCatching {
                        sink.update(tracked.id.await(), source, translation, now, key)
                    }.onFailure { Log.w(TAG, "translation attach failed: ${it.message}") }
                }
            }
            if (contextOn) {
                ring.push(ContextRing.ContextPair(source, translation, now, key, sourceLang, targetLang))
            }
            return@guarded
        }
        // Row not in the tracked window (older entry tapped from History,
        // recorder recreated since the lookup, or a pair mismatch above):
        // attach by key AND pair at the store — fills the newest matching
        // translation-less row — and only record fresh when none exists.
        // The ring push rides the same outcome so the fallback's own push
        // can't double-add the pair.
        if (historyOn) {
            val session = sessionId
            scope.launch {
                runCatching {
                    val affected = sink.attachByKey(key, translation, sourceLang, targetLang, backendDisplayName)
                    if (affected == 0) {
                        // Fresh record under THIS pair, inserted directly:
                        // the gate's seen map is pair-blind and would
                        // suppress a text already logged under another
                        // pair, but a new-pair record is semantically a
                        // new entry — and its dedupe already happened
                        // pair-correctly in attachByKey.
                        sink.insert(
                            now, source, translation, sourceLang, targetLang,
                            provenance, session, key, null, backendDisplayName,
                        )
                    }
                    if (contextOn) {
                        ring.push(ContextRing.ContextPair(source, translation, now, key, sourceLang, targetLang))
                    }
                }.onFailure { Log.w(TAG, "translation attach-by-key failed: ${it.message}") }
            }
        } else if (contextOn) {
            ring.push(ContextRing.ContextPair(source, translation, now, key, sourceLang, targetLang))
        }
    }

    private fun apply(
        decision: LogWriteGate.Decision,
        source: String,
        translation: String?,
        bounds: Rect?,
        sourceLang: String,
        targetLang: String,
        provenance: String,
        backendDisplayName: String?,
        historyOn: Boolean,
        contextOn: Boolean,
        now: Long,
    ) {
        when (decision) {
            is LogWriteGate.Decision.Append -> {
                if (historyOn) {
                    val deferred = CompletableDeferred<Long>()
                    rowIds[decision.entry.key] = PendingRow(deferred, sourceLang, targetLang)
                    val session = sessionId
                    scope.launch {
                        runCatching {
                            deferred.complete(
                                sink.insert(
                                    now, source, translation, sourceLang, targetLang,
                                    provenance, session, decision.entry.key, bounds, backendDisplayName,
                                )
                            )
                        }.onFailure {
                            deferred.completeExceptionally(it)
                            Log.w(TAG, "history insert failed: ${it.message}")
                        }
                    }
                }
                if (contextOn && !translation.isNullOrBlank()) {
                    ring.push(ContextRing.ContextPair(source, translation, now, decision.entry.key, sourceLang, targetLang))
                }
            }
            is LogWriteGate.Decision.Replace -> {
                if (historyOn) {
                    val prev = rowIds.remove(decision.previous.key)
                    if (prev != null) rowIds[decision.entry.key] = prev
                    scope.launch {
                        runCatching {
                            val rowId = prev?.id?.await()
                            if (rowId != null) {
                                sink.update(rowId, source, translation, now, decision.entry.key)
                            } else {
                                sink.insert(
                                    now, source, translation, sourceLang, targetLang,
                                    provenance, sessionId, decision.entry.key, bounds, backendDisplayName,
                                )
                            }
                        }.onFailure { Log.w(TAG, "history replace failed: ${it.message}") }
                    }
                }
                if (contextOn && !translation.isNullOrBlank()) {
                    ring.replaceByKey(
                        decision.previous.key,
                        ContextRing.ContextPair(source, translation, now, decision.entry.key, sourceLang, targetLang),
                    )
                }
            }
            is LogWriteGate.Decision.Suppress -> Unit
        }
    }

    /** Language-PAIR switch = new session: fresh gate state, cleared
     *  tracked rows, empty ring, new session id — cross-pair state must
     *  never dedupe, supersede, or contextualize across the switch (the
     *  LunaTranslator failure mode, and the pair-blind supersession bug). */
    private fun ensureGate(sourceLang: String, targetLang: String): LogWriteGate {
        val existing = gate
        if (existing != null && gateSourceLang == sourceLang && gateTargetLang == targetLang) {
            return existing
        }
        val fresh = LogWriteGate(sourceLang)
        gate = fresh
        gateSourceLang = sourceLang
        gateTargetLang = targetLang
        ring.clear()
        rowIds.clear()
        sessionId = UUID.randomUUID().toString()
        return fresh
    }

    private inline fun guarded(block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            // Never let a recorder failure reach a capture loop.
            Log.w(TAG, "recorder call failed: ${e.message}")
        }
    }

    private companion object {
        const val ROW_ID_CAP = 64
    }
}
