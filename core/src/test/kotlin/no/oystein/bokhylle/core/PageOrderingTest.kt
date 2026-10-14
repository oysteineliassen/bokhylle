package no.oystein.bokhylle.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PageOrderingTest {
    private fun p(id: Long, n: Int?, index: Int = id.toInt(), batch: Long = 1, roman: Boolean = false, time: Long? = null) =
        OrderInput(id, batch, index, time, n?.let { PageNumber(it, roman) })

    @Test
    fun `sorterer på sidetall`() {
        val r = PageOrdering.order(listOf(p(1, 12), p(2, 10), p(3, 11)))
        assertEquals(listOf(2L, 3L, 1L), r.order)
        assertTrue(r.missing.isEmpty())
    }

    @Test
    fun `side uten sidetall blir ved naboen og får utledet sidetall`() {
        // Bildene: 10, 11, (bildeside), 13, 14
        val r = PageOrdering.order(listOf(p(1, 10), p(2, 11), p(3, null), p(4, 13), p(5, 14)))
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), r.order)
        assertEquals(PageNumber(12), r.inferred[3])
        assertTrue(r.flags.isEmpty(), "${r.flags}")
    }

    @Test
    fun `manglende side meldes og flagges`() {
        val r = PageOrdering.order(listOf(p(1, 46), p(2, 47), p(3, 49), p(4, 50)))
        assertEquals(1, r.missing.size)
        assertEquals("48", r.missing[0].label)
        assertEquals(2L, r.missing[0].afterPageId)
        assertEquals(setOf(PageFlag.HOPP), r.flags[3])
    }

    @Test
    fun `flere manglende sider vises som intervall`() {
        val r = PageOrdering.order(listOf(p(1, 10), p(2, 14)))
        assertEquals("11–13", r.missing.single().label)
        assertEquals(3, r.missing.single().count)
    }

    @Test
    fun `duplikat flagges`() {
        val r = PageOrdering.order(listOf(p(1, 1), p(2, 2), p(3, 2), p(4, 3)))
        assertEquals(setOf(PageFlag.DUPLIKAT), r.flags[2])
        assertEquals(setOf(PageFlag.DUPLIKAT), r.flags[3])
        assertEquals(listOf(1L, 2L, 3L, 4L), r.order)
    }

    @Test
    fun `feillest sidetall blir der bildet ble tatt`() {
        // 20, 21, «122» (egentlig 22), 23, 24
        val r = PageOrdering.order(listOf(p(1, 20), p(2, 21), p(3, 122), p(4, 23), p(5, 24)))
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), r.order)
        assertEquals(setOf(PageFlag.HOPP), r.flags[3])
    }

    @Test
    fun `side fotografert på nytt senere sorteres inn`() {
        // 30, 31, 33, 34, så 32 tatt etterpå
        val r = PageOrdering.order(listOf(p(1, 30), p(2, 31), p(3, 33), p(4, 34), p(5, 32)))
        assertEquals(listOf(1L, 2L, 5L, 3L, 4L), r.order)
        assertEquals(setOf(PageFlag.TILBAKEGANG), r.flags[5])
        assertTrue(r.missing.isEmpty())
    }

    @Test
    fun `manglende side lagt til i ny import sorteres inn uten flagg`() {
        val first = listOf(p(1, 46), p(2, 47), p(3, 49))
        val r = PageOrdering.order(first + p(4, 48, index = 0, batch = 2))
        assertEquals(listOf(1L, 2L, 4L, 3L), r.order)
        assertTrue(r.missing.isEmpty())
        assertTrue(r.flags.isEmpty(), "${r.flags}")
    }

    @Test
    fun `romertall før vanlige sidetall`() {
        val r = PageOrdering.order(listOf(p(1, 1), p(2, 2), p(3, 1, roman = true), p(4, 2, roman = true)))
        assertEquals(listOf(3L, 4L, 1L, 2L), r.order)
    }

    @Test
    fun `EXIF-tid bestemmer fotorekkefølgen`() {
        // Ingen sidetall: rekkefølgen følger tidspunktet bildet ble tatt.
        val r = PageOrdering.order(listOf(p(1, null, time = 300), p(2, null, time = 100), p(3, null, time = 200)))
        assertEquals(listOf(2L, 3L, 1L), r.order)
        assertEquals(3, r.flags.count { PageFlag.MANGLER_SIDETALL in it.value })
    }

    @Test
    fun `manuelt flyttet side blir der brukeren la den`() {
        val pages = listOf(
            p(1, 1), p(2, 2), p(3, 3),
            OrderInput(4, 1, 4, null, null, manual = true, manualAfter = 1),
        )
        assertEquals(listOf(1L, 4L, 2L, 3L), PageOrdering.order(pages).order)
        val first = pages.dropLast(1) + OrderInput(4, 1, 4, null, null, manual = true, manualAfter = null)
        assertEquals(listOf(4L, 1L, 2L, 3L), PageOrdering.order(first).order)
    }

    @Test
    fun `godkjent side flagges ikke`() {
        val r = PageOrdering.order(listOf(p(1, 1), OrderInput(2, 1, 2, null, null, accepted = true), p(3, 5)))
        assertTrue(2L !in r.flags)
    }

    @Test
    fun `lengste ikke-synkende delsekvens`() {
        val v = listOf(1, 2, 9, 3, 4, 2, 5).map { PageNumber(it) }
        assertEquals(listOf(0, 1, 3, 4, 6), PageOrdering.longestNonDecreasing(v))
    }
}

class PageRangesTest {
    @Test
    fun `intervaller`() {
        val n = (1..64).map { PageNumber(it) } + (80..112).map { PageNumber(it) } + PageNumber(200) + listOf(PageNumber(1, true), PageNumber(2, true))
        assertEquals("i–ii, 1–64, 80–112, 200", PageRanges.describe(n))
        assertEquals("", PageRanges.describe(emptyList()))
    }
}
