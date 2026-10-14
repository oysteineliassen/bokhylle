package no.oystein.bokhylle.backup

import android.graphics.Bitmap
import no.oystein.bokhylle.BokhylleApp
import no.oystein.bokhylle.core.BlockType
import no.oystein.bokhylle.core.LayoutBlock
import no.oystein.bokhylle.core.LayoutMarkup
import no.oystein.bokhylle.core.PageLayout
import no.oystein.bokhylle.data.Page
import no.oystein.bokhylle.image.ImageOps
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * EPUB 3 for andre lesere: overskrifter, avsnitt (også de som fortsetter over sideskift), faktabokser,
 * figurer (som JPEG, som alle lesere støtter) med bildetekst, og fotnoter som popup-noter.
 * Nytt kapittel ved hver overskrift.
 */
object EpubExporter {
    private class Chapter(val title: String, val body: StringBuilder = StringBuilder())

    suspend fun export(app: BokhylleApp, bookId: Long, out: OutputStream, onProgress: (Int, Int) -> Unit = { _, _ -> }) {
        val grayFigures = grayFigures(app)
        val book = app.db.books().get(bookId) ?: throw BackupFormatException("Fant ikke boka")
        val pages = app.db.pages().forBook(bookId).filter { it.status == Page.STATUS_FERDIG }
        val chapters = mutableListOf(Chapter(book.tittel))
        val images = mutableListOf<Pair<String, ByteArray>>()
        var openParagraph: StringBuilder? = null // avsnitt som fortsetter på neste side

        fun closeParagraph() {
            openParagraph?.let { chapters.last().body.append("<p>").append(it).append("</p>\n") }
            openParagraph = null
        }

        pages.forEachIndexed { pi, page ->
            val base = PageLayout.decode(page.layoutJson) ?: PageLayout(
                page.ocrTekst.split(Regex("\\n\\s*\\n")).filter { it.isNotBlank() }.map { LayoutBlock(BlockType.PARAGRAPH, it.replace('\n', ' ')) },
            )
            val layout = page.redigertTekst?.let { LayoutMarkup.parse(it, base) } ?: base
            val notes = layout.blocks.filter { it.type == BlockType.FOOTNOTE }
            fun inline(text: String) = esc(text).replace(PageLayout.FOOTNOTE_REF) { m ->
                val n = m.groupValues[1]
                if (notes.any { it.number == n }) "<sup><a epub:type=\"noteref\" id=\"r$pi-$n\" href=\"#n$pi-$n\">$n</a></sup>" else "<sup>$n</sup>"
            }
            val body = layout.blocks.filter { it.type != BlockType.FOOTNOTE }
            body.forEachIndexed { bi, b ->
                val continuing = bi == 0 && layout.continuesFromPrevious && b.type == BlockType.PARAGRAPH && openParagraph != null
                if (!continuing) closeParagraph()
                when (b.type) {
                    BlockType.HEADING -> {
                        if (chapters.last().body.isNotEmpty()) chapters += Chapter(b.text)
                        chapters.last().body.append("<h2>").append(inline(b.text)).append("</h2>\n")
                    }
                    BlockType.PARAGRAPH -> {
                        val text = inline(b.text)
                        if (continuing) openParagraph!!.append(if (openParagraph!!.endsWith("-")) "" else " ").append(text)
                        else if (bi == body.lastIndex && layout.continuesToNext) openParagraph = StringBuilder(text)
                        else chapters.last().body.append("<p>").append(text).append("</p>\n")
                    }
                    BlockType.BOX -> {
                        val c = chapters.last().body
                        c.append("<aside class=\"boks\">")
                        b.title?.let { c.append("<h3>").append(inline(it)).append("</h3>") }
                        b.text.split(Regex("\\n\\s*\\n")).filter { it.isNotBlank() }.forEach { c.append("<p>").append(inline(it)).append("</p>") }
                        c.append("</aside>\n")
                    }
                    BlockType.FIGURE -> {
                        val jpeg = b.image?.let { jpegOf(it, gray = grayFigures) }
                        val c = chapters.last().body
                        if (jpeg != null) {
                            val name = "bilder/figur-${images.size + 1}.jpg"
                            images += name to jpeg
                            val caption = body.getOrNull(bi + 1)?.takeIf { it.type == BlockType.CAPTION }?.text
                            c.append("<figure><img src=\"").append(name).append("\" alt=\"").append(esc(b.description ?: "Figur")).append("\"/>")
                            if (caption != null) c.append("<figcaption>").append(inline(caption)).append("</figcaption>")
                            c.append("</figure>\n")
                        } else if (b.description != null) {
                            c.append("<p class=\"figur\">[").append(esc(b.description!!)).append("]</p>\n")
                        }
                    }
                    BlockType.CAPTION -> {
                        val prev = body.getOrNull(bi - 1)
                        if (prev?.type != BlockType.FIGURE || prev.image == null) chapters.last().body.append("<p class=\"bildetekst\">").append(inline(b.text)).append("</p>\n")
                    }
                    BlockType.FOOTNOTE -> Unit
                }
            }
            if (notes.isNotEmpty()) {
                closeParagraph()
                notes.forEach { n ->
                    // Ekte EPUB-fotnote: vises som popup på Kindle og andre lesere, med lenke tilbake.
                    chapters.last().body.append("<aside epub:type=\"footnote\" id=\"n$pi-${n.number}\" class=\"fotnote\"><p><a href=\"#r$pi-${n.number}\">")
                        .append(esc(n.number ?: "")).append("</a> ").append(inline(n.text)).append("</p></aside>\n")
                }
            }
            onProgress(pi + 1, pages.size)
        }
        closeParagraph()
        val nonEmpty = chapters.filter { it.body.isNotEmpty() }.ifEmpty { listOf(Chapter(book.tittel, StringBuilder("<p></p>"))) }
        // Omslag: valgt forside, ellers første side (hvis sidebildet finnes).
        val coverPage = pages.firstOrNull { it.id == book.coverPageId } ?: pages.firstOrNull()
        val cover = (book.omslagBilde ?: coverPage?.bildeSti)?.takeIf { java.io.File(it).exists() }?.let { jpegOf(it) }

        val zip = ZipOutputStream(out.buffered())
        // «mimetype» må være første fil og ukomprimert.
        val mime = "application/epub+zip".toByteArray()
        zip.putNextEntry(ZipEntry("mimetype").apply {
            method = ZipEntry.STORED; size = mime.size.toLong(); compressedSize = mime.size.toLong()
            crc = CRC32().apply { update(mime) }.value
        })
        zip.write(mime); zip.closeEntry()
        fun add(name: String, text: String) { zip.putNextEntry(ZipEntry(name)); zip.write(text.toByteArray()); zip.closeEntry() }
        add("META-INF/container.xml", """<?xml version="1.0" encoding="UTF-8"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
  <rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
</container>""")
        add("OEBPS/stil.css", CSS)
        if (cover != null) {
            zip.putNextEntry(ZipEntry("OEBPS/bilder/omslag.jpg")); zip.write(cover); zip.closeEntry()
            add("OEBPS/omslag.xhtml", """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops" lang="nb" xml:lang="nb">
<head><title>${esc(book.tittel)}</title><link rel="stylesheet" type="text/css" href="stil.css"/></head>
<body class="omslag"><section epub:type="cover"><img src="bilder/omslag.jpg" alt="${esc(book.tittel)}"/></section></body>
</html>""")
        }
        nonEmpty.forEachIndexed { i, c ->
            add("OEBPS/kapittel-${i + 1}.xhtml", """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops" lang="nb" xml:lang="nb">
<head><title>${esc(c.title)}</title><link rel="stylesheet" type="text/css" href="stil.css"/></head>
<body>
${c.body}</body>
</html>""")
        }
        add("OEBPS/nav.xhtml", """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops" lang="nb" xml:lang="nb">
<head><title>Innhold</title></head>
<body><nav epub:type="toc" id="toc"><h1>Innhold</h1><ol>
${nonEmpty.mapIndexed { i, c -> "<li><a href=\"kapittel-${i + 1}.xhtml\">${esc(c.title)}</a></li>" }.joinToString("\n")}
</ol></nav></body>
</html>""")
        add("OEBPS/toc.ncx", """<?xml version="1.0" encoding="UTF-8"?>
<ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1" xml:lang="nb">
<head><meta name="dtb:uid" content="urn:uuid:${book.uuid}"/></head>
<docTitle><text>${esc(book.tittel)}</text></docTitle>
<navMap>
${nonEmpty.mapIndexed { i, c -> "<navPoint id=\"p${i + 1}\" playOrder=\"${i + 1}\"><navLabel><text>${esc(c.title)}</text></navLabel><content src=\"kapittel-${i + 1}.xhtml\"/></navPoint>" }.joinToString("\n")}
</navMap>
</ncx>""")
        val modified = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date())
        add("OEBPS/content.opf", """<?xml version="1.0" encoding="UTF-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="bokid" xml:lang="nb">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
    <dc:identifier id="bokid">urn:uuid:${book.uuid}</dc:identifier>
    <dc:title>${esc(book.tittel)}</dc:title>
    ${if (book.forfatter.isNotBlank()) "<dc:creator>${esc(book.forfatter)}</dc:creator>" else ""}
    <dc:language>nb</dc:language>
    <meta property="dcterms:modified">$modified</meta>
    ${if (cover != null) "<meta name=\"cover\" content=\"omslag-bilde\"/>" else ""}
  </metadata>
  <manifest>
    <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
    <item id="stil" href="stil.css" media-type="text/css"/>
    <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
${if (cover != null) "    <item id=\"omslag-bilde\" href=\"bilder/omslag.jpg\" media-type=\"image/jpeg\" properties=\"cover-image\"/>\n    <item id=\"omslag\" href=\"omslag.xhtml\" media-type=\"application/xhtml+xml\"/>" else ""}
${nonEmpty.indices.joinToString("\n") { "    <item id=\"k${it + 1}\" href=\"kapittel-${it + 1}.xhtml\" media-type=\"application/xhtml+xml\"/>" }}
${images.indices.joinToString("\n") { "    <item id=\"b${it + 1}\" href=\"${images[it].first}\" media-type=\"image/jpeg\"/>" }}
  </manifest>
  <spine toc="ncx">
${if (cover != null) "    <itemref idref=\"omslag\" linear=\"yes\"/>" else ""}
${nonEmpty.indices.joinToString("\n") { "    <itemref idref=\"k${it + 1}\"/>" }}
  </spine>
</package>""")
        images.forEach { (name, bytes) -> zip.putNextEntry(ZipEntry("OEBPS/$name")); zip.write(bytes); zip.closeEntry() }
        zip.finish()
        zip.flush()
    }

    /** Valget «figurer i gråtoner» (for Kindle uten farger), delt av eksportdialogen og «Send til Kindle». */
    fun grayFigures(context: android.content.Context) =
        context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE).getBoolean(KEY_GRAY, true)

    fun setGrayFigures(context: android.content.Context, gray: Boolean) =
        context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE).edit().putBoolean(KEY_GRAY, gray).apply()

    private const val PREFS = "eksport"
    private const val KEY_GRAY = "gratoner"

    /**
     * JPEG på maks [MAX_IMAGE] px og 80 % kvalitet, så fila holder seg liten (Kindle har grenser for størrelse).
     * [gray]: gråtoneversjon laget for lesebrett uten farger (se FigureCleanup.einkGray).
     */
    private fun jpegOf(path: String, gray: Boolean = false): ByteArray? {
        val bmp = ImageOps.decode(path) ?: return null
        val scale = MAX_IMAGE.toFloat() / maxOf(bmp.width, bmp.height)
        var small = if (scale < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true) else bmp
        if (gray) {
            val px = IntArray(small.width * small.height)
            small.getPixels(px, 0, small.width, 0, 0, small.width, small.height)
            val g = no.oystein.bokhylle.core.FigureCleanup.einkGray(px)
            for (i in px.indices) px[i] = (0xFF shl 24) or (g[i] * 0x010101)
            val grayBitmap = Bitmap.createBitmap(px, small.width, small.height, Bitmap.Config.ARGB_8888)
            if (small != bmp) small.recycle()
            small = grayBitmap
        }
        val out = ByteArrayOutputStream()
        small.compress(Bitmap.CompressFormat.JPEG, 80, out)
        if (small != bmp) small.recycle()
        bmp.recycle()
        return out.toByteArray()
    }

    private const val MAX_IMAGE = 1200

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    // Enkel CSS som Kindle forstår: marger, kantlinjer og bakgrunnsfarge, ingen avansert layout.
    private const val CSS = """body { margin: 0 2%; }
h2 { margin: 1.5em 0 0.8em 0; }
p { margin: 0 0 0.5em 0; text-indent: 0; }
aside.boks { margin: 1em 0; padding: 0.6em 0.8em; border: 2px solid #555555; background-color: #e8f0ea; }
aside.boks h3 { margin: 0 0 0.4em 0; }
figure { margin: 1em 0; text-align: center; }
img { max-width: 100%; }
figcaption, p.bildetekst { font-style: italic; font-size: 0.9em; margin-top: 0.3em; }
aside.fotnote { font-size: 0.85em; margin: 0.4em 0; }
body.omslag { margin: 0; text-align: center; }
body.omslag img { max-height: 100%; }
"""
}
