package no.oystein.bokhylle.core

import no.oystein.bokhylle.core.TestPages.line
import no.oystein.bokhylle.core.TestPages.page
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PageNumberDetectorTest {
    @Test
    fun `sidetall nederst`() {
        val found = PageNumberDetector.detect(page(listOf("Det var en gang"), number = "47"))!!
        assertEquals(PageNumber(47), found.number)
        assertTrue(found.wholeLine)
    }

    @Test
    fun `sidetall øverst med pynt`() {
        val found = PageNumberDetector.detect(page(listOf("tekst"), number = "– 112 –", numberAtTop = true))!!
        assertEquals(PageNumber(112), found.number)
    }

    @Test
    fun `romertall i forordet`() {
        val found = PageNumberDetector.detect(page(listOf("Forord"), number = "xii"))!!
        assertEquals(PageNumber(12, roman = true), found.number)
        assertEquals("xii", found.number.label)
    }

    @Test
    fun `sidetall i topptekstlinje`() {
        val lines = page(listOf("tekst"), header = "48   KAPITTEL TRE")
        val found = PageNumberDetector.detect(lines)!!
        assertEquals(PageNumber(48), found.number)
        assertFalse(found.wholeLine)
        assertEquals("48", found.token)
    }

    @Test
    fun `OCR-feil i sidetallet rettes`() {
        assertEquals(PageNumber(101), PageNumberDetector.parseToken("1O1", allowRoman = false))
        assertEquals(PageNumber(11), PageNumberDetector.parseToken("l1", allowRoman = false))
        assertNull(PageNumberDetector.parseToken("ord", allowRoman = true))
        assertNull(PageNumberDetector.parseToken("Mix", allowRoman = true))
    }

    @Test
    fun `tall midt på siden er ikke sidetall`() {
        val lines = page(listOf("tekst")).toMutableList()
        lines += line("1984", 0.5f, left = 0.45f, right = 0.55f)
        assertNull(PageNumberDetector.detect(lines))
    }

    @Test
    fun `stort kapittelnummer er ikke sidetall`() {
        val lines = page(listOf("tekst")).toMutableList()
        lines.add(0, line("7", 0.03f, left = 0.45f, right = 0.55f, height = 0.06f))
        assertNull(PageNumberDetector.detect(lines))
    }

    @Test
    fun `årstall i topptekst er ikke sidetall`() {
        assertNull(PageNumberDetector.detect(page(listOf("tekst"), header = "Krigen 1940")))
    }

    @Test
    fun `romertall format og tolkning`() {
        for (n in 1..120) assertEquals(n, Roman.parse(Roman.format(n)))
        assertNull(Roman.parse("iiii"))
        assertEquals(PageNumber(3, true), PageNumber.parse("iii"))
        assertTrue(PageNumber(200, roman = true) < PageNumber(1))
    }
}
