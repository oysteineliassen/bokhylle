package no.oystein.bokhylle.data

import android.content.Context
import androidx.work.WorkManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import no.oystein.bokhylle.core.OcrPage
import no.oystein.bokhylle.core.PageNumber
import no.oystein.bokhylle.core.PageNumberDetector
import no.oystein.bokhylle.core.Quad
import no.oystein.bokhylle.image.ImageOps
import no.oystein.bokhylle.work.CompactWorker
import no.oystein.bokhylle.work.OrientWorker
import no.oystein.bokhylle.work.ProcessWorker

class BookRepository(
    private val context: Context,
    val db: AppDatabase,
    val files: FileStore,
) {
    val books: Flow<List<BookSummary>> = db.books().observeSummaries()

    fun book(id: Long): Flow<Book?> = db.books().observe(id)
    fun pages(bookId: Long): Flow<List<Page>> = db.pages().observe(bookId)

    suspend fun createBook(title: String, author: String): Long =
        db.books().insert(Book(tittel = title.trim().ifEmpty { "Uten tittel" }, forfatter = author.trim()))

    suspend fun updateBook(book: Book) = db.books().update(book)

    /** Sletter boka, alle sidene og alle bildene. */
    suspend fun deleteBook(bookId: Long) {
        WorkManager.getInstance(context).cancelAllWorkByTag(bookTag(bookId))
        val book = db.books().get(bookId) ?: return
        db.claude().deleteJobsForBook(bookId)
        db.books().delete(book)
        files.deleteBook(bookId)
    }

    suspend fun setCover(bookId: Long, pageId: Long?) {
        // En valgt side skal vises, ikke et tidligere omslagsbilde.
        removeCoverImage(bookId)
        db.books().setCover(bookId, pageId)
    }

    /** Lagrer et eget bilde av omslaget (fra kamera, dokumentskanner eller galleriet). */
    suspend fun setCoverImage(bookId: Long, uri: android.net.Uri): Unit = withContext(Dispatchers.IO) {
        val temp = files.newIncomingFile(bookId)
        try {
            context.contentResolver.openInputStream(uri)?.use { input -> temp.outputStream().use { input.copyTo(it) } } ?: return@withContext
            val bitmap = ImageOps.decodeUpright(temp, COVER_SIDE) ?: return@withContext
            val file = files.newPageFile(bookId, "omslag")
            ImageOps.save(bitmap, file, quality = ImageOps.FIGURE_QUALITY)
            bitmap.recycle()
            val old = db.books().get(bookId)?.omslagBilde
            db.books().setCoverImage(bookId, file.path)
            if (old != null && old != file.path) files.delete(old)
        } finally {
            temp.delete()
        }
    }

    /** Fjerner det egne omslagsbildet; da brukes valgt side eller første side igjen. */
    suspend fun removeCoverImage(bookId: Long) {
        val old = db.books().get(bookId)?.omslagBilde ?: return
        db.books().setCoverImage(bookId, null)
        files.delete(old)
    }

    private val processor get() = (context.applicationContext as no.oystein.bokhylle.BokhylleApp).processor

    suspend fun page(id: Long): Page? = db.pages().get(id)

    /** Sletter en side (f.eks. et duplikat) med bildene. */
    suspend fun deletePage(page: Page) {
        db.pages().delete(page)
        files.delete(page.bildeSti)
        // Et oppslag deler originalbildet med den andre siden.
        page.originalSti?.let { if (db.pages().countUsing(it) == 0) files.delete(it) }
        PageImages.figurePaths(page).forEach { files.delete(it) }
        db.books().get(page.bookId)?.let { if (it.coverPageId == page.id) db.books().setCover(page.bookId, null) }
        processor.rebuild(page.bookId)
    }

    /** Brukeren skriver inn sidetallet selv. Tomt felt = finn det automatisk igjen. */
    suspend fun setPageNumber(page: Page, number: PageNumber?) {
        val auto = number == null
        val detected = if (auto) OcrPage.decode(page.ocrLinjer)?.let { PageNumberDetector.detect(it.lines)?.number?.label } else null
        db.pages().update(
            page.copy(
                oppdagetSidetall = number?.label ?: detected,
                sidetallManuelt = !auto,
                // Med nytt sidetall skal siden sorteres på det, ikke ligge der den ble dratt.
                manuellPlassering = false,
                plassertEtter = null,
            ),
        )
        processor.rebuild(page.bookId)
    }

    suspend fun setAccepted(page: Page, accepted: Boolean) {
        db.pages().update(page.copy(godkjent = accepted))
        processor.rebuild(page.bookId)
    }

    suspend fun resetPlacement(page: Page) {
        db.pages().update(page.copy(manuellPlassering = false, plassertEtter = null))
        processor.rebuild(page.bookId)
    }

    /**
     * Brukeren har dratt [movedId] til en ny plass. Siden blir liggende rett etter siden foran den,
     * også når flere sider legges til senere.
     */
    suspend fun moveTo(bookId: Long, newOrder: List<Long>, movedId: Long) {
        val index = newOrder.indexOf(movedId)
        if (index < 0) return
        val moved = db.pages().get(movedId) ?: return
        db.pages().update(moved.copy(manuellPlassering = true, plassertEtter = newOrder.getOrNull(index - 1)))
        db.pages().setOrder(newOrder)
        processor.rebuild(bookId)
    }

    /** Teksten er kontrollert: sidebildene skaleres ned og originalene slettes (i bakgrunnen). */
    suspend fun markFinished(bookId: Long) {
        val book = db.books().get(bookId) ?: return
        db.books().update(book.copy(ferdigKontrollert = true))
        CompactWorker.enqueue(context, bookId, deleteImages = false)
    }

    /** Boka er under arbeid igjen (flere sider skal legges til). */
    suspend fun reopen(bookId: Long) {
        val book = db.books().get(bookId) ?: return
        db.books().update(book.copy(ferdigKontrollert = false))
    }

    /** Sletter sidebildene og originalene, men beholder figurene. */
    suspend fun deleteImages(bookId: Long) {
        val book = db.books().get(bookId) ?: return
        db.books().update(book.copy(ferdigKontrollert = true, bilderSlettet = true))
        CompactWorker.enqueue(context, bookId, deleteImages = true)
    }

    /** Nye hjørner: siden beskjæres og leses på nytt i bakgrunnen. */
    suspend fun recrop(page: Page, quad: Quad) {
        db.pages().update(
            page.copy(
                beskjaering = quad.encode(),
                status = Page.STATUS_NY,
                // Claudes plasseringer gjelder det gamle utsnittet.
                blocksJson = null,
                trengerClaude = page.trengerClaude || page.ocrKilde == Page.KILDE_CLAUDE,
                ocrKilde = Page.KILDE_MLKIT,
            ),
        )
        ProcessWorker.enqueue(context, page.bookId)
    }

    /**
     * Roterer originalbildet [degrees] grader med klokka, og finner siden(e) i det på nytt i bakgrunnen.
     * Var bildet delt som oppslag, slettes den andre halvdelen; den lages igjen om bildet fortsatt er et oppslag.
     */
    suspend fun rotate(page: Page, degrees: Int) = withContext(Dispatchers.IO) {
        rotateNow(page, degrees)
    }

    private suspend fun rotateNow(page: Page, degrees: Int) {
        val oldPath = page.originalSti ?: return
        val bitmap = ImageOps.decode(oldPath) ?: return
        val rotated = ImageOps.rotate(bitmap, degrees)
        val file = files.newPageFile(page.bookId, "original")
        ImageOps.save(rotated, file)
        if (rotated != bitmap) rotated.recycle()
        bitmap.recycle()

        val fromSameImage = db.pages().forBook(page.bookId).filter { it.originalSti == oldPath }
        val keep = fromSameImage.minByOrNull { it.importIndeks } ?: return
        for (other in fromSameImage) {
            if (other.id == keep.id) continue
            db.pages().delete(other)
            files.delete(other.bildeSti)
            PageImages.figurePaths(other).forEach { files.delete(it) }
        }
        PageImages.figurePaths(keep).forEach { files.delete(it) }
        db.pages().update(
            keep.copy(
                originalSti = file.path,
                beskjaering = null,
                status = Page.STATUS_NY,
                oppdagetSidetall = if (keep.sidetallManuelt) keep.oppdagetSidetall else null,
                blocksJson = null,
                trengerClaude = keep.trengerClaude || fromSameImage.any { it.ocrKilde == Page.KILDE_CLAUDE },
                ocrKilde = Page.KILDE_MLKIT,
            ),
        )
        files.delete(oldPath)
        ProcessWorker.enqueue(context, page.bookId)
    }

    /**
     * Bruk en ny tegning (ClaudeDraw) i stedet for fotoet av en figur. Fotoet tas vare på, så man kan bytte tilbake.
     */
    suspend fun useDrawing(pageId: Long, photo: String, drawing: String) {
        val page = db.pages().get(pageId) ?: return
        val layout = no.oystein.bokhylle.core.PageLayout.decode(page.layoutJson) ?: return
        // [photo] er bildet figuren har nå. Er det en tegning, erstattes den, og det opprinnelige fotoet beholdes.
        // En tegning lagt til side (se [usePhoto]) erstattes også.
        val replaced = mutableListOf<String>()
        val blocks = layout.blocks.map {
            if (it.image != photo) return@map it
            if (PageImages.isDrawing(photo)) replaced += photo
            it.drawing?.let(replaced::add)
            it.copy(image = drawing, photo = it.photo ?: photo, drawing = null)
        }
        db.pages().setLayoutJson(pageId, layout.copy(blocks = blocks).encode())
        replaced.filter { it != drawing }.forEach { deleteDrawing(it) }
        db.books().touch(page.bookId)
        processor.rebuild(page.bookId)
    }

    /**
     * Tilbake til fotoet. Tegningen legges til side, så [useParkedDrawing] kan ta den i bruk igjen.
     * Finnes ikke fotoet lenger, klippes det ut av sidebildet på nytt.
     */
    suspend fun usePhoto(pageId: Long, drawing: String) {
        val page = db.pages().get(pageId) ?: return
        val layout = no.oystein.bokhylle.core.PageLayout.decode(page.layoutJson) ?: return
        val blocks = layout.blocks.map {
            if (it.image == drawing) it.copy(image = it.photo?.takeIf { p -> java.io.File(p).exists() }, photo = null, drawing = drawing) else it
        }
        db.pages().setLayoutJson(pageId, layout.copy(blocks = blocks).encode())
        db.books().touch(page.bookId)
        processor.rebuild(page.bookId)
    }

    /** Tar i bruk igjen tegningen som ble lagt til side for figuren som nå viser [photo]. */
    suspend fun useParkedDrawing(pageId: Long, photo: String) {
        val page = db.pages().get(pageId) ?: return
        val layout = no.oystein.bokhylle.core.PageLayout.decode(page.layoutJson) ?: return
        val drawing = layout.blocks.firstOrNull { it.image == photo }?.drawing?.takeIf { java.io.File(it).exists() } ?: return
        val blocks = layout.blocks.map { if (it.image == photo) it.copy(image = drawing, photo = photo, drawing = null) else it }
        db.pages().setLayoutJson(pageId, layout.copy(blocks = blocks).encode())
        db.books().touch(page.bookId)
        processor.rebuild(page.bookId)
    }

    private fun deleteDrawing(path: String) {
        files.delete(path)
        files.delete(path.removeSuffix(".webp") + ".svg")
    }

    /** Finner liggende og opp-ned bilder i boka og snur dem (i bakgrunnen). */
    fun fixOrientation(bookId: Long) = OrientWorker.enqueue(context, bookId)

    suspend fun setLastRead(bookId: Long, pageId: Long) = db.books().setLastRead(bookId, pageId)

    companion object {
        fun bookTag(bookId: Long) = "bok-$bookId"

        /** Omslagsbildet lagres med maks denne langsiden. */
        private const val COVER_SIDE = 1400
    }
}
