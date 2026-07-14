package com.playtranslate.language

import java.util.PriorityQueue
import java.util.regex.Matcher
import java.util.regex.Pattern

/**
 * Dictionary maximal-matching Thai word segmenter — faithful Kotlin port of
 * PyThaiNLP `pythainlp/tokenize/newmm.py`:
 *   SPDX-FileCopyrightText: 2016-2026 PyThaiNLP Project
 *   SPDX-License-Identifier: Apache-2.0
 * Based on notebooks by Korakot Chaovavanich.
 *
 * It builds a DAG of dictionary-word edges constrained to [ThaiCharacterCluster]
 * boundaries and emits the fewest-words (maximal-matching) segmentation, with
 * BFS path selection, a graph-size cutoff, and non-dictionary-run handling — all
 * preserved from upstream. newmm uses **no** frequency weighting, so the
 * [dict] is a plain word set (a trie); this keeps the "agreement vs newmm"
 * validation valid.
 *
 * Output tokens are verbatim substrings of the input (the [ThaiSegmenter]
 * contract): the segmenter only slices `text`, never rewrites it.
 */
class MaximalMatchThaiSegmenter(private val dict: ThaiWordTrie) : ThaiSegmenter {

    override fun segment(text: String): List<String> {
        if (text.isEmpty()) return emptyList()
        val out = ArrayList<String>()
        onecut(text, out)
        return out
    }

    /** Port of `newmm._onecut` (generator → append to [out]). */
    private fun onecut(text: String, out: MutableList<String>) {
        val lenText = text.length
        val validPos = ThaiCharacterCluster.tccBoundaryArray(text)
        // begin position -> reachable end positions (dictionary-word edges).
        val graph = HashMap<Int, MutableList<Int>>()
        var graphSize = 0
        val posList = PriorityQueue<Int>() // min-heap of candidate break positions
        posList.add(0)
        var endPos = 0
        val nonThai = PAT_NONTHAI.matcher(text)

        // `posList` is a java.util.PriorityQueue, so peek()/poll() hand back a platform
        // `Int!` that the rest of this port consumes as a plain `Int` — an unguarded
        // unbox on every use. The queue is in fact non-empty at every loop top, but
        // only by construction: the `0 ->` branch below always re-adds, and the `1 ->`
        // branch leaves its sole element in place. That invariant is non-local, so
        // state it here once rather than let eleven sites unbox on faith.
        while (true) {
            val beginPos = posList.peek() ?: break // unreachable; see the invariant above
            if (beginPos >= lenText) break
            posList.poll() // == beginPos; the peek above is the loop's guard
            for (len in dict.prefixLengths(text, beginPos)) {
                val endCand = beginPos + len
                if (validPos[endCand]) {
                    graph.getOrPut(beginPos) { ArrayList() }.add(endCand)
                    graphSize++
                    if (!posList.contains(endCand)) posList.add(endCand)
                    if (graphSize > MAX_GRAPH_SIZE) break
                }
            }
            when (posList.size) {
                1 -> {
                    // No longer ambiguous: commit the (shortest = fewest-words)
                    // path from the last committed endPos to the sole frontier.
                    val goal = posList.peek()!! // size == 1 — this is that branch
                    val path = bfsFirstPath(graph, endPos, goal)
                    graphSize = 0
                    graph.clear()
                    if (path != null) {
                        for (k in 1 until path.size) {
                            out.add(text.substring(endPos, path[k]))
                            endPos = path[k]
                        }
                    } else if (goal > endPos) {
                        // Connected by construction; this defensive branch only
                        // guards pathological input so we slice rather than crash.
                        out.add(text.substring(endPos, goal))
                        endPos = goal
                    }
                }
                0 -> {
                    // No dictionary candidate at beginPos: emit one non-dictionary
                    // token, skipping to the next plausible word start.
                    val ep: Int
                    if (matchAt(nonThai, beginPos, lenText)) {
                        ep = nonThai.end() // non-Thai run (latin/number/symbol/space)
                    } else {
                        var found = lenText
                        var pos = beginPos + 1
                        while (pos < lenText) {
                            if (validPos[pos]) {
                                val realThai = dict.prefixLengths(text, pos).any { l ->
                                    validPos[pos + l] && !isTwoCharThai(text, pos, l)
                                }
                                if (realThai) { found = pos; break }       // Thai word >2 chars
                                if (matchAt(nonThai, pos, lenText)) { found = pos; break }
                            }
                            pos++
                        }
                        ep = found
                    }
                    graphSize = 0
                    graph.clear()
                    out.add(text.substring(beginPos, ep))
                    posList.add(ep)
                    endPos = ep
                }
                // else: still ambiguous — keep popping the next smallest position.
            }
        }
    }

    /** First BFS path start→goal through [graph]; null if none. Mirrors
     *  `next(_bfs_paths_graph(...))` — BFS yields a fewest-edges path first. */
    private fun bfsFirstPath(graph: Map<Int, List<Int>>, start: Int, goal: Int): List<Int>? {
        val visited = HashSet<Int>().apply { add(start) }
        val queue = ArrayDeque<List<Int>>()
        queue.add(listOf(start))
        while (queue.isNotEmpty()) {
            val path = queue.removeFirst()
            for (pos in graph[path.last()].orEmpty()) {
                if (pos == goal) return path + pos
                if (visited.add(pos)) queue.add(path + pos)
            }
        }
        return null
    }

    /** `re.match(pattern, text, pos)` equivalent: anchored at [pos], `$` at [end]. */
    private fun matchAt(m: Matcher, pos: Int, end: Int): Boolean {
        m.region(pos, end)
        return m.lookingAt()
    }

    /** `_PAT_THAI_TWOCHARS = [ก-ฮ]{,2}$` — true iff `text[start, start+len)` is
     *  0–2 Thai consonants only (a too-short fragment to anchor a skip on). */
    private fun isTwoCharThai(text: String, start: Int, len: Int): Boolean {
        if (len > 2) return false
        for (i in start until start + len) {
            if (text[i] !in 'ก'..'ฮ') return false
        }
        return true
    }

    private companion object {
        /** Upstream `_MAX_GRAPH_SIZE`: cap the DAG to avoid exponential blowup. */
        const val MAX_GRAPH_SIZE = 50

        /** Upstream `_PAT_NONTHAI` (verbose mode flattened): latin run | number |
         *  spaces | newline | other non-Thai run. */
        val PAT_NONTHAI: Pattern = Pattern.compile(
            "[-a-zA-Z]+|\\d+([,.]\\d+)*|[ \\t]+|\\r?\\n|[^\\u0E00-\\u0E7F \\t\\r\\n]+",
        )
    }
}

/**
 * A char trie over the Thai segmentation wordlist, supporting the prefix scan
 * `newmm` needs. Built once from the pack's `words.txt` (union of the Wiktionary
 * headwords and the PyThaiNLP CC0 list) and reused for the engine's lifetime.
 */
class ThaiWordTrie private constructor(private val root: Node) {

    private class Node {
        var children: HashMap<Char, Node>? = null
        var isWord = false
    }

    /** Lengths of dictionary words that are a prefix of [text] starting at
     *  [begin], in increasing order (mirrors `Trie.prefixes`). */
    fun prefixLengths(text: String, begin: Int): List<Int> {
        val out = ArrayList<Int>(4)
        var node = root
        var i = begin
        val n = text.length
        while (i < n) {
            node = node.children?.get(text[i]) ?: break
            i++
            if (node.isWord) out.add(i - begin)
        }
        return out
    }

    companion object {
        fun of(words: Iterable<String>): ThaiWordTrie {
            val root = Node()
            for (w in words) {
                if (w.isEmpty()) continue
                var node = root
                for (c in w) {
                    val children = node.children ?: HashMap<Char, Node>(4).also { node.children = it }
                    node = children.getOrPut(c) { Node() }
                }
                node.isWord = true
            }
            return ThaiWordTrie(root)
        }
    }
}
