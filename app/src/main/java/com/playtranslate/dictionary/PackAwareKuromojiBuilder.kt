package com.playtranslate.dictionary

import com.atilika.kuromoji.dict.CharacterDefinitions
import com.atilika.kuromoji.dict.ConnectionCosts
import com.atilika.kuromoji.dict.InsertedDictionary
import com.atilika.kuromoji.dict.TokenInfoDictionary
import com.atilika.kuromoji.dict.UnknownDictionary
import com.atilika.kuromoji.ipadic.Tokenizer
import com.atilika.kuromoji.trie.DoubleArrayTrie
import java.io.File

/**
 * Subclass of Kuromoji's [Tokenizer.Builder] that loads IPADIC bin files
 * from [packDir] (the JA source pack's `tokenizer/` directory) instead of
 * the classpath. Avoids forking Kuromoji — every field required for dict
 * construction is either protected (accessible to subclasses) or set up
 * by super's own body.
 *
 * Strategy in [loadDictionaries]:
 *  1. Call `super.loadDictionaries()` to let upstream populate `penalties`
 *     from its private `kanjiPenalty`/etc. fields — we can't access those
 *     from a different package.
 *  2. Upstream's body then tries to load dict files via a classpath
 *     [com.atilika.kuromoji.util.SimpleResourceResolver]. With the APK
 *     resource-stripping in place, those files aren't on the classpath and
 *     upstream throws a `RuntimeException("Could not load dictionaries.")`.
 *     We catch exactly that failure.
 *  3. We then swap `resolver` to a [PackResourceResolver] pointed at our
 *     pack dir, re-run the exact dict-factory sequence (protected fields
 *     `doubleArrayTrie`, `connectionCosts`, etc. are writable from here),
 *     and the build completes cleanly against pack-backed data.
 *
 * If the APK has NOT been resource-stripped yet (early rollout / dev build),
 * step 2 succeeds, super populates fields with classpath-loaded data, and
 * we skip the redo path. In that case the pack dir is unused — the tokenizer
 * just works from the APK's bundled resources.
 */
internal class PackAwareKuromojiBuilder(private val packDir: File) : Tokenizer.Builder() {
    override fun loadDictionaries() {
        try {
            super.loadDictionaries()
            // If super succeeded, classpath bins are still present (APK not
            // yet stripped). Dict fields are populated from classpath —
            // nothing more to do. Pack dir is silently unused.
            return
        } catch (classpathMiss: RuntimeException) {
            // Expected once the APK strips the bundled .bin files: super's
            // dict factories couldn't find the resources via classpath.
            // Re-run with our pack resolver.
        }
        val r = PackResourceResolver(packDir)
        this.resolver = r
        try {
            this.doubleArrayTrie = DoubleArrayTrie.newInstance(r)
            this.connectionCosts = ConnectionCosts.newInstance(r)
            this.tokenInfoDictionary = TokenInfoDictionary.newInstance(r)
            this.characterDefinitions = CharacterDefinitions.newInstance(r)
            this.unknownDictionary = UnknownDictionary.newInstance(
                r, characterDefinitions, totalFeatures
            )
            this.insertedDictionary = InsertedDictionary(totalFeatures)
        } catch (e: Exception) {
            throw RuntimeException(
                "Could not load Kuromoji dictionaries from pack ${packDir.absolutePath}",
                e
            )
        }
    }
}
