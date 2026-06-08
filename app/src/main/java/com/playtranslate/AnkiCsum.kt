package com.playtranslate

import java.security.MessageDigest

/**
 * Pure (Android-free) helpers mirroring how Anki de-duplicates notes: the
 * first-field checksum (`csum`) and the HTML stripping it relies on. Kept out
 * of [AnkiManager] so they stay unit-testable without loading that class's
 * content-provider URIs, whose `Uri.parse` needs an Android runtime.
 */
internal object AnkiCsum {

    // Mirrors Anki's strip_html_preserving_media_filenames closely enough for
    // first-field equality: whole comment / <style> / <script> blocks are
    // removed (content and all), then any remaining tags, then the handful of
    // entities Anki decodes. PlayTranslate's own first field is HTML (the word
    // wrapped in a <style> block + <div>), so this must reduce it to the bare
    // word to match the csum Anki computed and stored.
    private val HTML_BLOCK_RE =
        Regex("(?is)<!--.*?-->|<style.*?>.*?</style>|<script.*?>.*?</script>")
    private val HTML_TAG_RE = Regex("<[^>]*>")

    /** Strip HTML the way Anki does before hashing / first-field comparison. */
    fun stripHtml(s: String): String =
        HTML_TAG_RE.replace(HTML_BLOCK_RE.replace(s, ""), "")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")

    /** Anki's note `csum`: the first 4 bytes of SHA-1 of the (already
     *  HTML-stripped) first field, as an unsigned 32-bit integer. */
    fun checksum(strippedFirstField: String): Long {
        val d = MessageDigest.getInstance("SHA-1")
            .digest(strippedFirstField.toByteArray(Charsets.UTF_8))
        return ((d[0].toLong() and 0xff) shl 24) or
            ((d[1].toLong() and 0xff) shl 16) or
            ((d[2].toLong() and 0xff) shl 8) or
            (d[3].toLong() and 0xff)
    }
}
