package com.playtranslate.translation.llm

/** Format a byte count as a GB string for user-facing RAM/disk display.
 *  Whole-GB values render without a decimal ("4 GB"); fractional values
 *  render with one decimal place ("1.5 GB"). Matches the format the
 *  Settings UI has used for [OnDeviceLlmBackend.availMemFloorBytes] since
 *  the on-device tier shipped. */
fun Long.toGbDisplay(): String {
    val gb = this / 1_000_000_000.0
    return if (gb == gb.toLong().toDouble()) "${gb.toLong()} GB"
           else "%.1f GB".format(gb)
}
