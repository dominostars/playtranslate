package com.playtranslate.cloud

import com.playtranslate.language.SourceLangId

/**
 * Whether a capture may be read by PlayTranslate Cloud, and whether the
 * service is configured at all. Pure, so the whole eligibility table is
 * tested cell by cell; the router's decision ([PtCloudService.refusal])
 * gathers the facts and asks here, and the registry backend's
 * [PtCloudBackend.isUsable] asks [configured].
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
     * The one definition of "configured": the user switched the service on
     * ([enabled] is already false outside debug builds) and a usable client
     * key is stored ([PtCloudConfig.hasKey]). [hasKey] is asked only when
     * the switch is on, since reading the key decrypts it through the
     * AndroidKeyStore and the registry asks [PtCloudBackend.isUsable] several
     * times per translation on the caller's thread. [eligible] builds on it,
     * so the frame route and the translate route agree on it.
     */
    inline fun configured(enabled: Boolean, hasKey: () -> Boolean): Boolean = enabled && hasKey()

    /**
     * True only when every condition allows the cloud: it is [configured]
     * (switched on, a usable client key set), the source is one the server
     * reads, the service is not cooling down (a stopped pod and a refused
     * key cool it down too), the caller did not ask for the device reader
     * ([deviceOnly]: a re-OCR, and a re-read of the camera's frozen frame or
     * the import tool's page after an engine pick), and the device has a
     * network ([online]: any default network, validated or not, the app's
     * one "no internet" rule).
     */
    fun eligible(
        enabled: Boolean,
        hasKey: Boolean,
        sourceSupported: Boolean,
        coolingDown: Boolean,
        deviceOnly: Boolean,
        online: Boolean,
    ): Boolean = configured(enabled) { hasKey } && sourceSupported && !coolingDown && !deviceOnly && online
}
