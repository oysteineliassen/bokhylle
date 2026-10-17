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
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import no.oystein.bokhylle.core.BlockType
import no.oystein.bokhylle.core.ReadingFlow
import no.oystein.bokhylle.core.TocLinks

enum class TextKind { HEADING, PARAGRAPH, CAPTION }

/** Det som vises på én skjermside. [block] er indeksen i lesestrømmen, [start] der utsnittet begynner i blokkens tekst. */
sealed class ScreenItem {
    data class Text(val text: AnnotatedString, val kind: TextKind, val block: Int, val start: Int) : ScreenItem()
    data class Box(val title: String?, val text: AnnotatedString, val continued: Boolean, val block: Int, val start: Int) : ScreenItem()
    data class Figure(val image: String?, val description: String?, val height: Float, val page: Int) : ScreenItem()
    data object Empty : ScreenItem()
    /** Sider som ikke er lagt inn ennå (vises som egen skjermside der de mangler). */
    data class Missing(val label: String, val count: Int) : ScreenItem()
}

/** Én skjermside: innholdet og boksidene (indekser) den viser tekst fra. */
data class Screen(val items: List<ScreenItem>, val pages: List<Int>) {
    /** Boksiden skjermsiden begynner på. */
    val pageIndex get() = pages.first()

    /** Har skjermsiden posisjonen [pos] i blokk [block] i lesestrømmen? */
    fun contains(block: Int, pos: Int): Boolean = items.any {
        when (it) {
            is ScreenItem.Text -> it.block == block && pos >= it.start && pos <= it.start + it.text.length
            is ScreenItem.Box -> it.block == block && pos >= it.start && pos <= it.start + it.text.length
            else -> false
        }
    }
}

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
 * Deler lesestrømmen (boksidene etter hverandre, se [ReadingFlow]) i skjermsider som får plass uten scrolling.
 * Avsnitt og faktabokser deles mellom linjer, figurer flyttes til neste skjermside hvis de ikke får plass, og
 * hovedkapitler begynner på ny skjermside.
 */
class Paginator(
    private val measurer: TextMeasurer,
    private val density: Density,
    private val styles: ReaderStyles,
    private val width: Int,
    private val height: Int,
    private val onFootnote: (pageIndex: Int, number: String) -> Unit,
    /** Trykk på en oppføring i innholdsfortegnelsen: sidetallet den viser til. */
    private val onPageLink: (page: Int) -> Unit = {},
) {
    private val spacing = styles.spacing(density)
    private val boxPad = styles.boxPadding(density)

    fun paginate(flow: List<ReadingFlow.Block>, onEach: () -> Unit = {}): List<Screen> {
        val screens = mutableListOf<Screen>()
        var items = mutableListOf<ScreenItem>()
        val pages = linkedSetOf<Int>()
        var used = 0f
        fun newScreen() {
            if (items.isEmpty()) return
            screens += Screen(items, pages.toList().sorted())
            items = mutableListOf()
            pages.clear()
            used = 0f
        }
        fun gap() = if (items.isEmpty()) 0f else spacing

        for ((bi, b) in flow.withIndex()) {
            onEach()
            if (b.breakBefore) newScreen()
            val missingLabel = b.missingLabel
            if (missingLabel != null) {
                items += ScreenItem.Missing(missingLabel, b.missingCount)
                pages += b.page
                newScreen()
                continue
            }
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
                    items += ScreenItem.Figure(b.image, b.description, h, b.page)
                    pages += b.page
                }
                BlockType.BOX -> {
                    val full = annotate(b)
                    var start = 0
                    var title = b.title
                    var continued = false
                    while (true) {
                        val text = full.subSequence(start, full.length)
                        val inner = width - 2 * boxPad
                        val titleH = title?.let { measure(AnnotatedString(it), styles.boxTitle, inner).size.height + spacing / 2 } ?: 0f
                        val avail = height - used - gap() - 2 * boxPad - titleH
                        val layoutResult = measurer.measure(text, styles.boxText, constraints = Constraints(maxWidth = inner.toInt()))
                        if (layoutResult.size.height <= avail) {
                            used += gap() + 2 * boxPad + titleH + layoutResult.size.height
                            items += ScreenItem.Box(title, text, continued, bi, start)
                            pages += b.pagesIn(start, full.length)
                            break
                        }
                        val lines = fittingLines(layoutResult, avail)
                        if (lines == 0) {
                            if (items.isEmpty()) { items += ScreenItem.Box(title, text, continued, bi, start); pages += b.pagesIn(start, full.length); newScreen(); break }
                            newScreen(); continue
                        }
                        val end = layoutResult.getLineEnd(lines - 1)
                        items += ScreenItem.Box(title, text.subSequence(0, end), continued, bi, start)
                        pages += b.pagesIn(start, start + end)
                        newScreen()
                        start = skipSpace(full, start + end)
                        if (start >= full.length) break
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
                    val full = annotate(b)
                    if (full.isEmpty()) continue
                    var start = 0
                    // En overskrift nederst på en skjermside flyttes til neste, sammen med teksten under.
                    if (kind == TextKind.HEADING && used > height * 0.75f) newScreen()
                    while (true) {
                        val text = full.subSequence(start, full.length)
                        val style = styles.of(kind)
                        val layoutResult = measurer.measure(text, style, constraints = Constraints(maxWidth = width))
                        val avail = height - used - gap()
                        if (layoutResult.size.height <= avail) {
                            used += gap() + layoutResult.size.height
                            items += ScreenItem.Text(text, kind, bi, start)
                            pages += b.pagesIn(start, full.length)
                            break
                        }
                        val lines = fittingLines(layoutResult, avail)
                        if (lines == 0) {
                            if (items.isEmpty()) { items += ScreenItem.Text(text, kind, bi, start); pages += b.pagesIn(start, full.length); newScreen(); break }
                            newScreen(); continue
                        }
                        val end = layoutResult.getLineEnd(lines - 1)
                        items += ScreenItem.Text(text.subSequence(0, end), kind, bi, start)
                        pages += b.pagesIn(start, start + end)
                        newScreen()
                        start = skipSpace(full, start + end)
                        if (start >= full.length) break
                    }
                }
            }
        }
        newScreen()
        if (screens.isEmpty()) screens += Screen(listOf(ScreenItem.Empty), listOf(0))
        return screens
    }

    private fun skipSpace(text: AnnotatedString, from: Int): Int {
        var i = from
        while (i < text.length && text[i].isWhitespace()) i++
        return i
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

    /**
     * Blokkens tekst med fotnotereferanser som hevet skrift som kan trykkes på. I en innholdsfortegnelse blir hver
     * oppføring en lenke til siden den viser til (sidetallet understrekes).
     */
    fun annotate(b: ReadingFlow.Block): AnnotatedString = AnnotatedString.Builder(b.text).apply {
        for (n in b.notes) {
            addLink(LinkAnnotation.Clickable("fotnote:${n.number}") { onFootnote(n.page, n.number) }, n.start, n.end)
            addStyle(SpanStyle(baselineShift = BaselineShift.Superscript, fontSize = 0.7.em), n.start, n.end)
        }
        if (b.toc) {
            for (e in TocLinks.entries(b.text)) {
                addLink(LinkAnnotation.Clickable("side:${e.page}") { onPageLink(e.page) }, e.range.first, e.range.last + 1)
                addStyle(SpanStyle(textDecoration = TextDecoration.Underline), e.numberStart, e.range.last + 1)
            }
        }
    }.toAnnotatedString()
}
