package com.playtranslate

import kotlinx.serialization.json.Json

/**
 * Shared kotlinx.serialization [Json] configurations — the single source of
 * (de)serialization behaviour for every `@Serializable` DTO in the app,
 * replacing the scattered `Gson()` / `GsonBuilder()` instances.
 *
 * Two flavours:
 *  - [lenient] — parsing cloud API responses and reading persisted files. The
 *    settings reproduce the Gson behaviour we relied on: [ignoreUnknownKeys]
 *    tolerates the many fields every API returns that we don't model (Gson
 *    ignored them silently; kotlinx throws by default); [coerceInputValues]
 *    turns an explicit JSON `null` on a non-null property that has a default
 *    into that default; [explicitNulls] = false omits null fields on write,
 *    matching Gson's default so persisted files stay clean and round-trip
 *    with the ones Gson previously wrote.
 *  - [pretty] — [lenient] plus indentation, for the human-readable on-disk
 *    files (Yomitan `registry.json`, pack `manifest.json`) that Gson wrote
 *    with `setPrettyPrinting()`.
 */
object PtJson {
    val lenient: Json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
        explicitNulls = false
    }

    val pretty: Json = Json(lenient) {
        prettyPrint = true
    }
}
