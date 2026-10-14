package no.oystein.bokhylle.ui.review

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Crop
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Numbers
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import no.oystein.bokhylle.BokhylleApp
import no.oystein.bokhylle.app
import no.oystein.bokhylle.core.MissingPages
import no.oystein.bokhylle.core.PageNumber
import no.oystein.bokhylle.data.Page
import no.oystein.bokhylle.data.flagg
import no.oystein.bokhylle.data.sidetallVisning
import no.oystein.bokhylle.ui.book.ImportButtons
import no.oystein.bokhylle.ui.theme.Accent
import no.oystein.bokhylle.work.BookProcessor
import java.io.File

class ReviewViewModel(val bookId: Long, private val app: BokhylleApp) : ViewModel() {
    private val repo = app.repo
    val pages: StateFlow<List<Page>> = repo.pages(bookId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val missing: StateFlow<List<MissingPages>> = repo.pages(bookId)
        .map { BookProcessor.analyze(it).missing }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun move(order: List<Long>, moved: Long) = viewModelScope.launch { repo.moveTo(bookId, order, moved) }
    fun delete(page: Page) = viewModelScope.launch { repo.deletePage(page) }
    fun setNumber(page: Page, number: PageNumber?) = viewModelScope.launch { repo.setPageNumber(page, number) }
    fun accept(page: Page, accepted: Boolean) = viewModelScope.launch { repo.setAccepted(page, accepted) }
    fun resetPlacement(page: Page) = viewModelScope.launch { repo.resetPlacement(page) }
    fun setCover(page: Page) = viewModelScope.launch { repo.setCover(bookId, page.id) }
    fun import(uris: List<android.net.Uri>, preCropped: Boolean) {
        app.appScope.launch { app.importer.import(bookId, uris, preCropped) }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ReviewScreen(bookId: Long, onBack: () -> Unit, onCrop: (Long) -> Unit) {
    val context = LocalContext.current
    val vm: ReviewViewModel = viewModel(key = "kontroll-$bookId") { ReviewViewModel(bookId, context.app) }
    val pages by vm.pages.collectAsState()
    val missing by vm.missing.collectAsState()
    var onlyFlagged by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Long?>(null) }
    var missingDialog by remember { mutableStateOf<MissingPages?>(null) }
    val flagged = pages.count { it.flagget }
    val shown = if (onlyFlagged) pages.filter { it.flagget } else pages

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Kontroller rekkefølge") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Tilbake") } },
            )
        },
    ) { padding ->
        ReorderableGrid(
            items = shown,
            key = { it.id },
            onMove = { order, moved -> vm.move(order, moved) },
            dragEnabled = !onlyFlagged,
            modifier = Modifier.fillMaxSize().padding(padding),
            header = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "${pages.size} sider · $flagged flagget. Hold inne og dra for å flytte en side. Trykk på en side for flere valg.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = onlyFlagged,
                            onClick = { onlyFlagged = !onlyFlagged },
                            label = { Text("Bare flaggede ($flagged)") },
                            leadingIcon = { Icon(Icons.Outlined.Flag, null, Modifier.size(18.dp)) },
                        )
                    }
                    if (missing.isNotEmpty()) {
                        Text("Manglende sider", style = MaterialTheme.typography.titleSmall)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            missing.forEach { m ->
                                AssistChip(onClick = { missingDialog = m }, label = { Text("Mangler ${m.label}") })
                            }
                        }
                    }
                }
            },
        ) { page, dragging -> PageThumb(page, dragging, onClick = { selected = page.id }) }
    }

    selected?.let { id ->
        val page = pages.firstOrNull { it.id == id }
        if (page == null) selected = null else PageSheet(
            page = page,
            position = pages.indexOf(page) + 1,
            onDismiss = { selected = null },
            vm = vm,
            onCrop = { selected = null; onCrop(page.id) },
        )
    }

    missingDialog?.let { m ->
        val before = pages.firstOrNull { it.id == m.afterPageId }?.sidetallVisning
        AlertDialog(
            onDismissRequest = { missingDialog = null },
            title = { Text("Side ${m.label} mangler") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        buildString {
                            append(if (m.count == 1) "Én side" else "${m.count} sider")
                            if (before != null) append(" ser ut til å mangle etter side $before.") else append(" ser ut til å mangle.")
                            append(" Ta et nytt bilde av siden og legg det inn – det sorteres inn på riktig plass.")
                        },
                    )
                    ImportButtons { uris, pre -> vm.import(uris, pre); missingDialog = null }
                }
            },
            confirmButton = { TextButton(onClick = { missingDialog = null }) { Text("Lukk") } },
        )
    }
}

@Composable
private fun PageThumb(page: Page, dragging: Boolean, onClick: () -> Unit) {
    val flagged = page.flagget
    Column(
        Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (dragging) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)
            .then(if (flagged) Modifier.border(BorderStroke(3.dp, Accent.flag), RoundedCornerShape(6.dp)) else Modifier)
            .clickable(onClick = onClick)
            .padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(0.7f)) {
            AsyncImage(
                model = File(page.bildeSti),
                contentDescription = "Side ${page.sidetallVisning ?: ""}",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
            if (page.status == Page.STATUS_NY) {
                Text(
                    "Venter…",
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.align(Alignment.Center).background(MaterialTheme.colorScheme.surface).padding(4.dp),
                )
            }
            if (flagged) {
                Icon(
                    Icons.Outlined.Flag, contentDescription = "Flagget",
                    tint = Accent.flag,
                    modifier = Modifier.align(Alignment.TopEnd).background(MaterialTheme.colorScheme.surface, RoundedCornerShape(4.dp)).size(20.dp),
                )
            }
        }
        Text(
            page.sidetallVisning ?: "–",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (page.oppdagetSidetall != null) FontWeight.Bold else FontWeight.Normal,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PageSheet(page: Page, position: Int, onDismiss: () -> Unit, vm: ReviewViewModel, onCrop: () -> Unit) {
    var editNumber by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "Side ${page.sidetallVisning ?: "uten sidetall"} · nr. $position i boka",
                style = MaterialTheme.typography.titleMedium,
            )
            AsyncImage(
                model = File(page.bildeSti), contentDescription = null, contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth().height(320.dp),
            )
            page.flagg.forEach { f ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Flag, null, tint = Accent.flag, modifier = Modifier.size(18.dp))
                    Text("  ${f.description}", style = MaterialTheme.typography.bodyMedium)
                }
            }
            if (page.sidetallManuelt) Text("Sidetallet er skrevet inn manuelt.", style = MaterialTheme.typography.bodySmall)
            if (page.manuellPlassering) Text("Siden er flyttet manuelt.", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(4.dp))
            SheetAction(Icons.Outlined.Numbers, "Sett sidetall") { editNumber = true }
            if (page.flagget) SheetAction(Icons.Outlined.TaskAlt, "Godkjenn siden (fjern flagg)") { vm.accept(page, true) }
            else if (page.godkjent) SheetAction(Icons.Outlined.Flag, "Angre godkjenning") { vm.accept(page, false) }
            SheetAction(Icons.Outlined.Crop, "Juster beskjæring") { onCrop() }
            SheetAction(Icons.Outlined.Image, "Bruk som omslag") { vm.setCover(page); onDismiss() }
            if (page.manuellPlassering) SheetAction(Icons.Outlined.Restore, "Sorter etter sidetall igjen") { vm.resetPlacement(page) }
            SheetAction(Icons.Outlined.Delete, "Slett siden") { confirmDelete = true }
        }
    }
    if (editNumber) {
        var text by remember { mutableStateOf(page.oppdagetSidetall ?: "") }
        AlertDialog(
            onDismissRequest = { editNumber = false },
            title = { Text("Sidetall") },
            text = {
                Column {
                    OutlinedTextField(
                        value = text, onValueChange = { text = it }, singleLine = true,
                        label = { Text("F.eks. 47 eller xii") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                    )
                    Text("La feltet stå tomt for å finne sidetallet automatisk.", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                val parsed = PageNumber.parse(text)
                TextButton(
                    enabled = text.isBlank() || parsed != null,
                    onClick = { vm.setNumber(page, parsed); editNumber = false },
                ) { Text("Lagre") }
            },
            dismissButton = { TextButton(onClick = { editNumber = false }) { Text("Avbryt") } },
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Slette siden?") },
            text = { Text("Bildet og teksten for denne siden slettes.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; vm.delete(page); onDismiss() }) { Text("Slett") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Avbryt") } },
        )
    }
}

@Composable
private fun SheetAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(vertical = 12.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null)
        Text("   $label", style = MaterialTheme.typography.bodyLarge)
    }
}
