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
     * Jevner ut lyset: et foto er ofte mørkere i den ene kanten. Bakgrunnen (papiret, eller bakgrunnen i en
     * faktaboks) måles i et grovt rutenett (median per rute). Bare ruter med bakgrunnsfargen brukes; ruter
     * dekket av en fargeflate (f.eks. en stor sol) får lyset fra bakgrunnen rundt, så flaten beholder fargen.
     * Hver piksel lysnes eller mørknes like mye i alle kanaler (ingen fargestikk). Gjøres ikke når det ikke
     * finnes én tydelig bakgrunn (fotografier).
     * @return true hvis lyset ble jevnet ut.
     */
    fun flattenIllumination(px: IntArray, width: Int, height: Int): Boolean {
        if (width < 32 || height < 32) return false
        val cell = maxOf(16, maxOf(width, height) / 24)
        val gw = (width + cell - 1) / cell
        val gh = (height + cell - 1) / cell
        val n = gw * gh
        val bg = Array(3) { FloatArray(n) }
        val buf = Array(3) { IntArray(cell * cell) }
        for (gy in 0 until gh) for (gx in 0 until gw) {
            var k = 0
            for (y in gy * cell until minOf(height, (gy + 1) * cell) step 2) for (x in gx * cell until minOf(width, (gx + 1) * cell) step 2) {
                val p = px[y * width + x]
                buf[0][k] = (p shr 16) and 0xFF; buf[1][k] = (p shr 8) and 0xFF; buf[2][k] = p and 0xFF
                k++
            }
            for (c in 0..2) bg[c][gy * gw + gx] = buf[c].copyOf(k).also { it.sort() }[k / 2].toFloat()
        }
        fun lum(i: Int) = bg[0][i] * 0.299f + bg[1][i] * 0.587f + bg[2][i] * 0.114f
        // Bakgrunnsfargen: den vanligste rutefargen, sammenlignet uavhengig av lysstyrke (kromatisitet + grov lys).
        fun chroma(i: Int): Int {
            val l = lum(i).coerceAtLeast(1f)
            val cr = (bg[0][i] / l * 8).roundToInt().coerceIn(0, 31)
            val cb = (bg[2][i] / l * 8).roundToInt().coerceIn(0, 31)
            return (cr shl 5) or cb
        }
        val mode = (0 until n).groupingBy(::chroma).eachCount().maxByOrNull { it.value }?.key ?: return false
        val isBg = BooleanArray(n) { i -> abs(chroma(i) - mode) <= 1 || abs((chroma(i) shr 5) - (mode shr 5)) + abs((chroma(i) and 31) - (mode and 31)) <= 1 }
        // Også lysstyrken må ligge i bakgrunnens område (en mørk flate med samme fargetone er ikke bakgrunn).
        val bgLums = (0 until n).filter { isBg[it] }.map(::lum).sorted()
        if (bgLums.size < n * 0.4f) return false
        val medianLum = bgLums[bgLums.size / 2]
        for (i in 0 until n) if (isBg[i] && lum(i) < medianLum * 0.55f) isBg[i] = false
        val target = bgLums[(bgLums.size * 9 / 10).coerceAtMost(bgLums.size - 1)]
        // Ruter uten bakgrunn fylles fra nabo-rutene med bakgrunn, utover til alt er fylt.
        val filled = isBg.copyOf()
        var remaining = filled.count { !it }
        var guard = gw + gh
        while (remaining > 0 && guard-- > 0) {
            val next = filled.copyOf()
            for (gy in 0 until gh) for (gx in 0 until gw) {
                val i = gy * gw + gx
                if (filled[i]) continue
                var sum = FloatArray(3); var k = 0
                for (dy in -1..1) for (dx in -1..1) {
                    val yy = gy + dy; val xx = gx + dx
                    if (yy !in 0 until gh || xx !in 0 until gw) continue
                    val j = yy * gw + xx
                    if (!filled[j]) continue
                    for (c in 0..2) sum[c] += bg[c][j]
                    k++
                }
                if (k > 0) { for (c in 0..2) bg[c][i] = sum[c] / k; next[i] = true; remaining-- }
            }
            System.arraycopy(next, 0, filled, 0, n)
        }
        // Glatt rutenettet så overgangene blir myke (3×3).
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
        for (y in 0 until height) for (x in 0 until width) {
            val i = y * width + x
            val p = px[i]
            val br = at(0, x, y); val bgc = at(1, x, y); val bb = at(2, x, y)
            // Samme forsterkning i alle kanaler: lyset jevnes ut uten at fargene forskyves.
            val gain = (target / (br * 0.299f + bgc * 0.587f + bb * 0.114f)).coerceIn(0.8f, 2f)
            var r = (((p shr 16) and 0xFF) * gain).coerceIn(0f, 255f)
            var g = (((p shr 8) and 0xFF) * gain).coerceIn(0f, 255f)
            var b = ((p and 0xFF) * gain).coerceIn(0f, 255f)
            // Bakgrunnsrens som i en skanner: svake nyanser nær bakgrunnen (gjennomslag fra baksiden,
            // papirstruktur) blir ren bakgrunn. Tydelige streker og flater er langt unna og beholdes.
            val tr = (br * gain).coerceAtMost(255f); val tg = (bgc * gain).coerceAtMost(255f); val tb = (bb * gain).coerceAtMost(255f)
            val d = abs(r - tr) + abs(g - tg) + abs(b - tb)
            if (d < CLEAN_BG) {
                val t = (d / CLEAN_BG) * (d / CLEAN_BG)
                r = tr + (r - tr) * t; g = tg + (g - tg) * t; b = tb + (b - tb) * t
            }
            px[i] = (p and (0xFF shl 24)) or (r.roundToInt().coerceIn(0, 255) shl 16) or (g.roundToInt().coerceIn(0, 255) shl 8) or b.roundToInt().coerceIn(0, 255)
        }
        return true
    }

    /**
     * Strammer inn tekstblokkenes rammer (fra Claude eller ML Kit) til OCR-linjene som faktisk hører til teksten
     * deres. En etikett rett under en figur (f.eks. «Negativ side –») kan ligge innenfor bildetekstens ramme uten å
     * være en del av bildeteksten; da regnes den ikke som tekst, og [expandFigure] kan ta den med i figuren.
     * Linjene sammenlignes med blokkens tekst på bokstavtrekanter, så feillesinger (ø/o o.l.) tåles.
     */
    fun tightenTextBoxes(texts: List<Pair<Box, String>>, ocrLines: List<OcrLine>): List<Box> {
        fun norm(s: String) = s.lowercase().map { c ->
            when (c) { 'ø', 'ö' -> 'o'; 'æ', 'ä' -> 'a'; 'å' -> 'a'; else -> c }
        }.filter { it.isLetterOrDigit() }.joinToString("")
        fun grams(s: String) = (0..s.length - 3).map { s.substring(it, it + 3) }.toSet()
        return texts.map { (box, text) ->
            val textGrams = grams(norm(text))
            if (textGrams.isEmpty()) return@map box
            val inside = ocrLines.filter { l ->
                val w = minOf(l.right, box.right) - maxOf(l.left, box.left)
                val h = minOf(l.bottom, box.bottom) - maxOf(l.top, box.top)
                w > 0f && h > 0f && w * h > 0.3f * (l.right - l.left) * (l.bottom - l.top)
            }
            if (inside.isEmpty()) return@map box
            val belongs = inside.filter { l ->
                val g = grams(norm(l.text))
                // Korte linjer kan ikke avgjøres; de regnes som en del av teksten (som før).
                g.size < 3 || g.count { it in textGrams } >= 0.6f * g.size
            }
            // Hører ingen linjer til (f.eks. dårlig OCR), beholdes rammen.
            if (belongs.isEmpty()) return@map box
            belongs.map { Box(it.left, it.top, it.right, it.bottom) }.reduce { acc, b -> acc.union(b) }
        }
    }

    /**
     * Utvider figurens område så det som hører til figuren kommer med: korte tekstlinjer rett utenfor
     * (etiketter, akser, piler med tekst) som ikke er en del av brødteksten, og litt luft på hver side
     * (strektegning som stikker ut). Utvidelsen stopper ved tekstblokker, så brødtekst ikke kommer med.
     */
    fun expandFigure(figure: Box, ocrLines: List<OcrLine>, texts: List<Box>, pad: Float = 0.025f, reach: Float = 0.05f): Box {
        fun overlaps(a: Box, b: Box) = minOf(a.right, b.right) > maxOf(a.left, b.left) && minOf(a.bottom, b.bottom) > maxOf(a.top, b.top)
        fun area(b: Box) = maxOf(0f, b.width) * maxOf(0f, b.height)
        fun overlapShare(line: Box, t: Box): Float {
            val w = minOf(line.right, t.right) - maxOf(line.left, t.left)
            val h = minOf(line.bottom, t.bottom) - maxOf(line.top, t.top)
            return if (w <= 0f || h <= 0f) 0f else w * h / area(line).coerceAtLeast(1e-6f)
        }
        val others = texts.filter { !overlaps(it, figure) || overlapShare(it, figure) < 0.5f }
        var box = figure
        // Etiketter: OCR-linjer nær figuren som ikke hører til noen tekstblokk.
        val labels = ocrLines.map { Box(it.left, it.top, it.right, it.bottom) }
            .filter { l -> others.none { overlapShare(l, it) > 0.3f } }
        var changed = true
        var rounds = 0
        while (changed && rounds++ < 6) {
            changed = false
            for (l in labels) {
                if (overlaps(l, box)) continue
                val gapX = maxOf(0f, maxOf(box.left - l.right, l.left - box.right))
                val gapY = maxOf(0f, maxOf(box.top - l.bottom, l.top - box.bottom))
                if (gapX <= reach && gapY <= reach) {
                    val grown = box.union(l)
                    // Aldri inn over en tekstblokk.
                    if (others.none { overlaps(grown, it) && !overlaps(box, it) }) { box = grown; changed = true }
                }
            }
        }
        // Litt luft på hver side, men ikke inn i tekst.
        fun clampSide(candidate: Box): Box = if (others.any { overlaps(candidate, it) && !overlaps(box, it) }) box else candidate
        box = clampSide(box.copy(left = (box.left - pad).coerceAtLeast(0f)))
        box = clampSide(box.copy(right = (box.right + pad).coerceAtMost(1f)))
        box = clampSide(box.copy(top = (box.top - pad).coerceAtLeast(0f)))
        box = clampSide(box.copy(bottom = (box.bottom + pad).coerceAtMost(1f)))
        return box
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
        // En farget boks dekker mesteparten av figuren. En fargeflate midt i en tegning (f.eks. en sol) gjør
        // ikke det, og da skal ingenting beskjæres bort (etiketter og strektegning rundt ville forsvunnet).
        var covered = 0; var sampled = 0
        for (i in px.indices step 7) { if (dist(px[i], bg) < 70) covered++; sampled++ }
        if (covered < sampled * 0.55f) return full
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
