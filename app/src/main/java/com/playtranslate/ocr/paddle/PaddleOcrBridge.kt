package com.playtranslate.ocr.paddle

import android.content.Context
import android.util.Log
import com.playtranslate.language.PackIntegrity
import com.playtranslate.ocr.composites.DetectThenRecognize
import com.playtranslate.ocr.core.OcrEngine
import com.playtranslate.ocr.registry.OcrPackModelHelper
import java.io.File

/**
 * Owner of PaddleOCR's native MNN sessions (the lifecycle contract's "one
 * owner"). Builds a [DetectThenRecognize] (Paddle detector + recognizer) over a
 * session that pairs the **APK-bundled shared detector** with the selected
 * language's **recognizer pack**, caching engine+session by (recognizer pack
 * key, speed tier) — the same pack can be open at both tiers. Construction is
 * lazy + read-only; sessions close ONLY at the quiescent teardown via [close],
 * never on a selection switch.
 *
 * Detector: bundled `assets/ocr/paddle_det.mnn`, copied once to
 * `noBackupFilesDir/ocr/`. Recognizer pack (`noBackupFilesDir/models/<recPackKey>/`):
 * `rec.mnn` + `keys.txt`. Missing files → null → ML Kit floor.
 */
object PaddleOcrBridge {

    private const val TAG = "PaddleOcrBridge"
    private const val BUNDLED_DET_ASSET = "ocr/paddle_det.mnn"
    private const val BUNDLED_DET_SHA = "paddle_det.sha"

    /** Cache key: the same recognizer pack can be open at BOTH speed tiers. */
    private data class Key(val recPackKey: String, val fast: Boolean)

    private val sessions = HashMap<Key, PaddleOcrSession>()
    private val engines = HashMap<Key, OcrEngine>()
    @Volatile private var detFile: File? = null

    /** Cached engine for recognizer [recPackKey] at the [fast] or accurate tier,
     *  or null if det/rec files absent. */
    @Synchronized
    fun engine(ctx: Context, recPackKey: String, fast: Boolean = false): OcrEngine? {
        val key = Key(recPackKey, fast)
        engines[key]?.let { return it }
        val s = sessionFor(ctx, key) ?: return null
        return DetectThenRecognize(PaddleDetector(s), PaddleRecognizer(s)).also { engines[key] = it }
    }

    /** True if ANY tier of [recPackKey] holds a live session (sweep must not
     *  delete its files). */
    @Synchronized
    fun isLoaded(recPackKey: String): Boolean = sessions.keys.any { it.recPackKey == recPackKey }

    /** Close + drop the cached engines/sessions (both tiers) for a SINGLE
     *  [recPackKey], for the interactive pack delete (OcrModelManager.deleteOcrPack).
     *  No-op if none is held; unlike [close] it leaves every other pack's live
     *  sessions intact. */
    @Synchronized
    fun close(recPackKey: String) {
        for (fast in booleanArrayOf(false, true)) {
            val key = Key(recPackKey, fast)
            engines.remove(key)?.let { runCatching { it.close() } }
            sessions.remove(key)?.let { runCatching { it.close() } }
        }
    }

    private fun sessionFor(ctx: Context, key: Key): PaddleOcrSession? {
        sessions[key]?.let { return it }
        val det = bundledDetector(ctx) ?: return null
        val helper = OcrPackModelHelper(key.recPackKey)
        // Materialize a bundled recognizer (e.g. paddle-rec-unified) from APK assets
        // into the models dir on first use; no-op for downloaded packs.
        helper.ensureBundledMaterialized(ctx)
        val dir = helper.file(ctx)
        val rec = File(dir, "rec.mnn")
        val keys = File(dir, "keys.txt")
        if (!rec.exists() || !keys.exists()) {
            Log.w(TAG, "rec pack incomplete in ${dir.absolutePath} " +
                "(rec=${rec.exists()} keys=${keys.exists()}) — using ML Kit")
            return null
        }
        return try {
            // Fast tier = fp16 + 960px detector cap: ~3.5× faster end-to-end on a
            // 1080p frame, at the cost of small-text detection recall and
            // occasional edge-glyph reading changes (characterized by the
            // OcrAbHarnessTest fp16/detcap A/Bs, 2026-07-16). Accurate tier keeps
            // the fp32 / DET_LIMIT_SIDE production defaults.
            val session =
                if (key.fast) PaddleOcrSession.create(
                    det.absolutePath, rec.absolutePath, keys.absolutePath,
                    precision = 2, detLimitSide = 960,
                )
                else PaddleOcrSession.create(det.absolutePath, rec.absolutePath, keys.absolutePath)
            session.also { sessions[key] = it; Log.i(TAG, "Paddle session ready ($key)") }
        } catch (e: Throwable) {
            Log.e(TAG, "PaddleOcrSession.create failed ($key) — using ML Kit", e); null
        }
    }

    /** The bundled detector, copied from assets to a real file path (MNN loads from
     *  a path, not an asset stream). Null if the asset is missing or the copy fails.
     *  Runs under the object monitor (callers are @Synchronized), so the copy is
     *  single-flight. Existence is trustworthy because [copyBundledDetector] commits
     *  atomically — the final path only ever appears as a COMPLETE copy.
     *
     *  VERSION-AWARE: the copy lives in `noBackupFilesDir`, which survives app
     *  updates, so a plain copy-if-absent would keep a STALE detector forever after
     *  we ship a new one. Instead we re-copy whenever the bundled asset's SHA-256
     *  differs from the `.sha` sidecar we wrote alongside the last copy. Identity is
     *  derived from the asset itself (no manual version constant to forget to bump),
     *  and the compare is a plain mismatch (not version-greater) so a rolled-back
     *  app re-copies its older detector over a newer on-disk one. The ~5 MB asset is
     *  hashed once per process here (the [detFile] cache short-circuits thereafter),
     *  on the lazy first-OCR path — negligible. */
    private fun bundledDetector(ctx: Context): File? {
        detFile?.let { if (it.exists()) return it }
        val out = File(ctx.noBackupFilesDir, "ocr/paddle_det.mnn").apply { parentFile?.mkdirs() }
        val sidecar = File(out.parentFile, BUNDLED_DET_SHA)
        val assetSha = runCatching { PackIntegrity.sha256Hex(ctx.assets.open(BUNDLED_DET_ASSET)) }.getOrNull()
        val onDiskSha = if (out.exists()) runCatching { sidecar.readText().trim() }.getOrNull() else null
        // assetSha == null (asset unreadable) → fall back to copy-if-absent, never churn.
        val needCopy = if (assetSha != null) onDiskSha != assetSha else !out.exists()
        if (needCopy) {
            if (!copyBundledDetector(ctx, out)) return null
            // Sidecar written ONLY after the atomic replace: a crash in between leaves
            // a stale-or-missing sidecar (→ harmless re-copy next launch), never a
            // fresh sidecar over a stale detector.
            assetSha?.let { runCatching { sidecar.writeText(it) } }
        }
        detFile = out
        return out
    }

    /** Stream the bundled detector asset into a sibling `.tmp`, then atomic-rename
     *  onto [out] via [PackIntegrity.atomicReplace] — the same commit primitive
     *  every DOWNLOADED model file lands through. A process death / storage-full /
     *  mid-copy throw leaves only the `.tmp` (deleted here), so [out] never holds a
     *  truncated detector that the existence check would later trust and feed to a
     *  failing `PaddleOcrSession.create` — which would silently fall back to ML Kit
     *  with no repair path. Returns true iff [out] now holds the complete asset.
     *  No SHA/length check: it's a bundled asset (no transport corruption) and a
     *  non-throwing `copyTo` reads to EOF, so the atomic rename alone guarantees a
     *  complete file. */
    private fun copyBundledDetector(ctx: Context, out: File): Boolean {
        val tmp = File(out.parentFile, "paddle_det.mnn.tmp")
        return try {
            ctx.assets.open(BUNDLED_DET_ASSET).use { input -> tmp.outputStream().use { input.copyTo(it) } }
            PackIntegrity.atomicReplace(tmp, out)
            true
        } catch (e: Throwable) {
            tmp.delete()
            Log.e(TAG, "bundled $BUNDLED_DET_ASSET copy failed — using ML Kit", e)
            false
        }
    }

    @Synchronized
    fun close() {
        engines.values.forEach { runCatching { it.close() } }; engines.clear()
        sessions.values.forEach { runCatching { it.close() } }; sessions.clear()
    }
}
