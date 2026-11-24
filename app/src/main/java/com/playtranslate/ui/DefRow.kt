package com.playtranslate.ui

import com.playtranslate.model.aroundBuiltIn

/**
 * One row of a definitions document ([DefinitionDocument]): what a surface
 * maps its own data to, and the only thing the HTML renderer ([DefRowHtml])
 * reads. Immutable and compared by value, which is what lets a rebind patch
 * only the rows that changed.
 *
 * [id] names the row across rebinds of one document: the same word keeps the
 * same id, so a hide, a deck badge or a spinner is a patch of that row and
 * never a new list.
 */
internal sealed class DefRow {
    abstract val id: String

    /**
     * A word with its definitions: the ONE word row, whichever surface
     * lists it (the Words card, the dictionary search, a word page's
     * sections, the lens).
     *
     * [opens] draws the chevron and makes the row itself a tap target
     * ([DefAction.OPEN]). [speak] and [trailing] are the row's two buttons,
     * null for none; [trailingHolds] makes a press held on the trailing
     * button an action of its own ([DefAction.hold] of
     * [DefAction.TRAILING]). A [Trailing.SHOW] row is a hidden word's stub:
     * the word alone, muted, on the sunk band, with the eye that shows it
     * again; its reading, its definitions and its speak button are not
     * drawn.
     *
     * Each imported dictionary's definitions stand in full under the
     * dictionary's title, which takes a tap of its own
     * ([DefAction.dictionary]) and shuts or opens that dictionary's block in
     * this row: [shutDictionaries] names the ones shut
     * ([dictionaryKey]), of which only the title is drawn. Beside an
     * imported dictionary the pack's own definitions are a dictionary's
     * block like the others, under a title of their own
     * ([DefAction.OWN_DICTIONARY], [OWN_DICTIONARY_KEY]); alone in the row
     * they have no title and nothing shuts them.
     *
     * [full] is whether the definitions' links are live. False, for a list
     * whose row taps through to the word's own page: they are inert, so a
     * tap anywhere but a button or a dictionary's title is the row's. True,
     * where the row is where the word is read (the lens): there a
     * dictionary's link to another word is followed too
     * ([DefAction.reference]) when the row [opens], since such a link opens
     * its word as the row opens its own.
     */
    data class Word(
        override val id: String,
        val data: WordDefinitionData,
        val opens: Boolean = true,
        val speak: Speak? = null,
        val trailing: Trailing? = null,
        val trailingHolds: Boolean = false,
        val full: Boolean = false,
        /** A warning line over the definitions ("Machine translated"). */
        val label: String? = null,
        /** What stands in for the definitions when the word has none (a
         *  looked-up word with no entry stays on screen). */
        val emptyText: String? = null,
        val shutDictionaries: Set<String> = emptySet(),
        /** The row's "explain in this sentence" button, the first in its
         *  head ([DefAction.EXPLAIN]); null for a row with none (a word
         *  with no sentence behind it). */
        val explain: Explain? = null,
    ) : DefRow()

    /**
     * A word row's question of how its word is used in the sentence it was
     * read in, as the row draws it. With nothing asked it is the button
     * alone. While the question is out ([busy]) the button is a spinner and
     * nothing else moves. With something to read ([answer]) the button is
     * lit and the answer stands under the row's head, over its definitions.
     * A hidden word's stub draws neither, and keeps the button's place.
     */
    data class Explain(val busy: Boolean = false, val answer: Answer? = null)

    /**
     * What came of a row's question, in the words every surface that draws
     * a question uses ([askDisplay]): the [title] over it, with the ✕ that
     * puts it away at the line's end ([DefAction.EXPLAIN_CLOSE]); the
     * [text], [muted] where it is the app's word on the question and not an
     * answer (a failure, a question the service does not take); the
     * [footer] under an answer (who gave it, and that it was cut short);
     * and Try again where [retry] ([DefAction.EXPLAIN_RETRY]).
     */
    data class Answer(
        val title: String,
        val text: String,
        val muted: Boolean = false,
        val footer: String? = null,
        val retry: Boolean = false,
    )

    /**
     * A one-line action at the end of a list: the lens's "Load more (n)",
     * or "Looking up…" while its sections load. A tap fires
     * [DefAction.MORE]; what it does, and whether it does anything just
     * now, is the host's to say.
     */
    data class More(override val id: String, val text: String) : DefRow()

    /**
     * Imported dictionaries' definitions of a word's own page: one
     * labelled block per dictionary, in full, with live links. A page has
     * up to two of these rows, the dictionaries the user's order puts above
     * the built-in dictionary's senses and the ones it puts below them
     * ([importedRowsAround]). Under a [curation] (the Anki word sheet) the
     * definitions taken off the card are left out and the rest carry a ✕.
     *
     * [links] is whether a dictionary's links to its other words are
     * followed here ([DefAction.reference]): on the word's page, where the
     * host opens the word a link names. False, and under a curation, such a
     * link is its text alone.
     *
     * [words] is whether a tap on the definitions' text is reported with
     * the text it fell in ([DefinitionDocument.TextTap]), for a host that
     * looks the tapped word up: the word's page again. Never under a
     * curation, where a tap on a definition does nothing.
     */
    data class Imported(
        override val id: String,
        val groups: List<com.playtranslate.model.ImportedSenseGroup>,
        val look: SenseLook = SenseLook.PAGE,
        val curation: Curation? = null,
        val links: Boolean = false,
        val words: Boolean = false,
    ) : DefRow()

    /**
     * Per-card removal inside an imported block. [removed] holds the
     * definitions already taken off, each named by its flat index across
     * ALL the word's imported groups in order: the index it had before any
     * removal, which is what its ✕ reports ([DefAction.removeItem]), so a
     * tap that lands late still names the definition it was drawn on.
     * [removable] says whether the ones left offer a ✕ at all. [base] is
     * the flat index of the block's own first definition: 0 for a block
     * that starts the word's groups, and the number of definitions in the
     * groups before it for the block below the built-in dictionary's
     * senses, so both blocks name a definition in the one index space.
     */
    data class Curation(val removed: Set<Int>, val removable: Boolean, val base: Int = 0)

    /**
     * One sense of a word's own page: its [number] in the left column (null
     * when the word has a single sense), the part-of-speech eyebrow ([pos],
     * as it should read, blank for none; [posColor] overrides its colour),
     * the [gloss], the register note ([misc]) and its [examples].
     * [removable] draws the ✕ that takes the sense off a card
     * ([DefAction.REMOVE]). [words] is whether a tap on its text is
     * reported with the text it fell in, as [Imported.words]: an example's
     * sentence is then marked as written in the source language.
     */
    data class Sense(
        override val id: String,
        val number: Int?,
        val pos: String,
        val gloss: String,
        val misc: String? = null,
        val examples: List<Example> = emptyList(),
        val posColor: Int? = null,
        val look: SenseLook = SenseLook.PAGE,
        val removable: Boolean = false,
        val words: Boolean = false,
    ) : DefRow()

    /** An example sentence under a sense: the source line and, once it is
     *  known, its translation ([italic] where the target's script has an
     *  italic that reads as one). A [removeIndex] draws the example's ✕,
     *  which reports that index ([DefAction.removeItem]): the example's
     *  place among its sense's examples before any was removed. */
    data class Example(
        val text: String,
        val translation: String,
        val italic: Boolean,
        val removeIndex: Int? = null,
    )

    /**
     * A word of the Anki sentence sheet's list. A tap on the row makes the
     * word the card's target, or takes that back ([DefAction.TARGET]); its
     * eye hides the word everywhere, and on a [hidden] word's stub shows it
     * again ([DefAction.TRAILING]). A [target] draws the accent wash and
     * carries its [audio] row. The body is the imported dictionaries'
     * definitions ([importedGroups]) when they hold structured content, else
     * the first line of [meaning]; and the first line of [meaning] too
     * where the built-in dictionary leads the word's definitions (the
     * first group is marked
     * [com.playtranslate.model.ImportedSenseGroup.belowBuiltIn]), since the
     * card then opens with the built-in dictionary's and that line is its
     * first sense. A stub is the word alone on the sunk band, inert but
     * for its eye.
     */
    data class SheetWord(
        override val id: String,
        val word: String,
        val reading: String,
        val pitch: List<Int>,
        /** The frequency stars as they read, blank for none. */
        val stars: String,
        val target: Boolean,
        val hidden: Boolean,
        val meaning: String,
        val importedGroups: List<com.playtranslate.model.ImportedSenseGroup>,
        val audio: Audio? = null,
    ) : DefRow()

    /**
     * A target word's audio row: whether its audio goes on the card ([on],
     * the row's switch, [DefAction.AUDIO]), where its preview stands
     * ([play], [DefAction.SPEAK]) and the audio source's name on the pill
     * that opens the picker ([source], blank until it is known;
     * [DefAction.SOURCE]).
     */
    data class Audio(val on: Boolean, val play: Play, val source: String)

    enum class Play { IDLE, LOADING, PLAYING }

    /** How a sense reads. [PAGE] is a word's own page. [SHEET] is the Anki
     *  word sheet's card, a notch denser: a smaller gloss, no minimum row
     *  height, examples in full ink, dictionary names uncoloured. */
    enum class SenseLook { PAGE, SHEET }

    enum class Speak { IDLE, LOADING }

    /** The second button: add to Anki, hide the word (shown on a full row),
     *  or show it again (on a stub). */
    enum class Trailing { ANKI, HIDE, SHOW }
}

/** What a tap in a document row asked for, as the page reports it. */
internal object DefAction {
    const val OPEN = "open"
    const val SPEAK = "speak"
    const val TRAILING = "trailing"
    const val MORE = "more"

    /** A word row's explain button ([DefRow.Word.explain]): ask, stop a
     *  question that is out, or put an answer away. */
    const val EXPLAIN = "explain"

    /** The ✕ of an explain answer, and its Try again. */
    const val EXPLAIN_CLOSE = "explain:close"
    const val EXPLAIN_RETRY = "explain:retry"

    /**
     * Where a controller's cursor goes when the element it is on is gone
     * from its row after a press of it: the action of the same row that
     * takes its place, or null to leave the cursor as every cursor already
     * treats a target that is gone.
     *
     * Only an explain answer's own buttons have one, the row's explain
     * button: the ✕ takes the answer away and Try again folds the failure
     * while it asks, each with itself, and the button they leave behind is
     * the one that brought them. Nothing else moves by this, and must not:
     * a cursor that walked on by itself from a ✕ that removes something
     * (an Anki sheet's example, its sense) would stand on another ✕, and
     * the next press would remove a second thing.
     */
    fun fallback(act: String): String? = when (act) {
        EXPLAIN_CLOSE, EXPLAIN_RETRY -> EXPLAIN
        else -> null
    }

    /** [act] when its element was held, not tapped (an element that asks
     *  for it: a row's trailing button with [DefRow.Word.trailingHolds]). */
    fun hold(act: String): String = "$act:hold"

    /** A sheet word's row: toggle it as the card's target. */
    const val TARGET = "target"

    /** A target's audio row: its switch, and the pill that picks its source. */
    const val AUDIO = "audio"
    const val SOURCE = "source"

    /** The ✕ of a row: take the row itself off the card. */
    const val REMOVE = "remove"

    /** The ✕ of item [n] inside a row (an imported definition, an example). */
    fun removeItem(n: Int): String = "$REMOVE:$n"

    /** The item a [removeItem] action names; null for any other action. */
    fun removedItem(action: String): Int? =
        if (action.startsWith("$REMOVE:")) action.substring(REMOVE.length + 1).toIntOrNull() else null

    private const val DICTIONARY = "dict"

    /** The title of a word row's imported dictionary number [n], counted
     *  over the row's dictionaries in drawn order: shut or open its block. */
    fun dictionary(n: Int): String = "$DICTIONARY:$n"

    /** The dictionary a [dictionary] action names; null for any other action. */
    fun dictionaryIndex(action: String): Int? =
        if (action.startsWith("$DICTIONARY:")) action.substring(DICTIONARY.length + 1).toIntOrNull() else null

    /** The title of the pack's own definitions in a word row that also
     *  draws an imported dictionary: shut or open them. */
    const val OWN_DICTIONARY = "$DICTIONARY:own"

    private const val REFERENCE = "ref"

    /** The word a dictionary's link points at ([reference]): its [term] and,
     *  where the link names one, the [reading] that tells it from the other
     *  words written the same way. */
    data class Reference(val term: String, val reading: String?)

    /**
     * A dictionary's link to another word (Yomitan's `?query=…`), where the
     * surface follows them: open that word. [n] is the link's place among
     * its row's links, which is all that tells two links to one word apart:
     * a target is found by its row and its action, in the page
     * (`applyCursor`) and in every walker of its targets. The term and the
     * reading are percent-encoded, so the action is safe as an attribute
     * value, inside the selector the cursor is drawn by, and around the `:`
     * that parts them.
     */
    fun reference(n: Int, term: String, reading: String?): String = buildString {
        append(REFERENCE).append(':').append(n).append(':').append(encode(term))
        if (!reading.isNullOrEmpty()) append(':').append(encode(reading))
    }

    /** The word a [reference] action names; null for any other action, and
     *  for one that does not read back whole. */
    fun referenceOf(action: String): Reference? {
        if (!action.startsWith("$REFERENCE:")) return null
        val parts = action.split(':')
        if (parts.size != 3 && parts.size != 4) return null
        if (parts[1].toIntOrNull() == null) return null
        val term = decode(parts[2])?.takeIf { it.isNotBlank() } ?: return null
        val reading = if (parts.size == 4) decode(parts[3])?.takeIf { it.isNotEmpty() } ?: return null else null
        return Reference(term, reading)
    }

    private fun unreserved(c: Char): Boolean =
        c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '-' || c == '.' || c == '_' || c == '~'

    private fun encode(s: String): String = buildString {
        for (b in s.toByteArray(Charsets.UTF_8)) {
            val u = b.toInt() and 0xFF
            if (u < 128 && unreserved(u.toChar())) {
                append(u.toChar())
            } else {
                append('%').append(HEX[u shr 4]).append(HEX[u and 0xF])
            }
        }
    }

    /** [s] read back from [encode]; null when it holds anything [encode]
     *  never writes, bytes that are no text among them. */
    private fun decode(s: String): String? {
        val bytes = java.io.ByteArrayOutputStream(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '%') {
                if (i + 2 >= s.length) return null
                val hi = HEX.indexOf(s[i + 1])
                val lo = HEX.indexOf(s[i + 2])
                if (hi < 0 || lo < 0) return null
                bytes.write((hi shl 4) or lo)
                i += 3
            } else {
                if (!unreserved(c)) return null
                bytes.write(c.code)
                i++
            }
        }
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(bytes.toByteArray()))
                .toString()
        } catch (_: java.nio.charset.CharacterCodingException) {
            null
        }
    }

    private const val HEX = "0123456789ABCDEF"
}

/** What names the pack's own definitions in [DefRow.Word.shutDictionaries].
 *  No dictionary's id or label starts with a NUL. */
internal const val OWN_DICTIONARY_KEY = "\u0000own"

/**
 * A word page's imported rows around its built-in senses: the dictionaries
 * the user's order puts above the built-in dictionary's row as [aboveId]'s
 * row, and the ones below it as [belowId]'s, each made by [row] from its
 * groups and the flat index of its first definition among all of [groups]
 * ([DefRow.Curation.base]). A side with no definition has no row. The page
 * lists the first, its sense rows, then the second.
 */
internal fun importedRowsAround(
    groups: List<com.playtranslate.model.ImportedSenseGroup>,
    aboveId: String,
    belowId: String,
    row: (id: String, groups: List<com.playtranslate.model.ImportedSenseGroup>, base: Int) -> DefRow.Imported,
): Pair<DefRow.Imported?, DefRow.Imported?> {
    val (above, below) = groups.aroundBuiltIn()
    fun of(id: String, side: List<com.playtranslate.model.ImportedSenseGroup>, base: Int) =
        if (side.any { it.senses.isNotEmpty() }) row(id, side, base) else null
    return of(aboveId, above, 0) to of(belowId, below, above.sumOf { it.senses.size })
}

/** What names an imported dictionary's block in a word row
 *  ([DefRow.Word.shutDictionaries]): the dictionary's id, or its label for a
 *  group that carries no id. */
internal fun com.playtranslate.model.ImportedSenseGroup.dictionaryKey(): String = dictId.ifEmpty { source }
