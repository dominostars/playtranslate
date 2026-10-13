package com.playtranslate.language.inflection

import com.playtranslate.language.InflectionForm
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * The exhaustive gate on a language's labeller: every tagset of its pack
 * through [InflectionParadigm.label], compared with a reviewed golden file.
 * One concrete subclass per language names the language and its paradigm
 * ([SpanishTagsetGoldenTest]); the files live under
 * `src/test/resources/inflection/<lang>/`.
 *
 * A pack's tagsets are few and enumerable (939 in es-v5), so the labeller is
 * not sampled: `tagsets.tsv`, written by `scripts/dump_inflection_tagsets.py`
 * from a pack's dict.sqlite, lists each with its line count and an example
 * surface (one line per attached pronoun for the clitic tagsets, since the
 * label reads the pronoun off the surface). The gate labels every line and
 * requires:
 *  - the result equals `labels.tsv`, the golden reviewed by a reader of the
 *    language (line count, example, label, tags; an empty label is no label);
 *  - every line without a label has its tags in `no_label_reasons.tsv` with
 *    the reason, and no reason there is stale.
 * A kaikki convention the labeller does not handle is then an unlabelled or
 * changed line with a count, caught here, not a plausible label read on a
 * device. On failure the actual labels are written to
 * `app/build/inflection-golden/<lang>/labels.tsv` and the differences are
 * listed; after review, that file replaces the golden. A new pack build
 * regenerates `tagsets.tsv` first, and the diff of the golden is then exactly
 * the new tagsets.
 */
abstract class TagsetGoldenGate(private val lang: String, private val paradigm: InflectionParadigm) {

    @Test
    fun `every tagset of the pack labels as reviewed`() {
        val fixture = resource("tagsets.tsv") ?: error("missing fixture inflection/$lang/tagsets.tsv")
        val golden = linesOf(resource("labels.tsv")).associateBy { keyOf(it) }
        val reasons = linesOf(resource("no_label_reasons.tsv")).filterNot { it.startsWith("#") }
            .associate { it.substringBefore('\t') to it.substringAfter('\t') }

        val actual = linesOf(fixture).map { line ->
            val (rows, example, tags) = line.split('\t')
            val label = paradigm.label(listOf(InflectionForm(example, tags.split(' ').toSet())))
            "$rows\t$example\t${label.orEmpty()}\t$tags"
        }

        val problems = mutableListOf<String>()
        val unlabelledTags = HashSet<String>()
        for (line in actual) {
            val f = line.split('\t')
            if (f[LABEL].isEmpty()) {
                unlabelledTags += f[TAGS]
                if (f[TAGS] !in reasons) problems += "no label and no reason: $line"
            }
            val expected = golden[keyOf(line)]
            when {
                expected == null -> problems += "not in the golden: $line"
                expected != line -> problems += "changed: ${f[EXAMPLE]} [${f[TAGS]}]: '${expected.split('\t')[LABEL]}' -> '${f[LABEL]}'"
            }
        }
        val actualKeys = actual.mapTo(HashSet()) { keyOf(it) }
        for (key in golden.keys) if (key !in actualKeys) problems += "in the golden, not in the fixture: ${golden.getValue(key)}"
        for (tags in reasons.keys) if (tags !in unlabelledTags) problems += "stale reason (the tagset is labelled or gone): $tags"

        if (problems.isNotEmpty()) {
            val out = File(System.getProperty("user.dir"), "build/inflection-golden/$lang/labels.tsv")
            out.parentFile.mkdirs()
            out.writeText(actual.joinToString("\n", postfix = "\n"))
            fail(
                "$lang labels differ from the golden (${problems.size} problems; actual written to $out):\n" +
                    problems.take(SHOWN).joinToString("\n") + if (problems.size > SHOWN) "\n… ${problems.size - SHOWN} more" else "",
            )
        }
    }

    /** A line's identity across the fixture and the golden: its example surface and tags. */
    private fun keyOf(line: String): String = line.split('\t').let { "${it[EXAMPLE]}\t${it[TAGS]}" }

    private fun linesOf(text: String?): List<String> = text?.lines()?.filter { it.isNotEmpty() } ?: emptyList()

    private fun resource(name: String): String? =
        javaClass.getResourceAsStream("/inflection/$lang/$name")?.use { it.readBytes().toString(Charsets.UTF_8) }

    private companion object {
        const val EXAMPLE = 1
        const val LABEL = 2
        const val TAGS = 3
        const val SHOWN = 60
    }
}
