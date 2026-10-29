package com.playtranslate.cloud

import com.playtranslate.Prefs
import com.playtranslate.PtJson
import kotlinx.serialization.decodeFromString

/**
 * PlayTranslate Cloud's settings, read from [Prefs] at the moment they are
 * used: a capture reads the switch, the key and the upload cap for that
 * capture, so a change on the settings page applies to the next one without
 * anything to rebuild. The service's address is not a setting: it is fixed
 * in [PtCloudClient].
 *
 * The stored key is judged by one predicate ([isUsableKey]) wherever it is
 * used, since OkHttp's `Request.Builder.header` throws on a value that is
 * not a legal header value: [hasKey], which the router and the backend ask,
 * reads a key failing it as no key, and the client checks again the key it
 * reads for each request before sending it ([PtCloudClient]), since a key
 * saved after the router asked can fail it.
 *
 * The upload cap's default lives here and only here, and the cap is a value
 * read per call ([uploadLongSideCap]), not a constant anyone may inline: it
 * becomes a user setting later, so a second copy of 1920 would silently stop
 * following it.
 */
class PtCloudConfig(
    /** The settings' store. Exposed so [PtCloudService]'s gestures write the
     *  switches and the key through the instance they are read from: in
     *  production the application's, in a JVM test the fake-codec one a
     *  test installed, since production [Prefs] stores no key without the
     *  AndroidKeyStore. */
    internal val prefs: Prefs,
) {

    /** The OCR switch, already false outside debug builds
     *  ([Prefs.ptCloudOcrEnabled]): whether captures are read through the
     *  cloud. The frame route's switch ([PtCloudService.refusal]). */
    val ocrEnabled: Boolean get() = prefs.ptCloudOcrEnabled

    /** The translation switch ([Prefs.ptCloudTranslateEnabled]): whether the
     *  cloud translates, as the waterfall's first backend
     *  ([PtCloudBackend.isUsable]) and within a frame it reads
     *  ([PtCloudFrameReader.read]). */
    val translateEnabled: Boolean get() = prefs.ptCloudTranslateEnabled

    /** Either switch on: the service takes part in something, so the
     *  Settings hub describes it as on. */
    val anyEnabled: Boolean get() = ocrEnabled || translateEnabled

    /** The client key sent as the bearer token, trimmed (a pasted key often
     *  carries a newline, which is not a legal header value). Never logged. */
    val clientKey: String get() = prefs.ptCloudClientKey.trim()

    /** Long side, in pixels, the frame is scaled down to before upload
     *  ([FrameEncoder.encode]). */
    val uploadLongSideCap: Int get() = prefs.ptCloudUploadLongSideCap

    /** True when [clientKey] is usable as a bearer value ([isUsableKey]). A
     *  key failing this reads as no key, so the router keeps capture on the
     *  device, as it does when none is set. */
    val hasKey: Boolean get() = isUsableKey(clientKey)

    /** The account record kept beside the key ([PtCloudAccount],
     *  [Prefs.ptCloudAccountJson]), decoded leniently. Null unless [hasKey],
     *  so no reader sees a record without its key; null too when none is
     *  stored (a key saved before records) or the stored one does not
     *  decode, which every reader takes as no details
     *  ([PtCloudAccount.Standing.NO_DETAILS]). Reads the key, as [hasKey]
     *  does. */
    val account: PtCloudAccount?
        get() {
            if (!hasKey) return null
            val json = prefs.ptCloudAccountJson ?: return null
            return try {
                PtJson.lenient.decodeFromString<PtCloudAccount>(json)
            } catch (e: IllegalArgumentException) {
                // kotlinx's SerializationException is one.
                null
            }
        }

    /** The stored key's standing at [nowMs], the one reading of [account]
     *  every surface words: null without a usable key ([hasKey] false);
     *  [PtCloudAccount.Standing.NO_DETAILS] with a key and no record, none
     *  stored or one that does not decode; else the record's own
     *  ([PtCloudAccount.standing]). Words only; the routes and the controls
     *  read [entitled]. */
    fun standing(nowMs: Long = System.currentTimeMillis()): PtCloudAccount.Standing? {
        if (!hasKey) return null
        return account?.standing(nowMs) ?: PtCloudAccount.Standing.NO_DETAILS
    }

    /** A usable key whose account the server has not written as inactive
     *  ([PtCloudAccount.active]: on the trial or on a membership as of the
     *  last answer, or with nothing known against it, a key with no record).
     *  The server's last word, never the device's clock (2026-10-29, Codex
     *  adversarial review): a Ko-fi membership is a lease per payment that
     *  the next payment renews on the server, so a record whose dated end
     *  has passed here still routes to the cloud, and the server's own 403
     *  is what ends an account ([PtCloudService.onEntitlementEnded]), as a
     *  check's Inactive does ([PtCloudService.onStoredKeyChecked]). The
     *  precondition of both switches, which follow it
     *  ([PtCloudService.saveClientKey], [PtCloudService.onStoredKeyChecked],
     *  [PtCloudService.onEntitlementEnded]), and of every read
     *  ([PtCloudService.refusal]); false with no key. Reads the key as
     *  [hasKey] does ([Prefs] keeps its decryption, so a second read in one
     *  decision decrypts nothing), and decodes the small record at each
     *  call. */
    val entitled: Boolean get() = hasKey && account?.active != false

    companion object {
        /** True when [key] is usable as a bearer value: non-blank, and every
         *  character a tab or printable ASCII (OkHttp's header-value rule,
         *  which `Request.Builder.header` enforces by throwing). The one
         *  predicate for [hasKey] and for the client's check of the key it
         *  sends. */
        fun isUsableKey(key: String): Boolean = key.isNotBlank() && key.all { it == '\t' || it in ' '..'~' }

        /** The server's detector works at 1920 px on the long side, so a
         *  frame capped there loses nothing to detection and keeps the
         *  upload flat across displays. */
        const val DEFAULT_UPLOAD_LONG_SIDE_CAP = 1920
    }
}
