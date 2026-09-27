package com.playtranslate.translation

import com.playtranslate.net.NetworkConnectivity.InternetState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules of [TranslationErrorTracker], one cell at a time: once per
 * outage, a steady recovery of [TranslationErrorTracker.STEADY_RECOVERY_MS]
 * before an error may show again, pills down on recovery, shown only when
 * a pill actually appeared, a pill up saying its owner's newest failure,
 * transport failures split between the connection and the service (every
 * failure shows, the first one included), forget, the reset at the end of
 * a capture session, and every report judged by when its request went out,
 * not when it arrived.
 */
class TranslationErrorTrackerTest {

    private class Presenter(var canShow: Boolean = true) : TranslationErrorPresenter {
        val shown = mutableListOf<TranslationError>()
        val hidden = mutableListOf<Set<TranslationErrorKey>>()
        /** What each owner's pill on screen says. */
        val onScreen = LinkedHashMap<String, TranslationError>()
        override fun show(error: TranslationError): Boolean {
            if (!canShow) return false
            shown += error
            onScreen[error.key.owner] = error
            return true
        }
        override fun update(error: TranslationError) {
            if (error.key.owner in onScreen) onScreen[error.key.owner] = error
        }
        override fun hide(keys: Collection<TranslationErrorKey>) {
            hidden += keys.toSet()
            keys.forEach { onScreen.remove(it.owner) }
        }
        /** The user taps the pill's ×. */
        fun close(owner: String) {
            onScreen.remove(owner)
        }
    }

    private var clock = 1_000_000L
    private var net = InternetState.VALIDATED
    private val presenter = Presenter()
    private val tracker = TranslationErrorTracker(now = { clock }, internet = { net })
        .also { it.presenter = presenter }

    /** [id]'s request, sent at [at] (now by default), failed with [kind];
     *  its server answered unless [kind] is a transport failure. */
    private fun fail(id: String, kind: BackendFailureKind, name: String = id, at: Long = clock) =
        OnlineAttempt(id, name, BackendFailure(kind), at, reachedServer = !kind.isTransport)

    private fun ok(id: String, at: Long = clock) = OnlineAttempt(id, id, null, at, reachedServer = true)

    private fun advance(ms: Long) { clock += ms }

    private val minute = 60_000L

    // ── once per outage ────────────────────────────────────────────────

    @Test fun `a service's failure shows once, and again only after a steady recovery`() {
        tracker.onPass(listOf(fail("claude", BackendFailureKind.BILLING, "Claude")))
        assertEquals(1, presenter.shown.size)
        assertEquals(
            TranslationError.Service("claude", "Claude", BackendFailure(BackendFailureKind.BILLING)),
            presenter.shown.single(),
        )

        advance(minute)
        tracker.onPass(listOf(fail("claude", BackendFailureKind.BILLING)))
        assertEquals("same outage: no second pill", 1, presenter.shown.size)
    }

    @Test fun `a different kind from the same service is a different error`() {
        tracker.onPass(listOf(fail("gemini", BackendFailureKind.RATE_LIMITED)))
        tracker.onPass(listOf(fail("gemini", BackendFailureKind.DAILY_QUOTA)))
        assertEquals(
            listOf(BackendFailureKind.RATE_LIMITED, BackendFailureKind.DAILY_QUOTA),
            presenter.shown.map { (it as TranslationError.Service).failure.kind },
        )
    }

    @Test fun `a service that flaps between working and failing never shows its pill again`() {
        tracker.onPass(listOf(fail("gemini", BackendFailureKind.RATE_LIMITED)))
        repeat(10) {
            advance(minute)
            tracker.onPass(listOf(ok("gemini")))
            advance(minute)
            tracker.onPass(listOf(fail("gemini", BackendFailureKind.RATE_LIMITED)))
        }
        assertEquals(1, presenter.shown.size)
    }

    @Test fun `five minutes working after the fix re-arms the error`() {
        tracker.onPass(listOf(fail("claude", BackendFailureKind.BILLING)))
        advance(minute)
        tracker.onPass(listOf(ok("claude")))           // recovery begins
        advance(TranslationErrorTracker.STEADY_RECOVERY_MS)
        tracker.onPass(listOf(fail("claude", BackendFailureKind.BILLING)))
        assertEquals("a new outage after a steady recovery shows again", 2, presenter.shown.size)
    }

    @Test fun `just short of five minutes is still the same outage`() {
        tracker.onPass(listOf(fail("claude", BackendFailureKind.BILLING)))
        advance(minute)
        tracker.onPass(listOf(ok("claude")))
        advance(TranslationErrorTracker.STEADY_RECOVERY_MS - 1)
        tracker.onPass(listOf(fail("claude", BackendFailureKind.BILLING)))
        assertEquals(1, presenter.shown.size)
    }

    @Test fun `the steady clock starts at the FIRST success after the failure`() {
        tracker.onPass(listOf(fail("claude", BackendFailureKind.BILLING)))
        advance(minute)
        tracker.onPass(listOf(ok("claude")))
        advance(4 * minute)
        tracker.onPass(listOf(ok("claude")))           // must not restart the clock
        advance(minute)
        tracker.onPass(listOf(fail("claude", BackendFailureKind.BILLING)))
        assertEquals(2, presenter.shown.size)
    }

    // ── shown means on screen ──────────────────────────────────────────

    @Test fun `an error no display could take shows at its next failure that can`() {
        presenter.canShow = false
        tracker.onPass(listOf(fail("claude", BackendFailureKind.BILLING)))
        assertTrue(presenter.shown.isEmpty())

        presenter.canShow = true
        tracker.onPass(listOf(fail("claude", BackendFailureKind.BILLING)))
        assertEquals(1, presenter.shown.size)
    }

    // ── hide on recovery ───────────────────────────────────────────────

    @Test fun `the service working again takes its pill down, and only its pill`() {
        tracker.onPass(listOf(fail("claude", BackendFailureKind.BILLING), fail("deepl", BackendFailureKind.MONTHLY_QUOTA)))
        tracker.onPass(listOf(ok("claude")))
        assertEquals(listOf(setOf(TranslationErrorKey("claude", "BILLING"))), presenter.hidden)
    }

    @Test fun `a success with nothing shown hides nothing`() {
        tracker.onPass(listOf(ok("claude")))
        assertTrue(presenter.hidden.isEmpty())
    }

    // ── transport failures: connection or service ──────────────────────

    @Test fun `no network is a connection error at once`() {
        net = InternetState.NONE
        tracker.onPass(listOf(fail("gemini", BackendFailureKind.UNREACHABLE)))
        assertEquals(listOf<TranslationError>(TranslationError.Connection), presenter.shown)
    }

    @Test fun `several services failing together are one connection pill`() {
        net = InternetState.NONE
        tracker.onPass(listOf(
            fail("gemini", BackendFailureKind.UNREACHABLE),
            fail("lingva", BackendFailureKind.UNREACHABLE),
        ))
        assertEquals(listOf<TranslationError>(TranslationError.Connection), presenter.shown)
    }

    @Test fun `a network with no internet is a connection error at once`() {
        net = InternetState.UNVALIDATED
        tracker.onPass(listOf(fail("gemini", BackendFailureKind.UNREACHABLE)))
        assertEquals(listOf<TranslationError>(TranslationError.Connection), presenter.shown)
    }

    @Test fun `a single timeout shows at once, and its pill comes down at the next success`() {
        // No forgiving the first one (2026-09-27): a hiccup's pill
        // comes down at the next success anyway.
        tracker.onPass(listOf(fail("gemini", BackendFailureKind.TIMEOUT)))
        assertEquals(
            listOf(TranslationError.Service("gemini", "gemini", BackendFailure(BackendFailureKind.TIMEOUT))),
            presenter.shown,
        )
        advance(1_000)
        tracker.onPass(listOf(ok("gemini")))
        assertEquals(listOf(setOf(TranslationErrorKey("gemini", "TIMEOUT"))), presenter.hidden)
    }

    @Test fun `on an unvalidated network, another service answering makes it the service's problem`() {
        net = InternetState.UNVALIDATED
        tracker.onPass(listOf(fail("gemini", BackendFailureKind.UNREACHABLE), ok("lingva")))
        assertEquals(
            listOf(TranslationError.Service("gemini", "gemini", BackendFailure(BackendFailureKind.UNREACHABLE))),
            presenter.shown,
        )
    }

    @Test fun `on a validated network a timeout is the service's, not the connection's`() {
        tracker.onPass(listOf(fail("gemini", BackendFailureKind.TIMEOUT)))
        assertEquals(
            listOf(TranslationError.Service("gemini", "gemini", BackendFailure(BackendFailureKind.TIMEOUT))),
            presenter.shown,
        )
    }

    @Test fun `any online success takes the connection pill down`() {
        net = InternetState.NONE
        tracker.onPass(listOf(fail("gemini", BackendFailureKind.UNREACHABLE)))
        net = InternetState.VALIDATED
        tracker.onPass(listOf(ok("lingva")))
        assertEquals(listOf(setOf(TranslationError.Connection.key)), presenter.hidden)
    }

    @Test fun `an error reply from a server takes the connection pill down`() {
        // Codex adversarial 2026-09-26: back online, the service answers 429;
        // only a success used to clear the connection pill, so it stayed up
        // for as long as the service kept refusing.
        net = InternetState.NONE
        tracker.onPass(listOf(fail("gemini", BackendFailureKind.UNREACHABLE)))
        net = InternetState.VALIDATED
        tracker.onPass(listOf(fail("gemini", BackendFailureKind.RATE_LIMITED)))

        assertEquals(listOf(setOf(TranslationError.Connection.key)), presenter.hidden)
        assertEquals(
            listOf(
                TranslationError.Connection,
                TranslationError.Service("gemini", "gemini", BackendFailure(BackendFailureKind.RATE_LIMITED)),
            ),
            presenter.shown,
        )
    }

    @Test fun `a server's error reply starts the connection's steady clock`() {
        net = InternetState.NONE
        tracker.onPass(listOf(fail("gemini", BackendFailureKind.UNREACHABLE)))
        net = InternetState.VALIDATED
        tracker.onPass(listOf(fail("claude", BackendFailureKind.BILLING)))   // answers, no success
        advance(TranslationErrorTracker.STEADY_RECOVERY_MS)
        net = InternetState.NONE
        tracker.onPass(listOf(fail("gemini", BackendFailureKind.UNREACHABLE)))

        assertEquals(TranslationError.Connection, presenter.shown.last())
        assertEquals(2, presenter.shown.count { it == TranslationError.Connection })
    }

    @Test fun `a pass that reached a server never blames the connection, even if the network reads as gone`() {
        // The network is read after the pass; it dropped in between. The
        // 429 is the fresher proof that the connection worked.
        net = InternetState.NONE
        tracker.onPass(listOf(
            fail("gemini", BackendFailureKind.RATE_LIMITED),
            fail("lingva", BackendFailureKind.UNREACHABLE),
        ))
        assertEquals(
            "lingva's failure is its own, not the connection's",
            listOf(
                TranslationError.Service("gemini", "gemini", BackendFailure(BackendFailureKind.RATE_LIMITED)),
                TranslationError.Service("lingva", "lingva", BackendFailure(BackendFailureKind.UNREACHABLE)),
            ),
            presenter.shown,
        )
    }

    @Test fun `the connection error follows the same steady-recovery rule`() {
        net = InternetState.NONE
        tracker.onPass(listOf(fail("gemini", BackendFailureKind.UNREACHABLE)))
        net = InternetState.VALIDATED
        tracker.onPass(listOf(ok("gemini")))
        advance(minute)
        net = InternetState.NONE
        tracker.onPass(listOf(fail("gemini", BackendFailureKind.UNREACHABLE)))
        assertEquals("flapping wifi: one pill", 1, presenter.shown.size)

        net = InternetState.VALIDATED
        tracker.onPass(listOf(ok("gemini")))
        advance(TranslationErrorTracker.STEADY_RECOVERY_MS)
        net = InternetState.NONE
        tracker.onPass(listOf(fail("gemini", BackendFailureKind.UNREACHABLE)))
        assertEquals(2, presenter.shown.size)
    }

    // ── deleted or switched-off services ───────────────────────────────

    @Test fun `a pass reported after its service was deleted or switched off raises nothing`() {
        // Production passes "exists and is enabled" (OnlineServiceStore);
        // either way the service won't be asked again, so no success of it
        // could take a pill down.
        val deleted = setOf("claude")
        val t = TranslationErrorTracker(now = { clock }, internet = { net }, isActive = { it !in deleted })
        t.presenter = presenter
        t.onPass(listOf(fail("claude", BackendFailureKind.BILLING), fail("deepl", BackendFailureKind.MONTHLY_QUOTA)))
        assertEquals(
            listOf(TranslationError.Service("deepl", "deepl", BackendFailure(BackendFailureKind.MONTHLY_QUOTA))),
            presenter.shown,
        )
    }

    // ── end of a capture session ───────────────────────────────────────

    @Test fun `a new capture session shows an error that never recovered, once`() {
        // Codex adversarial 2026-09-26: a service that never works again
        // (out of credits) used to stay silent for the rest of the process.
        tracker.onPass(listOf(fail("claude", BackendFailureKind.BILLING)))
        tracker.onPass(listOf(fail("claude", BackendFailureKind.BILLING)))
        assertEquals(1, presenter.shown.size)

        tracker.reset()
        advance(1_000)
        tracker.onPass(listOf(fail("claude", BackendFailureKind.BILLING)))
        tracker.onPass(listOf(fail("claude", BackendFailureKind.BILLING)))
        assertEquals("once per session", 2, presenter.shown.size)
    }

    // ── forget ─────────────────────────────────────────────────────────

    @Test fun `forget takes the pill down and lets the same error show again`() {
        tracker.onPass(listOf(fail("claude", BackendFailureKind.AUTH)))
        advance(1_000)
        tracker.forget("claude")
        assertEquals(listOf(setOf(TranslationErrorKey("claude", "AUTH"))), presenter.hidden)

        advance(1_000)
        tracker.onPass(listOf(fail("claude", BackendFailureKind.AUTH)))
        assertEquals(2, presenter.shown.size)
    }

    @Test fun `forget leaves other services and the connection alone`() {
        net = InternetState.NONE
        tracker.onPass(listOf(fail("gemini", BackendFailureKind.UNREACHABLE)))
        net = InternetState.VALIDATED
        tracker.onPass(listOf(fail("deepl", BackendFailureKind.MONTHLY_QUOTA)))
        // (DeepL's reply already took the connection pill down: a server
        // answered. What matters here is that forget adds nothing.)
        val hiddenBefore = presenter.hidden.toList()
        tracker.forget("claude")
        assertEquals("forgetting another service hides nothing", hiddenBefore, presenter.hidden)
        advance(1_000)
        tracker.onPass(listOf(fail("deepl", BackendFailureKind.MONTHLY_QUOTA)))
        assertEquals("DeepL's error is still shown, not re-armed", 2, presenter.shown.size)
    }

    // ── reports of requests older than the change they'd speak for ─────

    @Test fun `a request sent before forget reports nothing about its service`() {
        // Codex adversarial 2026-09-27: the user fixes the key while a
        // request with the old one is in flight; its AUTH failure arrived
        // after the forget and put the pill straight back up.
        val inFlight = fail("claude", BackendFailureKind.AUTH)
        advance(1_000)
        tracker.forget("claude")
        advance(1_000)
        tracker.onPass(listOf(inFlight))
        assertTrue(presenter.shown.isEmpty())

        tracker.onPass(listOf(fail("claude", BackendFailureKind.AUTH)))
        assertEquals("a request with the new config still reports", 1, presenter.shown.size)
    }

    @Test fun `a request sent before forget still proves the connection worked`() {
        net = InternetState.NONE
        tracker.onPass(listOf(fail("gemini", BackendFailureKind.UNREACHABLE)))
        advance(1_000)
        val inFlight = fail("gemini", BackendFailureKind.RATE_LIMITED)
        advance(1_000)
        tracker.forget("gemini")
        net = InternetState.VALIDATED
        tracker.onPass(listOf(inFlight))

        assertEquals(listOf(setOf(TranslationError.Connection.key)), presenter.hidden)
        assertEquals("nothing about gemini itself", listOf<TranslationError>(TranslationError.Connection), presenter.shown)
    }

    @Test fun `a request sent before the capture session ended reports nothing`() {
        val inFlight = fail("claude", BackendFailureKind.BILLING)
        advance(1_000)
        tracker.reset()
        advance(1_000)
        tracker.onPass(listOf(inFlight))
        assertTrue(presenter.shown.isEmpty())
    }

    // ── out-of-order reports: judged by when their request went out ────

    @Test fun `an older failure reported after a newer success puts no pill up`() {
        // Codex native 2026-09-27: overlapping passes; the slower, older one
        // failed and reported last, reopening a pill for a service that
        // works.
        val older = fail("claude", BackendFailureKind.BILLING)
        advance(1_000)
        tracker.onPass(listOf(ok("claude")))
        tracker.onPass(listOf(older))
        assertTrue(presenter.shown.isEmpty())
    }

    @Test fun `an older success reported after a newer failure leaves the pill up and starts no clock`() {
        val older = ok("claude")
        advance(1_000)
        tracker.onPass(listOf(fail("claude", BackendFailureKind.BILLING)))
        tracker.onPass(listOf(older))
        assertTrue("the pill stays up", presenter.hidden.isEmpty())

        advance(TranslationErrorTracker.STEADY_RECOVERY_MS)
        tracker.onPass(listOf(fail("claude", BackendFailureKind.BILLING)))
        assertEquals("no run of health began, so the outage goes on", 1, presenter.shown.size)
    }

    @Test fun `a late failure from inside a run of health restarts the steady clock`() {
        tracker.onPass(listOf(fail("claude", BackendFailureKind.BILLING)))
        advance(minute)
        tracker.onPass(listOf(ok("claude")))                   // the run begins
        advance(4 * minute)
        val late = fail("claude", BackendFailureKind.BILLING)  // 4 minutes in
        advance(50_000)
        tracker.onPass(listOf(ok("claude")))
        tracker.onPass(listOf(late))                            // reported after that success
        assertEquals("older than the success: no pill", 1, presenter.shown.size)

        advance(40_000)  // 5m30s after the run began, 40s after the newest success
        tracker.onPass(listOf(fail("claude", BackendFailureKind.BILLING)))
        assertEquals("the run was broken at 4 minutes: same outage", 1, presenter.shown.size)
    }

    @Test fun `a connection failure sent before a server answered puts no connection pill up`() {
        net = InternetState.NONE
        val older = fail("gemini", BackendFailureKind.UNREACHABLE)
        advance(1_000)
        tracker.onPass(listOf(ok("lingva")))
        tracker.onPass(listOf(older))
        assertTrue(presenter.shown.isEmpty())
    }

    @Test fun `a new outage after a steady recovery shows once, whichever order its reports arrive in`() {
        // Codex native 2026-09-27 read a late failure re-arming the error as
        // premature. By send time the service had worked for six minutes
        // when it failed again: that failure began a new outage. Reported
        // in order, its pill shows at once and comes down at the success
        // after it; reported after that success, it shows at the outage's
        // next failure instead. Once either way.
        fun showings(lateReport: Boolean): Int {
            var now = 0L
            val p = Presenter()
            val t = TranslationErrorTracker(now = { now }, internet = { InternetState.VALIDATED })
            t.presenter = p
            fun billing(at: Long) = fail("claude", BackendFailureKind.BILLING, at = at)
            t.onPass(listOf(billing(0)))                       // outage 1, shown
            t.onPass(listOf(ok("claude", at = minute)))        // the run of health begins
            val failure = billing(7 * minute)                  // six minutes in: outage 2
            val success = ok("claude", at = 7 * minute + 10_000)
            if (lateReport) {
                t.onPass(listOf(success))
                t.onPass(listOf(failure))
            } else {
                t.onPass(listOf(failure))
                t.onPass(listOf(success))
            }
            t.onPass(listOf(billing(7 * minute + 20_000)))     // outage 2 goes on
            t.onPass(listOf(ok("claude", at = 8 * minute)))
            t.onPass(listOf(billing(8 * minute + 10_000)))     // still outage 2: flapping
            now = 9 * minute
            return p.shown.size
        }
        assertEquals("in order", 2, showings(lateReport = false))
        assertEquals("the failure reported after the success", 2, showings(lateReport = true))
    }

    // ── pills withdrawn with their display ─────────────────────────────

    @Test fun `pills withdrawn with their display show again at their owner's next failure`() {
        tracker.onPass(listOf(fail("claude", BackendFailureKind.BILLING), fail("deepl", BackendFailureKind.MONTHLY_QUOTA)))
        presenter.close("deepl")                   // the user dismissed this one
        tracker.withdrawn(listOf("claude"))        // this one went with its display
        assertTrue("nothing to take down: they're gone", presenter.hidden.isEmpty())

        advance(1_000)
        tracker.onPass(listOf(fail("claude", BackendFailureKind.BILLING), fail("deepl", BackendFailureKind.MONTHLY_QUOTA)))
        assertEquals(
            "claude again, deepl stays dismissed",
            listOf("claude", "deepl", "claude"),
            presenter.shown.map { (it as TranslationError.Service).serviceId },
        )
    }

    @Test fun `an older failure reported after a newer one leaves the pill saying the newer`() {
        // Codex native 2026-09-27: overlapping requests failed two ways; the
        // older reported last and reworded the pill, and its kind counted as
        // shown.
        val older = fail("gemini", BackendFailureKind.RATE_LIMITED)
        advance(1_000)
        tracker.onPass(listOf(fail("gemini", BackendFailureKind.DAILY_QUOTA)))
        tracker.onPass(listOf(older))
        assertEquals(
            TranslationError.Service("gemini", "gemini", BackendFailure(BackendFailureKind.DAILY_QUOTA)),
            presenter.onScreen["gemini"],
        )

        advance(1_000)
        tracker.onPass(listOf(fail("gemini", BackendFailureKind.RATE_LIMITED)))
        assertEquals("the older kind never counted as shown", 2, presenter.shown.size)
    }

    @Test fun `a transport failure reported after a request sent later reached a server isn't blamed on its service`() {
        // Codex native 2026-09-27: requests that hung through an outage
        // reported once the network was back; read VALIDATED on arrival,
        // they blamed the service, though another had since reached its
        // server.
        val hung = listOf(fail("gemini", BackendFailureKind.TIMEOUT), fail("gemini", BackendFailureKind.TIMEOUT, at = clock + 1_000))
        advance(2_000)
        tracker.onPass(listOf(ok("lingva")))
        hung.forEach { tracker.onPass(listOf(it)) }
        assertTrue(presenter.shown.isEmpty())
    }

    @Test fun `a transport failure reported after a request sent later failed on the connection belongs to the connection`() {
        val hung = listOf(fail("gemini", BackendFailureKind.TIMEOUT), fail("gemini", BackendFailureKind.TIMEOUT, at = clock + 500))
        advance(1_000)
        net = InternetState.NONE
        tracker.onPass(listOf(fail("lingva", BackendFailureKind.UNREACHABLE)))   // the outage, seen
        net = InternetState.VALIDATED                                           // and over
        hung.forEach { tracker.onPass(listOf(it)) }
        assertEquals(listOf<TranslationError>(TranslationError.Connection), presenter.shown)
    }

    // ── the words of a pill that is up ─────────────────────────────────

    @Test fun `a pill still up says its owner's newest failure, even one already seen`() {
        // Codex adversarial 2026-09-27: A, then B, then A again left the
        // pill saying B.
        tracker.onPass(listOf(fail("gemini", BackendFailureKind.RATE_LIMITED)))
        tracker.onPass(listOf(fail("gemini", BackendFailureKind.DAILY_QUOTA)))
        tracker.onPass(listOf(fail("gemini", BackendFailureKind.RATE_LIMITED)))

        assertEquals(
            TranslationError.Service("gemini", "gemini", BackendFailure(BackendFailureKind.RATE_LIMITED)),
            presenter.onScreen["gemini"],
        )
        assertEquals("no third showing", 2, presenter.shown.size)
    }

    @Test fun `a closed pill stays closed when a seen failure repeats`() {
        tracker.onPass(listOf(fail("gemini", BackendFailureKind.RATE_LIMITED)))
        presenter.close("gemini")
        tracker.onPass(listOf(fail("gemini", BackendFailureKind.RATE_LIMITED)))
        assertTrue(presenter.onScreen.isEmpty())
        assertEquals(1, presenter.shown.size)
    }

    @Test fun `a new failure no display can take still rewords the pill that is up`() {
        tracker.onPass(listOf(fail("gemini", BackendFailureKind.RATE_LIMITED)))
        presenter.canShow = false
        tracker.onPass(listOf(fail("gemini", BackendFailureKind.DAILY_QUOTA)))
        assertEquals(
            TranslationError.Service("gemini", "gemini", BackendFailure(BackendFailureKind.DAILY_QUOTA)),
            presenter.onScreen["gemini"],
        )
        assertEquals("not a showing of its own", 1, presenter.shown.size)
    }

    // ── what a pass's evidence proves ──────────────────────────────────

    @Test fun `a service that reached its server in a pass that also timed out never blames the connection`() {
        // Codex native 2026-09-27: per-text, one text translated and another
        // timed out; the report said TIMEOUT alone, and with the network
        // reading as gone that became a connection error.
        net = InternetState.NONE
        tracker.onPass(listOf(fail("lingva", BackendFailureKind.UNREACHABLE)))   // the connection pill
        advance(1_000)
        tracker.onPass(listOf(
            OnlineAttempt("gemini", "gemini", BackendFailure(BackendFailureKind.TIMEOUT), clock, reachedServer = true),
        ))
        assertEquals(listOf(setOf(TranslationError.Connection.key)), presenter.hidden)
        assertEquals(
            "the timeout is gemini's own",
            listOf(
                TranslationError.Connection,
                TranslationError.Service("gemini", "gemini", BackendFailure(BackendFailureKind.TIMEOUT)),
            ),
            presenter.shown,
        )
    }
}
