package com.playtranslate.audio

import kotlinx.serialization.Serializable

/**
 * Credit metadata for a recording carrying a Creative Commons license (parsed
 * from Wikimedia Commons `extmetadata`). License compliance is the reason this
 * exists: for CC-BY / CC-BY-SA clips the credit must travel with the audio
 * wherever it is redistributed (notably onto exported Anki cards).
 *
 * Serializable so it can be persisted in the [AudioCache] sidecar next to the
 * downloaded clip.
 */
@Serializable
data class Attribution(
    val author: String?,        // extmetadata Artist (HTML stripped)
    val license: String?,       // extmetadata LicenseShortName, e.g. "CC BY-SA 4.0"
    val sourceName: String,     // e.g. "Wikimedia Commons"
    val sourceUrl: String?,     // file description page URL
) {
    /** Compact one-line credit, e.g. "Jane Doe (CC BY-SA 4.0), via Wikimedia Commons". */
    fun creditLine(): String {
        val who = author?.takeIf { it.isNotBlank() } ?: "Unknown author"
        val lic = license?.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()
        return "$who$lic, via $sourceName"
    }

    companion object {
        /** Aggregate multiple clips' credits into one block (deduped, one per line). */
        fun creditBlock(attributions: List<Attribution>): String =
            attributions.map { it.creditLine() }.distinct().joinToString("\n")
    }
}
