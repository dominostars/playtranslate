package com.playtranslate.cloud

import com.playtranslate.Prefs

/**
 * PlayTranslate Cloud's settings, read from [Prefs] at the moment they are
 * used: a capture reads the switch, the key and the upload cap for that
 * capture, so a change on the settings page applies to the next one without
 * anything to rebuild. The service's address is not a setting: it is fixed
 * in [PtCloudClient].
 *
 * The stored key is checked here, where it is read: OkHttp's
 * `Request.Builder.header` throws on a value that is not a legal header
 * value, and nothing on the hold path would catch that, so a key [hasKey]
 * rejects reads as no key.
 *
 * The upload cap's default lives here and only here, and the cap is a value
 * read per call ([uploadLongSideCap]), not a constant anyone may inline: it
 * becomes a user setting later, so a second copy of 1920 would silently stop
 * following it.
 */
class PtCloudConfig(private val prefs: Prefs) {

    /** The user's switch, already false outside debug builds
     *  ([Prefs.ptCloudEnabled]). */
    val enabled: Boolean get() = prefs.ptCloudEnabled

    /** The client key sent as the bearer token, trimmed (a pasted key often
     *  carries a newline, which is not a legal header value). Never logged. */
    val clientKey: String get() = prefs.ptCloudClientKey.trim()

    /** Long side, in pixels, the frame is scaled down to before upload
     *  ([FrameEncoder.encode]). */
    val uploadLongSideCap: Int get() = prefs.ptCloudUploadLongSideCap

    /** True when [clientKey] is usable as a bearer value: non-blank, and
     *  every character a tab or printable ASCII (OkHttp's header-value rule,
     *  which `Request.Builder.header` enforces by throwing). A key failing
     *  this reads as no key, so the router keeps capture on the device, as
     *  it does when none is set. */
    val hasKey: Boolean
        get() = clientKey.let { key -> key.isNotBlank() && key.all { it == '\t' || it in ' '..'~' } }

    companion object {
        /** The server's detector works at 1920 px on the long side, so a
         *  frame capped there loses nothing to detection and keeps the
         *  upload flat across displays. */
        const val DEFAULT_UPLOAD_LONG_SIDE_CAP = 1920
    }
}
