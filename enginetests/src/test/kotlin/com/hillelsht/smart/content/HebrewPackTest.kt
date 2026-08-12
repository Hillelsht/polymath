package com.hillelsht.smart.content

import com.hillelsht.smart.data.seed.ContentParser
import com.hillelsht.smart.domain.model.Language
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Runs the app's real parser over the Hebrew proof-of-concept pack, mirroring [RussianPackTest].
 *
 * `packs/he/geography.json` is the first hand-translated Hebrew pack, published under the
 * `packs/<language tag>/` convention [com.hillelsht.smart.data.remote.PackService] fetches from.
 * The invariant that matters most here is the one Russian's launch got wrong the first time:
 * a translated pack has to be bundled where [com.hillelsht.smart.data.local.ContentSeeder]
 * actually looks for it, not just committed to the repo.
 */
class HebrewPackTest {

    private val hebrewFile = File("../packs/he/geography.json")
    private val englishFile = File("../packs/geography.json")

    @Test
    fun `the Hebrew geography pack parses with its own pack id and language`() {
        val pack = ContentParser.parsePack(hebrewFile.readText(), hebrewFile.name)
        assertEquals("geography-he", pack.packId)
        assertEquals(Language.HEBREW, pack.language)
        assertEquals("geography", pack.category.id)
        assertTrue(pack.facts.isNotEmpty())
        pack.facts.forEach { fact ->
            assertEquals(Language.HEBREW, fact.language)
            assertEquals("geography-he", fact.packId)
        }
    }

    @Test
    fun `no Hebrew fact id collides with the English pack it translates`() {
        val hebrew = ContentParser.parsePack(hebrewFile.readText(), hebrewFile.name).facts
        val english = ContentParser.parsePack(englishFile.readText(), englishFile.name).facts

        val overlap = hebrew.map { it.id }.toSet() intersect english.map { it.id }.toSet()
        assertTrue(overlap.isEmpty(), "Hebrew fact ids collide with English ones: $overlap")
    }

    @Test
    fun `the Hebrew pack is bundled where the seeder actually looks for it`() {
        // The failure this exists for: Russian's pack was published under packs/ru/ and shipped
        // nowhere, so a Russian speaker got an empty Read tab and an empty daily plan while the
        // facts sat in the repository. ContentSeeder reads assets/packs/<tag>/, so the copy has
        // to be there — and has to be the same content as the published one.
        val bundled = File("../app/src/main/assets/packs/he/geography.json")
        assertTrue(bundled.exists(), "packs/he/geography.json is not bundled into assets")

        val fromAssets = ContentParser.parsePack(bundled.readText(), bundled.name)
        val fromRepo = ContentParser.parsePack(hebrewFile.readText(), hebrewFile.name)
        assertEquals(fromRepo.facts.map { it.id }, fromAssets.facts.map { it.id })
        assertEquals(Language.HEBREW, fromAssets.language)
    }

    @Test
    fun `every Hebrew fact id is suffixed for its language, per the documented convention`() {
        val hebrew = ContentParser.parsePack(hebrewFile.readText(), hebrewFile.name).facts
        hebrew.forEach { fact ->
            assertTrue(fact.id.endsWith("-he"), "${fact.id} is not suffixed -he")
        }
    }
}
