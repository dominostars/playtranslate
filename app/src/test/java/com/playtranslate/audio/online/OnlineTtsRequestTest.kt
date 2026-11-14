package com.playtranslate.audio.online

import com.playtranslate.PtJson
import com.playtranslate.audio.online.OnlineTtsRequest.Issue
import com.playtranslate.audio.online.OnlineTtsRequest.Token
import com.playtranslate.audio.online.OnlineTtsRequest.Values
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * [OnlineTtsRequest]: a config and its values as one HTTP request, each value
 * escaped for where its token stands; what stops a config being saved; what
 * makes two requests the same clip; and a service's lists read back.
 */
class OnlineTtsRequestTest {

    private val openAi = OnlineTtsConfig(
        preset = OnlineTtsPreset.OPENAI, model = OnlineTtsChoice("gpt-4o-mini-tts"),
        instructions = "Speak slowly.",
    )
    private val elevenLabs = OnlineTtsConfig(
        preset = OnlineTtsPreset.ELEVENLABS, model = OnlineTtsChoice("eleven_multilingual_v2", "Multilingual v2"),
    )

    private fun values(
        text: String = "こんにちは",
        voice: String = "coral",
        config: OnlineTtsConfig = openAi,
        speed: Float = 1f,
        key: String = "sk-test",
    ) = Values(
        text = text, voice = voice, model = config.model?.id.orEmpty(),
        instructions = config.instructions, speed = speed,
        language = "ja", languageName = "Japanese", key = key,
    )

    private fun body(config: OnlineTtsConfig, values: Values): JsonObject =
        PtJson.lenient.parseToJsonElement(OnlineTtsRequest.render(config, values)!!.body!!) as JsonObject

    // ── The two presets as requests ──────────────────────────────────────

    @Test fun `the OpenAI preset is a POST with a bearer key and every value in its field`() {
        val r = OnlineTtsRequest.render(openAi, values(speed = 1.5f))!!
        assertEquals("https://api.openai.com/v1/audio/speech", r.url.toString())
        assertEquals(
            listOf("Authorization" to "Bearer sk-test", "Content-Type" to "application/json"),
            r.headers,
        )
        val json = body(openAi, values(speed = 1.5f))
        assertEquals("gpt-4o-mini-tts", json["model"]!!.jsonPrimitive.content)
        assertEquals("coral", json["voice"]!!.jsonPrimitive.content)
        assertEquals("こんにちは", json["input"]!!.jsonPrimitive.content)
        assertEquals(
            "the language is named ahead of what the user typed",
            "Speak in Japanese. Speak slowly.",
            json["instructions"]!!.jsonPrimitive.content,
        )
        assertEquals("mp3", json["response_format"]!!.jsonPrimitive.content)
        assertTrue("speed is a bare number", r.body!!.contains("\"speed\":1.5,"))
    }

    @Test fun `the ElevenLabs preset carries the voice in its path and the key in its own header`() {
        val r = OnlineTtsRequest.render(elevenLabs, values(voice = "21m00Tcm4TlvDq8ikWAM", config = elevenLabs))!!
        assertEquals(
            "https://api.elevenlabs.io/v1/text-to-speech/21m00Tcm4TlvDq8ikWAM?output_format=mp3_44100_128",
            r.url.toString(),
        )
        assertEquals("sk-test", r.headers.single { it.first == "xi-api-key" }.second)
        val json = body(elevenLabs, values(voice = "v", config = elevenLabs))
        assertEquals("こんにちは", json["text"]!!.jsonPrimitive.content)
        assertEquals("eleven_multilingual_v2", json["model_id"]!!.jsonPrimitive.content)
        assertEquals("the text's language, as a code", "ja", json["language_code"]!!.jsonPrimitive.content)
        assertEquals(
            "the speed, among the settings of this one request",
            "1.0", json["voice_settings"]!!.jsonObject["speed"]!!.jsonPrimitive.content,
        )
    }

    // ── The speed a service takes ────────────────────────────────────────

    private fun elevenLabsSpeed(slider: Float): String =
        body(elevenLabs, values(config = elevenLabs, speed = slider))["voice_settings"]!!
            .jsonObject["speed"]!!.jsonPrimitive.content

    @Test fun `the ElevenLabs preset sends the slider's speed, and its nearest limit past either end`() {
        assertEquals("inside the range, as it is", "0.9", elevenLabsSpeed(0.9f))
        assertEquals("1.1", elevenLabsSpeed(1.1f))
        assertEquals("the ends themselves", "0.7", elevenLabsSpeed(0.7f))
        assertEquals("1.2", elevenLabsSpeed(1.2f))
        assertEquals("the slider's slow detent and its slow end", "0.7", elevenLabsSpeed(1f / 1.5f))
        assertEquals("0.7", elevenLabsSpeed(0.5f))
        assertEquals("the slider's fast detent and its fast end", "1.2", elevenLabsSpeed(1.5f))
        assertEquals("1.2", elevenLabsSpeed(2f))
    }

    @Test fun `the OpenAI preset sends the slider's whole range as it is`() {
        fun sent(slider: Float) = body(openAi, values(speed = slider))["speed"]!!.jsonPrimitive.content
        assertEquals("0.5", sent(0.5f))
        assertEquals("2.0", sent(2f))
        assertNull(OnlineTtsPreset.OPENAI.spec.speedRange)
    }

    @Test fun `two speeds past a service's limit are one clip, and two inside it are two`() {
        fun clip(slider: Float) = OnlineTtsRequest.identity(elevenLabs, values(config = elevenLabs, speed = slider))!!
        assertEquals(clip(1.5f), clip(2f))
        assertEquals("the limit itself is that clip", clip(1.2f), clip(2f))
        assertEquals(clip(0.5f), clip(0.7f))
        assertNotEquals(clip(0.9f), clip(1.1f))
        assertNotEquals(clip(1f), clip(1.2f))
        assertNotEquals(clip(0.7f), clip(1f))
    }

    // ── The service is told the language ─────────────────────────────────

    @Test fun `the OpenAI preset names the language even when the user typed no instructions`() {
        val plain = openAi.copy(instructions = "")
        // A kanji-only word: what a service given no language reads as
        // Chinese from one voice and with an English accent from another.
        val json = body(plain, values(text = "日本語", config = plain))
        assertEquals("日本語", json["input"]!!.jsonPrimitive.content)
        assertEquals("Speak in Japanese.", json["instructions"]!!.jsonPrimitive.content.trim())
    }

    @Test fun `the same word is asked for in the language of its own request`() {
        // Spelled alike in English and in French, pronounced differently.
        fun asked(config: OnlineTtsConfig, code: String, name: String) =
            body(config, values(text = "chat", config = config).copy(language = code, languageName = name))
        assertEquals("Speak in French. Speak slowly.", asked(openAi, "fr", "French")["instructions"]!!.jsonPrimitive.content)
        assertEquals("Speak in English. Speak slowly.", asked(openAi, "en", "English")["instructions"]!!.jsonPrimitive.content)
        assertEquals("fr", asked(elevenLabs, "fr", "French")["language_code"]!!.jsonPrimitive.content)
        assertEquals("en", asked(elevenLabs, "en", "English")["language_code"]!!.jsonPrimitive.content)
    }

    @Test fun `the same text in another language is another clip`() {
        val french = values(text = "chat").copy(language = "fr", languageName = "French")
        val english = values(text = "chat").copy(language = "en", languageName = "English")
        assertNotEquals(OnlineTtsRequest.identity(openAi, french), OnlineTtsRequest.identity(openAi, english))
        assertNotEquals(OnlineTtsRequest.identity(elevenLabs, french), OnlineTtsRequest.identity(elevenLabs, english))
    }

    // ── Escaping by position ─────────────────────────────────────────────

    @Test fun `a value in the body is escaped as the inside of a JSON string`() {
        val text = "She said \"no\\yes\"\nthen left\t$1 {x}"
        assertEquals(text, body(openAi, values(text = text))["input"]!!.jsonPrimitive.content)
    }

    @Test fun `a token inside a value is spoken, not filled`() {
        val r = OnlineTtsRequest.render(openAi, values(text = "say {key} and {voice}", key = "SECRET"))!!
        assertEquals("say {key} and {voice}", body(openAi, values(text = "say {key} and {voice}"))["input"]!!.jsonPrimitive.content)
        assertFalse("the key reaches the body only where its token stands", r.body!!.contains("SECRET"))
    }

    @Test fun `a value in the URL is percent-encoded as one component`() {
        val r = OnlineTtsRequest.render(elevenLabs, values(voice = "a b/c?d&e=f~g*h", config = elevenLabs))!!
        assertEquals(
            "/v1/text-to-speech/a%20b%2Fc%3Fd%26e%3Df~g%2Ah",
            r.url.encodedPath,
        )
        assertEquals("the value added no query of its own", "output_format=mp3_44100_128", r.url.encodedQuery)
    }

    @Test fun `the speed is written with a point under a locale whose decimal mark is a comma`() {
        val saved = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            val r = OnlineTtsRequest.render(openAi, values(speed = 0.75f))!!
            assertTrue(r.body, r.body!!.contains("\"speed\":0.75,"))
        } finally {
            Locale.setDefault(saved)
        }
    }

    // ── The request text ─────────────────────────────────────────────────

    @Test fun `Windows line endings read as the same request`() {
        val crlf = openAi.copy(request = OnlineTtsPreset.OPENAI.spec.request.replace("\n", "\r\n"))
        val a = OnlineTtsRequest.render(openAi, values())!!
        val b = OnlineTtsRequest.render(crlf, values())!!
        assertEquals(a.headers, b.headers)
        assertEquals(a.body, b.body)
    }

    @Test fun `a header splits at its first colon and the body needs no blank line before it`() {
        val config = openAi.copy(request = "X-Trace: a: b\n{\"input\":\"{text}\"}")
        val r = OnlineTtsRequest.render(config, values(text = "hi"))!!
        assertEquals(listOf("X-Trace" to "a: b"), r.headers)
        assertEquals("{\"input\":\"hi\"}", r.body)
    }

    @Test fun `a request text with no header lines is all body`() {
        val config = openAi.copy(request = "{\"input\":\"{text}\"}")
        val r = OnlineTtsRequest.render(config, values(text = "hi"))!!
        assertTrue(r.headers.isEmpty())
        assertEquals("{\"input\":\"hi\"}", r.body)
    }

    @Test fun `a request text with no body is a GET`() {
        val config = openAi.copy(
            url = "https://tts.example.com/speak?q={text}&v={voice}",
            request = "Authorization: Bearer {key}",
        )
        val r = OnlineTtsRequest.render(config, values(text = "a b"))!!
        assertNull(r.body)
        assertEquals("https://tts.example.com/speak?q=a%20b&v=coral", r.url.toString())
    }

    @Test fun `a keyed header is left out while there is no key and the others stay`() {
        val r = OnlineTtsRequest.render(openAi, values(key = ""))!!
        assertEquals(listOf("Content-Type" to "application/json"), r.headers)
        val blank = OnlineTtsRequest.render(openAi, values(key = "   "))!!
        assertEquals(listOf("Content-Type" to "application/json"), blank.headers)
    }

    @Test fun `a filled header that is not printable ASCII makes no request`() {
        // A key pasted with a no-break space: OkHttp would refuse it with an
        // exception whose message quotes the value.
        assertNull(OnlineTtsRequest.render(openAi, values(key = "sk test")))
        assertNull(OnlineTtsRequest.render(openAi, values(key = "sk\ntest")))
        val voiceHeader = openAi.copy(request = "X-Voice: {voice}\n\n{\"input\":\"{text}\"}")
        assertNull(OnlineTtsRequest.render(voiceHeader, values(voice = "声")))
        assertNotNull(OnlineTtsRequest.render(voiceHeader, values(voice = "coral")))
    }

    // ── What stops a save ────────────────────────────────────────────────

    @Test fun `both presets with a model save clean, with a key and without one`() {
        assertEquals(emptyList<Issue>(), OnlineTtsRequest.validate(openAi, "sk-test"))
        assertEquals(emptyList<Issue>(), OnlineTtsRequest.validate(elevenLabs, "sk-test"))
        assertEquals(emptyList<Issue>(), OnlineTtsRequest.validate(openAi, ""))
    }

    @Test fun `a URL is refused unless it is https or http on the user's own network`() {
        fun issues(url: String) = OnlineTtsRequest.validate(openAi.copy(url = url), "k")
        assertEquals(listOf(Issue.BAD_URL), issues("http://api.example.com/v1/audio/speech"))
        assertEquals(listOf(Issue.BAD_URL), issues("not a url"))
        assertEquals(listOf(Issue.BAD_URL), issues(""))
        assertEquals(listOf(Issue.BAD_URL), issues("https://{voice}.example.com/speech"))
        assertEquals(emptyList<Issue>(), issues("http://192.168.1.5:8880/v1/audio/speech"))
        assertEquals(emptyList<Issue>(), issues("  https://tts.example.com/v1/audio/speech  "))
    }

    @Test fun `a request that sends no text is refused`() {
        val config = openAi.copy(request = "Authorization: Bearer {key}\n\n{\"model\":\"{model}\"}")
        assertEquals(listOf(Issue.NO_TEXT), OnlineTtsRequest.validate(config, "k"))
        val inUrl = config.copy(url = "https://tts.example.com/speak?q={text}")
        assertEquals(emptyList<Issue>(), OnlineTtsRequest.validate(inUrl, "k"))
    }

    @Test fun `the spoken text and the instructions are refused in a header`() {
        val text = openAi.copy(request = "X-Text: {text}\n\n{\"input\":\"{text}\"}")
        assertEquals(listOf(Issue.TEXT_IN_HEADER), OnlineTtsRequest.validate(text, "k"))
        val instructions = openAi.copy(request = "X-Style: {instructions}\n\n{\"input\":\"{text}\"}")
        assertEquals(listOf(Issue.TEXT_IN_HEADER), OnlineTtsRequest.validate(instructions, "k"))
    }

    @Test fun `a key that is not printable ASCII is refused at save`() {
        assertEquals(listOf(Issue.HEADER_NOT_ASCII), OnlineTtsRequest.validate(openAi, "sk test"))
    }

    @Test fun `a model is required only while the request names one`() {
        assertEquals(listOf(Issue.NO_MODEL), OnlineTtsRequest.validate(openAi.copy(model = null), "k"))
        assertEquals(
            listOf(Issue.NO_MODEL),
            OnlineTtsRequest.validate(openAi.copy(model = OnlineTtsChoice("  ")), "k"),
        )
        val noModel = openAi.copy(model = null, request = "Authorization: Bearer {key}\n\n{\"input\":\"{text}\"}")
        assertEquals(emptyList<Issue>(), OnlineTtsRequest.validate(noModel, "k"))
    }

    // ── What makes two requests the same clip ────────────────────────────

    @Test fun `the identity changes with everything that changes the audio`() {
        val base = OnlineTtsRequest.identity(openAi, values())!!
        assertNotEquals(base, OnlineTtsRequest.identity(openAi, values(text = "さようなら")))
        assertNotEquals(base, OnlineTtsRequest.identity(openAi, values(voice = "nova")))
        assertNotEquals(base, OnlineTtsRequest.identity(openAi, values(speed = 1.5f)))
        val otherModel = openAi.copy(model = OnlineTtsChoice("tts-1"))
        assertNotEquals(base, OnlineTtsRequest.identity(otherModel, values(config = otherModel)))
        val otherStyle = openAi.copy(instructions = "Whisper.")
        assertNotEquals(base, OnlineTtsRequest.identity(otherStyle, values(config = otherStyle)))
        val otherBody = openAi.copy(request = openAi.effectiveRequest.replace("\"mp3\"", "\"wav\""))
        assertNotEquals(base, OnlineTtsRequest.identity(otherBody, values()))
        val otherHeader = openAi.copy(request = "Accept: audio/wav\n" + openAi.effectiveRequest)
        assertNotEquals(base, OnlineTtsRequest.identity(otherHeader, values()))
        val otherUrl = openAi.copy(url = "http://192.168.1.5:8880/v1/audio/speech")
        assertNotEquals(base, OnlineTtsRequest.identity(otherUrl, values()))
    }

    @Test fun `the identity does not change with the key and never holds it`() {
        val a = OnlineTtsRequest.identity(openAi, values(key = "sk-FIRST"))!!
        val b = OnlineTtsRequest.identity(openAi, values(key = "sk-SECOND"))!!
        val none = OnlineTtsRequest.identity(openAi, values(key = ""))!!
        assertEquals(a, b)
        assertEquals(a, none)
        assertFalse(a.contains("sk-FIRST"))
        val keyInUrl = openAi.copy(url = "https://tts.example.com/speak?key={key}")
        val c = OnlineTtsRequest.identity(keyInUrl, values(key = "sk-FIRST"))!!
        assertFalse(c.contains("sk-FIRST"))
        assertEquals(c, OnlineTtsRequest.identity(keyInUrl, values(key = "sk-SECOND")))
    }

    // ── The page's rows ──────────────────────────────────────────────────

    @Test fun `the tokens of a config are the ones its URL and request text hold`() {
        assertEquals(
            setOf(
                Token.TEXT, Token.VOICE, Token.MODEL, Token.INSTRUCTIONS, Token.SPEED,
                Token.LANGUAGE_NAME, Token.KEY,
            ),
            OnlineTtsRequest.tokensIn(openAi),
        )
        assertEquals(
            setOf(Token.TEXT, Token.VOICE, Token.MODEL, Token.SPEED, Token.LANGUAGE, Token.KEY),
            OnlineTtsRequest.tokensIn(elevenLabs),
        )
    }

    // ── A preset's text is stored as null ────────────────────────────────

    @Test fun `a config that kept its preset's texts stores none of them`() {
        val spec = OnlineTtsPreset.OPENAI.spec
        val untouched = openAi.copy(
            url = " ${spec.url} ",
            request = spec.request.replace("\n", "\r\n") + "\n\n",
        ).normalized()
        assertNull(untouched.url)
        assertNull(untouched.request)
        assertTrue("no built-in voice is copied into the user's own", untouched.manualVoices.isEmpty())
    }

    // ── The voices a kind of service has built in ────────────────────────

    @Test fun `a model is offered only the built-in voices it takes`() {
        val spec = OnlineTtsPreset.OPENAI.spec
        val all = spec.builtInVoices
        assertEquals(13, all.size)
        assertEquals(all, spec.builtInVoicesFor("gpt-4o-mini-tts"))
        assertEquals("a model the table does not name takes them all", all, spec.builtInVoicesFor("some-later-model"))
        assertEquals("no model chosen yet", all, spec.builtInVoicesFor(null))
        val nine = listOf("alloy", "ash", "coral", "echo", "fable", "onyx", "nova", "sage", "shimmer")
        assertEquals(nine, spec.builtInVoicesFor("tts-1"))
        assertEquals(nine, spec.builtInVoicesFor("tts-1-hd"))
        // The four the older models refuse with an HTTP 400.
        assertEquals(setOf("ballad", "verse", "marin", "cedar"), all.toSet() - nine.toSet())
        assertTrue(OnlineTtsPreset.ELEVENLABS.spec.builtInVoicesFor("eleven_multilingual_v2").isEmpty())
    }

    // ── The model picked for a user who chose none ───────────────────────

    @Test fun `the model picked is the first the preset prefers that the service lists`() {
        fun models(vararg ids: String) = ids.map { OnlineTtsChoice(it) }
        val openAiSpec = OnlineTtsPreset.OPENAI.spec
        assertEquals(
            "the one that reads instructions, whatever the list's order",
            OnlineTtsChoice("gpt-4o-mini-tts"),
            openAiSpec.pickModel(models("gpt-4o", "tts-1-hd", "tts-1", "gpt-4o-mini-tts", "whisper-1")),
        )
        assertEquals(OnlineTtsChoice("tts-1"), openAiSpec.pickModel(models("gpt-4o", "tts-1-hd", "tts-1")))
        assertEquals(OnlineTtsChoice("tts-1-hd"), openAiSpec.pickModel(models("gpt-4o", "tts-1-hd")))
        // The listed model is what is returned, with the name the service gave it.
        val elevenSpec = OnlineTtsPreset.ELEVENLABS.spec
        val flash = OnlineTtsChoice("eleven_flash_v2_5", "Eleven Flash v2.5")
        assertEquals(flash, elevenSpec.pickModel(listOf(OnlineTtsChoice("eleven_multilingual_v2", "Multilingual"), flash)))
        assertEquals(
            OnlineTtsChoice("eleven_multilingual_v2", "Multilingual"),
            elevenSpec.pickModel(listOf(OnlineTtsChoice("eleven_v3"), OnlineTtsChoice("eleven_multilingual_v2", "Multilingual"))),
        )
    }

    @Test fun `with nothing the preset prefers a lone model is picked and several are not guessed at`() {
        val spec = OnlineTtsPreset.OPENAI.spec
        assertEquals(OnlineTtsChoice("kokoro"), spec.pickModel(listOf(OnlineTtsChoice("kokoro"))))
        assertNull(spec.pickModel(listOf(OnlineTtsChoice("gpt-4o"), OnlineTtsChoice("whisper-1"))))
        assertNull(spec.pickModel(emptyList()))
    }

    @Test fun `the hand-listed voices are the model's built-in ones, then the user's own, each once`() {
        val older = openAi.copy(model = OnlineTtsChoice("tts-1"), manualVoices = listOf("my_voice", "sage"))
        assertEquals(
            listOf("alloy", "ash", "coral", "echo", "fable", "onyx", "nova", "sage", "shimmer", "my_voice"),
            older.handListedVoices,
        )
        assertFalse(older.handListedVoices.contains("marin"))
        assertTrue(openAi.handListedVoices.contains("marin"))
        assertEquals(
            listOf("rachel"),
            elevenLabs.copy(manualVoices = listOf("rachel")).handListedVoices,
        )
    }

    @Test fun `a voice is known to be refused only when it is a built-in one the model does not take`() {
        val older = openAi.copy(model = OnlineTtsChoice("tts-1"))
        for (voice in listOf("ballad", "verse", "marin", "cedar")) {
            assertTrue(voice, older.doesNotTake(voice))
            assertTrue(voice, openAi.copy(model = OnlineTtsChoice("tts-1-hd")).doesNotTake(voice))
            assertFalse("the newest model takes $voice", openAi.copy(model = OnlineTtsChoice("gpt-4o-mini-tts")).doesNotTake(voice))
            assertFalse("with no model chosen nothing is known", openAi.copy(model = null).doesNotTake(voice))
        }
        assertFalse("one of the nine", older.doesNotTake("coral"))
        assertFalse("a voice the app holds no data about is not judged", older.doesNotTake("jf_alpha"))
        val typedIn = older.copy(manualVoices = listOf("marin"))
        assertFalse("a built-in name the user typed in is offered, so it is not refused", typedIn.doesNotTake("marin"))
        assertTrue(typedIn.doesNotTake("cedar"))
        assertFalse(
            "a preset with no built-in voices refuses none",
            elevenLabs.copy(model = OnlineTtsChoice("tts-1")).doesNotTake("marin"),
        )
    }

    @Test fun `a config that changed a text stores that text`() {
        val edited = openAi.copy(
            url = "http://192.168.1.5:8880/v1/audio/speech",
            request = "{\"input\":\"{text}\"}",
            manualVoices = listOf(" jf_alpha ", "", "zf_xiaobei"),
        ).normalized()
        assertEquals("http://192.168.1.5:8880/v1/audio/speech", edited.url)
        assertEquals("{\"input\":\"{text}\"}", edited.request)
        assertEquals(listOf("jf_alpha", "zf_xiaobei"), edited.manualVoices)
        assertEquals("http://192.168.1.5:8880/v1/audio/speech", edited.effectiveUrl)
    }

    // ── A service's lists ────────────────────────────────────────────────

    @Test fun `a list is fetched beside the request URL with the request's own key header`() {
        val spec = OnlineTtsPreset.OPENAI.spec
        val voices = OnlineTtsRequest.renderList(openAi, spec.voices, "sk-test")!!
        assertEquals("https://api.openai.com/v1/audio/voices", voices.url.toString())
        assertNull(voices.body)
        assertEquals("Bearer sk-test", voices.headers.single { it.first == "Authorization" }.second)
        val models = OnlineTtsRequest.renderList(openAi, spec.models, "sk-test")!!
        assertEquals("https://api.openai.com/v1/models", models.url.toString())
    }

    @Test fun `a self-hosted server's lists follow the URL typed for it`() {
        val kokoro = openAi.copy(url = "http://192.168.1.5:8880/v1/audio/speech")
        val spec = OnlineTtsPreset.OPENAI.spec
        assertEquals(
            "http://192.168.1.5:8880/v1/audio/voices",
            OnlineTtsRequest.renderList(kokoro, spec.voices, "")!!.url.toString(),
        )
        assertEquals(
            "http://192.168.1.5:8880/v1/models",
            OnlineTtsRequest.renderList(kokoro, spec.models, "")!!.url.toString(),
        )
    }

    @Test fun `the ElevenLabs lists resolve from the host with no voice in the URL yet`() {
        val spec = OnlineTtsPreset.ELEVENLABS.spec
        assertEquals(
            "https://api.elevenlabs.io/v2/voices?page_size=100",
            OnlineTtsRequest.renderList(elevenLabs, spec.voices, "k")!!.url.toString(),
        )
        assertEquals(
            "https://api.elevenlabs.io/v1/models",
            OnlineTtsRequest.renderList(elevenLabs, spec.models, "k")!!.url.toString(),
        )
    }

    @Test fun `a key the URL keeps in its query goes with a list request, and nothing else of that query does`() {
        val keyed = openAi.copy(
            url = "https://tts.example.com/v1/audio/speech?api_key={key}&voice={voice}&input={text}",
            request = "",
        )
        val spec = OnlineTtsPreset.OPENAI.spec
        assertEquals(
            "https://tts.example.com/v1/models?api_key=sk-test",
            OnlineTtsRequest.renderList(keyed, spec.models, "sk-test")!!.url.toString(),
        )
        assertEquals(
            "https://tts.example.com/v1/audio/voices?api_key=sk-test",
            OnlineTtsRequest.renderList(keyed, spec.voices, "sk-test")!!.url.toString(),
        )
        // Encoded as the speech URL itself encodes it.
        assertEquals(
            "https://tts.example.com/v1/models?api_key=a%20b%2Fc%26d",
            OnlineTtsRequest.renderList(keyed, spec.models, "a b/c&d")!!.url.toString(),
        )
        // After a query the list reference brings of its own.
        val elevenShaped = elevenLabs.copy(url = "https://tts.example.com/v1/text-to-speech/{voice}?token=Bearer%20{key}")
        assertEquals(
            "https://tts.example.com/v2/voices?page_size=100&token=Bearer%20k",
            OnlineTtsRequest.renderList(elevenShaped, OnlineTtsPreset.ELEVENLABS.spec.voices, "k")!!.url.toString(),
        )
    }

    @Test fun `with no key entered a list request carries no empty one`() {
        val keyed = openAi.copy(url = "https://tts.example.com/v1/audio/speech?api_key={key}")
        assertEquals(
            "https://tts.example.com/v1/models",
            OnlineTtsRequest.renderList(keyed, OnlineTtsPreset.OPENAI.spec.models, "")!!.url.toString(),
        )
    }

    @Test fun `a list request carries a key kept in a header or in the URL's query, and not one kept in the body`() {
        assertTrue("the presets keep it in a header", OnlineTtsRequest.listSendsKey(openAi))
        assertTrue(OnlineTtsRequest.listSendsKey(elevenLabs))
        val body = "Content-Type: application/json\n\n{\"input\":\"{text}\",\"voice\":\"{voice}\"}"
        assertTrue(
            OnlineTtsRequest.listSendsKey(openAi.copy(url = "https://tts.example.com/speak?k={key}", request = body)),
        )
        assertFalse(
            "a key in the body has nowhere to go on a GET",
            OnlineTtsRequest.listSendsKey(
                openAi.copy(request = "Content-Type: application/json\n\n{\"key\":\"{key}\",\"input\":\"{text}\"}"),
            ),
        )
        assertFalse("a request with no key at all", OnlineTtsRequest.listSendsKey(openAi.copy(request = body)))
        assertFalse(
            "the token in a parameter's name, or outside the query, is not a keyed parameter",
            OnlineTtsRequest.listSendsKey(openAi.copy(url = "https://tts.example.com/{key}/speak?{key}=1", request = body)),
        )
    }

    @Test fun `a list reference that leaves the user's network over http is refused`() {
        val kokoro = openAi.copy(url = "http://192.168.1.5:8880/v1/audio/speech")
        val elsewhere = OnlineTtsListSpec(ref = "http://example.com/voices", arrayField = "voices", idField = "id")
        assertNull(OnlineTtsRequest.renderList(kokoro, elsewhere, "k"))
    }

    @Test fun `the OpenAI model list and a voice list of objects or of strings are read`() {
        val spec = OnlineTtsPreset.OPENAI.spec
        assertEquals(
            listOf(OnlineTtsChoice("tts-1"), OnlineTtsChoice("gpt-4o-mini-tts")),
            OnlineTtsRequest.parseList(spec.models, """{"object":"list","data":[{"id":"tts-1"},{"id":"gpt-4o-mini-tts"}]}"""),
        )
        assertEquals(
            listOf(OnlineTtsChoice("jf_alpha", "Alpha"), OnlineTtsChoice("zf_xiaobei")),
            OnlineTtsRequest.parseList(spec.voices, """{"voices":[{"id":"jf_alpha","name":"Alpha"},{"id":"zf_xiaobei"}]}"""),
        )
        assertEquals(
            listOf(OnlineTtsChoice("jf_alpha"), OnlineTtsChoice("zf_xiaobei")),
            OnlineTtsRequest.parseList(spec.voices, """{"voices":["jf_alpha","zf_xiaobei",""]}"""),
        )
    }

    @Test fun `the ElevenLabs voices carry their names and its models only the ones that speak`() {
        val spec = OnlineTtsPreset.ELEVENLABS.spec
        assertEquals(
            listOf(OnlineTtsChoice("21m00Tcm4TlvDq8ikWAM", "Rachel")),
            OnlineTtsRequest.parseList(
                spec.voices,
                """{"voices":[{"voice_id":"21m00Tcm4TlvDq8ikWAM","name":"Rachel","labels":{"accent":"american"}},{"name":"no id"}],"has_more":false}""",
            ),
        )
        assertEquals(
            listOf(OnlineTtsChoice("eleven_multilingual_v2", "Multilingual v2")),
            OnlineTtsRequest.parseList(
                spec.models,
                """[{"model_id":"eleven_multilingual_v2","name":"Multilingual v2","can_do_text_to_speech":true},
                    {"model_id":"scribe_v1","name":"Scribe","can_do_text_to_speech":false},
                    {"model_id":"unknown","name":"No flag"}]""",
            ),
        )
    }

    @Test fun `a reply that is not the list is not read as an empty one`() {
        val spec = OnlineTtsPreset.OPENAI.spec
        assertNull(OnlineTtsRequest.parseList(spec.voices, "<html>Not Found</html>"))
        assertNull(OnlineTtsRequest.parseList(spec.voices, """{"error":{"message":"Incorrect API key"}}"""))
        assertNull(OnlineTtsRequest.parseList(spec.voices, """{"voices":"none"}"""))
        assertNull(OnlineTtsRequest.parseList(OnlineTtsPreset.ELEVENLABS.spec.models, """{"detail":"unauthorized"}"""))
        assertEquals(emptyList<OnlineTtsChoice>(), OnlineTtsRequest.parseList(spec.voices, """{"voices":[]}"""))
    }
}
