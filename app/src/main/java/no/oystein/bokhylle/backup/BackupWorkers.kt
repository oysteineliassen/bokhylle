package no.oystein.bokhylle.backup

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import no.oystein.bokhylle.R
import no.oystein.bokhylle.app
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

private const val TAG = "Bokhylle"
private const val CHANNEL = "sikkerhetskopi"

private fun channel(context: Context) {
    if (Build.VERSION.SDK_INT >= 26) {
        val m = context.getSystemService(NotificationManager::class.java)
        if (m.getNotificationChannel(CHANNEL) == null) {
            m.createNotificationChannel(NotificationChannel(CHANNEL, "Sikkerhetskopi og eksport", NotificationManager.IMPORTANCE_LOW))
        }
    }
}

private fun progressInfo(context: Context, id: Int, title: String, text: String, done: Int, total: Int): ForegroundInfo {
    channel(context)
    val n = NotificationCompat.Builder(context, CHANNEL)
        .setSmallIcon(R.drawable.ic_stat_book)
        .setContentTitle(title)
        .setContentText(text)
        .setProgress(total, done, total == 0)
        .setOngoing(true)
        .setSilent(true)
        .build()
    return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(id, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else ForegroundInfo(id, n)
}

/** Varsel som blir stående (f.eks. når den automatiske sikkerhetskopien feiler). */
fun notifyError(context: Context, title: String, text: String) {
    channel(context)
    val n = NotificationCompat.Builder(context, CHANNEL)
        .setSmallIcon(R.drawable.ic_stat_book)
        .setContentTitle(title)
        .setContentText(text)
        .setStyle(NotificationCompat.BigTextStyle().bigText(text))
        .setPriority(NotificationCompat.PRIORITY_DEFAULT)
        .build()
    runCatching { NotificationManagerCompat.from(context).notify(3001, n) }
}

/** Eksport av én bok (.bokhylle, EPUB eller tekstfil) eller hele biblioteket, i bakgrunnen med fremdriftsvarsel. */
class ExportWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext.app
        val kind = inputData.getString(KEY_KIND) ?: return Result.failure()
        val bookId = inputData.getLong(KEY_BOOK, -1)
        val target = inputData.getString(KEY_TARGET) ?: return Result.failure()
        val withImages = inputData.getBoolean(KEY_IMAGES, true)
        val title = when (kind) {
            KIND_EPUB -> "Lager EPUB"
            KIND_TXT -> "Lager tekstfil"
            KIND_BACKUP -> "Lager sikkerhetskopi"
            else -> "Eksporterer bok"
        }
        suspend fun progress(done: Int, total: Int) {
            runCatching { setForeground(progressInfo(applicationContext, 3002, title, if (total > 0) "$done av $total" else "", done, total)) }
        }
        progress(0, 0)
        return try {
            val uri = Uri.parse(target)
            val out = if (uri.scheme == "file") File(uri.path!!).outputStream() else app.contentResolver.openOutputStream(uri, "wt")
            out ?: throw IllegalStateException("Kunne ikke åpne målfilen")
            out.use {
                when (kind) {
                    KIND_EPUB -> EpubExporter.export(app, bookId, it) { d, t -> if (d % 10 == 0 || d == t) progressBlocking(this, title, d, t) }
                    KIND_TXT -> TxtExporter.export(app, bookId, it)
                    KIND_BACKUP -> app.backup.write(it) { d, t -> progressBlocking(this, title, d, t) }
                    else -> BookPackage.export(app, bookId, it, withImages) { d, t -> if (d % 10 == 0 || d == t) progressBlocking(this, title, d, t) }
                }
            }
            Result.success(workDataOf(KEY_TARGET to target))
        } catch (e: Exception) {
            Log.w(TAG, "eksport feilet", e)
            Result.failure(workDataOf(KEY_ERROR to (e.message ?: e.javaClass.simpleName)))
        }
    }

    companion object {
        const val KEY_KIND = "type"
        const val KEY_BOOK = "bok"
        const val KEY_TARGET = "mal"
        const val KEY_IMAGES = "bilder"
        const val KEY_ERROR = "feil"
        const val KIND_BOOK = "bok"
        const val KIND_EPUB = "epub"
        const val KIND_TXT = "txt"
        const val KIND_BACKUP = "kopi"

        fun enqueue(context: Context, kind: String, bookId: Long, target: Uri, withImages: Boolean): UUID {
            val request = OneTimeWorkRequestBuilder<ExportWorker>()
                .setInputData(workDataOf(KEY_KIND to kind, KEY_BOOK to bookId, KEY_TARGET to target.toString(), KEY_IMAGES to withImages))
                .build()
            WorkManager.getInstance(context).enqueue(request)
            return request.id
        }
    }
}

/** Fremdriften fra eksportkoden kommer synkront; varselet oppdateres uten å vente. */
private fun progressBlocking(worker: CoroutineWorker, title: String, done: Int, total: Int) {
    runCatching { worker.setForegroundAsync(progressInfo(worker.applicationContext, 3002, title, "$done av $total", done, total)) }
}

/** Gjenoppretting/import i bakgrunnen (kan ta tid med mange bilder). */
class RestoreWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext.app
        val file = File(inputData.getString(KEY_FILE) ?: return Result.failure())
        val kind = inputData.getString(KEY_KIND) ?: "backup"
        runCatching { setForeground(progressInfo(applicationContext, 3003, "Gjenoppretter", "", 0, 0)) }
        return try {
            when (kind) {
                "book" -> {
                    val replace = inputData.getLong(KEY_REPLACE, -1)
                    if (replace >= 0) {
                        // Erstatter en bok: ta kopi av biblioteket først.
                        app.backup.safetyCopy()
                        app.repo.deleteBook(replace)
                    }
                    val id = file.inputStream().use { BookPackage.import(app, it, newUuid = inputData.getBoolean(KEY_NEW_UUID, false)) }
                    Result.success(workDataOf(KEY_RESULT to id))
                }
                else -> {
                    val mode = RestoreMode.valueOf(inputData.getString(KEY_MODE) ?: RestoreMode.MERGE.name)
                    val use = inputData.getStringArray(KEY_USE)?.toSet().orEmpty()
                    val undo = inputData.getBoolean(KEY_UNDO, false)
                    val n = app.backup.restore(file, mode, use, takeSafetyCopy = !undo) { msg ->
                        runCatching { setForegroundAsync(progressInfo(applicationContext, 3003, "Gjenoppretter", msg, 0, 0)) }
                    }
                    Result.success(workDataOf(KEY_RESULT to n.toLong()))
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "gjenoppretting feilet", e)
            Result.failure(workDataOf(KEY_ERROR to (e.message ?: e.javaClass.simpleName)))
        } finally {
            if (!inputData.getBoolean(KEY_UNDO, false) && file.parentFile == File(app.cacheDir, INCOMING)) file.delete()
        }
    }

    companion object {
        const val INCOMING = "innkommende-filer"
        const val KEY_FILE = "fil"
        const val KEY_KIND = "type"
        const val KEY_MODE = "modus"
        const val KEY_USE = "bruk"
        const val KEY_UNDO = "angre"
        const val KEY_REPLACE = "erstatt"
        const val KEY_NEW_UUID = "ny_uuid"
        const val KEY_RESULT = "resultat"
        const val KEY_ERROR = "feil"

        fun enqueue(context: Context, data: androidx.work.Data): UUID {
            val request = OneTimeWorkRequestBuilder<RestoreWorker>().setInputData(data).build()
            WorkManager.getInstance(context).enqueueUniqueWork("gjenoppretting", ExistingWorkPolicy.APPEND_OR_REPLACE, request)
            return request.id
        }
    }
}

/** Automatisk sikkerhetskopi til valgt mappe, når telefonen lader og er på wifi. */
class BackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext.app
        if (app.backup.folder == null) return Result.success()
        runCatching { setForeground(progressInfo(applicationContext, 3004, "Sikkerhetskopi", "", 0, 0)) }
        return try {
            app.backup.backupToFolder { d, t ->
                runCatching { setForegroundAsync(progressInfo(applicationContext, 3004, "Sikkerhetskopi", "Bok $d av $t", d, t)) }
            }
            Log.d(TAG, "sikkerhetskopi ok")
            Result.success()
        } catch (e: Exception) {
            Log.w(TAG, "sikkerhetskopi feilet", e)
            app.backup.lastError = e.message ?: e.javaClass.simpleName
            if (runAttemptCount < 2) Result.retry() else {
                notifyError(applicationContext, "Sikkerhetskopien feilet", app.backup.lastError ?: "")
                Result.failure()
            }
        }
    }

    companion object {
        private const val NAME = "automatisk-sikkerhetskopi"

        /** Planlegger (eller stopper) den automatiske kopien etter innstillingene. */
        fun schedule(context: Context) {
            val app = context.app
            val wm = WorkManager.getInstance(context)
            val days = app.backup.frequencyDays
            if (days <= 0 || app.backup.folder == null) { wm.cancelUniqueWork(NAME); return }
            val request = PeriodicWorkRequestBuilder<BackupWorker>(days.toLong(), TimeUnit.DAYS)
                .setConstraints(Constraints.Builder().setRequiresCharging(true).setRequiredNetworkType(NetworkType.UNMETERED).build())
                .build()
            wm.enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
        }

        fun runNow(context: Context): UUID {
            val request = OneTimeWorkRequestBuilder<BackupWorker>().build()
            WorkManager.getInstance(context).enqueueUniqueWork("sikkerhetskopi-nå", ExistingWorkPolicy.KEEP, request)
            return request.id
        }
    }
}
