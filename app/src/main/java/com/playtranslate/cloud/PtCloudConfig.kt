package com.playtranslate.cloud

import com.playtranslate.Prefs
import com.playtranslate.net.CustomEndpointPolicy
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * PlayTranslate Cloud's settings, read from [Prefs] at the moment they are
 * used: a capture reads the switch, the key, the URL, the upload cap, the
 * upload format, whether to ask for item lines and whether to warm for that
 * capture, so a change on the settings page applies to the next one without
 * anything to rebuild.
 *
 * The stored URL and key are checked here, where they are read, not only on
 * the settings page: a value the page never validated (a write from outside
 * the UI, an older build) must not reach request building, where OkHttp's
 * `Request.Builder.url` and `.header` throw on it and nothing on the hold
 * path would catch that. So requests go to [endpoint], never to the string,
 * and a key [hasKey] rejects reads as no key.
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

    /** The service's root as stored, trimmed and without a trailing slash:
     *  what the settings page shows. Requests never use it; they go to
     *  [endpoint]. */
    val baseUrl: String get() = prefs.ptCloudBaseUrl.trim().trimEnd('/')

    /** [baseUrl] parsed, when it may be called: null when it is malformed,
     *  or is cleartext http to a public host ([CustomEndpointPolicy]: the
     *  bearer key would cross the internet in the clear). The one source of
     *  every request URL ([PtCloudClient]); the router sends nothing to the
     *  cloud while it is null. */
    val endpoint: HttpUrl?
        get() = baseUrl.toHttpUrlOrNull()?.takeIf { CustomEndpointPolicy.isAcceptable(it) }

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
     *  The client reads and times them; nothing is drawn from them. */
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
        /** The Cloudflare Worker in front of the service. The same URL
         *  whether the service is running or not: while it is off, every
         *  call answers 404 with an empty body. */
        const val DEFAULT_BASE_URL = "https://pt-api.thedominostars.workers.dev"

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
