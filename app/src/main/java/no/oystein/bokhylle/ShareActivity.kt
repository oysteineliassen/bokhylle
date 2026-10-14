package no.oystein.bokhylle

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import no.oystein.bokhylle.data.Book
import no.oystein.bokhylle.ui.theme.BokhylleTheme

/**
 * Mottar bilder fra «Del» i galleriet eller kameraet. Spør om hvilken bok de skal inn i, kopierer dem
 * mens tilgangen til bildene gjelder (aktiviteten holdes åpen så lenge), og åpner så boka.
 * Samme bilde delt flere ganger legges ikke inn på nytt (sjekk på SHA-256).
 */
class ShareActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uris = sharedUris(intent)
        if (uris.isEmpty()) { finish(); return }
        setContent {
            BokhylleTheme { ShareDialog(uris) }
        }
    }

    @androidx.compose.runtime.Composable
    private fun ShareDialog(uris: List<Uri>) {
        val scope = rememberCoroutineScope()
        var books by remember { mutableStateOf<List<Book>?>(null) }
        var selected by remember { mutableStateOf<Long?>(null) } // null = ny bok
        var title by remember { mutableStateOf("") }
        var author by remember { mutableStateOf("") }
        var copying by remember { mutableStateOf<Long?>(null) }
        val progress by app.importer.progress.collectAsState()
        LaunchedEffect(Unit) {
            books = app.db.books().all()
            selected = books?.firstOrNull()?.id
        }

        val busyBook = copying
        if (busyBook != null) {
            val p = progress[busyBook]
            AlertDialog(
                onDismissRequest = {},
                title = { Text("Kopierer bilder") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("${p?.done ?: 0} av ${uris.size}")
                        LinearProgressIndicator(progress = { (p?.done ?: 0).toFloat() / uris.size }, modifier = Modifier.fillMaxWidth())
                    }
                },
                confirmButton = {},
            )
            return
        }

        val list = books ?: return
        AlertDialog(
            onDismissRequest = { finish() },
            title = { Text("Legg til ${uris.size} bilder i:") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    list.forEach { b ->
                        Row(
                            Modifier.fillMaxWidth().selectable(selected = selected == b.id, onClick = { selected = b.id }).padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = selected == b.id, onClick = { selected = b.id })
                            Text(b.tittel, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth().selectable(selected = selected == null, onClick = { selected = null }).padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = selected == null, onClick = { selected = null })
                        Text("Ny bok", style = MaterialTheme.typography.bodyLarge)
                    }
                    if (selected == null) {
                        OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("Tittel") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(value = author, onValueChange = { author = it }, label = { Text("Forfatter") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = selected != null || title.isNotBlank(),
                    onClick = {
                        scope.launch {
                            val bookId = selected ?: app.repo.createBook(title, author)
                            copying = bookId
                            // Kopieres mens denne aktiviteten lever, så tilgangen til de delte bildene gjelder.
                            lifecycleScope.launch {
                                app.importer.import(bookId, uris)
                                startActivity(
                                    Intent(this@ShareActivity, MainActivity::class.java)
                                        .putExtra(MainActivity.EXTRA_BOOK, bookId)
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                                )
                                finish()
                            }
                        }
                    },
                ) { Text("Legg til") }
            },
            dismissButton = { TextButton(onClick = { finish() }) { Text("Avbryt") } },
        )
    }

    private fun sharedUris(intent: Intent?): List<Uri> {
        intent ?: return emptyList()
        val list = when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(parcelable(intent, Intent.EXTRA_STREAM))
            Intent.ACTION_SEND_MULTIPLE -> parcelableList(intent, Intent.EXTRA_STREAM)
            else -> emptyList()
        }
        // Noen apper legger bildene bare i ClipData.
        val clip = intent.clipData
        val fromClip = if (clip != null) (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri } else emptyList()
        return (list + fromClip).distinct()
    }

    @Suppress("DEPRECATION")
    private fun parcelable(intent: Intent, key: String): Uri? =
        if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(key, Uri::class.java) else intent.getParcelableExtra<Parcelable>(key) as? Uri

    @Suppress("DEPRECATION")
    private fun parcelableList(intent: Intent, key: String): List<Uri> =
        if (Build.VERSION.SDK_INT >= 33) intent.getParcelableArrayListExtra(key, Uri::class.java).orEmpty()
        else intent.getParcelableArrayListExtra<Parcelable>(key).orEmpty().mapNotNull { it as? Uri }
}
