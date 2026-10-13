package com.playtranslate.language

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.playtranslate.language.inflection.FormClass
import com.playtranslate.language.inflection.InflectionParadigm
import com.playtranslate.language.inflection.InflectionParadigms
import com.playtranslate.ui.InflectionChain
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.nio.file.Files

/**
 * The inflection oracle: Wiktionary's own "form of" pages checked against the
 * production lookup and its label over the whole pack, every line of the
 * pack's inflection table checked against its own surface, and the grid
 * checked against the classification it was built from.
 *
 * kaikki carries a page for most inflected forms, separate from the lemma's
 * table, saying what the form is (`pon`: "second-person singular imperative of
 * poner", with structured tags); `scripts/extract_form_of_oracle.py` writes
 * them as a TSV. For every page this test runs [SourceLanguageEngine.lookup]
 * on the surface and compares LABELS, not tags: every (pronoun, name) pair the
 * paradigm's label of the page's tags names must be among the pairs the labels
 * of the entry's readings of the surface name (a page may describe one reading
 * or several). The entry is the pack's entry of the page's lemma IN THE PAGE'S
 * PART OF SPEECH. The page and the table are kaikki's two independently
 * written descriptions of the same form, in two tag conventions, so a
 * convention the paradigm reads on one side and not the other is a mismatch.
 *
 * Every page ends in exactly one of three places, and nothing is merely
 * counted:
 *  - CHECKED and agreed, or agreed under one of [ALLOWANCES] (a rule over the
 *    two labels with its reason) or [KNOWN_DISAGREEMENTS] (one page and row
 *    kaikki wrote inconsistently, named with the reason);
 *  - EXCUSED by one named, mechanical rule that says why there is nothing to
 *    compare: the lemma is not in the pack, or not in the page's part of
 *    speech; the page carries only usage notes (a region, a register, a
 *    lexical note: [PAGE_USAGE_TAGS], each named with what it is, and removed
 *    before the page is labelled, since they describe the word, not the
 *    form); the page's grammatical tags say nothing, the tagset named in
 *    `page_no_label_reasons.tsv`; the page is of an obsolete, rare or
 *    nonstandard form the table does not list; the surface is an entry of its own, which the lookup rightly
 *    returns first; the surface is one the pack does not index, by design
 *    ([UNRESOLVED_EXCUSES]) or as a named gap ([UNRESOLVED_SURFACE_EXCUSES]);
 *    a tag outside both the paradigm's vocabulary and [PAGE_USAGE_TAGS] fails
 *    the test, so a new kaikki tag is a decision, never silence;
 *  - FAILED, in a bucket of [MUST_BE_ZERO], which fails the test.
 * Every allowance and excuse reports how often it fired, and one that never
 * fires fails the test as stale, so the lists cannot rot. Reading the report is
 * not part of the gate: what the gate does not assert, it does not have.
 *
 * Over the whole table, every line's label is checked against its surface,
 * which no tag convention can misdescribe: a clitic form must label (its
 * pronoun is read off the surface among those its tags allow), the leading
 * pronoun of a two-word pronominal form must agree with the labelled person,
 * a participle's ending with its labelled agreement; a row the paradigm leaves
 * unlabelled must be named in `no_label_reasons.tsv`. The grid is checked by
 * [InflectionParadigm.classify], the one reading the grid itself was built
 * from: every finite, non-clitic form of every verb's table lands in a cell,
 * every bare non-finite form in the list (imprimir's imprimido and impreso);
 * every main form of every cell is the surface of some plain-address form and
 * every companion the surface of some voseo form. (By surface, since the vos
 * present of an -ir verb, dormís, is also its vosotros present, and its vos
 * imperative, dormí, its yo preterite: a surface with both a plain and a
 * voseo reading may sit in both places; one with only a voseo reading, eris,
 * may not be a main form.)
 *
 * Skipped unless the environment names the pack, the oracle and the dump:
 * ```
 * PLAYTRANSLATE_ORACLE_LANG=es \
 * PLAYTRANSLATE_ORACLE_PACK_DIR=$PWD/local/inflection-dev/es \
 * PLAYTRANSLATE_ORACLE_FORMS=$PWD/local/inflection-dev/es/form_of_oracle.tsv \
 * PLAYTRANSLATE_ORACLE_DUMP_DIR=$PWD/seg-runs/es-oracle \
 *   ./gradlew :app:testDebugUnitTest --tests 'com.playtranslate.language.PackInflectionOracleTest' --rerun
 * ```
 * `PLAYTRANSLATE_ORACLE_SAMPLE=N` checks a seeded sample of N surfaces
 * instead of all of them. The report is `oracle.json` in the dump directory.
 */
@RunWith(RobolectricTestRunner::class)
class PackInflectionOracleTest {

    /** One form-of sense, compact: the lemma lowercased, the page's part of
     *  speech and its tags space-joined (parsed when checked). The gloss is
     *  not kept: 721k lines of it do not fit the test heap. */
    private class Expectation(val lemma: String, val pos: String, val tags: String)

    private class Bucket(val name: String) {
        var count = 0
        val examples = JSONArray()
        fun add(example: () -> JSONObject) {
            count++
            if (examples.length() < EXAMPLES) examples.put(example())
        }
    }

    private data class Atom(val pronoun: String?, val name: String)

    /** A principled difference between a page's label and the table's,
     *  forgiven when [applies] to (page pos, the page's pairs, the readings' pairs). */
    private class Allowance(val reason: String, val applies: (pos: String, surface: String, page: Set<Atom>, readings: Set<Atom>) -> Boolean)

    /** A surface the pack does not index, by design: the page is excused when [applies] to its label. */
    private class UnresolvedExcuse(val reason: String, val applies: (pageLabel: String) -> Boolean)

    /** What the oracle knows about one language beyond its paradigm: kaikki's
     *  page-side usage tags, the principled allowances, the named exceptions,
     *  and the surface invariants. One per language, in [LANGUAGES]. */
    private class OracleLanguage(
        /** Page tags for a form the table does not list: the table has the standard current paradigm. */
        val tableOmitsTags: Set<String>,
        val genderWords: Set<String>,
        /** Tags a form-of page carries that describe the word, not the form, each named with what it is;
         *  removed before the page is labelled; every entry must occur or the test fails it as stale. */
        val usageTags: Map<String, String>,
        val allowances: List<Allowance>,
        /** Pages and rows that differ on one form, by "surface|lemma", with why. */
        val knownDisagreements: Map<String, String>,
        val unresolvedExcuses: List<UnresolvedExcuse>,
        /** Surfaces the pack does not index, each a named gap with its cause. */
        val unresolvedSurfaceExcuses: Map<String, String>,
        /** Pages whose lemma the lookup does not return, by "surface|lemma", each with why. */
        val lemmaNotReturnedExcuses: Map<String, String>,
        /** Pages whose lemma the lookup does not return, excused by a rule over the page label. */
        val lemmaNotReturnedRules: List<UnresolvedExcuse> = emptyList(),
        /** The pronoun that leads a two-word pronominal form, and the labelled persons it agrees with. */
        val leadingClitics: Map<String, Set<String>>,
        /** A participle's allowed endings for each labelled agreement. */
        val participleEndings: Map<String, List<String>>,
        /** The paradigm's rule for a plain lemma's clitic-led two-word rows being a bundled variant's (Spanish). */
        val variantLeadClitics: Set<String> = emptySet(),
        val pronominalLemma: (String) -> Boolean = { false },
    )

    @Test
    fun oracle() {
        val lang = System.getenv(LANG_ENV)
        val packPath = System.getenv(PACK_DIR_ENV)
        val formsPath = System.getenv(FORMS_ENV)
        val dumpPath = System.getenv(DUMP_DIR_ENV)
        assumeTrue(
            "$LANG_ENV, $PACK_DIR_ENV, $FORMS_ENV and $DUMP_DIR_ENV are not all set; skipping the inflection oracle",
            lang != null && packPath != null && formsPath != null && dumpPath != null,
        )
        val sourceLang = requireNotNull(SourceLangId.fromCode(lang!!)) { "unknown language $lang" }
        val paradigm = requireNotNull(InflectionParadigms[sourceLang]) { "$lang has no paradigm" }
        val cfg = requireNotNull(LANGUAGES[lang]) { "$lang has no oracle configuration (OracleLanguage)" }
        val dumpDir = File(dumpPath!!)
        assertTrue("cannot create $dumpDir", dumpDir.isDirectory || dumpDir.mkdirs())
        val sample = System.getenv(SAMPLE_ENV)?.toIntOrNull()
        val rowReasons = reasons("/inflection/$lang/no_label_reasons.tsv")
        val pageReasons = reasons("/inflection/$lang/page_no_label_reasons.tsv")

        val ctx: Context = ApplicationProvider.getApplicationContext()
        stagePack(ctx, File(packPath!!), sourceLang)
        val locale = sourceLang.locale
        val dbFile = File(packPath, DICT_DB)
        val lemmas = packLemmas(dbFile)

        // The oracle, streamed and grouped by surface so each surface is looked
        // up once. Only expectations naming a pack lemma are kept; a surface
        // whose every expectation names a lemma the pack lacks is counted once.
        val bySurface = HashMap<String, MutableList<Expectation>>()
        val onlyForeignLemmas = HashSet<String>()
        File(formsPath!!).useLines(Charsets.UTF_8) { lines ->
            for (line in lines) {
                val f = line.split('\t', limit = 5)
                if (f.size < 4) continue
                val surface = f[0].lowercase(locale)
                val lemma = f[1].lowercase(locale)
                if (lemma in lemmas) {
                    bySurface.getOrPut(surface) { ArrayList(1) }.add(Expectation(lemma, f[2], f[3]))
                    onlyForeignLemmas.remove(surface)
                } else if (surface !in bySurface) {
                    onlyForeignLemmas.add(surface)
                }
            }
        }
        val surfaces = bySurface.keys.sorted().let { all ->
            if (sample == null || sample >= all.size) all else all.shuffled(kotlin.random.Random(SEED)).take(sample)
        }

        val failed = linkedMapOf<String, Bucket>()
        fun fail(name: String) = failed.getOrPut(name) { Bucket(name) }
        val excused = sortedMapOf<String, Int>()
        fun excuse(rule: String) { excused[rule] = (excused[rule] ?: 0) + 1 }
        val fired = HashSet<String>()
        var checked = 0
        var agreed = 0
        val unknownPageTags = sortedMapOf<String, Int>()
        val unlabelledPageTagsets = sortedMapOf<String, Int>()
        val mismatches = sortedMapOf<String, Int>()
        val mismatchExamples = sortedMapOf<String, JSONArray>()
        val unresolvedLabels = sortedMapOf<String, Int>()

        runBlocking {
            val engine = SourceLanguageEngines.get(ctx, sourceLang)
            assertEquals(PreloadResult.Success, engine.preload())
            excused["$EXCUSE_LEMMA_NOT_IN_PACK: ${REASONS.getValue(EXCUSE_LEMMA_NOT_IN_PACK)}"] = onlyForeignLemmas.size
            for (surface in surfaces) {
                val expected = bySurface.getValue(surface)
                val response = engine.lookup(surface)
                val entries = response?.entries.orEmpty()
                val headword = entries.firstOrNull()?.slug
                val lines = InflectionChain.lines(
                    surface, response?.deinflection.orEmpty(), emptyList(),
                    InflectionParadigms.readingsOf(sourceLang, entries, headword),
                )
                for (e in expected) {
                    checked++
                    val pageTags = e.tags.split(' ').filterTo(LinkedHashSet()) { it.isNotEmpty() }
                    // Usage notes describe the word, not the form, and are removed
                    // before the page is labelled; what remains is compared whole.
                    val usage = pageTags.filter { it in cfg.usageTags }
                    usage.forEach { fired += "usage:$it" }
                    val grammatical = pageTags.filterTo(LinkedHashSet()) { it !in cfg.usageTags }
                    val pageLabel = paradigm.label(listOf(InflectionForm(surface, grammatical)))
                    val byLemma = entries.filter { it.slug == e.lemma }
                    val matching = byLemma.filter { e.pos in posOf(it) }
                    val readings = matching.flatMap { it.formReadings }
                    val readingLabels = readings.mapNotNull { paradigm.label(listOf(it)) }.distinct()
                    val pageAtoms = atoms(pageLabel)
                    val readingAtoms = readingLabels.flatMapTo(HashSet()) { atoms(it) }
                    val omitted = pageTags.any { it in cfg.tableOmitsTags }
                    val example = {
                        JSONObject().put("surface", surface).put("lemma", e.lemma).put("pos", e.pos)
                            .put("pageTags", JSONArray(pageTags.toList())).put("pageLabel", pageLabel)
                            .put("entries", JSONArray(entries.map { "${it.slug} (${posOf(it).joinToString(",")})" }))
                            .put("readings", JSONArray(readings.map { it.tags.sorted().joinToString(" ") }))
                            .put("readingLabels", JSONArray(readingLabels))
                    }
                    when {
                        pageLabel == null -> {
                            val unknown = grammatical.filterNot { paradigm.isKnown(it) }
                            val key = grammatical.sorted().joinToString(" ")
                            when {
                                unknown.isNotEmpty() -> {
                                    unknown.forEach { unknownPageTags[it] = (unknownPageTags[it] ?: 0) + 1 }
                                    fail(PAGE_TAG_UNEXPLAINED).add(example)
                                }
                                grammatical.isEmpty() -> excuse("$EXCUSE_PAGE_USAGE_ONLY: ${REASONS.getValue(EXCUSE_PAGE_USAGE_ONLY)}")
                                key in pageReasons -> {
                                    fired += "page:$key"
                                    excuse("$EXCUSE_PAGE_SAYS_NOTHING: ${REASONS.getValue(EXCUSE_PAGE_SAYS_NOTHING)}")
                                }
                                else -> {
                                    unlabelledPageTagsets[key] = (unlabelledPageTagsets[key] ?: 0) + 1
                                    fail(PAGE_UNLABELLED_UNEXPLAINED).add(example)
                                }
                            }
                        }
                        response == null && omitted -> excuse("$EXCUSE_OMITTED_FORM: ${REASONS.getValue(EXCUSE_OMITTED_FORM)}")
                        response == null -> {
                            val rule = cfg.unresolvedExcuses.firstOrNull { it.applies(pageLabel) }
                            val gap = cfg.unresolvedSurfaceExcuses[surface]
                            if (rule != null) {
                                fired += "unresolved:${rule.reason}"
                                excuse("$EXCUSE_UNRESOLVED_BY_DESIGN: ${rule.reason}")
                            } else if (gap != null) {
                                fired += "unresolvedSurface:$surface"
                                excuse("$EXCUSE_UNRESOLVED_GAP: $gap")
                            } else {
                                unresolvedLabels[pageLabel] = (unresolvedLabels[pageLabel] ?: 0) + 1
                                fail(UNRESOLVED).add(example)
                            }
                        }
                        byLemma.isEmpty() && entries.any { it.slug == surface } -> {
                            excuse("$EXCUSE_OWN_ENTRY: ${REASONS.getValue(EXCUSE_OWN_ENTRY)}")
                        }
                        byLemma.isEmpty() -> {
                            val reason = cfg.lemmaNotReturnedExcuses["$surface|${e.lemma}"]
                            val rule = cfg.lemmaNotReturnedRules.firstOrNull { it.applies(pageLabel) }
                            when {
                                reason != null -> { fired += "notReturned:$surface|${e.lemma}"; excuse("$EXCUSE_LEMMA_NOT_RETURNED: $reason") }
                                rule != null -> { fired += "notReturnedRule:${rule.reason}"; excuse("$EXCUSE_LEMMA_NOT_RETURNED: ${rule.reason}") }
                                else -> fail(LEMMA_NOT_RETURNED).add(example)
                            }
                        }
                        matching.isEmpty() -> excuse("$EXCUSE_LEMMA_POS_NOT_IN_PACK: ${REASONS.getValue(EXCUSE_LEMMA_POS_NOT_IN_PACK)}")
                        // The surface is the lemma itself (a Portuguese -ar verb's future
                        // subjunctive and personal infinitive are its infinitive): the
                        // lookup shows the headword, and the manager reads no readings
                        // for a lemma match, by design.
                        surface == e.lemma -> excuse("$EXCUSE_SURFACE_IS_LEMMA: ${REASONS.getValue(EXCUSE_SURFACE_IS_LEMMA)}")
                        readings.isEmpty() && omitted -> excuse("$EXCUSE_OMITTED_FORM: ${REASONS.getValue(EXCUSE_OMITTED_FORM)}")
                        readings.isEmpty() -> {
                            // The entry's blob has the surface on a row the paradigm
                            // leaves unlabelled: by design only when the row's tagset
                            // has its reason.
                            val rows = matching.mapNotNull { engine.inflectionTable(it) }.flatten()
                                .filter { it.surface == surface && !it.fromPronominalVariant }
                            when {
                                rows.isEmpty() -> fail(NO_READING).add(example)
                                rows.all { it.tags.sorted().joinToString(" ") in rowReasons } -> {
                                    rows.forEach { fired += "row:${it.tags.sorted().joinToString(" ")}" }
                                    excuse("$EXCUSE_ROW_UNLABELLED: ${REASONS.getValue(EXCUSE_ROW_UNLABELLED)}")
                                }
                                else -> fail(READING_UNLABELLED).add { example().put("rows", JSONArray(rows.map { it.tags.sorted().joinToString(" ") })) }
                            }
                        }
                        else -> {
                            val agree = readingAtoms.containsAll(pageAtoms)
                            val allowance = if (agree) null else cfg.allowances.firstOrNull { it.applies(e.pos, surface, pageAtoms, readingAtoms) }
                            val disagreement = if (agree || allowance != null) null
                                else cfg.knownDisagreements["$surface|${e.lemma}"]?.also { fired += "known:$surface|${e.lemma}" }
                                    ?: cfg.knownDisagreements["*|${e.lemma}"]?.also { fired += "known:*|${e.lemma}" }
                            when {
                                !agree && allowance == null && disagreement == null && omitted ->
                                    excuse("$EXCUSE_OMITTED_FORM: ${REASONS.getValue(EXCUSE_OMITTED_FORM)}")
                                !agree && allowance == null && disagreement == null -> {
                                    val key = "$pageLabel -> ${readingLabels.joinToString(" | ")}"
                                    mismatches[key] = (mismatches[key] ?: 0) + 1
                                    mismatchExamples.getOrPut(key) { JSONArray() }.let { if (it.length() < MISMATCH_EXAMPLES) it.put(example()) }
                                    fail(LABEL_MISMATCH).add(example)
                                }
                                lines.none { l -> l.paradigm?.let { p -> p.forms.any { f -> f in readings } } == true } ->
                                    fail(LINE_MISSING).add(example)
                                else -> {
                                    if (allowance != null) { fired += "allow:${allowance.reason}"; excuse("$EXCUSE_ALLOWANCE: ${allowance.reason}") }
                                    if (disagreement != null) excuse("$EXCUSE_KNOWN_DISAGREEMENT: $disagreement")
                                    agreed++
                                }
                            }
                        }
                    }
                }
                // A form-of page names a part of speech. An entry of an expected
                // lemma whose pos no page of this surface names, yet carrying a
                // form-of line for the surface, took a sibling's page (ser the
                // noun with the verb's `fue`): the builder matched the page by
                // word alone. Table-sourced readings are the entry's own and
                // are not judged here.
                val pagePos = expected.groupBy({ it.lemma }, { it.pos })
                for (entry in entries) {
                    val posOfPages = pagePos[entry.slug] ?: continue
                    val fromPages = entry.formReadings.filter { "form-of" in it.tags }
                    if (fromPages.isEmpty()) continue
                    if (posOf(entry).any { it in posOfPages }) continue
                    fail(READING_ON_OTHER_POS).add {
                        JSONObject().put("surface", surface).put("lemma", entry.slug)
                            .put("entryPos", JSONArray(posOf(entry))).put("pagePos", JSONArray(posOfPages.distinct()))
                            .put("readings", JSONArray(fromPages.map { it.tags.sorted().joinToString(" ") }))
                    }
                }
            }

            // Every line of the table against its own surface.
            var tableLines = 0
            for ((lemma, entryId) in inflectionEntries(dbFile)) {
                val forms = engine.inflectionTable(stubEntry(lemma, entryId)) ?: continue
                for (f in forms) {
                    if (f.fromPronominalVariant) continue
                    tableLines++
                    val cls = paradigm.classify(f)
                    val example = { JSONObject().put("lemma", lemma).put("surface", f.surface).put("label", cls?.label).put("tags", JSONArray(f.tags.sorted())) }
                    if (cls == null) {
                        // Unlabelled by design only with a reason (the golden test
                        // holds the same rule over the tagsets; this holds it over
                        // the lines).
                        val key = f.tags.sorted().joinToString(" ")
                        if (key in rowReasons) fired += "row:$key" else fail(ROW_UNLABELLED_UNEXPLAINED).add(example)
                        continue
                    }
                    val lead = f.surface.substringBefore(' ')
                    val pronouns = cls.label.substringBefore(" · ", "").split(" / ").filter { it.isNotEmpty() }
                    if (' ' in f.surface && lead in cfg.leadingClitics && pronouns.isNotEmpty() &&
                        pronouns.none { it in cfg.leadingClitics.getValue(lead) }
                    ) fail(PRONOMINAL_DISAGREES).add(example)
                    // The participle of a phrasal lemma may follow an adverb (mejor
                    // valido): any word of the surface may carry the ending.
                    cfg.participleEndings[cls.label.substringBefore(" + ")]?.let { endings ->
                        if (f.surface.split(' ').none { w -> endings.any { w.endsWith(it) } }) fail(PARTICIPLE_AGREEMENT).add(example)
                    }
                }
            }

            // The grid, judged by the classification it was built from.
            var tables = 0
            for ((lemma, entryId) in verbEntries(dbFile)) {
                val forms = engine.inflectionTable(stubEntry(lemma, entryId)) ?: continue
                val table = paradigm.table(lemma, forms, highlight = null) ?: continue
                tables++
                // What the grid draws from: the lemma's own verbal, non-clitic
                // lines; a plain lemma's clitic-led two-word forms (me voy under
                // ir, from a table the builder did not mark) are not its own.
                val pronominal = cfg.pronominalLemma(lemma)
                val verbal = forms.filterNot { it.fromPronominalVariant }
                    .mapNotNull { f -> paradigm.classify(f)?.takeIf { it.verbal && !it.clitic }?.let { f to it } }
                    .filter { (f, _) -> pronominal || !(' ' in f.surface && f.surface.substringBefore(' ') in cfg.variantLeadClitics) }
                val plainSurfaces = verbal.filterNot { it.second.voseo }.mapTo(HashSet()) { it.first.surface }
                val voseoSurfaces = verbal.filter { it.second.voseo }.mapTo(HashSet()) { it.first.surface }
                // Placed: every finite form, and every bare non-finite form (the
                // agreeing participles are labelled, not listed).
                val cells = verbal.filter { it.second.finite || it.second.agreement == null }
                val placed = HashSet<String>()
                for (s in table.sections) for (r in s.rows) for ((ci, c) in r.cells.withIndex()) {
                    val where = { JSONObject().put("lemma", lemma).put("section", s.title).put("row", r.label).put("column", s.columns.getOrNull(ci)) }
                    // The negativo column writes "no " before the bare form the pack stores.
                    for (m in c.forms.map { it.removePrefix("no ") }) {
                        placed += m
                        if (m !in plainSurfaces) fail(GRID_MAIN_NOT_PLAIN).add { where().put("form", m) }
                    }
                    for (v in c.secondary) {
                        placed += v
                        if (v !in voseoSurfaces) fail(GRID_COMPANION_NOT_VOSEO).add { where().put("form", v) }
                    }
                }
                for ((f, cls) in cells) if (f.surface !in placed) {
                    fail(GRID_UNPLACED).add { JSONObject().put("lemma", lemma).put("surface", f.surface).put("label", cls.label).put("tags", JSONArray(f.tags.sorted())) }
                }
            }

            // An allowance or excuse that never fired is stale: it would
            // forgive a case that no longer exists, or never did.
            val stale = (cfg.allowances.map { "allow:${it.reason}" } + cfg.knownDisagreements.keys.map { "known:$it" } +
                cfg.usageTags.keys.map { "usage:$it" } + cfg.unresolvedExcuses.map { "unresolved:${it.reason}" } +
                cfg.unresolvedSurfaceExcuses.keys.map { "unresolvedSurface:$it" } +
                cfg.lemmaNotReturnedExcuses.keys.map { "notReturned:$it" } + cfg.lemmaNotReturnedRules.map { "notReturnedRule:${it.reason}" } +
                pageReasons.keys.map { "page:$it" })
                .filter { it !in fired }
            for (s in stale) fail(STALE_ALLOWLIST).add { JSONObject().put("entry", s) }

            val report = JSONObject().apply {
                put("lang", lang)
                put("scope", JSONObject().put("surfaces", surfaces.size).put("expectationsChecked", checked).put("tableLines", tableLines).put("tables", tables))
                put("agreed", agreed)
                put("excused", JSONObject(excused as Map<*, *>))
                put("mustBeZero", JSONArray(MUST_BE_ZERO))
                put("failed", JSONObject().apply {
                    for (b in failed.values) put(b.name, JSONObject().put("count", b.count).put("examples", b.examples))
                })
                put("labelMismatches", JSONObject(mismatches as Map<*, *>))
                put("labelMismatchExamples", JSONObject(mismatchExamples as Map<*, *>))
                put("unknownPageTags", JSONObject(unknownPageTags as Map<*, *>))
                put("unlabelledPageTagsets", JSONObject(unlabelledPageTagsets as Map<*, *>))
                put("unresolvedByLabel", JSONObject(unresolvedLabels as Map<*, *>))
            }
            File(dumpDir, "oracle.json").writeText(report.toString(2), Charsets.UTF_8)
            println("inflection oracle [$lang]: surfaces=${surfaces.size} checked=$checked agreed=$agreed tableLines=$tableLines tables=$tables " +
                "excused=${excused.values.sum()} failed: " + (failed.values.filter { it.count > 0 }.joinToString(" ") { "${it.name}=${it.count}" }.ifEmpty { "none" }))

            val failing = MUST_BE_ZERO.filter { (failed[it]?.count ?: 0) > 0 }
            assertTrue(
                "inflection oracle [$lang]: ${failing.joinToString { "$it=${failed.getValue(it).count}" }} must be zero; " +
                    "examples in ${File(dumpDir, "oracle.json")}",
                failing.isEmpty(),
            )
        }
    }

    /** A label's (pronoun, name) pairs: "yo / él, ella, usted · presente /
     *  pretérito; gerundio" names (yo, presente), (yo, pretérito), (él, ella,
     *  usted, presente), (él, ella, usted, pretérito) and (null, gerundio). */
    private fun atoms(label: String?): Set<Atom> = label?.split("; ")?.flatMapTo(HashSet()) { group ->
        val pronouns = if (" · " in group) group.substringBefore(" · ").split(" / ") else listOf(null)
        val names = group.substringAfter(" · ").split(" / ")
        pronouns.flatMap { p -> names.map { n -> Atom(p, n) } }
    } ?: emptySet()

    private fun posOf(entry: com.playtranslate.model.DictionaryEntry): List<String> =
        entry.senses.flatMap { it.partsOfSpeech }.filterNot { it.startsWith("[") }

    /** A reasons file: `tags \t reason`, `#` comments; the keys are space-joined sorted tags. */
    private fun reasons(resource: String): Map<String, String> =
        javaClass.getResourceAsStream(resource)?.use { it.readBytes().toString(Charsets.UTF_8) }?.lines()
            ?.filter { it.isNotEmpty() && !it.startsWith("#") }
            ?.associate { it.substringBefore('\t') to it.substringAfter('\t') } ?: emptyMap()

    private fun stubEntry(lemma: String, entryId: Long) = com.playtranslate.model.DictionaryEntry(
        slug = lemma, packId = entryId, isCommon = null, tags = emptyList(), jlpt = emptyList(),
        headwords = emptyList(), senses = emptyList(),
    )

    private fun packLemmas(db: File): Set<String> =
        SQLiteDatabase.openDatabase(db.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { c ->
            val out = HashSet<String>()
            c.rawQuery("SELECT text FROM headword WHERE position = 0", null).use { cur -> while (cur.moveToNext()) out.add(cur.getString(0)) }
            out
        }

    private fun verbEntries(db: File): List<Pair<String, Long>> =
        SQLiteDatabase.openDatabase(db.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { c ->
            val out = ArrayList<Pair<String, Long>>()
            c.rawQuery(
                "SELECT h.text, h.entry_id FROM headword h JOIN sense s ON s.entry_id = h.entry_id AND s.position = 0 " +
                    "WHERE h.position = 0 AND s.pos = 'verb' ORDER BY h.entry_id", null,
            ).use { cur -> while (cur.moveToNext()) out.add(cur.getString(0) to cur.getLong(1)) }
            out
        }

    /** Every entry with an inflection blob, with its lemma. */
    private fun inflectionEntries(db: File): List<Pair<String, Long>> =
        SQLiteDatabase.openDatabase(db.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { c ->
            val out = ArrayList<Pair<String, Long>>()
            c.rawQuery(
                "SELECT h.text, i.entry_id FROM inflection i JOIN headword h ON h.entry_id = i.entry_id AND h.position = 0 ORDER BY i.entry_id", null,
            ).use { cur -> while (cur.moveToNext()) out.add(cur.getString(0) to cur.getLong(1)) }
            out
        }

    private fun stagePack(ctx: Context, pack: File, lang: SourceLangId) {
        val dir = LanguagePackStore.dirFor(ctx, lang)
        for (name in listOf(DICT_DB, MANIFEST)) {
            val source = File(pack, name).absoluteFile
            assertTrue("pack file missing: $source", source.isFile)
            val link = File(dir, name)
            assertTrue("cannot create ${link.parentFile}", link.parentFile!!.isDirectory || link.parentFile!!.mkdirs())
            Files.createSymbolicLink(link.toPath(), source.toPath())
        }
    }

    private companion object {
        const val LANG_ENV = "PLAYTRANSLATE_ORACLE_LANG"
        const val PACK_DIR_ENV = "PLAYTRANSLATE_ORACLE_PACK_DIR"
        const val FORMS_ENV = "PLAYTRANSLATE_ORACLE_FORMS"
        const val DUMP_DIR_ENV = "PLAYTRANSLATE_ORACLE_DUMP_DIR"
        const val SAMPLE_ENV = "PLAYTRANSLATE_ORACLE_SAMPLE"
        const val DICT_DB = "dict.sqlite"
        const val MANIFEST = "manifest.json"
        const val SEED = 20261012L
        const val EXAMPLES = 40
        const val MISMATCH_EXAMPLES = 3

        // What fails the test.
        const val LABEL_MISMATCH = "labelMismatch"
        const val LINE_MISSING = "lineMissing"
        const val NO_READING = "noReading"
        const val READING_UNLABELLED = "readingUnlabelled"
        const val UNRESOLVED = "unresolved"
        const val LEMMA_NOT_RETURNED = "lemmaNotReturned"
        const val READING_ON_OTHER_POS = "readingOnOtherPos"
        const val PAGE_TAG_UNEXPLAINED = "pageTagUnexplained"
        const val PAGE_UNLABELLED_UNEXPLAINED = "pageUnlabelledUnexplained"
        const val ROW_UNLABELLED_UNEXPLAINED = "rowUnlabelledUnexplained"
        const val PRONOMINAL_DISAGREES = "pronominalDisagrees"
        const val PARTICIPLE_AGREEMENT = "participleAgreement"
        const val GRID_UNPLACED = "gridUnplaced"
        const val GRID_MAIN_NOT_PLAIN = "gridMainNotPlain"
        const val GRID_COMPANION_NOT_VOSEO = "gridCompanionNotVoseo"
        const val STALE_ALLOWLIST = "staleAllowlist"
        val MUST_BE_ZERO = listOf(
            LABEL_MISMATCH, LINE_MISSING, NO_READING, READING_UNLABELLED, UNRESOLVED, LEMMA_NOT_RETURNED, READING_ON_OTHER_POS,
            PAGE_TAG_UNEXPLAINED, PAGE_UNLABELLED_UNEXPLAINED, ROW_UNLABELLED_UNEXPLAINED,
            PRONOMINAL_DISAGREES, PARTICIPLE_AGREEMENT, GRID_UNPLACED, GRID_MAIN_NOT_PLAIN, GRID_COMPANION_NOT_VOSEO,
            STALE_ALLOWLIST,
        )

        // What excuses a page from the comparison, each a rule with its reason.
        const val EXCUSE_LEMMA_NOT_IN_PACK = "lemmaNotInPack"
        const val EXCUSE_LEMMA_POS_NOT_IN_PACK = "lemmaPosNotInPack"
        const val EXCUSE_PAGE_USAGE_ONLY = "pageUsageOnly"
        const val EXCUSE_PAGE_SAYS_NOTHING = "pageSaysNothing"
        const val EXCUSE_OMITTED_FORM = "omittedForm"
        const val EXCUSE_OWN_ENTRY = "ownEntry"
        const val EXCUSE_UNRESOLVED_BY_DESIGN = "unresolvedByDesign"
        const val EXCUSE_UNRESOLVED_GAP = "unresolvedGap"
        const val EXCUSE_SURFACE_IS_LEMMA = "surfaceIsLemma"
        const val EXCUSE_LEMMA_NOT_RETURNED = "lemmaNotReturned"
        const val EXCUSE_ROW_UNLABELLED = "rowUnlabelled"
        const val EXCUSE_ALLOWANCE = "allowance"
        const val EXCUSE_KNOWN_DISAGREEMENT = "knownDisagreement"
        val REASONS = mapOf(
            EXCUSE_LEMMA_NOT_IN_PACK to "the page's lemma is not a pack headword: nothing to compare",
            EXCUSE_LEMMA_POS_NOT_IN_PACK to "the pack has the page's lemma in no entry of the page's part of speech (angular the adjective, the page's verb): no entry could carry the reading",
            EXCUSE_PAGE_USAGE_ONLY to "the page carries no grammatical tag (only usage notes, or none at all): a variant of the word, not a form of it",
            EXCUSE_SURFACE_IS_LEMMA to "the surface is the lemma itself (orlar, a Portuguese verb's future subjunctive and personal infinitive): the lookup shows the headword and reads no line for it, by design",
            EXCUSE_PAGE_SAYS_NOTHING to "the page's grammatical tags are all known and say nothing the line would carry; the tagset is named in page_no_label_reasons.tsv",
            EXCUSE_OMITTED_FORM to "the page is of an obsolete, rare, nonstandard or proscribed form (avemos, cocho, dijistes, sabo; sé the archaic imperative of saber): the table lists the standard current forms and the alias pass follows it",
            EXCUSE_OWN_ENTRY to "the surface is an entry of its own (pajarito), which the lookup rightly returns; the page's lemma is extra",
            EXCUSE_ROW_UNLABELLED to "the entry's row for the surface is one the paradigm leaves unlabelled by design, named in no_label_reasons.tsv",
        )

        val LANGUAGES: Map<String, OracleLanguage> by lazy { mapOf("es" to spanish(), "pt" to portuguese(), "fr" to french()) }

        /** A nominal pair with its gender word removed ("masculino plural" → "plural"). */
        fun Atom.genderless(genderWords: Set<String>): Atom? =
            name.split(' ').filterNot { it in genderWords }.joinToString(" ").ifEmpty { null }?.let { Atom(pronoun, it) }

        // The allowances the Romance languages share, each parameterized by the
        // language's gender words or its participle's name.
        private fun nounGenderAllowance(genderWords: Set<String>) = Allowance(
            "a noun's page carries the lemma's gender (\"masculine plural of coche\"; \"masculine plural of albañil\" beside a row tagged feminine plural); " +
                "nouns do not inflect for gender, so a noun page's gender is not compared",
        ) { pos, _, page, readings ->
            pos == "noun" && page.all { it.pronoun == null } &&
                readings.mapNotNullTo(HashSet()) { it.genderless(genderWords) }.containsAll(page.mapNotNull { it.genderless(genderWords) })
        }
        private fun oneSidedGenderAllowance(genderWords: Set<String>) = Allowance(
            "kaikki tags some adjective, numeral and determiner plurals \"plural\" alone on one side and \"masculine plural\" on the other (abanderados, aquéllos): " +
                "gender is compared only when both sides say it",
        ) { _, _, page, readings ->
            val nominal = page.all { it.pronoun == null } && readings.all { it.pronoun == null }
            val pageSaysGender = page.any { a -> a.name.split(' ').any { it in genderWords } }
            val readingsSayGender = readings.any { a -> a.name.split(' ').any { it in genderWords } }
            nominal && pageSaysGender != readingsSayGender &&
                readings.mapNotNullTo(HashSet()) { it.genderless(genderWords) }.containsAll(page.mapNotNull { it.genderless(genderWords) })
        }
        private fun participleLemmaAllowance(participle: String) = Allowance(
            "a participle lemma's own page (sacrificado, a verb page) lists its agreement as headline forms (feminine, plural), " +
                "and the page of such a form calls it a participle",
        ) { _, _, page, readings ->
            page.isNotEmpty() && page.all { it.pronoun == null && it.name.startsWith("$participle ") } &&
                readings.containsAll(page.map { Atom(null, it.name.removePrefix("$participle ")) })
        }
        private val OMITTED = setOf("obsolete", "archaic", "dated", "rare", "uncommon", "nonstandard", "proscribed")
        private fun superlativeIsComparativeAllowance(superlative: String, comparative: String) = Allowance(
            "a page calling a comparative (melhor, meilleur) a superlative: the Romance relative superlative is the article plus the comparative, " +
                "so the page is not wrong and neither is the row, which says comparative",
        ) { _, _, page, readings -> page == setOf(Atom(null, superlative)) && Atom(null, comparative) in readings }
        private fun defectiveFirstThirdAllowance(first: String, third: String) = Allowance(
            "kaikki's form-of template for the first and third person singular lists both even for a defective verb (gear, saillir, venter) " +
                "whose table has only the third person: the page's first person is not compared",
        ) { _, _, page, readings ->
            page.isNotEmpty() && page.any { it.pronoun == first } && page.all { it in readings || (it.pronoun == first && Atom(third, it.name) in readings) }
        }
        private fun surfaceNumberAllowance(pluralWord: String, pluralEndings: List<String>) = Allowance(
            "a page and a row disagree on a noun's or adjective's number, and the row agrees with the surface's ending (natas, potentielle): the page is wrong",
        ) { _, surface, page, readings ->
            val nominal = page.isNotEmpty() && page.all { it.pronoun == null } && readings.isNotEmpty() && readings.all { it.pronoun == null }
            val surfacePlural = pluralEndings.any { surface.endsWith(it) }
            val rowPlural = readings.all { pluralWord in it.name.split(' ') }
            val pagePlural = page.all { pluralWord in it.name.split(' ') }
            nominal && rowPlural == surfacePlural && pagePlural != surfacePlural
        }

        private fun portuguese(): OracleLanguage {
            val genderWords = setOf("masculino", "feminino", "neutro")
            val evaluative = UnresolvedExcuse("a diminutive or augmentative: the alias pass does not index them (build_latin_dict.FORM_TAG_BLOCKLIST); the lookup finds nothing, or falls to a stem") { label ->
                label.split(' ').any { it == "diminutivo" || it == "aumentativo" }
            }
            return OracleLanguage(
                tableOmitsTags = OMITTED,
                genderWords = genderWords,
                usageTags = mapOf(
                    // regions
                    "South-Brazil" to "a region", "Northern" to "a region", "Rio-de-Janeiro" to "a region", "Southern" to "a region",
                    "Caipira" to "a regional dialect", "Madeira" to "a region", "Greek" to "an origin note",
                    // registers and usage
                    "nonstandard" to "a nonstandard form", "rare" to "a usage note", "colloquial" to "a register", "endearing" to "a register",
                    "neologism" to "a usage note", "euphemistic" to "a register", "slang" to "a register", "dated" to "a usage note",
                    "derogatory" to "a register", "dialectal" to "a usage note", "historical" to "a usage note", "obsolete" to "a usage note",
                    "vulgar" to "a register", "childish" to "a register", "ethnic" to "a usage note", "familiar" to "a register",
                    "literary" to "a register", "offensive" to "a register", "slur" to "a register", "ironic" to "a register",
                    // lexical categories and notes
                    "plural-only" to "a plurale tantum note", "by-personal-gender" to "a noun whose gender follows the referent",
                    "invariable" to "an inflection note", "irregular" to "an inflection note", "gender-neutral" to "a gender note",
                    "pronoun" to "a pronoun's category", "countable" to "a noun's countability", "uncountable" to "a noun's countability",
                    "demonstrative" to "a determiner's category", "morpheme" to "a lexical note", "definite" to "a determiner's category",
                    "indefinite" to "a determiner's category", "with-definite-article" to "a lexical note", "agent" to "a lexical note",
                    "prepositional" to "a lexical note", "relative" to "a pronoun's category",
                    // a pronoun's case, which the Portuguese paradigm does not read (no clitic rows in its tables)
                    "dative" to "a pronoun's case", "accusative" to "a pronoun's case", "reflexive" to "a pronoun's reflexive form",
                    // the paradigm's own regions and usage notes, stripped here too so a page of only those is usage-only
                    "Brazil" to "a region", "Portugal" to "a region", "uncommon" to "a usage note",
                    // template connectives
                    "often" to "a template's connective", "possibly" to "a template's connective", "usually" to "a template's connective",
                ),
                allowances = listOf(
                    nounGenderAllowance(genderWords), oneSidedGenderAllowance(genderWords), participleLemmaAllowance("particípio"),
                    defectiveFirstThirdAllowance("eu", "ele, ela, você"),
                    superlativeIsComparativeAllowance("superlativo", "comparativo"),
                    surfaceNumberAllowance("plural", listOf("s")),
                    Allowance("kaikki's page template calls -inho / -inha forms (arrumadinho, baratinho) augmentative; the suffix is the diminutive the row says") { _, surface, page, readings ->
                        page == setOf(Atom(null, "aumentativo")) && Atom(null, "diminutivo") in readings &&
                            listOf("inho", "inha", "inhos", "inhas").any { surface.endsWith(it) }
                    },
                ),
                knownDisagreements = mapOf(
                    "convido|convidar" to "the page calls convido a past participle; it is the first-person present the row says",
                    "*|gear" to "gear is a weather verb: its table has third-person rows only, the pages list every person and an imperative by template",
                    "rapidinho|rápido" to "the page calls rapidinho an augmentative, the row a superlative; it is a diminutive adverb and neither is read",
                    "tapão|tapa" to "the page says augmentative masculine, the row augmentative plural; the row is wrong on the number",
                    "tapinha|tapa" to "the row calls tapinha a plural diminutive; it is singular, as the page says: the line is wrong here",
                    "alfacinha|alface" to "the row calls alfacinha a plural diminutive; it is singular, as the page says: the line is wrong here",
                    "cabrão|cabra" to "the row says masculine only, the page augmentative masculine; the row misses the degree",
                    "bem-postos|bem-posto" to "the page calls the -os form feminine; the row says masculine",
                    "vivas|vivo" to "the page calls the -as form masculine; the row says feminine",
                    "gulosos|guloso" to "the page calls the -os form feminine; the row says masculine",
                    "*|redar" to "redar's pages and table disagree on several forms (redou, redemos): kaikki page errors; the line follows the table",
                    "entrevisto|entrevistar" to "the page calls entrevisto a participle of entrevistar (it is entrever's); the row is the first-person present",
                    "empregue|empregar" to "empregue is both the subjunctive the row says and a short participle the table lacks",
                    "segunde|segundar" to "the page lists an imperative segundar's table lacks; the row is the subjunctive",
                ),
                unresolvedExcuses = listOf(evaluative),
                unresolvedSurfaceExcuses = mapOf(
                    "acérrimo" to "the superlative of acre, which the pack indexes neither as an alias nor as a lemma",
                    "sagitária" to "the feminine of sagitário, which the pack indexes neither as an alias nor as a lemma",
                    "-eus" to "a suffix page; the pack keeps no suffix entries",
                    "-guaçus" to "a suffix page; the pack keeps no suffix entries",
                    "namorade" to "a gender-neutral neologism the pack indexes neither as an alias nor as a lemma",
                    "pegador" to "an agent noun page of pegar the pack indexes neither as an alias nor as a lemma",
                ),
                lemmaNotReturnedExcuses = emptyMap(),
                lemmaNotReturnedRules = listOf(evaluative),
                leadingClitics = emptyMap(),
                // Participles in -ado/-ido, and the short forms in -e (aceite, entregue).
                participleEndings = mapOf(
                    "particípio" to listOf("o", "e", "a"), "particípio feminino" to listOf("a", "e"),
                    "particípio masculino plural" to listOf("os", "es"), "particípio feminino plural" to listOf("as", "es"),
                ),
                pronominalLemma = { it.endsWith("-se") },
            )
        }

        private fun french(): OracleLanguage {
            val genderWords = setOf("masculin", "féminin")
            return OracleLanguage(
                tableOmitsTags = OMITTED,
                genderWords = genderWords,
                usageTags = mapOf(
                    // regions
                    "Quebec" to "a region", "Switzerland" to "a region", "North-America" to "a region",
                    // registers and usage
                    "rare" to "a usage note", "archaic" to "a usage note", "nonstandard" to "a nonstandard form", "obsolete" to "a usage note",
                    "dated" to "a usage note", "slang" to "a register", "colloquial" to "a register", "humorous" to "a register",
                    "euphemistic" to "a register", "neologism" to "a usage note", "poetic" to "a register", "endearing" to "a register",
                    "familiar" to "a register", "broadly" to "a usage note", "ethnic" to "a usage note", "historical" to "a usage note",
                    "ironic" to "a register", "literary" to "a register", "offensive" to "a register", "slur" to "a register", "vulgar" to "a register",
                    "uncommon" to "a usage note",
                    // lexical categories and notes
                    "intransitive" to "a verb's valency", "plural-only" to "a plurale tantum note", "plural-normally" to "a number note",
                    "gender-neutral" to "a gender note", "invariable" to "an inflection note", "unabbreviated" to "a lexical note",
                    "agent" to "a lexical note", "disjunctive" to "a pronoun's category", "noun-from-verb" to "a lexical note",
                    "active" to "a lexical note", "clitic" to "a pronoun's category", "impersonal" to "a verb's category",
                    "relative" to "a pronoun's category", "contracted" to "a lexical note", "contraction" to "a lexical note",
                    "emphatic" to "a lexical note", "in-compounds" to "a lexical note", "morpheme" to "a lexical note",
                    "possessive" to "a determiner's category", "pronoun" to "a pronoun's category", "uncountable" to "a noun's countability",
                    "with-definite-article" to "a lexical note",
                    // template connectives
                    "usually" to "a template's connective",
                ),
                allowances = listOf(
                    nounGenderAllowance(genderWords), oneSidedGenderAllowance(genderWords), participleLemmaAllowance("participe passé"),
                    defectiveFirstThirdAllowance("je", "il, elle, on"),
                    superlativeIsComparativeAllowance("superlatif", "comparatif"),
                    surfaceNumberAllowance("pluriel", listOf("s", "x")),
                    Allowance("a page tagging a second-person -ez / -iez form singular (accueillissiez): the ending is plural, as the row says") { _, _, page, readings ->
                        page.isNotEmpty() && page.all { it.pronoun == "tu" && Atom("vous", it.name) in readings }
                    },
                    Allowance("a page tagging the conditional `present` too (the conditionnel présent, kaikki's own name for it): the extra présent is not compared") { _, _, page, readings ->
                        val pronouns = page.map { it.pronoun }.toSet()
                        page.isNotEmpty() && pronouns.all { p -> Atom(p, "conditionnel") in page } &&
                            page.all { it in readings || (it.name == "présent" && Atom(it.pronoun, "conditionnel") in readings) }
                    },
                ),
                // Irregular verbs whose form-of pages and table disagree, by lemma, each with what kaikki got wrong.
                knownDisagreements = mapOf(
                    "*|départir" to "départir has two conjugations, like partir and like finir; the table lists one and the pages the other",
                    "*|faillir" to "faillir has two conjugations, like finir and a defective one; the table lists one and the pages the other",
                    "*|ressortir" to "ressortir is two verbs, like sortir and like finir; the table lists one and the pages the other",
                    "*|asseoir" to "asseoir has two conjugations (assieds, assois); the pages and the table split them differently",
                    "*|rasseoir" to "rasseoir has two conjugations; the pages and the table split them differently",
                    "*|surseoir" to "surseoir's pages and table disagree (sursis as a plural passé simple): kaikki page errors",
                    "*|haïr" to "haïr's Canadian pages (haïs, haït as present) describe a regional conjugation the table lacks",
                    "*|mouvoir" to "mouvoir's pages swap the present and imperfect subjunctive (meuve, meuves): kaikki page errors",
                    "*|pourvoir" to "pourvoir's pages swap the present and imperfect subjunctive: kaikki page errors",
                    "*|complaire" to "complaire's pages tag the imperfect subjunctive `conditional imperfect`: kaikki page errors",
                    "*|vivre" to "vivre's pages call the subjunctive vive an imperative of every person: kaikki page errors",
                    "*|mourir" to "mourir's page calls the subjunctive meure an imperative: kaikki page error",
                    "*|accroître" to "accroître's pages mix singular and plural and the passé simple with the subjunctive: kaikki page errors",
                    "*|prédire" to "prédire's pages mix singular and plural and the passé simple with the present: kaikki page errors",
                    "*|consentir" to "consentir's page calls the imperfect subjunctive consentisse present: kaikki page error",
                    "*|défaire" to "défaire's page calls the imparfait défaisiez a subjunctive: kaikki page error",
                    "*|refaire" to "refaire's page calls the imparfait refaisiez a subjunctive: kaikki page error",
                    "*|biaiser" to "biaiser's pages give biaises and biaise the wrong persons: kaikki page errors",
                    "*|solder" to "solder's page gives soldes the wrong persons: kaikki page error",
                    "*|enquérir" to "enquis is both the participle the table lists and the passé simple the page says; the table lacks the latter",
                    "*|entremettre" to "entremis is both the participle the table lists and the passé simple the page says; the table lacks the latter",
                    "*|absoudre" to "absout is both the present the table lists and a participle the page says; the table lacks the latter",
                    "*|dissoudre" to "dissout is both the present the table lists and a participle the page says; the table lacks the latter",
                    "*|croître" to "crûs is both the passé simple the table lists and the plural participle the page says; the table lacks the latter",
                    "*|recourir" to "recourir's page calls the conditional recourrait an imparfait: kaikki page error",
                    "*|traire" to "traire has no passé simple; the page gives trait one: kaikki page error",
                    "*|transparaître" to "transparaître's page calls the imparfait a present: kaikki page error",
                    "*|gésir" to "gésir's page gives gisez the third person: kaikki page error",
                    "*|valoir" to "valoir's page calls valent a subjunctive too (that is vaillent): kaikki page error",
                    "*|prévaloir" to "prévaloir's page calls prévalent an imperfect subjunctive: kaikki page error",
                    "*|ficher" to "fiche is a colloquial infinitive the page describes and the table does not list",
                    "*|coucher" to "coucher's page calls couche an imparfait: kaikki page error",
                    "*|circoncire" to "circoncire's page gives circoncis a third person it has not (circoncit): kaikki page error",
                    "*|recueillir" to "recueillir's page tags recueille both present and imperfect: kaikki page error",
                    "*|aller" to "je vas is a North American present the page describes; the table has the standard tu vas",
                    "*|relier" to "relier's page calls relia a first-person plural: kaikki page error",
                    "*|bénir" to "bénit is a participle variant (béni, bénit) the page describes and the table lacks",
                    "*|ouïr" to "ouïs is both the present and passé simple the table lists and the plural participle the page says",
                    "*|paître" to "paître's page gives paîtrons the second person: kaikki page error",
                    "*|troller" to "troller's pages give trolliez the wrong mood and tense: kaikki page errors",
                    "*|faire" to "faire's page calls fisses a second-person plural: kaikki page error",
                    "*|satisfaire" to "the page calls the verb form satisfaites a feminine plural adjective: kaikki page error",
                    "*|redire" to "redire's page gives redisent the singular: kaikki page error",
                    "*|émouvoir" to "émouvoir's pages swap the present and imperfect subjunctive (émeuve, émeuves): kaikki page errors",
                    "*|reboire" to "reboire's page calls the present reboit a subjunctive: kaikki page error",
                ),
                unresolvedExcuses = listOf(
                    UnresolvedExcuse("a diminutive: the alias pass does not index them (build_latin_dict.FORM_TAG_BLOCKLIST)") { label ->
                        label.split(' ').any { it == "diminutif" }
                    },
                ),
                unresolvedSurfaceExcuses = mapOf(
                    "bougresse" to "a Louisiana colloquial feminine of bougre the pack indexes neither as an alias nor as a lemma",
                ),
                lemmaNotReturnedExcuses = mapOf(
                    "des|une" to "an article page (des, plural of un/une) the pack does not index as a form",
                    "des|de la" to "an article page (des, plural of de la) the pack does not index as a form",
                    "des|de l'" to "an article page (des, plural of de l') the pack does not index as a form",
                    "venait|came" to "kaikki names the English gloss as the lemma",
                ),
                // A reflexive verb's own two-word rows: the pronoun before the verb and the person it agrees with.
                leadingClitics = mapOf(
                    "me" to setOf("je"), "m'en" to setOf("je"), "m'y" to setOf("je"),
                    "te" to setOf("tu"), "t'en" to setOf("tu"), "t'y" to setOf("tu"),
                    "se" to setOf("il, elle, on", "ils, elles"), "s'en" to setOf("il, elle, on", "ils, elles"), "s'y" to setOf("il, elle, on", "ils, elles"),
                    "nous" to setOf("nous"), "vous" to setOf("vous"),
                ),
                // The bare past participle ends in é, i, u, s or t; only the agreeing forms have one ending each.
                participleEndings = mapOf(
                    "participe passé féminin" to listOf("e"), "participe passé féminin pluriel" to listOf("es"), "participe passé masculin pluriel" to listOf("s"),
                ),
            )
        }

        private fun spanish(): OracleLanguage {
        val GENDER_WORDS = setOf("masculino", "femenino", "neutro")

        /** The principled differences between a page's label and the table's. */
        val ALLOWANCES = listOf(
            nounGenderAllowance(GENDER_WORDS),
            oneSidedGenderAllowance(GENDER_WORDS),
            participleLemmaAllowance("participio"),
            Allowance(
                "where the vos form coincides with the tú form (das, da, sé, estés), kaikki's table carries one row, tagged tú, " +
                    "and the page for vos names the same surface; the line says tú",
            ) { _, _, page, readings ->
                page.isNotEmpty() && page.all { it.pronoun == "vos" && Atom("tú", it.name) in readings }
            },
        )

        /** Pages and rows that differ on one form, by "surface|lemma": kaikki's two descriptions
         *  contradict each other and the labeller can take only one, or the page adds a reading the
         *  builder could not append because the table already lists the surface. */
        val KNOWN_DISAGREEMENTS = mapOf(
            "incauta|incauto" to "the page says feminine plural of incauto; incauta is the feminine singular the row says",
            "quinientas|quinientos" to "the page says feminine plural; the numeral's headline row says feminine (its feminine is plural by nature)",
            "mayor|grande" to "grande's table lists mayor as its superlative; the page (rightly) calls it the comparative. The line follows the table",
            "menor|joven" to "joven's table lists menor as its superlative; the page (rightly) calls it the comparative. The line follows the table",
            "peor|malo" to "the page calls peor the superlative of malo; the row (rightly) says comparative",
            "entendí|entender" to "the table lists entendí as yo · pretérito; the Chilean vos present shares the surface, and the builder keeps the table's row for a surface it has (append_form_of): the line shows the preterite",
            "sois|ser" to "the table lists sois as vosotros · presente; the Chilean vos present shares the surface, and the builder keeps the table's row for a surface it has (append_form_of): the line shows vosotros",
        )

        /** Tags a form-of page carries that describe the word, not the form (a region, a register, a lexical
         *  category or note, a template's connective), each named with what it is. Removed from a page's tags
         *  before it is labelled; every entry must occur in the oracle or the test fails it as stale, and a
         *  tag outside this map and the paradigm's vocabulary fails the test. */
        val PAGE_USAGE_TAGS: Map<String, String> = mapOf(
            // regions
            "Asturias" to "a region", "Bolivia" to "a region", "Canary-Islands" to "a region", "Caribbean" to "a region",
            "Catalonia" to "a region", "Chile" to "a region", "Colombia" to "a region", "Costa-Rica" to "a region",
            "Cuba" to "a region", "Ecuador" to "a region", "Honduras" to "a region", "Mexico" to "a region",
            "Northern" to "a region", "Paraguay" to "a region", "Peru" to "a region", "Philippines" to "a region",
            "Rioplatense" to "a region", "US" to "a region", "Valencia" to "a region", "Venezuela" to "a region",
            "El-Salvador" to "a region", "Latin-America" to "a region", "Spain" to "a region",
            "Lunfardo" to "a regional argot", "Internet" to "a medium",
            // registers and usage
            "proscribed" to "a proscribed spelling", "nonstandard" to "a nonstandard form", "colloquial" to "a register",
            "slang" to "a register", "vulgar" to "a register", "dated" to "a usage note", "rare" to "a usage note",
            "uncommon" to "a usage note",
            "dialectal" to "a usage note", "historical" to "a usage note", "literary" to "a register",
            "neologism" to "a usage note", "euphemistic" to "a register", "derogatory" to "a register",
            "humorous" to "a register", "endearing" to "a register", "pejorative" to "a register", "idiomatic" to "a usage note",
            // lexical categories and notes
            "transitive" to "a verb's valency", "uncountable" to "a noun's countability", "plural-only" to "a plurale tantum note",
            "by-personal-gender" to "a noun whose gender follows the referent", "gender-neutral" to "a gender note",
            "no-feminine" to "a gender note", "demonstrative" to "a determiner's category", "possessive" to "a determiner's category",
            "personal" to "a pronoun's category", "pronoun" to "a pronoun's category", "objective" to "a pronoun's category",
            "interrogative" to "a pronoun's category", "indefinite" to "a determiner's category", "impersonal" to "a verb's category",
            "invariable" to "an inflection note (a participle with one form)", "dependent" to "a lexical note",
            "in-compounds" to "a lexical note", "morpheme" to "a lexical note", "prepositional" to "a lexical note",
            "emphatic" to "a lexical note", "variant" to "a lexical note",
            // template connectives and errors
            "often" to "a template's connective", "sometimes" to "a template's connective", "usually" to "a template's connective",
            "including" to "a template's connective", "literally" to "a template's connective",
            "error-unknown-tag" to "wiktextract could not read a template; what it did read is compared",
        )

        /** Surfaces the pack does not index, each a named gap in the pack with its cause. */
        val UNRESOLVED_SURFACE_EXCUSES = mapOf(
            "cosas en sí" to "a multi-word plural: the alias pass indexes single-word forms only",
            "guardias de seguridad" to "a multi-word plural: the alias pass indexes single-word forms only",
            "elfa" to "a page with a sense of its own (female elf) below the frequency gate, so neither a lemma nor a rescued redirect",
            "resienta" to "resentirse's table lists the subjunctive as se resienta; the alias pass indexed resiento and resientes from their two-word rows but not resienta, a pack alias gap the line does not touch",
        )

        /** Surfaces the pack does not index, by its design; the page is excused by its label. */
        val UNRESOLVED_EXCUSES = listOf(
            UnresolvedExcuse("a diminutive or augmentative (abrazote, ajete): the alias pass does not index them (build_latin_dict.FORM_TAG_BLOCKLIST)") { label ->
                label.split(' ').any { it == "diminutivo" || it == "aumentativo" }
            },
        )

        /** Pages whose lemma the lookup does not return, by "surface|lemma", each with why. */
        val LEMMA_NOT_RETURNED_EXCUSES = mapOf(
            "amiga|friend" to "kaikki names the English gloss as the lemma",
            "men|man" to "an English page in the Spanish extract",
            "cascarón|cáscara" to "an augmentative the alias pass does not index; the lookup falls to the stem of cascar",
            "cucharón|cuchara" to "an augmentative the alias pass does not index; the lookup falls to the stem of cuchar",
            "lamparón|lámpara" to "an augmentative the alias pass does not index; the lookup falls to the stem of lampar",
            "gorrilla|gorra" to "a diminutive the alias pass does not index; the lookup falls to gorila",
            "pechito|pecho" to "a diminutive the alias pass does not index; the lookup falls to the stem of pechar",
        )

        val LEADING_CLITICS = mapOf(
            "me" to setOf("yo"),
            "te" to setOf("tú", "vos"),
            "se" to setOf("él, ella, usted", "usted", "ellos, ellas, ustedes", "ustedes"),
            "nos" to setOf("nosotros"),
            "os" to setOf("vosotros"),
        )

        val PARTICIPLE_ENDINGS = mapOf(
            "participio" to listOf("o"),
            "participio femenino" to listOf("a"),
            "participio masculino plural" to listOf("os"),
            "participio femenino plural" to listOf("as"),
        )
        return OracleLanguage(
            tableOmitsTags = OMITTED,
            genderWords = GENDER_WORDS,
            usageTags = PAGE_USAGE_TAGS,
            allowances = ALLOWANCES,
            knownDisagreements = KNOWN_DISAGREEMENTS,
            unresolvedExcuses = UNRESOLVED_EXCUSES,
            unresolvedSurfaceExcuses = UNRESOLVED_SURFACE_EXCUSES,
            lemmaNotReturnedExcuses = LEMMA_NOT_RETURNED_EXCUSES,
            leadingClitics = LEADING_CLITICS,
            participleEndings = PARTICIPLE_ENDINGS,
            variantLeadClitics = setOf("me", "te", "se", "nos", "os"),
            pronominalLemma = { it.endsWith("se") },
        )
        }
    }
}
