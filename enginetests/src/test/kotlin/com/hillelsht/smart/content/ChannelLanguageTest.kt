package com.hillelsht.smart.content

import com.hillelsht.smart.data.seed.ChannelParser
import com.hillelsht.smart.domain.model.Category
import com.hillelsht.smart.domain.model.Language
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Watch allowlist, checked with the app's own parser.
 *
 * The authoring file under `assets/content/` carries handles; CI's prober resolves each to a
 * UC id and publishes `packs/channels.json`, which is what the device actually reads. Both are
 * checked here — the authoring file for the invariants a human edits it against, the published
 * file for the shape the app installs.
 */
class ChannelLanguageTest {

    private val authoring = File("../app/src/main/assets/content/channels.json")
    private val published = File("../app/src/main/assets/packs/channels.json")

    /** The authoring file has no UC ids, so it is read as plain JSON rather than through the parser. */
    private fun authoredEntries(): List<Triple<String, String, String>> {
        val text = authoring.readText()
        return Regex("""\{[^{}]*}""").findAll(text).mapNotNull { match ->
            val block = match.value
            fun field(name: String) =
                Regex(""""$name"\s*:\s*"([^"]*)"""").find(block)?.groupValues?.get(1)
            val handle = field("handle") ?: return@mapNotNull null
            Triple(handle, field("category").orEmpty(), field("language") ?: "en")
        }.toList()
    }

    @Test
    fun `every authored channel names a category the app knows and a language it supports`() {
        val entries = authoredEntries()
        assertTrue(entries.isNotEmpty(), "the allowlist parsed to nothing")
        entries.forEach { (handle, category, language) ->
            assertTrue(Category.fromId(category) != null, "@$handle has unknown category '$category'")
            assertTrue(
                Language.entries.any { it.tag == language },
                "@$handle declares language '$language', which the app cannot select",
            )
        }
    }

    @Test
    fun `no handle is listed twice`() {
        val handles = authoredEntries().map { it.first.lowercase() }
        val duplicates = handles.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        assertTrue(duplicates.isEmpty(), "duplicate handles in the allowlist: $duplicates")
    }

    @Test
    fun `every language offered in Settings has channels in every category`() {
        // A language whose Watch tab is empty for a subject is a dead filter chip: the user
        // taps Geography and sees nothing, with no way to tell whether that is a bug.
        val byLanguage = authoredEntries().groupBy({ it.third }, { it.second })
        Language.entries.forEach { language ->
            val categories = byLanguage[language.tag].orEmpty().toSet()
            val missing = Category.entries.map { it.id }.toSet() - categories
            assertTrue(
                missing.isEmpty(),
                "'${language.tag}' has no Watch channel for: ${missing.sorted()}",
            )
        }
    }

    @Test
    fun `the published allowlist parses and defaults its language to English`() {
        // Written by CI before languages existed, so it carries no language field at all. It
        // must still install, entirely as English — that fallback is what stops an app update
        // arriving before a pipeline run from emptying the Watch tab.
        val parsed = ChannelParser.parse(published.readText(), published.name)
        assertTrue(parsed.channels.isNotEmpty())
        parsed.channels.forEach { channel ->
            assertEquals(Language.ENGLISH, channel.language, "${channel.handle} is not English")
            assertTrue(channel.id.startsWith("UC"), "${channel.handle} has no UC id")
        }
    }

    @Test
    fun `a channel that declares Russian parses as Russian`() {
        val raw = """
            {"channels":[
              {"id":"UC101o-vQ2iOj9vr00JUlyKw","handle":"varlamov","category":"geography",
               "displayName":"varlamov","language":"ru"},
              {"id":"UCmmPgObSUPw1HL2lq6H4ffA","handle":"GeographyNow","category":"geography"}
            ]}
        """.trimIndent()
        val parsed = ChannelParser.parse(raw, "mixed.json")
        assertEquals(Language.RUSSIAN, parsed.channels.first { it.handle == "varlamov" }.language)
        assertEquals(Language.ENGLISH, parsed.channels.first { it.handle == "GeographyNow" }.language)
    }
}
