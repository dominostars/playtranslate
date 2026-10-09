package com.playtranslate.ui

import com.playtranslate.language.TokenSpan
import com.playtranslate.model.DictionaryResponse
import com.playtranslate.ui.SecondaryKind.ALTERNATIVE
import com.playtranslate.ui.SecondaryKind.MEMBER
import com.playtranslate.ui.SecondaryKind.PHRASE
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [sectionsByKind] and [placeLoaded], the placement both lenses share: a
 * word's secondary sections stay in their keys' order within each kind,
 * before and after the held-back ones load. The sections are plain strings
 * here; the tap lens places [SourceWordLookup.Resolved] and the drag lens
 * its popup data the same way.
 */
class SectionOrderTest {

    private fun key(kind: SecondaryKind, form: String, needsMt: Boolean) =
        SecondaryKey(kind, TokenSpan(form, form), DictionaryResponse(emptyList()), needsMt)

    // 気になる with a gloss pack serving なる and not 気, and two
    // alternatives of which only the first is served: the first member and
    // the second alternative are held back.
    private val m1 = key(MEMBER, "気", needsMt = true)
    private val m2 = key(MEMBER, "なる", needsMt = false)
    private val a1 = key(ALTERNATIVE, "弾く", needsMt = false)
    private val a2 = key(ALTERNATIVE, "弾く", needsMt = true)
    private val mixed = listOf(m1, m2, a1, a2)
    private val shownBeforeLoad = Sections(null, listOf("m2"), listOf("a1"))

    @Test
    fun `sectionsByKind places each slot by its key's kind in key order and skips null slots`() {
        assertEquals(shownBeforeLoad, sectionsByKind(mixed, listOf(null, "m2", "a1", null)))
        assertEquals(
            Sections(null, listOf("m1", "m2"), listOf("a1", "a2")),
            sectionsByKind(listOf(a1, m1, a2, m2), listOf("a1", "m1", "a2", "m2")),
        )
    }

    @Test
    fun `sectionsByKind makes a phrase key's section the phrase`() {
        val phrase = key(PHRASE, "一番うまく", needsMt = false)
        assertEquals(Sections("phrase", emptyList(), emptyList()), sectionsByKind(listOf(phrase), listOf("phrase")))
    }

    @Test
    fun `placeLoaded puts each loaded section in its key's place, not after its kind's shown ones`() {
        val slots = placeLoaded(mixed, shownBeforeLoad, pendingKeys = listOf(m1, a2), loaded = listOf("m1", "a2"))
        assertEquals(listOf("m1", "m2", "a1", "a2"), slots)
        assertEquals(Sections(null, listOf("m1", "m2"), listOf("a1", "a2")), sectionsByKind(mixed, slots))
    }

    @Test
    fun `placeLoaded loads a held-back phrase and keeps a shown one`() {
        val phrase = key(PHRASE, "一番うまく", needsMt = true)
        val none = Sections<String>(null, emptyList(), emptyList())
        assertEquals(listOf("phrase"), placeLoaded(listOf(phrase), none, listOf(phrase), listOf("phrase")))
        assertEquals(
            listOf("phrase"),
            placeLoaded(listOf(phrase), Sections("phrase", emptyList(), emptyList()), emptyList(), emptyList<String>()),
        )
    }

    @Test
    fun `a pending key is told by identity, not by an equal key elsewhere in the list`() {
        // Equal keys do not come out of collectSecondaryKeys (members are
        // distinct by displayed word, alternatives by entry); this pins the
        // helper's contract: only the held-back object loads.
        val shown = key(ALTERNATIVE, "弾く", needsMt = true)
        val held = key(ALTERNATIVE, "弾く", needsMt = true)
        assertEquals(shown, held)
        assertEquals(
            listOf("shown", "held"),
            placeLoaded(listOf(shown, held), Sections(null, emptyList(), listOf("shown")), listOf(held), listOf("held")),
        )
    }

    @Test
    fun `a null loaded item leaves its key's slot null and places nothing`() {
        val slots = placeLoaded(mixed, shownBeforeLoad, pendingKeys = listOf(m1, a2), loaded = listOf(null, "a2"))
        assertEquals(listOf(null, "m2", "a1", "a2"), slots)
        assertEquals(Sections(null, listOf("m2"), listOf("a1", "a2")), sectionsByKind(mixed, slots))
    }
}
