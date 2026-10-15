package no.oystein.bokhylle.ui.reader

import android.graphics.BitmapFactory
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import no.oystein.bokhylle.core.BlockType
import no.oystein.bokhylle.core.PageLayout

enum class TextKind { HEADING, PARAGRAPH, CAPTION }

/** Det som vises på én skjermside. */
sealed class ScreenItem {
    data class Text(val text: AnnotatedString, val kind: TextKind) : ScreenItem()
    data class Box(val title: String?, val text: AnnotatedString, val continued: Boolean) : ScreenItem()
    data class Figure(val image: String?, val description: String?, val height: Float) : ScreenItem()
    data object Empty : ScreenItem()
    /** Sider som ikke er lagt inn ennå (vises som egen skjermside der de mangler). */
    data class Missing(val label: String, val count: Int) : ScreenItem()
}

data class Screen(val pageIndex: Int, val part: Int, val items: List<ScreenItem>)

/** Stilene leseren bruker; deles av pagineringen og visningen, så målingene stemmer. */
class ReaderStyles(settings: ReaderSettings) {
    private val family = if (settings.serif) FontFamily.Serif else FontFamily.SansSerif
    val body = TextStyle(fontSize = settings.fontSize.sp, lineHeight = (settings.fontSize * settings.lineHeight).sp, fontFamily = family)
    val heading = body.copy(fontSize = (settings.fontSize * 1.3f).sp, lineHeight = (settings.fontSize * 1.3f * 1.25f).sp, fontWeight = FontWeight.Bold)
    val caption = body.copy(fontSize = (settings.fontSize * 0.85f).sp, lineHeight = (settings.fontSize * 0.85f * 1.35f).sp, fontStyle = FontStyle.Italic)
    val boxText = body.copy(fontSize = (settings.fontSize * 0.92f).sp, lineHeight = (settings.fontSize * 0.92f * settings.lineHeight).sp)
    val boxTitle = boxText.copy(fontWeight = FontWeight.Bold)

    fun of(kind: TextKind) = when (kind) {
        TextKind.HEADING -> heading
        TextKind.PARAGRAPH -> body
        TextKind.CAPTION -> caption
    }

    /** Luft mellom blokker og innvendig marg i faktabokser (px), ut fra skriftstørrelsen. */
    fun spacing(density: Density) = with(density) { (body.fontSize * 0.7f).toPx() }
    fun boxPadding(density: Density) = with(density) { (body.fontSize * 0.8f).toPx() }
}

/**
 * Deler hver bokside i skjermsider som får plass uten scrolling. Avsnitt og faktabokser deles mellom linjer,
 * figurer flyttes til neste skjermside hvis de ikke får plass.
 */
class Paginator(
    private val measurer: TextMeasurer,
    private val density: Density,
    private val styles: ReaderStyles,
    private val width: Int,
    private val height: Int,
    private val onFootnote: (pageIndex: Int, number: String) -> Unit,
) {
    private val spacing = styles.spacing(density)
    private val boxPad = styles.boxPadding(density)

    fun paginate(pageIndex: Int, layout: PageLayout): List<Screen> {
        val screens = mutableListOf<Screen>()
        var items = mutableListOf<ScreenItem>()
        var used = 0f
        fun newScreen() {
            screens += Screen(pageIndex, screens.size, items)
            items = mutableListOf()
            used = 0f
        }
        fun gap() = if (items.isEmpty()) 0f else spacing

        // En figur uten egen bildetekst får beskrivelsen som tekst under, så man slipper å åpne figuren for å lese den.
        val blocks = layout.blocks.filter { it.type != BlockType.FOOTNOTE }.let { list ->
            list.flatMapIndexed { i, b ->
                val described = b.type == BlockType.FIGURE && b.image != null && !b.description.isNullOrBlank() &&
                    list.getOrNull(i + 1)?.type != BlockType.CAPTION
                if (described) listOf(b, b.copy(type = BlockType.CAPTION, text = b.description!!, image = null)) else listOf(b)
            }
        }
        for (b in blocks) {
            when (b.type) {
                BlockType.FIGURE -> {
                    val aspect = imageAspect(b.image) ?: b.box?.let { if (it.height > 0) it.width / it.height else null } ?: 1.5f
                    val wanted = width / aspect
                    var h = wanted.coerceAtMost(height * 0.6f)
                    if (used + gap() + h > height) {
                        val left = height - used - gap()
                        if (left >= height * 0.3f) h = left else newScreen()
                    }
                    used += gap() + h
                    items += ScreenItem.Figure(b.image, b.description, h)
                }
                BlockType.BOX -> {
                    var text = annotate(b.text.replace("\n\n", "\n"), pageIndex)
                    var title = b.title
                    var continued = false
                    while (true) {
                        val inner = width - 2 * boxPad
                        val titleH = title?.let { measure(AnnotatedString(it), styles.boxTitle, inner).size.height + spacing / 2 } ?: 0f
                        val avail = height - used - gap() - 2 * boxPad - titleH
                        val layoutResult = measurer.measure(text, styles.boxText, constraints = Constraints(maxWidth = inner.toInt()))
                        if (layoutResult.size.height <= avail) {
                            used += gap() + 2 * boxPad + titleH + layoutResult.size.height
                            items += ScreenItem.Box(title, text, continued)
                            break
                        }
                        val lines = fittingLines(layoutResult, avail)
                        if (lines == 0) {
                            if (items.isEmpty()) { items += ScreenItem.Box(title, text, continued); newScreen(); break }
                            newScreen(); continue
                        }
                        val end = layoutResult.getLineEnd(lines - 1)
                        items += ScreenItem.Box(title, text.subSequence(0, end), continued)
                        newScreen()
                        text = text.subSequence(end, text.length).trimStart()
                        title = null
                        continued = true
                    }
                }
                else -> {
                    val kind = when (b.type) {
                        BlockType.HEADING -> TextKind.HEADING
                        BlockType.CAPTION -> TextKind.CAPTION
                        else -> TextKind.PARAGRAPH
                    }
                    var text = annotate(b.text, pageIndex)
                    if (text.isEmpty()) continue
                    // En overskrift nederst på en skjermside flyttes til neste, sammen med teksten under.
                    if (kind == TextKind.HEADING && used > height * 0.75f) newScreen()
                    while (true) {
                        val style = styles.of(kind)
                        val layoutResult = measurer.measure(text, style, constraints = Constraints(maxWidth = width))
                        val avail = height - used - gap()
                        if (layoutResult.size.height <= avail) {
                            used += gap() + layoutResult.size.height
                            items += ScreenItem.Text(text, kind)
                            break
                        }
                        val lines = fittingLines(layoutResult, avail)
                        if (lines == 0) {
                            if (items.isEmpty()) { items += ScreenItem.Text(text, kind); newScreen(); break }
                            newScreen(); continue
                        }
                        val end = layoutResult.getLineEnd(lines - 1)
                        items += ScreenItem.Text(text.subSequence(0, end), kind)
                        newScreen()
                        text = text.subSequence(end, text.length).trimStart()
                        if (text.isEmpty()) break
                    }
                }
            }
        }
        if (items.isNotEmpty() || screens.isEmpty()) {
            if (items.isEmpty()) items += ScreenItem.Empty
            newScreen()
        }
        return screens
    }

    private fun measure(text: AnnotatedString, style: TextStyle, width: Float) =
        measurer.measure(text, style, constraints = Constraints(maxWidth = width.toInt().coerceAtLeast(1)))

    private fun fittingLines(result: androidx.compose.ui.text.TextLayoutResult, avail: Float): Int {
        var n = 0
        while (n < result.lineCount && result.getLineBottom(n) <= avail) n++
        return n
    }

    private fun imageAspect(path: String?): Float? {
        if (path == null) return null
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, o)
        return if (o.outWidth > 0 && o.outHeight > 0) o.outWidth.toFloat() / o.outHeight else null
    }

    /** Fotnotereferanser «[^n]» blir hevet skrift som kan trykkes på. */
    fun annotate(text: String, pageIndex: Int): AnnotatedString = buildAnnotatedString {
        var last = 0
        for (m in PageLayout.FOOTNOTE_REF.findAll(text)) {
            append(text.substring(last, m.range.first))
            val number = m.groupValues[1]
            withLink(LinkAnnotation.Clickable("fotnote:$number") { onFootnote(pageIndex, number) }) {
                withStyle(SpanStyle(baselineShift = BaselineShift.Superscript, fontSize = 0.7.em)) { append(number) }
            }
            last = m.range.last + 1
        }
        append(text.substring(last))
    }
}

private fun AnnotatedString.trimStart(): AnnotatedString {
    var i = 0
    while (i < length && this[i].isWhitespace()) i++
    return if (i == 0) this else subSequence(i, length)
}
