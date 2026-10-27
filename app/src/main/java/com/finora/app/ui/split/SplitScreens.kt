package com.finora.app.ui.split

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CallSplit
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.finora.app.AppContainer
import com.finora.app.core.Dates
import com.finora.app.core.Money
import com.finora.app.data.db.DocumentLinkEntity
import com.finora.app.data.db.PersonEntity
import com.finora.app.data.repo.SplitRepository
import com.finora.app.domain.model.DocumentKind
import com.finora.app.domain.model.LinkTarget
import com.finora.app.domain.model.SplitMode
import com.finora.app.domain.receipt.ParsedReceipt
import com.finora.app.domain.receipt.ReceiptParser
import com.finora.app.domain.split.BillSplitter
import com.finora.app.domain.split.SplitItem
import com.finora.app.domain.split.SplitResult
import com.finora.app.ui.Routes
import com.finora.app.ui.appViewModel
import com.finora.app.ui.components.EmptyState
import com.finora.app.ui.components.ErrorNote
import com.finora.app.ui.components.FinoraTopBar
import com.finora.app.ui.components.InfoNote
import com.finora.app.ui.components.KeyValueRow
import com.finora.app.ui.components.MoneyField
import com.finora.app.ui.components.Pill
import com.finora.app.ui.components.SectionCard
import com.finora.app.ui.components.SectionHeader
import com.finora.app.ui.components.SelectField
import com.finora.app.ui.theme.AmountStyle
import com.finora.app.ui.theme.Finance
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

class SplitsViewModel(c: AppContainer) : ViewModel() {
    val splits = c.db.billSplits().observeSummaries().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}

@Composable
fun SplitsScreen(open: (String) -> Unit) {
    val vm = appViewModel { SplitsViewModel(it) }
    val splits by vm.splits.collectAsStateWithLifecycle()
    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = { FinoraTopBar("Bills & splitting") },
        floatingActionButton = { ExtendedFloatingActionButton(onClick = { open(Routes.SPLIT_NEW) }, icon = { Icon(Icons.Filled.Add, null) }, text = { Text("Split a bill") }) },
    ) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(20.dp, 8.dp, 20.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val list = splits
            if (list != null) {
                val owed = list.sumOf { it.outstandingPaise.coerceAtLeast(0) }
                if (list.isNotEmpty()) item {
                    SectionCard {
                        Text("Still owed on shared bills", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(Money.format(owed), style = MaterialTheme.typography.headlineMedium.merge(AmountStyle))
                    }
                }
                if (list.isEmpty()) item { EmptyState(Icons.Filled.CallSplit, "No shared bills", "Scan a receipt or enter a bill, assign items to people, and track who has paid you back.", "Split a bill") { open(Routes.SPLIT_NEW) } }
                items(list, key = { it.split.id }) { s ->
                    SectionCard(onClick = { open(Routes.split(s.split.id)) }, padding = 16.dp) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(s.split.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("${Dates.formatDay(s.split.occurredAt)} · ${s.participantCount} people · ${s.split.mode.label}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text(Money.format(s.split.totalPaise), style = MaterialTheme.typography.titleSmall.merge(AmountStyle))
                                if (s.outstandingPaise > 0) Pill("${Money.format(s.outstandingPaise)} owed") else Pill("Settled", MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer)
                            }
                        }
                    }
                }
            }
        }
    }
}

class SplitCreateViewModel(private val c: AppContainer) : ViewModel() {
    val people = c.db.people().observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    suspend fun ocr(uri: Uri): ParsedReceipt = ReceiptParser.parse(c.ocr.recognize(uri))
    suspend fun create(d: SplitRepository.Draft, keepPhoto: Uri?): Result<Long> {
        val r = c.splits.create(d)
        r.getOrNull()?.let { id ->
            if (keepPhoto != null) runCatching {
                val docId = c.vault.import(keepPhoto, "Receipt · ${d.title}", DocumentKind.RECEIPT, "receipt", null)
                c.db.documents().link(DocumentLinkEntity(docId, LinkTarget.BILL_SPLIT, id))
            }
        }
        return r
    }
    suspend fun newPerson(name: String) = c.transactions.createPerson(name)
}

/** Editable item row; amounts kept as text so partial input never loses keystrokes. */
private class ItemDraft(label: String, amount: String, sharedBy: Set<Int>) {
    var label by mutableStateOf(label)
    var amount by mutableStateOf(amount)
    var sharedBy by mutableStateOf(sharedBy)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SplitCreateScreen(back: () -> Unit, created: (Long) -> Unit) {
    val vm = appViewModel { SplitCreateViewModel(it) }
    val people by vm.people.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var title by rememberSaveable { mutableStateOf("") }
    var mode by rememberSaveable { mutableStateOf(SplitMode.ITEMIZED) }
    val participants = remember { mutableStateListOf<Pair<String, Long?>>("You" to null) }
    val items = remember { mutableStateListOf<ItemDraft>() }
    val custom = remember { mutableStateListOf("") }
    var subtotal by rememberSaveable { mutableStateOf("") }
    var tax by rememberSaveable { mutableStateOf("") }
    var discount by rememberSaveable { mutableStateOf("") }
    var service by rememberSaveable { mutableStateOf("") }
    var total by rememberSaveable { mutableStateOf("") }
    var acceptRounding by rememberSaveable { mutableStateOf(false) }
    var paidBy by rememberSaveable { mutableStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    var scanning by remember { mutableStateOf(false) }
    var receipt by remember { mutableStateOf<ParsedReceipt?>(null) }
    var photo by remember { mutableStateOf<Uri?>(null) }
    var keepPhoto by rememberSaveable { mutableStateOf(true) }
    var addPerson by remember { mutableStateOf(false) }
    var captureUri by remember { mutableStateOf<Uri?>(null) }

    fun applyReceipt(uri: Uri) {
        scanning = true; error = null
        scope.launch {
            runCatching { vm.ocr(uri) }.onSuccess { r ->
                receipt = r; photo = uri
                if (title.isBlank()) title = r.merchant ?: ""
                items.clear()
                r.items.forEach { items += ItemDraft(it.name, Money.toInput(it.totalPaise), participants.indices.toSet()) }
                tax = if (r.taxPaise > 0) Money.toInput(r.taxPaise) else ""
                discount = if (r.discountPaise > 0) Money.toInput(r.discountPaise) else ""
                service = if (r.servicePaise > 0) Money.toInput(r.servicePaise) else ""
                total = r.totalPaise?.let(Money::toInput) ?: ""
                mode = SplitMode.ITEMIZED
                if (r.items.isEmpty()) error = "No line items could be read. Enter them below."
            }.onFailure { error = "Could not read the receipt: ${it.message}. Enter the bill manually." }
            scanning = false
        }
    }

    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok -> if (ok) captureUri?.let(::applyReceipt) }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> uri?.let(::applyReceipt) }

    fun draft(report: (String) -> Unit = { error = it }): SplitRepository.Draft? {
        val p = { s: String -> if (s.isBlank()) 0L else Money.parseToPaise(s) }
        val tot = p(total) ?: return null.also { report("Enter the bill total.") }
        if (tot <= 0) { report("Enter the bill total."); return null }
        val parsedItems = items.map { SplitItem(it.label.ifBlank { "Item" }, p(it.amount) ?: return null.also { _ -> report("Check the amount for ${it.label}.") }, it.sharedBy.toList()) }
        val customParsed = custom.map { p(it) ?: 0 }
        val sub = when (mode) {
            SplitMode.ITEMIZED -> parsedItems.sumOf { it.amountPaise }
            SplitMode.CUSTOM -> customParsed.sum()
            SplitMode.EQUAL -> p(subtotal) ?: 0
        }
        val taxP = p(tax) ?: 0; val disc = p(discount) ?: 0; val svc = p(service) ?: 0
        val diff = tot - (sub + taxP + svc - disc)
        return SplitRepository.Draft(
            title = title, mode = mode, participants = participants.mapIndexed { i, (n, id) -> Triple(n, id, i == 0) },
            items = parsedItems, customSubtotals = customParsed.take(participants.size), subtotalPaise = sub, taxPaise = taxP, discountPaise = disc,
            servicePaise = svc, totalPaise = tot, acceptedRoundingPaise = if (acceptRounding) diff else 0, paidByIndex = paidBy,
            occurredAt = receipt?.date?.let(Dates::startOfDayMillis) ?: System.currentTimeMillis(), receipt = receipt,
        )
    }

    val preview = remember(title, mode, participants.toList(), items.map { Triple(it.label, it.amount, it.sharedBy) }, custom.toList(), subtotal, tax, discount, service, total, acceptRounding) {
        runCatching { draft {}?.let { BillSplitter.split(it.input()) } }.getOrNull()
    }

    Scaffold(contentWindowInsets = WindowInsets(0), topBar = { FinoraTopBar("Split a bill", back) }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionCard {
                Text("Scan a receipt", style = MaterialTheme.typography.titleSmall)
                Text("Read on this phone — the photo is never uploaded. Always check what was read.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = {
                        val dir = File(context.cacheDir, "captures").apply { mkdirs() }
                        val f = File(dir, "receipt_${System.currentTimeMillis()}.jpg")
                        captureUri = FileProvider.getUriForFile(context, context.packageName + ".files", f)
                        camera.launch(captureUri!!)
                    }) { Icon(Icons.Filled.CameraAlt, null); Spacer(Modifier.width(6.dp)); Text("Camera") }
                    OutlinedButton(onClick = { gallery.launch("image/*") }) { Icon(Icons.Filled.Image, null); Spacer(Modifier.width(6.dp)); Text("Gallery") }
                    if (scanning) CircularProgressIndicator(Modifier.padding(start = 4.dp).height(22.dp).width(22.dp), strokeWidth = 2.dp)
                }
                receipt?.unreconciledPaise?.takeIf { it != 0L }?.let {
                    Spacer(Modifier.height(8.dp))
                    InfoNote("What was read differs from the printed total by ${Money.format(it)}. Correct the items or charges below.")
                }
                if (photo != null) Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { keepPhoto = !keepPhoto }) {
                    Checkbox(keepPhoto, { keepPhoto = it }); Text("Keep the photo in Documents (encrypted)", style = MaterialTheme.typography.bodyMedium)
                }
            }
            OutlinedTextField(title, { title = it }, label = { Text("What was it?") }, placeholder = { Text("Dinner at Toit") }, singleLine = true, modifier = Modifier.fillMaxWidth())

            SectionHeader("People")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                participants.forEachIndexed { i, (name, _) ->
                    FilterChip(selected = true, onClick = {}, label = { Text(name) }, trailingIcon = if (i > 0) ({
                        Icon(Icons.Filled.Close, "Remove $name", Modifier.clickable {
                            participants.removeAt(i)
                            items.forEach { it.sharedBy = it.sharedBy.filter { s -> s != i }.map { s -> if (s > i) s - 1 else s }.toSet() }
                            if (custom.size > i) custom.removeAt(i)
                            if (paidBy >= participants.size) paidBy = 0
                        })
                    }) else null)
                }
                FilterChip(selected = false, onClick = { addPerson = true }, label = { Text("Add person") }, leadingIcon = { Icon(Icons.Filled.Add, null) })
            }

            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SplitMode.entries.forEachIndexed { i, m -> SegmentedButton(selected = mode == m, onClick = { mode = m }, shape = SegmentedButtonDefaults.itemShape(i, SplitMode.entries.size)) { Text(m.label) } }
            }

            when (mode) {
                SplitMode.ITEMIZED -> {
                    items.forEachIndexed { idx, it ->
                        SectionCard(padding = 12.dp) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                OutlinedTextField(it.label, { v -> it.label = v }, label = { Text("Item") }, singleLine = true, modifier = Modifier.weight(1.4f))
                                Spacer(Modifier.width(8.dp))
                                MoneyField("Amount", it.amount, { v -> it.amount = v }, Modifier.weight(1f))
                                IconButton(onClick = { items.removeAt(idx) }) { Icon(Icons.Filled.Delete, "Remove item") }
                            }
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                participants.forEachIndexed { pi, (n, _) ->
                                    FilterChip(selected = pi in it.sharedBy, onClick = { it.sharedBy = if (pi in it.sharedBy) it.sharedBy - pi else it.sharedBy + pi }, label = { Text(n) })
                                }
                            }
                        }
                    }
                    OutlinedButton(onClick = { items += ItemDraft("", "", participants.indices.toSet()) }) { Icon(Icons.Filled.Add, null); Spacer(Modifier.width(6.dp)); Text("Add item") }
                }
                SplitMode.EQUAL -> MoneyField("Amount to split (before tax/charges)", subtotal, { subtotal = it })
                SplitMode.CUSTOM -> {
                    while (custom.size < participants.size) custom += ""
                    participants.forEachIndexed { i, (n, _) -> MoneyField("$n's share (before tax/charges)", custom.getOrElse(i) { "" }, { v -> custom[i] = v }) }
                }
            }

            SectionHeader("Charges")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MoneyField("Tax / GST", tax, { tax = it }, Modifier.weight(1f))
                MoneyField("Service / tip", service, { service = it }, Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MoneyField("Discount", discount, { discount = it }, Modifier.weight(1f))
                MoneyField("Bill total", total, { total = it }, Modifier.weight(1f))
            }
            InfoNote("Tax, service and discounts are shared in proportion to what each person had. Every share is rounded to the paisa and the shares always add up to the total exactly.")
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { acceptRounding = !acceptRounding }) {
                Checkbox(acceptRounding, { acceptRounding = it })
                Text("The printed total is rounded — accept a small difference", style = MaterialTheme.typography.bodyMedium)
            }

            SectionHeader("Who paid")
            participants.forEachIndexed { i, (n, _) ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { paidBy = i }) { RadioButton(paidBy == i, { paidBy = i }); Text(n) }
            }

            when (val p = preview) {
                is SplitResult.Ok -> SectionCard {
                    Text("Shares", style = MaterialTheme.typography.titleSmall)
                    p.shares.forEach { s -> KeyValueRow(participants.getOrNull(s.index)?.first ?: "", Money.format(s.totalPaise) + if (s.index == paidBy) " (paid)" else "") }
                }
                is SplitResult.Invalid -> InfoNote(p.message)
                null -> Unit
            }
            ErrorNote(error)
            Button(onClick = {
                error = null
                val d = draft() ?: return@Button
                scope.launch {
                    vm.create(d, if (keepPhoto) photo else null).onSuccess(created).onFailure { error = it.message }
                }
            }, modifier = Modifier.fillMaxWidth().height(52.dp), enabled = preview is SplitResult.Ok) { Text("Save bill") }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (addPerson) {
        var name by remember { mutableStateOf("") }
        var existing by remember { mutableStateOf<PersonEntity?>(null) }
        AlertDialog(
            onDismissRequest = { addPerson = false },
            title = { Text("Add a person") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    val available = people.filter { p -> participants.none { it.second == p.id } }
                    if (available.isNotEmpty()) SelectField("Someone you know", available, existing, { it.name }, { existing = it; name = "" })
                    OutlinedTextField(name, { name = it; existing = null }, label = { Text("Or a new name") }, singleLine = true)
                }
            },
            confirmButton = {
                TextButton(enabled = existing != null || name.isNotBlank(), onClick = {
                    scope.launch {
                        val entry = existing?.let { it.name to it.id } ?: (name.trim() to vm.newPerson(name.trim()))
                        participants += entry
                        items.forEach { it.sharedBy = it.sharedBy + (participants.size - 1) }
                        addPerson = false
                    }
                }) { Text("Add") }
            },
            dismissButton = { TextButton(onClick = { addPerson = false }) { Text("Cancel") } },
        )
    }
}

class SplitDetailViewModel(private val c: AppContainer, val id: Long) : ViewModel() {
    val split = c.db.billSplits().observe(id).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val participants = c.db.billSplits().observeParticipants(id).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val items = c.db.billSplits().observeItems(id).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val settlements = c.db.billSplits().observeSettlements(id).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val documents = c.db.documents().observeFor(LinkTarget.BILL_SPLIT.name, id).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    suspend fun settle(participantId: Long, amount: Long): String? = c.splits.settle(id, participantId, amount, System.currentTimeMillis(), null, null).exceptionOrNull()?.message
    fun undo(s: com.finora.app.data.db.SettlementEntity) = viewModelScope.launch { c.splits.deleteSettlement(s) }
    fun delete(done: () -> Unit) = viewModelScope.launch { split.value?.let { c.splits.delete(it) }; done() }
}

@Composable
fun SplitDetailScreen(id: Long, back: () -> Unit) {
    val vm = appViewModel("split$id") { SplitDetailViewModel(it, id) }
    val split by vm.split.collectAsStateWithLifecycle()
    val parts by vm.participants.collectAsStateWithLifecycle()
    val items by vm.items.collectAsStateWithLifecycle()
    val settlements by vm.settlements.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var settleFor by remember { mutableStateOf<Long?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }

    Scaffold(contentWindowInsets = WindowInsets(0), topBar = {
        FinoraTopBar(split?.title ?: "Shared bill", back) { IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Filled.Delete, "Delete bill") } }
    }) { pad ->
        val s = split ?: return@Scaffold
        LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(20.dp, 8.dp, 20.dp, 40.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                SectionCard {
                    Text(Money.format(s.totalPaise), style = MaterialTheme.typography.headlineMedium.merge(AmountStyle))
                    Text("${Dates.formatDay(s.occurredAt)} · paid by ${parts.firstOrNull { it.id == s.paidByParticipantId }?.displayName ?: "—"}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(10.dp))
                    KeyValueRow("Items", Money.format(s.subtotalPaise))
                    if (s.taxPaise > 0) KeyValueRow("Tax", Money.format(s.taxPaise))
                    if (s.servicePaise > 0) KeyValueRow("Service / tip", Money.format(s.servicePaise))
                    if (s.discountPaise > 0) KeyValueRow("Discount", "−" + Money.format(s.discountPaise))
                    if (s.roundingAdjustmentPaise != 0L) KeyValueRow("Rounding (accepted)", Money.format(s.roundingAdjustmentPaise))
                }
            }
            item { SectionHeader("Who owes what") }
            items(parts, key = { it.id }) { p ->
                val paid = settlements.filter { it.participantId == p.id }
                val bal = BillSplitter.balance(p.totalSharePaise, p.id == s.paidByParticipantId, paid.map { it.amountPaise })
                SectionCard(padding = 14.dp) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(p.displayName + if (p.id == s.paidByParticipantId) " · paid the bill" else "", style = MaterialTheme.typography.titleSmall)
                            Text("Share ${Money.format(p.totalSharePaise)}" + (if (p.taxSharePaise + p.serviceSharePaise > 0) " incl. ${Money.format(p.taxSharePaise + p.serviceSharePaise)} charges" else "") +
                                (if (p.discountSharePaise > 0) ", ${Money.format(p.discountSharePaise)} off" else ""), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        when {
                            p.id == s.paidByParticipantId -> Unit
                            bal.isSettled -> Pill("Settled", MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer)
                            else -> Column(horizontalAlignment = Alignment.End) {
                                Text(Money.format(bal.remainingPaise), style = MaterialTheme.typography.titleSmall.merge(AmountStyle), color = Finance.colors.warning)
                                if (!p.isMe) TextButton(onClick = { settleFor = p.id }) { Text("Record payment") }
                            }
                        }
                    }
                    paid.forEach { st ->
                        HorizontalDivider(Modifier.padding(vertical = 6.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Paid ${Money.format(st.amountPaise)} · ${Dates.formatDay(st.occurredAt)}" + if (st.transactionId != null) " · from SMS" else "", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                            TextButton(onClick = { vm.undo(st) }) { Text("Undo") }
                        }
                    }
                }
            }
            if (items.isNotEmpty()) {
                item { SectionHeader("Items") }
                item {
                    SectionCard {
                        items.forEach { it ->
                            val names = it.sharedBy.split(',').mapNotNull { id -> parts.firstOrNull { p -> p.id.toString() == id }?.displayName }
                            KeyValueRow("${it.label} · ${names.joinToString()}", Money.format(it.amountPaise))
                        }
                    }
                }
            }
        }
    }

    settleFor?.let { pid ->
        var amount by remember { mutableStateOf("") }
        var err by remember { mutableStateOf<String?>(null) }
        val p = parts.firstOrNull { it.id == pid }
        AlertDialog(
            onDismissRequest = { settleFor = null },
            title = { Text("Payment from ${p?.displayName}") },
            text = {
                Column {
                    MoneyField("Amount", amount, { amount = it }, error = err)
                    Text("If this arrived by bank transfer, link it from the transaction instead, so it is not counted as income.", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val paise = Money.parseToPaise(amount)
                    if (paise == null) { err = "Enter an amount." } else scope.launch { err = vm.settle(pid, paise); if (err == null) settleFor = null }
                }) { Text("Record") }
            },
            dismissButton = { TextButton(onClick = { settleFor = null }) { Text("Cancel") } },
        )
    }
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("Delete this bill?") },
        text = { Text("Its shares and recorded settlements are deleted too.") },
        confirmButton = { TextButton(onClick = { confirmDelete = false; vm.delete(back) }) { Text("Delete") } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
    )
}
