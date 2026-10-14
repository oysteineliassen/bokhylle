package no.oystein.bokhylle.work

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import no.oystein.bokhylle.app
import no.oystein.bokhylle.data.BookRepository
import no.oystein.bokhylle.data.Page
import no.oystein.bokhylle.image.ImageOps
import java.io.File

/**
 * Sparer plass når en bok er ferdig kontrollert: sidebildene skaleres ned til ~1600 px og originalene
 * (før beskjæring) slettes. Med [KEY_DELETE] slettes også sidebildene; figurene beholdes alltid.
 */
class CompactWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext.app
        val bookId = inputData.getLong(KEY_BOOK, -1)
        val deleteImages = inputData.getBoolean(KEY_DELETE, false)
        val pages = app.db.pages().forBook(bookId)
        // Vent til alle sidene er behandlet; figurene må være klippet ut først.
        if (pages.any { it.status == Page.STATUS_NY }) return Result.retry()
        var saved = 0L
        for (page in pages) {
            val before = sizeOf(page.bildeSti) + (page.originalSti?.takeIf { it != page.bildeSti }?.let(::sizeOf) ?: 0)
            if (deleteImages) {
                app.files.delete(page.bildeSti)
            } else if (File(page.bildeSti).exists()) {
                val target = app.files.newPageFile(bookId, "side")
                if (ImageOps.downscaleFile(page.bildeSti, ImageOps.ARCHIVE_SIDE, target)) {
                    app.db.pages().setImages(page.id, target.path, page.originalSti)
                    app.files.delete(page.bildeSti)
                }
            }
            val original = page.originalSti
            app.db.pages().get(page.id)?.let { app.db.pages().setImages(it.id, it.bildeSti, null) }
            if (original != null && app.db.pages().countUsing(original) == 0) app.files.delete(original)
            val after = app.db.pages().get(page.id)?.let { sizeOf(it.bildeSti) } ?: 0
            saved += before - after
        }
        Log.d("Bokhylle", "bok $bookId: komprimert, ${saved / 1024} kB spart")
        return Result.success()
    }

    private fun sizeOf(path: String) = File(path).takeIf { it.exists() }?.length() ?: 0L

    companion object {
        private const val KEY_BOOK = "bok"
        private const val KEY_DELETE = "slett"

        fun enqueue(context: Context, bookId: Long, deleteImages: Boolean) {
            val request = OneTimeWorkRequestBuilder<CompactWorker>()
                .setInputData(workDataOf(KEY_BOOK to bookId, KEY_DELETE to deleteImages))
                .addTag(BookRepository.bookTag(bookId))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork("komprimer-$bookId", ExistingWorkPolicy.REPLACE, request)
        }
    }
}
