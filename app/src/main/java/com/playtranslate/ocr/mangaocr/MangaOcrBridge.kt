package com.playtranslate.ocr.mangaocr

import android.util.Log
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * Sole owner of the manga-ocr native session: the lazily-built [MangaOcrSession]
 * (encoder + decoder + vocab), the [Mutex] that serializes access to it, AND its
 * teardown. [modelDir] is pushed by [MangaOcrProvisioning]; missing model files →
 * the session never builds → callers fall back to the base engine (never crashes).
 *
 * **The session reference never escapes the lock.** Use is ONLY via [withRecognizer],
 * which holds [lock] for the whole scope, so a close can't interleave between acquiring
 * the session and decoding on it — the lifecycle race is structurally impossible rather
 * than coordinated by caller discipline. [MangaOcrSession] is non-thread-safe, so the
 * same lock also serializes overlapping frames (a live capture vs a drag-lookup).
 *
 * Model files: `encoder.mnn`, `decoder.mnn`, `vocab.txt` under [modelDir].
 */
object MangaOcrBridge {

    private const val TAG = "MangaOcrBridge"

    /** Pushed by [MangaOcrProvisioning.refresh] (app start / toggle / download / delete). */
    @Volatile var modelDir: File? = null

    /** Guards both session USE ([withRecognizer]) and interactive teardown ([close]). */
    private val lock = Mutex()

    // @Volatile for visibility of [closeForTrim]'s lock-free writes (the quiescent path).
    @Volatile private var session: MangaOcrSession? = null
    @Volatile private var triedInit = false

    /**
     * Run [block] with a [MangaOcrRecognizer] over the shared session, holding [lock] for
     * the whole scope. The block gets the concrete recognizer (not the [com.playtranslate
     * .ocr.core.TextRecognizer] interface) so the refiner can drive the budgeted decode
     * overload. Returns null (block NOT run) when the model isn't loadable — the caller's
     * base result then stands. The recognizer's per-frame bitmap→BGR cache is released on
     * exit; the session is reused across frames (closed only by [close] / [closeForTrim]).
     */
    suspend fun <T> withRecognizer(block: suspend (MangaOcrRecognizer) -> T): T? =
        lock.withLock {
            val s = sessionOrNull() ?: return@withLock null
            val recognizer = MangaOcrRecognizer(s)
            try {
                block(recognizer)
            } finally {
                recognizer.close()
            }
        }

    /**
     * Interactive teardown (Settings delete): takes [lock], so it waits out any in-flight
     * [withRecognizer] and can never close the session mid-decode. Suspends — call it
     * from a coroutine.
     */
    suspend fun close() = lock.withLock { closeLocked() }

    /**
     * Lock-FREE teardown for [com.playtranslate.OcrManager.releaseAll] at
     * TRIM_MEMORY_COMPLETE ONLY — that signal guarantees no foreground service and so no
     * in-flight OCR (the same quiescence the registry engines rely on). Never call it off
     * that path.
     */
    fun closeForTrim() = closeLocked()

    /** Re-arm lazy session init after a (re)provision gesture (toggle-on, download, app
     *  start) so a prior transient [MangaOcrSession.create] failure — or a not-yet-present
     *  model — doesn't stay latched for the rest of the process (the latch otherwise only
     *  clears on [close]/[closeForTrim], i.e. delete or TRIM). Clears only the "already
     *  tried" flag; a live session is untouched, since [sessionOrNull] returns it before
     *  the latch check. Safe off-lock: [triedInit] is @Volatile and a race with an
     *  in-flight [sessionOrNull] is benign (at worst one extra init attempt). */
    fun rearmInit() { triedInit = false }

    private fun closeLocked() {
        session?.close(); session = null; triedInit = false
    }

    /** Caller holds [lock] (or is the quiescent TRIM path). Lazily builds the session and
     *  latches [triedInit] so a missing/broken model isn't retried every frame. */
    private fun sessionOrNull(): MangaOcrSession? {
        session?.let { return it }
        if (triedInit) return null
        triedInit = true
        val dir = modelDir ?: run { Log.w(TAG, "no modelDir pushed"); return null }
        val enc = File(dir, "encoder.mnn")
        val dec = File(dir, "decoder.mnn")
        val vocab = File(dir, "vocab.txt")
        if (!enc.exists() || !dec.exists() || !vocab.exists()) {
            Log.w(TAG, "models missing in ${dir.absolutePath} " +
                "(enc=${enc.exists()} dec=${dec.exists()} vocab=${vocab.exists()}) — using ML Kit")
            return null
        }
        return try {
            MangaOcrSession.create(enc.absolutePath, dec.absolutePath, vocab.absolutePath)
                .also { session = it; Log.i(TAG, "manga-ocr session ready") }
        } catch (e: Throwable) {
            Log.e(TAG, "MangaOcrSession.create failed — using ML Kit", e)
            null
        }
    }
}
