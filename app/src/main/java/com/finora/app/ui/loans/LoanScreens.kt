package com.finora.app.ui.loans

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.finora.app.AppContainer
import com.finora.app.core.Dates
import com.finora.app.core.Money
import com.finora.app.data.db.LoanEntity
import com.finora.app.data.db.PersonEntity
import com.finora.app.domain.loan.LoanCalculator
import com.finora.app.domain.model.CompoundingFrequency
import com.finora.app.domain.model.InterestMethod
import com.finora.app.domain.model.LinkTarget
import com.finora.app.domain.model.LoanKind
import com.finora.app.domain.model.LoanPaymentKind
import com.finora.app.domain.model.LoanStatus
import com.finora.app.ui.LoanRow
import com.finora.app.ui.Routes
import com.finora.app.ui.appViewModel
import com.finora.app.ui.buildLoans
import com.finora.app.ui.components.DateField
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
import com.finora.app.ui.components.StatTile
import com.finora.app.ui.lendingTotals
import com.finora.app.ui.theme.AmountStyle
import com.finora.app.ui.theme.Finance
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.time.LocalDate

class LoansViewModel(c: AppContainer) : ViewModel() {
    val rows = combine(c.db.loans().observeAll(), c.db.loans().observeAllPayments(), c.db.people().observeAll()) { l, p, people ->
        buildLoans(l, p, Dates.today()) to people.associateBy { it.id }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}

@Composable
fun LoansScreen(open: (String) -> Unit, back: (() -> Unit)?) {
    val vm = appViewModel { LoansViewModel(it) }
    val data by vm.rows.collectAsStateWithLifecycle()
    var filter by rememberSaveable { mutableStateOf<LoanKind?>(null) }
    var showClosed by rememberSaveable { mutableStateOf(false) }
    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = { FinoraTopBar("Loans & lending", back) },
        floatingActionButton = { ExtendedFloatingActionButton(onClick = { open(Routes.LOAN_NEW) }, icon = { Icon(Icons.Filled.Add, null) }, text = { Text("New") }) },
    ) { pad ->
        val (rows, people) = data ?: return@Scaffold
        val t = lendingTotals(rows)
        LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(20.dp, 8.dp, 20.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                SectionCard {
                    Row {
                        StatTile("Others owe you", Money.format(t.lentOutstanding), Modifier.weight(1f), valueColor = Finance.colors.income)
                        StatTile("You owe", Money.format(t.borrowedOutstanding), Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(6.dp))
                    Text("Outstanding principal plus interest accrued to today.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item { FilterChip(filter == null, { filter = null }, { Text("All") }) }
                    items(LoanKind.entries.toList()) { k -> FilterChip(filter == k, { filter = k }, { Text(k.label) }) }
                    item { FilterChip(showClosed, { showClosed = !showClosed }, { Text("Show closed") }) }
                }
            }
            val shown = rows.filter { (filter == null || it.loan.kind == filter) && (showClosed || it.loan.status == LoanStatus.ACTIVE) }
            if (shown.isEmpty()) item { EmptyState(Icons.Filled.AccountBalanceWallet, "Nothing here", "Record money you lent or borrowed, or a formal loan with EMIs. Principal and interest are always kept apart.") }
            items(shown, key = { it.loan.id }) { r -> LoanCard(r, people[r.loan.personId]) { open(Routes.loan(r.loan.id)) } }
        }
    }
}

@Composable
private fun LoanCard(r: LoanRow, person: PersonEntity?, onClick: () -> Unit) {
    SectionCard(onClick = onClick, padding = 16.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(r.loan.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(listOfNotNull(r.loan.kind.label, person?.name ?: r.loan.lenderName, r.loan.interestMethod.takeIf { it != InterestMethod.NONE }?.let { "${BigDecimal(r.loan.annualRateBps).movePointLeft(2).stripTrailingZeros().toPlainString()}% a year" }).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(Money.format(r.state.totalOutstandingPaise), style = MaterialTheme.typography.titleSmall.merge(AmountStyle))
                when {
                    r.loan.status == LoanStatus.CLOSED -> Pill("Closed", MaterialTheme.colorScheme.surfaceContainerHighest, MaterialTheme.colorScheme.onSurfaceVariant)
                    r.overdue -> Pill("Overdue", MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer)
                    r.state.isSettled -> Pill("Repaid", MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer)
                    r.dueDate != null -> Text("Due ${Dates.formatDayShort(r.dueDate)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        val total = r.state.totalPrincipalPaise
        if (total > 0) {
            Spacer(Modifier.height(10.dp))
            LinearProgressIndicator(progress = { (r.state.principalRepaidPaise.toFloat() / total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
        }
    }
}

class LoanEditViewModel(private val c: AppContainer, private val id: Long?) : ViewModel() {
    val people = c.db.people().observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    suspend fun load(): LoanEntity? = id?.let { c.db.loans().get(it) }
    suspend fun save(l: LoanEntity): Long = if (l.id == 0L) c.loans.create(l) else { c.loans.update(l); l.id }
    suspend fun newPerson(name: String) = c.transactions.createPerson(name)
}

@Composable
fun LoanEditScreen(id: Long?, back: () -> Unit, saved: (Long) -> Unit) {
    val vm = appViewModel("loanEdit$id") { LoanEditViewModel(it, id) }
    val people by vm.people.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var existing by remember { mutableStateOf<LoanEntity?>(null) }
    var kind by rememberSaveable { mutableStateOf(LoanKind.LENT) }
    var title by rememberSaveable { mutableStateOf("") }
    var personId by rememberSaveable { mutableStateOf<Long?>(null) }
    var newPerson by rememberSaveable { mutableStateOf("") }
    var lender by rememberSaveable { mutableStateOf("") }
    var principal by rememberSaveable { mutableStateOf("") }
    var rate by rememberSaveable { mutableStateOf("") }
    var method by rememberSaveable { mutableStateOf(InterestMethod.NONE) }
    var compounding by rememberSaveable { mutableStateOf(CompoundingFrequency.MONTHLY) }
    var start by rememberSaveable { mutableStateOf(Dates.today().toEpochDay()) }
    var due by rememberSaveable { mutableStateOf<Long?>(null) }
    var tenure by rememberSaveable { mutableStateOf("") }
    var notes by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(id) {
        vm.load()?.let { l ->
            existing = l; kind = l.kind; title = l.title; personId = l.personId; lender = l.lenderName ?: ""; principal = Money.toInput(l.principalPaise)
            rate = if (l.annualRateBps > 0) BigDecimal(l.annualRateBps).movePointLeft(2).stripTrailingZeros().toPlainString() else ""
            method = l.interestMethod; compounding = l.compounding; start = l.startDate; due = l.dueDate; tenure = l.tenureMonths?.toString() ?: ""; notes = l.notes ?: ""
        }
    }

    Scaffold(contentWindowInsets = WindowInsets(0), topBar = { FinoraTopBar(if (id == null) "New loan" else "Edit loan", back) }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SelectField("Kind", LoanKind.entries.toList(), kind, { it.label }, { kind = it })
            OutlinedTextField(title, { title = it }, label = { Text("Name") }, placeholder = { Text(if (kind == LoanKind.FORMAL_LOAN) "Car loan" else "Rent deposit for Arjun") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            if (kind == LoanKind.FORMAL_LOAN) OutlinedTextField(lender, { lender = it }, label = { Text("Lender") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            else {
                SelectField(if (kind == LoanKind.LENT) "Lent to" else "Borrowed from", listOf<PersonEntity?>(null) + people, people.firstOrNull { it.id == personId }, { it?.name ?: "Someone new" }, { personId = it?.id }, placeholder = "Someone new")
                if (personId == null) OutlinedTextField(newPerson, { newPerson = it }, label = { Text("Their name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
            MoneyField("Principal", principal, { principal = it })
            SelectField("Interest", InterestMethod.entries.toList(), method, { it.label }, { method = it })
            if (method != InterestMethod.NONE) {
                OutlinedTextField(rate, { if (it.isEmpty() || it.matches(Regex("\\d{0,3}(\\.\\d{0,2})?"))) rate = it }, label = { Text("Rate, % a year") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
                if (method == InterestMethod.COMPOUND) SelectField("Compounded", CompoundingFrequency.entries.toList(), compounding, { it.label }, { compounding = it })
            }
            DateField("Start date", LocalDate.ofEpochDay(start), { start = it.toEpochDay() })
            if (kind == LoanKind.FORMAL_LOAN) OutlinedTextField(tenure, { if (it.all(Char::isDigit) && it.length <= 3) tenure = it }, label = { Text("Tenure (months, for EMI)") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
            DateField("Due date (optional)", due?.let(LocalDate::ofEpochDay), { due = it.toEpochDay() }, placeholder = "No due date")
            OutlinedTextField(notes, { notes = it }, label = { Text("Notes") }, minLines = 2, modifier = Modifier.fillMaxWidth())
            InfoNote(assumptions(method, compounding))
            ErrorNote(error)
            Button(modifier = Modifier.fillMaxWidth().height(52.dp), onClick = {
                val p = Money.parseToPaise(principal)
                val bps = if (method == InterestMethod.NONE) 0 else rate.toBigDecimalOrNull()?.movePointRight(2)?.toInt()
                val months = tenure.toIntOrNull()
                when {
                    p == null || p <= 0 -> error = "Enter the principal."
                    bps == null || bps < 0 -> error = "Enter the interest rate."
                    due != null && due!! < start -> error = "The due date is before the start date."
                    kind == LoanKind.FORMAL_LOAN && tenure.isNotEmpty() && (months == null || months <= 0) -> error = "Enter the tenure in months."
                    else -> scope.launch {
                        val pid = if (kind == LoanKind.FORMAL_LOAN) null else personId ?: newPerson.takeIf { it.isNotBlank() }?.let { vm.newPerson(it.trim()) }
                        val emi = if (kind == LoanKind.FORMAL_LOAN && months != null) LoanCalculator.emi(p, bps, months) else null
                        val base = existing ?: LoanEntity(kind = kind, title = "", principalPaise = p, annualRateBps = bps, interestMethod = method, startDate = start, createdAt = 0)
                        val name = title.ifBlank { listOfNotNull(kind.label, people.firstOrNull { it.id == pid }?.name ?: newPerson.ifBlank { null } ?: lender.ifBlank { null }).joinToString(" · ") }
                        val id2 = vm.save(base.copy(kind = kind, title = name, personId = pid, lenderName = lender.ifBlank { null }, principalPaise = p, annualRateBps = bps, interestMethod = method,
                            compounding = compounding, startDate = start, dueDate = due, tenureMonths = months, emiPaise = emi, notes = notes.ifBlank { null }))
                        saved(id2)
                    }
                }
            }) { Text("Save") }
            Spacer(Modifier.height(24.dp))
        }
    }
}

fun assumptions(method: InterestMethod, c: CompoundingFrequency) = when (method) {
    InterestMethod.NONE -> "No interest: only the principal is owed."
    InterestMethod.SIMPLE -> "Simple interest accrues daily (actual/365) on the outstanding principal. Payments clear accrued interest first, then principal."
    InterestMethod.COMPOUND -> "Interest accrues daily (actual/365); unpaid interest is added to the balance ${c.label.lowercase()}, counted from the start date. Payments clear interest first, then principal."
}

class LoanDetailViewModel(private val c: AppContainer, val id: Long) : ViewModel() {
    val data = combine(c.db.loans().observe(id), c.db.loans().observePayments(id), c.db.people().observeAll()) { l, p, people -> Triple(l, p, people) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val documents = c.db.documents().observeFor(LinkTarget.LOAN.name, id).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    suspend fun addPayment(kind: LoanPaymentKind, amount: Long, date: LocalDate, note: String?): String? =
        runCatching { c.loans.addPayment(id, kind, amount, date, null, note) }.exceptionOrNull()?.message
    fun deletePayment(p: com.finora.app.data.db.LoanPaymentEntity) = viewModelScope.launch { c.loans.deletePayment(p) }
    fun close() = viewModelScope.launch { c.db.loans().get(id)?.let { c.loans.close(it, Dates.today()) } }
    fun reopen() = viewModelScope.launch { c.db.loans().get(id)?.let { c.loans.reopen(it) } }
    fun delete(done: () -> Unit) = viewModelScope.launch { c.db.loans().get(id)?.let { c.loans.delete(it) }; done() }
}

@Composable
fun LoanDetailScreen(id: Long, back: () -> Unit, open: (String) -> Unit) {
    val vm = appViewModel("loan$id") { LoanDetailViewModel(it, id) }
    val data by vm.data.collectAsStateWithLifecycle()
    val docs by vm.documents.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var addOpen by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    Scaffold(contentWindowInsets = WindowInsets(0), topBar = {
        FinoraTopBar(data?.first?.title ?: "Loan", back) {
            IconButton(onClick = { open(Routes.loanEdit(id)) }) { Icon(Icons.Filled.Edit, "Edit") }
            IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Filled.Delete, "Delete") }
        }
    }) { pad ->
        val (loan, payments, people) = data ?: return@Scaffold
        loan ?: return@Scaffold
        val today = Dates.today()
        val row = buildLoans(listOf(loan), payments, today).first()
        val st = row.state
        LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(20.dp, 8.dp, 20.dp, 40.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                SectionCard {
                    Text(if (loan.kind == LoanKind.LENT) "They owe you" else "You owe", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(Money.format(st.totalOutstandingPaise), style = MaterialTheme.typography.displaySmall.merge(AmountStyle))
                    Text("as of ${Dates.formatDay(today)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(12.dp))
                    Row {
                        StatTile("Principal left", Money.format(st.principalOutstandingPaise), Modifier.weight(1f))
                        StatTile("Interest accrued", Money.format(st.interestOutstandingPaise), Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(10.dp))
                    Row {
                        StatTile("Principal repaid", Money.format(st.principalRepaidPaise), Modifier.weight(1f))
                        StatTile(if (loan.kind == LoanKind.LENT) "Interest received" else "Interest paid", Money.format(st.interestPaidPaise), Modifier.weight(1f))
                    }
                    if (st.overpaidPaise > 0) { Spacer(Modifier.height(8.dp)); Text("Overpaid by ${Money.format(st.overpaidPaise)} — check the payments.", color = Finance.colors.warning, style = MaterialTheme.typography.bodySmall) }
                    if (st.waivedPaise > 0) Text("Waived: ${Money.format(st.waivedPaise)}", style = MaterialTheme.typography.bodySmall)
                }
            }
            item {
                SectionCard {
                    KeyValueRow("With", people.firstOrNull { it.id == loan.personId }?.name ?: loan.lenderName ?: "—")
                    KeyValueRow("Principal", Money.format(st.totalPrincipalPaise))
                    KeyValueRow("Interest", if (loan.interestMethod == InterestMethod.NONE) "None" else "${BigDecimal(loan.annualRateBps).movePointLeft(2).stripTrailingZeros().toPlainString()}% a year, ${loan.interestMethod.label.lowercase()}")
                    KeyValueRow("Started", Dates.formatDay(LocalDate.ofEpochDay(loan.startDate)))
                    row.dueDate?.let { KeyValueRow(if (row.overdue) "Overdue since" else "Next due", Dates.formatDay(it), if (row.overdue) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface) }
                    loan.emiPaise?.let { KeyValueRow("EMI", Money.format(it)) }
                    Spacer(Modifier.height(6.dp))
                    InfoNote(assumptions(loan.interestMethod, loan.compounding))
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = { addOpen = true }, modifier = Modifier.weight(1f)) { Text("Record payment") }
                    if (loan.status == LoanStatus.ACTIVE) OutlinedButton(onClick = { vm.close() }, modifier = Modifier.weight(1f)) { Text("Mark closed") }
                    else OutlinedButton(onClick = { vm.reopen() }, modifier = Modifier.weight(1f)) { Text("Reopen") }
                }
            }
            item { SectionHeader("Payment history") }
            if (st.payments.isEmpty()) item { Text("No payments yet.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(st.payments.zip(payments.sortedWith(compareBy({ it.date }, { it.id })).filter { !LocalDate.ofEpochDay(it.date).isAfter(today) }).reversed()) { (applied, entity) ->
                SectionCard(padding = 14.dp) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("${applied.kind.label} · ${Dates.formatDay(applied.date)}", style = MaterialTheme.typography.titleSmall)
                            if (applied.kind != LoanPaymentKind.ADDITIONAL_DISBURSEMENT) Text("Interest ${Money.format(applied.toInterestPaise)} · principal ${Money.format(applied.toPrincipalPaise)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (entity.transactionId != null) TextButton(onClick = { open(Routes.txn(entity.transactionId)) }) { Text("From a bank transaction") }
                        }
                        Text(Money.format(applied.amountPaise), style = MaterialTheme.typography.titleSmall.merge(AmountStyle))
                        IconButton(onClick = { vm.deletePayment(entity) }) { Icon(Icons.Filled.Delete, "Remove payment") }
                    }
                }
            }
            if (loan.kind == LoanKind.FORMAL_LOAN && loan.tenureMonths != null) {
                item { SectionHeader("EMI schedule (planned)") }
                item {
                    SectionCard {
                        val sched = LoanCalculator.schedule(loan.principalPaise, loan.annualRateBps, loan.tenureMonths, LocalDate.ofEpochDay(loan.startDate).plusMonths(1), loan.emiPaise)
                        sched.take(24).forEach { r ->
                            KeyValueRow("${r.number}. ${Dates.formatDayShort(r.dueDate)} · int ${Money.formatCompact(r.interestPaise)}", Money.format(r.emiPaise))
                        }
                        if (sched.size > 24) Text("…and ${sched.size - 24} more", style = MaterialTheme.typography.bodySmall)
                        HorizontalDivider(Modifier.padding(vertical = 6.dp))
                        KeyValueRow("Total interest over the loan", Money.format(sched.sumOf { it.interestPaise }), bold = true)
                    }
                }
            }
            if (docs.isNotEmpty()) {
                item { SectionHeader("Agreements & documents") }
                items(docs) { d -> TextButton(onClick = { open(Routes.document(d.id)) }) { Text(d.title) } }
            }
            loan.notes?.let { item { InfoNote(it) } }
        }
    }

    if (addOpen) {
        var kind by remember { mutableStateOf(LoanPaymentKind.REPAYMENT) }
        var amount by remember { mutableStateOf("") }
        var date by remember { mutableStateOf(Dates.today()) }
        var note by remember { mutableStateOf("") }
        var err by remember { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { addOpen = false },
            title = { Text("Record payment") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SelectField("Type", LoanPaymentKind.entries.toList(), kind, { it.label }, { kind = it })
                    MoneyField("Amount", amount, { amount = it }, error = err)
                    DateField("Date", date, { date = it }, maxDate = Dates.today())
                    OutlinedTextField(note, { note = it }, label = { Text("Note") }, singleLine = true)
                    Text("Paid by bank transfer? Link it from the transaction instead so it is not counted twice.", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = { TextButton(onClick = {
                val p = Money.parseToPaise(amount)
                if (p == null || p <= 0) err = "Enter an amount." else scope.launch { err = vm.addPayment(kind, p, date, note.ifBlank { null }); if (err == null) addOpen = false }
            }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { addOpen = false }) { Text("Cancel") } },
        )
    }
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("Delete this loan?") },
        text = { Text("Its payment history is deleted. Linked bank transactions stay, but are no longer attached to a loan.") },
        confirmButton = { TextButton(onClick = { confirmDelete = false; vm.delete(back) }) { Text("Delete") } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
    )
}
