package com.playtranslate.translationlog

import android.graphics.Rect
import com.playtranslate.OcrManager
import com.playtranslate.OverlayToolkit

/**
 * Write-gate policy for the translation log ("History"): decides, per
 * committed live-mode block, whether it becomes a log entry. Deliberate
 * altitude: this is the WHOLE policy in one pure, session-scoped object —
 * no store, no UI, no pipeline wiring — so it can be validated offline
 * against recorded traces ([LogTraceRecorder] → LogTraceReplayTest) before
 * any product surface is built on it.
 *
 * Five gates, in order:
 *  1. Sentence-ness ([isSentenceLike]) — drops HUD churn (clocks, dates,
 *     counters, bare names). Deliberate acceptance: short interjection-only
 *     lines can be lost; thresholds are PROVISIONAL until swept on real
 *     traces in BOTH script classes (char-metric ja/zh/ko/th vs word-metric
 *     Latin & friends) — a JA-tuned floor proves nothing for FR→ES.
 *  2. Region mute — a region that has accumulated [REGION_MUTE_AFTER]
 *     near-duplicate suppressions is muted for the session. The trigger is
 *     near-dup DENSITY, never append count: a dialogue box appends dozens
 *     of pairwise-distant lines and never mutes; a fixed UI bar (game nav
 *     bar, button prompts) re-garbles the SAME content into pairwise-close
 *     variants and mutes after a few. (First real-trace finding: the
 *     dominant surviving noise was one nav bar OCR'd ten ways.)
 *  3. Content dedupe — the same normalized text seen again this session
 *     (re-entered scene, recurring line) bumps last-seen instead of
 *     appending. Key = source-language characters only, the same
 *     normalization as the pipeline's dedupKey.
 *  4. Near-duplicate collapse — a re-read of a recent entry is folded by a
 *     length-proportional bag-of-chars distance (two tiers: tight
 *     anywhere, for jitter on moving world-anchored text; loose when the
 *     region agrees, for wholesale re-garbling of a fixed UI element).
 *     Matches are then CLASSIFIED: containment-shaped (typewriter growth /
 *     partial re-reveal — growth keeps the fullest read via Replace,
 *     partials suppress, neither ever churns) vs substitution-shaped
 *     (garble — suppresses AND accrues region churn toward the mute). The
 *     slack floor keeps short distinct lines (はい。 vs いいえ。) apart —
 *     that safety property is why this does not reuse
 *     [OverlayToolkit.isSignificantChange], whose fixed 3-char tolerance
 *     is too loose at dialogue-reply lengths.
 *  5. Prefix supersession — a still-growing block whose growth exceeds the
 *     near-dup budget REPLACES its previous entry instead of appending a
 *     partial trail: same region + [OverlayToolkit.isEvolvingText] over
 *     NORMALIZED KEYS (decoration like 「…」 must not break the prefix).
 *     Only the most recent entry is a candidate — dialogue advancing in
 *     the same box is a new entry, not a replacement.
 *
 * Deliberately gates AUTO-mode commits only. Deliberate one-shot/lookup
 * entries are never gated at write; the read-time context selector
 * re-applies sentence-ness instead (see the History design).
 */
class LogWriteGate(private val sourceLang: String) {

    data class Entry(
        val text: String,
        val bounds: Rect,
        val atMs: Long,
        val cycle: Int,
        val key: String,
    )

    sealed interface Decision {
        data class Append(val entry: Entry) : Decision

        /** [previous] is superseded by [entry] — the log rewrites in place. */
        data class Replace(val previous: Entry, val entry: Entry) : Decision
        data class Suppress(val reason: SuppressReason) : Decision
    }

    enum class SuppressReason { NOT_SENTENCE, DUPLICATE, NEAR_DUPLICATE, REGION_MUTED }

    /** Normalized-key → last-seen ms. Access-ordered with a hard cap so an
     *  all-night session can't grow it unbounded. */
    private val seen = object : LinkedHashMap<String, Long>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?) =
            size > SEEN_CAP
    }

    /** Supersession candidate: the most recently appended/replaced entry. */
    private var lastEntry: Entry? = null

    /** Recent entries (bounded) — the near-dup comparison window. */
    private val recent = ArrayDeque<Entry>()

    /** One tracked screen region accruing near-dup evidence toward a mute. */
    private class ChurnRegion(val bounds: Rect) {
        var nearDups = 0
        val muted get() = nearDups >= REGION_MUTE_AFTER
    }

    private val churnRegions = ArrayDeque<ChurnRegion>()

    /** Deliberate-action entries (one-shot, drag-lookup): the user asked for
     *  this translation, so noise gates don't apply — but the SAME exact-
     *  dedupe as auto entries does, sharing [seen] so a one-shot of a line
     *  live mode already logged still collapses. No supersession and no
     *  effect on [lastEntry]/[recent]: a deliberate capture is a complete
     *  result, never a typewriter partial, and must not become a Replace
     *  target for unrelated auto commits. */
    fun offerDeliberate(text: String, atMs: Long, cycle: Int): Decision {
        if (text.isBlank()) return Decision.Suppress(SuppressReason.NOT_SENTENCE)
        val key = normalizedKey(text, sourceLang)
        if (key.isEmpty()) return Decision.Suppress(SuppressReason.NOT_SENTENCE)
        if (seen.containsKey(key)) {
            seen[key] = atMs
            return Decision.Suppress(SuppressReason.DUPLICATE)
        }
        seen[key] = atMs
        return Decision.Append(Entry(text, Rect(), atMs, cycle, key))
    }

    fun offer(text: String, bounds: Rect, atMs: Long, cycle: Int): Decision {
        if (!isSentenceLike(text, sourceLang)) {
            return Decision.Suppress(SuppressReason.NOT_SENTENCE)
        }
        if (churnRegionFor(bounds)?.muted == true) {
            return Decision.Suppress(SuppressReason.REGION_MUTED)
        }
        val key = normalizedKey(text, sourceLang)

        if (seen.containsKey(key)) {
            // Same content chars, fuller rendering (final 。 landing after
            // the content did): keep the fullest form — sentence cards
            // want their terminal punctuation.
            val sameKeyPrev = lastEntry
            if (sameKeyPrev != null && sameKeyPrev.key == key &&
                sameRegion(sameKeyPrev.bounds, bounds) &&
                text.length > sameKeyPrev.text.length
            ) {
                val entry = Entry(text, Rect(bounds), atMs, cycle, key)
                seen[key] = atMs
                lastEntry = entry
                recent.remove(sameKeyPrev)
                remember(entry)
                return Decision.Replace(sameKeyPrev, entry)
            }
            seen[key] = atMs
            return Decision.Suppress(SuppressReason.DUPLICATE)
        }

        // Near-dup MUST precede supersession: an insertion-style jitter
        // variant is a superset-with-similar-prefix, which isEvolvingText
        // reads as typewriter growth — routed there it would Replace and
        // bypass churn accounting, so an insertion-garbling region would
        // never mute. Real typewriter growth adds far more than the
        // near-dup budget and still falls through to Replace below; the
        // residual cost is a same-region micro-extension (+1-2 chars)
        // logging its shorter form.
        val jitterOf = recent.lastOrNull { r ->
            isNearDuplicate(r.key, key) ||
                (sameRegion(r.bounds, bounds) &&
                    isNearDuplicate(r.key, key, NEAR_DUP_SAME_REGION_FRACTION)) ||
                // Containment tier, distance-free: a deep partial can sit
                // arbitrarily far from its full line by bag distance, but a
                // true subset at ≥ half the length IS the same content.
                // The ratio guard stops short real lines (はい) from being
                // swallowed by any long line containing their chars.
                containsWithRatio(shorter = key, longer = r.key) ||
                containsWithRatio(shorter = r.key, longer = key)
        }
        if (jitterOf != null) {
            // Both spellings are now known — the next re-read of either
            // garbling dies in the cheap exact-dedupe gate above.
            seen[key] = atMs
            // Classify the family: CONTAINMENT (one read's chars ⊂ the
            // other's — typewriter growth or partial re-reveal) vs
            // SUBSTITUTION (chars swapped — OCR garble). Second real trace:
            // typewriter re-reveals are legitimate near-dups of the
            // dialogue box, and counting them muted the box mid-session —
            // only substitution-class events are garble evidence.
            val growth = containsWithRatio(shorter = jitterOf.key, longer = key)
            val partialReRead = containsWithRatio(shorter = key, longer = jitterOf.key)
            if (growth && key.length > jitterOf.key.length) {
                // The fuller read of the same content wins the entry.
                val entry = Entry(text, Rect(bounds), atMs, cycle, key)
                seen.remove(jitterOf.key)
                seen[key] = atMs
                lastEntry = entry
                recent.remove(jitterOf)
                remember(entry)
                return Decision.Replace(jitterOf, entry)
            }
            if (!growth && !partialReRead && sameRegion(jitterOf.bounds, bounds)) {
                // Substitution-class at a screen-anchored element: garble
                // churn. (A moving sign's jitter says nothing about its
                // region, hence the sameRegion gate.)
                val region = churnRegionFor(bounds) ?: ChurnRegion(Rect(bounds)).also {
                    churnRegions.addLast(it)
                    if (churnRegions.size > REGION_TRACKER_CAP) churnRegions.removeFirst()
                }
                region.nearDups++
            }
            return Decision.Suppress(SuppressReason.NEAR_DUPLICATE)
        }

        // Supersession compares NORMALIZED KEYS, not raw text: quote/dash
        // decoration arriving with the grown read (「…」, trailing ―) sits
        // outside the source-char alphabet and must not break the prefix
        // relationship (second real trace: a 「-prefixed grown line leaked
        // as a separate entry beside its partial).
        val prev = lastEntry
        if (prev != null && sameRegion(prev.bounds, bounds) &&
            OverlayToolkit.isEvolvingText(prev.key, key)
        ) {
            val entry = Entry(text, Rect(bounds), atMs, cycle, key)
            seen.remove(prev.key)
            seen[key] = atMs
            lastEntry = entry
            recent.remove(prev)
            remember(entry)
            return Decision.Replace(prev, entry)
        }

        val entry = Entry(text, Rect(bounds), atMs, cycle, key)
        seen[key] = atMs
        lastEntry = entry
        remember(entry)
        return Decision.Append(entry)
    }

    private fun remember(entry: Entry) {
        recent.addLast(entry)
        if (recent.size > RECENT_WINDOW) recent.removeFirst()
    }

    private fun churnRegionFor(bounds: Rect): ChurnRegion? =
        churnRegions.lastOrNull { sameRegion(it.bounds, bounds) }

    companion object {
        private const val SEEN_CAP = 512

        /** Near-dup comparison window (entries). Sized by the first real
         *  trace: a fixed UI bar recurs every ~25 s of menu-heavy play with
         *  up to ~15 other commits between sightings — an 8-entry window
         *  evicted it before its next garbling arrived. */
        private const val RECENT_WINDOW = 24

        /** SUBSTITUTION-class near-dups a region accrues before it is
         *  muted for the session. Containment-class events (typewriter
         *  growth / partial re-reveals) never count — the second real
         *  trace muted the dialogue box on its own typewriter re-reveals
         *  under a count-all rule. */
        private const val REGION_MUTE_AFTER = 3

        private const val REGION_TRACKER_CAP = 32

        /** Two-tier near-dup budgets, both length-proportional with a slack
         *  floor (so short distinct lines — はい。 vs いいえ。, 3 of 4 chars
         *  apart — can never collapse):
         *   - ANY region, tight (20%): clean-jitter re-reads of MOVING text.
         *     First real trace: the same shop sign read 交番/交街/交器 at
         *     l=103→707→1189 as the camera tracked the player — a
         *     same-region requirement can never catch world-anchored text.
         *   - SAME region, loose (40%): a screen-anchored UI element
         *     re-garbling wholesale. Same trace: the nav bar's variants
         *     differed by ~35% of their chars at a fixed rect — beyond
         *     jitter, but unmistakably one element when the region agrees.
         *  PROVISIONAL fractions — swept against trace dumps. */
        private const val NEAR_DUP_MAX_FRACTION = 0.2
        private const val NEAR_DUP_SAME_REGION_FRACTION = 0.4
        private const val NEAR_DUP_MIN_SLACK = 2

        fun isNearDuplicate(keyA: String, keyB: String, fraction: Double = NEAR_DUP_MAX_FRACTION): Boolean {
            if (keyA.isEmpty() || keyB.isEmpty()) return false
            val longest = maxOf(keyA.length, keyB.length)
            val budget = maxOf(NEAR_DUP_MIN_SLACK, (longest * fraction).toInt())
            // Cheap reject: length delta alone exceeding the budget can't
            // come back below it in the bag distance.
            if (kotlin.math.abs(keyA.length - keyB.length) > budget) return false
            return bagDistance(keyA, keyB) <= budget
        }

        /** Containment tier: [shorter] is a true bag-subset of [longer] AND
         *  at least [CONTAINMENT_MIN_RATIO] of its length. Growth-replace
         *  uses the same ratio: without it, any long line whose bag happens
         *  to cover a short entry's chars would "grow" over it. Deeper
         *  growth (partial < half of full) still collapses — it falls
         *  through to key-prefix supersession, where the partial is the
         *  most recent entry. */
        private const val CONTAINMENT_MIN_RATIO = 0.5

        private fun containsWithRatio(shorter: String, longer: String): Boolean =
            shorter.length >= (longer.length * CONTAINMENT_MIN_RATIO).toInt() &&
                bagContains(shorter, longer)

        /** True when every char of [shorter] is present in [longer] — the
         *  containment shape of typewriter growth and partial re-reveals,
         *  as opposed to substitution-shaped garble. EXACT subset on
         *  purpose: real partials are true subsets of their grown form
         *  (both real traces), while any slack here reclassifies small
         *  substitutions as containment and defeats the region mute. */
        private fun bagContains(shorter: String, longer: String): Boolean {
            if (shorter.length > longer.length) return false
            val counts = HashMap<Char, Int>(longer.length)
            for (c in longer) counts.merge(c, 1, Int::plus)
            for (c in shorter) {
                if (counts.merge(c, -1, Int::plus)!! < 0) return false
            }
            return true
        }

        /** Symmetric difference of char frequencies — order-insensitive on
         *  purpose (OCR jitter reorders as well as substitutes). */
        private fun bagDistance(a: String, b: String): Int {
            val counts = HashMap<Char, Int>(a.length + b.length)
            for (c in a) counts.merge(c, 1, Int::plus)
            for (c in b) counts.merge(c, -1, Int::plus)
            var diff = 0
            for (v in counts.values) diff += kotlin.math.abs(v)
            return diff
        }

        /** Same-region = the rects' intersection covers at least half of the
         *  smaller rect. Looser than equality on purpose: OCR bounds jitter
         *  and a growing typewriter block's rect grows with it. */
        private const val REGION_OVERLAP_MIN = 0.5f

        /** Source-language chars are the unit of content; digits are never
         *  content regardless of what the script predicate admits (defends
         *  the clock/date/counter case on every script). */
        private fun isContentChar(c: Char, lang: String): Boolean =
            !c.isDigit() && OcrManager.isSourceLangChar(c, lang)

        /** Scripts where characters (not whitespace-split words) are the
         *  sentence-length unit. PROVISIONAL membership — swept per script
         *  class on real traces. */
        private val CHAR_METRIC_LANGS = setOf("ja", "zh", "ko", "th")

        /** Sentence-final punctuation across supported scripts; closing
         *  quotes/brackets are stripped before the check. */
        private const val TERMINALS = "。．｡!！?？.…‥"
        private const val CLOSERS = "」』〟”\"'）)】〕≫»›]"

        // PROVISIONAL floors — pending trace sweeps (both script classes).
        private const val CHAR_MIN = 5
        private const val CHAR_MIN_WITH_TERMINAL = 2
        private const val WORD_MIN = 3
        private const val WORD_MIN_WITH_TERMINAL = 2

        /** Fold OCR-jitter-equivalent forms before any comparison: NFKC
         *  (half/full-width flips — ﾊﾛｰ/ハロー, ＡＢＣ/ＡBC — otherwise read
         *  as changes frame-to-frame) plus small→big kana (ょ→よ; engines
         *  flip kana size on re-reads). Both LunaTranslator and
         *  GameSentenceMiner fold BEFORE comparing for exactly this reason.
         *  Comparison-only: entry text stays raw for display. */
        fun fold(text: String): String {
            val nfkc = java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFKC)
            val sb = StringBuilder(nfkc.length)
            for (c in nfkc) sb.append(SMALL_KANA_FOLD[c] ?: c)
            return sb.toString()
        }

        private val SMALL_KANA_FOLD = mapOf(
            'ぁ' to 'あ', 'ぃ' to 'い', 'ぅ' to 'う', 'ぇ' to 'え', 'ぉ' to 'お',
            'っ' to 'つ', 'ゃ' to 'や', 'ゅ' to 'ゆ', 'ょ' to 'よ', 'ゎ' to 'わ',
            'ァ' to 'ア', 'ィ' to 'イ', 'ゥ' to 'ウ', 'ェ' to 'エ', 'ォ' to 'オ',
            'ッ' to 'ツ', 'ャ' to 'ヤ', 'ュ' to 'ユ', 'ョ' to 'ヨ', 'ヮ' to 'ワ',
            'ヵ' to 'カ', 'ヶ' to 'ケ',
        )

        fun isSentenceLike(text: String, lang: String): Boolean {
            val trimmed = fold(text).trim().trimEnd { it in CLOSERS }
            if (trimmed.isEmpty()) return false
            val hasTerminal = trimmed.last() in TERMINALS
            return if (lang in CHAR_METRIC_LANGS) {
                val content = trimmed.count { isContentChar(it, lang) }
                content >= if (hasTerminal) CHAR_MIN_WITH_TERMINAL else CHAR_MIN
            } else {
                val words = trimmed.split(WHITESPACE)
                    .count { tok -> tok.any { isContentChar(it, lang) } }
                words >= if (hasTerminal) WORD_MIN_WITH_TERMINAL else WORD_MIN
            }
        }

        private val WHITESPACE = Regex("\\s+")

        /** The pipeline's dedupKey normalization — source-language characters
         *  only (see OverlayToolkit.runOcrPipeline) — over [fold]ed text, so
         *  width/kana-size jitter can't mint distinct keys. */
        fun normalizedKey(text: String, lang: String): String =
            fold(text).filter { OcrManager.isSourceLangChar(it, lang) }

        private fun sameRegion(a: Rect, b: Rect): Boolean {
            val ix = maxOf(0, minOf(a.right, b.right) - maxOf(a.left, b.left))
            val iy = maxOf(0, minOf(a.bottom, b.bottom) - maxOf(a.top, b.top))
            val inter = ix.toLong() * iy
            val minArea = minOf(
                a.width().toLong() * a.height(),
                b.width().toLong() * b.height(),
            )
            if (minArea <= 0) return false
            return inter >= minArea * REGION_OVERLAP_MIN
        }
    }
}
