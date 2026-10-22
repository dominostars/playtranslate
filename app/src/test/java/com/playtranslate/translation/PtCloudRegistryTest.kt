package com.playtranslate.translation

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.playtranslate.cloud.PtCloudBackend
import com.playtranslate.cloud.PtCloudClient
import com.playtranslate.cloud.PtCloudService
import com.playtranslate.cloud.PtCloudTestSupport
import com.playtranslate.language.offlineFallbackCandidates
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Collections

/**
 * PlayTranslate Cloud in the waterfall: registered by the Application and
 * pinned first among the online services by the one order writer
 * ([OnlineServiceMutations.applyOnlineOrder]) whatever the priorities; a
 * cooling cloud skipped; the cache's preferred identity "ptcloud" while it
 * is usable; and never a candidate of the offline fallback that translates
 * dictionary definitions, even usable and first.
 */
@RunWith(RobolectricTestRunner::class)
class PtCloudRegistryTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()

    /** On the wall clock: the registry judges cooldowns against it. */
    private lateinit var cooldown: CooldownState
    private val seen = Collections.synchronizedList(mutableListOf<Request>())

    @Before fun setUp() {
        PtCloudTestSupport.sharedPrefs(ctx).edit().clear().commit()
        cooldown = CooldownState(null, PtCloudService.ID)
        PtCloudService.installForTest(cooldown, PtCloudTestSupport.config(ctx))
        PtCloudTestSupport.prefs(ctx).ptCloudEnabled = true
    }

    @After fun tearDown() {
        // Before the uninstall: a registry walk reaches the cooldown.
        TranslationBackendRegistry.close()
        PtCloudService.installForTest(null)
        PtCloudTestSupport.sharedPrefs(ctx).edit().clear().commit()
    }

    /** The cloud's backend on the service's config and cooldown (its
     *  defaults), answering through a canned server so that no call ever
     *  reaches the network. */
    private fun cloud(): PtCloudBackend {
        val client = PtCloudClient(PtCloudService.config, clientFactory = {
            PtCloudTestSupport.cannedClient(
                200, PtCloudTestSupport.part2("ok", """[{"id":0,"translation":"from-cloud"}]"""), seen = seen,
            )
        })
        return PtCloudBackend(client = { client })
    }

    /** An offline tier that opts into the definitions fallback, as Bergamot
     *  and ML Kit do. */
    private val definitionsFallback = object : TranslationBackend {
        override val id = "bergamot-fake"
        override val displayName = "Bergamot"
        override val priority = 28
        override val requiresInternet = false
        override val isDegradedFallback = false
        override val usableAsOfflineFallback = true
        override fun isUsable(source: String, target: String) = true
        override suspend fun translate(text: String, source: String, target: String) = "offline"
    }

    @Test fun `the application registers the cloud first among the online services`() {
        // The registry as PlayTranslateApplication.onCreate left it.
        assertTrue(TranslationBackendRegistry.byId(PtCloudService.ID) is PtCloudBackend)
        val ids = TranslationBackendRegistry.orderedBackends().map { it.id }
        assertEquals(listOf(PtCloudService.ID) + OnlineServiceStore.all().map { it.id }, ids.take(1 + OnlineServiceStore.all().size))
    }

    @Test fun `applyOnlineOrder puts the cloud first and the store's instances after it, whatever the priorities`() {
        OnlineServiceStore.init(ctx, PtCloudTestSupport.prefs(ctx))
        assertEquals(listOf("lingva"), OnlineServiceStore.all().map { it.id })
        TranslationBackendRegistry.init(
            listOf(FakeOfflineBackend(id = "offline", priority = 5), FakeOnlineBackend(id = "lingva", priority = 1), cloud()),
        )

        OnlineServiceMutations.applyOnlineOrder()

        assertEquals(listOf(PtCloudService.ID, "lingva", "offline"), TranslationBackendRegistry.orderedBackends().map { it.id })
    }

    @Test fun `a cooling cloud is skipped and the next service translates`() {
        val next = FakeOnlineBackend(id = "next", priority = 20, response = "from-next")
        TranslationBackendRegistry.init(listOf(cloud(), next))

        assertEquals("from-cloud", runBlocking { TranslationBackendRegistry.translate("一", "ja", "en") }.text)
        assertEquals(1, seen.size)

        cooldown.recordLadderFailure(CooldownLadder.RateLimit, "Server error", CooldownCause.SERVER_ERROR)
        val result = runBlocking { TranslationBackendRegistry.translate("一", "ja", "en") }
        assertEquals("next", result.backend.id)
        val batch = runBlocking { TranslationBackendRegistry.translateBatch(listOf("一", "二"), "ja", "en") }
        assertTrue(batch.all { it.backend.id == "next" })
        assertEquals("no call while it cools down", 1, seen.size)
    }

    @Test fun `the preferred online id is the cloud's while it is usable and not cooling`() {
        TranslationBackendRegistry.init(listOf(cloud(), FakeOnlineBackend(id = "next", priority = 20)))
        assertEquals(PtCloudService.ID, TranslationBackendRegistry.preferredOnlineId("ja", "en"))

        cooldown.recordLadderFailure(CooldownLadder.RateLimit, "Bad key", CooldownCause.AUTH)
        assertEquals("next", TranslationBackendRegistry.preferredOnlineId("ja", "en"))

        cooldown.resetCooldown()
        PtCloudTestSupport.prefs(ctx).ptCloudEnabled = false
        assertEquals("next", TranslationBackendRegistry.preferredOnlineId("ja", "en"))
    }

    @Test fun `the definitions fallback never takes the cloud, even usable and first`() {
        OnlineServiceStore.init(ctx, PtCloudTestSupport.prefs(ctx))
        TranslationBackendRegistry.init(listOf(definitionsFallback, cloud()))
        OnlineServiceMutations.applyOnlineOrder()
        val cloud = TranslationBackendRegistry.orderedBackends().first()
        assertEquals(PtCloudService.ID, cloud.id)
        assertTrue(cloud.isUsable("ja", "en"))

        val candidates = offlineFallbackCandidates("ja", "en").map { it.id }

        assertEquals(listOf("bergamot-fake"), candidates)
        assertFalse(PtCloudService.ID in candidates)
    }
}
