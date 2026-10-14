package no.oystein.bokhylle.data

import android.content.Context
import androidx.work.WorkManager
import kotlinx.coroutines.flow.Flow
import no.oystein.bokhylle.core.OcrPage
import no.oystein.bokhylle.core.PageNumber
import no.oystein.bokhylle.core.PageNumberDetector
import no.oystein.bokhylle.core.Quad
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

    suspend fun setCover(bookId: Long, pageId: Long?) = db.books().setCover(bookId, pageId)

    private val processor get() = (context.applicationContext as no.oystein.bokhylle.BokhylleApp).processor

    suspend fun page(id: Long): Page? = db.pages().get(id)

    /** Sletter en side (f.eks. et duplikat) med bildene. */
    suspend fun deletePage(page: Page) {
        db.pages().delete(page)
        files.delete(page.bildeSti)
        files.delete(page.originalSti)
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
    suspend fun setLastRead(bookId: Long, pageId: Long) = db.books().setLastRead(bookId, pageId)

    companion object {
        fun bookTag(bookId: Long) = "bok-$bookId"
    }
}
