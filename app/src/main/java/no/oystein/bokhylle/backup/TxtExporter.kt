package no.oystein.bokhylle.backup

import no.oystein.bokhylle.BokhylleApp
import no.oystein.bokhylle.core.BlockType
import no.oystein.bokhylle.core.LayoutBlock
import no.oystein.bokhylle.core.LayoutMarkup
import no.oystein.bokhylle.core.PageLayout
import no.oystein.bokhylle.core.TextExport
import no.oystein.bokhylle.data.Page
import java.io.OutputStream

/** Boka som ren tekst (UTF-8), med samme tekst og rettelser som leseren viser. */
object TxtExporter {
    suspend fun export(app: BokhylleApp, bookId: Long, out: OutputStream) {
        val book = app.db.books().get(bookId) ?: throw BackupFormatException("Fant ikke boka")
        val layouts = app.db.pages().forBook(bookId).filter { it.status == Page.STATUS_FERDIG }.map { p ->
            val base = PageLayout.decode(p.layoutJson) ?: PageLayout(
                p.ocrTekst.split(Regex("\\n\\s*\\n")).map { it.replace('\n', ' ').trim() }.filter { it.isNotEmpty() }
                    .map { LayoutBlock(BlockType.PARAGRAPH, it) },
            )
            p.redigertTekst?.let { LayoutMarkup.parse(it, base) } ?: base
        }
        // Byte order mark, så Windows-programmer leser æ, ø og å riktig.
        out.write(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()))
        out.write(TextExport.render(book.tittel, book.forfatter, layouts).replace("\n", "\r\n").toByteArray(Charsets.UTF_8))
    }
}
