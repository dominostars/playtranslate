package com.playtranslate.cloud

import android.content.Context
import com.playtranslate.Prefs
import com.playtranslate.security.SecretCodec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.Dispatcher
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.asResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.Source
import okio.Timeout
import okio.buffer
import java.io.IOException
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Shared pieces for the PlayTranslate Cloud tests: fixtures in the wire's
 *  two-part shape, a [Prefs] with a reversible codec, and canned HTTP. */
internal object PtCloudTestSupport {

    const val BASE_URL = "https://pt.example.test"
    const val KEY = "test-client-key"

    /** Reversible stand-in for the AndroidKeyStore codec (instrumented-only),
     *  as in OnlineServiceStoreTest. */
    class FakeCodec : SecretCodec {
        override fun encrypt(context: String, plaintext: String): String? = "$PREFIX$context|$plaintext"
        override fun decrypt(context: String, stored: String): String? {
            val prefix = "$PREFIX$context|"
            return if (stored.startsWith(prefix)) stored.removePrefix(prefix) else null
        }
        companion object { const val PREFIX = "enc:" }
    }

    fun sharedPrefs(ctx: Context) = ctx.getSharedPreferences("playtranslate_prefs", Context.MODE_PRIVATE)

    fun prefs(ctx: Context): Prefs = Prefs(ctx, FakeCodec())

    /** A config pointing at [BASE_URL] with [KEY]. */
    fun config(ctx: Context, baseUrl: String = BASE_URL, key: String = KEY): PtCloudConfig {
        val p = prefs(ctx)
        p.ptCloudBaseUrl = baseUrl
        p.ptCloudClientKey = key
        return PtCloudConfig(p)
    }

    // ── Fixtures ───────────────────────────────────────────────────────

    const val JA = "bof3_camping_01.json"
    const val LATIN = "persona-5-ichiko-ohya-hangout-leblanc.json"
    const val AR = "arabic_wikipedia.json"

    private val json = Json { ignoreUnknownKeys = true }

    fun fixture(name: String): JsonObject {
        val text = PtCloudTestSupport::class.java.getResourceAsStream("/ptcloud/$name")!!
            .bufferedReader(Charsets.UTF_8).use { it.readText() }
        return json.parseToJsonElement(text) as JsonObject
    }

    /** A fixture (the single-JSON shape) as the part-1 line the two-part
     *  response sends: `"part":"ocr"`, status `pending`, no translation
     *  error, and a per-group translation only for the groups [translated]
     *  names by id (the ones the server's cache answered). [edit] may change
     *  it first. */
    fun part1(
        name: String,
        translated: Map<Int, String> = emptyMap(),
        edit: (JsonObject) -> JsonObject = { it },
    ): String {
        val single = fixture(name)
        val groups = (single["groups"] as JsonArray).map { g ->
            val group = JsonObject((g as JsonObject) - "translation")
            translated[group["id"]!!.jsonPrimitive.int]?.let { group.with("translation", JsonPrimitive(it)) } ?: group
        }
        val obj = JsonObject(
            single - "translation_error" + mapOf(
                "part" to JsonPrimitive("ocr"),
                "translation_status" to JsonPrimitive("pending"),
                "groups" to JsonArray(groups),
            ),
        )
        return edit(obj).toString()
    }

    fun part2(status: String = "ok", translations: String = """[{"id":0,"translation":"Hello"}]""", extra: String = ""): String =
        """{"part":"translation","schema_version":1,"frame_id":"f","translation_status":"$status",""" +
            """"translations":$translations$extra,"timings":{"translate_ms":988.0,"total_ms":1210.0}}"""

    /** An item line ("Incremental translations"): one group's translation,
     *  sent between part 1 and the final part. */
    fun item(id: Int, translation: String, tMs: Double = 900.0): String =
        """{"part":"translation_item","schema_version":1,"frame_id":"f","id":$id,""" +
            """"translation":"$translation","t_ms":$tMs}"""

    /** [obj] with [key] replaced. */
    fun JsonObject.with(key: String, value: JsonElement) = JsonObject(this + (key to value))

    fun meta(persona: String? = null, context: String? = null, streamTranslations: Boolean? = null, filterFurigana: Boolean = true) =
        FrameMeta(
            source = "ja", target = "en", frameId = "capture-0042", persona = persona, context = context,
            streamTranslations = streamTranslations, filterFurigana = filterFurigana,
        )

    fun frame(bytes: ByteArray = ByteArray(64) { it.toByte() }, scale: Float = 1f, format: FrameFormat = FrameFormat.JPEG) =
        EncodedFrame(bytes, format, scale, width = 1920, height = 1080, encodeMs = 0)

    // ── Canned HTTP ────────────────────────────────────────────────────

    /** A client whose every call answers [code] with [body] (and
     *  [retryAfter] as `Retry-After`), recording each request it sees. */
    fun cannedClient(
        code: Int,
        body: String,
        retryAfter: String? = null,
        seen: MutableList<okhttp3.Request> = mutableListOf(),
    ): OkHttpClient = OkHttpClient.Builder().addInterceptor { chain ->
        seen += chain.request()
        Response.Builder()
            .request(chain.request())
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message(if (code == 200) "OK" else "Error")
            .apply { if (retryAfter != null) header("Retry-After", retryAfter) }
            .body(body.toResponseBody("application/json".toMediaType()))
            .build()
    }.build()

    /** A client whose every call fails on the transport with [e]. */
    fun failingClient(e: IOException): OkHttpClient =
        OkHttpClient.Builder().addInterceptor(Interceptor { throw e }).build()

    /** A client answering 200 with an NDJSON body built by [body] for the
     *  call, and [headers] as response headers, so a test can gate its lines
     *  ([GatedSource]). */
    fun streamingClient(
        seen: MutableList<Call> = mutableListOf(),
        headers: Map<String, String> = emptyMap(),
        body: (Call) -> Source,
    ): OkHttpClient = OkHttpClient.Builder().addInterceptor { chain ->
        seen += chain.call()
        Response.Builder()
            .request(chain.request())
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .apply { headers.forEach { (name, value) -> header(name, value) } }
            .body(body(chain.call()).buffer().asResponseBody("application/x-ndjson".toMediaType()))
            .build()
    }.build()

    /** [this] client with its enqueued calls run on [executor], so a test can
     *  wait for a fire-and-forget call's callback ([CapturingExecutor.await]). */
    fun OkHttpClient.onExecutor(executor: ExecutorService): OkHttpClient =
        newBuilder().dispatcher(Dispatcher(executor)).build()

    /**
     * One thread for OkHttp's enqueued calls that keeps whatever escapes a
     * task (OkHttp rethrows onto its thread anything but an IOException a
     * callback throws), so a test can assert a callback threw nothing. [await] shuts it down and waits,
     * after which every enqueued callback has run.
     */
    class CapturingExecutor {
        val thrown: MutableList<Throwable> = Collections.synchronizedList(mutableListOf())
        val executor: ExecutorService = Executors.newSingleThreadExecutor { r ->
            Thread(r, "pt-cloud-test-dispatcher").apply { setUncaughtExceptionHandler { _, t -> thrown += t } }
        }

        fun await() {
            executor.shutdown()
            check(executor.awaitTermination(5, TimeUnit.SECONDS)) { "enqueued calls never finished" }
        }
    }

    /**
     * A response body that yields [lines] one at a time, each followed by a
     * newline and each held back until its gate (null = none) is released,
     * then ends. While it waits it watches [call]: a cancelled call fails the
     * read the way OkHttp's closed socket does. [waiting] counts down each
     * time a read starts waiting on a gate, so a test knows the reader is
     * blocked there. A gate never released within [MAX_WAIT_MS] fails the
     * read, so a broken cancellation path fails its test instead of hanging
     * the run.
     */
    class GatedSource(
        private val call: Call,
        private val lines: List<String>,
        private val gates: List<CountDownLatch?>,
        private val waiting: CountDownLatch = CountDownLatch(0),
    ) : Source {
        private var next = 0
        private val pending = Buffer()

        override fun read(sink: Buffer, byteCount: Long): Long {
            while (pending.size == 0L) {
                if (next >= lines.size) return -1
                gates.getOrNull(next)?.let { gate ->
                    waiting.countDown()
                    val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(MAX_WAIT_MS)
                    while (!gate.await(5, TimeUnit.MILLISECONDS)) {
                        if (call.isCanceled()) throw IOException("Canceled")
                        if (System.nanoTime() > deadline) throw IOException("gate never released")
                    }
                }
                pending.writeUtf8(lines[next] + "\n")
                next++
            }
            return pending.read(sink, byteCount)
        }

        override fun timeout(): Timeout = Timeout.NONE
        override fun close() = Unit

        companion object { const val MAX_WAIT_MS = 8_000L }
    }
}
