package com.playtranslate.language.inflection

import com.playtranslate.language.inflection.KaikkiTags.COMPARATIVE
import com.playtranslate.language.inflection.KaikkiTags.DIMINUTIVE
import com.playtranslate.language.inflection.KaikkiTags.FEMININE
import com.playtranslate.language.inflection.KaikkiTags.IMPERATIVE
import com.playtranslate.language.inflection.KaikkiTags.INDICATIVE
import com.playtranslate.language.inflection.KaikkiTags.INFINITIVE
import com.playtranslate.language.inflection.KaikkiTags.MASCULINE
import com.playtranslate.language.inflection.KaikkiTags.NEUTER
import com.playtranslate.language.inflection.KaikkiTags.PARTICIPLE
import com.playtranslate.language.inflection.KaikkiTags.PAST
import com.playtranslate.language.inflection.KaikkiTags.PLURAL
import com.playtranslate.language.inflection.KaikkiTags.PRESENT
import com.playtranslate.language.inflection.KaikkiTags.PRETERITE
import com.playtranslate.language.inflection.KaikkiTags.SUBJUNCTIVE
import com.playtranslate.language.inflection.KaikkiTags.SUPERLATIVE
import java.util.Locale

/**
 * German: labels and tables over kaikki's German tags, on [RomanceParadigm]'s
 * machinery (the base is a reading of kaikki's tag conventions, Romance in
 * name only). Names are the ones German grammars use (Präsens, Präteritum,
 * Konjunktiv I and II, Partizip II, Nominativ Plural).
 *
 * Verbs get the card: Indikativ (Präsens, Präteritum), Konjunktiv (I, II),
 * Imperativ (du, ihr), the infinite forms as a list (Infinitiv, zu-Infinitiv,
 * Partizip I, Partizip II). Nouns get a declension card: the four cases down,
 * Singular and Plural across. Adjectives get the line only: a declined form
 * reads "dekliniert" (with its degree: "dekliniert im Komparativ"), since
 * kaikki's adjective table is three declensions by four cases by four
 * gender-number columns, a surface such as freien sits in a dozen of its
 * cells, and the article that would settle the case is not in the word the
 * player tapped; a predicative comparative or superlative reads its degree. A
 * product decision for v1, to revisit.
 *
 * Usage notes read in Duden's words: veraltet, veraltend, selten,
 * umgangssprachlich, gehoben, dichterisch, fachsprachlich, scherzhaft, nicht
 * standardsprachlich, mundartlich, landschaftlich, übertragen ("übelere ·
 * dekliniert im Komparativ (selten)"; "bärs · Genitiv Singular
 * (umgangssprachlich)"). `honorific` (Themata) is left silent, its meaning in
 * kaikki's German unverified; a named region (Austria) says nothing.
 *
 * kaikki's German conventions this encodes (the de extract, 2026-10): the
 * Konjunktiv II rows carry `formal rare`, which there says nothing (the
 * template writes it on every row; there is no formal address in the tables,
 * Sie being the third-person plural), while `rare` on any other row is a usage
 * note (a rare comparative, a rare plural); separable
 * verbs' finite rows lead with the verb ("stehe auf") and the
 * `subordinate-clause` rows give the joined form ("aufstehe"), both the
 * lemma's own; `auxiliary` rows name the perfect auxiliary (haben, sein) and
 * are not forms; a noun's plural rows carry `definite`; an adjective's rows
 * carry a declension (`strong`, `weak`, `mixed`) or `predicative`, the
 * `includes-article` rows the article too ("der freie"), and `negative` marks
 * the keine rows; the zu-infinitive is `infinitive infinitive-zu`.
 */
object GermanParadigm : RomanceParadigm() {

    private const val SUBJUNCTIVE_I = "subjunctive-i"
    private const val SUBJUNCTIVE_II = "subjunctive-ii"
    private const val INFINITIVE_ZU = "infinitive-zu"
    private const val PREDICATIVE = "predicative"
    private val DECLENSIONS = setOf("strong", "weak", "mixed")
    private val CASES = linkedMapOf("nominative" to "Nominativ", "genitive" to "Genitiv", "dative" to "Dativ", "accusative" to "Akkusativ")

    override val locale: Locale = Locale("de")

    /** Pages also call the Präteritum `imperfect`. */
    override val indicativeTenses = linkedMapOf(setOf(PRESENT) to "Präsens", setOf(PRETERITE) to "Präteritum", setOf("imperfect") to "Präteritum", setOf(PAST) to "Präteritum")
    override val subjunctiveTenses = linkedMapOf(setOf(SUBJUNCTIVE_I) to "Konjunktiv I", setOf(SUBJUNCTIVE_II) to "Konjunktiv II")
    override val imperativeName = "Imperativ"
    override val nonFiniteForms = linkedMapOf(
        setOf(INFINITIVE) to "Infinitiv", setOf(INFINITIVE, INFINITIVE_ZU) to "zu-Infinitiv",
        setOf(PARTICIPLE, PRESENT) to "Partizip I", setOf(PARTICIPLE, PAST) to "Partizip II", setOf(PARTICIPLE, "perfect") to "Partizip II",
    )

    override val pronouns: Map<Triple<Int, Boolean, Address>, String> = mapOf(
        Triple(1, false, Address.PLAIN) to "ich",
        Triple(2, false, Address.PLAIN) to "du",
        Triple(3, false, Address.PLAIN) to "er, sie, es",
        Triple(1, true, Address.PLAIN) to "wir",
        Triple(2, true, Address.PLAIN) to "ihr",
        Triple(3, true, Address.PLAIN) to "sie, Sie",
    )
    override val pronounOrder = listOf("ich", "du", "er, sie, es", "wir", "ihr", "sie, Sie")
    override val genderNames = mapOf(FEMININE to "Femininum", MASCULINE to "Maskulinum", NEUTER to "Neutrum")
    override val degreeNames = linkedMapOf(COMPARATIVE to "Komparativ", SUPERLATIVE to "Superlativ", DIMINUTIVE to "Diminutiv")
    override val pluralWord = "Plural"
    /** No formal address in the tables: `formal` on the Konjunktiv II rows is kaikki's artifact. */
    override val formalTags: Set<String> = emptySet()
    override val informalTags: Set<String> = emptySet()
    override val nominalTags = CASES.keys + DECLENSIONS + PREDICATIVE
    override val usageNames = linkedMapOf(
        "archaic" to "veraltet", "obsolete" to "veraltet", "dated" to "veraltend",
        "rare" to "selten", "uncommon" to "selten",
        "colloquial" to "umgangssprachlich", "informal" to "umgangssprachlich", "vernacular" to "umgangssprachlich",
        "formal" to "gehoben", "literary" to "gehoben", "poetic" to "dichterisch", "jargon" to "fachsprachlich", "humorous" to "scherzhaft",
        "nonstandard" to "nicht standardsprachlich", "proscribed" to "nicht standardsprachlich",
        "dialectal" to "mundartlich", "regional" to "landschaftlich", "figuratively" to "übertragen",
    )
    override val ignored = setOf(
        "subordinate-clause", "dependent", "auxiliary",
        "definite", "indefinite", "includes-article", "without-article", "with-article", "usually-without-article",
        "pronoun", "personal", "common", "prepositional", "before-noun", "mutation", "reflexive",
        "especially", "honorific",
        "Standard", "standard", "German", "Austria", "Switzerland", "Swiss-German",
        "Southern-Germany", "southern-Germany", "Upper-German", "Northwest-German",
    )

    /** The Konjunktiv II template writes `formal rare` on every row; neither says anything there. */
    override fun usageTagsOf(tags: Set<String>): Set<String> =
        super.usageTagsOf(tags).let { if (SUBJUNCTIVE_II in tags) it - setOf("rare", "formal") else it }

    private val PERSON_ROWS = listOf(
        PersonRow("ich", 1, false), PersonRow("du", 2, false), PersonRow("er, sie, es", 3, false),
        PersonRow("wir", 1, true), PersonRow("ihr", 2, true), PersonRow("sie, Sie", 3, true),
    )
    override val gridSections = listOf(
        GridSection("Indikativ", PERSON_ROWS, listOf(
            Column("Präsens", INDICATIVE, setOf(PRESENT)), Column("Präteritum", INDICATIVE, setOf(PRETERITE)),
        )),
        GridSection("Konjunktiv", PERSON_ROWS, listOf(
            Column("Konjunktiv I", SUBJUNCTIVE, setOf(SUBJUNCTIVE_I)), Column("Konjunktiv II", SUBJUNCTIVE, setOf(SUBJUNCTIVE_II)),
        )),
        GridSection("Imperativ", listOf(PersonRow("du", 2, false), PersonRow("ihr", 2, true)), listOf(Column("Imperativ", IMPERATIVE))),
    )
    override val nonFiniteTitle = "Infinite Formen"
    override val verbLeadsPhrase = true
    /** A page's "imperative singular" or "imperative plural" with no person is du's or ihr's. */
    override fun impliedPersons(moods: Set<String>, plural: Boolean): List<Int> =
        if (IMPERATIVE in moods) listOf(2) else emptyList()

    /** A noun's form reads its case(s) and number ("Dativ Plural"; a page may
     *  merge cases, "Nominativ / Akkusativ Plural", or carry none, "Genitiv");
     *  an adjective's declined form reads "dekliniert" with its degree
     *  ("dekliniert im Komparativ"; the table tags the declension, a page only
     *  a case with a gender or degree),
     *  a predicative one its degree alone (the positive is the lemma itself);
     *  a pronoun's case form reads its case, its person being the lemma's; a
     *  feminine, a diminutive or a bare plural reads that. */
    override fun nominal(tags: Set<String>, persons: List<Int>, numbers: List<Boolean>, negative: Boolean): NominalReading? {
        val pronoun = "pronoun" in tags || "personal" in tags
        if (persons.isNotEmpty() && !pronoun) return null
        val cases = CASES.keys.filter { it in tags }
        val degree = degreeNames.entries.filter { it.key in tags }.map { it.value }
        val genders = genderNames.entries.filter { it.key in tags }.map { it.value }
        // A page may merge both numbers; it then names the case alone.
        val plural = numbers.singleOrNull()
        // An adjective's row carries a declension with its case (the table), or
        // is predicative, or a page carries a degree with a case; a noun's page
        // carries the lemma's gender, and `strong` with no case is a noun's
        // class (das Aalen, Abendkleidchen), so neither says adjective.
        // An adjective's row carries a declension with its case (the table) or
        // is predicative; a page carries a declension without a case and
        // without a gender (abartigen: mixed plural weak; the gender rides the
        // article), or a degree with a case. A noun's page carries its gender
        // with its class (Kracherl: diminutive mixed neuter strong).
        val declension = tags.any { it in DECLENSIONS }
        val adjective = PREDICATIVE in tags || (cases.isNotEmpty() && (declension || degree.isNotEmpty())) ||
            (("mixed" in tags || "weak" in tags) && cases.isEmpty() && genders.isEmpty())
        return when {
            adjective && PREDICATIVE !in tags -> NominalReading(degree.firstOrNull()?.let { "dekliniert im $it" } ?: "dekliniert")
            PREDICATIVE in tags -> degree.takeIf { it.isNotEmpty() }?.let { NominalReading(it.joinToString(" ")) }
            cases.isNotEmpty() -> {
                // One name per case, each with the number, so a merged page's
                // names match the table's rows one by one.
                val number = when (plural) { true -> " Plural"; false -> " Singular"; null -> "" }
                NominalReading(cases.joinToString(" / ") { CASES.getValue(it) + number }, cases.singleOrNull(), plural)
            }
            // A diminutive's neuter is the suffix's own gender and goes unsaid.
            else -> (degree + (if (degree.isEmpty()) genders else emptyList()) + listOfNotNull("Plural".takeIf { PLURAL in tags }))
                .takeIf { it.isNotEmpty() }?.let { NominalReading(it.joinToString(" "), plural = plural) }
        }
    }

    /** A noun's declension: cases down, Singular and Plural across. Nothing
     *  for an adjective (its forms read "dekliniert") or for fewer than four
     *  filled cells. */
    override fun nominalTable(forms: List<Pair<String, NominalReading>>, target: String?): ConjugationTable? {
        if (forms.any { it.second.name.startsWith("dekliniert") }) return null
        val cased = forms.filter { it.second.case != null && it.second.plural != null }
        if (cased.isEmpty()) return null
        val rows = CASES.map { (case, name) ->
            ConjugationRow(name, listOf(false, true).map { plural ->
                val surfaces = cased.filter { it.second.case == case && it.second.plural == plural }.map { it.first }.distinct()
                ConjugationCell(surfaces, highlighted = target != null && surfaces.any { hits(it, target) })
            })
        }
        if (rows.sumOf { r -> r.cells.count { it.forms.isNotEmpty() } } < 4) return null
        return ConjugationTable(listOf(ConjugationSection("Deklination", listOf("Singular", "Plural"), rows)), 0, TableKind.DECLENSION)
    }
}
