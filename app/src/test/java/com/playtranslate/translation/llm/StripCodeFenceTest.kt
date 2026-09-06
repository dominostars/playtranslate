package com.playtranslate.translation.llm

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [stripCodeFence] guards the cloud batch parse on endpoints that ignore
 * `response_format` (Anthropic's compatibility layer): a reply fenced as
 * Markdown must decode as the JSON inside it, while anything that is not a
 * whole-payload fence passes through untouched.
 */
class StripCodeFenceTest {

    private val payload = """{"translations": ["こんにちは", "さようなら"]}"""

    @Test
    fun jsonTaggedFenceUnwraps() {
        assertEquals(payload, stripCodeFence("```json\n$payload\n```"))
    }

    @Test
    fun bareFenceUnwraps() {
        assertEquals(payload, stripCodeFence("```\n$payload\n```"))
    }

    @Test
    fun whitespaceAroundFenceIsTolerated() {
        assertEquals(payload, stripCodeFence("  \n```json  \n  $payload  \n```  \n"))
    }

    @Test
    fun crlfFenceUnwraps() {
        assertEquals(payload, stripCodeFence("```json\r\n$payload\r\n```"))
    }

    @Test
    fun singleLineFenceWithNoTagKeepsTheBrace() {
        assertEquals(payload, stripCodeFence("```$payload```"))
    }

    @Test
    fun unfencedPayloadIsUnchanged() {
        assertEquals(payload, stripCodeFence(payload))
    }

    @Test
    fun fenceInsideTheTextIsLeftAlone() {
        val text = "Use ```code``` here"
        assertEquals(text, stripCodeFence(text))
    }

    @Test
    fun unterminatedFenceIsLeftAlone() {
        val text = "```json\n$payload"
        assertEquals(text, stripCodeFence(text))
    }

    @Test
    fun emptyFenceYieldsEmpty() {
        assertEquals("", stripCodeFence("```json\n```"))
    }
}
