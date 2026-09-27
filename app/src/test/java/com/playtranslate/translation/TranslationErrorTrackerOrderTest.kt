package com.playtranslate.translation

import com.playtranslate.net.NetworkConnectivity.InternetState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * [TranslationErrorTracker] against random report orders. Random passes are
 * built on one timeline of send times, then delivered shuffled, with the user
 * closing random pills in between. After every step, whatever the order:
 *  - a pill that is up says its owner's newest failure;
 *  - an owner whose newest report worked has no pill up;
 *  - no pill is up for an owner nothing was reported about.
 * At the end, an owner that failed, never worked and was never closed has
 * its pill up. The example tests in [TranslationErrorTrackerTest] pin the
 * rules one case at a time; this checks that they compose.
 *
 * The network reads as down throughout, so the tracker's split of transport
 * failures is fixed by each pass alone (the connection's unless the pass
 * reached a server) and the expected owner of every report is known without
 * the tracker's help.
 */
class TranslationErrorTrackerOrderTest {

    private class Presenter : TranslationErrorPresenter {
        val onScreen = HashMap<String, TranslationError>()
        override fun show(error: TranslationError): Boolean {
            onScreen[error.key.owner] = error
            return true
        }
        override fun update(error: TranslationError) {
            if (error.key.owner in onScreen) onScreen[error.key.owner] = error
        }
        override fun hide(keys: Collection<TranslationErrorKey>) {
            keys.forEach { onScreen.remove(it.owner) }
        }
    }

    /** One report about one owner: [error] null when it worked. */
    private data class Fact(val at: Long, val error: TranslationError?)

    private val services = listOf("a", "b", "c")
    private val serverKinds = listOf(
        BackendFailureKind.AUTH, BackendFailureKind.BILLING, BackendFailureKind.DAILY_QUOTA,
        BackendFailureKind.MONTHLY_QUOTA, BackendFailureKind.RATE_LIMITED,
        BackendFailureKind.SERVER_ERROR, BackendFailureKind.REJECTED, BackendFailureKind.BAD_RESPONSE,
    )
    private val transportKinds = listOf(BackendFailureKind.TIMEOUT, BackendFailureKind.UNREACHABLE)

    /** A few passes on one timeline: every send time unique, gaps now and
     *  then past the steady-recovery window so outages end and re-arm. */
    private fun randomPasses(rnd: Random): List<List<OnlineAttempt>> {
        var t = 1_000L
        return List(rnd.nextInt(2, 12)) {
            t += if (rnd.nextInt(8) == 0) TranslationErrorTracker.STEADY_RECOVERY_MS + 60_000L
                else rnd.nextLong(100, 30_000)
            services.shuffled(rnd).take(rnd.nextInt(1, 4)).mapIndexed { i, id ->
                val at = t + i * 10L
                when (rnd.nextInt(3)) {
                    0 -> OnlineAttempt(id, id, null, at, reachedServer = true)
                    1 -> OnlineAttempt(id, id, BackendFailure(serverKinds.random(rnd)), at, reachedServer = true)
                    else -> OnlineAttempt(id, id, BackendFailure(transportKinds.random(rnd)), at, reachedServer = false)
                }
            }
        }
    }

    /** What [pass] says about each owner, attributed as the tracker must on
     *  a network that's down. */
    private fun facts(pass: List<OnlineAttempt>): List<Pair<String, Fact>> {
        val out = mutableListOf<Pair<String, Fact>>()
        val reachedAt = pass.filter { it.reachedServer }.maxOfOrNull { it.sentAtMs }
        if (reachedAt != null) out += TranslationErrorKey.CONNECTION_OWNER to Fact(reachedAt, null)
        for (a in pass) {
            val f = a.failure
            out += when {
                f == null -> a.serviceId to Fact(a.sentAtMs, null)
                !f.kind.isTransport || reachedAt != null ->
                    a.serviceId to Fact(a.sentAtMs, TranslationError.Service(a.serviceId, a.serviceName, f))
                else -> TranslationErrorKey.CONNECTION_OWNER to Fact(a.sentAtMs, TranslationError.Connection)
            }
        }
        return out
    }

    private fun checkPills(delivered: Map<String, List<Fact>>, presenter: Presenter, where: String) {
        for ((owner, facts) in delivered) {
            val newest = facts.maxBy { it.at }
            val up = presenter.onScreen[owner]
            if (newest.error == null) {
                assertNull("$where: $owner's newest report worked, yet its pill is up", up)
            } else if (up != null) {
                assertEquals("$where: $owner's pill must say its newest failure", newest.error, up)
            }
        }
        for (owner in presenter.onScreen.keys) {
            assertTrue("$where: a pill for $owner, with nothing reported about it", owner in delivered)
        }
    }

    private fun run(passes: List<List<OnlineAttempt>>, rnd: Random, label: String) {
        val presenter = Presenter()
        val tracker = TranslationErrorTracker(now = { 0L }, internet = { InternetState.NONE })
        tracker.presenter = presenter
        val delivered = HashMap<String, MutableList<Fact>>()
        val closed = HashSet<String>()
        for ((step, pass) in passes.withIndex()) {
            tracker.onPass(pass)
            facts(pass).forEach { (owner, fact) -> delivered.getOrPut(owner) { mutableListOf() } += fact }
            checkPills(delivered, presenter, "$label step $step")
            if (presenter.onScreen.isNotEmpty() && rnd.nextInt(5) == 0) {
                val owner = presenter.onScreen.keys.random(rnd)
                presenter.onScreen.remove(owner)   // the user taps its ×
                closed += owner
                checkPills(delivered, presenter, "$label step $step after ×")
            }
        }
        for ((owner, facts) in delivered) {
            if (owner in closed || facts.any { it.error == null }) continue
            assertNotNull(
                "$label: $owner failed and never worked or was closed, so its pill is up",
                presenter.onScreen[owner],
            )
        }
    }

    @Test fun `pills follow each owner's newest report, whatever order the reports arrive in`() {
        repeat(500) { seed ->
            val rnd = Random(seed)
            val passes = randomPasses(rnd)
            run(passes, rnd, "seed $seed in order")
            repeat(3) { n -> run(passes.shuffled(rnd), rnd, "seed $seed order $n") }
        }
    }
}
