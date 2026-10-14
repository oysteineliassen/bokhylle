package no.oystein.bokhylle.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import no.oystein.bokhylle.R
import no.oystein.bokhylle.app
import no.oystein.bokhylle.core.PageNumberDetector
import no.oystein.bokhylle.core.Quad
import no.oystein.bokhylle.core.rawText
import no.oystein.bokhylle.data.BookRepository
import no.oystein.bokhylle.data.Page
import no.oystein.bokhylle.image.ImageOps
import no.oystein.bokhylle.ocr.MlKitOcr
import java.io.File

/**
 * Behandler nye sider i bakgrunnen, også om appen lukkes:
 * komprimerer originalen, finner og retter opp siden, kjører OCR, og sorterer og rydder boka til slutt.
 */
class ProcessWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val app = context.app
    private val db = app.db

    override suspend fun doWork(): Result {
        val bookId = inputData.getLong(KEY_BOOK, -1)
        if (bookId < 0) return Result.failure()
        val book = db.books().get(bookId) ?: return Result.success()
        var done = 0
        while (true) {
            // Spør på nytt hver runde: sider som kommer inn mens jobben går, blir også med.
            val pending = db.pages().pending(bookId)
            if (pending.isEmpty()) break
            val total = done + pending.size
            for (page in pending) {
                trySetForeground(book.tittel, done, total)
                setProgress(workDataOf(KEY_DONE to done, KEY_TOTAL to total))
                if (isStopped) return Result.retry()
                processPage(page)
                done++
            }
        }
        app.processor.rebuild(bookId)
        return Result.success()
    }

    private suspend fun processPage(page: Page) {
        try {
            // 1. Komprimert original (opprettet etter EXIF, maks 2000 px), så hjørnene kan justeres senere.
            var originalPath = page.originalSti
            if (originalPath == null || !File(originalPath).exists()) {
                val incoming = File(page.bildeSti)
                val bitmap = ImageOps.decodeUpright(incoming) ?: error("kan ikke lese bildet")
                val file = app.files.newPageFile(page.bookId, "original")
                ImageOps.save(bitmap, file)
                bitmap.recycle()
                incoming.delete()
                originalPath = file.path
                db.pages().update(page.copy(originalSti = originalPath, bildeSti = originalPath))
            }
            val original = ImageOps.decode(originalPath) ?: error("kan ikke lese originalen")

            // 2. Finn siden og rett opp perspektivet. Er bildet et oppslag, blir høyre side en egen side.
            var quad = Quad.decode(page.beskjaering)
            if (quad == null) {
                val found = ImageOps.detectPages(original)
                quad = found.firstOrNull() ?: Quad.FULL
                if (found.size == 2) {
                    db.pages().insert(
                        page.copy(
                            id = 0,
                            bildeSti = originalPath,
                            originalSti = originalPath,
                            beskjaering = found[1].encode(),
                            importIndeks = page.importIndeks + 1,
                            kildeHash = page.kildeHash + "#hoyre",
                            status = Page.STATUS_NY,
                        ),
                    )
                    Log.d(TAG, "Side ${page.id}: oppslag delt i to")
                }
            }
            val pageBitmap = if (quad == Quad.FULL) original else ImageOps.warp(original, quad)
            val pageFile = app.files.newPageFile(page.bookId, "side")
            ImageOps.save(pageBitmap, pageFile)

            // 3. OCR på den beskårne siden.
            val ocr = MlKitOcr.recognize(pageBitmap)
            if (pageBitmap != original) pageBitmap.recycle()
            original.recycle()
            val raw = rawText(ocr.lines)
            val number = if (page.sidetallManuelt) page.oppdagetSidetall else PageNumberDetector.detect(ocr.lines)?.number?.label

            val current = db.pages().get(page.id) ?: run { pageFile.delete(); return } // slettet underveis
            if (current.bildeSti != originalPath) app.files.delete(current.bildeSti)
            db.pages().update(
                current.copy(
                    bildeSti = pageFile.path,
                    originalSti = originalPath,
                    beskjaering = quad.encode(),
                    ocrLinjer = ocr.encode(),
                    ocrTekst = raw,
                    ocrSikkerhet = ocr.lines.map { it.confidence }.average().takeIf { !it.isNaN() }?.toFloat() ?: 0f,
                    oppdagetSidetall = number,
                    sokeTekst = Page.searchText(raw),
                    status = Page.STATUS_FERDIG,
                ),
            )
        } catch (e: Exception) {
            Log.w(TAG, "Side ${page.id} feilet", e)
            db.pages().get(page.id)?.let { db.pages().update(it.copy(status = Page.STATUS_FEIL)) }
        }
    }

    private suspend fun trySetForeground(title: String, done: Int, total: Int) {
        try {
            setForeground(foregroundInfo(title, done, total))
        } catch (e: Exception) {
            // Kan nektes når appen er i bakgrunnen; jobben fortsetter likevel.
        }
    }

    private fun foregroundInfo(title: String, done: Int, total: Int): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26 && manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "Behandling av sider", NotificationManager.IMPORTANCE_LOW))
        }
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_book)
            .setContentTitle("Behandler «$title»")
            .setContentText("Side ${done + 1} av $total")
            .setProgress(total, done, false)
            .setOngoing(true)
            .setSilent(true)
            .build()
        return if (Build.VERSION.SDK_INT >= 29) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        const val TAG = "Bokhylle"
        private const val KEY_BOOK = "bok"
        const val KEY_DONE = "ferdig"
        const val KEY_TOTAL = "totalt"
        private const val CHANNEL = "behandling"
        private const val NOTIFICATION_ID = 1001

        fun workName(bookId: Long) = "behandle-$bookId"

        fun enqueue(context: Context, bookId: Long) {
            val request = OneTimeWorkRequestBuilder<ProcessWorker>()
                .setInputData(workDataOf(KEY_BOOK to bookId))
                .addTag(BookRepository.bookTag(bookId))
                .build()
            // Ny import mens en jobb går: legg til etter, så den nye jobben tar det som er igjen.
            WorkManager.getInstance(context).enqueueUniqueWork(workName(bookId), ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }
    }
}
