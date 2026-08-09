package com.hillelsht.smart.domain

import com.hillelsht.smart.domain.PlaybackWatchdog.Failure
import com.hillelsht.smart.domain.PlaybackWatchdog.Verdict
import com.hillelsht.smart.domain.PlaybackWatchdog.assess
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The Watch tab already shipped one self-healing blocklist that never fired, because it waited
 * for YouTube to report an error that YouTube does not report. These tests pin the replacement:
 * silence is itself a failure, and — the part that matters most — which failures are allowed to
 * delete a video permanently.
 */
class PlaybackWatchdogTest {

    @Test
    fun `a duration arriving means it is playing`() {
        assertEquals(Verdict.Playing, assess(elapsedMs = 400, sawSignal = true))
    }

    @Test
    fun `before the deadline with no signal we simply wait`() {
        assertEquals(Verdict.Waiting, assess(elapsedMs = 6_999, sawSignal = false))
    }

    @Test
    fun `silence past the deadline is a failure even though YouTube said nothing`() {
        // This is error 152: the player draws its own "video unavailable" screen and never
        // calls onError. Noticing it at all is the entire point of the watchdog.
        val verdict = assess(elapsedMs = PlaybackWatchdog.DEADLINE_MS, sawSignal = false)
        assertEquals(Verdict.Failed(Failure.SILENT, blocklist = false), verdict)
    }

    @Test
    fun `a signal arriving late still counts as playing`() {
        assertEquals(Verdict.Playing, assess(elapsedMs = 30_000, sawSignal = true))
    }

    @Test
    fun `only a refusal aimed at this video removes it from the shelf`() {
        val refused = assess(0, sawSignal = false, failure = Failure.VIDEO_REFUSED)
        assertEquals(Verdict.Failed(Failure.VIDEO_REFUSED, blocklist = true), refused)
    }

    @Test
    fun `an app-level rejection must never blocklist`() {
        // REQUEST_MISSING_HTTP_REFERER fires identically for every video, so blocklisting on it
        // would empty the Watch tab one card at a time and it would never refill.
        val verdict = assess(0, sawSignal = false, failure = Failure.APP_REJECTED)
        assertEquals(Failure.APP_REJECTED, (verdict as Verdict.Failed).reason)
        assertFalse(verdict.blocklist, "an app-level rejection would drain the whole shelf")
    }

    @Test
    fun `a timeout must never blocklist`() {
        // A bad connection must not permanently delete a video that is perfectly fine.
        val verdict = assess(20_000, sawSignal = false) as Verdict.Failed
        assertFalse(verdict.blocklist)
    }

    @Test
    fun `exactly one failure kind is allowed to blocklist`() {
        // Guards the rule against a future edit quietly widening it.
        val blocklisting = Failure.entries.filter {
            (assess(0, sawSignal = false, failure = it) as Verdict.Failed).blocklist
        }
        assertEquals(listOf(Failure.VIDEO_REFUSED), blocklisting)
    }

    @Test
    fun `an explicit failure beats a signal that already arrived`() {
        // Some videos start, then die. The report wins over the earlier liveness.
        val verdict = assess(3_000, sawSignal = true, failure = Failure.VIDEO_REFUSED)
        assertTrue(verdict is Verdict.Failed)
    }
}
