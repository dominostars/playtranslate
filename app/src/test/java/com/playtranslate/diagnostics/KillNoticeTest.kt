package com.playtranslate.diagnostics

import android.app.ApplicationExitInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Which deaths the kill notice speaks about, and in which words. */
@RunWith(RobolectricTestRunner::class)
class KillNoticeTest {

    @Test fun `low memory gets the memory wording`() {
        assertEquals(KillNotice.Kind.MEMORY, KillNotice.kindFor(ApplicationExitInfo.REASON_LOW_MEMORY, null))
    }

    @Test fun `the system's own kills get the generic wording`() {
        for (reason in listOf(
            ApplicationExitInfo.REASON_SIGNALED, ApplicationExitInfo.REASON_FREEZER,
            ApplicationExitInfo.REASON_DEPENDENCY_DIED, ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE,
            ApplicationExitInfo.REASON_OTHER,
        )) assertEquals("reason $reason", KillNotice.Kind.OTHER, KillNotice.kindFor(reason, "too many cached"))
    }

    @Test fun `a force stop gets the hedged wording, since the record cannot say who did it`() {
        assertEquals(
            KillNotice.Kind.STOPPED,
            KillNotice.kindFor(ApplicationExitInfo.REASON_USER_REQUESTED, "stop com.playtranslate due to from pid 3051"),
        )
    }

    @Test fun `the user's own stops are not the phone's doing`() {
        assertNull(KillNotice.kindFor(ApplicationExitInfo.REASON_USER_REQUESTED, "remove task"))
        assertNull(KillNotice.kindFor(
            ApplicationExitInfo.REASON_USER_REQUESTED, "fully stop com.playtranslate/0 by user request",
        ))
        assertNull(KillNotice.kindFor(
            ApplicationExitInfo.REASON_USER_REQUESTED, "stop com.playtranslate due to installPackageLI",
        ))
        assertNull(KillNotice.kindFor(ApplicationExitInfo.REASON_USER_REQUESTED, null))
    }

    @Test fun `every other reason says nothing`() {
        for (reason in listOf(
            ApplicationExitInfo.REASON_UNKNOWN, ApplicationExitInfo.REASON_EXIT_SELF,
            ApplicationExitInfo.REASON_CRASH, ApplicationExitInfo.REASON_CRASH_NATIVE,
            ApplicationExitInfo.REASON_ANR, ApplicationExitInfo.REASON_INITIALIZATION_FAILURE,
            ApplicationExitInfo.REASON_PERMISSION_CHANGE, ApplicationExitInfo.REASON_USER_STOPPED,
            ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE, ApplicationExitInfo.REASON_PACKAGE_UPDATED,
            99,
        )) assertNull("reason $reason", KillNotice.kindFor(reason, "crash"))
    }
}
