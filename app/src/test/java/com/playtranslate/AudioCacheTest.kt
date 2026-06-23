package com.playtranslate

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.playtranslate.audio.Attribution
import com.playtranslate.audio.AudioCache
import com.playtranslate.language.SourceLangId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AudioCacheTest {

    private val ctx = ApplicationProvider.getApplicationContext<Context>()

    @Test fun put_then_get_returns_clip_and_attribution() = runBlocking {
        val cache = AudioCache(ctx)
        val attr = Attribution("Jane", "CC BY", "Wikimedia Commons", "https://x")
        val f = cache.putClip("wikimedia_commons", "Foo.ogg", byteArrayOf(1, 2, 3), attr)
        assertTrue(f.exists())
        assertNotNull(cache.getClip("wikimedia_commons", "Foo.ogg"))
        assertEquals("Jane", cache.readAttribution("wikimedia_commons", "Foo.ogg")?.author)
    }

    @Test fun negative_marker_absent_then_fresh() {
        val cache = AudioCache(ctx)
        assertFalse(cache.isNegativeFresh("wikimedia_commons", SourceLangId.JA, "x"))
        cache.markNegative("wikimedia_commons", SourceLangId.JA, "x")
        assertTrue(cache.isNegativeFresh("wikimedia_commons", SourceLangId.JA, "x"))
    }

    @Test fun lru_evicts_oldest_over_budget() = runBlocking {
        val cache = AudioCache(ctx, maxBytes = 10)
        val a = cache.putClip("s", "a.ogg", ByteArray(6), null)
        a.setLastModified(1_000L) // mark as oldest, independent of fs mtime granularity
        cache.putClip("s", "b.ogg", ByteArray(6), null) // total 12 > 10 → evict oldest (a)
        assertNull(cache.getClip("s", "a.ogg"))
        assertNotNull(cache.getClip("s", "b.ogg"))
    }
}
