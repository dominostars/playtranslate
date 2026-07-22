package com.playtranslate.yomitan

/**
 * Pure tally for the multi-file import summary — no Context / Android deps, so the
 * counting and truncation (the off-by-one-prone part) are unit-testable. The
 * settings activity resolves each outcome to a display label (the dictionary title
 * for Success/Duplicate, the SAF file name for failures), calls [summarizeBatch],
 * then formats the counts into strings.
 */

/** A capped list of example names for one failure/duplicate group: up to
 *  `maxPerGroup` [examples], with [overflow] = how many more were elided
 *  (rendered as a "+K more" tail by the caller). */
internal data class GroupedNames(val examples: List<String>, val overflow: Int) {
    val isEmpty: Boolean get() = examples.isEmpty()
}

/** Grouped outcome counts for a finished batch import. */
internal data class BatchImportTally(
    val importedCount: Int,
    val totalSelected: Int,
    val duplicates: GroupedNames,
    val invalid: GroupedNames,
    val noSpace: GroupedNames,
    val failed: GroupedNames,
)

/**
 * Tallies [labeled] — one `(displayLabel, result)` pair per file, in order — into
 * a [BatchImportTally]. Every selected file is attempted (an out-of-space file no
 * longer stops the batch), so the total is simply `labeled.size`. Each
 * failure/duplicate group keeps at most [maxPerGroup] example names; the rest
 * become [GroupedNames.overflow].
 */
internal fun summarizeBatch(
    labeled: List<Pair<String, YomitanImportResult>>,
    maxPerGroup: Int,
): BatchImportTally {
    val duplicates = mutableListOf<String>()
    val invalid = mutableListOf<String>()
    val noSpace = mutableListOf<String>()
    val failed = mutableListOf<String>()
    var imported = 0

    for ((label, result) in labeled) {
        when (result) {
            is YomitanImportResult.Success -> imported++
            // The tally's unit is FILES ("N of M imported"), so a collection
            // dump counts as one imported file when it added anything; a dump
            // whose dictionaries were all already installed groups with the
            // duplicates (labeled by its file name). The dump's own dictionary
            // counts surface through the single-file alert instead.
            is YomitanImportResult.CollectionImported ->
                if (result.imported > 0) imported++ else duplicates += label
            is YomitanImportResult.Duplicate -> duplicates += label
            is YomitanImportResult.InvalidFormat -> invalid += label
            is YomitanImportResult.InsufficientSpace -> noSpace += label
            YomitanImportResult.IoError -> failed += label
            // Auto-update-only outcome; a manual/batch import never produces it.
            // Ignored so it counts toward neither imported nor failed.
            is YomitanImportResult.Skipped -> Unit
        }
    }

    return BatchImportTally(
        importedCount = imported,
        totalSelected = labeled.size,
        duplicates = duplicates.grouped(maxPerGroup),
        invalid = invalid.grouped(maxPerGroup),
        noSpace = noSpace.grouped(maxPerGroup),
        failed = failed.grouped(maxPerGroup),
    )
}

private fun List<String>.grouped(maxPerGroup: Int): GroupedNames =
    GroupedNames(examples = take(maxPerGroup), overflow = (size - maxPerGroup).coerceAtLeast(0))
