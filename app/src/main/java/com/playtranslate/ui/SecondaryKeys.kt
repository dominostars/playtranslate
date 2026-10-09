package com.playtranslate.ui

import com.playtranslate.language.DefinitionResolver
import com.playtranslate.language.SourceLanguageEngine
import com.playtranslate.language.TokenSpan
import com.playtranslate.model.DictionaryEntry
import com.playtranslate.model.DictionaryResponse
import com.playtranslate.model.HeadwordDisplay
import com.playtranslate.model.headwordDisplay
import com.playtranslate.model.selectHeadword
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** What a lens secondary section is: the multi-word expression containing
 *  the looked-up word ([PHRASE]), a member word of the fused expression the
 *  looked-up word is ([MEMBER]), or another dictionary entry the looked-up
 *  word could be ([ALTERNATIVE]). */
enum class SecondaryKind { PHRASE, MEMBER, ALTERNATIVE }

/** One secondary section of a looked-up word, decided before anything is
 *  translated: its [kind], the [span] its section resolves under (a phrase
 *  key as TokenSpan(key, key); a member or alternative span as the engine
 *  gave it), the dictionary [response] that lookup returned (so the
 *  resolution does not repeat it), and [needsMt], whether resolving it
 *  would machine-translate its definitions. */
data class SecondaryKey(
    val kind: SecondaryKind,
    val span: TokenSpan,
    val response: DictionaryResponse,
    val needsMt: Boolean,
)

/** A looked-up word's secondary sections, [all] of them in section order
 *  (the phrase alone, else the members then the alternatives), split by
 *  cost: [eager] resolve without a translator, [pending] would
 *  machine-translate. Both keep section order. */
data class SecondaryKeys(val all: List<SecondaryKey>) {
    val eager: List<SecondaryKey> = all.filterNot { it.needsMt }
    val pending: List<SecondaryKey> = all.filter { it.needsMt }
}

/**
 * Decides the secondary sections of a looked-up word with dictionary
 * lookups only (nothing is translated here); both lenses
 * ([SourceWordLookup.resolveAt] and the drag lens) build their sections from
 * this one list, one key per section.
 *
 * Each candidate span is looked up with [SourceLanguageEngine.lookup] under
 * its lookup form and reading (an empty reading counts as none) and dropped
 * when the lookup lands no entry. Its displayed word is the headword its
 * section shows: the first entry's [headwordDisplay] of [selectHeadword]
 * with the lookup form as the surface. A [phraseKey] makes the containing
 * phrase the only candidate, and [memberSpans] and [alternativeSpans] are
 * then not looked up. Otherwise the members come first, one per displayed
 * word (the first wins), then the alternatives, one per entry as
 * [SourceWordLookup.distinctAlternatives] identifies them.
 *
 * A phrase or member that lands on the looked-up word itself is dropped:
 * its first entry's pack id or slug is that of one of [primaryEntries], or
 * its displayed word is [primaryWord]. An alternative is told from the word by
 * its entry alone (the pack-id rule of [SourceWordLookup.distinctAlternatives]),
 * because a homograph shares the word's headword and slug: tapping 弾く read
 * ひく still offers 弾く read はじく.
 *
 * Each kept key is probed with [DefinitionResolver.needsMachineTranslation]
 * under the lookup form and reading its section resolves with. The lookups
 * and probes run on [Dispatchers.IO].
 */
suspend fun collectSecondaryKeys(
    engine: SourceLanguageEngine,
    resolver: DefinitionResolver,
    primaryEntries: List<DictionaryEntry>,
    primaryWord: String,
    phraseKey: String?,
    memberSpans: List<TokenSpan>,
    alternativeSpans: List<TokenSpan>,
): SecondaryKeys = withContext(Dispatchers.IO) {
    val primaryIds = primaryEntries.mapNotNullTo(mutableSetOf()) { it.packId }
    val primarySlugs = primaryEntries.mapTo(mutableSetOf()) { it.slug }
    val isPrimary = { found: Found ->
        found.entry.packId?.let { it in primaryIds } == true ||
            found.entry.slug in primarySlugs ||
            found.display.written == primaryWord
    }
    val kept = if (phraseKey != null) {
        listOfNotNull(lookUp(engine, SecondaryKind.PHRASE, TokenSpan(phraseKey, phraseKey)))
            .filterNot(isPrimary)
    } else {
        memberSpans.mapNotNull { lookUp(engine, SecondaryKind.MEMBER, it) }
            .filterNot(isPrimary)
            .distinctBy { it.display.written } +
            SourceWordLookup.distinctAlternatives(
                alternativeSpans.mapNotNull { lookUp(engine, SecondaryKind.ALTERNATIVE, it) },
                primaryIds,
                packIdOf = { it.entry.packId },
                fallbackKeyOf = { it.display.written to it.display.reading },
            )
    }
    SecondaryKeys(
        kept.map {
            SecondaryKey(
                it.kind, it.span, it.response,
                needsMt = resolver.needsMachineTranslation(it.response, it.span.lookupForm, it.reading),
            )
        },
    )
}

/** A candidate span whose lookup landed an entry: [reading] is the span's
 *  reading with an empty one as none, [display] the headword its section
 *  shows. */
private class Found(
    val kind: SecondaryKind,
    val span: TokenSpan,
    val reading: String?,
    val response: DictionaryResponse,
    val display: HeadwordDisplay,
) {
    val entry: DictionaryEntry get() = response.entries.first()
}

/** [span] looked up as a [kind] candidate; null when the lookup lands no entry. */
private suspend fun lookUp(engine: SourceLanguageEngine, kind: SecondaryKind, span: TokenSpan): Found? {
    val reading = span.reading?.ifEmpty { null }
    val response = engine.lookup(span.lookupForm, reading) ?: return null
    val entry = response.entries.firstOrNull() ?: return null
    val display = entry.headwordDisplay(entry.selectHeadword(span.lookupForm, span.lookupForm, reading), span.lookupForm)
    return Found(kind, span, reading, response, display)
}
