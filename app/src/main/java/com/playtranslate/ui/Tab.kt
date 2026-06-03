package com.playtranslate.ui

/** The main screen's bottom-bar destinations. Top-level (not nested in
 *  MainActivity) so the pure home-routing decider [decideHome] and its test can
 *  reference it without depending on the Activity. */
enum class Tab { TRANSLATE, SETTINGS, REGIONS }
