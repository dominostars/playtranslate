package com.playtranslate.cloud

import com.playtranslate.Prefs

/**
 * PlayTranslate Cloud's settings, read from [Prefs] at the moment they are
 * used: a capture reads the switch, the key, the upload cap, the upload
 * format, whether to ask for item lines and whether to warm for that
 * capture, so a change on the settings page applies to the next one without
 * anything to rebuild. The service's address is not a setting: it is fixed
 * in [PtCloudClient].
 *
 * The stored key is checked here, where it is read: OkHttp's
 * `Request.Builder.header` throws on a value that is not a legal header
 * value, and nothing on the hold path would catch that, so a key [hasKey]
 * rejects reads as no key.
 *
 * The defaults live here and only here. The upload cap in particular is a
 * value read per call ([uploadLongSideCap]), not a constant anyone may
 * inline: it becomes a user setting later, so a second copy of 1920 would
 * silently stop following it.
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

    /** The format a frame is uploaded in ([FrameEncoder.encode], the
     *  `frame` part's type and name). A stored name this build does not know
     *  reads as [DEFAULT_UPLOAD_FORMAT]. */
    val uploadFormat: FrameFormat
        get() = FrameFormat.fromWireName(prefs.ptCloudUploadFormat) ?: DEFAULT_UPLOAD_FORMAT

    /** Whether a frame asks for item lines ([FrameMeta.streamTranslations]).
     *  Each one fills its box as it arrives, ahead of the final part, and
     *  is timed for the clock line. */
    val streamItems: Boolean get() = prefs.ptCloudStreamItems

    /** Whether [PtCloudClient.warm] sends anything. */
    val warmEnabled: Boolean get() = prefs.ptCloudWarm

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

        /** JPEG q85: the server's recommended upload, measured as accurate
         *  as WebP q90 and far faster to encode ([FrameFormat]). */
        val DEFAULT_UPLOAD_FORMAT: FrameFormat = FrameFormat.JPEG

        /** Asking costs nothing on a server that predates item lines (it
         *  ignores the flag and answers in two lines), and the clock line
         *  then shows when each group's translation is known. */
        const val DEFAULT_STREAM_ITEMS = true

        /** One small call per capture, meant to take the idle penalty off
         *  the frame's path (not yet measured on a device). */
        const val DEFAULT_WARM = true
    }
}
