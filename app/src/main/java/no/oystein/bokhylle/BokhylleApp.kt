package no.oystein.bokhylle

import android.app.Application
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import no.oystein.bokhylle.data.AppDatabase
import no.oystein.bokhylle.data.BookRepository
import no.oystein.bokhylle.data.FileStore

class BokhylleApp : Application() {
    /** Lever like lenge som prosessen; brukes til kopiering som ikke skal avbrytes når en skjerm lukkes. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val db by lazy { AppDatabase.create(this) }
    val files by lazy { FileStore(this) }
    val repo by lazy { BookRepository(this, db, files) }
}

val Context.app: BokhylleApp get() = applicationContext as BokhylleApp
