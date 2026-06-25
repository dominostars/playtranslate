package com.playtranslate.model

/**
 * One reading row for the word-detail header: the kana [reading], its [pitch]
 * contour, the [written] form it belongs to (used for TTS), and whether it is
 * the occurrence reading the lens highlighted (rendered bold + first-class).
 */
data class ReadingRow(
    val written: String?,
    val reading: String,
    val pitch: List<Int>,
    val bolded: Boolean,
)

/**
 * The entry's readings as display rows, ordered by **common use**
 * ([Headword.rankScore] descending; ties keep position/source order via the
 * stable sort). Deduped by reading — the higher-`rankScore` headword wins a
 * duplicate. The row whose reading equals [occurrenceReading] is flagged
 * [ReadingRow.bolded]; a null or unmatched occurrence leaves nothing bolded.
 *
 * Reading-less headwords (written-only entries — e.g. non-JA) are skipped, so a
 * pure written-only entry yields an empty list the caller treats as
 * "speak-the-word only".
 */
fun DictionaryEntry.orderedReadingRows(occurrenceReading: String?): List<ReadingRow> {
    // First insertion wins a reading's slot (position order); a later headword
    // with the SAME reading replaces only when it ranks higher, keeping the
    // slot's position so equal-rank ties stay in source order after the sort.
    val byReading = LinkedHashMap<String, Headword>()
    for (hw in headwords) {
        val r = hw.reading ?: continue
        val existing = byReading[r]
        if (existing == null || hw.rankScore > existing.rankScore) byReading[r] = hw
    }
    return byReading.values
        .sortedByDescending { it.rankScore }
        .map { hw ->
            ReadingRow(
                written = hw.written,
                reading = hw.reading!!,
                pitch = hw.pitch,
                bolded = occurrenceReading != null && hw.reading == occurrenceReading,
            )
        }
}
