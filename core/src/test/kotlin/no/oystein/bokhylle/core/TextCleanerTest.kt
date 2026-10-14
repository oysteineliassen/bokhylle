package no.oystein.bokhylle.core

import no.oystein.bokhylle.core.TestPages.page
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TextCleanerTest {
    private val sentences = listOf("Morgenen var kald.", "Hunden bjeffet lenge.", "Ingen svarte på brevet.", "Toget gikk klokken ni.", "Elva flommet over.")

    @Test
    fun `orddeling på linjeslutt`() {
        assertEquals("bokhylle", TextCleaner.joinLines("bok-", "hylle"))
        assertEquals("NRK-sjefen", TextCleaner.joinLines("NRK-", "sjefen"))
        assertEquals("Nord-Norge", TextCleaner.joinLines("Nord-", "Norge"))
        assertEquals("sommer- og vinterferie", TextCleaner.joinLines("sommer-", "og vinterferie"))
        assertEquals("17-åring", TextCleaner.joinLines("17-", "åring"))
        assertEquals("en linje til", TextCleaner.joinLines("en linje", "til"))
    }

    @Test
    fun `linjer blir avsnitt ut fra innrykk`() {
        val lines = page(
            listOf(
                "Det var en gang en bok som sto i en hyl-",
                "le. Den var full av ord som ingen leste,",
                "og den ventet.",
                "Så kom det en leser. Hun tok boka ned",
                "og begynte å lese.",
            ),
            indents = setOf(0, 3),
            shortLines = setOf(2, 4),
        ).take(5)
        val paragraphs = TextCleaner.paragraphs(lines)
        assertEquals(
            listOf(
                "Det var en gang en bok som sto i en hylle. Den var full av ord som ingen leste, og den ventet.",
                "Så kom det en leser. Hun tok boka ned og begynte å lese.",
            ),
            paragraphs,
        )
    }

    @Test
    fun `avsnitt uten innrykk skilles på kort linje med punktum`() {
        val lines = page(
            listOf("Første avsnitt slutter her", "med punktum.", "Andre avsnitt starter her."),
            shortLines = setOf(1),
        ).take(3)
        assertEquals(2, TextCleaner.paragraphs(lines).size)
    }

    @Test
    fun `fjerner sidetall og løpende topptekst`() {
        val pages = (1..5).map { n ->
            val header = if (n % 2 == 0) "$n   BOKHYLLA" else "KAPITTEL EN   $n"
            val lines = page(listOf(sentences[n - 1]), header = header)
            CleanInput(n.toLong(), lines, PageNumberDetector.detect(lines))
        }
        val cleaned = TextCleaner.clean(pages)
        for ((id, text) in cleaned) {
            assertFalse("BOKHYLLA" in text || "KAPITTEL" in text, text)
            assertTrue(text.startsWith(sentences[id.toInt() - 1]), text)
        }
    }

    @Test
    fun `fjerner sidetall nederst og gjentatt bokttittel øverst`() {
        val pages = (1..4).map { n ->
            val lines = page(listOf(sentences[n - 1]), header = "Bokhylla", number = "$n")
            CleanInput(n.toLong(), lines, PageNumberDetector.detect(lines))
        }
        val cleaned = TextCleaner.clean(pages)
        assertTrue(cleaned.values.all { !it.contains("Bokhylla") && !Regex("(?m)^\\d+$").containsMatchIn(it) }, "$cleaned")
    }

    @Test
    fun `kapitteltittel i stor skrift beholdes`() {
        val pages = (1..4).map { n ->
            val lines = if (n == 1) {
                listOf(TestPages.line("Bokhylla", 0.05f, 0.3f, 0.7f, height = 0.05f)) + page(listOf("Start."), number = "1")
            } else page(listOf(sentences[n - 1]), header = "Bokhylla", number = "$n")
            CleanInput(n.toLong(), lines, PageNumberDetector.detect(lines))
        }
        assertTrue(TextCleaner.clean(pages).getValue(1).startsWith("Bokhylla"))
    }

    @Test
    fun `orddeling over sideskift`() {
        val a = CleanInput(1, listOf(TestPages.line("Han gikk inn i bok-", 0.1f)), null)
        val b = CleanInput(2, listOf(TestPages.line("hylla og fant den.", 0.1f)), null)
        val r = TextCleaner.clean(listOf(a, b))
        assertEquals("Han gikk inn i bokhylla", r[1])
        assertEquals("og fant den.", r[2])
    }

    @Test
    fun `råtekst`() {
        val lines = listOf(OcrLine("a", 0f, 0f, 1f, 0.1f, 0), OcrLine("b", 0f, 0f, 1f, 0.1f, 0), OcrLine("c", 0f, 0f, 1f, 0.1f, 1))
        assertEquals("a\nb\n\nc", rawText(lines))
    }
}
