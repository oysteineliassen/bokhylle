package no.oystein.bokhylle.ui.book

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import no.oystein.bokhylle.app

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewBookScreen(onBack: () -> Unit, onCreated: (Long) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var title by rememberSaveable { mutableStateOf("") }
    var author by rememberSaveable { mutableStateOf("") }

    fun create(images: List<android.net.Uri> = emptyList(), preCropped: Boolean = false) = scope.launch {
        val app = context.app
        val id = app.repo.createBook(title, author)
        // Kopieringen fortsetter selv om skjermen lukkes.
        if (images.isNotEmpty()) app.appScope.launch { app.importer.import(id, images, preCropped) }
        onCreated(id)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Ny bok") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Tilbake") } },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = title, onValueChange = { title = it }, label = { Text("Tittel") }, singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = author, onValueChange = { author = it }, label = { Text("Forfatter") }, singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "Ta bilder av alle sidene med kameraet først, og velg dem så her – gjerne alle på én gang. " +
                    "Rekkefølgen finnes ut fra sidetallene.",
                style = MaterialTheme.typography.bodyMedium,
            )
            ImportButtons(enabled = title.isNotBlank()) { uris, preCropped -> create(uris, preCropped) }
            TextButton(onClick = { create() }, enabled = title.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
                Text("Opprett bok uten sider")
            }
        }
    }
}
