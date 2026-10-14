package no.oystein.bokhylle.data

import android.content.Context
import androidx.work.WorkManager
import kotlinx.coroutines.flow.Flow

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
    suspend fun setLastRead(bookId: Long, pageId: Long) = db.books().setLastRead(bookId, pageId)

    companion object {
        fun bookTag(bookId: Long) = "bok-$bookId"
    }
}
