package com.hillelsht.smart.content

import com.hillelsht.smart.data.seed.ContentParser
import com.hillelsht.smart.domain.model.Language
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Runs the app's real parser over the Russian proof-of-concept pack, the same way
 * [GeneratedLibraryTest] checks the English library CI publishes.
 *
 * `packs/ru/geography.json` is the first hand-translated pack, published under the
 * `packs/<language tag>/` convention [com.hillelsht.smart.data.remote.PackService] fetches from.
 * The one invariant a translated pack cannot afford to get wrong is id collision with its
 * English counterpart — that would make the seeder overwrite one language's fact, and the
 * learner's review history, with the other's — so that is what this asserts.
 */
class RussianPackTest {

    private val russianFile = File("../packs/ru/geography.json")
    private val englishFile = File("../packs/geography.json")

    @Test
    fun `the Russian geography pack parses with its own pack id and language`() {
        val pack = ContentParser.parsePack(russianFile.readText(), russianFile.name)
        assertEquals("geography-ru", pack.packId)
        assertEquals(Language.RUSSIAN, pack.language)
        assertEquals("geography", pack.category.id)
        assertTrue(pack.facts.isNotEmpty())
        pack.facts.forEach { fact ->
            assertEquals(Language.RUSSIAN, fact.language)
            assertEquals("geography-ru", fact.packId)
        }
    }

    @Test
    fun `no Russian fact id collides with the English pack it translates`() {
        val russian = ContentParser.parsePack(russianFile.readText(), russianFile.name).facts
        val english = ContentParser.parsePack(englishFile.readText(), englishFile.name).facts

        val overlap = russian.map { it.id }.toSet() intersect english.map { it.id }.toSet()
        assertTrue(overlap.isEmpty(), "Russian fact ids collide with English ones: $overlap")
    }

    @Test
    fun `the Russian pack is bundled where the seeder actually looks for it`() {
        // The failure this exists for: the pack was published under packs/ru/ and shipped
        // nowhere, so a Russian speaker got an empty Read tab and an empty daily plan while
        // the facts sat in the repository. ContentSeeder reads assets/packs/<tag>/, so the
        // copy has to be there — and has to be the same content as the published one.
        val bundled = File("../app/src/main/assets/packs/ru/geography.json")
        assertTrue(bundled.exists(), "packs/ru/geography.json is not bundled into assets")

        val fromAssets = ContentParser.parsePack(bundled.readText(), bundled.name)
        val fromRepo = ContentParser.parsePack(russianFile.readText(), russianFile.name)
        assertEquals(fromRepo.facts.map { it.id }, fromAssets.facts.map { it.id })
        assertEquals(Language.RUSSIAN, fromAssets.language)
    }

    @Test
    fun `every Russian fact id is suffixed for its language, per the documented convention`() {
        val russian = ContentParser.parsePack(russianFile.readText(), russianFile.name).facts
        russian.forEach { fact ->
            assertTrue(fact.id.endsWith("-ru"), "${fact.id} is not suffixed -ru")
        }
    }
}
