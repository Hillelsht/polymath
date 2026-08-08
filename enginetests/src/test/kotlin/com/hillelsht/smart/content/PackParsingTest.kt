package com.hillelsht.smart.content

import com.hillelsht.smart.data.seed.ContentParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

/** Schema v2: enriched packs parse with their extra fields, and plain authoring files still work. */
class PackParsingTest {

    private val enriched = """
        {
          "category": "science",
          "packId": "science",
          "version": "abc123",
          "name": "Science",
          "facts": [
            {
              "id": "sci-x",
              "title": "The symbol Pb",
              "statement": "Lead has the chemical symbol Pb, from the Latin plumbum used for Roman pipes.",
              "question": "Which element has the chemical symbol Pb?",
              "answer": "Lead",
              "answerType": "element",
              "wikiTitle": "Lead",
              "details": "Lead is a chemical element with atomic number 82. It is a heavy metal that is denser than most common materials.",
              "imageUrl": "https://upload.wikimedia.org/wikipedia/commons/thumb/x/y/Lead.jpg/640px-Lead.jpg",
              "pageUrl": "https://en.wikipedia.org/wiki/Lead"
            }
          ]
        }
    """.trimIndent()

    private val authoring = """
        {
          "category": "science",
          "facts": [
            {
              "id": "sci-y",
              "title": "The symbol Fe",
              "statement": "Iron has the chemical symbol Fe, from the Latin ferrum, and fills the Earth's core.",
              "question": "Which element has the chemical symbol Fe?",
              "answer": "Iron",
              "answerType": "element"
            }
          ]
        }
    """.trimIndent()

    @Test
    fun `an enriched pack carries version, pack id and the enrichment fields`() {
        val pack = ContentParser.parsePack(enriched, "science.json")
        assertEquals("science", pack.packId)
        assertEquals("abc123", pack.version)

        val fact = pack.facts.single()
        assertEquals("science", fact.packId)
        assertEquals("https://en.wikipedia.org/wiki/Lead", fact.pageUrl)
        assertEquals(
            "https://upload.wikimedia.org/wikipedia/commons/thumb/x/y/Lead.jpg/640px-Lead.jpg",
            fact.imageUrl,
        )
        assertEquals(true, fact.details!!.startsWith("Lead is a chemical element"))
    }

    @Test
    fun `an authoring file without pack metadata still parses, with derived identity`() {
        val pack = ContentParser.parsePack(authoring, "science.json")
        assertEquals("science", pack.packId)
        assertNotEquals("", pack.version)

        val fact = pack.facts.single()
        assertNull(fact.details)
        assertNull(fact.imageUrl)
        assertNull(fact.pageUrl)
    }

    @Test
    fun `the version derived for authoring files changes when the text changes`() {
        val a = ContentParser.parsePack(authoring, "a")
        val b = ContentParser.parsePack(authoring.replace("ferrum", "FERRUM"), "b")
        assertNotEquals(a.version, b.version)
    }
}
