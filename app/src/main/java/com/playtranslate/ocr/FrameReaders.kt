package com.playtranslate.ocr

import android.content.Context
import android.util.Log
import androidx.annotation.VisibleForTesting
import com.playtranslate.OcrManager
import com.playtranslate.cloud.CloudRefusal
import com.playtranslate.cloud.PtCloudConfig
import com.playtranslate.cloud.PtCloudFrameReader
import com.playtranslate.cloud.PtCloudService
import com.playtranslate.language.SourceLangId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

private const val TAG = "FrameReaders"

/**
 * The process's frame readers and the one router over them: the on-device
 * reader ([DeviceFrameReader]), the PlayTranslate Cloud reader
 * ([PtCloudFrameReader]), and the decision of which one a read goes through
 * ([readerFor]) with the warm call that precedes such a read ([warm]). Every
 * consumer of the [FrameReader] seam asks here: the capture service's
 * cycles, hold and live tiers, the drag lookup, the camera tool and the
 * import tool. Initialised once from
 * [com.playtranslate.PlayTranslateApplication.onCreate] ([init]), after
 * [PtCloudService.init], whose cooldown the router reads.
 *
 * The cloud's client and settings are [PtCloudService]'s ([PtCloudService.client],
 * [PtCloudService.config]), the composition root the registry's cloud
 * backend reads too: one client for every warm, frame and translate call, so
 * a warm's send stamp reaches the next frame's clock line and the warm opens
 * the connection both routes reuse (one connection pool).
 */
object FrameReaders {

    private lateinit var appContext: Context
    private lateinit var scope: CoroutineScope

    /** The on-device reader: the process-wide [OcrManager] and the app context. */
    val device: DeviceFrameReader by lazy { DeviceFrameReader(OcrManager.instance, appContext) }

    /** The PlayTranslate Cloud reader, built once on the service's one
     *  client and its settings, which read [com.playtranslate.Prefs] at each
     *  use, so a settings change applies to the next read with nothing to
     *  rebuild. */
    val cloud: PtCloudFrameReader by lazy { PtCloudFrameReader(PtCloudService.client, PtCloudService.config) }

    /** Test seam: a reader every [readerFor] returns in place of the router's,
     *  so a JVM test can drive a consumer's frame handling with scripted
     *  readings ([com.playtranslate.FuriganaModeGateTest], the tools' cycle
     *  tests). Production never sets it; a test that sets it resets it. */
    @VisibleForTesting
    @Volatile
    var overrideForTest: FrameReader? = null

    /** Called once from the Application; [scope] runs the warm call's enqueue
     *  (the app's IO scope). A repeat call keeps the first. */
    @Synchronized
    fun init(context: Context, scope: CoroutineScope) {
        if (this::appContext.isInitialized) return
        appContext = context.applicationContext
        this.scope = scope
    }

    /**
     * The [FrameReader] a read of [srcId] goes through: PlayTranslate Cloud,
     * falling back to the device for a frame it cannot read
     * ([FallbackFrameReader]), when [PtCloudService.refusal] finds nothing
     * against it, else the device alone. The decision, from
     * [PtCloudService.config] read at this call:
     *
     * | OCR switch | deviceOnly | network | usable key | account | source | cooling down | reader |
     * |---|---|---|---|---|---|---|---|
     * | off (always, outside debug builds) | any | any | any | any | any | any | device |
     * | on | true | any | any | any | any | any | device |
     * | on | false | none | any | any | any | any | device |
     * | on | false | some | no | any | any | any | device |
     * | on | false | some | yes | withheld | any | any | device |
     * | on | false | some | yes | admitted | not one the server reads | any | device |
     * | on | false | some | yes | admitted | read | yes | device |
     * | on | false | some | yes | admitted | read | no | cloud, then device |
     *
     * "Account" is [PtCloudConfig.admitted], the server's last word in the
     * record kept beside the key: "withheld" is a record a 403 or a check's
     * Inactive or TermsRequired wrote, an ended entitlement or the current
     * terms not accepted ([CloudRefusal.NOT_ADMITTED]), which comes with both
     * switches off, so the switch answers first in practice. A trial or a
     * lease whose dated end has passed on the device's clock is "admitted"
     * here: the read goes out, and the server's 403 ends the account
     * ([PtCloudService.onAccountWithheld]), the frame's own report being
     * the pill.
     *
     * "Network" is the default network as
     * [com.playtranslate.net.NetworkConnectivity.state] last heard it, the one
     * the floating menu's cloud strip follows: "some" is any, validated or
     * not, and with none no key is read. The translation switch
     * ([com.playtranslate.cloud.PtCloudConfig.translateEnabled]) is not in
     * the table: it decides what the cloud reader asks for, not where a
     * frame is read ([PtCloudFrameReader.read]).
     *
     * The decision is made before the frame is encoded, and the cloud reader
     * asks it once more just before its upload ([PtCloudFrameReader.read]):
     * a refusal found then (the switch turned off or the key cleared during
     * the encode, the network gone, a cooldown begun) reads the frame on the
     * device through the fallback, with nothing recorded or reported.
     *
     * [deviceOnly] is the caller's statement that this read must stay on the
     * device whatever the settings say, because the user picked a device
     * engine for it: a re-OCR of a capture on a device engine (a re-OCR on
     * the OCR picker's cloud entry states nothing and follows the router),
     * and every re-read of the camera's frozen frame or the import tool's
     * page after an engine pick there, which holds for that frame's review.
     * A stopped pod and a refused key
     * are cooldowns ([PtCloudService]), and a withheld account (ended, or
     * owing the current terms) comes with the switch off
     * ([PtCloudService.onAccountWithheld]), so a
     * live tier or the camera's acquires read on the device while one holds
     * without stating anything.
     *
     * A read kept on the device logs why at debug level, except when the switch
     * is off, which is every read of every release build. [warm] asks the same
     * [PtCloudService.refusal] and logs nothing: the read that follows says
     * why.
     */
    fun readerFor(srcId: SourceLangId, deviceOnly: Boolean): FrameReader {
        overrideForTest?.let { return it }
        val refusal = PtCloudService.refusal(PtCloudService.config, srcId, deviceOnly)
            ?: return FallbackFrameReader(cloud, device)
        refusal.logWord?.let { Log.d(TAG, "PlayTranslate Cloud not used for this read: $it") }
        return device
    }


    /**
     * Sends PlayTranslate Cloud's warm call ([PtCloudClient.warm]) when a read
     * of [srcId] with [deviceOnly], which the caller is about to make, would go
     * to the cloud: the same [PtCloudService.refusal] [readerFor] routes that
     * read by. A caller passes the source language and the device-only
     * statement its read will pass, and calls this just before it requests
     * the frame, so the warm's head start covers the screenshot (or the
     * camera's frame conversion, or the import tool's decode and dwell), the
     * crop and the encode. One caller per kind of read: a capture's cycle,
     * the single-screen capture sheet, a press-and-hold, a drag lookup's
     * capture, a live mode's start (for its first cycle's read only), the
     * camera's shutter and acquire, the import tool's page load, and a re-OCR
     * that follows the router (the picker's cloud entry;
     * [com.playtranslate.CaptureService.processScreenshot]). A re-OCR on a
     * device engine reads on the device and never warms.
     *
     * Returns at once, on the caller's thread, when the switch is off or when
     * [deviceOnly] is true: a plain pref read, no key. Otherwise the decision,
     * whose key read decrypts it, and the enqueue run on [Dispatchers.IO], off
     * the read's path; with no network the decision refuses before the key is
     * read, so a warm sends nothing then, as the read that follows goes to
     * the device. Fire and forget: nothing waits for it or reads its
     * answer, and it logs nothing about why it did not go out (the read logs
     * that).
     */
    fun warm(srcId: SourceLangId, deviceOnly: Boolean) {
        val config = PtCloudService.config
        if (!config.ocrEnabled || deviceOnly) return
        scope.launch(Dispatchers.IO) {
            if (PtCloudService.refusal(config, srcId, deviceOnly) == null) PtCloudService.client.warm()
        }
    }

    /** A frame id for a reader's own logs and requests ([ReadRequest.frameId]):
     *  [tag] names the consumer ("d<display>" for the service's reads of a
     *  display, "cam" and "import" for the tools) and the time makes it
     *  unique. */
    fun frameId(tag: String): String = "$tag-${android.os.SystemClock.elapsedRealtime()}"
}
