package com.playtranslate.ui

import com.playtranslate.language.TokenSpan
import com.playtranslate.model.DictionaryEntry
import com.playtranslate.model.DictionaryResponse
import com.playtranslate.model.Headword
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The lens's secondary sections: [SourceWordLookup.ResolvedAt.secondaries]'s
 * order, [SourceWordLookup.distinctAlternatives]'s entry identity, which
 * the tap path and the drag lens share, [SourceWordLookup.assemble]'s
 * placement of resolved keys, [SourceWordLookup.mergeLoaded]'s placement of
 * loaded ones, and [SourceWordLookup.loadMoreFor]'s row.
 */
class LensSecondariesTest {

    private fun resolved(word: String, reading: String?, packId: Long?) = SourceWordLookup.Resolved(
        word = word,
        reading = reading,
        surface = null,
        lookupForm = word,
        lookupReading = reading,
        label = null,
        data = WordDefinitionData(word, reading, emptyList(), freqScore = 0, isCommon = false),
        entry = DictionaryEntry(
            slug = word, packId = packId, isCommon = null, tags = emptyList(), jlpt = emptyList(),
            headwords = listOf(Headword(written = word, reading = reading)), senses = emptyList(),
        ),
    )

    private val word = resolved("弾ける", "はじける", 1419380L)
    private val phrase = resolved("一番", "いちばん", 1L)
    private val member = resolved("気", "き", 2L)
    private val ma = resolved("間", "ま", 3L)
    private val hiku = resolved("弾く", "ひく", 1419370L)
    private val hajiku = resolved("弾く", "はじく", 1419360L)

    @Test
    fun `a containing phrase is the only secondary`() {
        val at = SourceWordLookup.ResolvedAt(word, phrase = phrase, members = listOf(member), alternatives = listOf(hiku))
        assertEquals(listOf(phrase), at.secondaries())
    }

    @Test
    fun `members come before alternatives`() {
        val at = SourceWordLookup.ResolvedAt(word, members = listOf(member), alternatives = listOf(hiku, hajiku))
        assertEquals(listOf(member, hiku, hajiku), at.secondaries())
    }

    private fun distinct(alternatives: List<SourceWordLookup.Resolved>, primaryIds: Set<Long>) =
        SourceWordLookup.distinctAlternatives(
            alternatives, primaryIds,
            packIdOf = { it.entry?.packId },
            fallbackKeyOf = { it.word to it.reading },
        )

    @Test
    fun `two homographs sharing a headword are two alternatives`() {
        assertEquals(listOf(hiku, hajiku), distinct(listOf(hiku, hajiku), primaryIds = setOf(1419380L)))
    }

    @Test
    fun `an alternative landing on a primary entry or on an entry already kept is dropped`() {
        assertEquals(listOf(hajiku), distinct(listOf(hiku, hajiku, hajiku), primaryIds = setOf(1419380L, 1419370L)))
    }

    @Test
    fun `an alternative without a pack id is identified by its word and reading`() {
        val importedA = resolved("弾く", "ひく", null)
        val importedB = resolved("弾く", "はじく", null)
        assertEquals(
            listOf(importedA, importedB),
            distinct(listOf(importedA, importedB, importedA), primaryIds = emptySet()),
        )
    }

    private fun key(kind: SecondaryKind, form: String, needsMt: Boolean = false) =
        SecondaryKey(kind, TokenSpan(form, form), DictionaryResponse(emptyList()), needsMt)

    @Test
    fun `assemble places each section by its key's kind and captions only the alternatives`() {
        val keys = listOf(
            key(SecondaryKind.MEMBER, "気"), key(SecondaryKind.ALTERNATIVE, "弾く"),
            key(SecondaryKind.MEMBER, "間"), key(SecondaryKind.ALTERNATIVE, "弾く"),
        )
        val at = SourceWordLookup.assemble(word, keys, listOf(member, hiku, ma, hajiku), caption = "Also matches")
        assertEquals(word, at.word)
        assertNull(at.phrase)
        assertEquals(listOf(member, ma), at.members)
        assertEquals(
            listOf(hiku.copy(caption = "Also matches"), hajiku.copy(caption = "Also matches")),
            at.alternatives,
        )
        assertEquals(keys, at.keys)
        assertEquals(emptyList<SecondaryKey>(), at.pending)
    }

    @Test
    fun `assemble makes a phrase key's section the phrase, uncaptioned`() {
        val at = SourceWordLookup.assemble(
            word, listOf(key(SecondaryKind.PHRASE, "一番")), listOf(phrase), caption = "Also matches",
        )
        assertEquals(phrase, at.phrase)
        assertEquals(listOf(phrase), at.secondaries())
    }

    @Test
    fun `assemble holds the keys of unresolved slots as pending`() {
        val kiKey = key(SecondaryKind.MEMBER, "気")
        val hajikuKey = key(SecondaryKind.ALTERNATIVE, "弾く", needsMt = true)
        val at = SourceWordLookup.assemble(word, listOf(kiKey, hajikuKey), listOf(member, null), caption = "Also matches")
        assertEquals(listOf(hajikuKey), at.pending)
        assertEquals(listOf(kiKey, hajikuKey), at.keys)
        assertEquals(listOf(member), at.secondaries())
    }

    @Test
    fun `a load places each held-back section in its key's place`() {
        // 気 and 弾く read はじく would machine-translate; 間 and 弾く read
        // ひく would not. Appending the load would read 間 before 気.
        val kiKey = key(SecondaryKind.MEMBER, "気", needsMt = true)
        val maKey = key(SecondaryKind.MEMBER, "間")
        val hikuKey = key(SecondaryKind.ALTERNATIVE, "弾く")
        val hajikuKey = key(SecondaryKind.ALTERNATIVE, "弾く", needsMt = true)
        val keys = listOf(kiKey, maKey, hikuKey, hajikuKey)
        val captioned = { r: SourceWordLookup.Resolved -> r.copy(caption = "Also matches") }

        val before = SourceWordLookup.assemble(word, keys, listOf(null, ma, hiku, null), caption = "Also matches")
        assertEquals(listOf(ma), before.members)
        assertEquals(listOf(captioned(hiku)), before.alternatives)
        assertEquals(listOf(kiKey, hajikuKey), before.pending)

        val after = SourceWordLookup.mergeLoaded(before, listOf(member, hajiku), caption = "Also matches")
        assertEquals(listOf(member, ma), after.members)
        assertEquals(listOf(captioned(hiku), captioned(hajiku)), after.alternatives)
        assertEquals(emptyList<SecondaryKey>(), after.pending)
        assertEquals(keys, after.keys)
        assertEquals(listOf(member, ma, captioned(hiku), captioned(hajiku)), after.secondaries())
        assertNull(SourceWordLookup.loadMoreFor(after, loading = false))
    }

    @Test
    fun `no load-more row when nothing is pending`() {
        val at = SourceWordLookup.ResolvedAt(word, members = listOf(member))
        assertNull(SourceWordLookup.loadMoreFor(at, loading = false))
        assertNull(SourceWordLookup.loadMoreFor(at, loading = true))
    }

    @Test
    fun `the load-more row counts the pending sections and carries the loading flag`() {
        val at = SourceWordLookup.ResolvedAt(
            word, members = listOf(member),
            pending = listOf(
                key(SecondaryKind.MEMBER, "間", needsMt = true), key(SecondaryKind.ALTERNATIVE, "弾く", needsMt = true),
            ),
        )
        assertEquals(LensLoadMore(2, loading = false), SourceWordLookup.loadMoreFor(at, loading = false))
        assertEquals(LensLoadMore(2, loading = true), SourceWordLookup.loadMoreFor(at, loading = true))
    }
}
