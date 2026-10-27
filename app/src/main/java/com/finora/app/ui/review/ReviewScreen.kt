package com.finora.app.ui.review

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.finora.app.core.Dates
import com.finora.app.core.Money
import com.finora.app.data.db.BillParticipantEntity
import com.finora.app.data.db.BillSplitSummary
import com.finora.app.data.db.LoanEntity
import com.finora.app.data.db.PersonEntity
import com.finora.app.domain.merchant.MerchantLearningPolicy
import com.finora.app.domain.merchant.MerchantNormalizer
import com.finora.app.domain.merchant.RankedPlace
import com.finora.app.domain.model.CategoryKind
import com.finora.app.domain.model.Direction
import com.finora.app.domain.model.LoanKind
import com.finora.app.domain.model.LoanPaymentKind
import com.finora.app.domain.model.ReviewReason
import com.finora.app.domain.model.TxnType
import com.finora.app.ui.Routes
import com.finora.app.ui.appViewModel
import com.finora.app.ui.components.AmountText
import com.finora.app.ui.components.EmptyState
import com.finora.app.ui.components.FinoraTopBar
import com.finora.app.ui.components.InfoNote
import com.finora.app.ui.components.Pill
import com.finora.app.ui.components.SectionCard
import com.finora.app.ui.components.SectionHeader
import com.finora.app.ui.components.SelectField
import com.finora.app.ui.map.MapPreview
import kotlinx.coroutines.launch

@Composable
fun ReviewScreen(back: () -> Unit, open: (String) -> Unit) {
    val vm = appViewModel { ReviewViewModel(it) }
    val groups by vm.groups.collectAsStateWithLifecycle()
    val unparsed by vm.unparsed.collectAsStateWithLifecycle()
    val msg by vm.message.collectAsStateWithLifecycle()
    var active by remember { mutableStateOf<String?>(null) }
    val snack = remember { SnackbarHostState() }
    LaunchedEffect(msg) { msg?.let { snack.showSnackbar(it); vm.message.value = null } }

    Scaffold(contentWindowInsets = WindowInsets(0), topBar = { FinoraTopBar("Review", back) }, snackbarHost = { SnackbarHost(snack) }) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(20.dp, 8.dp, 20.dp, 40.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val g = groups
            if (g != null && g.isEmpty() && unparsed.isEmpty()) item {
                EmptyState(Icons.Filled.TaskAlt, "All caught up", "Nothing needs a decision. New transactions that need one appear here, and the daily reminders bring you back.")
            }
            g?.groupBy { it.reason }?.forEach { (reason, list) ->
                item(key = "h${reason.name}") { SectionHeader("${reason.label} · ${list.sumOf { it.rows.size }}") }
                items(list, key = { it.key }) { grp -> GroupCard(grp) { active = grp.key } }
            }
            if (unparsed.isNotEmpty()) {
                item { SectionHeader("Messages Finora could not read · ${unparsed.size}") }
                item { InfoNote("These looked like bank messages but no rule understood the wording. Add the transaction yourself if it matters, or dismiss it.") }
                items(unparsed, key = { "u${it.id}" }) { m ->
                    SectionCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Sms, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.width(8.dp))
                            Text(m.sender, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                            Text(Dates.formatDay(m.receivedAt), style = MaterialTheme.typography.bodySmall)
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(m.body, style = MaterialTheme.typography.bodySmall, maxLines = 6, overflow = TextOverflow.Ellipsis)
                        Row {
                            TextButton(onClick = { open(Routes.ADD_TXN) }) { Text("Add manually") }
                            TextButton(onClick = { vm.dismissMessage(m) }) { Text("Dismiss") }
                        }
                    }
                }
            }
        }
    }

    val current = groups?.firstOrNull { it.key == active }
    if (current != null) ReviewSheet(vm, current) { active = null; vm.resetLookup() }
}

@Composable
private fun GroupCard(g: ReviewGroup, onClick: () -> Unit) {
    SectionCard(onClick = onClick, padding = 16.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(g.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val sub = if (g.rows.size > 1) "${g.rows.size} payments · latest ${Dates.formatDay(g.lead.txn.occurredAt)}" else "${Dates.formatDay(g.lead.txn.occurredAt)} · ${Dates.formatTime(g.lead.txn.occurredAt)}"
                Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                g.lead.categoryName?.let { Spacer(Modifier.height(4.dp)); Pill("Suggested: $it") }
            }
            AmountText(g.rows.sumOf { it.txn.amountPaise }, g.lead.txn.direction, g.lead.txn.type)
            Icon(Icons.Filled.ChevronRight, null)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReviewSheet(vm: ReviewViewModel, g: ReviewGroup, close: () -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = close, sheetState = sheet) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).navigationBarsPadding().padding(bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val t = g.lead.txn
            Text(g.reason.label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.secondary)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(t.rawCounterparty ?: g.title, style = MaterialTheme.typography.titleLarge, maxLines = 2)
                    Text("${Dates.formatDay(t.occurredAt)} · ${Dates.formatTime(t.occurredAt)}" + (g.lead.accountName?.let { " · $it" } ?: ""), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                AmountText(t.amountPaise, t.direction, t.type, style = MaterialTheme.typography.titleLarge)
            }
            if (g.rows.size > 1) InfoNote("Your answer applies to all ${g.rows.size} payments in this group and counts as one confirmation.")
            when (g.reason) {
                ReviewReason.NEW_MERCHANT, ReviewReason.CONFIRM_MERCHANT, ReviewReason.AMBIGUOUS_MERCHANT -> MerchantForm(vm, g, close)
                ReviewReason.PURPOSE -> PurposeForm(vm, g, close)
                ReviewReason.CLASSIFICATION, ReviewReason.UNKNOWN_RECIPIENT -> ClassifyForm(vm, g, close)
            }
        }
    }
}

@Composable
private fun MerchantForm(vm: ReviewViewModel, g: ReviewGroup, close: () -> Unit) {
    val cats by vm.categories.collectAsStateWithLifecycle()
    val merchants by vm.merchants.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val lookup by vm.lookup.collectAsStateWithLifecycle()
    val t = g.lead.txn
    val profile = merchants.firstOrNull { it.id == t.merchantId }
    var name by rememberSaveable(g.key) { mutableStateOf(profile?.displayName ?: g.lead.merchantName ?: t.rawCounterparty?.let(MerchantNormalizer::displayName) ?: "") }
    var categoryId by rememberSaveable(g.key) { mutableStateOf(t.categoryId ?: profile?.categoryId) }
    var purpose by rememberSaveable(g.key) { mutableStateOf("") }
    var shared by rememberSaveable(g.key) { mutableStateOf(profile?.sharedName ?: false) }
    var picked by remember(g.key) { mutableStateOf<RankedPlace?>(null) }
    var askPermission by remember { mutableStateOf(false) }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted.values.any { it }) { vm.setLocationEnabled(true); vm.findNearby(g.lead) } else vm.findNearby(g.lead)
    }

    when (g.reason) {
        ReviewReason.CONFIRM_MERCHANT -> {
            val n = profile?.confirmationCount ?: 0
            Column {
                Text(MerchantLearningPolicy.progressLabel(n), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(progress = { n / MerchantLearningPolicy.CONFIRMATIONS_REQUIRED.toFloat() }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(6.dp))
                Text("After ${MerchantLearningPolicy.CONFIRMATIONS_REQUIRED} confirmations of the same category, Finora applies it automatically and stops asking.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        ReviewReason.AMBIGUOUS_MERCHANT -> InfoNote(
            if (profile?.sharedName == true) "You marked this name as used by several businesses, so Finora always asks which one."
            else "This name is close to “${profile?.displayName}” but not identical. Confirm if it is the same business, or rename it if not."
        )
        else -> Unit
    }

    OutlinedTextField(name, { name = it }, label = { Text("Merchant name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    val expense = cats.filter { it.kind == CategoryKind.EXPENSE }
    SelectField("Category", expense, expense.firstOrNull { it.id == categoryId }, { it.name }, { categoryId = it.id })
    OutlinedTextField(purpose, { purpose = it }, label = { Text("Purpose (optional)") }, placeholder = { Text("e.g. work meal, shared dinner") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { shared = !shared }) {
        Checkbox(shared, { shared = it })
        Text("Several businesses use this name (always ask)", style = MaterialTheme.typography.bodyMedium)
    }

    // A map lookup is offered only for a merchant seen for the first time.
    if (g.reason == ReviewReason.NEW_MERCHANT && settings.placeLookupEnabled) {
        SectionCard(padding = 14.dp) {
            Text("Find the business nearby", style = MaterialTheme.typography.titleSmall)
            Text("Searches OpenStreetMap for “${t.rawCounterparty ?: name}”" + if (settings.locationEnabled) " near your current location." else ".", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            when (val l = lookup) {
                LookupState.Idle -> OutlinedButton(onClick = {
                    if (settings.locationEnabled && !vm.hasLocationPermission()) askPermission = true else vm.findNearby(g.lead)
                }) { Icon(Icons.Filled.MyLocation, null); Spacer(Modifier.width(8.dp)); Text("Search nearby") }
                LookupState.Working -> Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp); Spacer(Modifier.width(10.dp)); Text("Searching…") }
                is LookupState.Failed -> Text(l.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                is LookupState.Done -> {
                    l.note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    Spacer(Modifier.height(6.dp))
                    l.places.forEach { p ->
                        val sel = picked == p
                        Row(Modifier.fillMaxWidth().clickable {
                            picked = if (sel) null else p
                            if (!sel) {
                                name = p.place.name
                                p.suggestedCategory?.let { s -> cats.firstOrNull { it.name == s }?.let { categoryId = it.id } }
                            }
                        }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(if (sel) Icons.Filled.CheckCircle else Icons.Filled.Place, null, tint = if (sel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(p.place.name, style = MaterialTheme.typography.bodyMedium)
                                Text(listOfNotNull(p.distanceMeters?.let { if (it < 1000) "${it.toInt()} m away" else String.format("%.1f km away", it / 1000) }, p.place.address).joinToString(" · "),
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                    if (settings.mapPreviewEnabled) picked?.let { MapPreview(it.place.point.lat, it.place.point.lon, it.place.name) }
                    Text("Place data © OpenStreetMap contributors", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }

    if (askPermission) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { askPermission = false },
            title = { Text("Use your location for this search?") },
            text = { Text("Finora asks for your location once, to sort nearby businesses by distance. It is not tracked, not stored, and only these coordinates go to the map search. You can say no and search without it.") },
            confirmButton = { TextButton(onClick = {
                askPermission = false
                permission.launch(if (settings.preciseLocation) arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION) else arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION))
            }) { Text("Continue") } },
            dismissButton = { TextButton(onClick = { askPermission = false; vm.findNearby(g.lead) }) { Text("Search without it") } },
        )
    }

    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedButton(onClick = { vm.dismiss(g); close() }, modifier = Modifier.weight(1f)) { Text("Skip") }
        Button(enabled = name.isNotBlank() && categoryId != null, onClick = { vm.confirm(g, name.trim(), categoryId!!, purpose, picked, shared); close() }, modifier = Modifier.weight(1.4f)) {
            Text(if (g.reason == ReviewReason.CONFIRM_MERCHANT && categoryId == profile?.categoryId) "Confirm" else "Save")
        }
    }
    Text("“Skip” resolves it without teaching Finora anything.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

private enum class Purpose(val label: String, val forDebit: Boolean?) {
    EXPENSE("A purchase or expense", true), GIFT_OUT("A gift I gave", true),
    LENT_NEW("Money I lent (new loan)", true), REPAY_BORROW("Repaying money I borrowed", true),
    INCOME("Income or a gift I received", false), BORROW_NEW("Money I borrowed (new loan)", false),
    REPAID_TO_ME("Repayment of money I lent", false), SETTLEMENT("Someone settling a shared bill", false),
    OWN_TRANSFER("Transfer between my own accounts", null),
}

@Composable
private fun PurposeForm(vm: ReviewViewModel, g: ReviewGroup, close: () -> Unit) {
    val cats by vm.categories.collectAsStateWithLifecycle()
    val people by vm.people.collectAsStateWithLifecycle()
    val loans by vm.loans.collectAsStateWithLifecycle()
    val splits by vm.splits.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val t = g.lead.txn
    val debit = t.direction == Direction.DEBIT
    var purpose by rememberSaveable(g.key) { mutableStateOf<Purpose?>(null) }
    var personId by rememberSaveable(g.key) { mutableStateOf(t.personId) }
    var newName by rememberSaveable(g.key) { mutableStateOf(t.rawCounterparty?.takeIf { !it.contains('@') }?.let(MerchantNormalizer::displayName) ?: "") }
    var categoryId by rememberSaveable(g.key) { mutableStateOf<Long?>(null) }
    var loan by remember { mutableStateOf<LoanEntity?>(null) }
    var split by remember { mutableStateOf<BillSplitSummary?>(null) }
    var participant by remember { mutableStateOf<BillParticipantEntity?>(null) }
    var participants by remember { mutableStateOf(listOf<BillParticipantEntity>()) }
    LaunchedEffect(split) { participants = split?.let { vm.participants(it.split.id).filter { p -> !p.isMe } } ?: emptyList() }

    Text("Who was it?", style = MaterialTheme.typography.titleSmall)
    SelectField("Person", listOf<PersonEntity?>(null) + people, people.firstOrNull { it.id == personId }, { it?.name ?: "Someone new" }, { personId = it?.id }, placeholder = "Someone new")
    if (personId == null) OutlinedTextField(newName, { newName = it }, label = { Text("Their name") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        supportingText = { Text("Saved as a person; their UPI id is remembered so next time they are recognised.") })

    Text("What was it for?", style = MaterialTheme.typography.titleSmall)
    Purpose.entries.filter { it.forDebit == null || it.forDebit == debit }.forEach { p ->
        FilterChip(selected = purpose == p, onClick = { purpose = p }, label = { Text(p.label) }, modifier = Modifier.fillMaxWidth())
    }
    when (purpose) {
        Purpose.EXPENSE, Purpose.GIFT_OUT -> SelectField("Category", cats.filter { it.kind == CategoryKind.EXPENSE }, cats.firstOrNull { it.id == categoryId }, { it.name }, { categoryId = it.id })
        Purpose.INCOME -> SelectField("Category", cats.filter { it.kind == CategoryKind.INCOME }, cats.firstOrNull { it.id == categoryId }, { it.name }, { categoryId = it.id })
        Purpose.REPAY_BORROW -> SelectField("Which loan", loans.filter { it.kind != LoanKind.LENT }, loan, { it.title }, { loan = it })
        Purpose.REPAID_TO_ME -> SelectField("Which loan", loans.filter { it.kind == LoanKind.LENT }, loan, { it.title }, { loan = it })
        Purpose.SETTLEMENT -> {
            SelectField("Shared bill", splits.filter { it.outstandingPaise > 0 }, split, { "${it.split.title} · ${Money.format(it.outstandingPaise)} owed" }, { split = it })
            SelectField("Paid back by", participants, participant, { it.displayName }, { participant = it })
        }
        else -> Unit
    }
    val ready = when (purpose) {
        null -> false
        Purpose.EXPENSE, Purpose.GIFT_OUT, Purpose.INCOME -> categoryId != null
        Purpose.REPAY_BORROW, Purpose.REPAID_TO_ME -> loan != null
        Purpose.SETTLEMENT -> split != null && participant != null
        Purpose.LENT_NEW, Purpose.BORROW_NEW -> personId != null || newName.isNotBlank()
        Purpose.OWN_TRANSFER -> true
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedButton(onClick = { vm.dismiss(g); close() }, modifier = Modifier.weight(1f)) { Text("Skip") }
        Button(enabled = ready, modifier = Modifier.weight(1.4f), onClick = {
            scope.launch {
                val pid = personId ?: if (newName.isNotBlank() && purpose != Purpose.OWN_TRANSFER && purpose != Purpose.EXPENSE) vm.newPerson(newName.trim(), t.rawCounterparty?.takeIf { it.contains('@') }) else null
                val pname = people.firstOrNull { it.id == pid }?.name ?: newName.trim()
                when (purpose!!) {
                    // A purchase from a business teaches merchant learning, exactly like the merchant card.
                    Purpose.EXPENSE -> if (personId == null && newName.isNotBlank()) vm.confirm(g, newName.trim(), categoryId!!, null, null, false)
                        else vm.resolve(t.id, TxnType.EXPENSE, pid, categoryId, null)
                    Purpose.GIFT_OUT -> vm.resolve(t.id, TxnType.EXPENSE, pid, categoryId, "Gift")
                    Purpose.INCOME -> vm.resolve(t.id, TxnType.INCOME, pid, categoryId, null)
                    Purpose.LENT_NEW -> vm.lendOrBorrow(g.lead, pid, pname, lent = true)
                    Purpose.BORROW_NEW -> vm.lendOrBorrow(g.lead, pid, pname, lent = false)
                    Purpose.REPAY_BORROW, Purpose.REPAID_TO_ME -> vm.linkLoan(g.lead, loan!!, LoanPaymentKind.REPAYMENT)
                    Purpose.SETTLEMENT -> vm.settle(g.lead, split!!.split.id, participant!!.id)
                    Purpose.OWN_TRANSFER -> vm.resolve(t.id, TxnType.SELF_TRANSFER, null, null, null)
                }
                close()
            }
        }) { Text("Save") }
    }
}

@Composable
private fun ClassifyForm(vm: ReviewViewModel, g: ReviewGroup, close: () -> Unit) {
    val cats by vm.categories.collectAsStateWithLifecycle()
    val t = g.lead.txn
    var type by rememberSaveable(g.key) { mutableStateOf(t.type) }
    var categoryId by rememberSaveable(g.key) { mutableStateOf(t.categoryId) }
    InfoNote(when (t.type) {
        TxnType.INCOME -> "Money arrived and the message does not say why. Confirm it is income, or choose what it really was so it is not counted as income."
        TxnType.LOAN_REPAYMENT -> "This looks like a loan EMI. Link it to the loan from the transaction screen to split principal from interest."
        else -> "Check the type: it decides whether this counts as income, spending, or neither."
    })
    SelectField("Type", TxnType.entries.filter { com.finora.app.ui.transactions.compatible(it, t.direction) }, type, { it.label }, { type = it })
    if (type.countsAs == TxnType.Flow.INCOME || type.countsAs == TxnType.Flow.EXPENSE) {
        val kind = if (type.countsAs == TxnType.Flow.INCOME) CategoryKind.INCOME else CategoryKind.EXPENSE
        SelectField("Category", cats.filter { it.kind == kind }, cats.firstOrNull { it.id == categoryId }, { it.name }, { categoryId = it.id })
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedButton(onClick = { vm.dismiss(g); close() }, modifier = Modifier.weight(1f)) { Text("Skip") }
        Button(onClick = { vm.resolve(t.id, type, null, categoryId, null); close() }, modifier = Modifier.weight(1.4f)) { Text("Save") }
    }
}
