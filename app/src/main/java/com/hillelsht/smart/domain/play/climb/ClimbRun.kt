package com.hillelsht.smart.domain.play.climb

import com.hillelsht.smart.domain.play.Scoring
import kotlin.random.Random

enum class ClimbPhase {
    /** Standing on a floor, picking a door. */
    CHOOSING,

    /** Mid-fight. Questions are being asked. */
    FIGHTING,

    /** Standing over a cache, picking one relic of three. */
    LOOTING,

    /** The run is over. Nothing changes it after this. */
    DEAD,
}

/**
 * Everything about a run in progress.
 *
 * A plain immutable value with no behaviour of its own — every transition lives in [ClimbRules]
 * as a pure function — so a run can be written to the database, read back and continued, and so
 * the whole game is exercised in tests without a screen.
 */
data class ClimbRun(
    val seed: Long,
    val floor: Int = 1,
    val phase: ClimbPhase = ClimbPhase.CHOOSING,
    val hp: Int = ClimbRules.STARTING_HP,
    val maxHp: Int = ClimbRules.STARTING_HP,
    val shield: Int = 0,
    /** Relics held **in this run**, which is not the same as relics unlocked across runs. */
    val relicIds: List<String> = emptyList(),
    val insight: Int = 0,
    val score: Int = 0,
    val combo: Int = 0,
    val answered: Int = 0,
    val missed: Int = 0,
    val node: ClimbNode? = null,
    val enemyHp: Int = 0,
    val enemyMaxHp: Int = 0,
    /** Relic ids on offer at a cache. Empty outside [ClimbPhase.LOOTING]. */
    val offered: List<String> = emptyList(),
) {
    val over: Boolean get() = phase == ClimbPhase.DEAD

    init {
        require(hp in 0..maxHp) { "hp $hp is outside 0..$maxHp" }
        require(enemyHp >= 0) { "enemy hp $enemyHp is negative" }
    }
}

/**
 * The rules of the climb.
 *
 * Every function here is `(state, event) -> state` with no side effects and no clock of its own,
 * which is the whole reason the game is winnable-by-inspection: the balance questions that matter
 * — can a fight go on forever, can a relic combination make you immortal, can health go negative
 * — are answered by tests rather than by playing until something looks wrong.
 */
object ClimbRules {

    const val STARTING_HP = 60

    /** Damage floor for a correct answer, before speed and streak. */
    const val BASE_DAMAGE = 10

    /** The most that answering quickly adds on top of [BASE_DAMAGE]. */
    const val SPEED_DAMAGE = 10

    /** A rest mends this share of your maximum health. */
    const val REST_HEAL_PERCENT = 30

    /** What [RelicEffect.COMBO_HEAL] mends when the streak comes round. */
    const val COMBO_HEAL_AMOUNT = 8

    const val DEFAULT_OPTIONS = 4
    const val NARROWED_OPTIONS = 2

    /** Relics offered at a cache. */
    const val CACHE_OFFER = 3

    /** Begins a run, applying whatever the starting relic changes before the first question. */
    fun start(seed: Long, starting: Relic? = null): ClimbRun {
        val extraHp = if (starting?.effect == RelicEffect.EXTRA_HEART) starting.magnitude else 0
        val shield = if (starting?.effect == RelicEffect.SHIELD) starting.magnitude else 0
        val maxHp = STARTING_HP + extraHp
        return ClimbRun(
            seed = seed,
            maxHp = maxHp,
            hp = maxHp,
            shield = shield,
            relicIds = listOfNotNull(starting?.id),
        )
    }

    /** Walks through a door. */
    fun choose(run: ClimbRun, node: ClimbNode, roster: List<Relic>): ClimbRun {
        if (run.phase != ClimbPhase.CHOOSING) return run
        return when (node.kind) {
            NodeKind.REST -> {
                val healed = (run.hp + run.maxHp * REST_HEAL_PERCENT / 100).coerceAtMost(run.maxHp)
                advance(run.copy(hp = healed))
            }

            NodeKind.CACHE -> {
                val available = roster.map { it.id }.filterNot { it in run.relicIds }
                if (available.isEmpty()) {
                    advance(run)
                } else {
                    run.copy(
                        phase = ClimbPhase.LOOTING,
                        node = node,
                        offered = available.shuffled(Random(run.seed * 131 + run.floor))
                            .take(CACHE_OFFER),
                    )
                }
            }

            else -> {
                val hp = ClimbMap.enemyHp(run.floor, node.kind)
                run.copy(phase = ClimbPhase.FIGHTING, node = node, enemyHp = hp, enemyMaxHp = hp)
            }
        }
    }

    /** Takes a relic from a cache and moves on. An id that was not offered is ignored. */
    fun take(run: ClimbRun, relicId: String, roster: List<Relic>): ClimbRun {
        if (run.phase != ClimbPhase.LOOTING || relicId !in run.offered) return run
        val relic = roster.firstOrNull { it.id == relicId } ?: return advance(run)
        val withRelic = run.copy(relicIds = run.relicIds + relicId)
        // A relic that changes the shape of the run applies the moment it is picked up, not on
        // the next run — finding more health and not getting it now would read as a bug.
        val applied = when (relic.effect) {
            RelicEffect.EXTRA_HEART -> withRelic.copy(
                maxHp = withRelic.maxHp + relic.magnitude,
                hp = withRelic.hp + relic.magnitude,
            )

            RelicEffect.SHIELD -> withRelic.copy(shield = withRelic.shield + relic.magnitude)
            else -> withRelic
        }
        return advance(applied)
    }

    /**
     * One answered question.
     *
     * @param elapsedMs how long the answer took, which drives both damage and score.
     * @param categoryId the fact's category, so a mastery relic knows whether to fire.
     */
    fun answer(
        run: ClimbRun,
        correct: Boolean,
        elapsedMs: Long,
        categoryId: String,
        roster: List<Relic>,
    ): ClimbRun {
        if (run.phase != ClimbPhase.FIGHTING) return run
        val node = run.node ?: return run

        val scored = run.copy(
            score = run.score + Scoring.points(correct, elapsedMs, run.combo),
            answered = run.answered + 1,
        )

        val after = if (correct) {
            val dealt = damage(run, elapsedMs, categoryId, roster)
            val combo = Scoring.nextCombo(run.combo, true)
            val mended = comboHeal(scored, combo, roster)
            mended.copy(combo = combo, enemyHp = (run.enemyHp - dealt).coerceAtLeast(0))
        } else {
            val blocked = run.shield > 0
            val hurt = if (blocked) 0 else ClimbMap.enemyDamage(run.floor, node.kind)
            scored.copy(
                combo = 0,
                missed = run.missed + 1,
                shield = if (blocked) run.shield - 1 else run.shield,
                hp = (run.hp - hurt).coerceAtLeast(0),
            )
        }

        return when {
            after.hp <= 0 -> after.copy(phase = ClimbPhase.DEAD, combo = 0)
            after.enemyHp <= 0 -> advance(
                after.copy(insight = after.insight + ClimbMap.insight(after.floor, node.kind)),
            )

            else -> after
        }
    }

    /**
     * Damage for one correct answer.
     *
     * Never below 1, which is what guarantees a fight ends: whatever the streak, the relics or
     * how long the answer took, the enemy's health strictly decreases on every correct answer,
     * so no fight can be drawn out forever.
     */
    fun damage(run: ClimbRun, elapsedMs: Long, categoryId: String, roster: List<Relic>): Int {
        var dealt = (BASE_DAMAGE + SPEED_DAMAGE * Scoring.speedFraction(elapsedMs)) *
            Scoring.multiplier(run.combo)
        held(run, roster).forEach { relic ->
            when (relic.effect) {
                RelicEffect.CATEGORY_MASTERY ->
                    if (relic.categoryId == categoryId) dealt *= 1f + relic.magnitude / 100f

                RelicEffect.NARROW_CHOICE -> dealt *= 1f + relic.magnitude / 100f
                else -> Unit
            }
        }
        return dealt.toInt().coerceAtLeast(1)
    }

    /** How many options this run's questions offer. */
    fun optionCount(run: ClimbRun, roster: List<Relic>): Int =
        if (held(run, roster).any { it.effect == RelicEffect.NARROW_CHOICE }) {
            NARROWED_OPTIONS
        } else {
            DEFAULT_OPTIONS
        }

    /** Whether this run shows the picture before the answer. */
    fun revealsImage(run: ClimbRun, roster: List<Relic>): Boolean =
        held(run, roster).any { it.effect == RelicEffect.REVEAL_IMAGE }

    fun held(run: ClimbRun, roster: List<Relic>): List<Relic> =
        roster.filter { it.id in run.relicIds }

    private fun comboHeal(run: ClimbRun, combo: Int, roster: List<Relic>): ClimbRun {
        val mender = held(run, roster).firstOrNull { it.effect == RelicEffect.COMBO_HEAL }
            ?: return run
        if (combo == 0 || combo % mender.magnitude != 0) return run
        return run.copy(hp = (run.hp + COMBO_HEAL_AMOUNT).coerceAtMost(run.maxHp))
    }

    private fun advance(run: ClimbRun): ClimbRun = run.copy(
        floor = run.floor + 1,
        phase = ClimbPhase.CHOOSING,
        node = null,
        enemyHp = 0,
        enemyMaxHp = 0,
        offered = emptyList(),
    )
}
