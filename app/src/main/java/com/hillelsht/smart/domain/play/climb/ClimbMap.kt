package com.hillelsht.smart.domain.play.climb

import kotlin.random.Random

enum class NodeKind {
    /** An ordinary fight. */
    DUEL,

    /** A harder fight that pays better. */
    ELITE,

    /** Choose one relic of three. */
    CACHE,

    /** Mend some health instead of spending it. */
    REST,

    /** Every tenth floor. No choice, and no way past it but through. */
    BOSS,
}

data class ClimbNode(val kind: NodeKind, val slot: Int)

/**
 * The tower.
 *
 * A floor is a **pure function of the run's seed and the floor number**, which is what makes a
 * run reproducible: the same seed always builds the same tower, so a run can be replayed from a
 * saved seed rather than a saved map, a bug can be reproduced from the seed in a crash report,
 * and the whole structure is testable without a device.
 *
 * Each floor offers a small choice rather than a branching path graph. Three doors is enough to
 * make the run yours — spend health now for a relic, or take the rest you need — without asking
 * someone to read a map on a phone screen mid-session.
 */
object ClimbMap {

    /** Every tenth floor is a boss. */
    const val BOSS_EVERY = 10

    /** Doors per floor. */
    const val CHOICES = 3

    /**
     * The doors on a floor. Boss floors and the first floor offer exactly one, for opposite
     * reasons: a boss is not optional, and a first floor asking someone to weigh three unfamiliar
     * options before they have seen a single question is a worse opening than simply starting.
     */
    fun floor(seed: Long, floor: Int): List<ClimbNode> {
        require(floor >= 1) { "floors start at 1, got $floor" }
        if (floor % BOSS_EVERY == 0) return listOf(ClimbNode(NodeKind.BOSS, 0))
        if (floor == 1) return listOf(ClimbNode(NodeKind.DUEL, 0))

        val random = Random(seed * 31 + floor)
        val kinds = mutableListOf<NodeKind>()
        // At least one fight, always: a floor you can walk past without answering anything is a
        // floor that teaches nothing and costs nothing.
        kinds += if (floor > 3 && random.nextInt(4) == 0) NodeKind.ELITE else NodeKind.DUEL

        while (kinds.size < CHOICES) {
            val candidate = when (random.nextInt(10)) {
                0, 1, 2, 3 -> NodeKind.DUEL
                4, 5 -> NodeKind.ELITE
                6, 7 -> NodeKind.CACHE
                else -> NodeKind.REST
            }
            // One rest and one cache at most, or a floor stops being a choice and becomes a gift.
            val alreadyHas = kinds.count { it == candidate }
            if (candidate in setOf(NodeKind.REST, NodeKind.CACHE) && alreadyHas >= 1) continue
            if (candidate == NodeKind.ELITE && kinds.count { it == NodeKind.ELITE } >= 2) continue
            kinds += candidate
        }

        return kinds.shuffled(random).mapIndexed { slot, kind -> ClimbNode(kind, slot) }
    }

    /** What a typical mid-fight answer deals, used to size fights. See [ClimbRules.damage]. */
    private const val TYPICAL_DAMAGE = 30

    /** Questions a fight is meant to last, before the kind's surcharge. */
    private const val BASE_QUESTIONS = 4
    private const val MAX_EXTRA_QUESTIONS = 3

    /**
     * How many questions this fight is meant to take.
     *
     * **Fights are sized in questions, not in hit points, and that is the whole trick.** The
     * obvious design — health that climbs with the floor — was tried first and is quietly broken
     * in a game with no ending: player damage is capped by the streak multiplier, so linearly
     * rising health means fights that get longer forever. A floor-200 elite worked out at
     * roughly 490 correct answers. Sizing the fight in questions and deriving the health from
     * that keeps every fight four to ten questions at any height.
     *
     * The tower still gets harder — through [enemyDamage], so a mistake at height costs a third
     * of your health instead of a tenth. Rising pressure per mistake is difficulty; rising
     * length is just typing.
     */
    fun questionsToClear(floor: Int, kind: NodeKind): Int {
        val base = BASE_QUESTIONS + (floor / 12).coerceAtMost(MAX_EXTRA_QUESTIONS)
        return when (kind) {
            NodeKind.ELITE -> base + 3
            NodeKind.BOSS -> base + 6
            else -> base
        }
    }

    /** How much health the thing on this floor has, derived from how long the fight should be. */
    fun enemyHp(floor: Int, kind: NodeKind): Int = questionsToClear(floor, kind) * TYPICAL_DAMAGE

    /**
     * What a wrong answer costs on this floor.
     *
     * This is where the tower gets frightening. It rises with height and is capped, because a
     * hit that takes more than about a third of a full bar turns one unlucky question into a
     * dead run with nothing the player could have done about it.
     */
    fun enemyDamage(floor: Int, kind: NodeKind): Int {
        val base = (8 + floor / 3).coerceAtMost(20)
        return when (kind) {
            NodeKind.ELITE -> base + 3
            NodeKind.BOSS -> base + 6
            else -> base
        }
    }

    /** What clearing this floor is worth towards unlocking relics for later runs. */
    fun insight(floor: Int, kind: NodeKind): Int = when (kind) {
        NodeKind.ELITE -> 3 + floor / 5
        NodeKind.BOSS -> 8 + floor / 3
        NodeKind.DUEL -> 1 + floor / 8
        else -> 0
    }
}
