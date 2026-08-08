package com.hillelsht.smart.domain

import com.hillelsht.smart.domain.model.Category
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Regression cover for the crash that took down the Library.
 *
 * Category rows and content-pack rows render in one lazy list, and a pack is named after its
 * category, so before namespacing both sides produced the key "geography" and Compose threw
 * as soon as the remote catalog arrived.
 */
class LibraryKeysTest {

    private val categoryIds = Category.entries.map { it.id }

    @Test
    fun `category and pack keys never collide for the real ids`() {
        val categoryKeys = categoryIds.map(LibraryKeys::category)
        val packKeys = categoryIds.map(LibraryKeys::pack)

        val overlap = categoryKeys.toSet() intersect packKeys.toSet()
        assertTrue(overlap.isEmpty(), "keys used by both sections: $overlap")
    }

    @Test
    fun `all keys in one library list are unique`() {
        val all = categoryIds.map(LibraryKeys::category) + categoryIds.map(LibraryKeys::pack)
        assertEquals(all.size, all.toSet().size, "duplicate key in $all")
    }

    @Test
    fun `a raw id is never used as a key on its own`() {
        categoryIds.forEach { id ->
            assertTrue(LibraryKeys.category(id) != id, "category key for $id is the bare id")
            assertTrue(LibraryKeys.pack(id) != id, "pack key for $id is the bare id")
        }
    }
}
