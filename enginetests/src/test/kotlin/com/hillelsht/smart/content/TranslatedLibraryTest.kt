package com.hillelsht.smart.content

import com.hillelsht.smart.data.seed.ContentParser
import com.hillelsht.smart.domain.model.Language
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The same gate `GeneratedLibraryTest` puts on the English library, applied to the translated ones.
 *
 * They are separate because the two are not the same object. The English library is at
 * `packs/library/` and has always been; a translated one is at `packs/<tag>/library/`, and its ids
 * carry a language suffix that English's must never grow. That suffix is the whole of the
 * invariant in `docs/invariants.md`: reuse an English fact id for a Russian fact and installing
 * the Russian library **overwrites the English fact and takes its review history with it** —
 * silently, on a device, with no way back, because review history is the one thing here that
 * cannot be re-downloaded.
 *
 * A language with nothing published yet is reported rather than asserted about. That is the state
 * Russian and Hebrew are in until `library.yml` has run for them, and a test that failed on it
 * would be failing on work not yet done rather than on work done wrongly.
 */
class TranslatedLibraryTest {

    private val translated = Language.entries.filter { it != Language.default }

    private fun dirFor(language: Language) = File("../packs/${language.tag}/library")

    private fun shards(dir: File): List<File> =
        dir.listFiles { f -> f.name.endsWith(".json") && f.name != "index.json" }
            ?.sortedBy { it.name }
            .orEmpty()

    /** Every fact id in a library directory, read with the parser the device uses. */
    private fun factIds(dir: File): List<String> =
        shards(dir).flatMap { ContentParser.parsePack(it.readText(), it.name).facts.map { f -> f.id } }

    @Test
    fun `every translated shard parses with the parser the device uses`() {
        translated.forEach { language ->
            val dir = dirFor(language)
            if (!dir.isDirectory) {
                println("  ${language.tag}: no library published yet")
                return@forEach
            }
            val files = shards(dir)
            assertTrue(files.isNotEmpty(), "${language.tag} has a library directory and no shards")
            files.forEach { file ->
                val pack = ContentParser.parsePack(file.readText(), file.name)
                assertTrue(pack.facts.isNotEmpty(), "${pack.packId} parsed to no facts")
                assertTrue(pack.version.isNotBlank(), "${pack.packId} has no version to re-seed by")
            }
            println("  ${language.tag}: ${files.size} shards, ${factIds(dir).size} facts")
        }
    }

    /**
     * Whether a pack id names its language, wherever in the id it says so.
     *
     * Two conventions are in use and both are correct. The hand-authored packs suffix
     * (`geography-ru`); `generate_facts.py` prefixes (`library-ru-geography-000`), so a translated
     * shard cannot collide with the English shard of the same category and number. What matters is
     * neither spelling but the property they share — the tag is a delimited segment of the id — so
     * that is what is asserted. An earlier version of this test demanded the suffix and failed the
     * generated library on its first real run, which is a test encoding a habit rather than a rule.
     */
    private fun namesLanguage(packId: String, tag: String) = "-$packId-".contains("-$tag-")

    @Test
    fun `a translated pack claims its own language, and its own ids`() {
        translated.forEach { language ->
            val dir = dirFor(language)
            if (!dir.isDirectory) return@forEach
            val suffix = "-${language.tag}"

            shards(dir).forEach { file ->
                val pack = ContentParser.parsePack(file.readText(), file.name)
                assertTrue(
                    namesLanguage(pack.packId, language.tag),
                    "${pack.packId} does not name '${language.tag}' anywhere in its id, so it " +
                        "could install over an English pack of the same name",
                )
                assertTrue(
                    Regex(""""language"\s*:\s*"${language.tag}"""").containsMatchIn(file.readText()),
                    "${pack.packId} does not declare itself as ${language.tag}",
                )
                pack.facts.forEach { fact ->
                    assertTrue(
                        fact.id.endsWith(suffix),
                        "fact '${fact.id}' in ${pack.packId} has no '$suffix' suffix — installing " +
                            "this pack would overwrite the English fact of that id and destroy " +
                            "its review history",
                    )
                }
            }
        }
    }

    @Test
    fun `no translated fact can overwrite an English one`() {
        val english = File("../packs/library")
        if (!english.isDirectory) return
        val englishIds = factIds(english).toSet()

        translated.forEach { language ->
            val dir = dirFor(language)
            if (!dir.isDirectory) return@forEach
            // The suffix check above is the rule; this is the consequence, asserted against the
            // library actually published rather than against the rule that is meant to prevent it.
            val collisions = factIds(dir).filter { it in englishIds }
            assertTrue(
                collisions.isEmpty(),
                "${language.tag} publishes ${collisions.size} fact ids English already uses, " +
                    "starting with ${collisions.take(3)}",
            )
        }
    }

    @Test
    fun `a translated index points inside its own language's folder`() {
        translated.forEach { language ->
            val dir = dirFor(language)
            val index = File(dir, "index.json")
            if (!index.isFile) return@forEach

            val raw = index.readText()
            val entries = Regex("""\{[^{}]*"file"[^{}]*}""").findAll(raw).toList()
            assertTrue(entries.isNotEmpty(), "${language.tag}'s index lists no shards")

            entries.forEach { entry ->
                val file = Regex(""""file"\s*:\s*"([^"]*)"""").find(entry.value)!!.groupValues[1]
                // PackService appends this to `.../packs/<tag>/`, so it is relative to the
                // language's own root and must not repeat the tag. A path that repeats it 404s on
                // every device, and nothing in the app would say so.
                assertTrue(
                    file.startsWith("library/") && !file.startsWith("/") &&
                        !file.startsWith("${language.tag}/"),
                    "${language.tag} index has file '$file', which will not resolve",
                )
                assertTrue(
                    File(dir, file.removePrefix("library/")).exists(),
                    "${language.tag} index points at '$file', which is not there",
                )
            }

            val listed = entries.mapNotNull {
                Regex(""""id"\s*:\s*"([^"]*)"""").find(it.value)?.groupValues?.get(1)
            }.toSet()
            assertEquals(
                shards(dir).map { ContentParser.parsePack(it.readText(), it.name).packId }.toSet(),
                listed,
                "${language.tag}'s index and its shard files disagree about what exists",
            )
        }
    }
}
