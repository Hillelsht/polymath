package com.hillelsht.smart.content

import com.hillelsht.smart.data.seed.ContentParser
import com.hillelsht.smart.domain.model.Language
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The catalogue, checked against the packs it claims to describe.
 *
 * `manifest.json` is one of exactly two files `PackService` reads to discover content, and it is
 * the only route by which a pack that is not a library shard ever reaches a device. Everything
 * about it is a promise made to a phone that cannot check it: that the file named is there, that
 * it parses, that it holds the number of facts advertised, and that its version is the one the
 * device will compute after downloading it.
 *
 * Each of those had already been broken at least once when this was written.
 *
 *  * **The file named is there.** `tools/build_manifest.py` writes paths relative to the language
 *    root because that is what `fetchPack` joins them to. A path that repeats the tag —
 *    `ru/geography.json` under `packs/ru/` — 404s on every device and nothing in the app says so.
 *  * **The version is the one the device computes.** `ContentParser` falls back to hashing the raw
 *    text when a pack names no version, and no tool outside the app can reproduce that hash. Both
 *    translated packs shipped that way: a catalogue that permanently disagrees with what is
 *    installed is a catalogue that offers the same download on every single refresh, forever.
 *  * **A catalogue exists at all.** `packs/ru/manifest.json` and its Hebrew twin did not, for
 *    weeks, while both languages had a published pack sitting one URL away. `fetchManifest`
 *    returns null for a 404 and null also means "CI has not published yet", so the Library tab in
 *    those languages was empty and nothing distinguished that from working correctly.
 *
 * A language with nothing published is reported rather than asserted about — the same rule
 * `TranslatedLibraryTest` follows, for the same reason: failing on work not yet done is not the
 * same as failing on work done wrongly.
 */
class CatalogueTest {

    private fun rootFor(language: Language) =
        if (language == Language.default) File("../packs") else File("../packs/${language.tag}")

    private fun manifestFor(language: Language) = File(rootFor(language), "manifest.json")

    /**
     * The catalogue's rows, read with a regex rather than a serializer.
     *
     * `enginetests` compiles `domain/` and `data/seed/` only — `RemotePack` lives in
     * `data/remote/` with OkHttp beside it, which would drag Android into a build whose whole
     * purpose is not to have it. The fields are flat strings and numbers, so reading them here
     * costs less than the dependency would.
     */
    private data class Row(
        val id: String,
        val category: String,
        val version: String,
        val facts: Int,
        val file: String,
    )

    private fun rowsOf(manifest: File): List<Row> {
        val raw = manifest.readText()
        return Regex("""\{[^{}]*"file"[^{}]*}""").findAll(raw).map { entry ->
            fun str(field: String) =
                Regex(""""$field"\s*:\s*"([^"]*)"""").find(entry.value)?.groupValues?.get(1).orEmpty()

            fun num(field: String) =
                Regex(""""$field"\s*:\s*(\d+)""").find(entry.value)?.groupValues?.get(1)?.toInt() ?: -1
            Row(str("id"), str("category"), str("version"), num("facts"), str("file"))
        }.toList()
    }

    private fun catalogues(): List<Pair<Language, List<Row>>> =
        Language.entries.mapNotNull { language ->
            val manifest = manifestFor(language)
            if (!manifest.isFile) {
                println("  ${language.tag}: no catalogue published")
                null
            } else {
                language to rowsOf(manifest)
            }
        }

    @Test
    fun `every language that publishes a pack publishes a catalogue naming it`() {
        Language.entries.forEach { language ->
            val root = rootFor(language)
            // Only loose packs at the language root are catalogue material. `library/` is a
            // supply consumed through its own index, and `play/` is game content, not facts.
            val published = root.listFiles { f ->
                f.name.endsWith(".json") && f.name !in NOT_A_PACK
            }.orEmpty().filter { it.readText().contains("\"facts\"") }
            if (published.isEmpty()) {
                println("  ${language.tag}: nothing published")
                return@forEach
            }
            assertTrue(
                manifestFor(language).isFile,
                "${language.tag} publishes ${published.size} packs and no manifest.json, so a " +
                    "device asking for its catalogue gets a 404 and an empty Library tab",
            )
            val listed = rowsOf(manifestFor(language)).map { it.file }.toSet()
            published.forEach { pack ->
                assertTrue(
                    pack.name in listed,
                    "${language.tag} publishes ${pack.name} and its catalogue does not list it, " +
                        "so nothing can ever download it",
                )
            }
        }
    }

    @Test
    fun `every catalogue entry names a file that is there and parses`() {
        catalogues().forEach { (language, rows) ->
            assertTrue(rows.isNotEmpty(), "${language.tag}'s catalogue lists nothing")
            rows.forEach { row ->
                assertTrue(
                    !row.file.startsWith("/") && !row.file.startsWith("${language.tag}/"),
                    "${language.tag} lists '${row.file}', which is not relative to its own root " +
                        "and will 404 on every device",
                )
                val file = File(rootFor(language), row.file)
                assertTrue(file.isFile, "${language.tag} lists '${row.file}', which is not there")
                val pack = ContentParser.parsePack(file.readText(), row.file)
                assertEquals(row.id, pack.packId, "${row.file} is catalogued under the wrong id")
                assertEquals(
                    row.facts, pack.facts.size,
                    "${row.file} is advertised as ${row.facts} facts and holds ${pack.facts.size}",
                )
            }
            println("  ${language.tag}: ${rows.size} packs catalogued, ${rows.sumOf { it.facts }} facts")
        }
    }

    @Test
    fun `the version a catalogue advertises is the one the device will compute`() {
        catalogues().forEach { (language, rows) ->
            rows.forEach { row ->
                val file = File(rootFor(language), row.file)
                val pack = ContentParser.parsePack(file.readText(), row.file)
                assertEquals(
                    row.version, pack.version,
                    "${row.file} is catalogued as v${row.version} and parses as v${pack.version}. " +
                        "The device compares exactly these two strings to decide whether it is up " +
                        "to date, so it would re-download this pack on every refresh, forever",
                )
            }
        }
    }

    @Test
    fun `a catalogue holds one entry per pack, and only its own language's`() {
        catalogues().forEach { (language, rows) ->
            val ids = rows.map { it.id }
            assertEquals(
                ids.size, ids.toSet().size,
                "${language.tag} catalogues an id twice: ${ids.groupBy { it }.filterValues { it.size > 1 }.keys}",
            )
            rows.forEach { row ->
                val pack = ContentParser.parsePack(
                    File(rootFor(language), row.file).readText(), row.file,
                )
                assertEquals(
                    language, pack.language,
                    "${row.file} sits in ${language.tag}'s catalogue and declares itself " +
                        "${pack.language.tag} — whichever is wrong, the device believes the file",
                )
            }
        }
    }

    /**
     * The rule that costs review history when it is broken, applied to the newest way of breaking
     * it: a topic pack is generated, namespaced by a slug somebody typed, and lands in the same
     * catalogue as the curated packs. An id collision here installs one pack *over* another.
     */
    @Test
    fun `no catalogued pack can overwrite another pack's facts`() {
        catalogues().forEach { (language, rows) ->
            val owners = mutableMapOf<String, String>()
            rows.forEach { row ->
                val pack = ContentParser.parsePack(
                    File(rootFor(language), row.file).readText(), row.file,
                )
                pack.facts.forEach { fact ->
                    val previous = owners.put(fact.id, pack.packId)
                    assertTrue(
                        previous == null || previous == pack.packId,
                        "fact '${fact.id}' is published by both $previous and ${pack.packId} in " +
                            "${language.tag} — installing the second replaces the first and takes " +
                            "its review history with it",
                    )
                }
            }
        }
    }

    /**
     * The bundled copy and the published one must agree about the version, not merely exist.
     *
     * `docs/invariants.md` already required both copies. This is the half that was missing: a
     * bundled pack naming no version parses as a hash of its own text, so the device seeds one
     * version at first launch and the catalogue immediately advertises a different one — and it
     * downloads a pack it already has, in full, to end up exactly where it started.
     *
     * The specific way it went wrong is worth keeping: `build_manifest.py`'s own self-test built a
     * pack tree in a temporary directory and mirrored it to the *repository's* assets folder,
     * overwriting the bundled Russian pack with a three-fact fixture. It was caught by the id
     * comparison in `RussianPackTest`; this catches the quieter half of the same mistake.
     */
    @Test
    fun `a bundled pack and its published copy name the same version`() {
        catalogues().forEach { (language, rows) ->
            rows.forEach { row ->
                val bundled = File(
                    if (language == Language.default) "../app/src/main/assets/packs"
                    else "../app/src/main/assets/packs/${language.tag}",
                    row.file,
                )
                // Only packs that ship inside the APK have a twin. A topic pack is downloaded, and
                // copying it in would grow the APK by exactly the content designed to stay out.
                if (!bundled.isFile) return@forEach
                val published = ContentParser.parsePack(
                    File(rootFor(language), row.file).readText(), row.file,
                )
                val inApk = ContentParser.parsePack(bundled.readText(), row.file)
                assertEquals(
                    published.version, inApk.version,
                    "${row.file} is v${published.version} published and v${inApk.version} bundled, " +
                        "so every install downloads a pack it already shipped with",
                )
                assertEquals(
                    published.facts.map { it.id }, inApk.facts.map { it.id },
                    "${row.file} bundles different facts than it publishes",
                )
            }
        }
    }

    private companion object {
        val NOT_A_PACK = setOf("manifest.json", "channels.json", "durations.json", "index.json")
    }
}
