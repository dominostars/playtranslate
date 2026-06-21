package com.playtranslate.net

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Enforces the central-https invariant at build time: every OkHttp client must
 * be constructed via [PtHttp] (so it inherits [HttpsOnlyInterceptor]), never
 * with a raw `OkHttpClient(...)` / `OkHttpClient.Builder(...)`. Since the
 * manifest now permits cleartext app-wide (for the OpenAI custom endpoint), this
 * test is what keeps https the default a new client gets for free — a raw client
 * fails the build instead of silently going cleartext.
 *
 * A lightweight source scan rather than a custom detekt/lint rule because the
 * project has neither configured; swap to a ForbiddenMethodCall rule if it adopts one.
 */
class PtHttpEnforcementTest {

    @Test fun `no OkHttp client is constructed outside PtHttp`() {
        // testDebugUnitTest runs with the module dir as cwd; fall back to the
        // repo-root-relative path just in case.
        val root = listOf(
            File("src/main/java/com/playtranslate"),
            File("app/src/main/java/com/playtranslate"),
        ).firstOrNull { it.isDirectory }
            ?: error("source root not found (cwd=${File(".").absolutePath})")

        val rawConstruction = Regex("""OkHttpClient\s*\(|OkHttpClient\.Builder\s*\(""")
        val offenders = root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.name != "PtHttp.kt" }
            .filter { rawConstruction.containsMatchIn(it.readText()) }
            .map { it.relativeTo(root).path }
            .toList()

        assertTrue(
            "Construct OkHttp clients via PtHttp.clientBuilder() / customEndpointBuilder() " +
                "so https stays enforced. Raw construction found in: $offenders",
            offenders.isEmpty(),
        )
    }
}
