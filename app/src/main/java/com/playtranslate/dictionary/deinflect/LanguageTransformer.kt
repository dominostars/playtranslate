/*
 * Port of ext/js/language/language-transformer.js and the suffixInflection /
 * wholeWordInflection factories of ext/js/language/language-transforms.js
 * from Yomitan (https://github.com/yomidevs/yomitan) at
 * 833409247c5e30a976551a6475843b3a4896ef2f.
 * Copyright (C) 2024-2026  Yomitan Authors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.playtranslate.dictionary.deinflect

/** A language's deinflection table: Yomitan's `LanguageTransformDescriptor` without its i18n and descriptions. */
internal data class TransformDescriptor(
    val conditions: List<Condition>,
    val transforms: List<Transform>,
)

/**
 * A word class or an intermediate form a rule can require or produce. A
 * condition with null [subConditions] is a leaf and gets its own flag bit; a
 * composite one gets the OR of its sub-conditions' flags.
 * [isDictionaryForm] marks the conditions a dictionary entry's part of speech
 * can name ([LanguageTransformer.partOfSpeechFlags]).
 */
internal data class Condition(
    val key: String,
    val isDictionaryForm: Boolean,
    val subConditions: List<String>?,
)

/** One named inflection step; [key] is what a [TraceFrame] records. */
internal data class Transform(
    val key: String,
    val name: String,
    val rules: List<Rule>,
)

/**
 * One rewrite from an inflected text back toward its dictionary form.
 * [conditionsIn] gates which texts it applies to, [conditionsOut] is the
 * condition set the rewritten text carries.
 */
internal sealed class Rule {
    abstract val conditionsIn: List<String>
    abstract val conditionsOut: List<String>

    /** The text this rule's match requires at the end of the input (the whole input for [WholeWord]). */
    abstract val inflected: String

    abstract fun isInflected(text: String): Boolean

    abstract fun deinflect(text: String): String

    /**
     * Yomitan's `suffixInflection`: matches a text ending in [suffixIn] and
     * replaces that ending with [suffixOut]. Upstream compiles [suffixIn]
     * into the RegExp `suffixIn$`; the table's suffixes hold no RegExp
     * metacharacters (the generator checks) and a JavaScript `$` without the
     * multiline flag matches only at the end of the input, so the test is
     * [String.endsWith].
     */
    data class Suffix(
        val suffixIn: String,
        val suffixOut: String,
        override val conditionsIn: List<String>,
        override val conditionsOut: List<String>,
    ) : Rule() {
        override val inflected: String get() = suffixIn

        override fun isInflected(text: String): Boolean = text.endsWith(suffixIn)

        override fun deinflect(text: String): String =
            text.substring(0, text.length - suffixIn.length) + suffixOut
    }

    /** Yomitan's `wholeWordInflection`: matches exactly [wordIn] (the RegExp `^wordIn$`) and yields [wordOut]. */
    data class WholeWord(
        val wordIn: String,
        val wordOut: String,
        override val conditionsIn: List<String>,
        override val conditionsOut: List<String>,
    ) : Rule() {
        override val inflected: String get() = wordIn

        override fun isInflected(text: String): Boolean = text == wordIn

        override fun deinflect(text: String): String = wordOut
    }
}

/**
 * One deinflection result: [text] carrying [conditions] (a flag mask; 0 for
 * the source text and for a rule with no conditionsOut), reached through
 * [trace].
 */
internal data class TransformedText(
    val text: String,
    val conditions: Int,
    val trace: List<TraceFrame>,
)

/** One applied rule: the transform's key, the rule's index in it, and the text the rule rewrote. */
internal data class TraceFrame(
    val transformKey: String,
    val ruleIndex: Int,
    val text: String,
)

/**
 * Port of Yomitan's `LanguageTransformer` for one descriptor: condition flag
 * bits assigned as `addDescriptor` assigns them, and [transform]'s
 * breadth-first deinflection.
 */
internal class LanguageTransformer(descriptor: TransformDescriptor) {

    private class IndexedRule(
        val transformKey: String,
        val ruleIndex: Int,
        val rule: Rule,
        val conditionsIn: Int,
        val conditionsOut: Int,
    )

    private val conditionFlagsByKey: Map<String, Int>
    private val partOfSpeechFlagsByKey: Map<String, Int>

    /**
     * Every rule, in upstream visiting order (transform order, then rule
     * order), grouped by the last character of [Rule.inflected]. A rule can
     * match only a text ending in that character, so visiting a text's group
     * visits exactly the rules upstream's per-transform heuristic and
     * `isInflected` test could accept, in the same order; the group lookup
     * stands in for the heuristic.
     */
    private val rulesByLastChar: Map<Char, List<IndexedRule>>

    init {
        val flagsByKey = conditionFlagsMap(descriptor.conditions)
        val byLastChar = HashMap<Char, MutableList<IndexedRule>>()
        for (transform in descriptor.transforms) {
            transform.rules.forEachIndexed { j, rule ->
                val flagsIn = strictFlags(flagsByKey, rule.conditionsIn)
                    ?: throw IllegalArgumentException("Invalid conditionsIn for transform ${transform.key}.rules[$j]")
                val flagsOut = strictFlags(flagsByKey, rule.conditionsOut)
                    ?: throw IllegalArgumentException("Invalid conditionsOut for transform ${transform.key}.rules[$j]")
                require(rule.inflected.isNotEmpty()) { "Empty inflected text for transform ${transform.key}.rules[$j]" }
                byLastChar.getOrPut(rule.inflected.last()) { ArrayList() }
                    .add(IndexedRule(transform.key, j, rule, flagsIn, flagsOut))
            }
        }
        rulesByLastChar = byLastChar
        conditionFlagsByKey = descriptor.conditions.associate { it.key to flagsByKey.getValue(it.key) }
        partOfSpeechFlagsByKey = descriptor.conditions
            .filter { it.isDictionaryForm }
            .associate { it.key to flagsByKey.getValue(it.key) }
    }

    /**
     * Every deinflection of [text], as upstream's `transform` produces them.
     * The result list doubles as the queue: result 0 is [text] itself with
     * conditions 0 and an empty trace, and each result in turn has every
     * matching rule applied, appending one result per application, until no
     * unvisited result is left. A rule applies when its pattern matches and
     * [conditionsMatch] accepts the result's conditions against the rule's
     * conditionsIn: a result with conditions 0 passes every rule's condition
     * test, and a rule with no conditionsIn (flags 0) applies only to a
     * result with conditions 0. A rule is skipped when the result's trace already holds
     * a frame with the same transform key, rule index and text (the cycle
     * guard; upstream also logs a warning there). Each new result's trace is
     * the applied frame followed by the parent's trace, so trace[0] is the
     * step closest to the dictionary form. Results are not de-duplicated.
     */
    fun transform(text: String): List<TransformedText> {
        val results = ArrayList<TransformedText>()
        results.add(TransformedText(text, 0, emptyList()))
        var i = 0
        while (i < results.size) {
            val current = results[i++]
            val currentText = current.text
            // No rule's pattern matches an empty text.
            if (currentText.isEmpty()) continue
            val rules = rulesByLastChar[currentText.last()] ?: continue
            for (indexed in rules) {
                if (!conditionsMatch(current.conditions, indexed.conditionsIn)) continue
                if (!indexed.rule.isInflected(currentText)) continue
                val isCycle = current.trace.any {
                    it.transformKey == indexed.transformKey && it.ruleIndex == indexed.ruleIndex && it.text == currentText
                }
                if (isCycle) continue
                results.add(
                    TransformedText(
                        indexed.rule.deinflect(currentText),
                        indexed.conditionsOut,
                        extendTrace(current.trace, TraceFrame(indexed.transformKey, indexed.ruleIndex, currentText)),
                    ),
                )
            }
        }
        return results
    }

    /** Upstream `getConditionFlagsFromConditionTypes`: the OR of the named conditions' flags; unknown keys add nothing. */
    fun conditionFlags(conditionKeys: Iterable<String>): Int =
        conditionKeys.fold(0) { flags, key -> flags or (conditionFlagsByKey[key] ?: 0) }

    /**
     * Upstream `getConditionFlagsFromPartsOfSpeech`: like [conditionFlags],
     * but only conditions marked isDictionaryForm count.
     */
    fun partOfSpeechFlags(keys: Iterable<String>): Int =
        keys.fold(0) { flags, key -> flags or (partOfSpeechFlagsByKey[key] ?: 0) }

    companion object {
        /** Upstream `conditionsMatch`: true when [current] is 0, else when the two masks share a flag. */
        fun conditionsMatch(current: Int, next: Int): Boolean = current == 0 || (current and next) != 0

        /**
         * Upstream `_getConditionFlagsMap`: one bit per leaf condition in
         * declaration order; a composite condition resolves once all its
         * sub-conditions have, over repeated passes.
         */
        private fun conditionFlagsMap(conditions: List<Condition>): Map<String, Int> {
            val flagsByKey = HashMap<String, Int>()
            var nextFlagIndex = 0
            var targets = conditions
            while (targets.isNotEmpty()) {
                val nextTargets = ArrayList<Condition>()
                for (target in targets) {
                    val subConditions = target.subConditions
                    if (subConditions == null) {
                        // Upstream's limit: JavaScript bit operations are 32-bit.
                        if (nextFlagIndex >= 32) throw IllegalArgumentException("Maximum number of conditions was exceeded")
                        flagsByKey[target.key] = 1 shl nextFlagIndex
                        ++nextFlagIndex
                    } else {
                        val flags = strictFlags(flagsByKey, subConditions)
                        if (flags == null) {
                            nextTargets.add(target)
                        } else {
                            flagsByKey[target.key] = flags
                        }
                    }
                }
                if (nextTargets.size == targets.size) {
                    // Upstream reports a sub-condition cycle with the same message.
                    throw IllegalArgumentException("Maximum number of conditions was exceeded")
                }
                targets = nextTargets
            }
            return flagsByKey
        }

        /** Upstream `_getConditionFlagsStrict`: the OR of the named flags, or null when any key is unknown. */
        private fun strictFlags(flagsByKey: Map<String, Int>, keys: List<String>): Int? {
            var flags = 0
            for (key in keys) {
                flags = flags or (flagsByKey[key] ?: return null)
            }
            return flags
        }

        private fun extendTrace(trace: List<TraceFrame>, frame: TraceFrame): List<TraceFrame> {
            val extended = ArrayList<TraceFrame>(trace.size + 1)
            extended.add(frame)
            extended.addAll(trace)
            return extended
        }
    }
}
