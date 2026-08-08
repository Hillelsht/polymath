package com.hillelsht.smart.domain

/**
 * Lazy-list keys for the Library screen.
 *
 * Category rows and content-pack rows share a single scrolling list, and a pack is named after
 * the category it fills — so their raw ids are the same six strings. Compose requires keys to
 * be unique within a list and throws `IllegalArgumentException: Key "arts" was already used`
 * on a collision, which crashed the app as soon as the remote catalog loaded.
 *
 * These live in the domain layer, free of Compose, purely so the disjointness can be asserted
 * by a test rather than maintained by memory.
 */
object LibraryKeys {

    fun category(categoryId: String): String = "category:$categoryId"

    fun pack(packId: String): String = "pack:$packId"
}
