package com.playtranslate.bergamot

/**
 * Thin JNI bridge to the slimt (Bergamot) inference engine, built with the
 * gemmology int8 backend — correct on ARM, unlike the ruy path that produces
 * garbage (DavidVentura/offline-translator#185).
 *
 * Handles are raw native pointers (Long, 0 = failure). The native engine is
 * single-threaded: callers MUST serialize every call (see BergamotTranslator,
 * which owns a mutex). String methods return null on native failure.
 */
object BergamotNative {
    @Volatile private var loaded = false

    /** Loads libbergamot_jni.so. Safe to call repeatedly. Throws on load failure. */
    @Synchronized
    fun ensureLoaded() {
        if (loaded) return
        System.loadLibrary("bergamot_jni")
        loaded = true
    }

    /** Create a Blocking translation service with the given sentence-cache size. */
    external fun createService(cacheSize: Long): Long

    external fun destroyService(handle: Long)

    /**
     * Load a marian/Bergamot model. Layer counts MUST match the model — Mozilla's
     * exported models ship no model.yml, and base-memory is 6 encoder / 4 decoder
     * (the caller auto-detects from the model file). [splitMode] empty = engine
     * default. Returns 0 on failure.
     *
     * [targetVocabPath] is the separate target-side vocabulary for split-vocab
     * models (en→CJK ship srcvocab+trgvocab); pass "" for single-vocab models,
     * where the source vocabulary is shared for decoding.
     */
    external fun loadModel(
        modelPath: String,
        vocabPath: String,
        targetVocabPath: String,
        shortlistPath: String,
        encoderLayers: Int,
        decoderLayers: Int,
        feedForwardDepth: Int,
        numHeads: Int,
        splitMode: String,
    ): Long

    external fun destroyModel(handle: Long)

    /** Single-hop translate [text] through [modelHandle]. Null on failure. */
    external fun translate(serviceHandle: Long, modelHandle: Long, text: String): String?

    /**
     * Pivot translate in one native call: first model (src→en) then second
     * (en→tgt), intermediate English hidden, alignments remapped internally.
     */
    external fun pivot(
        serviceHandle: Long,
        firstModelHandle: Long,
        secondModelHandle: Long,
        text: String,
    ): String?
}
