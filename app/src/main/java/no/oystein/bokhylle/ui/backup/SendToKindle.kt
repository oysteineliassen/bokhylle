package no.oystein.bokhylle.ui.backup

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.work.WorkInfo
import androidx.work.WorkManager
import no.oystein.bokhylle.backup.ExportWorker
import no.oystein.bokhylle.data.Book
import java.io.File
import java.util.UUID

/**
 * «Send til Kindle»: lager en EPUB og åpner Android-delingsmenyen med filen
 * (velg Kindle-appen eller «Send to Kindle»).
 */
@Composable
fun SendToKindleButton(book: Book) {
    val context = LocalContext.current
    var work by remember { mutableStateOf<UUID?>(null) }
    var file by remember { mutableStateOf<File?>(null) }

    work?.let { id ->
        LaunchedEffect(id) {
            WorkManager.getInstance(context).getWorkInfoByIdFlow(id).collect { info ->
                when (info?.state) {
                    WorkInfo.State.SUCCEEDED -> {
                        val f = file ?: return@collect
                        val uri = FileProvider.getUriForFile(context, "${context.packageName}.filer", f)
                        val send = Intent(Intent.ACTION_SEND)
                            .setType("application/epub+zip")
                            .putExtra(Intent.EXTRA_STREAM, uri)
                            .putExtra(Intent.EXTRA_SUBJECT, book.tittel)
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        context.startActivity(Intent.createChooser(send, "Send «${book.tittel}» til Kindle"))
                        work = null
                    }
                    WorkInfo.State.FAILED, WorkInfo.State.CANCELLED -> {
                        Toast.makeText(context, "Kunne ikke lage EPUB: ${info?.outputData?.getString(ExportWorker.KEY_ERROR) ?: ""}", Toast.LENGTH_LONG).show()
                        work = null
                    }
                    else -> Unit
                }
            }
        }
    }

    OutlinedButton(
        onClick = {
            val dir = File(context.cacheDir, "del").apply { mkdirs() }
            val name = book.tittel.replace(Regex("[^\\p{L}\\p{N} ._-]"), "").trim().ifEmpty { "bok" }
            val f = File(dir, "$name.epub").apply { delete() }
            file = f
            work = ExportWorker.enqueue(context, ExportWorker.KIND_EPUB, book.id, Uri.fromFile(f), withImages = false)
        },
        enabled = work == null,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (work != null) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            Text(if (work != null) "  Lager EPUB …" else "Send til Kindle")
        }
    }
}
