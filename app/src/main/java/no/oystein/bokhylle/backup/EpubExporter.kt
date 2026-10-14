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
                if (notes.any { it.number == n }) "<sup><a epub:type=\"noteref\" href=\"#n$pi-$n\">$n</a></sup>" else "<sup>$n</sup>"
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
                        val jpeg = b.image?.let { jpegOf(it) }
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
                    chapters.last().body.append("<aside epub:type=\"footnote\" id=\"n$pi-${n.number}\"><p>").append(esc(n.number ?: ""))
                        .append(" ").append(inline(n.text)).append("</p></aside>\n")
                }
            }
            onProgress(pi + 1, pages.size)
        }
        closeParagraph()
        val nonEmpty = chapters.filter { it.body.isNotEmpty() }.ifEmpty { listOf(Chapter(book.tittel, StringBuilder("<p></p>"))) }

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
        val modified = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date())
        add("OEBPS/content.opf", """<?xml version="1.0" encoding="UTF-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="bokid" xml:lang="nb">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
    <dc:identifier id="bokid">urn:uuid:${book.uuid}</dc:identifier>
    <dc:title>${esc(book.tittel)}</dc:title>
    ${if (book.forfatter.isNotBlank()) "<dc:creator>${esc(book.forfatter)}</dc:creator>" else ""}
    <dc:language>nb</dc:language>
    <meta property="dcterms:modified">$modified</meta>
  </metadata>
  <manifest>
    <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
    <item id="stil" href="stil.css" media-type="text/css"/>
${nonEmpty.indices.joinToString("\n") { "    <item id=\"k${it + 1}\" href=\"kapittel-${it + 1}.xhtml\" media-type=\"application/xhtml+xml\"/>" }}
${images.indices.joinToString("\n") { "    <item id=\"b${it + 1}\" href=\"${images[it].first}\" media-type=\"image/jpeg\"/>" }}
  </manifest>
  <spine>
${nonEmpty.indices.joinToString("\n") { "    <itemref idref=\"k${it + 1}\"/>" }}
  </spine>
</package>""")
        images.forEach { (name, bytes) -> zip.putNextEntry(ZipEntry("OEBPS/$name")); zip.write(bytes); zip.closeEntry() }
        zip.finish()
        zip.flush()
    }

    private fun jpegOf(path: String): ByteArray? {
        val bmp = ImageOps.decode(path) ?: return null
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 88, out)
        bmp.recycle()
        return out.toByteArray()
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private const val CSS = """body { font-family: serif; line-height: 1.5; }
h2 { margin-top: 2em; }
p { margin: 0 0 0.6em 0; }
aside.boks { background: #e8f0ea; padding: 0.8em 1em; margin: 1em 0; border-radius: 6px; }
aside.boks h3 { margin-top: 0; }
figure { margin: 1em 0; text-align: center; }
figure img { max-width: 100%; }
figcaption, p.bildetekst { font-style: italic; font-size: 0.9em; }
"""
}
