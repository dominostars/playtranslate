package com.playtranslate.ocr

import android.graphics.Bitmap
import android.graphics.Rect

/**
 * A read that found nothing, remembered so the same read is not made twice:
 * the OCR input of the last read of one display that came back with nothing
 * to translate, the question it was asked with, and who answered. The next
 * read of that display whose input is the same bitmap, byte for byte, asked
 * the same question, is that answer again and never reaches the reader, on
 * the device or on PlayTranslate Cloud
 * ([com.playtranslate.OverlayToolkit.runOcrPipeline] asks before it reads).
 * A live session left on a screen with no text and no motion reads it once.
 *
 * The comparison is exact ([Bitmap.sameAs]): one pixel that differs is a
 * read, so nothing on screen can be missed, and nothing has to check on the
 * memo later. The input is the bitmap the reader is handed, after the crop
 * and with this app's own chrome blacked out, so what the read ignores (the
 * status bar's clock, the floating icon repainting itself) the comparison
 * ignores, with no second set of rules to keep in step with the read's.
 *
 * The question ([Asked]) is everything else the read's answer hangs on that
 * is in hand at the call. What is not (which device engine is selected, the
 * furigana filter) reaches the live tiers as
 * [com.playtranslate.CaptureService.refreshLiveOverlay], which forgets.
 *
 * Only a read that found nothing is remembered. The caller's dispatcher,
 * like the reads it stands in front of; one per display.
 */
class NoTextMemo {

    /**
     * What a read was asked besides its pixels: the kind of reader the
     * router chose (the device's, or the cloud's with its fallback, so a
     * cloud cooldown ending asks the cloud), the request without the two
     * fields that say nothing about the answer (the frame id, the device
     * reader's callback), and the rect whose groups the read drops (the
     * live-start card's, in the input's coordinates).
     */
    data class Asked(
        val reader: Class<out FrameReader>,
        val request: ReadRequest,
        val exclude: Rect?,
    ) {
        companion object {
            fun of(reader: FrameReader, request: ReadRequest, exclude: Rect?) =
                Asked(reader.javaClass, request.copy(frameId = "", beforeDeviceOcr = null), exclude)
        }
    }

    private var input: Bitmap? = null
    private var asked: Asked? = null
    private var answeredBy: ReaderAttribution? = null

    private var remembered = 0
    private var recalled = 0

    /** Who found nothing in [input] when asked [asked], when that is the
     *  read remembered; null when it is not, and the caller reads. */
    fun recall(input: Bitmap, asked: Asked): ReaderAttribution? {
        val kept = this.input ?: return null
        if (asked != this.asked || !kept.sameAs(input)) return null
        recalled++
        return answeredBy
    }

    /** A read of [input], asked [asked], found nothing; [answeredBy] looked.
     *  [input] is copied: the caller still owns it. */
    fun remember(input: Bitmap, asked: Asked, answeredBy: ReaderAttribution) {
        val copy = input.copy(input.config ?: Bitmap.Config.ARGB_8888, false)
        this.input?.recycle()
        this.input = copy
        this.asked = asked
        this.answeredBy = answeredBy
        remembered++
    }

    /** Forgets the read: the next one is made. (The question and the answer
     *  stay behind unread: both are read only beside an input, and
     *  [remember] replaces all three.) */
    fun forget() {
        input?.recycle()
        input = null
    }

    /** The tally since the memo was made, for the log when its display's
     *  live mode stops. */
    fun summary(): String = "no-text memo: remembered=$remembered recalled=$recalled"
}
