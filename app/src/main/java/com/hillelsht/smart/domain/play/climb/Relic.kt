package com.hillelsht.smart.domain.play.climb

/**
 * What a relic actually does.
 *
 * The **effects are code and the roster is content**. Adding "a relic that doubles History
 * damage" needs no app release — it is a line in `packs/play/climb.json` naming an effect that
 * already exists here. Inventing a genuinely new *kind* of effect needs code, which is right:
 * an effect is a rule, and rules belong somewhere they can be tested.
 */
enum class RelicEffect {
    /** Answers in one category hit harder. */
    CATEGORY_MASTERY,

    /** The fact's picture is shown before you answer. Drawing only; no effect on the maths. */
    REVEAL_IMAGE,

    /** Fewer options to choose between, and more damage for the smaller risk. */
    NARROW_CHOICE,

    /** Absorbs the first few wrong answers of a run outright. */
    SHIELD,

    /** More maximum health, from the start of the run. */
    EXTRA_HEART,

    /** Reaching a streak of this length heals you. */
    COMBO_HEAL,
}

/**
 * One relic. [magnitude] is read per effect: a percentage for [RelicEffect.CATEGORY_MASTERY] and
 * [RelicEffect.NARROW_CHOICE], a count of hits for [RelicEffect.SHIELD], hit points for
 * [RelicEffect.EXTRA_HEART], a streak length for [RelicEffect.COMBO_HEAL].
 */
data class Relic(
    val id: String,
    val name: String,
    val description: String,
    val effect: RelicEffect,
    val magnitude: Int,
    /** Only meaningful for [RelicEffect.CATEGORY_MASTERY]. */
    val categoryId: String? = null,
)

object Relics {

    /**
     * Resolves a published roster into relics this build understands.
     *
     * An entry naming an effect this version has never heard of is **dropped**, not guessed at
     * and not fatal. The roster is served from the repo and updates without an app release, so
     * an older install will inevitably meet a relic from a newer roster; silently ignoring it
     * leaves that player a working game, while failing the parse would leave them no game at all.
     */
    fun resolve(entries: List<RelicEntry>): List<Relic> = entries.mapNotNull { entry ->
        val effect = RelicEffect.entries.firstOrNull { it.name == entry.effect } ?: return@mapNotNull null
        if (entry.id.isBlank() || entry.name.isBlank()) return@mapNotNull null
        if (entry.magnitude <= 0) return@mapNotNull null
        Relic(
            id = entry.id,
            name = entry.name,
            description = entry.description,
            effect = effect,
            magnitude = entry.magnitude,
            categoryId = entry.categoryId?.takeIf { it.isNotBlank() },
        )
    }

    /**
     * A published roster entry, before it is known to be usable. Deliberately stringly-typed on
     * [effect] — this is what arrives over the network, and it has to survive naming something
     * that does not exist.
     */
    data class RelicEntry(
        val id: String,
        val name: String,
        val description: String,
        val effect: String,
        val magnitude: Int,
        val categoryId: String? = null,
    )

    /**
     * Enough to play with when the network has never been reached. The published roster replaces
     * this; a first-run player with no signal still gets a real game rather than an empty cache.
     */
    val FALLBACK: List<Relic> = listOf(
        Relic("shield", "Lion's Hide", "Shrugs off your first two wrong answers.", RelicEffect.SHIELD, 2),
        Relic("heart", "Second Wind", "Start every run with 20 more health.", RelicEffect.EXTRA_HEART, 20),
        Relic("gambler", "Gambler's Eye", "Two choices instead of four, and you hit 60% harder.", RelicEffect.NARROW_CHOICE, 60),
        Relic("mender", "Steady Nerve", "A streak of four mends 8 health.", RelicEffect.COMBO_HEAL, 4),
    )
}
