package no.oystein.bokhylle.core

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PageCropTest {
    private val w = 300
    private val h = 400

    /** Mørk bakgrunn med en lys, litt skjev side; valgfritt en naboside til venstre adskilt av en skygge. */
    private fun image(neighbour: Boolean): IntArray {
        val img = IntArray(w * h) { 40 }
        val tl = Point(60f, 40f); val tr = Point(250f, 50f); val br = Point(240f, 370f); val bl = Point(50f, 360f)
        fun inside(x: Float, y: Float): Boolean {
            val p = listOf(tl, tr, br, bl)
            for (i in 0..3) {
                val a = p[i]; val b = p[(i + 1) % 4]
                if ((b.x - a.x) * (y - a.y) - (b.y - a.y) * (x - a.x) < 0) return false
            }
            return true
        }
        for (y in 0 until h) for (x in 0 until w) {
            if (inside(x.toFloat(), y.toFloat())) img[y * w + x] = 220
            if (neighbour && x in 0 until 60 && y in 45..365) img[y * w + x] = 215
            if (neighbour && x in 52..62 && y in 45..365) img[y * w + x] = 120 // skygge i falsen
        }
        // Litt «tekst» på siden.
        for (y in 80 until 340 step 12) for (x in 80 until 220) if ((x / 4) % 3 != 0) img[y * w + x] = 60
        return img
    }

    private fun near(p: Point, x: Float, y: Float, tol: Float = 0.03f) =
        assertTrue(abs(p.x - x / (w - 1)) < tol && abs(p.y - y / (h - 1)) < tol, "$p ≠ (${x / (w - 1)}, ${y / (h - 1)})")

    @Test
    fun `finner hjørnene til en skjev side`() {
        val q = assertNotNull(PageCropDetector.detect(image(false), w, h))
        near(q.tl, 60f, 40f); near(q.tr, 250f, 50f); near(q.br, 240f, 370f); near(q.bl, 50f, 360f)
    }

    @Test
    fun `kutter bort nabosiden ved midtfalsen`() {
        val q = assertNotNull(PageCropDetector.detect(image(true), w, h))
        assertTrue(q.tl.x > 55f / w, "venstre kant ${q.tl.x * w}")
        assertTrue(q.bl.x > 50f / w, "venstre kant ${q.bl.x * w}")
        near(q.br, 240f, 370f)
    }

    @Test
    fun `helt mørkt bilde gir ingen side`() {
        assertNull(PageCropDetector.detect(IntArray(w * h) { 30 }, w, h))
    }

    @Test
    fun `koding av hjørner`() {
        val q = Quad(Point(0.1f, 0.2f), Point(0.9f, 0.1f), Point(0.8f, 0.9f), Point(0.2f, 0.95f))
        assertEquals(q, Quad.decode(q.encode()))
        assertTrue(Quad.FULL.isConvex())
        assertEquals(1f, Quad.FULL.area())
    }
}

class JsonTest {
    @Test
    fun `les og skriv`() {
        val text = """{"a":1,"b":[true,null,"x\"y\n"],"c":{"d":-2.5e1}}"""
        val v = Json.parse(text)
        assertEquals(text.replace("-2.5e1", "-25"), Json.write(v))
    }

    @Test
    fun `OCR-side tur-retur`() {
        val page = OcrPage(
            listOf(OcrLine("Hei på deg", 0.1f, 0.2f, 0.5f, 0.25f, 3, 0.9f, listOf(OcrElement("Hei", 0.1f, 0.2f, 0.2f, 0.25f)))),
            listOf(OcrBlockInfo(3, Box(0.1f, 0.2f, 0.5f, 0.25f), cutByEdge = true, colored = false)),
        )
        assertEquals(page, OcrPage.decode(page.encode()))
    }
}

class SpreadTest {
    @Test
    fun `oppslag deles i to sider ved ryggen`() {
        val w = 400; val h = 300
        val img = IntArray(w * h) { 40 }
        for (y in 30 until 270) for (x in 30 until 370) img[y * w + x] = 225
        for (y in 30 until 270) for (x in 196..204) img[y * w + x] = 150 // skygge i ryggen
        for (y in 60 until 240 step 10) for (x in 50 until 180) if (x % 5 != 0) img[y * w + x] = 70
        val pages = PageCropDetector.detectPages(img, w, h)
        assertEquals(2, pages.size)
        val (left, right) = pages
        assertTrue(left.tr.x < 0.52f && left.tl.x < 0.1f, "venstre $left")
        assertTrue(right.tl.x > 0.48f && right.tr.x > 0.9f, "høyre $right")
    }

    @Test
    fun `stående enkeltside deles ikke`() {
        val w = 300; val h = 400
        val img = IntArray(w * h) { 40 }
        for (y in 30 until 370) for (x in 40 until 260) img[y * w + x] = 225
        assertEquals(1, PageCropDetector.detectPages(img, w, h).size)
    }
}

class DarkSpineTest {
    @Test
    fun `oppslag med helt mørk rygg deles også`() {
        val w = 460; val h = 330
        val img = IntArray(w * h) { 40 }
        for (y in 30 until 300) for (x in 30 until 220) img[y * w + x] = 230
        for (y in 30 until 300) for (x in 240 until 430) img[y * w + x] = 230
        for (y in 30 until 300) for (x in 220 until 240) img[y * w + x] = 90 // mørk rygg
        val pages = PageCropDetector.detectPages(img, w, h)
        assertEquals(2, pages.size, "$pages")
        assertTrue(pages[0].tl.x < pages[1].tl.x)
        assertTrue(pages[0].tr.x < 0.5f && pages[1].tl.x > 0.5f)
    }
}
