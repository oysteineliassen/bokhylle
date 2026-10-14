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
        black = black.coerceAtMost(70)
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
        val amount = 0.5f
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
