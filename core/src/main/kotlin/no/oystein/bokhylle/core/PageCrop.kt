package no.oystein.bokhylle.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class Point(val x: Float, val y: Float)

/** Sidens fire hjørner, normalisert 0–1 i bildet: øverst til venstre, øverst til høyre, nederst til høyre, nederst til venstre. */
data class Quad(val tl: Point, val tr: Point, val br: Point, val bl: Point) {
    val points: List<Point> get() = listOf(tl, tr, br, bl)

    /** Lagres som «x,y,x,y,x,y,x,y». */
    fun encode(): String = points.joinToString(",") { "${it.x},${it.y}" }

    /** Areal som andel av bildet (skolisseformelen). */
    fun area(): Float {
        val p = points
        var s = 0f
        for (i in p.indices) {
            val a = p[i]
            val b = p[(i + 1) % p.size]
            s += a.x * b.y - b.x * a.y
        }
        return abs(s) / 2f
    }

    fun isConvex(): Boolean {
        val p = points
        var sign = 0
        for (i in p.indices) {
            val a = p[i]
            val b = p[(i + 1) % 4]
            val c = p[(i + 2) % 4]
            val cross = (b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x)
            val s = if (cross > 0) 1 else if (cross < 0) -1 else 0
            if (s != 0) {
                if (sign != 0 && s != sign) return false
                sign = s
            }
        }
        return sign != 0
    }

    companion object {
        val FULL = Quad(Point(0f, 0f), Point(1f, 0f), Point(1f, 1f), Point(0f, 1f))

        fun decode(text: String?): Quad? {
            val v = text?.split(',')?.mapNotNull { it.trim().toFloatOrNull() } ?: return null
            if (v.size != 8) return null
            return Quad(Point(v[0], v[1]), Point(v[2], v[3]), Point(v[4], v[5]), Point(v[6], v[7]))
        }
    }
}

/**
 * Finner papirflaten i et foto av en bokside: den største lyse flaten rundt midten av bildet.
 * Er en del av nabosiden med, kuttes den ved den mørke skyggen i midtfalsen.
 * Arbeider på et lite gråtonebilde (f.eks. 400 px), så det går raskt.
 */
object PageCropDetector {
    /** @param lum lysstyrke 0–255, rad for rad. @return hjørnene, eller null hvis ingen tydelig side ble funnet. */
    fun detect(lum: IntArray, width: Int, height: Int): Quad? = detectPages(lum, width, height).firstOrNull()

    /**
     * Finner én side, eller to når bildet er et oppslag (bredere enn høyt, med en mørk rygg på midten).
     * Ved oppslag kommer venstre side først.
     */
    fun detectPages(lum: IntArray, width: Int, height: Int): List<Quad> {
        if (width < 8 || height < 8 || lum.size != width * height) return emptyList()
        val threshold = otsu(lum)
        val bright = BooleanArray(lum.size) { lum[it] > threshold }

        val seed = findSeed(bright, width, height) ?: return emptyList()
        val component = floodFill(bright, width, height, seed)

        var minX = width; var maxX = -1; var minY = height; var maxY = -1
        for (i in component.indices) if (component[i]) {
            val x = i % width; val y = i / width
            if (x < minX) minX = x; if (x > maxX) maxX = x
            if (y < minY) minY = y; if (y > maxY) maxY = y
        }
        if (maxX < 0) return emptyList()

        // Oppslag: papirflaten er tydelig bredere enn høy, og ryggen ligger omtrent midt i.
        val ratio = (maxX - minX + 1).toFloat() / (maxY - minY + 1)
        if (ratio > 1.05f) {
            val profile = columnProfile(lum, component, width, minX, maxX, minY, maxY)
            val span = profile.size
            val paper = profile.sorted()[span * 3 / 4]
            val middle = (span * 30 / 100) until (span * 70 / 100)
            val spine = middle.minByOrNull { profile[it] }
            val clear = spine != null && profile[spine] < paper * 0.9f
            if (spine != null && (clear || ratio > 1.2f)) {
                val x = minX + spine
                val left = corners(component, width, height, minX, x - 1, minY, maxY)
                val right = corners(component, width, height, x + 1, maxX, minY, maxY)
                val pages = listOfNotNull(left, right).filter { it.area() > 0.08f && it.isConvex() }
                if (pages.size == 2) return pages
            }
        }

        // Oppslag med mørk rygg: de to sidene er hver sin lyse flate. Se etter en like stor flate rett ved siden av.
        neighbourPage(bright, width, height, component, minX, maxX, minY, maxY)?.let { other ->
            val (otherComp, oMinX, oMaxX, oMinY, oMaxY) = other
            val here = corners(component, width, height, minX, maxX, minY, maxY)
            val there = corners(otherComp, width, height, oMinX, oMaxX, oMinY, oMaxY)
            if (here != null && there != null && here.isConvex() && there.isConvex()) {
                return if (oMinX < minX) listOf(there, here) else listOf(here, there)
            }
        }

        // Én side: kutt bort nabosiden ved midtfalsen.
        val gutter = findGutters(lum, component, width, minX, maxX, minY, maxY)
        val quad = corners(component, width, height, gutter.first ?: minX, gutter.second ?: maxX, minY, maxY) ?: return emptyList()
        if (quad.area() < 0.2f || !quad.isConvex()) return emptyList()
        return listOf(quad)
    }

    private data class Region(val comp: BooleanArray, val minX: Int, val maxX: Int, val minY: Int, val maxY: Int)

    /**
     * En lys flate på samme høyde rett til venstre eller høyre for siden, adskilt av en smal mørk stripe,
     * som er omtrent like stor: den andre siden i et oppslag. Smale striper (kanten av nabosiden) teller ikke.
     */
    private fun neighbourPage(bright: BooleanArray, w: Int, h: Int, comp: BooleanArray, minX: Int, maxX: Int, minY: Int, maxY: Int): Region? {
        val pageW = maxX - minX + 1
        val pageH = maxY - minY + 1
        val maxGap = max(3, w * 8 / 100)
        for (dir in listOf(-1, 1)) {
            for (frac in listOf(2, 3, 4)) { // prøv noen høyder midt på siden
                val y = minY + pageH * frac / 6
                var x = if (dir < 0) minX - 1 else maxX + 1
                var steps = 0
                while (x in 0 until w && steps <= maxGap && (!bright[y * w + x] || comp[y * w + x])) { x += dir; steps++ }
                if (x !in 0 until w || steps > maxGap || !bright[y * w + x]) continue
                val other = floodFill(bright, w, h, y * w + x)
                var oMinX = w; var oMaxX = -1; var oMinY = h; var oMaxY = -1
                for (i in other.indices) if (other[i]) {
                    val ox = i % w; val oy = i / w
                    if (ox < oMinX) oMinX = ox; if (ox > oMaxX) oMaxX = ox
                    if (oy < oMinY) oMinY = oy; if (oy > oMaxY) oMaxY = oy
                }
                val oW = oMaxX - oMinX + 1
                val oH = oMaxY - oMinY + 1
                if (oW >= pageW * 0.6f && oW <= pageW * 1.6f && oH >= pageH * 0.8f && oH <= pageH * 1.25f) {
                    return Region(other, oMinX, oMaxX, oMinY, oMaxY)
                }
                break
            }
        }
        return null
    }

    /** Hjørnene er de ytterste punktene i diagonalretningene innenfor kolonnene [left, right]. */
    private fun corners(component: BooleanArray, width: Int, height: Int, left: Int, right: Int, minY: Int, maxY: Int): Quad? {
        if (right <= left) return null
        var tl = -1; var tr = -1; var br = -1; var bl = -1
        var tlV = Int.MAX_VALUE; var trV = Int.MIN_VALUE; var brV = Int.MIN_VALUE; var blV = Int.MAX_VALUE
        for (y in minY..maxY) {
            val row = y * width
            for (x in left..right) {
                if (!component[row + x]) continue
                val s = x + y
                val d = x - y
                if (s < tlV) { tlV = s; tl = row + x }
                if (s > brV) { brV = s; br = row + x }
                if (d > trV) { trV = d; tr = row + x }
                if (d < blV) { blV = d; bl = row + x }
            }
        }
        if (tl < 0) return null
        fun p(i: Int) = Point((i % width).toFloat() / (width - 1), (i / width).toFloat() / (height - 1))
        return Quad(p(tl), p(tr), p(br), p(bl))
    }

    /** Snittlysstyrke per kolonne over den midterste delen av papirflaten, litt utjevnet. */
    private fun columnProfile(lum: IntArray, comp: BooleanArray, w: Int, minX: Int, maxX: Int, minY: Int, maxY: Int): FloatArray {
        val span = maxX - minX + 1
        val y0 = minY + (maxY - minY) / 5
        val y1 = maxY - (maxY - minY) / 5
        val mean = FloatArray(span)
        for (i in 0 until span) {
            val x = minX + i
            var s = 0L; var n = 0
            for (y in y0..y1) { val k = y * w + x; if (comp[k]) { s += lum[k]; n++ } }
            mean[i] = if (n > 0) s.toFloat() / n else 0f
        }
        return FloatArray(span) { i ->
            var s = 0f; var n = 0
            for (j in max(0, i - 1)..min(span - 1, i + 1)) { s += mean[j]; n++ }
            s / n
        }
    }

    /** Terskel som skiller lyst papir fra mørk bakgrunn best mulig (Otsus metode). */
    internal fun otsu(lum: IntArray): Int {
        val hist = IntArray(256)
        for (v in lum) hist[v.coerceIn(0, 255)]++
        val total = lum.size.toDouble()
        var sum = 0.0
        for (i in 0..255) sum += i * hist[i].toDouble()
        var sumB = 0.0
        var wB = 0.0
        var best = 0.0
        var threshold = 127
        for (t in 0..255) {
            wB += hist[t]
            if (wB == 0.0) continue
            val wF = total - wB
            if (wF == 0.0) break
            sumB += t * hist[t].toDouble()
            val mB = sumB / wB
            val mF = (sum - sumB) / wF
            val between = wB * wF * (mB - mF) * (mB - mF)
            if (between > best) { best = between; threshold = t }
        }
        return threshold
    }

    /** Et lyst punkt nær midten; siden er nesten alltid midt i bildet. */
    private fun findSeed(bright: BooleanArray, w: Int, h: Int): Int? {
        val cx = w / 2; val cy = h / 2
        val maxR = min(w, h) / 4
        for (r in 0..maxR) {
            for (dy in -r..r) for (dx in -r..r) {
                if (max(abs(dx), abs(dy)) != r) continue
                val x = cx + dx; val y = cy + dy
                if (x in 0 until w && y in 0 until h && bright[y * w + x]) {
                    // Krev et lite lyst område, ikke ett enkelt lyst piksel i bakgrunnen.
                    var count = 0
                    for (yy in max(0, y - 2)..min(h - 1, y + 2)) for (xx in max(0, x - 2)..min(w - 1, x + 2)) if (bright[yy * w + xx]) count++
                    if (count >= 20) return y * w + x
                }
            }
        }
        return null
    }

    private fun floodFill(bright: BooleanArray, w: Int, h: Int, seed: Int): BooleanArray {
        val seen = BooleanArray(bright.size)
        val queue = IntArray(bright.size)
        var head = 0; var tail = 0
        queue[tail++] = seed; seen[seed] = true
        while (head < tail) {
            val i = queue[head++]
            val x = i % w; val y = i / w
            if (x > 0 && bright[i - 1] && !seen[i - 1]) { seen[i - 1] = true; queue[tail++] = i - 1 }
            if (x < w - 1 && bright[i + 1] && !seen[i + 1]) { seen[i + 1] = true; queue[tail++] = i + 1 }
            if (y > 0 && bright[i - w] && !seen[i - w]) { seen[i - w] = true; queue[tail++] = i - w }
            if (y < h - 1 && bright[i + w] && !seen[i + w]) { seen[i + w] = true; queue[tail++] = i + w }
        }
        // Tekst og bilder på siden gir hull; tett dem radvis mellom ytterpunktene så hjørnene blir riktige.
        val filled = seen.copyOf()
        for (y in 0 until h) {
            val row = y * w
            var first = -1; var last = -1
            for (x in 0 until w) if (seen[row + x]) { if (first < 0) first = x; last = x }
            if (first >= 0) for (x in first..last) filled[row + x] = true
        }
        return filled
    }

    /**
     * Ser etter en mørk, smal stripe (midtfalsen) i ytterste tredjedel på hver side av papirflaten.
     * @return (venstre kant, høyre kant) etter kutt, eller null der det ikke er noen fals.
     */
    private fun findGutters(lum: IntArray, comp: BooleanArray, w: Int, minX: Int, maxX: Int, minY: Int, maxY: Int): Pair<Int?, Int?> {
        val span = maxX - minX + 1
        if (span < 20) return null to null
        val smooth = columnProfile(lum, comp, w, minX, maxX, minY, maxY)
        val paper = smooth.sorted()[span * 3 / 4] // typisk lysstyrke på papiret
        val zone = span * 35 / 100
        fun valley(range: IntRange): Int? {
            val i = range.minByOrNull { smooth[it] } ?: return null
            if (smooth[i] > paper * 0.72f) return null
            // Må være lysere på begge sider av stripen (ikke bare en mørk kant ytterst).
            val l = (max(0, i - span / 15) until i).maxOfOrNull { smooth[it] } ?: return null
            val r = (i + 1..min(span - 1, i + span / 15)).maxOfOrNull { smooth[it] } ?: return null
            return if (l > smooth[i] * 1.15f && r > smooth[i] * 1.15f) i else null
        }
        val left = valley(2 until zone)?.let { minX + it + span / 50 }
        val right = valley(span - zone until span - 2)?.let { minX + it - span / 50 }
        return left to right
    }
}
