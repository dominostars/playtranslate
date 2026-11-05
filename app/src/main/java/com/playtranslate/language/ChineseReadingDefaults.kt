package com.playtranslate.language

import android.content.Context
import android.util.Log
import java.io.IOException

/**
 * Which reading a Chinese word with several readings leads with.
 *
 * The ZH pack scores frequency per written word, so two readings of one
 * word always tie on `freq_score` and nothing in the pack says which is the
 * common one. Left to the pack's line order, which is CC-CEDICT's
 * alphabetical pinyin, 要 led with yāo "to demand" before yào "to want",
 * 跑 with páo "to paw the ground" before pǎo "to run" and 东西 with dōng xī
 * "east and west" before dōng xi "thing". This table names, for the words
 * it covers, the reading the word most often has when it stands alone in
 * running text. Single characters come from the Unicode Han Database
 * (kMandarin, then kHanyuPinlu); multi-character words, and the characters
 * whose standalone word differs from the character's customary reading (教
 * is jiāo "to teach" as a word, jiào inside 教育), are hand decisions in
 * `scripts/zh_reading_defaults_curated.tsv`. Built by
 * `scripts/build_zh_reading_defaults.py` against the pack's own lines.
 *
 * It ships in the app, not the pack, so it reaches the pack already on a
 * device. Keyed by simplified headword and reading as the pack stores them,
 * never by entry id, so a rebuilt pack cannot silently break it: a row whose
 * reading the pack no longer lists matches no line and the word falls back
 * to line order, as does a word with no row.
 */
internal class ChineseReadingDefaults(private val leadBySurface: Map<String, String>) {

    /** The reading [simplified] leads with, numbered as the pack stores it
     *  ("dong1 xi5"), or null when the table has no row for the word. */
    fun leadReading(simplified: String): String? = leadBySurface[simplified]

    val size: Int get() = leadBySurface.size

    companion object {
        private const val TAG = "ChineseReadingDefaults"
        const val ASSET = "zh_reading_defaults.tsv"

        val NONE = ChineseReadingDefaults(emptyMap())

        /** Rows of `surface<TAB>reading`; blank lines and `#` comments (the
         *  header carries the data's licence notice) are skipped, as is a
         *  row missing either field. */
        fun parse(lines: Sequence<String>): ChineseReadingDefaults {
            val rows = HashMap<String, String>()
            for (line in lines) {
                if (line.isEmpty() || line.startsWith("#")) continue
                val tab = line.indexOf('\t')
                if (tab <= 0) continue
                val reading = line.substring(tab + 1).substringBefore('\t').trim()
                if (reading.isEmpty()) continue
                rows[line.substring(0, tab)] = reading
            }
            return ChineseReadingDefaults(rows)
        }

        /** The bundled table. An unreadable asset degrades to no table, so
         *  every word keeps the pack's line order. */
        fun load(context: Context): ChineseReadingDefaults = try {
            context.assets.open(ASSET).bufferedReader().useLines { parse(it) }
        } catch (e: IOException) {
            Log.e(TAG, "Lead-reading table unreadable: ${e.message}")
            NONE
        }
    }
}
