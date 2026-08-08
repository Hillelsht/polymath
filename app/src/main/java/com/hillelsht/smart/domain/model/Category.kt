package com.hillelsht.smart.domain.model

/**
 * The six knowledge domains that make up the v1 curriculum.
 *
 * [accentHex] and [secondaryHex] live here rather than in the UI layer so that a category
 * carries its own identity everywhere it is rendered — rings, chips, quiz headers, hero
 * gradients — without every call site re-deciding what "Science teal" means. The UI parses
 * them into Compose colours; the domain never imports Compose.
 */
enum class Category(
    val id: String,
    val displayName: String,
    val blurb: String,
    val accentHex: String,
    val secondaryHex: String,
) {
    GEOGRAPHY(
        id = "geography",
        displayName = "Geography",
        blurb = "Countries, capitals, rivers, and the shape of the world",
        accentHex = "#00C2A8",
        secondaryHex = "#0E7C6B",
    ),
    HISTORY(
        id = "history",
        displayName = "History",
        blurb = "The events, empires, and people that set everything in motion",
        accentHex = "#F2994A",
        secondaryHex = "#B45309",
    ),
    SCIENCE(
        id = "science",
        displayName = "Science",
        blurb = "Physics, chemistry, biology, and the rules reality runs on",
        accentHex = "#4C9AFF",
        secondaryHex = "#1D4ED8",
    ),
    ARTS(
        id = "arts",
        displayName = "Arts & Literature",
        blurb = "Paintings, novels, composers, and the canon worth knowing",
        accentHex = "#E15FA6",
        secondaryHex = "#9D174D",
    ),
    SPORTS(
        id = "sports",
        displayName = "Sports & Games",
        blurb = "Records, rules, and the moments that made legends",
        accentHex = "#6EE7A8",
        secondaryHex = "#047857",
    ),
    CULTURE(
        id = "culture",
        displayName = "Pop Culture",
        blurb = "Film, music, television, and the references everyone quotes",
        accentHex = "#A78BFA",
        secondaryHex = "#5B21B6",
    ),
    ;

    companion object {
        fun fromId(id: String): Category? = entries.firstOrNull { it.id == id }
    }
}
