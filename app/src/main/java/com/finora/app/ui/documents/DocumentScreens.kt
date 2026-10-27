package com.finora.app.ui.documents

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.finora.app.AppContainer
import com.finora.app.core.Dates
import com.finora.app.data.db.DocumentEntity
import com.finora.app.data.db.DocumentLinkEntity
import com.finora.app.domain.model.DocumentKind
import com.finora.app.domain.model.LinkTarget
import com.finora.app.ui.Routes
import com.finora.app.ui.appViewModel
import com.finora.app.ui.components.EmptyState
import com.finora.app.ui.components.FinoraTopBar
import com.finora.app.ui.components.InfoNote
import com.finora.app.ui.components.KeyValueRow
import com.finora.app.ui.components.SectionCard
import com.finora.app.ui.components.SectionHeader
import com.finora.app.ui.components.SelectField
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalCoroutinesApi::class)
class DocumentsViewModel(private val c: AppContainer) : ViewModel() {
    val query = MutableStateFlow("")
    val kind = MutableStateFlow<DocumentKind?>(null)
    val docs = combine(query, kind) { q, k -> q to k }.flatMapLatest { (q, k) -> c.db.documents().search(q.trim().ifEmpty { null }?.let { "%$it%" }, k?.name) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    suspend fun import(uri: Uri, title: String, kind: DocumentKind, tags: String, notes: String?): Result<Long> = runCatching { c.vault.import(uri, title, kind, tags, notes) }
}

@Composable
fun DocumentsScreen(open: (String) -> Unit, back: (() -> Unit)?) {
    val vm = appViewModel { DocumentsViewModel(it) }
    val docs by vm.docs.collectAsStateWithLifecycle()
    val q by vm.query.collectAsStateWithLifecycle()
    val kind by vm.kind.collectAsStateWithLifecycle()
    var pending by remember { mutableStateOf<Uri?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> pending = uri }
    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = { FinoraTopBar("Documents", back) },
        floatingActionButton = { ExtendedFloatingActionButton(onClick = { picker.launch(arrayOf("image/*", "application/pdf")) }, icon = { Icon(Icons.Filled.Add, null) }, text = { Text("Add") }) },
    ) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(20.dp, 4.dp, 20.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                OutlinedTextField(q, { vm.query.value = it }, leadingIcon = { Icon(Icons.Filled.Search, null) }, placeholder = { Text("Title, tag or note") }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large)
            }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item { FilterChip(kind == null, { vm.kind.value = null }, { Text("All") }) }
                    items(DocumentKind.entries.toList()) { k -> FilterChip(kind == k, { vm.kind.value = k }, { Text(k.label) }) }
                }
            }
            item { InfoNote("Files are encrypted on this phone with a key held by Android's keystore. They are never uploaded.") }
            val list = docs
            if (list != null && list.isEmpty()) item { EmptyState(Icons.Filled.Folder, "No documents", "Add receipts, loan agreements, statements and warranties, and link them to transactions, loans, bills or investments.") }
            items(list.orEmpty(), key = { it.id }) { d ->
                SectionCard(onClick = { open(Routes.document(d.id)) }, padding = 14.dp) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Description, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(d.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(listOf(d.kind.label, Dates.formatDay(d.createdAt), "${d.sizeBytes / 1024} KB").joinToString(" · ") + if (d.tags.isNotBlank()) " · #" + d.tags.replace(",", " #") else "",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
    pending?.let { uri ->
        val scope = rememberCoroutineScope()
        var title by remember { mutableStateOf("") }
        var k by remember { mutableStateOf(DocumentKind.RECEIPT) }
        var tags by remember { mutableStateOf("") }
        var notes by remember { mutableStateOf("") }
        var err by remember { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { pending = null }, title = { Text("Add to vault") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(title, { title = it }, label = { Text("Title") }, singleLine = true)
                    SelectField("Kind", DocumentKind.entries.toList(), k, { it.label }, { k = it })
                    OutlinedTextField(tags, { tags = it }, label = { Text("Tags, comma separated") }, singleLine = true)
                    OutlinedTextField(notes, { notes = it }, label = { Text("Notes") })
                    err?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = { TextButton(onClick = { scope.launch { vm.import(uri, title, k, tags, notes.ifBlank { null }).onSuccess { pending = null; open(Routes.document(it)) }.onFailure { err = it.message } } }) { Text("Add") } },
            dismissButton = { TextButton(onClick = { pending = null }) { Text("Cancel") } },
        )
    }
}

class DocumentDetailViewModel(private val c: AppContainer, val id: Long) : ViewModel() {
    val doc = c.db.documents().observe(id).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val links = c.db.documents().observeLinks(id).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val options = combine(c.db.loans().observeAll(), c.db.investments().observeAll(), c.db.billSplits().observeSummaries(), c.db.people().observeAll(), c.db.transactions().observeRecent(60)) { l, i, s, p, t ->
        mapOf(
            LinkTarget.LOAN to l.map { it.id to it.title },
            LinkTarget.INVESTMENT to i.map { it.id to it.name },
            LinkTarget.BILL_SPLIT to s.map { it.split.id to it.split.title },
            LinkTarget.PERSON to p.map { it.id to it.name },
            LinkTarget.TRANSACTION to t.map { it.txn.id to "${it.merchantName ?: it.txn.rawCounterparty ?: it.txn.type.label} · ${com.finora.app.core.Money.format(it.txn.amountPaise)} · ${Dates.formatDay(it.txn.occurredAt)}" },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    suspend fun preview(d: DocumentEntity): ImageBitmap? = if (!d.mimeType.startsWith("image/")) null else withContext(Dispatchers.IO) {
        runCatching { c.vault.readBytes(d).let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() } }.getOrNull()
    }
    suspend fun exportForViewing(d: DocumentEntity) = c.vault.decryptToCache(d)
    fun link(target: LinkTarget, targetId: Long) = viewModelScope.launch { c.db.documents().link(DocumentLinkEntity(id, target, targetId)) }
    fun unlink(l: DocumentLinkEntity) = viewModelScope.launch { c.db.documents().unlink(l) }
    fun update(d: DocumentEntity) = viewModelScope.launch { c.db.documents().update(d) }
    fun delete(done: () -> Unit) = viewModelScope.launch { doc.value?.let { c.vault.delete(it) }; done() }
}

@Composable
fun DocumentDetailScreen(id: Long, back: () -> Unit) {
    val vm = appViewModel("doc$id") { DocumentDetailViewModel(it, id) }
    val doc by vm.doc.collectAsStateWithLifecycle()
    val links by vm.links.collectAsStateWithLifecycle()
    val options by vm.options.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var image by remember { mutableStateOf<ImageBitmap?>(null) }
    var linkOpen by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    Scaffold(contentWindowInsets = WindowInsets(0), topBar = { FinoraTopBar(doc?.title ?: "Document", back) { IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Filled.Delete, "Delete") } } }) { pad ->
        val d = doc ?: return@Scaffold
        LaunchedEffect(d.id) { image = vm.preview(d) }
        var tags by rememberSaveable(d.id) { mutableStateOf(d.tags) }
        var notes by rememberSaveable(d.id) { mutableStateOf(d.notes ?: "") }
        Column(Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            image?.let { Image(it, d.title, Modifier.fillMaxWidth().heightIn(max = 420.dp).clip(MaterialTheme.shapes.large), contentScale = ContentScale.Fit) }
            OutlinedButton(onClick = {
                scope.launch {
                    val f = vm.exportForViewing(d)
                    val uri = FileProvider.getUriForFile(context, context.packageName + ".files", f)
                    val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, d.mimeType).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    runCatching { context.startActivity(Intent.createChooser(intent, "Open with")) }
                }
            }) { Icon(Icons.Filled.OpenInNew, null); Spacer(Modifier.width(8.dp)); Text("Open in another app") }
            Text("Opening decrypts a temporary copy in Finora's cache so the other app can read it.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SectionCard {
                KeyValueRow("Kind", d.kind.label)
                KeyValueRow("Added", Dates.formatDay(d.createdAt))
                d.originalFileName?.let { KeyValueRow("File", it) }
                KeyValueRow("Size", "${d.sizeBytes / 1024} KB")
            }
            OutlinedTextField(tags, { tags = it }, label = { Text("Tags") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(notes, { notes = it }, label = { Text("Notes") }, minLines = 2, modifier = Modifier.fillMaxWidth())
            Button(onClick = { vm.update(d.copy(tags = tags.lowercase(), notes = notes.ifBlank { null })) }) { Text("Save") }
            SectionHeader("Linked to", "Link") { linkOpen = true }
            if (links.isEmpty()) Text("Nothing yet.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            links.forEach { l ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${l.target.name.lowercase().replace('_', ' ').replaceFirstChar(Char::uppercase)}: ${options[l.target]?.firstOrNull { it.first == l.targetId }?.second ?: "#${l.targetId}"}", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = { vm.unlink(l) }) { Text("Unlink") }
                }
            }
        }
    }
    if (linkOpen) {
        var target by remember { mutableStateOf(LinkTarget.TRANSACTION) }
        var choice by remember { mutableStateOf<Pair<Long, String>?>(null) }
        AlertDialog(
            onDismissRequest = { linkOpen = false }, title = { Text("Link document") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SelectField("Link to", LinkTarget.entries.toList(), target, { it.name.lowercase().replace('_', ' ').replaceFirstChar(Char::uppercase) }, { target = it; choice = null })
                    SelectField("Which", options[target].orEmpty(), choice, { it.second }, { choice = it })
                }
            },
            confirmButton = { TextButton(enabled = choice != null, onClick = { vm.link(target, choice!!.first); linkOpen = false }) { Text("Link") } },
            dismissButton = { TextButton(onClick = { linkOpen = false }) { Text("Cancel") } },
        )
    }
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false }, title = { Text("Delete this document?") },
        text = { Text("The encrypted file is removed from this phone. It stays in any backup you made earlier.") },
        confirmButton = { TextButton(onClick = { confirmDelete = false; vm.delete(back) }) { Text("Delete") } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
    )
}
