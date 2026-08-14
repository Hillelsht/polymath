package com.hillelsht.smart.domain.play.vaults

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GhostTest {

    private fun press(vararg spans: Pair<Buttons, Int>): List<Buttons> =
        spans.flatMap { (buttons, frames) -> List(frames) { buttons } }

    private val right = Buttons(right = true)
    private val idle = Buttons()

    /** The same button stream `Playtest.replay` builds from a plan, as a recordable list. */
    private fun framesFor(plan: Playtest.Plan, frames: Int = Playtest.MAX_FRAMES): List<Buttons> {
        val presses = plan.presses.map { it + plan.waitFrames }.toSet()
        return (0 until frames).map { frame ->
            val moving = frame >= plan.waitFrames
            Buttons(left = moving && !plan.right, right = moving && plan.right, jump = frame in presses)
        }
    }

    // --- the format ------------------------------------------------------------------------------

    @Test
    fun `a run survives the round trip exactly`() {
        val run = press(idle to 12, right to 90, Buttons(right = true, jump = true) to 3, right to 40)
        assertEquals(run, Ghost.decode(Ghost.encode(run)))
    }

    @Test
    fun `every combination of buttons survives`() {
        // Four booleans is sixteen states and all of them are reachable, so all of them are tested
        // rather than the handful a person is likely to press.
        val run = (0..15).map { Ghost.buttons(it) }
        assertEquals(run, Ghost.decode(Ghost.encode(run)))
        assertEquals((0..15).toList(), run.map { Ghost.bits(it) })
    }

    @Test
    fun `random runs survive the round trip`() {
        val random = Random(11)
        repeat(60) {
            // Built as spans rather than per-frame noise, because that is the shape a person
            // produces and the shape the encoding is built around.
            val run = (0 until random.nextInt(1, 40)).flatMap {
                List(random.nextInt(1, 200)) { Ghost.buttons(random.nextInt(16)) }
            }
            assertEquals(run, Ghost.decode(Ghost.encode(run)), "run of ${run.size} frames")
        }
    }

    @Test
    fun `a link stays short enough to paste`() {
        // The whole premise is that a run fits in a URL someone sends a friend. A minute of play
        // held down in the way a person actually plays must not approach the ~2,000 characters
        // every browser handles.
        val run = press(
            idle to 20, right to 300, Buttons(right = true, jump = true) to 4, right to 120,
            Buttons(left = true) to 45, idle to 30, right to 600, Buttons(down = true) to 8,
            right to 400, Buttons(jump = true) to 5, right to 200,
        )
        assertTrue(run.size > 1_700, "the fixture should be about half a minute of play")
        assertTrue(Ghost.encode(run).length < 100, "encoded to ${Ghost.encode(run).length} characters")
    }

    @Test
    fun `an empty run is not a run`() {
        assertEquals("", Ghost.encode(emptyList()))
        assertNull(Ghost.decode(""))
    }

    // --- refusing what it should refuse -----------------------------------------------------------

    @Test
    fun `text that is not a ghost is refused rather than half read`() {
        // This arrives from a URL, so it is the one input here a stranger controls. A ghost that
        // half-decodes would race against something its owner never played.
        listOf(
            "hello",    // no mask at all
            "1A",       // starts with a length
            "A",        // a mask with no length
            "AA",       // two masks running together
            "A0",       // a span of no frames
            "B12C",     // a trailing mask with nothing after it
            "Q5",       // a mask outside the sixteen that exist
            "A1 B2",    // whitespace, which a URL would have escaped
            "A-1",      // a character in neither alphabet
            "!!",
        ).forEach { assertNull(Ghost.decode(it), "'$it' should not decode") }

        // The other side of the same line: the smallest thing that *is* a ghost still reads.
        assertEquals(List(35) { Buttons() }, Ghost.decode("Az"))
    }

    @Test
    fun `a run too long to be an attempt is refused`() {
        // Both doors: one absurd span, and many ordinary spans that add up to the same thing.
        assertNull(Ghost.decode("B" + "zzzz"), "a span longer than the cap")
        val many = buildString { repeat(40) { append("B").append("s0") } }   // 40 x 1,008 frames
        assertNull(Ghost.decode(many), "spans that add up past the cap")
    }

    @Test
    fun `a decoded run never exceeds the cap`() {
        val long = Ghost.encode(List(Ghost.MAX_FRAMES) { right })
        val decoded = Ghost.decode(long)
        assertNotNull(decoded)
        assertEquals(Ghost.MAX_FRAMES, decoded.size)
    }

    // --- the point of it all ------------------------------------------------------------------------

    @Test
    fun `replaying a ghost reproduces the run that made it`() {
        // The claim the whole feature rests on: inputs are enough, because the engine is
        // deterministic. If this ever fails, a shared link is showing people a run nobody played.
        val room = Rooms.all.first()
        val plan = Playtest.solve(room, Tuning())
        assertNotNull(plan, "the first room should be solvable")

        val frames = framesFor(plan)
        val played = Ghost.replay(listOf(room), frames)

        val shared = Ghost.encode(frames)
        val ghost = Ghost.decode(shared)
        assertNotNull(ghost)
        val replayed = Ghost.replay(listOf(room), ghost)

        assertEquals(played.elapsedFrames, replayed.elapsedFrames)
        assertEquals(played.finished, replayed.finished)
        assertEquals(played.deaths, replayed.deaths)
        assertEquals(played.runner.x, replayed.runner.x)
        assertEquals(played.runner.y, replayed.runner.y)
        assertTrue(played.finished, "the fixture should actually clear the room")
    }

    @Test
    fun `a run that dies replays through the death identically`() {
        // Respawn timing lives in the replay rather than in whoever is driving it. A replay that
        // skipped the frames spent lying on the floor would drift out of step within one death.
        val room = Rooms.all.first()
        val frames = press(right to 600)          // straight off the edge, then keep holding
        val played = Ghost.replay(listOf(room), frames)
        val replayed = Ghost.replay(listOf(room), Ghost.decode(Ghost.encode(frames))!!)

        assertTrue(played.deaths > 0, "the fixture should actually die")
        assertEquals(played.deaths, replayed.deaths)
        assertEquals(played.elapsedFrames, replayed.elapsedFrames)
        assertEquals(played.runner.x, replayed.runner.x)
    }

    @Test
    fun `a run stops at the finish rather than running on`() {
        val room = Rooms.all.first()
        val plan = Playtest.solve(room, Tuning())!!
        val short = framesFor(plan)
        val padded = short + List(2_000) { Buttons(right = true) }
        assertEquals(
            Ghost.replay(listOf(room), short).elapsedFrames,
            Ghost.replay(listOf(room), padded).elapsedFrames,
            "frames after the exit must not keep the clock running",
        )
    }
}
