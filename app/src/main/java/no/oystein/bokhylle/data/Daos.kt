package no.oystein.bokhylle.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface BookDao {
    @Query(
        """
        SELECT b.*,
            (SELECT COUNT(*) FROM pages p WHERE p.bookId = b.id) AS antallSider,
            (SELECT COUNT(*) FROM pages p WHERE p.bookId = b.id AND p.status = 0) AS ventende,
            (SELECT COUNT(*) FROM pages p WHERE p.bookId = b.id AND p.flagget = 1) AS flaggede,
            COALESCE(
                b.omslagBilde,
                (SELECT p.bildeSti FROM pages p WHERE p.id = b.coverPageId AND p.bookId = b.id),
                (SELECT p.bildeSti FROM pages p WHERE p.bookId = b.id ORDER BY p.sorteringsIndeks, p.id LIMIT 1)
            ) AS omslag,
            (SELECT COUNT(*) FROM pages p, pages l
                WHERE l.id = b.sistLestSide AND p.bookId = b.id AND p.sorteringsIndeks <= l.sorteringsIndeks) AS lestTil
        FROM books b
        ORDER BY b.opprettet DESC
        """,
    )
    fun observeSummaries(): Flow<List<BookSummary>>

    @Query("SELECT * FROM books WHERE id = :id")
    fun observe(id: Long): Flow<Book?>

    @Query("SELECT * FROM books WHERE id = :id")
    suspend fun get(id: Long): Book?

    @Query("SELECT * FROM books ORDER BY opprettet DESC")
    suspend fun all(): List<Book>

    @Insert
    suspend fun insert(book: Book): Long

    @Update
    suspend fun update(book: Book)

    @Delete
    suspend fun delete(book: Book)

    @Query("UPDATE books SET omslagBilde = :path WHERE id = :bookId")

    suspend fun setCoverImage(bookId: Long, path: String?)


    @Query("UPDATE books SET sistLestSide = :pageId WHERE id = :bookId")
    suspend fun setLastRead(bookId: Long, pageId: Long)

    @Query("UPDATE books SET sistEndret = :time WHERE id = :bookId")
    suspend fun touch(bookId: Long, time: Long = System.currentTimeMillis())

    @Query("UPDATE books SET coverPageId = :pageId WHERE id = :bookId")
    suspend fun setCover(bookId: Long, pageId: Long?)
}

@Dao
interface PageDao {
    @Query("SELECT * FROM pages WHERE bookId = :bookId ORDER BY sorteringsIndeks, id")
    fun observe(bookId: Long): Flow<List<Page>>

    @Query("SELECT * FROM pages WHERE bookId = :bookId ORDER BY sorteringsIndeks, id")
    suspend fun forBook(bookId: Long): List<Page>

    @Query("SELECT * FROM pages WHERE id = :id")
    suspend fun get(id: Long): Page?

    @Query("SELECT * FROM pages WHERE bookId = :bookId AND status = 0 ORDER BY importBatch, importIndeks")
    suspend fun pending(bookId: Long): List<Page>

    @Query("SELECT bookId, oppdagetSidetall, utledetSidetall FROM pages WHERE status = 1")
    fun observeNumbers(): Flow<List<PageNumberRow>>

    @Query("SELECT DISTINCT bookId FROM pages WHERE status = 0")
    suspend fun booksWithPending(): List<Long>

    @Query("SELECT COUNT(*) FROM pages WHERE originalSti = :path OR bildeSti = :path")
    suspend fun countUsing(path: String): Int

    @Query("UPDATE pages SET bildeSti = :image, originalSti = :original WHERE id = :id")
    suspend fun setImages(id: Long, image: String, original: String?)

    @Query("SELECT kildeHash FROM pages WHERE bookId = :bookId")
    suspend fun hashes(bookId: Long): List<String>

    @Query("SELECT COALESCE(MAX(sorteringsIndeks), -1) FROM pages WHERE bookId = :bookId")
    suspend fun maxSortIndex(bookId: Long): Int

    @Insert
    suspend fun insert(page: Page): Long

    @Update
    suspend fun update(page: Page)

    @Update
    suspend fun updateAll(pages: List<Page>)

    @Delete
    suspend fun delete(page: Page)

    /** Feltene sorteringen eier; skrives uten å røre status, bilder og tekst som behandlingen kan endre samtidig. */
    @Query(
        "UPDATE pages SET sorteringsIndeks = :index, flagget = :flagged, flaggGrunner = :reasons, " +
            "utledetSidetall = :inferred, oppdagetSidetall = :number WHERE id = :id",
    )
    suspend fun updateOrder(id: Long, index: Int, flagged: Boolean, reasons: String, inferred: String?, number: String?)

    @Query("UPDATE pages SET layoutJson = :layout WHERE id = :id")
    suspend fun setLayoutJson(id: Long, layout: String)

    @Query("UPDATE pages SET layoutJson = :layout, ryddetTekst = :clean, sokeTekst = :search, ocrKilde = :source WHERE id = :id")
    suspend fun updateLayout(id: Long, layout: String, clean: String, search: String, source: String)

    /** Claudes svar for siden; ny analyse erstatter ML Kit-oppsettet ved neste oppbygging. */
    @Query("UPDATE pages SET blocksJson = :json, trengerClaude = 0 WHERE id = :id")
    suspend fun setClaudeResult(id: Long, json: String)

    @Query("UPDATE pages SET trengerClaude = :needs WHERE id IN (:ids)")
    suspend fun setNeedsClaude(ids: List<Long>, needs: Boolean)

    @Query("UPDATE pages SET redigertTekst = :text WHERE id = :id")
    suspend fun setEditedText(id: Long, text: String?)

    @Query("UPDATE pages SET manuelleOmrader = :regions WHERE id = :id")
    suspend fun setRegions(id: Long, regions: String?)

    @Query("UPDATE pages SET sorteringsIndeks = :index WHERE id = :id")
    suspend fun setSortIndex(id: Long, index: Int)

    @Transaction
    suspend fun setOrder(ids: List<Long>) {
        ids.forEachIndexed { i, id -> setSortIndex(id, i) }
    }

    @Query(
        """
        SELECT p.id AS pageId, p.bookId AS bookId, b.tittel AS tittel, p.oppdagetSidetall AS oppdagetSidetall,
            COALESCE(p.redigertTekst, p.ryddetTekst, p.ocrTekst) AS tekst
        FROM pages p JOIN books b ON b.id = p.bookId
        WHERE p.sokeTekst LIKE '%' || :query || '%' ESCAPE '\'
        ORDER BY b.tittel, p.sorteringsIndeks
        LIMIT :limit
        """,
    )
    suspend fun search(query: String, limit: Int = 200): List<SearchHit>
}

@Dao
interface ClaudeDao {
    @Insert
    suspend fun insertUsage(usage: ApiUsage)

    @Query("SELECT COALESCE(SUM(inputTokens), 0) AS inputTokens, COALESCE(SUM(outputTokens), 0) AS outputTokens, COALESCE(SUM(kostnadUsd), 0) AS kostnadUsd FROM api_bruk")
    fun observeTotal(): Flow<UsageTotal>

    @Query("SELECT COALESCE(SUM(inputTokens), 0) AS inputTokens, COALESCE(SUM(outputTokens), 0) AS outputTokens, COALESCE(SUM(kostnadUsd), 0) AS kostnadUsd FROM api_bruk WHERE bookId = :bookId")
    fun observeBookTotal(bookId: Long): Flow<UsageTotal>

    @Insert
    suspend fun insertJob(job: ClaudeJob): Long

    @Query("SELECT * FROM claude_jobber WHERE ferdig = 0")
    suspend fun openJobs(): List<ClaudeJob>

    @Query("SELECT * FROM claude_jobber WHERE ferdig = 0 AND bookId = :bookId")
    fun observeOpenJobs(bookId: Long): Flow<List<ClaudeJob>>

    @Query("UPDATE claude_jobber SET ferdig = 1 WHERE id = :id")
    suspend fun finish(id: Long)

    @Query("DELETE FROM claude_jobber WHERE bookId = :bookId")
    suspend fun deleteJobsForBook(bookId: Long)
}
