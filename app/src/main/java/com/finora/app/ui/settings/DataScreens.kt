package com.finora.app.ui.settings

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.finora.app.AppContainer
import com.finora.app.MainActivity
import com.finora.app.core.Dates
import com.finora.app.core.Money
import com.finora.app.data.db.AccountEntity
import com.finora.app.data.db.CategoryEntity
import com.finora.app.data.db.MerchantConfirmationEntity
import com.finora.app.data.db.MerchantEntity
import com.finora.app.data.db.PersonEntity
import com.finora.app.data.db.RecurringBillEntity
import com.finora.app.data.repo.CsvService
import com.finora.app.domain.merchant.MerchantLearningPolicy
import com.finora.app.domain.merchant.MerchantNormalizer
import com.finora.app.domain.model.AccountType
import com.finora.app.domain.model.CategoryKind
import com.finora.app.domain.model.ConfirmationKind
import com.finora.app.domain.model.MerchantStatus
import com.finora.app.platform.backup.BackupManager
import com.finora.app.ui.appViewModel
import com.finora.app.ui.components.CategoryBadge
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
import com.finora.app.ui.components.parseHex
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.system.exitProcess

// ---------------- Learned merchants ----------------

class MerchantsViewModel(private val c: AppContainer) : ViewModel() {
    val merchants = c.db.merchants().observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val categories = c.db.categories().observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** An explicit edit here is authoritative: the merchant keeps its learned status under the new category. */
    fun save(m: MerchantEntity, name: String, categoryId: Long?, shared: Boolean, applyToPast: Boolean) = viewModelScope.launch {
        val now = System.currentTimeMillis()
        val changed = categoryId != m.categoryId
        val count = if (changed && m.confirmationCount < MerchantLearningPolicy.CONFIRMATIONS_REQUIRED) 1 else m.confirmationCount
        c.db.merchants().update(m.copy(displayName = name, categoryId = categoryId, sharedName = shared, confirmationCount = count,
            status = if (shared) MerchantStatus.AMBIGUOUS else MerchantLearningPolicy.statusFor(count), updatedAt = now))
        if (changed && categoryId != null) {
            c.db.merchants().insertConfirmation(MerchantConfirmationEntity(merchantId = m.id, transactionId = null, categoryId = categoryId, kind = ConfirmationKind.EDITED, createdAt = now))
            if (applyToPast) c.transactions.recategorizePastForMerchant(m.id, categoryId)
        }
    }

    /** Forget what was learned; the next payment asks again from the first confirmation. */
    fun reset(m: MerchantEntity) = viewModelScope.launch {
        c.db.merchants().update(m.copy(confirmationCount = 0, status = MerchantStatus.LEARNING, updatedAt = System.currentTimeMillis()))
        c.db.merchants().clearAliases(m.id)
        c.db.merchants().insertConfirmation(MerchantConfirmationEntity(merchantId = m.id, transactionId = null, categoryId = m.categoryId, kind = ConfirmationKind.RESET, createdAt = System.currentTimeMillis()))
    }

    fun delete(m: MerchantEntity) = viewModelScope.launch { c.db.merchants().delete(m) }
}

@Composable
fun MerchantsScreen(back: () -> Unit) {
    val vm = appViewModel { MerchantsViewModel(it) }
    val list by vm.merchants.collectAsStateWithLifecycle()
    val cats by vm.categories.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<MerchantEntity?>(null) }
    Scaffold(contentWindowInsets = WindowInsets(0), topBar = { FinoraTopBar("Learned merchants", back) }) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(20.dp, 4.dp, 20.dp, 40.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { InfoNote("A merchant is applied automatically after you confirm the same category three times. Edit one here to correct it; reset it to make Finora ask again.") }
            if (list?.isEmpty() == true) item { EmptyState(Icons.Filled.Storefront, "Nothing learned yet", "Confirm merchants in Review and they appear here.") }
            items(list.orEmpty(), key = { it.id }) { m ->
                val cat = cats.firstOrNull { it.id == m.categoryId }
                SectionCard(onClick = { editing = m }, padding = 14.dp) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CategoryBadge(cat?.iconKey, cat?.colorHex)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(m.displayName, style = MaterialTheme.typography.titleSmall)
                            Text("${cat?.name ?: "No category"} · ${MerchantLearningPolicy.progressLabel(m.confirmationCount)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        when {
                            m.sharedName -> Pill("Always asks")
                            m.status == MerchantStatus.LEARNED -> Pill("Auto", MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer)
                        }
                    }
                }
            }
        }
    }
    editing?.let { m ->
        var name by remember(m.id) { mutableStateOf(m.displayName) }
        var catId by remember(m.id) { mutableStateOf(m.categoryId) }
        var shared by remember(m.id) { mutableStateOf(m.sharedName) }
        var past by remember(m.id) { mutableStateOf(true) }
        var confirmDelete by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { editing = null }, title = { Text("Edit merchant") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
                    SelectField("Category", cats.filter { it.kind == CategoryKind.EXPENSE }, cats.firstOrNull { it.id == catId }, { it.name }, { catId = it.id })
                    if (catId != m.categoryId) Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { past = !past }) { Checkbox(past, { past = it }); Text("Also update past transactions") }
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { shared = !shared }) { Checkbox(shared, { shared = it }); Text("Several businesses use this name") }
                    Text("Matched on: “${m.normalizedKey}”", style = MaterialTheme.typography.bodySmall)
                    m.address?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    Row {
                        TextButton(onClick = { vm.reset(m); editing = null }) { Text("Reset learning") }
                        TextButton(onClick = { confirmDelete = true }) { Text("Delete") }
                    }
                }
            },
            confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { vm.save(m, name.trim(), catId, shared, past); editing = null }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Cancel") } },
        )
        if (confirmDelete) AlertDialog(
            onDismissRequest = { confirmDelete = false }, title = { Text("Delete ${m.displayName}?") },
            text = { Text("Its transactions stay but lose the merchant link. The next payment to it is treated as new.") },
            confirmButton = { TextButton(onClick = { vm.delete(m); confirmDelete = false; editing = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

// ---------------- Categories ----------------

class CategoriesViewModel(private val c: AppContainer) : ViewModel() {
    val categories = c.db.categories().observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    fun add(name: String, kind: CategoryKind, color: String) = viewModelScope.launch { c.db.categories().insert(CategoryEntity(name = name, kind = kind, iconKey = "category", colorHex = color)) }
    fun update(cat: CategoryEntity) = viewModelScope.launch { c.db.categories().update(cat) }
}

private val palette = listOf("#E07A5F", "#81B29A", "#3D5A80", "#F2A541", "#9B5DE5", "#00A6A6", "#C38D9E", "#6C757D")

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CategoriesScreen(back: () -> Unit) {
    val vm = appViewModel { CategoriesViewModel(it) }
    val cats by vm.categories.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<CategoryEntity?>(null) }
    var adding by remember { mutableStateOf(false) }
    Scaffold(contentWindowInsets = WindowInsets(0), topBar = { FinoraTopBar("Categories", back) },
        floatingActionButton = { FloatingActionButton(onClick = { adding = true }) { Icon(Icons.Filled.Add, "Add category") } }) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(20.dp, 4.dp, 20.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            CategoryKind.entries.forEach { kind ->
                item { SectionHeader(if (kind == CategoryKind.EXPENSE) "Spending" else "Income") }
                items(cats.filter { it.kind == kind }, key = { it.id }) { c ->
                    Row(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).clickable { editing = c }.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        CategoryBadge(c.iconKey, c.colorHex, 36.dp)
                        Spacer(Modifier.width(12.dp))
                        Text(c.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        if (c.isDefault) Text("Default", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
    if (adding || editing != null) {
        val e = editing
        var name by remember(e) { mutableStateOf(e?.name ?: "") }
        var kind by remember(e) { mutableStateOf(e?.kind ?: CategoryKind.EXPENSE) }
        var color by remember(e) { mutableStateOf(e?.colorHex ?: palette.first()) }
        AlertDialog(
            onDismissRequest = { adding = false; editing = null }, title = { Text(if (e == null) "New category" else "Edit category") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
                    if (e == null) SelectField("Kind", CategoryKind.entries.toList(), kind, { if (it == CategoryKind.EXPENSE) "Spending" else "Income" }, { kind = it })
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        palette.forEach { hex ->
                            Box(Modifier.size(32.dp).clip(CircleShape).background(parseHex(hex, MaterialTheme.colorScheme.primary)).clickable { color = hex }, contentAlignment = Alignment.Center) {
                                if (color == hex) Box(Modifier.size(12.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surface))
                            }
                        }
                    }
                    if (e != null) TextButton(onClick = { vm.update(e.copy(archived = true)); editing = null }) { Text("Archive category") }
                }
            },
            confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = {
                if (e == null) vm.add(name.trim(), kind, color) else vm.update(e.copy(name = name.trim(), colorHex = color))
                adding = false; editing = null
            }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { adding = false; editing = null }) { Text("Cancel") } },
        )
    }
}

// ---------------- People ----------------

class PeopleViewModel(private val c: AppContainer) : ViewModel() {
    val people = c.db.people().observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    fun save(p: PersonEntity?, name: String, upi: String?, phone: String?) = viewModelScope.launch {
        if (p == null) c.transactions.createPerson(name, upi, phone)
        else c.db.people().update(p.copy(name = name, normalizedKey = MerchantNormalizer.key(name), upiId = upi?.lowercase(), phone = phone))
    }
    fun delete(p: PersonEntity) = viewModelScope.launch { c.db.people().delete(p) }
}

@Composable
fun PeopleScreen(back: () -> Unit) {
    val vm = appViewModel { PeopleViewModel(it) }
    val people by vm.people.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<PersonEntity?>(null) }
    var adding by remember { mutableStateOf(false) }
    Scaffold(contentWindowInsets = WindowInsets(0), topBar = { FinoraTopBar("People", back) },
        floatingActionButton = { FloatingActionButton(onClick = { adding = true }) { Icon(Icons.Filled.Add, "Add person") } }) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(20.dp, 4.dp, 20.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { InfoNote("Two people with similar names are never merged automatically. A UPI id is remembered once you link a payment to someone.") }
            items(people, key = { it.id }) { p ->
                SectionCard(onClick = { editing = p }, padding = 14.dp) {
                    Text(p.name, style = MaterialTheme.typography.titleSmall)
                    Text(listOfNotNull(p.upiId, p.phone).joinToString(" · ").ifEmpty { "No UPI id yet" }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
    if (adding || editing != null) {
        val e = editing
        var name by remember(e) { mutableStateOf(e?.name ?: "") }
        var upi by remember(e) { mutableStateOf(e?.upiId ?: "") }
        var phone by remember(e) { mutableStateOf(e?.phone ?: "") }
        AlertDialog(
            onDismissRequest = { adding = false; editing = null }, title = { Text(if (e == null) "New person" else "Edit person") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
                    OutlinedTextField(upi, { upi = it.trim() }, label = { Text("UPI id (optional)") }, singleLine = true)
                    OutlinedTextField(phone, { phone = it }, label = { Text("Phone (optional)") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone))
                    if (e != null) TextButton(onClick = { vm.delete(e); editing = null }) { Text("Delete") }
                }
            },
            confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { vm.save(e, name.trim(), upi.ifBlank { null }, phone.ifBlank { null }); adding = false; editing = null }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { adding = false; editing = null }) { Text("Cancel") } },
        )
    }
}

// ---------------- Accounts ----------------

class AccountsViewModel(private val c: AppContainer) : ViewModel() {
    val accounts = c.db.accounts().observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    fun save(a: AccountEntity?, name: String, type: AccountType, masked: String?, balance: Long?) = viewModelScope.launch {
        val now = System.currentTimeMillis()
        if (a == null) c.db.accounts().insert(AccountEntity(name = name, type = type, maskedNumber = masked, manualBalancePaise = balance, manualBalanceAt = balance?.let { now }, createdAt = now))
        else c.db.accounts().update(a.copy(name = name, type = type, maskedNumber = masked,
            manualBalancePaise = balance ?: a.manualBalancePaise, manualBalanceAt = if (balance != null && balance != a.manualBalancePaise) now else a.manualBalanceAt))
    }
    fun archive(a: AccountEntity) = viewModelScope.launch { c.db.accounts().update(a.copy(archived = true)) }
}

@Composable
fun AccountsScreen(back: () -> Unit) {
    val vm = appViewModel { AccountsViewModel(it) }
    val accounts by vm.accounts.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<AccountEntity?>(null) }
    var adding by remember { mutableStateOf(false) }
    Scaffold(contentWindowInsets = WindowInsets(0), topBar = { FinoraTopBar("Accounts", back) },
        floatingActionButton = { FloatingActionButton(onClick = { adding = true }) { Icon(Icons.Filled.Add, "Add account") } }) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(20.dp, 4.dp, 20.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { InfoNote("Accounts are created automatically from the last digits in bank messages. Only the last 4 digits are ever stored. Add a balance by hand if your bank's SMS does not include one.") }
            items(accounts, key = { it.id }) { a ->
                SectionCard(onClick = { editing = a }, padding = 14.dp) {
                    Text(a.name, style = MaterialTheme.typography.titleSmall)
                    Text(listOfNotNull(a.type.label, a.maskedNumber?.let { "••$it" }, a.manualBalancePaise?.let { "Balance ${Money.format(it)} on ${Dates.formatDay(a.manualBalanceAt ?: 0)}" }).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
    if (adding || editing != null) {
        val e = editing
        var name by remember(e) { mutableStateOf(e?.name ?: "") }
        var type by remember(e) { mutableStateOf(e?.type ?: AccountType.BANK) }
        var masked by remember(e) { mutableStateOf(e?.maskedNumber ?: "") }
        var balance by remember(e) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { adding = false; editing = null }, title = { Text(if (e == null) "New account" else "Edit account") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
                    SelectField("Type", AccountType.entries.toList(), type, { it.label }, { type = it })
                    OutlinedTextField(masked, { if (it.length <= 4 && it.all(Char::isDigit)) masked = it }, label = { Text("Last 4 digits") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    MoneyField("Current balance (optional)", balance, { balance = it }, supporting = "Recorded with today's date")
                    if (e != null) TextButton(onClick = { vm.archive(e); editing = null }) { Text("Archive") }
                }
            },
            confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { vm.save(e, name.trim(), type, masked.ifBlank { null }, Money.parseToPaise(balance)); adding = false; editing = null }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { adding = false; editing = null }) { Text("Cancel") } },
        )
    }
}

// ---------------- Recurring bills ----------------

class RecurringViewModel(private val c: AppContainer) : ViewModel() {
    val bills = c.db.recurringBills().observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val categories = c.db.categories().observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    fun save(b: RecurringBillEntity?, name: String, amount: Long, day: Int, categoryId: Long?, sub: Boolean) = viewModelScope.launch {
        if (b == null) c.db.recurringBills().insert(RecurringBillEntity(name = name, amountPaise = amount, dayOfMonth = day, categoryId = categoryId, isSubscription = sub, createdAt = System.currentTimeMillis()))
        else c.db.recurringBills().update(b.copy(name = name, amountPaise = amount, dayOfMonth = day, categoryId = categoryId, isSubscription = sub))
    }
    fun toggle(b: RecurringBillEntity) = viewModelScope.launch { c.db.recurringBills().update(b.copy(active = !b.active)) }
    fun delete(b: RecurringBillEntity) = viewModelScope.launch { c.db.recurringBills().delete(b) }
}

@Composable
fun RecurringBillsScreen(back: () -> Unit) {
    val vm = appViewModel { RecurringViewModel(it) }
    val bills by vm.bills.collectAsStateWithLifecycle()
    val cats by vm.categories.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<RecurringBillEntity?>(null) }
    var adding by remember { mutableStateOf(false) }
    Scaffold(contentWindowInsets = WindowInsets(0), topBar = { FinoraTopBar("Recurring bills", back) },
        floatingActionButton = { FloatingActionButton(onClick = { adding = true }) { Icon(Icons.Filled.Add, "Add bill") } }) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(20.dp, 4.dp, 20.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                val active = bills.filter { it.active }
                SectionCard {
                    Text("Per month", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(Money.format(active.sumOf { it.amountPaise }), style = MaterialTheme.typography.headlineMedium)
                    Text("${active.count { it.isSubscription }} subscriptions · ${active.count { !it.isSubscription }} bills", style = MaterialTheme.typography.bodySmall)
                }
            }
            items(bills, key = { it.id }) { b ->
                SectionCard(onClick = { editing = b }, padding = 14.dp) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(b.name, style = MaterialTheme.typography.titleSmall)
                            Text("${Money.format(b.amountPaise)} on day ${b.dayOfMonth}" + if (b.isSubscription) " · subscription" else "", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(b.active, { vm.toggle(b) })
                    }
                }
            }
        }
    }
    if (adding || editing != null) {
        val e = editing
        var name by remember(e) { mutableStateOf(e?.name ?: "") }
        var amount by remember(e) { mutableStateOf(e?.amountPaise?.let(Money::toInput) ?: "") }
        var day by remember(e) { mutableStateOf(e?.dayOfMonth?.toString() ?: "1") }
        var catId by remember(e) { mutableStateOf(e?.categoryId) }
        var sub by remember(e) { mutableStateOf(e?.isSubscription ?: false) }
        AlertDialog(
            onDismissRequest = { adding = false; editing = null }, title = { Text(if (e == null) "New recurring bill" else "Edit bill") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
                    MoneyField("Amount", amount, { amount = it })
                    OutlinedTextField(day, { if (it.length <= 2 && it.all(Char::isDigit)) day = it }, label = { Text("Day of month") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    SelectField("Category", cats.filter { it.kind == CategoryKind.EXPENSE }, cats.firstOrNull { it.id == catId }, { it.name }, { catId = it.id })
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { sub = !sub }) { Checkbox(sub, { sub = it }); Text("Subscription") }
                    if (e != null) TextButton(onClick = { vm.delete(e); editing = null }) { Text("Delete") }
                }
            },
            confirmButton = { TextButton(onClick = {
                val a = Money.parseToPaise(amount); val d = day.toIntOrNull()
                if (name.isNotBlank() && a != null && a > 0 && d != null && d in 1..31) { vm.save(e, name.trim(), a, d, catId, sub); adding = false; editing = null }
            }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { adding = false; editing = null }) { Text("Cancel") } },
        )
    }
}

// ---------------- Backup, restore, export ----------------

class BackupViewModel(val c: AppContainer) : ViewModel()

@Composable
fun BackupScreen(back: () -> Unit) {
    val vm = appViewModel { BackupViewModel(it) }
    val c = vm.c
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snack = remember { SnackbarHostState() }
    var busy by remember { mutableStateOf(false) }
    var password by remember { mutableStateOf("") }
    var password2 by remember { mutableStateOf("") }
    var restorePassword by remember { mutableStateOf("") }
    var restoreUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var preview by remember { mutableStateOf<BackupManager.Preview?>(null) }
    var csvPreview by remember { mutableStateOf<CsvService.ImportPreview?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    val createBackup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            runCatching { c.backup.createBackup(uri, password.toCharArray()) }
                .onSuccess { snack.showSnackbar("Backup saved, with $it documents."); password = ""; password2 = "" }
                .onFailure { error = "Backup failed: ${it.message}" }
            busy = false
        }
    }
    val openBackup = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> restoreUri = uri }
    val exportCsv = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                val text = c.csv.export(null, null)
                context.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(text.toByteArray()) }
            }.onSuccess { snack.showSnackbar("Exported.") }.onFailure { error = "Export failed: ${it.message}" }
        }
    }
    val importCsv = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            runCatching { c.csv.preview(context.contentResolver.openInputStream(uri)!!.use { it.readBytes().decodeToString() }) }
                .onSuccess { csvPreview = it }.onFailure { error = "Could not read that file: ${it.message}" }
        }
    }

    Scaffold(contentWindowInsets = WindowInsets(0), topBar = { FinoraTopBar("Backup & export", back) }, snackbarHost = { SnackbarHost(snack) }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            InfoNote("Your data lives only on this phone. If the phone is lost, reset, or Finora is uninstalled without a backup, it is gone. Make a backup regularly and keep it somewhere safe.")
            ErrorNote(error)
            if (busy) Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp); Spacer(Modifier.width(10.dp)); Text("Working…") }
            SectionHeader("Encrypted backup")
            SectionCard {
                Text("Everything — transactions, loans, investments, splits, settings and documents — in one file encrypted with your password (AES-256).", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(password, { password = it }, label = { Text("Backup password (8+ characters)") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(password2, { password2 = it }, label = { Text("Repeat password") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(),
                    isError = password2.isNotEmpty() && password2 != password)
                Text("There is no way to recover a forgotten password — not even for us.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(8.dp))
                Button(enabled = password.length >= 8 && password == password2 && !busy, onClick = { error = null; createBackup.launch("finora-backup-${Dates.today()}.finora") }) { Text("Create backup") }
            }
            SectionHeader("Restore")
            SectionCard {
                Text("Restoring REPLACES all current data with the backup's. You see what the backup holds before anything changes.", style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = { openBackup.launch(arrayOf("*/*")) }) { Text(if (restoreUri == null) "Choose backup file" else "File chosen ✓") }
                if (restoreUri != null) {
                    OutlinedTextField(restorePassword, { restorePassword = it }, label = { Text("Backup password") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                    Button(enabled = restorePassword.isNotEmpty() && !busy, onClick = {
                        busy = true; error = null
                        scope.launch {
                            runCatching { c.backup.preview(restoreUri!!, restorePassword.toCharArray()) }.onSuccess { preview = it }.onFailure { error = it.message }
                            busy = false
                        }
                    }) { Text("Check backup") }
                }
            }
            SectionHeader("CSV")
            SectionCard {
                Text("Export every transaction to a spreadsheet, or import transactions from a CSV in the same format. Imports skip anything already recorded.", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { exportCsv.launch("finora-transactions-${Dates.today()}.csv") }) { Text("Export CSV") }
                    OutlinedButton(onClick = { importCsv.launch(arrayOf("text/*", "application/csv", "text/comma-separated-values")) }) { Text("Import CSV") }
                }
                Text("Columns: date (yyyy-MM-dd HH:mm), amount, direction, type, merchant, person, category, purpose, account, method, reference, notes, fingerprint.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }

    preview?.let { p ->
        AlertDialog(
            onDismissRequest = { preview = null }, title = { Text("Restore this backup?") },
            text = {
                Column {
                    Text("Made ${Dates.formatDay(p.createdAt)} · ${Dates.formatTime(p.createdAt)}", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))
                    p.counts.forEach { (t, n) -> KeyValueRow(t.replace('_', ' ').replaceFirstChar(Char::uppercase), n.toString()) }
                    KeyValueRow("Document files", p.documentFiles.toString())
                    Spacer(Modifier.height(8.dp))
                    Text("All data now on this phone will be replaced. Finora restarts afterwards.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = { TextButton(onClick = {
                busy = true
                scope.launch {
                    runCatching { c.backup.applyRestore(p) }.onSuccess {
                        val intent = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                        context.startActivity(intent)
                        exitProcess(0)
                    }.onFailure { error = "Restore failed: ${it.message}"; busy = false; preview = null }
                }
            }) { Text("Replace my data") } },
            dismissButton = { TextButton(onClick = { preview = null }) { Text("Cancel") } },
        )
    }
    csvPreview?.let { p ->
        AlertDialog(
            onDismissRequest = { csvPreview = null }, title = { Text("Import transactions?") },
            text = {
                Column {
                    Text("${p.rows.size} rows read: ${p.newRows.size} new, ${p.duplicates} already recorded (skipped).")
                    if (p.errors.isNotEmpty()) { Spacer(Modifier.height(6.dp)); Text("${p.errors.size} rows could not be read:\n" + p.errors.take(5).joinToString("\n"), style = MaterialTheme.typography.bodySmall) }
                }
            },
            confirmButton = { TextButton(enabled = p.newRows.isNotEmpty(), onClick = { scope.launch { val n = c.csv.commit(p); csvPreview = null; snack.showSnackbar("Imported $n transactions.") } }) { Text("Import ${p.newRows.size}") } },
            dismissButton = { TextButton(onClick = { csvPreview = null }) { Text("Cancel") } },
        )
    }
}

// ---------------- Privacy ----------------

@Composable
fun PrivacyScreen(back: () -> Unit) {
    Scaffold(contentWindowInsets = WindowInsets(0), topBar = { FinoraTopBar("Privacy", back) }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            PrivacyBlock("Stays on this phone", listOf(
                "Every transaction, balance, loan, investment, bill split and setting is stored in Finora's private database on this phone.",
                "SMS messages are read and parsed on the phone. They are never sent anywhere.",
                "Receipt photos are read by on-device text recognition (ML Kit, bundled model). The image is not uploaded.",
                "All calculations — totals, interest, portfolio values, analytics — are done on the phone, offline.",
            ))
            PrivacyBlock("Goes online, only when you ask", listOf(
                "Merchant lookup: when you press Search for an unfamiliar merchant, its name (and, if you allowed it, your approximate location) is sent to the place-search server you configured — OpenStreetMap's Nominatim by default. Results are cached so the same search is not repeated, and a learned merchant is never looked up again.",
                "Map preview, if turned on: map images are downloaded from OpenStreetMap's tile servers for the area shown.",
                "Nothing else. Finora has no account, no server, no analytics SDK, no advertising SDK and no AI service.",
            ))
            PrivacyBlock("Protection", listOf(
                "Documents in the vault are encrypted with AES-256-GCM using a key held in the Android Keystore. Uninstalling Finora or clearing its data destroys that key — restore from a backup to get documents back.",
                "Backups are encrypted with AES-256-GCM using a key derived from your password. A forgotten password cannot be recovered.",
                "The main database is NOT separately encrypted by Finora. It is protected by Android's app sandbox and, on devices with a lock screen, the phone's own storage encryption.",
                "Android's cloud backup is disabled for Finora, so your data is not copied to Google Drive behind your back. Use Finora's own backup instead.",
            ))
            PrivacyBlock("Location", listOf(
                "Off unless you turn it on. Foreground only, read once per search, never in the background, never stored, no history.",
                "Being near a business is treated as a hint, never as proof that you paid there. You always confirm.",
            ))
            PrivacyBlock("Prices", listOf("Stock prices and fund NAVs are only ever the ones you type. Finora does not fetch market data and does not trade or connect to any broker."))
        }
    }
}

@Composable
private fun PrivacyBlock(title: String, lines: List<String>) {
    SectionHeader(title)
    SectionCard { lines.forEach { Text("•  $it", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 4.dp)) } }
}
