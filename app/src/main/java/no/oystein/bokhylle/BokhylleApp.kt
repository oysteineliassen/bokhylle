package no.oystein.bokhylle

import android.app.Application
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import no.oystein.bokhylle.backup.BackupManager
import no.oystein.bokhylle.claude.ClaudePollWorker
import no.oystein.bokhylle.claude.ClaudeService
import no.oystein.bokhylle.claude.ExchangeRates
import no.oystein.bokhylle.data.AppDatabase
import no.oystein.bokhylle.data.BookRepository
import no.oystein.bokhylle.data.FileStore
import no.oystein.bokhylle.importer.Importer
import no.oystein.bokhylle.ui.reader.ReaderPrefs
import no.oystein.bokhylle.work.BookProcessor

class BokhylleApp : Application() {
    /** Lever like lenge som prosessen; brukes til kopiering som ikke skal avbrytes når en skjerm lukkes. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val db by lazy { AppDatabase.create(this) }
    val files by lazy { FileStore(this) }
    val repo by lazy { BookRepository(this, db, files) }
    val importer by lazy { Importer(this, db, files) }
    val processor by lazy { BookProcessor(this) }
    val claude by lazy { ClaudeService(this) }
    val readerPrefs by lazy { ReaderPrefs(this) }
    val backup by lazy { BackupManager(this) }

    override fun onCreate() {
        super.onCreate()
        // Dollarkursen prisene vises med: lagret verdi nå, ny henting fra Norges Bank én gang i døgnet.
        ExchangeRates.load(this)
        ExchangeRates.schedule(this)
        // Batcher som ble sendt før appen ble lukket, sjekkes videre.
        appScope.launch { if (db.claude().openJobs().isNotEmpty()) { claude.checkNow(); claude.schedulePoll(ClaudePollWorker.INTERVAL_MINUTES) } }
    }
}

val Context.app: BokhylleApp get() = applicationContext as BokhylleApp
