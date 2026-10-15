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

    /**
     * Klipper ut figurene fra sidebildet. En figur med samme plassering som før gjenbruker bildet sitt,
     * også når sidebildet er slettet eller boka er importert uten sidebilder.
     */
    private fun cropFigures(page: Page, layout: PageLayout): PageLayout {
        fun legacyKey(box: no.oystein.bokhylle.core.Box) =
            box.toList().joinToString("_") { "%.3f".format(java.util.Locale.ROOT, it) }.hashCode().toUInt().toString(16)
        // Ny versjon i nøkkelen: figurer klippet før opprettingen lages på nytt når sidebildet finnes.
        fun keyOf(box: no.oystein.bokhylle.core.Box) = "${legacyKey(box)}v$FIGURE_VERSION"
        val previousBlocks = PageLayout.decode(page.layoutJson)?.blocks.orEmpty()
            .mapNotNull { b -> val box = b.box; val img = b.image; if (box != null && img != null && File(img).exists()) box to img else null }
        val previous = previousBlocks.filter { (_, img) -> "v$FIGURE_VERSION" in File(img).name }.associate { (box, img) -> keyOf(box) to img }
        // Figurer som er tegnet på nytt (ClaudeDraw) beholder tegningen, og fotoet tas vare på ved siden av.
        val drawings = PageLayout.decode(page.layoutJson)?.blocks.orEmpty()
            .filter { b -> b.box != null && PageImages.isDrawing(b.image) && File(b.image!!).exists() }
            .associate { b -> legacyKey(b.box!!) to b }
        val legacy = previousBlocks.associate { (box, img) -> legacyKey(box) to img }
        val old = PageImages.figurePaths(page).toSet()
        val keep = mutableSetOf<String>()
        val texts = layout.blocks.filter { it.type != BlockType.FIGURE && it.text.isNotBlank() }.mapNotNull { it.box }
        val ocrLines = OcrPage.decode(page.ocrLinjer)?.lines.orEmpty()
        var bitmap: Bitmap? = null
        var paper = 0
        try {
            val blocks = layout.blocks.mapIndexed { i, b ->
                val box = b.box
                if (b.type != BlockType.FIGURE || box == null) return@mapIndexed b
                drawings[legacyKey(box)]?.let { d ->
                    keep += d.image!!
                    val photo = d.photo?.takeIf { File(it).exists() }
                    photo?.let { keep += it }
                    return@mapIndexed b.copy(image = d.image, photo = photo)
                }
                val key = keyOf(box)
                previous[key]?.let { keep += it; return@mapIndexed b.copy(image = it) }
                val file = File(app.files.bookDir(page.bookId), "figur-${page.id}-$i-$key.webp")
                if (!file.exists()) {
                    if (bitmap == null) {
                        bitmap = ImageOps.decode(page.bildeSti)
                        bitmap?.let { paper = ImageOps.paperColor(it) }
                    }
                    val source = bitmap
                    val crop = source?.let {
                        val skew = no.oystein.bokhylle.core.FigureCleanup.skewDegrees(ocrLines, box, it.width, it.height)
                        // Ta med etiketter og strektegning rett utenfor Claudes ramme, men ikke brødtekst eller bildetekst.
                        val area = no.oystein.bokhylle.core.FigureCleanup.expandFigure(box, ocrLines, texts)
                        runCatching { ImageOps.figure(it, no.oystein.bokhylle.core.FigureCleanup.trimText(area, texts), skew, paper) }
                            .onFailure { e -> Log.w(TAG, "figur på side ${page.id}", e) }
                            .getOrNull()
                    }
                    if (crop == null) {
                        // Uten sidebilde (slettet eller importert uten bilder): behold den gamle figuren.
                        legacy[legacyKey(box)]?.let { keep += it; return@mapIndexed b.copy(image = it) }
                        return@mapIndexed b
                    }
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

        /** Øk når utklippingen av figurer forbedres, så eksisterende figurer lages på nytt. */
        private const val FIGURE_VERSION = 4

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
