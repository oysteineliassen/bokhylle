package no.oystein.bokhylle.ui.backup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import no.oystein.bokhylle.app
import no.oystein.bokhylle.backup.BackupManager
import no.oystein.bokhylle.backup.BookCompare
import no.oystein.bokhylle.backup.BookInfo
import no.oystein.bokhylle.backup.BookPackage
import no.oystein.bokhylle.backup.CompareStatus
import no.oystein.bokhylle.backup.RestoreMode
import no.oystein.bokhylle.backup.RestorePreview
import no.oystein.bokhylle.backup.RestoreWorker
import no.oystein.bokhylle.data.Book
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

private fun date(t: Long?) = if (t == null || t <= 0) "ukjent" else SimpleDateFormat("d. MMM yyyy 'kl.' HH:mm", Locale("nb", "NO")).format(Date(t))

private sealed class Loaded {
    data class BookFile(val info: BookInfo, val existing: Book?) : Loaded()
    data class Backup(val preview: RestorePreview) : Loaded()
    data class Error(val message: String) : Loaded()
}

private enum class BookChoice(val label: String) { REPLACE("Erstatt boka i appen"), KEEP_BOTH("Behold begge"), CANCEL("Avbryt") }

/**
 * Import av en .bokhylle-fil eller gjenoppretting fra en .bokhylle-backup, med forhåndsvisning.
 * [undo] = «Angre siste gjenoppretting» (bruker kopien fra før gjenopprettingen, uten ny sikkerhetskopi).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportScreen(path: String, undo: Boolean, onBack: () -> Unit, onDone: (bookId: Long?) -> Unit) {
    val context = LocalContext.current
    val app = context.app
    val file = File(path)
    var loaded by remember { mutableStateOf<Loaded?>(null) }
    var work by remember { mutableStateOf<UUID?>(null) }
    var progressText by remember { mutableStateOf("") }

    LaunchedEffect(path) {
        loaded = withContext(Dispatchers.IO) {
            try {
                when (file.inputStream().use { BackupManager.kindOf(it) }) {
                    "book" -> {
                        val info = file.inputStream().use { BookPackage.peek(it) }
                        Loaded.BookFile(info, app.db.books().all().firstOrNull { it.uuid == info.uuid })
                    }
                    "backup" -> Loaded.Backup(file.inputStream().use { app.backup.preview(it) })
                    else -> Loaded.Error("Filen er verken en Bokhylle-bok eller en sikkerhetskopi, eller den er skadet.")
                }
            } catch (e: Exception) {
                Loaded.Error(e.message ?: "Kunne ikke lese filen.")
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (undo) "Angre gjenoppretting" else "Importer") },
                navigationIcon = { IconButton(onClick = onBack, enabled = work == null) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Tilbake") } },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val id = work
            if (id != null) {
                var info by remember { mutableStateOf<WorkInfo?>(null) }
                LaunchedEffect(id) { WorkManager.getInstance(context).getWorkInfoByIdFlow(id).collect { info = it } }
                when (info?.state) {
                    WorkInfo.State.SUCCEEDED -> LaunchedEffect(Unit) {
                        val result = info?.outputData?.getLong(RestoreWorker.KEY_RESULT, -1L) ?: -1L
                        onDone(if (loaded is Loaded.BookFile) result.takeIf { it >= 0 } else null)
                    }
                    WorkInfo.State.FAILED -> Text(
                        "Importen feilet: ${info?.outputData?.getString(RestoreWorker.KEY_ERROR) ?: ""}. Biblioteket er ikke endret, " +
                            "eller kan settes tilbake med «Angre siste gjenoppretting» i Innstillinger.",
                        color = MaterialTheme.colorScheme.error,
                    )
                    else -> {
                        Text(progressText.ifEmpty { "Importerer … Dette fortsetter i bakgrunnen." })
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                }
                return@Column
            }
            when (val l = loaded) {
                null -> LinearProgressIndicator(Modifier.fillMaxWidth())
                is Loaded.Error -> Text(l.message, color = MaterialTheme.colorScheme.error)
                is Loaded.BookFile -> BookImport(l) { choice ->
                    if (choice == BookChoice.CANCEL) { onBack(); return@BookImport }
                    work = RestoreWorker.enqueue(
                        context,
                        workDataOf(
                            RestoreWorker.KEY_FILE to path, RestoreWorker.KEY_KIND to "book",
                            RestoreWorker.KEY_REPLACE to (if (choice == BookChoice.REPLACE) l.existing?.id ?: -1L else -1L),
                            RestoreWorker.KEY_NEW_UUID to (choice == BookChoice.KEEP_BOTH && l.existing != null),
                        ),
                    )
                }
                is Loaded.Backup -> BackupRestore(l.preview, undo) { mode, use ->
                    progressText = "Gjenoppretter … Det tas først en sikkerhetskopi av biblioteket slik det er nå."
                    work = RestoreWorker.enqueue(
                        context,
                        workDataOf(
                            RestoreWorker.KEY_FILE to path, RestoreWorker.KEY_KIND to "backup",
                            RestoreWorker.KEY_MODE to mode.name, RestoreWorker.KEY_USE to use.toTypedArray(),
                            RestoreWorker.KEY_UNDO to undo,
                        ),
                    )
                }
            }
        }
    }
}

@Composable
private fun BookImport(l: Loaded.BookFile, onChoice: (BookChoice) -> Unit) {
    val info = l.info
    Text(info.title, style = MaterialTheme.typography.headlineSmall)
    if (info.author.isNotBlank()) Text(info.author, style = MaterialTheme.typography.titleMedium)
    Text("${info.pages} sider · ${info.editedPages} rettede · sist endret ${date(info.changed)}")
    val existing = l.existing
    if (existing == null) {
        Button(onClick = { onChoice(BookChoice.KEEP_BOTH) }, modifier = Modifier.fillMaxWidth()) { Text("Importer boka") }
        return
    }
    val fileNewer = info.changed > existing.sistEndret
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Boka finnes fra før", fontWeight = FontWeight.Bold)
            Text("I appen: sist endret ${date(existing.sistEndret)}")
            Text("I filen: sist endret ${date(info.changed)}")
            Text(if (fileNewer) "Filen er nyest." else if (info.changed == existing.sistEndret) "Begge er like gamle." else "Boka i appen er nyest.")
        }
    }
    val suggested = if (fileNewer) BookChoice.REPLACE else BookChoice.CANCEL
    var choice by remember { mutableStateOf(suggested) }
    BookChoice.entries.forEach { c ->
        Row(Modifier.fillMaxWidth().selectable(selected = choice == c, onClick = { choice = c }), verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = choice == c, onClick = { choice = c })
            Text(c.label + if (c == suggested) " (anbefalt)" else "")
        }
    }
    if (choice == BookChoice.REPLACE) Text("Det tas automatisk en sikkerhetskopi av biblioteket før boka erstattes.", style = MaterialTheme.typography.bodySmall)
    Button(onClick = { onChoice(choice) }, modifier = Modifier.fillMaxWidth()) { Text(if (choice == BookChoice.CANCEL) "Avbryt" else "Fortsett") }
}

@Composable
private fun BackupRestore(p: RestorePreview, undo: Boolean, onRestore: (RestoreMode, Set<String>) -> Unit) {
    var mode by remember { mutableStateOf(if (undo) RestoreMode.REPLACE_ALL else RestoreMode.MERGE) }
    var confirmText by remember { mutableStateOf("") }
    // Ved konflikt foreslås alltid den nyeste versjonen.
    val useBackup = remember { mutableStateListOf<String>().apply { addAll(p.compare.filter { it.status == CompareStatus.NEWER }.map { it.uuid }) } }

    Text(if (p.beforeRestore) "Kopi tatt før en gjenoppretting" else "Sikkerhetskopi", style = MaterialTheme.typography.titleLarge)
    Text("Laget ${date(p.created)} · ${p.books.size} bøker")

    if (p.olderThanLibrary) {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
            Text(
                "Denne kopien er fra ${date(p.created)}. Med «Erstatt alt» vil ${p.lostBooksOnReplace} bøker og " +
                    "${p.lostEditsOnReplace} rettede sider gå tapt.",
                modifier = Modifier.padding(12.dp),
            )
        }
    }

    val groups = p.compare.groupBy { it.status }
    listOf(CompareStatus.NEW, CompareStatus.NEWER, CompareStatus.OLDER, CompareStatus.MISSING, CompareStatus.SAME).forEach { status ->
        val list = groups[status].orEmpty()
        if (list.isEmpty()) return@forEach
        Text("${status.label} (${list.size})", style = MaterialTheme.typography.titleSmall)
        list.forEach { c -> CompareRow(c, mode, useBackup) }
    }

    Text("Hvordan?", style = MaterialTheme.typography.titleMedium)
    Row(Modifier.fillMaxWidth().selectable(selected = mode == RestoreMode.MERGE, onClick = { mode = RestoreMode.MERGE }), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = mode == RestoreMode.MERGE, onClick = { mode = RestoreMode.MERGE })
        Column {
            Text("Slå sammen (anbefalt)")
            Text("Nye bøker legges til. Der boka finnes begge steder, brukes versjonen du har krysset av for.", style = MaterialTheme.typography.bodySmall)
        }
    }
    Row(Modifier.fillMaxWidth().selectable(selected = mode == RestoreMode.REPLACE_ALL, onClick = { mode = RestoreMode.REPLACE_ALL }), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = mode == RestoreMode.REPLACE_ALL, onClick = { mode = RestoreMode.REPLACE_ALL })
        Column {
            Text("Erstatt alt")
            Text("Hele biblioteket byttes ut med innholdet i kopien.", style = MaterialTheme.typography.bodySmall)
        }
    }
    val needsConfirm = mode == RestoreMode.REPLACE_ALL && !undo
    if (needsConfirm) {
        OutlinedTextField(
            value = confirmText, onValueChange = { confirmText = it },
            label = { Text("Skriv ERSTATT for å bekrefte") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
    }
    if (!undo) Text("Biblioteket slik det er nå lagres automatisk først, så du kan angre i Innstillinger.", style = MaterialTheme.typography.bodySmall)
    Button(
        enabled = !needsConfirm || confirmText.trim() == "ERSTATT",
        onClick = { onRestore(mode, useBackup.toSet()) },
        modifier = Modifier.fillMaxWidth(),
    ) { Text(if (mode == RestoreMode.MERGE) "Slå sammen" else "Erstatt alt") }
}

@Composable
private fun CompareRow(c: BookCompare, mode: RestoreMode, useBackup: MutableList<String>) {
    val conflict = c.status in setOf(CompareStatus.NEWER, CompareStatus.OLDER, CompareStatus.SAME)
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        if (conflict && mode == RestoreMode.MERGE) {
            Checkbox(checked = c.uuid in useBackup, onCheckedChange = { if (it) useBackup += c.uuid else useBackup -= c.uuid })
        }
        Column(Modifier.weight(1f)) {
            Text(c.title, style = MaterialTheme.typography.bodyLarge)
            val parts = listOfNotNull(
                c.backupChanged?.let { "kopien: ${date(it)}" },
                c.localChanged?.let { "appen: ${date(it)}" },
            )
            Text(parts.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
            if (conflict && mode == RestoreMode.MERGE) {
                Text(
                    if (c.uuid in useBackup) "Bruker kopien" + if (c.status == CompareStatus.NEWER) " (nyest)" else ""
                    else "Beholder appens versjon" + if (c.status == CompareStatus.OLDER) " (nyest)" else "",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}
