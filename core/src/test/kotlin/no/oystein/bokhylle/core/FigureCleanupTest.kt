package no.oystein.bokhylle.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FigureCleanupTest {
    private val figure = Box(0.1f, 0.1f, 0.9f, 0.6f)

    @Test
    fun `bildetekst nederst i figuren klippes bort`() {
        val caption = Box(0.12f, 0.52f, 0.88f, 0.58f)
        val trimmed = FigureCleanup.trimText(figure, listOf(caption))
        assertTrue(trimmed.bottom < caption.top)
        assertEquals(figure.top, trimmed.top)
    }

    @Test
    fun `tittel øverst i figuren klippes bort`() {
        val title = Box(0.3f, 0.11f, 0.7f, 0.15f)
        val trimmed = FigureCleanup.trimText(figure, listOf(title))
        assertTrue(trimmed.top > title.bottom)
        assertEquals(figure.bottom, trimmed.bottom)
    }

    @Test
    fun `tekst utenfor figuren rører ikke figuren`() {
        val below = Box(0.1f, 0.62f, 0.9f, 0.66f)
        val beside = Box(0.92f, 0.3f, 0.99f, 0.35f)
        assertEquals(figure, FigureCleanup.trimText(figure, listOf(below, beside)))
    }

    @Test
    fun `figur som nesten bare er tekst beholdes hel`() {
        val texts = listOf(Box(0.1f, 0.1f, 0.9f, 0.34f), Box(0.1f, 0.36f, 0.9f, 0.6f))
        assertEquals(figure, FigureCleanup.trimText(figure, texts))
    }

    private fun slantedLine(y: Float, degrees: Double, width: Int, height: Int): OcrLine {
        val slope = Math.tan(Math.toRadians(degrees)).toFloat()
        val words = (0 until 5).map { i ->
            val cx = 0.2f + i * 0.15f
            val cy = y + slope * (cx - 0.2f) * width / height
            OcrElement("ord", cx - 0.05f, cy - 0.01f, cx + 0.05f, cy + 0.01f)
        }
        return OcrLine("ord ord ord ord ord", 0.15f, y - 0.02f, 0.85f, y + 0.05f, elements = words)
    }

    @Test
    fun `skjevhet måles fra ordene på linjene`() {
        val lines = listOf(0.3f, 0.35f, 0.4f).map { slantedLine(it, 3.0, 1000, 1500) }
        val skew = assertNotNull(FigureCleanup.skewDegrees(lines, Box(0.1f, 0.25f, 0.9f, 0.45f), 1000, 1500))
        assertEquals(3f, skew, 0.2f)
    }

    @Test
    fun `rett tekst gir ingen rotasjon`() {
        val lines = listOf(0.3f, 0.35f, 0.4f).map { slantedLine(it, 0.1, 1000, 1500) }
        assertNull(FigureCleanup.skewDegrees(lines, Box(0.1f, 0.25f, 0.9f, 0.45f), 1000, 1500))
    }

    @Test
    fun `gråstikk på papiret blir hvitt og blekk blir svart`() {
        val paper = (0xFF shl 24) or (190 shl 16) or (185 shl 8) or 170
        val ink = (0xFF shl 24) or (60 shl 16) or (58 shl 8) or 55
        val w = 20; val h = 20
        val px = IntArray(w * h) { i -> if (i % w in 8..11) ink else paper }
        FigureCleanup.enhance(px, w, h, paper)
        val p = px[5 * w + 2]
        assertTrue(((p shr 16) and 0xFF) >= 240 && ((p shr 8) and 0xFF) >= 240 && (p and 0xFF) >= 240, "papiret ble ikke hvitt")
        val k = px[5 * w + 9]
        assertTrue(((k shr 16) and 0xFF) < 60, "blekket ble ikke mørkere")
    }

    @Test
    fun `farget boks beskjæres til boksen, vanlig figur ikke`() {
        val white = (0xFF shl 24) or (235 shl 16) or (233 shl 8) or 230
        val yellow = (0xFF shl 24) or (220 shl 16) or (175 shl 8) or 90
        val w = 100; val h = 80
        // Gul boks med en stripe av siden langs toppen og venstre kant.
        val boxed = IntArray(w * h) { i -> if (i / w < 6 || i % w < 4) white else yellow }
        val b = FigureCleanup.backgroundBounds(boxed, w, h, white)
        assertEquals(4, b[0]); assertEquals(6, b[1]); assertEquals(w, b[2]); assertEquals(h, b[3])
        val plain = IntArray(w * h) { i -> if (i % 7 == 0) 0xFF000000.toInt() else white }
        assertEquals(listOf(0, 0, w, h), FigureCleanup.backgroundBounds(plain, w, h, white).toList())
    }

    private fun rgb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    @Test
    fun `lyset jevnes ut når en kant er mørkere`() {
        val w = 120; val h = 80
        // Gul bakgrunn som blir mørkere mot høyre, med svarte streker.
        val px = IntArray(w * h) { i ->
            val x = i % w
            val f = 1f - 0.35f * x / w
            if (x % 15 == 0) rgb(20, 20, 20) else rgb((230 * f).toInt(), (180 * f).toInt(), (90 * f).toInt())
        }
        assertTrue(FigureCleanup.flattenIllumination(px, w, h))
        val left = (px[40 * w + 3] shr 16) and 0xFF
        val right = (px[40 * w + w - 4] shr 16) and 0xFF
        assertTrue(kotlin.math.abs(left - right) < 15, "venstre $left, høyre $right")
        assertTrue(((px[40 * w + 15] shr 16) and 0xFF) < 60, "strekene skal fortsatt være mørke")
    }

    @Test
    fun `fotografi røres ikke`() {
        val w = 90; val h = 90
        val rnd = java.util.Random(1)
        val px = IntArray(w * h) { rgb(rnd.nextInt(256), rnd.nextInt(256), rnd.nextInt(256)) }
        val before = px.copyOf()
        assertTrue(!FigureCleanup.flattenIllumination(px, w, h))
        assertTrue(before.contentEquals(px))
    }

    @Test
    fun `gråtoner - hvite linjer på gult skiller seg fra bakgrunnen`() {
        val w = 100; val h = 50
        val px = IntArray(w * h) { i ->
            when (i % w) {
                in 10..12 -> rgb(250, 250, 245) // hvit linje
                in 30..31 -> rgb(30, 30, 30) // svart strek
                else -> rgb(230, 185, 95) // gul bakgrunn
            }
        }
        val g = FigureCleanup.einkGray(px)
        val bg = g[20 * w + 50]; val white = g[20 * w + 11]; val ink = g[20 * w + 30]
        assertTrue(white - bg >= 40, "hvit $white mot bakgrunn $bg")
        assertTrue(ink < 40, "blekk $ink")
    }

    @Test
    fun `gråtoner - vanlig figur på hvitt papir får hvit bakgrunn`() {
        val w = 100; val h = 50
        val px = IntArray(w * h) { i -> if (i % w in 30..31) rgb(30, 30, 30) else rgb(244, 242, 238) }
        val g = FigureCleanup.einkGray(px)
        assertTrue(g[20 * w + 50] >= 245, "bakgrunn ${g[20 * w + 50]}")
    }

    @Test
    fun `stor fargeflate beholder fargen og papiret får ikke fargestikk`() {
        val w = 160; val h = 120
        // Papir som blir mørkere mot høyre, med en stor oransje sirkel i midten (som sola i boka).
        val px = IntArray(w * h) { i ->
            val x = i % w; val y = i / w
            val f = 1f - 0.3f * x / w
            val inDisc = (x - 80) * (x - 80) + (y - 60) * (y - 60) < 45 * 45
            if (inDisc) rgb((235 * f).toInt(), (170 * f).toInt(), (70 * f).toInt())
            else rgb((240 * f).toInt(), (238 * f).toInt(), (232 * f).toInt())
        }
        assertTrue(FigureCleanup.flattenIllumination(px, w, h))
        val center = px[60 * w + 80]
        val r = (center shr 16) and 0xFF; val g = (center shr 8) and 0xFF; val b = center and 0xFF
        assertTrue(r - b > 100 && r > 180, "sola skal fortsatt være oransje: $r,$g,$b")
        val paperL = px[10 * w + 5]; val paperR = px[10 * w + w - 6]
        fun spread(p: Int) = maxOf((p shr 16) and 0xFF, (p shr 8) and 0xFF, p and 0xFF) - minOf((p shr 16) and 0xFF, (p shr 8) and 0xFF, p and 0xFF)
        assertTrue(spread(paperL) < 15 && spread(paperR) < 15, "papiret har fått fargestikk")
        assertTrue(kotlin.math.abs(((paperL shr 16) and 0xFF) - ((paperR shr 16) and 0xFF)) < 15, "lyset er ikke jevnet ut")
    }

    @Test
    fun `etiketter rett over figuren kommer med, brødtekst ikke`() {
        val figure = Box(0.2f, 0.3f, 0.8f, 0.6f)
        val label = OcrLine("UVB (5 % av total UV)", 0.4f, 0.25f, 0.65f, 0.28f)
        val body = Box(0.1f, 0.65f, 0.9f, 0.9f)
        val bodyLine = OcrLine("brødtekst", 0.1f, 0.65f, 0.9f, 0.68f)
        val heading = Box(0.25f, 0.18f, 0.75f, 0.22f)
        val headingLine = OcrLine("Solenergien som når jorden", 0.25f, 0.18f, 0.75f, 0.22f)
        val out = FigureCleanup.expandFigure(figure, listOf(label, bodyLine, headingLine), listOf(body, heading))
        assertTrue(out.top <= label.top, "etiketten kom ikke med: $out")
        assertTrue(out.top > heading.bottom - 0.001f, "overskriften kom med: $out")
        assertTrue(out.bottom < body.top, "brødteksten kom med: $out")
        assertTrue(out.left < figure.left && out.right > figure.right, "ingen luft på sidene: $out")
    }

    @Test
    fun `etikett innenfor bildetekstens ramme kommer med i figuren`() {
        // Vannmolekylet: «Negativ side –» står rett under figuren, men bildetekstens ramme begynner over den.
        val figure = Box(0.15f, 0.2f, 0.85f, 0.6f)
        val label = OcrLine("Negativ side –", 0.4f, 0.62f, 0.6f, 0.65f)
        val cap1 = OcrLine("Vannmolekylet (H2O) består av ett oksygenatom og to hydrogenatomer. De dan-", 0.12f, 0.68f, 0.88f, 0.71f)
        val cap2 = OcrLine("ner en gjennomsnittlig vinkel pa 104,5 grader, men molekylet vibrerer", 0.12f, 0.72f, 0.88f, 0.75f)
        val caption = Box(0.12f, 0.61f, 0.88f, 0.76f) to
            "Vannmolekylet (H₂O) består av ett oksygenatom og to hydrogenatomer. De danner en gjennomsnittlig vinkel på 104,5 grader, men molekylet vibrerer"
        val lines = listOf(label, cap1, cap2)
        val tight = FigureCleanup.tightenTextBoxes(listOf(caption), lines)
        assertTrue(tight[0].top >= cap1.top - 0.001f, "bildetekstens ramme ble ikke strammet inn: ${tight[0]}")
        val out = FigureCleanup.expandFigure(figure, lines, tight)
        assertTrue(out.bottom >= label.bottom, "etiketten kom ikke med: $out")
        assertTrue(out.bottom < cap1.top + 0.001f, "bildeteksten kom med: $out")
        // Uten innstramming stopper rammen ved bildeteksten (slik det var).
        val before = FigureCleanup.expandFigure(figure, lines, listOf(caption.first))
        assertTrue(before.bottom < label.bottom, "testen viser ikke forskjellen: $before")
    }

    @Test
    fun `fargeflate midt i en tegning gir ingen beskjæring`() {
        val white = rgb(235, 233, 230)
        val w = 100; val h = 100
        // Oransje sirkel i midten, hvitt papir rundt (der etiketter og strektegning står).
        val px = IntArray(w * h) { i ->
            val x = i % w; val y = i / w
            if ((x - 50) * (x - 50) + (y - 50) * (y - 50) < 30 * 30) rgb(235, 170, 70) else white
        }
        assertEquals(listOf(0, 0, w, h), FigureCleanup.backgroundBounds(px, w, h, white).toList())
    }
}
