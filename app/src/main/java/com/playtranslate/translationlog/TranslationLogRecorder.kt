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
 * on Main), which keeps the gate and the row-id map lock-free;
 * [contextBlockFor] is the one cross-thread read and is safe via the
 * ring's snapshot semantics and live pref reads. Every entry point
 * swallows its own failures — a recorder bug must never reach a capture
 * loop (the LogTraceRecorder contract).
 *
 * Sessions: a fresh [sessionId] is minted at construction, on
 * [onLiveStarted], and on a source-language change (which also recreates
 * the gate and clears the ring). [onLiveStopped] clears the ring — the
 * DB persists across sessions, context never does.
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
    }

    private val prefs = Prefs(appContext)
    private val ring = ContextRing()

    private var gate: LogWriteGate? = null
    private var gateSourceLang: String? = null

    @Volatile
    private var sessionId: String = UUID.randomUUID().toString()

    /** normKey → pending/known rowId for the gate's supersession window.
     *  Deferred, because inserts are async: a Replace awaits its Append's
     *  row id, and the store's single-thread dispatcher keeps the UPDATE
     *  strictly after the INSERT. Access-ordered, hard-capped. */
    private val rowIds = object : LinkedHashMap<String, CompletableDeferred<Long>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CompletableDeferred<Long>>?) =
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
        val gate = ensureGate(sourceLang)
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
        val gate = ensureGate(sourceLang)
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
    fun onHistoryCleared() = guarded {
        gate = null
        gateSourceLang = null
        rowIds.clear()
    }

    /** One entry deleted from History: its next sighting must record again. */
    fun onEntryDeleted(normKey: String) = guarded {
        gate?.forget(normKey)
        rowIds.remove(normKey)
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
        val tracked = rowIds[key]
        if (tracked == null) {
            onShownDeliberate(source, translation, null, sourceLang, targetLang, provenance, backendDisplayName)
            return@guarded
        }
        if (historyOn) {
            scope.launch {
                runCatching {
                    sink.update(tracked.await(), source, translation, now, key)
                }.onFailure { Log.w(TAG, "translation attach failed: ${it.message}") }
            }
        }
        if (contextOn) {
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
                    rowIds[decision.entry.key] = deferred
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
                    val prevDeferred = rowIds.remove(decision.previous.key)
                    if (prevDeferred != null) rowIds[decision.entry.key] = prevDeferred
                    scope.launch {
                        runCatching {
                            val rowId = prevDeferred?.await()
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

    /** Language switch = new session: fresh gate state, empty ring —
     *  cross-language pairs must never become context (the LunaTranslator
     *  failure mode). */
    private fun ensureGate(sourceLang: String): LogWriteGate {
        val existing = gate
        if (existing != null && gateSourceLang == sourceLang) return existing
        val fresh = LogWriteGate(sourceLang)
        gate = fresh
        gateSourceLang = sourceLang
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
