package com.playtranslate.cloud

import com.playtranslate.Prefs
import com.playtranslate.net.CustomEndpointPolicy
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * PlayTranslate Cloud's settings, read from [Prefs] at the moment they are
 * used: a capture reads the switch, the key, the URL and the upload cap for
 * that capture, so a change on the settings page applies to the next one
 * without anything to rebuild.
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
    }
}
