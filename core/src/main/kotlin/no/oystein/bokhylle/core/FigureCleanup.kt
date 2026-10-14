package no.oystein.bokhylle.core

import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.roundToInt

/**
 * Gjør et utklipt figurfoto mer likt originalen i boka: tekst som også vises som egen tekst klippes bort,
 * skjevheten måles ut fra tekstlinjene, og fargene rettes som i en skanner (papiret blir hvitt,
 * kontrasten strekkes og bildet gjøres litt skarpere).
 */
object FigureCleanup {
    /** Endre bare når skjevheten er tydelig, men ikke så stor at målingen trolig er feil. */
    const val MIN_SKEW = 0.4f
    const val MAX_SKEW = 8f

    /** Fargeavstand (sum av kanalene) under denne regnes som bakgrunn ved bakgrunnsrens. */
    private const val CLEAN_BG = 60f

    /**
     * Krymper figurens område så bildetekst eller annen tekst som ligger inni det (øverst eller nederst)
     * ikke kommer med i bildet; teksten vises jo under figuren uansett.
     */
    fun trimText(figure: Box, texts: List<Box>): Box {
        var top = figure.top
        var bottom = figure.bottom
        val pad = figure.height * 0.01f
        for (t in texts) {
            val overlap = minOf(figure.right, t.right) - maxOf(figure.left, t.left)
            if (t.width <= 0f || overlap < t.width * 0.6f) continue
            // Teksten må ligge (nesten) helt inni figurens høyde.
            if (t.top < figure.top - pad || t.bottom > figure.bottom + pad) continue
            if (t.centerY > figure.top + figure.height * 0.55f) bottom = minOf(bottom, t.top - pad)
            else if (t.centerY < figure.top + figure.height * 0.45f) top = maxOf(top, t.bottom + pad)
        }
        // Er det lite igjen, var «figuren» mest tekst: behold hele.
        if (bottom - top < figure.height * 0.4f) return figure
        return figure.copy(top = top, bottom = bottom)
    }

    /**
     * Skjevheten i grader (positiv = teksten heller nedover mot høyre) ut fra ordene på linjene i eller nær [near].
     * Bruker sidebildets størrelse for riktig vinkel. null når det ikke er nok linjer å måle på.
     */
    fun skewDegrees(lines: List<OcrLine>, near: Box, width: Int, height: Int): Float? {
        val margin = 0.03f
        fun inside(l: OcrLine) = l.centerY in (near.top - margin)..(near.bottom + margin) &&
            minOf(l.right, near.right) - maxOf(l.left, near.left) > 0f
        val slopes = mutableListOf<Float>()
        val candidates = lines.filter(::inside).ifEmpty { lines }
        for (line in candidates) {
            val words = line.elements.filter { it.text.isNotBlank() }
            if (words.size < 3) continue
            // Minste kvadraters linje gjennom ordenes midtpunkter, i piksler.
            val xs = words.map { (it.left + it.right) / 2f * width }
            val ys = words.map { (it.top + it.bottom) / 2f * height }
            val mx = xs.average().toFloat()
            val my = ys.average().toFloat()
            var sxx = 0f
            var sxy = 0f
            for (i in xs.indices) {
                sxx += (xs[i] - mx) * (xs[i] - mx)
                sxy += (xs[i] - mx) * (ys[i] - my)
            }
            if (sxx <= 0f) continue
            slopes += sxy / sxx
        }
        if (slopes.size < 2) return null
        val degrees = Math.toDegrees(atan(slopes.sorted()[slopes.size / 2].toDouble())).toFloat()
        return degrees.takeIf { abs(it) in MIN_SKEW..MAX_SKEW }
    }

    /**
     * Jevner ut lyset: et foto er ofte mørkere i den ene kanten. Bakgrunnen måles i et grovt rutenett
     * (median per rute, så tynne streker ikke teller), glattes, og hver piksel skaleres så bakgrunnen får
     * samme farge over hele figuren. Gjøres bare når figuren mest er bakgrunn med streker og tekst
     * (tegninger, diagrammer, faktabokser), ikke på fotografier.
     * @return true hvis lyset ble jevnet ut.
     */
    fun flattenIllumination(px: IntArray, width: Int, height: Int): Boolean {
        if (width < 32 || height < 32) return false
        val cell = maxOf(16, maxOf(width, height) / 24)
        val gw = (width + cell - 1) / cell
        val gh = (height + cell - 1) / cell
        val bg = Array(3) { FloatArray(gw * gh) }
        val buf = Array(3) { IntArray(cell * cell) }
        for (gy in 0 until gh) for (gx in 0 until gw) {
            var n = 0
            for (y in gy * cell until minOf(height, (gy + 1) * cell) step 2) for (x in gx * cell until minOf(width, (gx + 1) * cell) step 2) {
                val p = px[y * width + x]
                buf[0][n] = (p shr 16) and 0xFF; buf[1][n] = (p shr 8) and 0xFF; buf[2][n] = p and 0xFF
                n++
            }
            for (c in 0..2) {
                val sorted = buf[c].copyOf(n).also { it.sort() }
                bg[c][gy * gw + gx] = sorted[n / 2].toFloat()
            }
        }
        // Glatt rutenettet så overgangene blir myke (3×3).
        repeat(1) {
            for (c in 0..2) {
                val src = bg[c].copyOf()
                for (gy in 0 until gh) for (gx in 0 until gw) {
                    var sum = 0f; var k = 0
                    for (dy in -1..1) for (dx in -1..1) {
                        val yy = gy + dy; val xx = gx + dx
                        if (yy in 0 until gh && xx in 0 until gw) { sum += src[yy * gw + xx]; k++ }
                    }
                    bg[c][gy * gw + gx] = sum / k
                }
            }
        }
        fun at(c: Int, x: Int, y: Int): Float {
            // Bilineær mellom rutenes midtpunkter, og lineært videre ut til kantene (lyset endrer seg jevnt).
            val fx = (x + 0.5f) / cell - 0.5f
            val fy = (y + 0.5f) / cell - 0.5f
            val x0 = kotlin.math.floor(fx).toInt().coerceIn(0, maxOf(0, gw - 2))
            val y0 = kotlin.math.floor(fy).toInt().coerceIn(0, maxOf(0, gh - 2))
            val x1 = minOf(x0 + 1, gw - 1); val y1 = minOf(y0 + 1, gh - 1)
            val tx = (fx - x0).coerceIn(-0.6f, 1.6f); val ty = (fy - y0).coerceIn(-0.6f, 1.6f)
            val g = bg[c]
            val top = g[y0 * gw + x0] * (1 - tx) + g[y0 * gw + x1] * tx
            val bot = g[y1 * gw + x0] * (1 - tx) + g[y1 * gw + x1] * tx
            return (top * (1 - ty) + bot * ty).coerceAtLeast(1f)
        }
        // Mest bakgrunn? Ellers er det trolig et fotografi, og da røres det ikke.
        var near = 0; var total = 0
        for (y in 0 until height step 3) for (x in 0 until width step 3) {
            val p = px[y * width + x]
            val d = abs(((p shr 16) and 0xFF) - at(0, x, y)) + abs(((p shr 8) and 0xFF) - at(1, x, y)) + abs((p and 0xFF) - at(2, x, y))
            if (d < 45f) near++
            total++
        }
        if (near < total * 0.6f) return false
        // Målet: den lyseste fjerdedelen av bakgrunnen (det godt opplyste området).
        val target = FloatArray(3) { c -> bg[c].sorted()[(bg[c].size * 3 / 4).coerceAtMost(bg[c].size - 1)] }
        for (y in 0 until height) for (x in 0 until width) {
            val i = y * width + x
            val p = px[i]
            var r = (((p shr 16) and 0xFF) * target[0] / at(0, x, y)).roundToInt().coerceIn(0, 255)
            var g = (((p shr 8) and 0xFF) * target[1] / at(1, x, y)).roundToInt().coerceIn(0, 255)
            var b = ((p and 0xFF) * target[2] / at(2, x, y)).roundToInt().coerceIn(0, 255)
            // Bakgrunnsrens som i en skanner: svake nyanser nær bakgrunnen (gjennomslag fra baksiden,
            // papirstruktur) blir ren bakgrunn. Tydelige streker og flater er langt unna og beholdes.
            val d = abs(r - target[0]) + abs(g - target[1]) + abs(b - target[2])
            if (d < CLEAN_BG) {
                val t = (d / CLEAN_BG) * (d / CLEAN_BG)
                r = (target[0] + (r - target[0]) * t).roundToInt()
                g = (target[1] + (g - target[1]) * t).roundToInt()
                b = (target[2] + (b - target[2]) * t).roundToInt()
            }
            px[i] = (p and (0xFF shl 24)) or (r shl 16) or (g shl 8) or b
        }
        return true
    }

    /**
     * Gråtoneversjon for lesebrett uten farger (vanlig Kindle, 16 gråtoner): fargeforskjeller som forsvinner
     * i grått, blir til lysforskjeller. Er bakgrunnen farget (det finnes lysere streker enn bakgrunnen, f.eks.
     * hvite linjer på gult), blir bakgrunnen lys grå og de lyse strekene hvite; ellers blir bakgrunnen hvit.
     * Mørke streker strekkes mot svart. Gir gråverdier (0–255) per piksel.
     */
    fun einkGray(px: IntArray): IntArray {
        val lum = IntArray(px.size) { i ->
            val p = px[i]
            ((((p shr 16) and 0xFF) * 299 + ((p shr 8) and 0xFF) * 587 + (p and 0xFF) * 114) / 1000)
        }
        val hist = IntArray(256)
        for (l in lum) hist[l]++
        fun percentile(q: Float): Int {
            val limit = (lum.size * q).toInt()
            var seen = 0
            for (v in 0..255) { seen += hist[v]; if (seen > limit) return v }
            return 255
        }
        val black = percentile(0.01f)
        val bg = percentile(0.5f)
        val bright = percentile(0.995f)
        val colored = bright - bg > 15 && bg < 235
        val bgOut = if (colored) 205f else 250f
        return IntArray(lum.size) { i ->
            val l = lum[i]
            // Svake nyanser like under bakgrunnen (gjennomslag) blir bakgrunn; resten strekkes mot svart.
            val dead = (bg - black) * 0.2f
            val v = if (l <= bg) {
                if (l >= bg - dead) bgOut
                else (l - black).coerceAtLeast(0) * bgOut / (bg - dead - black).coerceAtLeast(1f)
            } else {
                bgOut + (l - bg) * (255f - bgOut) / (bright - bg).coerceAtLeast(1)
            }
            v.roundToInt().coerceIn(0, 255)
        }
    }

    /**
     * Har figuren en farget bakgrunn (faktaboks e.l.), beskjæres kantene der bakgrunnsfargen ikke dekker
     * mesteparten av raden/kolonnen: da forsvinner biter av siden rundt boksen og tomme hjørner etter rotasjon.
     * Gir [venstre, topp, høyre, bunn] i piksler (høyre/bunn eksklusive). Figurer på vanlig papir endres ikke.
     */
    fun backgroundBounds(px: IntArray, width: Int, height: Int, paper: Int, maxTrim: Float = 0.1f): IntArray {
        val full = intArrayOf(0, 0, width, height)
        if (width < 20 || height < 20) return full
        // Bakgrunnen: vanligste (grovt avrundede) farge i midtpartiet.
        val counts = HashMap<Int, Int>()
        for (y in height / 4 until height * 3 / 4 step 2) for (x in width / 4 until width * 3 / 4 step 2) {
            val p = px[y * width + x]
            val q = (((p shr 16) and 0xFF) / 16 shl 8) or (((p shr 8) and 0xFF) / 16 shl 4) or ((p and 0xFF) / 16)
            counts[q] = (counts[q] ?: 0) + 1
        }
        val mode = counts.maxByOrNull { it.value }?.key ?: return full
        val bg = intArrayOf((mode shr 8) * 16 + 8, ((mode shr 4) and 0xF) * 16 + 8, (mode and 0xF) * 16 + 8)
        fun dist(p: Int, c: IntArray) =
            abs(((p shr 16) and 0xFF) - c[0]) + abs(((p shr 8) and 0xFF) - c[1]) + abs((p and 0xFF) - c[2])
        val paperRgb = intArrayOf((paper shr 16) and 0xFF, (paper shr 8) and 0xFF, paper and 0xFF)
        // Bakgrunn som ligner papiret: vanlig figur, ingen boks å beskjære til.
        if (dist(0xFF shl 24 or (bg[0] shl 16) or (bg[1] shl 8) or bg[2], paperRgb) < 60) return full
        // Papir rundt boksen (eller tomme hjørner): lyst og nesten uten farge, eller nær papirfargen.
        fun isPaper(p: Int): Boolean {
            val r = (p shr 16) and 0xFF; val g = (p shr 8) and 0xFF; val b = p and 0xFF
            return dist(p, paperRgb) < 45 || (minOf(r, g, b) > 180 && maxOf(r, g, b) - minOf(r, g, b) < 25)
        }
        fun rowPaper(y: Int) = (0 until width step 2).count { isPaper(px[y * width + it]) } * 2f / width
        fun colPaper(x: Int) = (0 until height step 2).count { isPaper(px[it * width + x]) } * 2f / height
        // Forsiktig: heller litt side igjen i et hjørne enn å kutte innhold når boksen er fotografert på skrå.
        val limit = 0.4f
        var top = 0
        while (top < height * maxTrim && rowPaper(top) > limit) top++
        var bottom = height
        while (bottom > height * (1 - maxTrim) && rowPaper(bottom - 1) > limit) bottom--
        var left = 0
        while (left < width * maxTrim && colPaper(left) > limit) left++
        var right = width
        while (right > width * (1 - maxTrim) && colPaper(right - 1) > limit) right--
        return intArrayOf(left, top, right, bottom)
    }

    /**
     * Fargekorrigering av ARGB-piksler (endres på stedet):
     * papirfargen skaleres til nesten hvitt per kanal (fjerner fargestikk og grått lys),
     * svartpunktet strekkes, og en mild uskarp maske gjør strekene skarpere.
     */
    fun enhance(px: IntArray, width: Int, height: Int, paper: Int) {
        val target = 248f
        fun gain(c: Int) = (target / c.coerceAtLeast(1)).coerceIn(1f, 1.8f)
        val gr = gain((paper shr 16) and 0xFF)
        val gg = gain((paper shr 8) and 0xFF)
        val gb = gain(paper and 0xFF)

        // Svartpunkt: 1 % mørkeste piksler (etter hvitbalansen).
        val hist = IntArray(256)
        for (p in px) {
            val l = (((p shr 16) and 0xFF) * gr * 0.299f + ((p shr 8) and 0xFF) * gg * 0.587f + (p and 0xFF) * gb * 0.114f)
            hist[l.roundToInt().coerceIn(0, 255)]++
        }
        var black = 0
        var seen = 0
        val limit = px.size / 100
        while (black < 255 && seen + hist[black] <= limit) { seen += hist[black]; black++ }
        black = black.coerceAtMost(40)
        val scale = 255f / (255f - black)
        fun level(v: Float) = ((v - black) * scale).coerceIn(0f, 255f)

        val r = FloatArray(px.size)
        val g = FloatArray(px.size)
        val b = FloatArray(px.size)
        for (i in px.indices) {
            val p = px[i]
            r[i] = level(((p shr 16) and 0xFF) * gr)
            g[i] = level(((p shr 8) and 0xFF) * gg)
            b[i] = level((p and 0xFF) * gb)
        }
        val amount = 0.35f
        for (y in 0 until height) for (x in 0 until width) {
            val i = y * width + x
            val (sr, sg, sb) = if (x in 1 until width - 1 && y in 1 until height - 1) {
                var ar = 0f; var ag = 0f; var ab = 0f
                for (dy in -1..1) for (dx in -1..1) {
                    val j = i + dy * width + dx
                    ar += r[j]; ag += g[j]; ab += b[j]
                }
                Triple(
                    r[i] + amount * (r[i] - ar / 9f),
                    g[i] + amount * (g[i] - ag / 9f),
                    b[i] + amount * (b[i] - ab / 9f),
                )
            } else Triple(r[i], g[i], b[i])
            px[i] = (0xFF shl 24) or
                (sr.roundToInt().coerceIn(0, 255) shl 16) or
                (sg.roundToInt().coerceIn(0, 255) shl 8) or
                sb.roundToInt().coerceIn(0, 255)
        }
    }
}
