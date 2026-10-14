package no.oystein.bokhylle.work

import android.graphics.Bitmap
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import no.oystein.bokhylle.BokhylleApp
import no.oystein.bokhylle.core.BlockType
import no.oystein.bokhylle.core.Json
import no.oystein.bokhylle.core.LayoutBuilder
import no.oystein.bokhylle.core.LayoutInput
import no.oystein.bokhylle.core.LayoutMarkup
import no.oystein.bokhylle.core.LayoutOps
import no.oystein.bokhylle.core.ManualRegion
import no.oystein.bokhylle.core.OcrPage
import no.oystein.bokhylle.core.OrderInput
import no.oystein.bokhylle.core.OrderResult
import no.oystein.bokhylle.core.PageLayout
import no.oystein.bokhylle.core.PageNumber
import no.oystein.bokhylle.core.PageNumberDetector
import no.oystein.bokhylle.core.PageOrdering
import no.oystein.bokhylle.core.obj
import no.oystein.bokhylle.data.Page
import no.oystein.bokhylle.data.PageImages
import no.oystein.bokhylle.data.sidetall
import no.oystein.bokhylle.image.ImageOps
import java.io.File

/**
 * Sorterer og rydder en hel bok etter at sidene er lest inn eller endret:
 * sidetall (Claude eller ML Kit), rekkefølge og flagg, sideoppsett med figurer og faktabokser, og søketekst.
 */
class BookProcessor(private val app: BokhylleApp) {
    private val db get() = app.db
    private val lock = Mutex()

    suspend fun rebuild(bookId: Long) = withContext(Dispatchers.Default) {
        lock.withLock { rebuildLocked(bookId) }
    }

    private suspend fun rebuildLocked(bookId: Long) {
        var pages = db.pages().forBook(bookId)
        if (pages.isEmpty()) return

        // 1. Sidetall: brukerens > Claudes > ML Kits.
        val ocr = pages.associate { it.id to OcrPage.decode(it.ocrLinjer) }
        val claude = pages.associate { it.id to claudeLayout(it) }
        val detected = pages.associate { p -> p.id to ocr[p.id]?.let { PageNumberDetector.detect(it.lines) } }
        pages = pages.map { p ->
            if (p.sidetallManuelt || p.status != Page.STATUS_FERDIG) p
            else p.copy(oppdagetSidetall = claude[p.id]?.pageNumber?.let(PageNumber::parse)?.label ?: detected[p.id]?.number?.label)
        }

        // 2. Rekkefølge og flagg.
        val result = analyze(pages)
        val byId = pages.associateBy { it.id }
        val ordered = result.order.mapNotNull { byId[it] }

        // 3. Sideoppsett i bokas rekkefølge (ML Kit trenger nabosidene for å finne løpende topptekst).
        val done = ordered.filter { it.status == Page.STATUS_FERDIG }
        val mlInputs = done.filter { claude[it.id] == null }.mapNotNull { p ->
            val o = ocr[p.id] ?: return@mapNotNull null
            LayoutInput(p.id, o, detected[p.id], ManualRegion.decode(p.manuelleOmrader))
        }
        val mlLayouts = LayoutBuilder.build(mlInputs)
        val layouts = LayoutBuilder.linkPages(
            done.mapNotNull { p ->
                val l = claude[p.id]?.let { LayoutOps.applyRegions(it, ManualRegion.decode(p.manuelleOmrader)) } ?: mlLayouts[p.id]
                l?.let { p.id to it }
            },
        ).toMap()

        // 4. Lagre bare det som er endret, og bare feltene sorteringen og oppsettet eier.
        var changed = 0
        ordered.forEachIndexed { index, p ->
            val flags = result.flags[p.id].orEmpty()
            val reasons = flags.joinToString(",") { it.name }
            val inferred = result.inferred[p.id]?.label
            val original = byId.getValue(p.id)
            val before = db.pages().get(p.id) ?: return@forEachIndexed
            if (before.sorteringsIndeks != index || before.flagget != flags.isNotEmpty() || before.flaggGrunner != reasons ||
                before.utledetSidetall != inferred || before.oppdagetSidetall != original.oppdagetSidetall
            ) {
                db.pages().updateOrder(p.id, index, flags.isNotEmpty(), reasons, inferred, original.oppdagetSidetall)
                changed++
            }
            val layout = layouts[p.id] ?: return@forEachIndexed
            val withFigures = withContext(Dispatchers.IO) { cropFigures(p, layout) }
            val plain = withFigures.plainText()
            val shown = p.redigertTekst?.let { LayoutMarkup.parse(it, withFigures).plainText() } ?: plain
            val json = withFigures.encode()
            val source = if (claude[p.id] != null) Page.KILDE_CLAUDE else Page.KILDE_MLKIT
            if (before.layoutJson != json || before.sokeTekst != Page.searchText(shown) || before.ocrKilde != source) {
                db.pages().updateLayout(p.id, json, plain, Page.searchText(shown), source)
                changed++
            }
        }
        if (changed > 0) db.books().touch(bookId)
        Log.d(TAG, "bok $bookId: ${pages.size} sider, $changed endringer, ${result.missing.size} hull")
    }

    private fun claudeLayout(p: Page): PageLayout? = p.blocksJson?.let { json ->
        runCatching { Json.parse(json).obj()?.let { PageLayout.fromJson(it, PageLayout.SOURCE_CLAUDE) } }.getOrNull()
    }

    /** Klipper ut figurene fra sidebildet; filnavnet er fast for samme plassering, så de gjenbrukes. */
    private fun cropFigures(page: Page, layout: PageLayout): PageLayout {
        val old = PageImages.figurePaths(page).toSet()
        val keep = mutableSetOf<String>()
        var bitmap: Bitmap? = null
        try {
            val blocks = layout.blocks.mapIndexed { i, b ->
                val box = b.box
                if (b.type != BlockType.FIGURE || box == null) return@mapIndexed b
                val key = box.toList().joinToString("_") { "%.3f".format(java.util.Locale.ROOT, it) }.hashCode().toUInt().toString(16)
                val file = File(app.files.bookDir(page.bookId), "figur-${page.id}-$i-$key.webp")
                if (!file.exists()) {
                    if (bitmap == null) bitmap = ImageOps.decode(page.bildeSti)
                    val crop = bitmap?.let { ImageOps.crop(it, box) } ?: return@mapIndexed b
                    ImageOps.save(crop, file, ImageOps.FIGURE_QUALITY)
                    crop.recycle()
                }
                keep += file.path
                b.copy(image = file.path)
            }
            (old - keep).forEach { app.files.delete(it) }
            return layout.copy(blocks = blocks)
        } finally {
            bitmap?.recycle()
        }
    }

    companion object {
        private const val TAG = "Bokhylle"

        /** Sorteringen og flaggene for sidene slik de er nå (brukes også av kontrollskjermen for «manglende sider»). */
        fun analyze(pages: List<Page>): OrderResult = PageOrdering.order(
            pages.map { p ->
                OrderInput(
                    id = p.id,
                    batch = p.importBatch,
                    importIndex = p.importIndeks,
                    captureTime = p.fotoTidspunkt,
                    number = p.sidetall,
                    manual = p.manuellPlassering,
                    manualAfter = p.plassertEtter,
                    accepted = p.godkjent,
                )
            },
        )
    }
}
