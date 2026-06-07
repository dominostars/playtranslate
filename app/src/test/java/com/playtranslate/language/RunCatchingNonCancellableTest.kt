package com.playtranslate.language

import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

/**
 * Pins the cancellation-safe contract of [runCatchingNonCancellable]: it must
 * rethrow [CancellationException] (so a cancelled card send actually aborts
 * instead of falling through with empty overrides) while degrading other
 * failures to null. The engine/send paths that use it aren't JVM-testable, so
 * this covers the fix at the unit it lives in.
 */
class RunCatchingNonCancellableTest {

    @Test fun `rethrows CancellationException`() {
        try {
            runCatchingNonCancellable { throw CancellationException("cancelled") }
            fail("expected CancellationException to propagate")
        } catch (_: CancellationException) {
            // expected
        }
    }

    @Test fun `degrades a non-cancellation exception to null`() {
        assertNull(runCatchingNonCancellable<String> { throw RuntimeException("boom") })
    }

    @Test fun `returns the value on success`() {
        assertEquals("ok", runCatchingNonCancellable { "ok" })
    }

    @Test fun `lets Error propagate`() {
        try {
            runCatchingNonCancellable { throw OutOfMemoryError("oom") }
            fail("expected Error to propagate")
        } catch (_: OutOfMemoryError) {
            // expected — only Exception degrades, Error escapes
        }
    }
}
