package com.playtranslate.yomitan

import android.content.Context
import android.net.Uri
import android.util.Log
import com.google.gson.JsonParser
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.playtranslate.PtJson
import com.playtranslate.language.PackIntegrity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import kotlin.coroutines.coroutineContext

/**
 * Data categories a Yomitan dictionary can contribute, in fixed render
 * order. One imported zip can span several (e.g. Jitendex ships terms AND
 * kanji data), so a dictionary lists every category it matched and the
 * settings page shows it once per matching section.
 */
@Serializable
enum class YomitanCategory {
    TERMS,            // term_bank_N.json with ≥1 entry
    KANJI,            // kanji_bank_N.json with ≥1 entry
    FREQUENCY,        // term_meta_bank_N.json entry with mode "freq"
    KANJI_FREQUENCY,  // kanji_meta_bank_N.json with ≥1 entry
    PITCH_ACCENT,     // term_meta_bank_N.json entry with mode "pitch"
    PRONUNCIATION,    // term_meta_bank_N.json entry with mode "ipa"
}

/** One imported dictionary. [id] is the first 16 hex chars of the zip's
 *  SHA-256 — stable across re-imports of identical content. */
@Serializable
data class YomitanDictionary(
    val id: String,
    val title: String,
    val revision: String? = null,
    val description: String? = null,
    val author: String? = null,
    val format: Int,
    val categories: List<YomitanCategory>,
    val sizeBytes: Long,
    val importedAtMs: Long,
    /** index.json sourceLanguage/targetLanguage (RFC 5646), when declared.
     *  Null on older registry entries and dicts that omit them. */
    val sourceLanguage: String? = null,
    val targetLanguage: String? = null,
    /** User-set display name override (Yomitan's per-dictionary "alias"
     *  concept). Stand-in field — no UI sets it yet; consumers render
     *  `alias ?: title`. */
    val alias: String? = null,
    /** index.json frequencyMode ("rank-based"/"occurrence-based"), when
     *  declared. Persisted for future ranking use; display ignores it. */
    val frequencyMode: String? = null,
    /** Per-dictionary accent override (ARGB) for the rounded background of this
     *  dictionary's text chips; null = the default (subtitle text color).
     *  User-set on the dictionary detail page. */
    val accentColor: Int? = null,
    /** Yomitan self-update metadata from index.json: whether the deck opts into
     *  updates, the URL of its canonical index.json (to check `revision`), and
     *  the URL of the latest .zip (to fetch). False/null on decks that omit them
     *  and on registry entries imported before these fields existed (populated
     *  for the latter by [YomitanDictionaryStore.backfillUpdateMetadata]). */
    val isUpdatable: Boolean = false,
    val indexUrl: String? = null,
    val downloadUrl: String? = null,
    /** Per-dictionary auto-update opt-out (default ON). Flipped on the detail
     *  page; read only by the auto-updater, never by a capability cache. */
    val autoUpdate: Boolean = true,
)

/**
 * Whether this dictionary's data applies to source language [lang]. A DECLARED
 * index.json sourceLanguage matches [lang] by primary subtag, case-insensitively
 * (so "ja-JP" applies to "ja", and a caller may pass "zh-Hant" — both reduce to
 * "zh"). An UNDECLARED sourceLanguage is a WILDCARD that applies to EVERY source
 * language: a deck that omits the field (e.g. a Chinese→English one) is consulted
 * whatever the app's current source language is. A wrong-language lookup simply
 * finds nothing — its headwords won't match the input's script/lemmas — so this
 * surfaces results when the language lines up and is harmlessly silent otherwise,
 * rather than hiding the dictionary outright or mis-bucketing it as Japanese.
 */
fun YomitanDictionary.matchesSourceLanguage(lang: String): Boolean {
    val declared = sourceLanguage?.split('-', '_')?.first() ?: return true
    return declared.equals(lang.split('-', '_').first(), ignoreCase = true)
}

/**
 * On-disk registry of imported dictionaries. [sectionOrder] holds an
 * independent priority order per category ([YomitanCategory.name] → ordered
 * ids) — reordering a dictionary in one section must not move it in the
 * others (a package might be the preferred term dictionary but deprioritized
 * for pitch accent).
 */
@Serializable
data class YomitanRegistry(
    val dictionaries: List<YomitanDictionary> = emptyList(),
    val sectionOrder: Map<String, List<String>> = emptyMap(),
    /** TERMS-section toggle: definitions come from only the highest-priority
     *  dictionary that has results, instead of every dictionary. Absent in
     *  older registries → Gson leaves the primitive false, the default. */
    val termsSingleDictionary: Boolean = false,
) {
    /** Dictionaries belonging to [category], in that section's stored order. */
    fun orderedFor(category: YomitanCategory): List<YomitanDictionary> {
        val byId = dictionaries.associateBy { it.id }
        val inCategory = dictionaries.filter { category in it.categories }
        val ordered = (sectionOrder[category.name] ?: emptyList()).mapNotNull { byId[it] }
            .filter { category in it.categories }
        // Defensive: anything in the category but missing from the order list
        // (e.g. registry hand-edited) renders at the end instead of vanishing.
        return ordered + inCategory.filterNot { it in ordered }
    }
}

/** Outcome of [YomitanDictionaryStore.import]. */
sealed class YomitanImportResult {
    data class Success(val dictionary: YomitanDictionary) : YomitanImportResult()

    /** A dictionary with the same index.json title is already imported. */
    data class Duplicate(val title: String) : YomitanImportResult()

    /** Not a zip / no valid index.json / malformed bank / no dictionary data.
     *  [reason] is a short developer-facing diagnostic (e.g. which bank file
     *  and entry failed) — shown as a detail line on the debug-only page so
     *  dictionary authors aren't left with a dead-end "invalid file". */
    data class InvalidFormat(val reason: String?) : YomitanImportResult()

    /** The file is fine but the device lacks room for it — distinct from
     *  [InvalidFormat] so the alert can say "free up space" instead of
     *  "bad file". */
    data class InsufficientSpace(
        val requiredBytes: Long,
        val availableBytes: Long,
    ) : YomitanImportResult()

    /** Copy or disk failure unrelated to the file's contents. */
    object IoError : YomitanImportResult()

    /** A replacement (auto-update) was deliberately NOT applied — the target
     *  deck was deleted or opted out of auto-update during the update. Not an
     *  error; the auto-updater logs it at info. Never produced by manual import. */
    data class Skipped(val reason: String) : YomitanImportResult()
}

/**
 * Storage + registry for user-imported Yomitan dictionary zips.
 *
 * Layout mirrors [com.playtranslate.language.LanguagePackStore]:
 * `noBackupFilesDir/yomitan/<id>/dict.zip` per dictionary, with a single
 * `registry.json` alongside. The original zip is kept whole — later stages
 * ingest from it; nothing is extracted at import time.
 *
 * Import validation is structural, not schema-level: index.json must carry a
 * title and a known format, and every `*_bank_*.json` must be a well-formed
 * JSON array of arrays (streamed — term banks can run 100 MB+). That is what
 * separates "any zip" from "a Yomitan dictionary" without committing to the
 * full term-bank schema before the ingest stage exists.
 */
object YomitanDictionaryStore {

    private const val TAG = "YomitanStore"
    private const val ZIP_NAME = "dict.zip"

    /** index.json is dictionary metadata — realistically a few KB. We read it
     *  whole (bank files are streamed), so cap that one read: an oversized or
     *  zip-bombed index.json is rejected as InvalidFormat instead of OOMing
     *  the process during validation. */
    private const val MAX_INDEX_JSON_BYTES = 256 * 1024

    /** Serializes registry mutations (import / delete / reorder). */
    private val mutex = Mutex()

    fun rootDir(ctx: Context): File =
        File(ctx.applicationContext.noBackupFilesDir, "yomitan")

    private fun registryFile(ctx: Context): File = File(rootDir(ctx), "registry.json")

    private fun dictionaryDir(ctx: Context, id: String): File = File(rootDir(ctx), id)

    /** The stored zip for [id] — later ingest stages read from this. */
    fun zipFile(ctx: Context, id: String): File = File(dictionaryDir(ctx, id), ZIP_NAME)

    /**
     * Every top-level field of the stored dictionary's index.json, in file
     * order, as (key, displayValue) pairs — for the read-only metadata detail
     * view. Reads the raw index.json (not the parsed registry entry) so it
     * surfaces fields we don't model (attribution, url, …). Returns null when
     * the zip or its index.json can't be read; non-scalar values render as
     * compact JSON, JSON nulls as an em dash.
     */
    suspend fun readIndexJson(ctx: Context, id: String): List<Pair<String, String>>? =
        withContext(Dispatchers.IO) {
            val zip = zipFile(ctx, id)
            if (!zip.exists()) return@withContext null
            try {
                ZipFile(zip).use { z ->
                    val entry = z.getEntry("index.json") ?: return@withContext null
                    val text = z.getInputStream(entry).bufferedReader().use { it.readText() }
                    JsonParser.parseString(text).asJsonObject.entrySet().map { (key, value) ->
                        key to when {
                            value.isJsonNull -> "—"
                            value.isJsonPrimitive -> value.asString
                            else -> value.toString()
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "readIndexJson failed for $id", e)
                null
            }
        }

    // ── Registry IO ─────────────────────────────────────────────────────

    suspend fun load(ctx: Context): YomitanRegistry = withContext(Dispatchers.IO) {
        // Display-only fallback: a corrupt registry renders as empty, but the
        // mutation paths below refuse to write through it, so the on-disk
        // state (and every imported zip) survives for recovery.
        readRegistry(ctx) ?: YomitanRegistry()
    }

    /** Returns the registry, an empty one when no file exists yet, or null
     *  when the file exists but can't be read/parsed. Mutation paths MUST
     *  abort on null — writing back the empty fallback would silently orphan
     *  every imported dictionary. */
    private fun readRegistry(ctx: Context): YomitanRegistry? = try {
        val file = registryFile(ctx)
        if (!file.exists()) YomitanRegistry()
        else PtJson.lenient.decodeFromString<YomitanRegistry>(file.readText())
    } catch (e: Exception) {
        Log.w(TAG, "registry read failed — refusing to treat as empty", e)
        null
    }

    /** Write-temp-then-rename so a crash mid-write can't corrupt the registry. */
    private fun writeRegistry(ctx: Context, registry: YomitanRegistry) {
        val file = registryFile(ctx)
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "registry.json.tmp")
        tmp.writeText(PtJson.pretty.encodeToString(registry))
        PackIntegrity.atomicReplace(tmp, file)
    }

    // ── Import ──────────────────────────────────────────────────────────

    /**
     * Copies [uri] into app storage, validates it as a Yomitan dictionary,
     * and registers it. Cancellable throughout ([kotlinx.coroutines.CancellationException]
     * propagates; the temp file is cleaned up either way).
     */
    suspend fun import(ctx: Context, uri: Uri): YomitanImportResult = withContext(Dispatchers.IO) {
        val temp = File.createTempFile("yomitan_import", ".zip", ctx.cacheDir)
        try {
            try {
                ctx.contentResolver.openInputStream(uri)?.use { input ->
                    temp.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            coroutineContext.ensureActive()
                            val n = input.read(buf)
                            if (n <= 0) break
                            out.write(buf, 0, n)
                        }
                    }
                } ?: return@withContext YomitanImportResult.IoError
            } catch (e: CancellationException) {
                throw e // user cancel must not surface as a failed-import alert
            } catch (e: Exception) {
                Log.w(TAG, "copy from SAF failed", e)
                return@withContext YomitanImportResult.IoError
            }
            installZip(ctx, temp, replacing = null)
        } finally {
            temp.delete()
        }
    }

    /**
     * Re-imports [newZip] as a replacement for an already-installed [old] deck
     * (Yomitan auto-update). Preserves the user's alias, accent color, and
     * auto-update opt-out, swaps the registry entry atomically, and persists
     * [remoteRevision] as the installed revision so the next update check
     * converges instead of re-downloading forever (the standalone indexUrl
     * revision and the zip's bundled revision can differ). The caller owns
     * [newZip]'s lifecycle. [YomitanImportResult.Duplicate] is impossible here —
     * the title-collision check is skipped for replacements.
     */
    suspend fun applyUpdate(
        ctx: Context,
        old: YomitanDictionary,
        newZip: File,
        remoteRevision: String?,
    ): YomitanImportResult = installZip(ctx, newZip, replacing = old, revisionOverride = remoteRevision)

    /**
     * Validates [temp] as a Yomitan dictionary and registers it. When
     * [replacing] is null this is a fresh import (the duplicate-title check
     * applies, new dicts append at the end of each section). When [replacing] is
     * non-null this is an update: the duplicate-title check is skipped, the
     * user's alias/accent/autoUpdate carry over, [revisionOverride] (if given)
     * becomes the stored revision, and the old entry + files are swapped out
     * atomically — the new content-derived id takes the old id's priority slot.
     * The caller owns [temp]'s lifecycle (a successful install MOVES it into
     * place; failures leave it for the caller's finally to delete).
     */
    private suspend fun installZip(
        ctx: Context,
        temp: File,
        replacing: YomitanDictionary?,
        revisionOverride: String? = null,
    ): YomitanImportResult = withContext(Dispatchers.IO) {
        // Disk guard: the zip is kept whole (1×) and the derived term rows from
        // a flattened glossary can exceed the compressed source — 3× leaves
        // headroom. The local copy already landed, so this checks what's left.
        val required = temp.length() * 3
        val available = rootDir(ctx).apply { mkdirs() }.usableSpace
        if (available < required) {
            return@withContext YomitanImportResult.InsufficientSpace(
                requiredBytes = required,
                availableBytes = available,
            )
        }

        val sha256 = PackIntegrity.sha256Hex(temp)
        val id = sha256.take(16)

        val parsed = try {
            ZipFile(temp).use { zip -> parseAndValidate(zip) }
        } catch (e: CancellationException) {
            throw e // validation runs ensureActive — don't fold cancel into InvalidFormat
        } catch (e: InvalidDictionaryException) {
            Log.w(TAG, "invalid dictionary: ${e.message}", e.cause)
            return@withContext YomitanImportResult.InvalidFormat(e.message)
        } catch (e: Exception) {
            Log.w(TAG, "not a readable zip", e)
            return@withContext YomitanImportResult.InvalidFormat("Not a readable zip file")
        }

        if (replacing == null) {
            commitFreshInstall(ctx, temp, id, parsed)
        } else {
            commitReplacement(ctx, temp, id, parsed, replacing, revisionOverride)
        }
    }

    /** Fresh import: refuse a same-title duplicate, stage the zip, append the new
     *  id at the end of each of its sections, then ingest. Ingest failure is
     *  swallowed + retried by reconcile — there is no prior deck to lose here,
     *  unlike [commitReplacement]. */
    private suspend fun commitFreshInstall(
        ctx: Context,
        temp: File,
        id: String,
        parsed: ParsedDictionary,
    ): YomitanImportResult {
        val dictionary = mutex.withLock {
            val registry = readRegistry(ctx) ?: return YomitanImportResult.IoError
            registry.dictionaries.firstOrNull { it.title == parsed.title }?.let {
                return YomitanImportResult.Duplicate(it.title)
            }
            val dictionary = newDictionary(id, parsed, temp.length(), carryFrom = null, revisionOverride = null)
            try {
                dictionaryDir(ctx, id).mkdirs()
                PackIntegrity.atomicReplace(temp, zipFile(ctx, id))
                writeRegistry(ctx, buildRegistryAfterInstall(registry, dictionary, replacing = null))
            } catch (e: Exception) {
                Log.w(TAG, "install failed", e)
                dictionaryDir(ctx, id).deleteRecursively()
                return YomitanImportResult.IoError
            }
            dictionary
        }
        YomitanDataStore.onDictImported(ctx, dictionary)
        return YomitanImportResult.Success(dictionary)
    }

    /** Post-lock outcome of [commitReplacement]'s registry decision, so the
     *  suspend cleanup/purge runs AFTER the registry lock releases — symmetric
     *  across abort + success, and the lock does pure (non-suspend) registry IO. */
    private sealed interface ReplaceCommit {
        /** Not applied; clean up the staged new id ([stagedId], null when nothing
         *  was staged) after the lock, then return [result]. */
        data class Aborted(val result: YomitanImportResult, val stagedId: String?) : ReplaceCommit
        /** Applied as a same-bytes metadata refresh; no old deck to purge. */
        data class Refreshed(val dictionary: YomitanDictionary) : ReplaceCommit
        /** Applied; purge the replaced [oldId] after the lock to make the new
         *  deck live. */
        data class Swapped(val dictionary: YomitanDictionary, val oldId: String) : ReplaceCommit
    }

    /**
     * Auto-update replacement, structured to NEVER lose the working deck:
     *  1. stage the new zip beside the old (different content ⇒ different id ⇒
     *     different dir, so the old deck stays intact);
     *  2. PROVE the new deck ingests into the shared DB before discarding the old
     *     — the Yomitan analog of the language-pack validate-before-swap (the
     *     code differs: packs swap a directory, here the proof is a successful
     *     [YomitanDataStore.tryIngest]). On failure the old deck is untouched;
     *  3. commit atomically against the CURRENT registry — the scan object is
     *     stale (the deck may have been deleted, opted out, or re-edited during
     *     the download + ingest), so re-resolve and abort rather than resurrect
     *     a deleted deck or clobber newer user state; then purge the old deck,
     *     which also makes the proven-good new deck live by clearing the caches.
     */
    private suspend fun commitReplacement(
        ctx: Context,
        temp: File,
        id: String,
        parsed: ParsedDictionary,
        replacing: YomitanDictionary,
        revisionOverride: String?,
    ): YomitanImportResult {
        val candidate = newDictionary(id, parsed, temp.length(), carryFrom = null, revisionOverride = revisionOverride)
        // Identical bytes ⇒ same id as the deck being replaced: no new rows to
        // prove and no swap — handled as a metadata-only refresh in the commit
        // section below. Otherwise stage + prove the new id first.
        val sameContent = id == replacing.id
        if (!sameContent) {
            // Identity guard (pure, no IO): a new revision must still be the SAME
            // dictionary. If the update URL resolved to a different deck (author
            // misconfiguration), skip and keep the installed deck rather than
            // silently swap unrelated content in under its alias + priority slot.
            if (!isSameDictionaryIdentity(
                    parsed.title, parsed.sourceLanguage, parsed.targetLanguage,
                    replacing.title, replacing.sourceLanguage, replacing.targetLanguage,
                )
            ) {
                Log.w(
                    TAG,
                    "update for ${replacing.id}: replacement is a different dictionary " +
                        "(title/language mismatch); skipping",
                )
                return YomitanImportResult.Skipped("update content is a different dictionary")
            }
            // Collision guard BEFORE touching disk/DB. The id is content-derived,
            // and BOTH dictionaryDir(id) and the ingested rows key on it. Here
            // id != replacing.id, so id already being registered means the
            // update's bytes are identical to a DIFFERENT installed deck — staging
            // would overwrite that deck's zip and re-ingest-then-purge its rows
            // under the shared id, and the abort path below would delete its dir.
            // Astronomically rare (a sha256-prefix match), but corruption-grade,
            // so skip the update rather than clobber an unrelated deck.
            val collidesWithOtherDeck = mutex.withLock {
                readRegistry(ctx)?.dictionaries?.any { it.id == id } == true
            }
            if (collidesWithOtherDeck) {
                Log.w(TAG, "update for ${replacing.id}: new content id $id already belongs to another installed deck; skipping")
                return YomitanImportResult.Skipped("update content matches another installed dictionary")
            }
            try {
                dictionaryDir(ctx, id).mkdirs()
                PackIntegrity.atomicReplace(temp, zipFile(ctx, id))
            } catch (e: Exception) {
                Log.w(TAG, "update: staging new zip failed for ${replacing.id}", e)
                dictionaryDir(ctx, id).deleteRecursively()
                return YomitanImportResult.IoError
            }
            // Prove-before-swap. tryIngest is transactional, so a failure rolls
            // back cleanly (no rows, not marked ingested); drop the staged zip
            // and leave the old deck fully intact and queryable.
            if (!YomitanDataStore.tryIngest(ctx, candidate)) {
                dictionaryDir(ctx, id).deleteRecursively()
                return YomitanImportResult.IoError
            }
        }

        // The locked block makes ONLY the registry decision (pure, non-suspend
        // IO); the suspend DB purge/cleanup runs after the lock releases.
        val commit = mutex.withLock {
            val registry = readRegistry(ctx)
                ?: return@withLock ReplaceCommit.Aborted(
                    YomitanImportResult.IoError, stagedId = if (sameContent) null else id,
                )
            // Re-resolve against the CURRENT registry — never trust the stale
            // scan object for the swap decision or the carried-over user state.
            val current = registry.dictionaries.firstOrNull { it.id == replacing.id }
            if (current == null || !current.autoUpdate) {
                // Deleted or opted out during the update: don't resurrect or
                // override. The old deck is untouched; the staged new id is
                // dropped after the lock.
                return@withLock ReplaceCommit.Aborted(
                    YomitanImportResult.Skipped(
                        if (current == null) "replaced dictionary removed during update"
                        else "auto-update disabled during update",
                    ),
                    stagedId = if (sameContent) null else id,
                )
            }
            val committed = candidate.copy(
                alias = current.alias,
                accentColor = current.accentColor,
                autoUpdate = current.autoUpdate,
            )
            if (sameContent) {
                // Metadata-only refresh in place (revision/urls); rows unchanged.
                writeRegistry(
                    ctx,
                    registry.copy(
                        dictionaries = registry.dictionaries.map { if (it.id == id) committed else it },
                    ),
                )
                return@withLock ReplaceCommit.Refreshed(committed)
            }
            writeRegistry(ctx, buildRegistryAfterInstall(registry, committed, replacing = current))
            ReplaceCommit.Swapped(committed, current.id)
        }

        return when (commit) {
            is ReplaceCommit.Aborted -> {
                commit.stagedId?.let { staged ->
                    dictionaryDir(ctx, staged).deleteRecursively()
                    YomitanDataStore.onDictDeleted(ctx, staged) // purge rows tryIngest added
                }
                commit.result
            }
            is ReplaceCommit.Refreshed -> YomitanImportResult.Success(commit.dictionary)
            is ReplaceCommit.Swapped -> {
                // Old id is out of the registry now; purge its rows + delete its
                // dir. onDictDeleted clears the caches, making the proven-good
                // new deck live.
                dictionaryDir(ctx, commit.oldId).deleteRecursively()
                YomitanDataStore.onDictDeleted(ctx, commit.oldId)
                YomitanImportResult.Success(commit.dictionary)
            }
        }
    }

    /** Builds a [YomitanDictionary] from a validated [parsed], carrying the
     *  user-set alias/accent/autoUpdate from [carryFrom] (the CURRENT registry
     *  entry on an update; null on a fresh import → defaults). [revisionOverride]
     *  wins over the zip's bundled revision (update convergence). */
    private fun newDictionary(
        id: String,
        parsed: ParsedDictionary,
        sizeBytes: Long,
        carryFrom: YomitanDictionary?,
        revisionOverride: String?,
    ) = YomitanDictionary(
        id = id,
        title = parsed.title,
        revision = revisionOverride ?: parsed.revision,
        description = parsed.description,
        author = parsed.author,
        format = parsed.format,
        categories = parsed.categories,
        sizeBytes = sizeBytes,
        importedAtMs = System.currentTimeMillis(),
        sourceLanguage = parsed.sourceLanguage,
        targetLanguage = parsed.targetLanguage,
        frequencyMode = parsed.frequencyMode,
        isUpdatable = parsed.isUpdatable,
        indexUrl = parsed.indexUrl,
        downloadUrl = parsed.downloadUrl,
        alias = carryFrom?.alias,
        accentColor = carryFrom?.accentColor,
        autoUpdate = carryFrom?.autoUpdate ?: true,
    )

    /**
     * Identity invariant for an auto-update REPLACEMENT: a new revision must
     * still be the SAME dictionary. The title (trimmed, case-insensitive) must
     * match, and any DECLARED source/target language must agree on its primary
     * subtag. A null/blank language on either side is tolerated — adding or
     * dropping language metadata across a revision is not a different dictionary.
     *
     * This catches an `indexUrl`/`downloadUrl` that resolves to a DIFFERENT deck
     * (author misconfiguration) and keeps the installed deck rather than swap in
     * unrelated content under the user's alias + priority slot. It is deliberately
     * NOT an anti-tampering control: a hostile endpoint controls every field in
     * its own zip, so it can always make these match. The accepted trust boundary
     * is "you trusted the author at import time" — the same model as Yomitan.
     */
    internal fun isSameDictionaryIdentity(
        newTitle: String,
        newSource: String?,
        newTarget: String?,
        installedTitle: String,
        installedSource: String?,
        installedTarget: String?,
    ): Boolean {
        if (!newTitle.trim().equals(installedTitle.trim(), ignoreCase = true)) return false
        return languageCompatible(newSource, installedSource) &&
            languageCompatible(newTarget, installedTarget)
    }

    /** True unless BOTH sides declare a language and their primary subtags differ. */
    private fun languageCompatible(a: String?, b: String?): Boolean {
        val pa = primarySubtag(a)
        val pb = primarySubtag(b)
        return pa == null || pb == null || pa == pb
    }

    private fun primarySubtag(lang: String?): String? =
        lang?.trim()?.lowercase()?.takeUnless { it.isEmpty() }
            ?.substringBefore('-')?.substringBefore('_')

    /**
     * Registry after installing [dictionary]. Fresh import ([replacing] null):
     * appends the new id at the end of each of its sections (lowest priority),
     * de-duping any stale occurrence — the original import behavior. Update
     * ([replacing] non-null): swaps the old id → new id IN PLACE in each section
     * (preserving the user's priority slot), drops the old id from sections the
     * update no longer matches, de-dupes any stale new id, and appends to any
     * section the update newly gained. `copy()` so untouched registry fields
     * (e.g. termsSingleDictionary) survive.
     */
    internal fun buildRegistryAfterInstall(
        registry: YomitanRegistry,
        dictionary: YomitanDictionary,
        replacing: YomitanDictionary?,
    ): YomitanRegistry {
        val newId = dictionary.id
        val oldId = replacing?.id
        val newCats = dictionary.categories.mapTo(mutableSetOf()) { it.name }

        val dictionaries = if (oldId != null) {
            val mapped = registry.dictionaries.map { if (it.id == oldId) dictionary else it }
            if (mapped.any { it.id == newId }) mapped else mapped + dictionary
        } else {
            registry.dictionaries + dictionary
        }

        val sectionOrder = registry.sectionOrder.toMutableMap()
        if (oldId != null) {
            for ((cat, ids) in registry.sectionOrder) {
                sectionOrder[cat] = ids.flatMap { existing ->
                    when {
                        existing == oldId && cat in newCats -> listOf(newId) // swap in place
                        existing == oldId -> emptyList()                     // section dropped
                        existing == newId -> emptyList()                     // de-dupe stale new id
                        else -> listOf(existing)
                    }
                }
            }
        }
        for (cat in dictionary.categories) {
            val list = sectionOrder[cat.name] ?: emptyList()
            sectionOrder[cat.name] = when {
                oldId == null -> list.filterNot { it == newId } + newId // fresh: append at end
                newId in list -> list                                   // update: already in slot
                else -> list + newId                                    // update: newly-gained section
            }
        }
        return registry.copy(dictionaries = dictionaries, sectionOrder = sectionOrder)
    }

    /** Removes the dictionary's files and every registry reference. No-op
     *  (data preserved) when the registry is unreadable. */
    suspend fun delete(ctx: Context, id: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val registry = readRegistry(ctx) ?: return@withLock
            // copy() for the same reason as import: untouched fields
            // (termsSingleDictionary) must survive the rewrite.
            writeRegistry(
                ctx,
                registry.copy(
                    dictionaries = registry.dictionaries.filterNot { it.id == id },
                    sectionOrder = registry.sectionOrder.mapValues { (_, ids) ->
                        ids.filterNot { it == id }
                    },
                ),
            )
            dictionaryDir(ctx, id).deleteRecursively()
        }
        YomitanDataStore.onDictDeleted(ctx, id)
    }

    /** Replaces [category]'s priority order; other sections are untouched.
     *  No-op when the registry is unreadable. */
    suspend fun reorder(ctx: Context, category: YomitanCategory, orderedIds: List<String>) =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val registry = readRegistry(ctx) ?: return@withLock
                // [orderedIds] is the UI's snapshot and may be stale against a
                // delete/import that landed since the drag started: drop ids
                // no longer in the category (a stale id would resurface as a
                // duplicate row on re-import of the same zip) and re-append
                // anything the snapshot is missing.
                val inCategory = registry.dictionaries
                    .filter { category in it.categories }
                    .map { it.id }
                val cleaned = orderedIds.distinct().filter { it in inCategory.toSet() }
                writeRegistry(
                    ctx,
                    registry.copy(
                        sectionOrder = registry.sectionOrder.toMutableMap().apply {
                            put(category.name, cleaned + inCategory.filterNot { it in cleaned.toSet() })
                        },
                    ),
                )
            }
            // Priority order feeds conflict resolution in the data store.
            YomitanDataStore.invalidate()
        }

    /** Sets the TERMS-section single-dictionary toggle. No-op when the
     *  registry is unreadable. */
    suspend fun setTermsSingleDictionary(ctx: Context, enabled: Boolean) =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val registry = readRegistry(ctx) ?: return@withLock
                if (registry.termsSingleDictionary == enabled) return@withLock
                writeRegistry(ctx, registry.copy(termsSingleDictionary = enabled))
            }
            // The flag is part of the data store's registry-derived cache.
            YomitanDataStore.invalidate()
        }

    /** Sets the user-facing alias override for dictionary [id]; blank clears it
     *  (consumers render `alias ?: title`). No-op when the registry is
     *  unreadable, the dictionary is gone, or the value is unchanged. */
    suspend fun setAlias(ctx: Context, id: String, alias: String?) =
        withContext(Dispatchers.IO) {
            val normalized = alias?.trim()?.takeUnless { it.isEmpty() }
            val changed = mutex.withLock {
                val registry = readRegistry(ctx) ?: return@withLock false
                val current = registry.dictionaries.firstOrNull { it.id == id }
                    ?: return@withLock false
                if (current.alias == normalized) return@withLock false
                writeRegistry(
                    ctx,
                    registry.copy(
                        dictionaries = registry.dictionaries.map {
                            if (it.id == id) it.copy(alias = normalized) else it
                        },
                    ),
                )
                true
            }
            // Alias feeds the data store's registry-derived display cache.
            if (changed) YomitanDataStore.invalidate()
        }

    /** Sets the per-dictionary accent color override (ARGB) for dictionary
     *  [id]; null clears it (chips fall back to the default neutral
     *  background). No-op when the registry is unreadable, the dictionary is
     *  gone, or the value is unchanged. */
    suspend fun setAccentColor(ctx: Context, id: String, color: Int?) =
        withContext(Dispatchers.IO) {
            val changed = mutex.withLock {
                val registry = readRegistry(ctx) ?: return@withLock false
                val current = registry.dictionaries.firstOrNull { it.id == id }
                    ?: return@withLock false
                if (current.accentColor == color) return@withLock false
                writeRegistry(
                    ctx,
                    registry.copy(
                        dictionaries = registry.dictionaries.map {
                            if (it.id == id) it.copy(accentColor = color) else it
                        },
                    ),
                )
                true
            }
            // Accent color feeds the data store's registry-derived chip cache.
            if (changed) YomitanDataStore.invalidate()
        }

    /** Sets the per-dictionary auto-update opt-out for dictionary [id]. No-op
     *  when the registry is unreadable, the dictionary is gone, or unchanged.
     *  Deliberately does NOT call [YomitanDataStore.invalidate] — unlike alias
     *  and accent color, `autoUpdate` feeds no capability cache (only the
     *  auto-updater reads it), so invalidating would drop every per-language
     *  cache for nothing. */
    suspend fun setAutoUpdate(ctx: Context, id: String, enabled: Boolean) =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val registry = readRegistry(ctx) ?: return@withLock
                val current = registry.dictionaries.firstOrNull { it.id == id }
                    ?: return@withLock
                if (current.autoUpdate == enabled) return@withLock
                writeRegistry(
                    ctx,
                    registry.copy(
                        dictionaries = registry.dictionaries.map {
                            if (it.id == id) it.copy(autoUpdate = enabled) else it
                        },
                    ),
                )
            }
        }

    /**
     * One-time migration: populate [YomitanDictionary.isUpdatable]/[indexUrl]/
     * [downloadUrl] on registry entries imported before those fields existed, by
     * re-reading each stored zip's index.json (capped, typed). Idempotent and
     * cheap (a few-KB capped read per deck); the caller gates it behind a
     * one-time [com.playtranslate.Prefs] flag so it runs once. No cache
     * invalidation — these fields feed the updater only, never a capability
     * cache. Other fields (alias/accent/autoUpdate) are preserved.
     */
    suspend fun backfillUpdateMetadata(ctx: Context) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val registry = readRegistry(ctx) ?: return@withLock
            var changed = false
            val updated = registry.dictionaries.map { dict ->
                val meta = readUpdateMetadata(ctx, dict.id) ?: return@map dict
                if (dict.isUpdatable == meta.isUpdatable &&
                    dict.indexUrl == meta.indexUrl &&
                    dict.downloadUrl == meta.downloadUrl
                ) {
                    dict
                } else {
                    changed = true
                    dict.copy(
                        isUpdatable = meta.isUpdatable,
                        indexUrl = meta.indexUrl,
                        downloadUrl = meta.downloadUrl,
                    )
                }
            }
            if (changed) writeRegistry(ctx, registry.copy(dictionaries = updated))
        }
    }

    private class UpdateMetadata(
        val isUpdatable: Boolean,
        val indexUrl: String?,
        val downloadUrl: String?,
    )

    /** Reads just the update-relevant fields from [id]'s stored zip index.json
     *  (capped + typed — the same primitive [parseAndValidate] uses, NOT the
     *  uncapped [readIndexJson] display reader). Null when unreadable. */
    private fun readUpdateMetadata(ctx: Context, id: String): UpdateMetadata? {
        val zip = zipFile(ctx, id)
        if (!zip.exists()) return null
        return try {
            ZipFile(zip).use { z ->
                val entry = z.getEntry("index.json") ?: return null
                val text = z.getInputStream(entry).use { it.readUtf8Capped(MAX_INDEX_JSON_BYTES) }
                    ?: return null
                val index = PtJson.lenient.decodeFromString<IndexJson>(text)
                UpdateMetadata(
                    isUpdatable = index.isUpdatable,
                    indexUrl = index.indexUrl?.trim()?.takeIf { it.isNotEmpty() },
                    downloadUrl = index.downloadUrl?.trim()?.takeIf { it.isNotEmpty() },
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "backfill: index.json read failed for $id", e)
            null
        }
    }

    // ── Validation ──────────────────────────────────────────────────────

    /** Structural-validation failure. [message] is the user-visible (debug
     *  page) diagnostic; the surrounding import logic converts it into
     *  [YomitanImportResult.InvalidFormat]. */
    private class InvalidDictionaryException(
        message: String,
        cause: Throwable? = null,
    ) : Exception(message, cause)

    private class ParsedDictionary(
        val title: String,
        val revision: String?,
        val description: String?,
        val author: String?,
        val format: Int,
        val categories: List<YomitanCategory>,
        val sourceLanguage: String?,
        val targetLanguage: String?,
        val frequencyMode: String?,
        val isUpdatable: Boolean,
        val indexUrl: String?,
        val downloadUrl: String?,
    )

    /** index.json shape — extra fields ignored, [format]/[version] aliased. */
    @Serializable
    private class IndexJson(
        val title: String? = null,
        val revision: String? = null,
        val description: String? = null,
        val author: String? = null,
        val format: Int? = null,
        val version: Int? = null,
        val sourceLanguage: String? = null,
        val targetLanguage: String? = null,
        val frequencyMode: String? = null,
        val isUpdatable: Boolean = false,
        val indexUrl: String? = null,
        val downloadUrl: String? = null,
    )

    private val TERM_BANK = Regex("""term_bank_\d+\.json""")
    private val TERM_META_BANK = Regex("""term_meta_bank_\d+\.json""")
    private val KANJI_BANK = Regex("""kanji_bank_\d+\.json""")
    private val KANJI_META_BANK = Regex("""kanji_meta_bank_\d+\.json""")
    private val TAG_BANK = Regex("""tag_bank_\d+\.json""")

    /** Throws [InvalidDictionaryException] (with the diagnostic as its
     *  message) when anything fails structural validation. */
    private suspend fun parseAndValidate(zip: ZipFile): ParsedDictionary {
        val indexEntry = zip.getEntry("index.json")
            ?: throw InvalidDictionaryException("No index.json at the zip root")
        val index = try {
            val text = zip.getInputStream(indexEntry).use { it.readUtf8Capped(MAX_INDEX_JSON_BYTES) }
                ?: throw InvalidDictionaryException(
                    "index.json exceeds ${MAX_INDEX_JSON_BYTES / 1024} KB"
                )
            PtJson.lenient.decodeFromString<IndexJson>(text)
        } catch (e: InvalidDictionaryException) {
            throw e
        } catch (e: Exception) {
            throw InvalidDictionaryException("index.json is not valid JSON", e)
        }

        val title = index.title?.trim().orEmpty()
        val format = index.format ?: index.version
        if (title.isEmpty() || format == null || format !in 1..3) {
            throw InvalidDictionaryException(
                "index.json needs a title and a format of 1–3 (got title='$title', format=$format)"
            )
        }

        val categories = mutableSetOf<YomitanCategory>()
        for (entry in zip.entries()) {
            coroutineContext.ensureActive()
            // Banks must sit at the zip root — ignore anything in subfolders
            // and non-bank files (styles.css, media) entirely.
            if (entry.name.contains('/')) continue
            when {
                TERM_BANK.matches(entry.name) ->
                    if (validateBank(zip, entry, null) > 0) categories += YomitanCategory.TERMS
                KANJI_BANK.matches(entry.name) ->
                    if (validateBank(zip, entry, null) > 0) {
                        categories += YomitanCategory.KANJI
                        // KANJIDIC-lineage dicts ship their per-kanji frequency
                        // rank inside the kanji_bank stats (no kanji_meta_bank);
                        // expose it as a kanji-frequency source so it reaches the
                        // freq-chip path. Guarded so a multi-file bank only scans
                        // until the first hit.
                        if (YomitanCategory.KANJI_FREQUENCY !in categories &&
                            kanjiBankHasFreqStat(zip, entry)
                        ) {
                            categories += YomitanCategory.KANJI_FREQUENCY
                        }
                    }
                KANJI_META_BANK.matches(entry.name) ->
                    if (validateBank(zip, entry, null) > 0) categories += YomitanCategory.KANJI_FREQUENCY
                TAG_BANK.matches(entry.name) -> validateBank(zip, entry, null)
                TERM_META_BANK.matches(entry.name) -> {
                    val modes = mutableSetOf<String>()
                    validateBank(zip, entry, modes)
                    if ("freq" in modes) categories += YomitanCategory.FREQUENCY
                    if ("pitch" in modes) categories += YomitanCategory.PITCH_ACCENT
                    if ("ipa" in modes) categories += YomitanCategory.PRONUNCIATION
                }
            }
        }
        if (categories.isEmpty()) {
            throw InvalidDictionaryException("No term, kanji, frequency, or pitch data found")
        }
        return ParsedDictionary(
            title = title,
            revision = index.revision,
            description = index.description?.trim()?.takeIf { it.isNotEmpty() },
            author = index.author?.trim()?.takeIf { it.isNotEmpty() },
            format = format,
            categories = YomitanCategory.entries.filter { it in categories },
            sourceLanguage = index.sourceLanguage?.trim()?.takeIf { it.isNotEmpty() },
            targetLanguage = index.targetLanguage?.trim()?.takeIf { it.isNotEmpty() },
            frequencyMode = index.frequencyMode?.trim()?.takeIf { it.isNotEmpty() },
            isUpdatable = index.isUpdatable,
            indexUrl = index.indexUrl?.trim()?.takeIf { it.isNotEmpty() },
            downloadUrl = index.downloadUrl?.trim()?.takeIf { it.isNotEmpty() },
        )
    }

    /**
     * Streams one bank file: top level must be a JSON array whose every
     * element is itself an array. When [collectModes] is non-null (term meta
     * banks), each element's second value — the `freq`/`pitch`/`ipa` mode —
     * is read into it. Returns the entry count; throws
     * [InvalidDictionaryException] on malformed JSON or wrong shape.
     */
    private suspend fun validateBank(
        zip: ZipFile,
        entry: ZipEntry,
        collectModes: MutableSet<String>?,
    ): Int {
        var count = 0
        try {
            zip.getInputStream(entry).use { input ->
                JsonReader(InputStreamReader(input.buffered(), Charsets.UTF_8)).use { reader ->
                    reader.beginArray()
                    while (reader.hasNext()) {
                        coroutineContext.ensureActive()
                        if (reader.peek() != JsonToken.BEGIN_ARRAY) {
                            throw InvalidDictionaryException("${entry.name}: entry $count is not an array")
                        }
                        if (collectModes != null) {
                            reader.beginArray()
                            reader.skipValue() // term
                            if (reader.peek() == JsonToken.STRING) {
                                collectModes += reader.nextString()
                            } else {
                                throw InvalidDictionaryException("${entry.name}: entry $count mode is not a string")
                            }
                            while (reader.hasNext()) reader.skipValue()
                            reader.endArray()
                        } else {
                            reader.skipValue()
                        }
                        count++
                    }
                    reader.endArray()
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: InvalidDictionaryException) {
            throw e
        } catch (e: Exception) {
            // Gson's malformed-JSON errors don't name the file — wrap so the
            // diagnostic says which bank broke and where.
            throw InvalidDictionaryException(
                "${entry.name}: ${e.message ?: e.javaClass.simpleName}", e
            )
        }
        return count
    }

    /** True when a kanji_bank carries a `freq` stat on any character.
     *  KANJIDIC-lineage dicts pack their frequency rank into each entry's
     *  stats object rather than shipping a kanji_meta_bank, so such a dict is a
     *  kanji-frequency source too. Streams [entry] with an early exit on the
     *  first hit; the array-of-arrays shape is already guaranteed by the
     *  preceding [validateBank] call, so an unexpected parse hiccup is treated
     *  as "no freq" rather than failing the whole import. */
    private suspend fun kanjiBankHasFreqStat(zip: ZipFile, entry: ZipEntry): Boolean {
        try {
            zip.getInputStream(entry).use { input ->
                JsonReader(InputStreamReader(input.buffered(), Charsets.UTF_8)).use { reader ->
                    reader.beginArray()
                    while (reader.hasNext()) {
                        coroutineContext.ensureActive()
                        if (KanjiBankEntry.parse(reader)?.freq != null) return true
                    }
                    reader.endArray()
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return false
        }
        return false
    }
}

/** Reads [this] stream as UTF-8 up to [maxBytes]; returns null the moment the
 *  content would exceed the cap, so an oversized or zip-bombed entry is rejected
 *  (InvalidFormat) instead of being materialised into memory. The bank files
 *  are streamed; index.json is the one entry we read whole. Internal for tests. */
internal fun InputStream.readUtf8Capped(maxBytes: Int): String? {
    val buf = ByteArray(maxBytes)
    var off = 0
    while (off < maxBytes) {
        val n = read(buf, off, maxBytes - off)
        if (n < 0) return String(buf, 0, off, Charsets.UTF_8)
        off += n
    }
    // Buffer filled without hitting EOF: if any byte remains, we're over the cap.
    return if (read() < 0) String(buf, 0, off, Charsets.UTF_8) else null
}
