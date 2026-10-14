package no.oystein.bokhylle.data

import android.content.Context
import java.io.File
import java.util.UUID

/**
 * Bildene ligger i appens interne lagring, én mappe per bok. Databasen lagrer bare filstiene.
 * Innkommende originaler legges i en egen mappe til de er komprimert.
 */
class FileStore(context: Context) {
    private val booksDir = File(context.filesDir, "boker")
    private val incomingDir = File(context.noBackupFilesDir, "innkommende")

    fun bookDir(bookId: Long) = File(booksDir, bookId.toString()).apply { mkdirs() }
    fun incomingDir(bookId: Long) = File(incomingDir, bookId.toString()).apply { mkdirs() }

    fun newPageFile(bookId: Long, prefix: String) = File(bookDir(bookId), "$prefix-${UUID.randomUUID()}.jpg")
    fun newIncomingFile(bookId: Long) = File(incomingDir(bookId), "${UUID.randomUUID()}.bin")

    fun deleteBook(bookId: Long) {
        File(booksDir, bookId.toString()).deleteRecursively()
        File(incomingDir, bookId.toString()).deleteRecursively()
    }

    fun delete(path: String?) {
        if (path != null) File(path).delete()
    }
}
