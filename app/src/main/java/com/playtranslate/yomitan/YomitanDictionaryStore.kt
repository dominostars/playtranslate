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
)

/**
 * Whether this dictionary's data applies to source language [lang]: compares
 * the index.json sourceLanguage's primary subtag against [lang]'s primary
 * subtag, case-insensitively, so "ja-JP" applies to "ja" and a caller may pass
 * "zh-Hant" safely (both reduce to "zh"). An undeclared sourceLanguage defaults
 * to Japanese — the field is a recent schema addition most community
 * dictionaries predate, and the ecosystem is overwhelmingly JA, so the default
 * keeps every dictionary that works today working.
 */
fun YomitanDictionary.matchesSourceLanguage(lang: String): Boolean =
    (sourceLanguage ?: "ja").split('-', '_').first()
        .equals(lang.split('-', '_').first(), ignoreCase = true)

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

            // Disk guard: the zip is kept whole (1×) and the derived term
            // rows from a flattened glossary can exceed the compressed
            // source — 3× the zip leaves headroom for both. The temp copy
            // already landed, so this checks what's left AFTER it.
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

            val success = mutex.withLock {
                val registry = readRegistry(ctx)
                    ?: return@withContext YomitanImportResult.IoError
                registry.dictionaries.firstOrNull { it.title == parsed.title }?.let {
                    return@withContext YomitanImportResult.Duplicate(it.title)
                }

                val dictionary = YomitanDictionary(
                    id = id,
                    title = parsed.title,
                    revision = parsed.revision,
                    description = parsed.description,
                    author = parsed.author,
                    format = parsed.format,
                    categories = parsed.categories,
                    sizeBytes = temp.length(),
                    importedAtMs = System.currentTimeMillis(),
                    sourceLanguage = parsed.sourceLanguage,
                    targetLanguage = parsed.targetLanguage,
                    frequencyMode = parsed.frequencyMode,
                )

                try {
                    dictionaryDir(ctx, id).mkdirs()
                    PackIntegrity.atomicReplace(temp, zipFile(ctx, id))
                    // copy(), never a fresh YomitanRegistry(...): rebuilding
                    // from scratch silently resets every field this mutation
                    // doesn't touch (e.g. termsSingleDictionary).
                    writeRegistry(
                        ctx,
                        registry.copy(
                            dictionaries = registry.dictionaries + dictionary,
                            sectionOrder = registry.sectionOrder.toMutableMap().apply {
                                for (cat in dictionary.categories) {
                                    // filterNot: a stale occurrence of this id
                                    // (same content re-imported after a racy
                                    // delete) must not yield duplicate rows.
                                    put(
                                        cat.name,
                                        (get(cat.name) ?: emptyList()).filterNot { it == id } + id,
                                    )
                                }
                            },
                        ),
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "install failed", e)
                    dictionaryDir(ctx, id).deleteRecursively()
                    return@withContext YomitanImportResult.IoError
                }
                YomitanImportResult.Success(dictionary)
            }
            // Outside the registry mutex: derive runtime data (e.g. pitch
            // rows) so it's queryable before the first lookup. Failures are
            // logged inside and retried by the data store's reconcile.
            YomitanDataStore.onDictImported(ctx, success.dictionary)
            success
        } finally {
            temp.delete()
        }
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
                    if (validateBank(zip, entry, null) > 0) categories += YomitanCategory.KANJI
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
