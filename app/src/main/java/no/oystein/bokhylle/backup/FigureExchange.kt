package no.oystein.bokhylle.backup

import android.graphics.Bitmap
import no.oystein.bokhylle.BokhylleApp
import no.oystein.bokhylle.claude.ClaudeDraw
import no.oystein.bokhylle.core.BlockType
import no.oystein.bokhylle.core.Box
import no.oystein.bokhylle.core.Json
import no.oystein.bokhylle.core.PageLayout
import no.oystein.bokhylle.core.arr
import no.oystein.bokhylle.core.obj
import no.oystein.bokhylle.data.Page
import no.oystein.bokhylle.data.PageImages
import no.oystein.bokhylle.data.sidetallVisning
import no.oystein.bokhylle.image.ImageOps
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Figurer tegnes på nytt i en chat i stedet for via API-et (sparer API-kreditt):
 * «Eksporter figurer til tegning» lager en .bokhylle-figurer-fil (figurbildene, et utsnitt av siden rundt hver
 * figur med figuren markert i rødt, beskrivelser og bildetekster). Tilbake kommer en .bokhylle-tegninger-fil
 * med én SVG per figur, som «Importer tegninger» legger inn i boka (fotoene tas vare på, som ved «Tegn på nytt»).
 *
 * figurer.json: {"format":"bokhylle-figurer","version":1,"book":uuid,"title":…,"figures":[{"key","page","pageNumber",
 *   "image":"figurer/<key>.jpg","context":"kontekst/<key>.jpg"|null,"description","caption"}]}
 * tegninger.json: {"format":"bokhylle-tegninger","version":1,"book":uuid,"drawings":[{"key","svg":"tegninger/<key>.svg"}]}
 */
object FigureExchange {
    const val FIGURES_EXTENSION = "bokhylle-figurer"
    const val DRAWINGS_EXTENSION = "bokhylle-tegninger"
    private const val FIGURES_FORMAT = "bokhylle-figurer"
    private const val DRAWINGS_FORMAT = "bokhylle-tegninger"
    private const val VERSION = 1

    /** Nøkkel for en figur: siden og plasseringen (stabil selv om figuren klippes ut på nytt). */
    fun keyOf(pageId: Long, box: Box) =
        "s$pageId-" + box.toList().joinToString("_") { "%.3f".format(java.util.Locale.ROOT, it) }.hashCode().toUInt().toString(16)

    private data class Figure(val page: Page, val key: String, val image: String, val description: String?, val caption: String?, val box: Box)

    private fun figures(pages: List<Page>): List<Figure> = pages.filter { it.status == Page.STATUS_FERDIG }.flatMap { p ->
        val blocks = PageLayout.decode(p.layoutJson)?.blocks.orEmpty()
        blocks.mapIndexedNotNull { i, b ->
            val image = b.image ?: return@mapIndexedNotNull null
            val box = b.box ?: return@mapIndexedNotNull null
            if (b.type != BlockType.FIGURE || PageImages.isDrawing(image) || !File(image).exists()) return@mapIndexedNotNull null
            Figure(p, keyOf(p.id, box), image, b.description, blocks.getOrNull(i + 1)?.takeIf { it.type == BlockType.CAPTION }?.text, box)
        }
    }

    /** Antall figurer som kan sendes til tegning (ikke tegnet ennå). */
    fun count(pages: List<Page>) = figures(pages).size

    suspend fun export(app: BokhylleApp, bookId: Long, out: OutputStream): Int {
        val book = app.db.books().get(bookId) ?: throw BackupFormatException("Fant ikke boka")
        val list = figures(app.db.pages().forBook(bookId))
        ZipOutputStream(out.buffered()).use { zip ->
            fun put(name: String, bytes: ByteArray) { zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry() }
            fun jpeg(bitmap: Bitmap): ByteArray = java.io.ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
            val entries = list.map { f ->
                ImageOps.decode(f.image)?.let { put("figurer/${f.key}.jpg", jpeg(it)); it.recycle() }
                val context = ImageOps.figureContext(f.page.bildeSti, f.box)?.let { put("kontekst/${f.key}.jpg", jpeg(it)); it.recycle(); "kontekst/${f.key}.jpg" }
                mapOf(
                    "key" to f.key, "page" to f.page.id, "pageNumber" to f.page.sidetallVisning,
                    "image" to "figurer/${f.key}.jpg", "context" to context,
                    "description" to f.description, "caption" to f.caption,
                )
            }
            put(
                "figurer.json",
                Json.write(
                    mapOf(
                        "format" to FIGURES_FORMAT, "version" to VERSION, "book" to book.uuid, "title" to book.tittel,
                        "instructions" to INSTRUCTIONS, "figures" to entries,
                    ),
                ).toByteArray(),
            )
        }
        return list.size
    }

    /** Resultatet av en import: antall tegninger lagt inn, og antall som ikke passet (feil bok, figuren finnes ikke). */
    data class ImportResult(val added: Int, val skipped: Int)

    suspend fun importDrawings(app: BokhylleApp, bookId: Long, input: InputStream): ImportResult {
        val book = app.db.books().get(bookId) ?: throw BackupFormatException("Fant ikke boka")
        var manifest: Map<String, Any?>? = null
        val svgs = mutableMapOf<String, String>()
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                when {
                    e.name == "tegninger.json" -> manifest = runCatching { Json.parse(String(zip.readBytes())).obj() }.getOrNull()
                        ?: throw BackupFormatException("tegninger.json er skadet")
                    e.name.endsWith(".svg") && !e.isDirectory -> svgs[e.name] = String(zip.readBytes())
                }
            }
        }
        val m = manifest ?: throw BackupFormatException("Filen er ikke en Bokhylle-tegningsfil (mangler tegninger.json)")
        if (m["format"] != DRAWINGS_FORMAT) throw BackupFormatException("Ukjent filformat")
        if (m["book"] != book.uuid) throw BackupFormatException("Tegningene hører til en annen bok")
        val byKey = figures(app.db.pages().forBook(bookId)).associateBy { it.key }
        var added = 0
        var skipped = 0
        for (d in m["drawings"].arr().mapNotNull { it.obj() }) {
            val figure = byKey[d["key"] as? String]
            val svg = svgs[d["svg"] as? String]?.let(ClaudeDraw::extractSvg)
            if (figure == null || svg == null) { skipped++; continue }
            val bitmap = runCatching { ClaudeDraw.render(svg) }.getOrNull()
            if (bitmap == null) { skipped++; continue }
            val name = "${PageImages.DRAWING_PREFIX}${figure.page.id}-${UUID.randomUUID()}"
            val dir = app.files.bookDir(bookId)
            File(dir, "$name.svg").writeText(svg)
            val image = File(dir, "$name.webp")
            ImageOps.save(bitmap, image, ImageOps.FIGURE_QUALITY)
            bitmap.recycle()
            app.repo.useDrawing(figure.page.id, figure.image, image.path)
            added++
        }
        return ImportResult(added, skipped)
    }

    /** Står i figurer.json, så den som tegner (Claude i en chat) vet hva som skal leveres tilbake. */
    private val INSTRUCTIONS = """
        Tegn hver figur på nytt som én selvstendig SVG (viewBox, bare path/line/polyline/polygon/rect/circle/ellipse/
        text/tspan/g/linearGradient; ingen <image> eller skript). Gjengi figuren trofast (former, farger, all tekst
        nøyaktig som i boka), rett opp skjevhet fra fotoet, uten skygger og gjennomslag. Kontekstbildet viser siden
        rundt figuren med figuren markert i rødt: ta med etiketter og piler som stikker utenfor rammen, men ikke
        brødtekst, overskrifter eller bildeteksten (den vises for seg). Hopp over fotografier og detaljerte
        illustrasjoner. Lever en zip med filendelsen .$DRAWINGS_EXTENSION som inneholder tegninger.json
        {"format":"$DRAWINGS_FORMAT","version":$VERSION,"book":<samme book som her>,"drawings":[{"key":<key>,"svg":"tegninger/<key>.svg"}]}
        og SVG-filene.
    """.trimIndent().replace("\n", " ")
}
