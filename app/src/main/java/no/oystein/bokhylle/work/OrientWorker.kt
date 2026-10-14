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
import no.oystein.bokhylle.ocr.MlKitOcr
import java.io.File

/**
 * «Rett opp liggende bilder»: leser hvert originalbilde i boka i alle fire retninger og snur de som ligger feil.
 * For sider som ble lagt inn før appen fant retningen selv, eller der telefonen lå flatt og EXIF var feil.
 */
class OrientWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val app = context.app

    override suspend fun doWork(): Result {
        val bookId = inputData.getLong(KEY_BOOK, -1)
        if (bookId < 0) return Result.failure()
        // Ett oppslag gir to sider med samme original: hvert bilde sjekkes én gang.
        val images = app.db.pages().forBook(bookId)
            .filter { it.status != Page.STATUS_NY && it.originalSti != null && File(it.originalSti).exists() }
            .groupBy { it.originalSti!! }
        var done = 0
        var rotated = 0
        for ((path, pages) in images) {
            if (isStopped) return Result.retry()
            setProgress(workDataOf(KEY_DONE to done, KEY_TOTAL to images.size, KEY_ROTATED to rotated))
            val bitmap = ImageOps.decode(path)
            if (bitmap != null) {
                val degrees = try { MlKitOcr.detectRotation(bitmap) } finally { bitmap.recycle() }
                if (degrees != 0) {
                    Log.d(ProcessWorker.TAG, "retning: ${File(path).name} roteres $degrees grader")
                    app.repo.rotate(pages.minBy { it.importIndeks }, degrees)
                    rotated++
                }
            }
            done++
        }
        // Sidetall og rekkefølge regnes ut på nytt også når ingenting ble snudd (f.eks. etter en ny app-versjon).
        app.processor.rebuild(bookId)
        Log.d(ProcessWorker.TAG, "retning: $rotated av ${images.size} bilder rotert")
        return Result.success(workDataOf(KEY_DONE to done, KEY_TOTAL to images.size, KEY_ROTATED to rotated))
    }

    companion object {
        private const val KEY_BOOK = "bok"
        const val KEY_DONE = "ferdig"
        const val KEY_TOTAL = "totalt"
        const val KEY_ROTATED = "rotert"

        fun workName(bookId: Long) = "retning-$bookId"

        fun enqueue(context: Context, bookId: Long) {
            val request = OneTimeWorkRequestBuilder<OrientWorker>()
                .setInputData(workDataOf(KEY_BOOK to bookId))
                .addTag(BookRepository.bookTag(bookId))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(workName(bookId), ExistingWorkPolicy.KEEP, request)
        }
    }
}
