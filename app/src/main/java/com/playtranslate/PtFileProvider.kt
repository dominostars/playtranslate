package com.playtranslate

import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import java.util.Locale

/**
 * [FileProvider] that advertises a MIME type whose platform extension mapping
 * round-trips back to the file's real extension.
 *
 * AnkiDroid stores media as `<preferred_name>_<unique>.<ext>` and derives
 * `<ext>` from the URI's MIME type, NOT from our filename (we deliberately
 * pass a base name — see [AnkiManager.addMediaFromFile]). Android's mime map
 * sends `m4a → audio/mpeg`, and `audio/mpeg → mp3` coming back, so every
 * AAC-in-MP4 game-audio clip landed in the collection under a `.mp3` name.
 * Players that dispatch on extension then refuse it: device-confirmed
 * 2026-07-28 with `mime='audio/mpeg' extFromMime='mp3'` logged for a `.m4a`
 * file, and the resulting card silent in AnkiDroid and in AnkiMobile on iOS
 * while the `.wav` TTS on the same card played everywhere.
 *
 * The override is deliberately narrow: the platform's own answer is kept
 * whenever it round-trips (so `wav → audio/x-wav → wav` and `jpg` are
 * untouched), and a replacement is used only if it maps back to the same
 * extension ON THIS DEVICE. An OEM mime map we didn't anticipate therefore
 * can't push us somewhere worse than the status quo.
 */
class PtFileProvider : FileProvider() {

    override fun getType(uri: Uri): String? {
        val ext = uri.lastPathSegment
            ?.substringAfterLast('.', "")
            ?.lowercase(Locale.ROOT)
            .orEmpty()
        if (ext.isEmpty()) return super.getType(uri)
        val map = MimeTypeMap.getSingleton()
        val platform = map.getMimeTypeFromExtension(ext)
        if (platform != null && map.getExtensionFromMimeType(platform) == ext) return platform
        val roundTrips = CANDIDATES[ext]?.firstOrNull { map.getExtensionFromMimeType(it) == ext }
        return roundTrips ?: platform ?: super.getType(uri)
    }

    private companion object {
        /** Consulted only for extensions the platform map mangles; ordered
         *  most-canonical first. */
        val CANDIDATES = mapOf(
            "m4a" to listOf("audio/mp4", "audio/x-m4a", "audio/m4a", "audio/mp4a-latm"),
            "ogg" to listOf("audio/ogg"),
            "opus" to listOf("audio/opus", "audio/ogg"),
        )
    }
}
