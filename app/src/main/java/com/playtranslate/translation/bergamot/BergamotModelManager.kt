package com.playtranslate.translation.bergamot

import android.content.Context
import com.playtranslate.language.LanguagePackCatalog
import com.playtranslate.language.LanguagePackCatalogLoader
import java.io.File

/** On-disk files + architecture for one translation direction's model. */
data class BergamotModelFiles(
    val direction: String,
    val modelPath: String,
    val vocabPath: String,
    val shortlistPath: String,
    val encoderLayers: Int,
    val decoderLayers: Int,
    val feedForwardDepth: Int,
    val numHeads: Int,
)

/**
 * Resolves Bergamot translation directions for a (source, target) pair using
 * the English-pivot rule, and answers which directions are *supported* (have a
 * catalog entry) and *installed* (model files present on disk).
 *
 * Mozilla trains only xx↔English, so a non-English↔non-English pair is a
 * two-hop pivot through English (the engine performs both hops natively). The
 * set of supported directions is read from the langpack catalog — every
 * `engine` entry whose id is `bergamot-<src>-<tgt>`.
 */
class BergamotModelManager(private val context: Context) {

    private val catalog: LanguagePackCatalog by lazy { LanguagePackCatalogLoader.load(context) }

    /** Direction keys we have catalog entries for, e.g. "ja-en", "en-es". */
    private val availableDirections: Set<String> by lazy {
        catalog.packs.keys
            .filter { it.startsWith(CATALOG_PREFIX) }
            .map { it.removePrefix(CATALOG_PREFIX) }
            .toSet()
    }

    /** Map a PlayTranslate translation code to a Mozilla model language code. */
    private fun mozilla(code: String): String = when (val c = code.lowercase()) {
        "zh-hant", "zh_hant" -> "zh_hant"
        else -> c.substringBefore('-') // strip region subtag (e.g. pt-BR -> pt)
    }

    /**
     * The model directions needed to translate [source]→[target], or null if
     * unsupported (a required xx↔en model has no catalog entry, or source==target).
     * One element = single hop; two = English pivot.
     */
    fun requiredDirections(source: String, target: String): List<String>? {
        val s = mozilla(source)
        val t = mozilla(target)
        if (s == t) return null
        val dirs = when {
            s == "en" -> listOf("en-$t")
            t == "en" -> listOf("$s-en")
            else -> listOf("$s-en", "en-$t")
        }
        return if (dirs.all { it in availableDirections }) dirs else null
    }

    fun supportsPair(source: String, target: String): Boolean =
        requiredDirections(source, target) != null

    fun isInstalled(source: String, target: String): Boolean {
        val dirs = requiredDirections(source, target) ?: return false
        return dirs.all { isDirectionInstalled(it) }
    }

    /** On-disk dir for a direction's model files. */
    fun directionDir(direction: String): File =
        File(context.noBackupFilesDir, "models/$CATALOG_PREFIX$direction")

    private fun isDirectionInstalled(direction: String): Boolean =
        File(directionDir(direction), SENTINEL).exists()

    /** Resolve files + architecture for a direction, or null if not fully installed. */
    fun filesFor(direction: String): BergamotModelFiles? {
        val dir = directionDir(direction)
        if (!File(dir, SENTINEL).exists()) return null
        val model = dir.listFiles { f -> MODEL_RE.matches(f.name) }?.firstOrNull() ?: return null
        val shortlist = dir.listFiles { f -> SHORTLIST_RE.matches(f.name) }?.firstOrNull() ?: return null
        // Single shared vocab for the pairs we ship (base-memory European + ja/ko-en).
        // CJK en→{ja,zh} use split src/trg vocabs — out of v1 scope; if present we
        // pass the source vocab.
        val vocab = dir.listFiles { f -> VOCAB_RE.matches(f.name) }?.firstOrNull()
            ?: dir.listFiles { f -> SRCVOCAB_RE.matches(f.name) }?.firstOrNull()
            ?: return null
        return BergamotModelFiles(
            direction = direction,
            modelPath = model.absolutePath,
            vocabPath = vocab.absolutePath,
            shortlistPath = shortlist.absolutePath,
            // Mozilla "base-memory" architecture: verified 6 encoder / 4 decoder
            // for every base-memory pair sampled (ffn 2, heads 8). We only ship
            // base-memory; the models carry no model.yml, so these must be set
            // explicitly or the engine mis-loads layers and emits fluent garbage.
            encoderLayers = BASE_MEMORY_ENCODER_LAYERS,
            decoderLayers = BASE_MEMORY_DECODER_LAYERS,
            feedForwardDepth = BASE_MEMORY_FFN_DEPTH,
            numHeads = BASE_MEMORY_NUM_HEADS,
        )
    }

    companion object {
        const val CATALOG_PREFIX = "bergamot-"
        const val SENTINEL = ".sentinel"

        const val BASE_MEMORY_ENCODER_LAYERS = 6
        const val BASE_MEMORY_DECODER_LAYERS = 4
        const val BASE_MEMORY_FFN_DEPTH = 2
        const val BASE_MEMORY_NUM_HEADS = 8

        private val MODEL_RE = Regex("""model\..*\.intgemm\.alphas\.bin""")
        private val VOCAB_RE = Regex("""vocab\..*\.spm""")
        private val SRCVOCAB_RE = Regex("""srcvocab\..*\.spm""")
        private val SHORTLIST_RE = Regex("""lex\..*\.bin""")
    }
}
