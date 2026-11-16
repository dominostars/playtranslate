package com.playtranslate.audio.localaudio

/**
 * One row of the file's `entries` table: one recording of one written form.
 * [reading] is kana or absent (every Forvo row has none); [display] is the
 * source's own label for the recording (an accent form, a speaker's name).
 */
data class LocalAudioRow(
    val source: String,
    val reading: String?,
    val speaker: String?,
    val display: String?,
    val file: String,
)

/**
 * Which of a written form's recordings answer a request, in which order,
 * and under what name. Pure: no database and no Android, so the rule is
 * tested on its own.
 */
object LocalAudioLookup {

    /**
     * The sources in the order their recordings are offered, which is the
     * order Hoshi Reader's built-in list has them in. Forvo's three, whose
     * rows carry no reading, are last, so a recording tied to the reading
     * leads whenever the file holds one.
     */
    val SOURCE_ORDER: List<String> = listOf(
        "nhk16", "daijisen", "shinmeikai8", "jpod", "jpod_alternate",
        "taas", "ozk5", "forvo", "forvo_ext", "forvo_ext2",
    )

    /** Proper names, not translated. */
    private val SOURCE_NAMES: Map<String, String> = mapOf(
        "nhk16" to "NHK",
        "daijisen" to "Daijisen",
        "shinmeikai8" to "Shinmeikai",
        "jpod" to "JapanesePod101",
        "jpod_alternate" to "JapanesePod101 Alt",
        "taas" to "TAAS",
        "ozk5" to "OZK5",
        "forvo" to "Forvo",
        "forvo_ext" to "Forvo Ext",
        "forvo_ext2" to "Forvo Ext2",
    )

    /** The name a source is shown under: its own for a source listed here,
     *  the id as the file stores it for any other. */
    fun sourceName(source: String): String = SOURCE_NAMES[source] ?: source

    /** [sources] once each, in the order their recordings are offered: the
     *  listed ones as listed, then the rest by id. */
    fun ordered(sources: Collection<String>): List<String> =
        sources.distinct().sortedWith(compareBy<String> { rank(it) }.thenBy { it })

    /**
     * The recordings among [rows], all of one written form, that answer a
     * request for it read as [reading], best first.
     *
     * A row is kept when it has no reading, or the request has none, or the
     * two are the same kana (katakana and hiragana are one here: NHK's
     * readings are katakana). This is the rule of the desktop add-on the
     * file comes from. A row of another reading is another word's audio; a
     * match on the reading alone, with another written form, is not taken,
     * for the same reason.
     *
     * Ordered by source ([SOURCE_ORDER], an unlisted source after them by
     * id), then rows with a reading ahead of rows without. One recording
     * (source and file) is listed once.
     */
    fun recordings(rows: List<LocalAudioRow>, reading: String?): List<LocalAudioRow> {
        val asked = reading?.takeIf { it.isNotBlank() }?.let(::foldKana)
        return rows
            .filter { row ->
                val has = row.reading?.takeIf { it.isNotBlank() }
                asked == null || has == null || foldKana(has) == asked
            }
            .sortedWith(
                compareBy<LocalAudioRow> { rank(it.source) }
                    .thenBy { it.source }
                    .thenBy { if (it.reading.isNullOrBlank()) 1 else 0 },
            )
            .distinctBy { key(it) }
    }

    /**
     * A recording's key as a selection carries it: its source, a slash, its
     * file as the file names it. It ends in the clip's extension, which the
     * audio cache and the card's file take theirs from, and it is all a pick
     * needs to be found again ([parseKey]).
     */
    fun key(row: LocalAudioRow): String = "${row.source}/${row.file}"

    /** [key] back into its source and its file, or null for anything that
     *  is not one. A file may hold slashes of its own; a source holds none. */
    fun parseKey(key: String): Pair<String, String>? {
        val slash = key.indexOf('/')
        if (slash <= 0 || slash == key.lastIndex) return null
        return key.substring(0, slash) to key.substring(slash + 1)
    }

    /** Katakana as hiragana; every other character as it is. */
    internal fun foldKana(text: String): String {
        val out = StringBuilder(text.length)
        for (ch in text) out.append(if (ch in 'ァ'..'ヶ') ch - 0x60 else ch)
        return out.toString()
    }

    private fun rank(source: String): Int =
        SOURCE_ORDER.indexOf(source).let { if (it >= 0) it else Int.MAX_VALUE }
}
