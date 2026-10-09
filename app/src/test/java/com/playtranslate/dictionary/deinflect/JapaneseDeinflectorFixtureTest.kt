package com.playtranslate.dictionary.deinflect

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

/**
 * Yomitan's Japanese deinflection suite (test/language/japanese-transforms.test.js,
 * converted by scripts/port_yomitan_transform_tests.mjs into
 * `yomitan/japanese-transforms-tests.json`), loaded from the test classpath.
 * Upstream runs it with no input preprocessing, and so does the port.
 */
internal object YomitanJapaneseFixture {

    data class Case(
        val categoryIndex: Int,
        val category: String,
        val valid: Boolean,
        val source: String,
        val term: String,
        val rule: String?,
        val reasons: List<String>?,
    )

    class Fixture(val upstreamCommit: String, val categoryCount: Int, val cases: List<Case>)

    val fixture: Fixture by lazy { load() }

    private fun load(): Fixture {
        val url = checkNotNull(javaClass.classLoader?.getResource("yomitan/japanese-transforms-tests.json")) {
            "yomitan/japanese-transforms-tests.json is not on the test classpath"
        }
        val root = Json.parseToJsonElement(url.readText(Charsets.UTF_8)).jsonObject
        val categories = root.getValue("categories").jsonArray
        val cases = categories.flatMapIndexed { index, element ->
            val category = element.jsonObject
            val name = category.getValue("category").jsonPrimitive.content
            val valid = category.getValue("valid").jsonPrimitive.boolean
            category.getValue("tests").jsonArray.map { testElement ->
                val test = testElement.jsonObject
                val reasons = test.getValue("reasons")
                Case(
                    categoryIndex = index,
                    category = name,
                    valid = valid,
                    source = test.getValue("source").jsonPrimitive.content,
                    term = test.getValue("term").jsonPrimitive.content,
                    rule = test.getValue("rule").jsonPrimitive.contentOrNull,
                    reasons = if (reasons is JsonNull) null else reasons.jsonArray.map { it.jsonPrimitive.content },
                )
            }
        }
        val commit = root.getValue("upstream").jsonObject.getValue("commit").jsonPrimitive.content
        return Fixture(commit, categories.size, cases)
    }
}

/**
 * Every case of Yomitan's suite against the port, judged as upstream's
 * test/fixtures/language-transformer-test.js `hasTermReasons` judges it: a
 * case HAS its term when some transform result's text equals the term, its
 * conditions match the flags of the case's rule (when the rule is not null),
 * and its trace's transform keys equal the case's reasons (when the reasons
 * are not null). A case in a valid category must have its term and a case in
 * an invalid one must not.
 */
class JapaneseDeinflectorFixtureTest {

    private val transformer = LanguageTransformer(JapaneseTransforms.descriptor)

    private fun hasTermReasons(source: String, term: String, rule: String?, reasons: List<String>?): Boolean {
        for (result in transformer.transform(source)) {
            if (result.text != term) continue
            if (rule != null) {
                val expectedConditions = transformer.conditionFlags(listOf(rule))
                if (!LanguageTransformer.conditionsMatch(result.conditions, expectedConditions)) continue
            }
            if (reasons != null && result.trace.map { it.transformKey } != reasons) continue
            return true
        }
        return false
    }

    @Test
    fun fixtureIsTheWholeSuiteAtTheTablesCommit() {
        val fixture = YomitanJapaneseFixture.fixture
        assertEquals(JapaneseTransforms.UPSTREAM_COMMIT, fixture.upstreamCommit)
        assertEquals(33, fixture.categoryCount)
        assertEquals(1411, fixture.cases.size)
    }

    @Test
    fun everyCaseHasItsUpstreamVerdict() {
        val cases = YomitanJapaneseFixture.fixture.cases
        val failures = cases.filter { hasTermReasons(it.source, it.term, it.rule, it.reasons) != it.valid }
        if (failures.isNotEmpty()) {
            fail(
                "${failures.size} of ${cases.size} cases have the wrong verdict:\n" +
                    failures.joinToString("\n") {
                        "category ${it.categoryIndex} \"${it.category}\" (valid=${it.valid}): " +
                            "source=${it.source} term=${it.term} rule=${it.rule} reasons=${it.reasons}"
                    },
            )
        }
    }
}
