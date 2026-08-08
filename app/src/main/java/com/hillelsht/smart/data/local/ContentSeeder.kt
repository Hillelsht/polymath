package com.hillelsht.smart.data.local

import android.content.Context
import android.util.Log
import com.hillelsht.smart.data.seed.ContentParser
import com.hillelsht.smart.domain.model.Category
import com.hillelsht.smart.domain.model.Fact

/**
 * Loads the bundled curriculum into the database.
 *
 * Facts are re-seeded whenever the bundled content changes, keyed by total fact count, so
 * shipping new knowledge in an update does not require a migration. Only the `facts` table is
 * touched — review history is keyed by fact id and survives untouched.
 */
class ContentSeeder(
    private val context: Context,
    private val factDao: FactDao,
) {

    suspend fun seedIfNeeded() {
        val bundled = readBundledFacts()
        if (bundled.isEmpty()) return

        if (factDao.count() == bundled.size) return

        factDao.insertAll(bundled.map { it.toEntity() })
    }

    private fun readBundledFacts(): List<Fact> = Category.entries.flatMap { category ->
        val path = "content/${category.id}.json"
        try {
            val raw = context.assets.open(path).bufferedReader().use { it.readText() }
            ContentParser.parseFile(raw, path)
        } catch (e: Exception) {
            // A single malformed file must not stop the rest of the curriculum from loading.
            Log.e(TAG, "Failed to load $path", e)
            emptyList()
        }
    }

    private companion object {
        const val TAG = "ContentSeeder"
    }
}
