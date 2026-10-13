package com.playtranslate.language.inflection

import com.playtranslate.language.InflectionForm
import com.playtranslate.language.inflection.KaikkiTags.FEMININE
import com.playtranslate.language.inflection.KaikkiTags.FIRST
import com.playtranslate.language.inflection.KaikkiTags.IMPERATIVE
import com.playtranslate.language.inflection.KaikkiTags.INDICATIVE
import com.playtranslate.language.inflection.KaikkiTags.INFINITIVE
import com.playtranslate.language.inflection.KaikkiTags.MASCULINE
import com.playtranslate.language.inflection.KaikkiTags.NEGATIVE
import com.playtranslate.language.inflection.KaikkiTags.NEUTER
import com.playtranslate.language.inflection.KaikkiTags.PARTICIPLE
import com.playtranslate.language.inflection.KaikkiTags.PAST
import com.playtranslate.language.inflection.KaikkiTags.PLURAL
import com.playtranslate.language.inflection.KaikkiTags.SECOND
import com.playtranslate.language.inflection.KaikkiTags.SINGULAR
import com.playtranslate.language.inflection.KaikkiTags.SUBJUNCTIVE
import com.playtranslate.language.inflection.KaikkiTags.THIRD
import java.util.Locale

/** How a form addresses its second or third person: the plain pronoun, a
 *  formal one (usted, você), or vos. */
enum class Address { PLAIN, FORMAL, VOSEO }

/** A pronoun row of a grid section. */
data class PersonRow(val label: String, val person: Int, val plural: Boolean)

/** A column of a grid section: the mood it holds, the tag set identifying its
 *  tense (null for a mood with one column, the imperative), a qualifier tag
 *  the column requires present or absent (Spanish `imperfect-se` splits the
 *  imperfect subjunctive in two), and the polarity it holds. */
class Column(
    val title: String,
    val mood: String,
    val tense: Set<String>? = null,
    val qualifier: Pair<String, Boolean>? = null,
    val negative: Boolean? = null,
    val rare: Boolean = false,
)

/** A grid section: a mood as a chip, pronouns down, columns across. */
class GridSection(val title: String, val rows: List<PersonRow>, val columns: List<Column>, val rareMarker: String? = null)

/** What a noun's, adjective's or pronoun's form says: its [name] on the line
 *  ("femenino plural", "Dativ Plural"), and for a language that declines, its
 *  [case] and number for the declension grid. */
data class NominalReading(val name: String, val case: String? = null, val plural: Boolean? = null)

/**
 * The machinery shared by the Romance paradigms (Spanish, Portuguese, French):
 * one reader of a form's kaikki tags, [classified], from which both the line
 * ([label]) and the grid ([table]) are drawn, so the two cannot disagree. A
 * language supplies its vocabulary and names; the base supplies the rules.
 *
 * The classification is total over the language's closed vocabulary: every tag
 * the pack's tagsets carry is in one role (person, number, tense, mood,
 * polarity, register, voseo, non-finite, agreement, degree, clitic, qualifier,
 * usage note, or explicitly ignored), and a form is classified only when all
 * its tags are known and resolve without contradiction; anything else gets no
 * label and no cell. A usage note (archaic, rare, colloquial) changes no
 * grammar and is shown after the name in the language's own word, in
 * parentheses: "usted · imperativo + nos (arcaico)"; a register tag (formal,
 * informal) is an address on a finite form and a usage note on a nominal one
 * (chefa: feminino (informal)). Nothing falls through to a default: the
 * pronoun is a lookup over the language's (person, number, address) cells, a
 * tense is named only by the tag set that identifies it, and a tag the
 * vocabulary does not place fails the form. Each language's golden test runs
 * every tagset of its pack through [label] against a reviewed file, and the
 * oracle judges the grid by [classify].
 *
 * kaikki's shared Romance conventions this encodes: a finite form is tagged
 * person + number + tense + mood, the conditional carries no mood (or the
 * indicative), a headline form (alisto) and some pages name a tense with no
 * mood, which is the indicative; a third-person imperative addresses the
 * formal pronoun (usted, você), there being no él imperative; a form-of page
 * may merge two persons or tenses in one tagset (alistara, quejamos) and then
 * names each; a bundled pronominal variant's table (irse under ir, se lever
 * under lever) is tagged `pronominal` by the builder and stays out of the
 * lemma's readings and grid.
 */
abstract class RomanceParadigm : InflectionParadigm {

    protected abstract val locale: Locale

    /** Finite tenses of the indicative (or of a form with no mood tag), each
     *  by the tag set that identifies it, in paradigm order: Spanish {present}
     *  → presente, French {past, historic} → passé simple. */
    protected abstract val indicativeTenses: LinkedHashMap<Set<String>, String>
    protected abstract val subjunctiveTenses: LinkedHashMap<Set<String>, String>
    protected abstract val imperativeName: String
    /** The negative imperative's name, or null for a language whose tables have none (French). */
    protected open val negativeImperativeName: String? = null
    /** Tense tags an imperative may carry (French pages say `imperative present`, the impératif présent). */
    protected open val imperativeTenseTags: Set<String> = emptySet()
    /** Non-finite forms by the tag set that identifies them, in list order;
     *  the participle's set is the one holding `participle`. */
    protected abstract val nonFiniteForms: LinkedHashMap<Set<String>, String>
    /** Portuguese: the name of an infinitive carrying person and number. */
    protected open val personalInfinitiveName: String? = null
    /** The pronoun cells by (person, plural, address); anything else is no cell and no label. */
    protected abstract val pronouns: Map<Triple<Int, Boolean, Address>, String>
    /** Paradigm order of the pronouns, for a line naming several. */
    protected abstract val pronounOrder: List<String>
    protected abstract val genderNames: Map<String, String>
    protected abstract val degreeNames: LinkedHashMap<String, String>
    protected abstract val pluralWord: String
    protected open val formalTags: Set<String> = setOf(KaikkiTags.FORMAL, "second-person-semantically")
    protected open val informalTags: Set<String> = setOf(KaikkiTags.INFORMAL)
    protected open val voseoTags: Set<String> = emptySet()
    /** The tags of an attached pronoun (Spanish); stripped to classify the verb form under it. */
    protected open val cliticTags: Set<String> = emptySet()
    /** Known tags that qualify a tense without naming one, read by the grid's columns (Spanish `imperfect-se`). */
    protected open val qualifiers: Set<String> = emptySet()
    /** kaikki's usage tags and the word the line shows for each (archaic → arcaico, rare → selten), in
     *  display order; the ones the language's pack carries on its rows. Two tags may share a word
     *  (German's archaic and obsolete are both veraltet) and the word is shown once. */
    protected open val usageNames: LinkedHashMap<String, String> = linkedMapOf()
    /** The words for the register tags ([formalTags], [informalTags]) on a nominal form, where they are a
     *  usage note (chefa is the informal feminine) rather than an address. */
    protected open val registerNames: Map<String, String> = emptyMap()
    /** Tags that change nothing the label says: a region, a template's connective, a spelling variant's marker. */
    protected abstract val ignored: Set<String>
    /** Tags the language's [nominal] hook reads (German's cases and declension types): known, and theirs to interpret. */
    protected open val nominalTags: Set<String> = emptySet()

    protected abstract val gridSections: List<GridSection>
    protected abstract val nonFiniteTitle: String
    /** What the grid writes before a bare negative imperative (Spanish "no ");
     *  null when the pack's surface carries it (Portuguese "não fales"). */
    protected open val negativePrefix: String? = null
    /** Whether a plain lemma's two-word form led by a pronoun in [leadingClitics]
     *  is a bundled variant's row rather than the lemma's own (Spanish `me voy`
     *  under ir, from a table the builder did not mark). */
    protected open val cliticLedIsVariant: Boolean = false
    protected open val leadingClitics: Set<String> = emptySet()
    protected open fun isPronominalLemma(lemma: String): Boolean = false
    /** The pronoun(s) written onto a clitic form, read off the surface; null when the form cannot be read. */
    protected open fun attachedPronouns(tags: Set<String>, surface: String): String? = null
    /** Whether the verb leads a multi-word form (German's separable verbs: "stehe auf"), so the
     *  arrival surface is matched against the first word too. */
    protected open val verbLeadsPhrase: Boolean = false
    /** The persons a finite form has when kaikki tags none (a German page's "imperative singular" is du's). */
    protected open fun impliedPersons(moods: Set<String>, plural: Boolean): List<Int> = emptyList()
    /** The usage tags of a form that say something about it; a language drops the ones kaikki's
     *  template writes on every row (German's `rare` on the Konjunktiv II). */
    protected open fun usageTagsOf(tags: Set<String>): Set<String> = tags.filterTo(HashSet()) { it in usageNames }

    /** A noun's, adjective's or pronoun's form: what the line says, or null for
     *  nothing to say or a form not read. The Romance default names degree,
     *  gender and number and reads a person or a polarity as a page it does not
     *  read; a declining language overrides it with cases. */
    protected open fun nominal(tags: Set<String>, persons: List<Int>, numbers: List<Boolean>, negative: Boolean): NominalReading? {
        // A person or a polarity on a noun's or adjective's form is a page this
        // does not read ("a mis solas": first-person singular); a register is a
        // usage note there, shown through registerNames.
        if (persons.isNotEmpty() || numbers.size > 1 || negative || PAST in tags) return null
        if (tags.any { it in voseoTags }) return null
        return agreement(tags, participle = false)?.let { NominalReading(it, plural = numbers.singleOrNull()) }
    }

    /** The table for an entry whose forms are all nominal (a declension grid),
     *  or null; the Romance languages draw none. Receives every classified
     *  nominal form as (surface, reading) and the arrival surface. */
    protected open fun nominalTable(forms: List<Pair<String, NominalReading>>, target: String?): ConjugationTable? = null

    // ── the classification ─────────────────────────────────────────────

    private enum class Kind { FINITE, NON_FINITE, NOMINAL }

    /** One (pronoun, name) the line says, with the form's usage notes, which render after the name. */
    private data class Reading(val pronoun: String?, val name: String, val usage: List<String> = emptyList()) {
        val rendered: String get() = if (usage.isEmpty()) name else "$name (${usage.joinToString(", ")})"
    }

    /** One pack line as read here, the only reading of its tags. */
    private data class Classified(
        val form: InflectionForm,
        val kind: Kind,
        val clitic: String?,
        val persons: List<Int>,
        val plural: Boolean?,
        val address: Address,
        /** Finite: the moods (INDICATIVE when kaikki left it unsaid; a form-of page may merge the indicative and
         *  the subjunctive, ils absentent), or INFINITIVE for a personal infinitive. */
        val moods: Set<String>,
        /** Finite: the identifying tag sets of the tenses it carries, in paradigm order. */
        val tenseKeys: List<Set<String>>,
        val qualifiers: Set<String>,
        val negative: Boolean,
        /** Non-finite: its name as listed. */
        val nonFiniteName: String?,
        val agreement: String?,
        /** Nominal, in a declining language: the case. */
        val case: String?,
        val readings: List<Reading>,
    )

    private val tenseTags: Set<String> by lazy { indicativeTenses.keys.flatten().toSet() + subjunctiveTenses.keys.flatten() }
    /** The (person, plural) cells the imperative has, read off the grid's imperative section. */
    private val imperativeCells: Set<Pair<Int, Boolean>> by lazy {
        gridSections.filter { s -> s.columns.any { it.mood == IMPERATIVE } }.flatMap { s -> s.rows.map { it.person to it.plural } }.toSet()
    }
    private val known: Set<String> by lazy {
        PERSONS.keys + NUMBERS.keys + MOODS + NEGATIVE + tenseTags + nonFiniteForms.keys.flatten() + PAST +
            formalTags + informalTags + voseoTags + genderNames.keys + degreeNames.keys + cliticTags + qualifiers + usageNames.keys +
            registerNames.keys + ignored + nominalTags + setOf(FORM_OF_TAG, InflectionForm.PRONOMINAL_TAG)
    }

    override fun isKnown(tag: String): Boolean = tag in known || tag.startsWith(TABLE_PREFIX)

    override fun classify(form: InflectionForm): FormClass? {
        val c = classified(form) ?: return null
        return FormClass(
            verbal = c.kind != Kind.NOMINAL,
            finite = c.kind == Kind.FINITE,
            clitic = c.clitic != null,
            voseo = c.address == Address.VOSEO,
            agreement = c.agreement,
            label = labelOf(c.readings) ?: return null,
        )
    }

    private fun classified(form: InflectionForm): Classified? {
        val tags = form.tags
        if (tags.any { !isKnown(it) }) return null
        if (cliticTags.isNotEmpty() && tags.any { it in cliticTags }) {
            val base = classified(InflectionForm(form.surface, tags - cliticTags)) ?: return null
            val attached = attachedPronouns(tags, form.surface) ?: return null
            return base.copy(form = form, clitic = attached, readings = base.readings.map { it.copy(name = "${it.name} + $attached") })
        }
        val persons = PERSONS.entries.filter { it.key in tags }.map { it.value }
        val numbers = NUMBERS.entries.filter { it.key in tags }.map { it.value }
        val moods = MOODS.filter { it in tags }
        val negative = NEGATIVE in tags
        val agreementTagged = tags.any { it in genderNames || it in degreeNames }
        val register = tags.any { it in formalTags || it in informalTags || it in voseoTags }
        val usage = usageWordsOf(tags)

        // Portuguese: an infinitive with a person and number is the personal
        // infinitive, a finite paradigm of its own.
        if (personalInfinitiveName != null && INFINITIVE in tags && persons.isNotEmpty()) {
            if (moods.isNotEmpty() || negative || agreementTagged || tags.any { it in tenseTags }) return null
            return finite(form, setOf(INFINITIVE), emptyList(), listOf(personalInfinitiveName!!), persons, numbers, negative = false, usage)
        }

        val nonFinite = nonFiniteForms.entries.filter { (key, _) -> key.all { it in tags } }
        if (nonFinite.isNotEmpty()) {
            // The most specific match names the form (French {gerund, participle,
            // present} over nothing smaller); two matches that do not nest are a
            // contradiction. Beside the key: no mood, polarity or register;
            // `past` only on a participle, which alone agrees in gender and
            // number. Person and number say nothing of an infinitive or gerund
            // (a form-of page may carry them: adueñándome).
            val match = nonFinite.maxByOrNull { it.key.size }!!
            if (nonFinite.any { !match.key.containsAll(it.key) }) return null
            val participle = PARTICIPLE in match.key
            if (moods.isNotEmpty() || negative || register) return null
            if (tags.any { it in tenseTags && it !in match.key }) return null
            if (!participle && (PAST in tags || agreementTagged)) return null
            val agreement = if (participle) agreement(tags, participle = true) else null
            return Classified(
                form, Kind.NON_FINITE, clitic = null, persons = emptyList(), plural = null, address = Address.PLAIN,
                moods = emptySet(), tenseKeys = emptyList(), qualifiers = emptySet(), negative = false, nonFiniteName = match.value,
                agreement = agreement, case = null, readings = listOf(Reading(null, listOfNotNull(match.value, agreement).joinToString(" "), usage)),
            )
        }

        if (moods.isNotEmpty() || tags.any { it in tenseTags }) {
            if (agreementTagged) return null
            // The indicative, or no mood at all: kaikki's headline forms (alisto:
            // first-person singular present) and some pages name the tense alone,
            // and the indicative is the mood that goes unsaid. A page may merge
            // the indicative and the subjunctive where the forms coincide (ils
            // absentent); the imperative stands alone.
            // A tense that belongs to one mood names it when kaikki left the mood
            // unsaid (a German page's `subjunctive-i` alone is the Konjunktiv).
            val read = moods.ifEmpty {
                val indicativeKey = indicativeTenses.keys.any { k -> k.all { it in tags } }
                val subjunctiveKey = subjunctiveTenses.keys.any { k -> k.all { it in tags } }
                listOf(if (subjunctiveKey && !indicativeKey) SUBJUNCTIVE else INDICATIVE)
            }.toSet()
            if (IMPERATIVE in read && read.size > 1) return null
            val keys = ArrayList<Set<String>>()
            val names = ArrayList<String>()
            for (mood in listOf(INDICATIVE, SUBJUNCTIVE, IMPERATIVE).filter { it in read }) {
                when (mood) {
                    IMPERATIVE -> {
                        if (tags.any { it in tenseTags && it !in imperativeTenseTags }) return null
                        keys += tags.filter { it in imperativeTenseTags }.map { setOf(it) }
                        names += if (negative) negativeImperativeName ?: return null else imperativeName
                    }
                    SUBJUNCTIVE -> {
                        if (negative) return null
                        val found = subjunctiveTenses.keys.filter { k -> k.all { it in tags } }
                        if (found.isEmpty()) return null
                        keys += found
                        names += found.map { subjunctiveTenses.getValue(it) }
                    }
                    else -> {
                        if (negative) return null
                        val found = indicativeTenses.keys.filter { k -> k.all { it in tags } }
                        if (found.isEmpty()) return null
                        keys += found
                        names += found.map { indicativeTenses.getValue(it) }
                    }
                }
            }
            // Every tense tag on the form must belong to a tense named here; a
            // stray one (`past` on a Spanish finite form) is a form not read.
            val covered = keys.flatMapTo(HashSet()) { it }
            if (tags.any { (it in tenseTags || it == PAST) && it !in covered }) return null
            return finite(form, read, keys.distinct(), names, persons, numbers, negative, usage)
        }

        // A noun's, adjective's or pronoun's form: the language's nominal
        // reading, with a register tag read as a usage note.
        val nominal = nominal(tags, persons, numbers, negative) ?: return null
        val nominalUsage = (usage + registerNames.filterKeys { it in tags }.values).distinct()
        return Classified(
            form, Kind.NOMINAL, clitic = null, persons = emptyList(), plural = nominal.plural, address = Address.PLAIN,
            moods = emptySet(), tenseKeys = emptyList(), qualifiers = emptySet(), negative = false, nonFiniteName = null,
            agreement = nominal.name, case = nominal.case, readings = listOf(Reading(null, nominal.name, nominalUsage)),
        )
    }

    /** The words for a form's usage tags, in [usageNames] order, each once. */
    private fun usageWordsOf(tags: Set<String>): List<String> {
        val present = usageTagsOf(tags)
        return usageNames.entries.filter { it.key in present }.map { it.value }.distinct()
    }

    private fun finite(
        form: InflectionForm, moods: Set<String>, keys: List<Set<String>>, names: List<String>,
        persons: List<Int>, numbers: List<Boolean>, negative: Boolean, usage: List<String>,
    ): Classified? {
        val tags = form.tags
        if (names.isEmpty() || numbers.isEmpty()) return null
        // A page may merge both numbers where the forms coincide (German
        // abbisst: du and ihr); the grid places the form by the first.
        val plural = numbers.first()
        val persons = persons.ifEmpty { if (numbers.size == 1) impliedPersons(moods, plural) else emptyList() }
        if (persons.isEmpty()) return null
        // An imperative exists for the persons the language's grid has rows
        // for (no yo imperative; French has none for il): a page tagging one
        // elsewhere (ferme-la, "first-person singular imperative") is not read.
        if (IMPERATIVE in moods && numbers.any { n -> persons.any { (it to n) !in imperativeCells } }) return null
        val formal = tags.any { it in formalTags }
        val informal = tags.any { it in informalTags }
        val voseo = tags.any { it in voseoTags }
        if (formal && (informal || voseo)) return null
        if (informal && persons.any { it != 2 }) return null
        val address = when {
            voseo -> Address.VOSEO
            formal -> Address.FORMAL
            // A third-person imperative addresses the formal pronoun (usted,
            // você); there is no él imperative. kaikki lists it twice in
            // Spanish, once without `formal`.
            IMPERATIVE in moods && persons == listOf(3) -> Address.FORMAL
            else -> Address.PLAIN
        }
        val who = numbers.flatMap { n -> persons.map { pronouns[Triple(it, n, address)] ?: return null } }
        return Classified(
            form, Kind.FINITE, clitic = null, persons = persons, plural = plural, address = address,
            moods = moods, tenseKeys = keys, qualifiers = tags.filterTo(HashSet()) { it in qualifiers }, negative = negative,
            nonFiniteName = null, agreement = null, case = null, readings = who.flatMap { pronoun -> names.map { Reading(pronoun, it, usage) } },
        )
    }

    /** Degree, gender and number words for a noun's or adjective's form, or a
     *  participle's agreement (participle = true, where the masculine singular
     *  is the participle itself and says nothing). A bare masculine reading
     *  is a feminine lemma's counterpart (matrón under matrona). Null when the
     *  tags say nothing (a noun's own singular). */
    protected fun agreement(tags: Set<String>, participle: Boolean): String? {
        val words = buildList {
            for ((tag, name) in degreeNames) if (tag in tags) add(name)
            // One plural for both genders (grandes) is tagged feminine AND
            // masculine: no gender word, just the plural.
            val bothGenders = FEMININE in tags && MASCULINE in tags
            when {
                bothGenders -> Unit
                FEMININE in tags -> genderNames[FEMININE]?.let { add(it) }
                MASCULINE in tags && (PLURAL in tags || !participle) -> genderNames[MASCULINE]?.let { add(it) }
                NEUTER in tags -> genderNames[NEUTER]?.let { add(it) }
            }
            if (PLURAL in tags) add(pluralWord)
        }
        return words.joinToString(" ").ifEmpty { null }
    }

    // ── the line ────────────────────────────────────────────────────────

    override fun label(readings: List<InflectionForm>): String? =
        labelOf(readings.mapNotNull { classified(it) }.flatMap { it.readings })

    private fun labelOf(readings: List<Reading>): String? {
        val parts = readings.distinct()
        if (parts.isEmpty()) return null
        // One group per pronoun with its tenses joined ("nosotros · presente /
        // pretérito"), then pronouns whose tense lists are identical merge
        // ("yo / él, ella, usted · imperfecto"); groups are separated by "; ".
        // A reading without a pronoun (gerundio, plural) is named alone. A
        // name carries its usage notes ("imperativo (arcaico)").
        val namesByPronoun: Map<String?, List<String>> = parts.groupBy { it.pronoun }
            .mapValues { (_, group) -> group.map { it.rendered }.distinct() }
        val pronounsByNames = LinkedHashMap<List<String>, MutableList<String?>>()
        for ((pronoun, names) in namesByPronoun) pronounsByNames.getOrPut(names) { mutableListOf() }.add(pronoun)
        val groups = pronounsByNames.map { (names, who) ->
            val order = who.filterNotNull().sortedBy { pronounOrder.indexOf(it) }.joinToString(" / ")
            listOfNotNull(order.ifEmpty { null }, names.joinToString(" / ")).joinToString(" · ")
        }
        return groups.take(MAX_LABEL_PARTS).joinToString("; ")
    }

    // ── the table ───────────────────────────────────────────────────────

    override fun table(lemma: String, forms: List<InflectionForm>, highlight: String?): ConjugationTable? {
        val pronominal = isPronominalLemma(lemma)
        // The lemma's own tables only: a bundled pronominal variant's rows are
        // tagged by the builder and not shown; a second table of one-word forms
        // (acostar's regular conjugation) is, its forms alternates in the same
        // cells. Clitic-attached forms are labelled but are not cells. A line
        // the classifier does not read is no cell.
        val classified = forms.filterNot { it.fromPronominalVariant }.mapNotNull { classified(it) }
        val target = highlight?.lowercase(locale)
        if (classified.none { it.kind != Kind.NOMINAL }) {
            return nominalTable(classified.map { it.form.surface to NominalReading(it.agreement!!, it.case, it.plural) }, target)
        }
        val usable = classified.filter { it.clitic == null && (pronominal || !cliticLedIsVariant || !isCliticLed(it.form.surface)) }

        val grids = gridSections.map { grid(it, usable, target) }
        val finiteCells = grids.sumOf { s -> s.rows.sumOf { r -> r.cells.count { it.forms.isNotEmpty() } } }
        if (finiteCells < MIN_FINITE_CELLS) return null

        val sections = grids + nonFinite(usable, target)
        val open = sections.indexOfFirst { s -> s.rows.any { r -> r.cells.any { it.highlighted } } }
        return ConjugationTable(sections, if (open < 0) 0 else open)
    }

    private fun Column.holds(c: Classified): Boolean =
        c.kind == Kind.FINITE && mood in c.moods && (tense == null || tense in c.tenseKeys) &&
            (qualifier == null || (qualifier.first in c.qualifiers) == qualifier.second) &&
            (negative == null || c.negative == negative)

    private fun grid(spec: GridSection, forms: List<Classified>, target: String?): ConjugationSection {
        val built = spec.rows.map { row ->
            ConjugationRow(row.label, spec.columns.map { col ->
                val slot = forms.filter { col.holds(it) && row.person in it.persons && it.plural == row.plural }
                // Whatever tag said vos puts the form under tú as a companion,
                // never beside the tú form.
                val main = slot.filter { it.address != Address.VOSEO }.map { it.form.surface }.distinct()
                val vos = slot.filter { it.address == Address.VOSEO }.map { it.form.surface }.distinct()
                val prefix = negativePrefix
                val shown = if (prefix != null && slot.any { it.negative }) main.map { if (it.startsWith(prefix)) it else prefix + it } else main
                ConjugationCell(
                    forms = shown,
                    secondary = vos,
                    highlighted = target != null && (main + vos).any { hits(it, target) },
                )
            })
        }
        val rare = spec.columns.withIndex().filter { it.value.rare }.map { it.index }.toSet()
        return ConjugationSection(spec.title, spec.columns.map { it.title }, built, rare, spec.rareMarker.takeIf { rare.isNotEmpty() })
    }

    private fun nonFinite(forms: List<Classified>, target: String?): ConjugationSection {
        // Every bare form of each kind, alternates side by side (imprimir's
        // participles imprimido and impreso); the agreeing forms are labelled,
        // not listed.
        val rows = nonFiniteForms.values.distinct().mapNotNull { name ->
            val surfaces = forms.filter { it.nonFiniteName == name && it.agreement == null }.map { it.form.surface }.distinct()
            if (surfaces.isEmpty()) null
            else ConjugationRow(name, listOf(ConjugationCell(surfaces, highlighted = target != null && surfaces.any { hits(it, target) })))
        }
        return ConjugationSection(nonFiniteTitle, emptyList(), rows, isList = true)
    }

    private fun isCliticLed(surface: String): Boolean =
        ' ' in surface && surface.substringBefore(' ') in leadingClitics

    /** The arrival surface matches a cell form directly, the verb of a
     *  two-word pronominal form ("quejo" in "me quejo"), the verb after an
     *  elided pronoun ("absente" in "m'absente"), or, where the verb leads the
     *  phrase, its first word ("stehe" in "stehe auf"). */
    protected fun hits(form: String, target: String): Boolean =
        form == target || (' ' in form && form.substringAfterLast(' ') == target) ||
            ('\'' in form && form.substringAfterLast('\'') == target) ||
            (verbLeadsPhrase && ' ' in form && form.substringBefore(' ') == target)

    protected companion object {
        const val TABLE_PREFIX = "table-"
        const val FORM_OF_TAG = "form-of"
        val PERSONS = linkedMapOf(FIRST to 1, SECOND to 2, THIRD to 3)
        val NUMBERS = mapOf(SINGULAR to false, PLURAL to true)
        val MOODS = setOf(INDICATIVE, SUBJUNCTIVE, IMPERATIVE)
        /** Finite cells a table must fill before it is worth a card. */
        const val MIN_FINITE_CELLS = 6
        const val MAX_LABEL_PARTS = 3
    }
}
