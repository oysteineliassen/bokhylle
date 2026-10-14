package no.oystein.bokhylle.importer

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import no.oystein.bokhylle.core.Quad
import no.oystein.bokhylle.data.AppDatabase
import no.oystein.bokhylle.data.FileStore
import no.oystein.bokhylle.data.Page
import no.oystein.bokhylle.image.ImageOps
import no.oystein.bokhylle.work.ProcessWorker
import java.security.MessageDigest

/** Kopiering pågår for en bok: «x av y». */
data class ImportProgress(val done: Int, val total: Int)

data class ImportResult(val added: Int, val duplicates: Int, val failed: Int)

/**
 * Kopierer valgte/delte bilder inn i appen og starter behandlingen i bakgrunnen.
 * Originalene kopieres uendret (rask, ingen komprimering) mens tilgangen til bildene fortsatt gjelder.
 * Samme bilde to ganger (lik SHA-256) hoppes over, også på tvers av flere importer til samme bok.
 */
class Importer(private val context: Context, private val db: AppDatabase, private val files: FileStore) {
    private val _progress = MutableStateFlow<Map<Long, ImportProgress>>(emptyMap())
    val progress: StateFlow<Map<Long, ImportProgress>> = _progress

    /** @param preCropped bildene er allerede beskåret og rettet opp (dokumentskanneren). */
    suspend fun import(bookId: Long, uris: List<Uri>, preCropped: Boolean = false): ImportResult = withContext(Dispatchers.IO) {
        val known = db.pages().hashes(bookId).toMutableSet()
        val batch = System.currentTimeMillis()
        var sortIndex = db.pages().maxSortIndex(bookId) + 1
        var added = 0
        var duplicates = 0
        var failed = 0
        _progress.update { it + (bookId to ImportProgress(0, uris.size)) }
        try {
            uris.forEachIndexed { index, uri ->
                val target = files.newIncomingFile(bookId)
                try {
                    val digest = MessageDigest.getInstance("SHA-256")
                    val input = context.contentResolver.openInputStream(uri) ?: error("kan ikke åpne $uri")
                    input.use { inp ->
                        target.outputStream().use { out ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                val n = inp.read(buf)
                                if (n < 0) break
                                digest.update(buf, 0, n)
                                out.write(buf, 0, n)
                            }
                        }
                    }
                    val hash = digest.digest().joinToString("") { "%02x".format(it) }
                    if (!known.add(hash)) {
                        target.delete()
                        duplicates++
                    } else {
                        db.pages().insert(
                            Page(
                                bookId = bookId,
                                bildeSti = target.path,
                                sorteringsIndeks = sortIndex++,
                                importBatch = batch,
                                importIndeks = index,
                                fotoTidspunkt = ImageOps.captureTime(target),
                                kildeHash = hash,
                                beskjaering = if (preCropped) Quad.FULL.encode() else null,
                            ),
                        )
                        added++
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Kunne ikke kopiere bilde ${index + 1}", e)
                    target.delete()
                    failed++
                }
                _progress.update { it + (bookId to ImportProgress(index + 1, uris.size)) }
            }
        } finally {
            _progress.update { it - bookId }
        }
        Log.d(TAG, "import bok $bookId: $added nye, $duplicates duplikater, $failed feil")
        if (added > 0) ProcessWorker.enqueue(context, bookId)
        ImportResult(added, duplicates, failed)
    }

    companion object {
        const val TAG = "Bokhylle"
    }
}
