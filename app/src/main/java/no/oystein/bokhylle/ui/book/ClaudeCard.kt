package no.oystein.bokhylle.ui.book

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import no.oystein.bokhylle.app
import no.oystein.bokhylle.claude.ClaudeModel
import no.oystein.bokhylle.claude.formatUsd
import no.oystein.bokhylle.data.Book
import no.oystein.bokhylle.data.ClaudeJob
import no.oystein.bokhylle.data.Page
import no.oystein.bokhylle.data.UsageTotal
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Status for Claude-analysen av boka, valg av modell og sending med kostnadsestimat. */
@Composable
fun ClaudeCard(book: Book, pages: List<Page>, onOpenSettings: () -> Unit) {
    val context = LocalContext.current
    val app = context.app
    val scope = rememberCoroutineScope()
    val jobs by app.db.claude().observeOpenJobs(book.id).collectAsState(initial = emptyList<ClaudeJob>())
    val usage by app.db.claude().observeBookTotal(book.id).collectAsState(initial = UsageTotal(0, 0, 0.0))
    val model = ClaudeModel.of(book.claudeModell)
    var confirm by remember { mutableStateOf<List<Page>?>(null) }

    val ready = pages.filter { it.status == Page.STATUS_FERDIG }
    val done = ready.count { it.blocksJson != null }
    val waiting = jobs.flatMap { j -> j.sideIder.split(',').mapNotNull { it.toLongOrNull() } }.toSet()
    val missing = ready.filter { it.blocksJson == null && it.id !in waiting }
    val flagged = ready.filter { it.flagget && it.id !in waiting }
    val failed = ready.count { it.trengerClaude }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Claude-analyse", style = MaterialTheme.typography.titleMedium)
            if (!app.claude.hasKey) {
                Text(
                    "Legg inn en API-nøkkel i Innstillinger for å få riktige avsnitt, faktabokser, figurer og fotnoter. " +
                        "Nå brukes tekstgjenkjenningen på telefonen.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedButton(onClick = onOpenSettings) { Text("Åpne innstillinger") }
                return@Column
            }
            Text("$done av ${pages.size} sider er analysert av Claude.", style = MaterialTheme.typography.bodyMedium)
            if (waiting.isNotEmpty()) {
                val since = jobs.minOf { it.opprettet }
                Text(
                    "${waiting.size} sider venter hos Claude (sendt kl. ${SimpleDateFormat("HH:mm", Locale.ROOT).format(Date(since))}). " +
                        "Batcher tar vanligvis noen minutter, men kan ta opptil et døgn. Teksten fra telefonen vises så lenge.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (failed > 0) {
                Text("$failed sider kunne ikke analyseres (feil eller ikke nett) og bruker teksten fra telefonen.", style = MaterialTheme.typography.bodySmall)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ClaudeModel.ALL.forEach { m ->
                    FilterChip(
                        selected = m.id == model.id,
                        onClick = { scope.launch { app.repo.updateBook(book.copy(claudeModell = m.id)) } },
                        label = { Text(m.name.removePrefix("Claude ")) },
                    )
                }
            }
            Text(model.description, style = MaterialTheme.typography.bodySmall)
            if (missing.isNotEmpty()) {
                Button(onClick = { confirm = missing }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (done == 0) "Send boka til Claude (${missing.size} sider)" else "Send resten (${missing.size} sider)")
                }
            }
            if (flagged.isNotEmpty()) {
                OutlinedButton(onClick = { confirm = flagged }, modifier = Modifier.fillMaxWidth()) {
                    Text("Bare flaggede sider (${flagged.size})")
                }
            }
            if (missing.isEmpty() && done > 0) {
                OutlinedButton(onClick = { confirm = ready.filter { it.id !in waiting } }, modifier = Modifier.fillMaxWidth()) {
                    Text("Analyser hele boka på nytt")
                }
            }
            if (usage.kostnadUsd > 0) {
                Text("Brukt på denne boka: ${formatUsd(usage.kostnadUsd)}", style = MaterialTheme.typography.bodySmall)
            }
        }
    }

    confirm?.let { list ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("Sende ${list.size} sider til Claude?") },
            text = {
                Text(
                    "Modell: ${model.name}\n" +
                        "Anslått kostnad: ca. ${formatUsd(model.estimate(list.size, batch = true))} " +
                        "(batch med halv pris; ${formatUsd(model.estimate(1, batch = true))} per side).\n\n" +
                        "Sidebildene sendes til Anthropic. Det faktiske forbruket vises etterpå her og i Innstillinger.",
                )
            },
            confirmButton = {
                TextButton(onClick = { app.claude.submitBook(book.id, list.map { it.id }); confirm = null }) { Text("Send") }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Avbryt") } },
        )
    }
}
