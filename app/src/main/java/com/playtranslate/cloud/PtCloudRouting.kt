package com.playtranslate.cloud

import com.playtranslate.language.SourceLangId

/**
 * Whether a capture may be read by PlayTranslate Cloud. Pure, so the whole
 * eligibility table is tested cell by cell; the router gathers the facts
 * and asks here.
 */
object PtCloudRouting {

    /**
     * The source codes the service's OCR accepts, spelled exactly as the
     * wire wants them (`zh-Hant`, not `zh_Hant`). Any other value reaches
     * the client as `502 ocr_unavailable`, which reads as an outage, so a
     * source outside this set never goes to the cloud. Equal today to every
     * [SourceLangId] code; the test pins that, so a new source language
     * fails it until the server's list is checked.
     */
    val SUPPORTED_SOURCE_CODES: Set<String> = setOf(
        "ja", "zh", "zh-Hant", "en", "es", "fr", "de", "it", "pt", "nl", "tr", "vi", "id",
        "sv", "da", "no", "fi", "hu", "ro", "ca", "pl", "ko", "ru", "ar", "th", "hi",
    )

    fun isSupportedSource(id: SourceLangId): Boolean = id.code in SUPPORTED_SOURCE_CODES

    /**
     * True only when every condition allows the cloud: the user switched it
     * on ([enabled] is already false outside debug builds), a usable client
     * key is set ([PtCloudConfig.hasKey]), the source is one the server
     * reads, the service is not cooling down, and the caller did not ask for
     * the device reader ([deviceOnly]: re-OCR, the furigana hold, a hold
     * while live mode runs, the furigana live tier, a live session the
     * service refused ([PtCloudService.liveSessionBlocked])).
     */
    fun eligible(
        enabled: Boolean,
        hasKey: Boolean,
        sourceSupported: Boolean,
        coolingDown: Boolean,
        deviceOnly: Boolean,
    ): Boolean = enabled && hasKey && sourceSupported && !coolingDown && !deviceOnly
}
