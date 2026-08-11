package com.hillelsht.smart.domain.play.climb

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The Climb is meant to hold someone for forty minutes, which means the balance has to hold up
 * over hundreds of answers rather than five. None of that can be checked by playing it here —
 * there is no device — so the properties that would ruin a run are asserted directly: a fight
 * that cannot end, a relic that makes you immortal, health that goes negative, a tower that
 * builds differently the second time you climb it.
 */
class ClimbTest {

    private val roster = Relics.FALLBACK
    private val science = "science"

    private fun relic(effect: RelicEffect, magnitude: Int, category: String? = null) =
        Relic("test-${effect.name}", effect.name, "", effect, magnitude, category)

    // --- the tower ---------------------------------------------------------------------------

    @Test
    fun `the same seed always builds the same tower`() {
        // A run is saved as its seed, so if the tower were not reproducible a run resumed after
        // backgrounding the app would silently become a different run.
        (1..40).forEach { floor ->
            assertEquals(ClimbMap.floor(77L, floor), ClimbMap.floor(77L, floor))
        }
    }

    @Test
    fun `different seeds build different towers`() {
        val a = (1..30).map { ClimbMap.floor(1L, it) }
        val b = (1..30).map { ClimbMap.floor(2L, it) }
        assertNotEquals(a, b)
    }

    @Test
    fun `every tenth floor is a boss, alone`() {
        listOf(10, 20, 30, 100).forEach { floor ->
            val nodes = ClimbMap.floor(5L, floor)
            assertEquals(listOf(NodeKind.BOSS), nodes.map { it.kind }, "floor $floor")
        }
    }

    @Test
    fun `the first floor just starts`() {
        // Three unfamiliar doors before a single question has been seen is a worse opening than
        // simply beginning.
        assertEquals(listOf(NodeKind.DUEL), ClimbMap.floor(5L, 1).map { it.kind })
    }

    @Test
    fun `every ordinary floor offers a real choice with at least one fight`() {
        (1..200).forEach { seed ->
            (2..9).forEach { floor ->
                val nodes = ClimbMap.floor(seed.toLong(), floor)
                assertEquals(ClimbMap.CHOICES, nodes.size, "seed $seed floor $floor")
                assertTrue(
                    nodes.any { it.kind in setOf(NodeKind.DUEL, NodeKind.ELITE) },
                    "seed $seed floor $floor is a floor you can walk past: ${nodes.map { it.kind }}",
                )
                assertTrue(nodes.count { it.kind == NodeKind.REST } <= 1, "two rests on one floor")
                assertTrue(nodes.count { it.kind == NodeKind.CACHE } <= 1, "two caches on one floor")
                assertEquals(nodes.indices.toList(), nodes.map { it.slot })
            }
        }
    }

    @Test
    fun `the tower gets harder as it goes up`() {
        // Harder means a mistake costs more, not that a fight drags on longer.
        val cost = (1..60).map { ClimbMap.enemyDamage(it, NodeKind.DUEL) }
        assertTrue(cost.last() > cost.first() * 2, "misses never got more expensive: $cost")
        cost.zipWithNext { lower, higher -> assertTrue(higher >= lower, "it got easier: $cost") }
        assertTrue(ClimbMap.enemyHp(9, NodeKind.BOSS) > ClimbMap.enemyHp(9, NodeKind.ELITE))
        assertTrue(ClimbMap.enemyHp(9, NodeKind.ELITE) > ClimbMap.enemyHp(9, NodeKind.DUEL))
    }

    @Test
    fun `no single hit can take a third of a full bar`() {
        // One unlucky question must never end an otherwise good run with nothing the player
        // could have done, which is what an uncapped scaling hit eventually does.
        val full = ClimbRules.STARTING_HP
        listOf(1, 30, 100, 500).forEach { floor ->
            NodeKind.entries.forEach { kind ->
                val hit = ClimbMap.enemyDamage(floor, kind)
                assertTrue(hit <= full / 2, "floor $floor $kind hits for $hit of $full")
            }
        }
    }

    @Test
    fun `fights stay the same length however high the tower goes`() {
        // The flaw this pins: health that rises with the floor while damage is capped means
        // fights that get longer forever. A floor-200 elite once worked out at ~490 answers.
        listOf(1, 50, 200, 1_000).forEach { floor ->
            NodeKind.entries.filter { it !in setOf(NodeKind.REST, NodeKind.CACHE) }.forEach { kind ->
                assertTrue(
                    ClimbMap.questionsToClear(floor, kind) in 4..13,
                    "floor $floor $kind is ${ClimbMap.questionsToClear(floor, kind)} questions long",
                )
            }
        }
    }

    // --- fights end --------------------------------------------------------------------------

    @Test
    fun `a fight always ends, at any height and however slowly you answer`() {
        // The property that guarantees it: damage is floored at 1, so enemy health strictly
        // falls on every correct answer. Without it a high floor plus a slow answer could round
        // to zero damage and the run would be stuck forever with no way out but dying.
        listOf(1, 10, 25, 60, 200).forEach { floor ->
            NodeKind.entries.filter { it !in setOf(NodeKind.REST, NodeKind.CACHE) }.forEach { kind ->
                val run = ClimbRules.choose(
                    ClimbRun(seed = 1L, floor = floor),
                    ClimbNode(kind, 0),
                    roster,
                )
                var current = run
                var answers = 0
                while (current.phase == ClimbPhase.FIGHTING && answers < 100) {
                    current = ClimbRules.answer(current, true, 60_000L, science, roster)
                    answers++
                }
                assertNotEquals(
                    ClimbPhase.FIGHTING, current.phase,
                    "floor $floor $kind never ended after $answers correct answers",
                )
            }
        }
    }

    @Test
    fun `an ordinary fight is a handful of questions, not a chore`() {
        // Four to twelve at any height. Fights that outgrow that stop being fights and become
        // typing, which is exactly how a long game turns into a boring one.
        listOf(1, 5, 15, 30, 50).forEach { floor ->
            var run = ClimbRules.choose(
                ClimbRun(seed = 3L, floor = floor),
                ClimbNode(NodeKind.DUEL, 0),
                roster,
            )
            var answers = 0
            while (run.phase == ClimbPhase.FIGHTING && answers < 100) {
                run = ClimbRules.answer(run, true, 3_000L, science, roster)
                answers++
            }
            assertTrue(answers in 3..12, "floor $floor took $answers questions")
        }
    }

    @Test
    fun `damage is never zero, whatever the relics`() {
        val everything = RelicEffect.entries.mapIndexed { i, effect -> relic(effect, i + 1) }
        val run = ClimbRun(seed = 1L, relicIds = everything.map { it.id })
        listOf(0L, 3_000L, 15_000L, 999_999L).forEach { elapsed ->
            assertTrue(ClimbRules.damage(run, elapsed, science, everything) >= 1)
        }
    }

    // --- health -------------------------------------------------------------------------------

    @Test
    fun `health never goes negative and never exceeds its maximum`() {
        var run = ClimbRules.start(seed = 9L)
        val random = Random(9)
        var steps = 0
        while (!run.over && steps < 4_000) {
            run = when (run.phase) {
                ClimbPhase.CHOOSING ->
                    ClimbRules.choose(run, ClimbMap.floor(run.seed, run.floor).random(random), roster)

                ClimbPhase.LOOTING -> ClimbRules.take(run, run.offered.random(random), roster)
                ClimbPhase.FIGHTING -> ClimbRules.answer(
                    run,
                    random.nextBoolean(),
                    random.nextLong(0, 20_000),
                    science,
                    roster,
                )

                ClimbPhase.DEAD -> run
            }
            assertTrue(run.hp in 0..run.maxHp, "hp ${run.hp} of ${run.maxHp} at step $steps")
            assertTrue(run.enemyHp >= 0, "enemy hp ${run.enemyHp} at step $steps")
            assertTrue(run.shield >= 0, "shield ${run.shield} at step $steps")
            steps++
        }
        assertTrue(run.over, "a run of random play never ended in $steps steps")
    }

    @Test
    fun `answering everything wrong kills you, shield or not`() {
        // The other half of "a fight always ends": a run that cannot be lost has no stakes.
        listOf(null, relic(RelicEffect.SHIELD, 3), relic(RelicEffect.EXTRA_HEART, 200)).forEach { held ->
            val loadout = listOfNotNull(held)
            var run = ClimbRules.choose(
                ClimbRules.start(seed = 2L, starting = held),
                ClimbNode(NodeKind.DUEL, 0),
                loadout,
            )
            var answers = 0
            while (!run.over && answers < 500) {
                run = ClimbRules.answer(run, false, 5_000L, science, loadout)
                answers++
            }
            assertTrue(run.over, "with ${held?.name ?: "nothing"} the run never ended")
            assertEquals(0, run.hp)
        }
    }

    @Test
    fun `a shield absorbs misses and then runs out`() {
        val shield = relic(RelicEffect.SHIELD, 2)
        var run = ClimbRules.choose(
            ClimbRules.start(seed = 1L, starting = shield),
            ClimbNode(NodeKind.DUEL, 0),
            listOf(shield),
        )
        val full = run.hp
        repeat(2) { run = ClimbRules.answer(run, false, 1_000L, science, listOf(shield)) }
        assertEquals(full, run.hp, "the shield should have taken both")
        assertEquals(0, run.shield)

        run = ClimbRules.answer(run, false, 1_000L, science, listOf(shield))
        assertTrue(run.hp < full, "the third miss should have hurt")
    }

    @Test
    fun `a rest mends without overfilling`() {
        val hurt = ClimbRules.start(seed = 1L).copy(hp = 5)
        val rested = ClimbRules.choose(hurt, ClimbNode(NodeKind.REST, 0), roster)
        assertTrue(rested.hp > 5)
        assertTrue(rested.hp <= rested.maxHp)
        assertEquals(2, rested.floor, "a rest should still move you up a floor")

        val healthy = ClimbRules.start(seed = 1L)
        assertEquals(healthy.maxHp, ClimbRules.choose(healthy, ClimbNode(NodeKind.REST, 0), roster).hp)
    }

    // --- relics -------------------------------------------------------------------------------

    @Test
    fun `a mastery relic only fires on its own category`() {
        val mastery = relic(RelicEffect.CATEGORY_MASTERY, 100, category = "geography")
        val run = ClimbRun(seed = 1L, relicIds = listOf(mastery.id))
        val plain = ClimbRun(seed = 1L)
        assertTrue(
            ClimbRules.damage(run, 3_000L, "geography", listOf(mastery)) >
                ClimbRules.damage(plain, 3_000L, "geography", listOf(mastery)),
        )
        assertEquals(
            ClimbRules.damage(plain, 3_000L, "history", listOf(mastery)),
            ClimbRules.damage(run, 3_000L, "history", listOf(mastery)),
        )
    }

    @Test
    fun `picking up a relic changes the run immediately`() {
        // Finding more health and not getting it until the next run reads as a bug, not a rule.
        val heart = relic(RelicEffect.EXTRA_HEART, 25)
        val looting = ClimbRun(seed = 1L, phase = ClimbPhase.LOOTING, offered = listOf(heart.id))
        val after = ClimbRules.take(looting, heart.id, listOf(heart))
        assertEquals(ClimbRules.STARTING_HP + 25, after.maxHp)
        assertEquals(ClimbRules.STARTING_HP + 25, after.hp)
        assertTrue(heart.id in after.relicIds)
    }

    @Test
    fun `a relic that was not offered cannot be taken`() {
        val heart = relic(RelicEffect.EXTRA_HEART, 25)
        val looting = ClimbRun(seed = 1L, phase = ClimbPhase.LOOTING, offered = listOf("something-else"))
        assertEquals(looting, ClimbRules.take(looting, heart.id, listOf(heart)))
    }

    @Test
    fun `a cache never offers a relic you already hold`() {
        val run = ClimbRun(seed = 4L, floor = 3, relicIds = roster.dropLast(1).map { it.id })
        val looting = ClimbRules.choose(run, ClimbNode(NodeKind.CACHE, 0), roster)
        assertTrue(looting.offered.none { it in run.relicIds })
    }

    @Test
    fun `a cache with nothing left to give steps aside`() {
        val run = ClimbRun(seed = 4L, floor = 3, relicIds = roster.map { it.id })
        val after = ClimbRules.choose(run, ClimbNode(NodeKind.CACHE, 0), roster)
        assertEquals(ClimbPhase.CHOOSING, after.phase)
        assertEquals(4, after.floor)
    }

    @Test
    fun `a mender heals on the streak it advertises`() {
        val mender = relic(RelicEffect.COMBO_HEAL, 3)
        var run = ClimbRules.choose(
            ClimbRun(seed = 1L, floor = 30, hp = 20, maxHp = 60, relicIds = listOf(mender.id)),
            ClimbNode(NodeKind.BOSS, 0),
            listOf(mender),
        )
        repeat(2) { run = ClimbRules.answer(run, true, 2_000L, science, listOf(mender)) }
        assertEquals(20, run.hp, "it should not have fired yet")
        run = ClimbRules.answer(run, true, 2_000L, science, listOf(mender))
        assertEquals(20 + ClimbRules.COMBO_HEAL_AMOUNT, run.hp)
    }

    @Test
    fun `narrowing the choice narrows the choice`() {
        val gambler = relic(RelicEffect.NARROW_CHOICE, 60)
        val run = ClimbRun(seed = 1L, relicIds = listOf(gambler.id))
        assertEquals(ClimbRules.NARROWED_OPTIONS, ClimbRules.optionCount(run, listOf(gambler)))
        assertEquals(ClimbRules.DEFAULT_OPTIONS, ClimbRules.optionCount(ClimbRun(seed = 1L), listOf(gambler)))
    }

    // --- the state machine holds -------------------------------------------------------------

    @Test
    fun `nothing happens to a run that is over`() {
        val dead = ClimbRun(seed = 1L, hp = 0, phase = ClimbPhase.DEAD)
        assertEquals(dead, ClimbRules.answer(dead, true, 1_000L, science, roster))
        assertEquals(dead, ClimbRules.choose(dead, ClimbNode(NodeKind.DUEL, 0), roster))
        assertEquals(dead, ClimbRules.take(dead, "shield", roster))
    }

    @Test
    fun `answering outside a fight is ignored`() {
        val choosing = ClimbRun(seed = 1L)
        assertEquals(choosing, ClimbRules.answer(choosing, true, 1_000L, science, roster))
    }

    @Test
    fun `clearing a floor moves you up and pays out`() {
        var run = ClimbRules.choose(ClimbRun(seed = 1L, floor = 5), ClimbNode(NodeKind.ELITE, 0), roster)
        while (run.phase == ClimbPhase.FIGHTING) {
            run = ClimbRules.answer(run, true, 0L, science, roster)
        }
        assertEquals(6, run.floor)
        assertEquals(ClimbPhase.CHOOSING, run.phase)
        assertTrue(run.insight > 0, "an elite paid nothing")
        assertEquals(0, run.enemyHp)
    }

    @Test
    fun `the score only ever goes up`() {
        var run = ClimbRules.choose(ClimbRun(seed = 1L), ClimbNode(NodeKind.DUEL, 0), roster)
        var last = run.score
        val random = Random(11)
        repeat(60) {
            if (run.phase == ClimbPhase.FIGHTING) {
                run = ClimbRules.answer(run, random.nextBoolean(), random.nextLong(0, 9_000), science, roster)
                assertTrue(run.score >= last, "the score went backwards")
                last = run.score
            }
        }
    }

    // --- the published roster ------------------------------------------------------------------

    @Test
    fun `a relic naming an effect this build has never heard of is dropped`() {
        // The roster updates from the repo without an app release, so an older install will meet
        // a relic from a newer roster. Ignoring it leaves a working game; refusing to parse
        // leaves no game at all.
        val entries = listOf(
            Relics.RelicEntry("good", "Good", "", "SHIELD", 2),
            Relics.RelicEntry("future", "From A Later Version", "", "TIME_TRAVEL", 3),
        )
        assertEquals(listOf("good"), Relics.resolve(entries).map { it.id })
    }

    @Test
    fun `a malformed relic is dropped rather than half-built`() {
        val entries = listOf(
            Relics.RelicEntry("", "No Id", "", "SHIELD", 2),
            Relics.RelicEntry("blank", "", "", "SHIELD", 2),
            Relics.RelicEntry("zero", "Zero", "", "SHIELD", 0),
            Relics.RelicEntry("negative", "Negative", "", "EXTRA_HEART", -5),
        )
        assertTrue(Relics.resolve(entries).isEmpty())
    }

    @Test
    fun `the fallback roster is playable on its own`() {
        // A first run with no signal has to be a real game, not an empty cache.
        assertTrue(Relics.FALLBACK.size >= ClimbRules.CACHE_OFFER)
        assertEquals(Relics.FALLBACK.size, Relics.FALLBACK.map { it.id }.distinct().size)
        assertTrue(Relics.FALLBACK.all { it.name.isNotBlank() && it.description.isNotBlank() })
        assertFalse(Relics.FALLBACK.any { it.magnitude <= 0 })
    }
}
