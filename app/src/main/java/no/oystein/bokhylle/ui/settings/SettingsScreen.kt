package no.oystein.bokhylle.ui.settings

import no.oystein.bokhylle.claude.ExchangeRates
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import no.oystein.bokhylle.app
import no.oystein.bokhylle.claude.ClaudeModel
import no.oystein.bokhylle.claude.formatUsd
import no.oystein.bokhylle.data.UsageTotal

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, onImport: (path: String, undo: Boolean) -> Unit = { _, _ -> }) {
    val context = LocalContext.current
    val claude = context.app.claude
    var hint by remember { mutableStateOf(claude.keys.hint()) }
    var input by remember { mutableStateOf("") }
    val total by context.app.db.claude().observeTotal().collectAsState(initial = UsageTotal(0, 0, 0.0))

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Innstillinger") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Tilbake") } },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Claude API", style = MaterialTheme.typography.titleLarge)
            Text(
                "Med en API-nøkkel analyserer Claude sidebildene: avsnitt, overskrifter, faktabokser, figurer og fotnoter " +
                    "blir riktige. Uten nøkkel, eller uten nett, brukes tekstgjenkjenningen på telefonen.",
                style = MaterialTheme.typography.bodyMedium,
            )
            if (hint != null) {
                Text("Nøkkel lagret (slutter på …$hint). Den lagres kryptert på telefonen.", style = MaterialTheme.typography.bodyMedium)
            }
            OutlinedTextField(
                value = input,
                onValueChange = { input = it.trim() },
                label = { Text(if (hint == null) "API-nøkkel" else "Ny API-nøkkel") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { claude.keys.set(input); input = ""; hint = claude.keys.hint() }, enabled = input.isNotEmpty()) { Text("Lagre nøkkel") }
                if (hint != null) OutlinedButton(onClick = { claude.keys.set(null); hint = null }) { Text("Fjern nøkkel") }
            }

            Text("Modeller og priser", style = MaterialTheme.typography.titleMedium)
            ClaudeModel.ALL.forEach { m ->
                Text(
                    "${m.name}: ${m.description}. Ca. ${formatUsd(m.estimate(100, batch = true))} per 100 sider (hel bok som batch, halv pris).",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Text("Modellen velges per bok på bokskjermen. Standard er ${ClaudeModel.DEFAULT.name}.", style = MaterialTheme.typography.bodySmall)

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Forbruk totalt", style = MaterialTheme.typography.titleMedium)
                    Text(formatUsd(total.kostnadUsd), style = MaterialTheme.typography.headlineSmall)
                    Text("${"%,d".format(total.inputTokens)} tokens inn, ${"%,d".format(total.outputTokens)} tokens ut", style = MaterialTheme.typography.bodySmall)
                    Text("Beregnet fra forbruket API-et rapporterer for hvert kall.", style = MaterialTheme.typography.bodySmall)
                    ExchangeRateRow()
                }
            }

            HorizontalDivider()
            BackupSettings(onImport)
        }
    }
}

/** Dollarkursen kronebeløpene regnes om med, og når den sist ble hentet fra Norges Bank. */
@Composable
private fun ExchangeRateRow() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var rate by remember { mutableStateOf(ExchangeRates.nokPerUsd) }
    var date by remember { mutableStateOf(ExchangeRates.date) }
    var busy by remember { mutableStateOf(false) }
    val nb = java.util.Locale("nb", "NO")
    Text(
        if (date != null) "Kurs: %.2f kr per dollar (Norges Bank, %s). Oppdateres daglig.".format(nb, rate, date)
        else "Kurs: ca. %.1f kr per dollar (fast anslag; hentes fra Norges Bank når telefonen er på nett).".format(nb, rate),
        style = MaterialTheme.typography.bodySmall,
    )
    TextButton(
        enabled = !busy,
        onClick = {
            busy = true
            scope.launch {
                val r = ExchangeRates.refresh(context)
                busy = false
                if (r != null) { rate = r.nokPerUnit; date = r.date }
                else android.widget.Toast.makeText(context, "Fikk ikke hentet kursen (nett?)", android.widget.Toast.LENGTH_SHORT).show()
            }
        },
    ) { Text(if (busy) "Henter kurs …" else "Oppdater kursen nå") }
}
