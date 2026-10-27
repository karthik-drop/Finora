package com.finora.app.ui.transactions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.finora.app.AppContainer
import com.finora.app.core.Dates
import com.finora.app.core.Money
import com.finora.app.data.db.BillSplitSummary
import com.finora.app.data.db.CategoryEntity
import com.finora.app.data.db.LoanEntity
import com.finora.app.data.db.PersonEntity
import com.finora.app.domain.merchant.MerchantLearningPolicy
import com.finora.app.domain.model.CategoryKind
import com.finora.app.domain.model.Direction
import com.finora.app.domain.model.LinkTarget
import com.finora.app.domain.model.LoanPaymentKind
import com.finora.app.domain.model.TxnType
import com.finora.app.ui.Routes
import com.finora.app.ui.appViewModel
import com.finora.app.ui.components.AmountText
import com.finora.app.ui.components.ErrorNote
import com.finora.app.ui.components.FinoraTopBar
import com.finora.app.ui.components.InfoNote
import com.finora.app.ui.components.KeyValueRow
import com.finora.app.ui.components.SectionCard
import com.finora.app.ui.components.SectionHeader
import com.finora.app.ui.components.SelectField
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class TransactionDetailViewModel(private val c: AppContainer, val id: Long) : ViewModel() {
    val row = c.db.transactions().observeRow(id).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val lookups = combine(c.db.categories().observeAll(), c.db.people().observeAll(), c.db.loans().observeAll(), c.db.billSplits().observeSummaries()) { a, b, l, s -> Lookups(a, b, l, s) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Lookups())
    val documents = c.db.documents().observeFor(LinkTarget.TRANSACTION.name, id).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val merchant = kotlinx.coroutines.flow.flow { emit(c.db.transactions().get(id)?.merchantId?.let { c.db.merchants().get(it) }) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    data class Lookups(val categories: List<CategoryEntity> = emptyList(), val people: List<PersonEntity> = emptyList(), val loans: List<LoanEntity> = emptyList(), val splits: List<BillSplitSummary> = emptyList())

    suspend fun setCategory(categoryId: Long): Int = c.transactions.correctCategory(id, categoryId)
    fun recategorizePast(merchantId: Long, categoryId: Long) = viewModelScope.launch { c.transactions.recategorizePastForMerchant(merchantId, categoryId) }
    fun save(type: TxnType, personId: Long?, purpose: String?, notes: String?) = viewModelScope.launch {
        val t = c.db.transactions().get(id) ?: return@launch
        c.transactions.update(t.copy(type = type, personId = personId, purpose = purpose?.ifBlank { null }, notes = notes?.ifBlank { null }))
    }
    suspend fun linkLoan(loanId: Long, kind: LoanPaymentKind): String? = runCatching {
        val t = c.db.transactions().get(id)!!
        c.loans.addPayment(loanId, kind, t.amountPaise, Dates.toLocalDate(t.occurredAt), id, null)
    }.exceptionOrNull()?.message
    suspend fun settleSplit(splitId: Long, participantId: Long): String? {
        val t = c.db.transactions().get(id) ?: return "Transaction not found"
        return c.splits.settle(splitId, participantId, t.amountPaise, t.occurredAt, id, null).exceptionOrNull()?.message
    }
    suspend fun participants(splitId: Long) = c.db.billSplits().participants(splitId)
    fun delete(done: () -> Unit) = viewModelScope.launch { c.db.transactions().get(id)?.let { c.transactions.delete(it) }; done() }
}

@Composable
fun TransactionDetailScreen(id: Long, back: () -> Unit, open: (String) -> Unit) {
    val vm = appViewModel("txn$id") { TransactionDetailViewModel(it, id) }
    val r by vm.row.collectAsStateWithLifecycle()
    val l by vm.lookups.collectAsStateWithLifecycle()
    val docs by vm.documents.collectAsStateWithLifecycle()
    val merchant by vm.merchant.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var confirmDelete by remember { mutableStateOf(false) }
    var offerPast by remember { mutableStateOf<Pair<Int, Long>?>(null) }
    var linkDialog by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    Scaffold(contentWindowInsets = WindowInsets(0), topBar = {
        FinoraTopBar("Transaction", back) { androidx.compose.material3.IconButton(onClick = { confirmDelete = true }) { androidx.compose.material3.Icon(Icons.Filled.Delete, "Delete") } }
    }) { pad ->
        val row = r ?: return@Scaffold
        val t = row.txn
        var type by rememberSaveable(t.id) { mutableStateOf(t.type) }
        var personId by rememberSaveable(t.id) { mutableStateOf(t.personId) }
        var purpose by rememberSaveable(t.id) { mutableStateOf(t.purpose ?: "") }
        var notes by rememberSaveable(t.id) { mutableStateOf(t.notes ?: "") }
        LaunchedEffect(t.type, t.personId) { type = t.type; personId = t.personId }

        Column(Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(row.merchantName ?: row.personName ?: t.rawCounterparty ?: t.type.label, style = MaterialTheme.typography.titleMedium)
                AmountText(t.amountPaise, t.direction, t.type, style = MaterialTheme.typography.displaySmall, strike = t.reversed)
                Text("${Dates.formatDay(t.occurredAt)} · ${Dates.formatTime(t.occurredAt)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (t.reversed) Text("Reversed by the bank — counts for nothing", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            ErrorNote(error)

            SectionCard {
                SelectField("Category", l.categories.filter { it.kind == if (t.direction == Direction.DEBIT) CategoryKind.EXPENSE else CategoryKind.INCOME } + l.categories.filter { it.kind != if (t.direction == Direction.DEBIT) CategoryKind.EXPENSE else CategoryKind.INCOME },
                    l.categories.firstOrNull { it.id == t.categoryId }, { it.name }, { cat ->
                        scope.launch {
                            val others = vm.setCategory(cat.id)
                            if (others > 0 && t.merchantId != null) offerPast = others to cat.id
                        }
                    })
                if (t.autoCategorized) {
                    Spacer(Modifier.height(6.dp))
                    Text("Categorised automatically · ${MerchantLearningPolicy.progressLabel(merchant?.confirmationCount ?: 3)}. Changing it updates the merchant for the future.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(10.dp))
                SelectField("Type", TxnType.entries.filter { compatible(it, t.direction) }, type, { it.label }, { type = it })
                Spacer(Modifier.height(10.dp))
                SelectField("Person", listOf<PersonEntity?>(null) + l.people, l.people.firstOrNull { it.id == personId }, { it?.name ?: "None" }, { personId = it?.id }, placeholder = "None")
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(purpose, { purpose = it }, label = { Text("Purpose") }, placeholder = { Text("e.g. work lunch, shared dinner") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(notes, { notes = it }, label = { Text("Notes") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
                Spacer(Modifier.height(12.dp))
                Button(onClick = { vm.save(type, personId, purpose, notes) }, modifier = Modifier.fillMaxWidth()) { Text("Save changes") }
            }

            SectionCard {
                KeyValueRow("Counts as", when (t.type.countsAs) {
                    TxnType.Flow.INCOME -> "Income"; TxnType.Flow.EXPENSE -> "Expense"; TxnType.Flow.EXPENSE_REDUCTION -> "Reduces spending"; TxnType.Flow.NEUTRAL -> "Neither income nor expense"
                })
                row.accountName?.let { KeyValueRow("Account", it) }
                t.paymentMethod?.let { KeyValueRow("Method", it.label) }
                t.reference?.let { KeyValueRow("Reference", it) }
                t.balanceAfterPaise?.let { KeyValueRow("Balance after", Money.format(it)) }
                if (t.feePaise > 0) KeyValueRow("Charges", Money.format(t.feePaise))
                if (t.taxPaise > 0) KeyValueRow("GST", Money.format(t.taxPaise))
                KeyValueRow("Source", when (t.source) { com.finora.app.domain.model.TxnSource.SMS -> "Bank SMS"; com.finora.app.domain.model.TxnSource.MANUAL -> "Entered by you"; com.finora.app.domain.model.TxnSource.CSV_IMPORT -> "CSV import"; com.finora.app.domain.model.TxnSource.RECEIPT -> "Receipt" })
                t.linkedTransactionId?.let { lid -> TextButton(onClick = { open(Routes.txn(lid)) }) { Text("Open the linked transaction") } }
            }

            SectionHeader("Link")
            SectionCard {
                Text("Linking records this money against a loan or a shared bill, so it is not counted again as income or spending.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { linkDialog = "loan" }, enabled = t.loanId == null) { Text(if (t.loanId == null) "Loan payment" else "Linked to loan") }
                    OutlinedButton(onClick = { linkDialog = "split" }, enabled = t.direction == Direction.CREDIT && t.type != TxnType.BILL_SETTLEMENT) { Text("Bill settlement") }
                }
                t.loanId?.let { TextButton(onClick = { open(Routes.loan(it)) }) { Text("Open loan") } }
                t.billSplitId?.let { TextButton(onClick = { open(Routes.split(it)) }) { Text("Open shared bill") } }
            }

            if (docs.isNotEmpty()) {
                SectionHeader("Documents")
                SectionCard { docs.forEach { d -> TextButton(onClick = { open(Routes.document(d.id)) }) { Text(d.title) } } }
            }

            t.smsBody?.let { body ->
                SectionHeader("Original message")
                InfoNote("${t.smsSender ?: ""}\n$body")
            }
            Spacer(Modifier.height(24.dp))
        }

        if (linkDialog == "loan") LinkLoanDialog(l.loans, onDismiss = { linkDialog = null }) { loanId, kind ->
            scope.launch { error = vm.linkLoan(loanId, kind); linkDialog = null }
        }
        if (linkDialog == "split") LinkSplitDialog(l.splits, vm, onDismiss = { linkDialog = null }) { splitId, pid ->
            scope.launch { error = vm.settleSplit(splitId, pid); linkDialog = null }
        }
    }

    offerPast?.let { (count, catId) ->
        AlertDialog(
            onDismissRequest = { offerPast = null },
            title = { Text("Update earlier transactions?") },
            text = { Text("$count earlier transaction${if (count == 1) "" else "s"} from this merchant ${if (count == 1) "has" else "have"} a different category. Future ones will use the new category either way.") },
            confirmButton = { TextButton(onClick = { r?.txn?.merchantId?.let { vm.recategorizePast(it, catId) }; offerPast = null }) { Text("Update all") } },
            dismissButton = { TextButton(onClick = { offerPast = null }) { Text("Leave them") } },
        )
    }
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("Delete this transaction?") },
        text = { Text("It is removed from every total. If it came from an SMS, re-importing that message will add it back.") },
        confirmButton = { TextButton(onClick = { confirmDelete = false; vm.delete(back) }) { Text("Delete") } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
    )
}

fun compatible(t: TxnType, d: Direction): Boolean = when (t) {
    TxnType.EXPENSE, TxnType.LOAN_REPAYMENT, TxnType.LENT, TxnType.BORROWING_REPAID, TxnType.INTEREST_PAID, TxnType.BANK_FEE, TxnType.TAX,
    TxnType.ATM_WITHDRAWAL, TxnType.INVESTMENT_PURCHASE, TxnType.PERSON_PAYMENT -> d == Direction.DEBIT
    TxnType.INCOME, TxnType.SALARY, TxnType.LOAN_DISBURSEMENT, TxnType.LENDING_REPAYMENT_RECEIVED, TxnType.BORROWED, TxnType.REFUND,
    TxnType.REVERSAL, TxnType.INTEREST_EARNED, TxnType.CASH_DEPOSIT, TxnType.INVESTMENT_REDEMPTION, TxnType.PERSON_RECEIPT, TxnType.BILL_SETTLEMENT -> d == Direction.CREDIT
    TxnType.SELF_TRANSFER, TxnType.CARD_BILL_PAYMENT -> true
}

@Composable
private fun LinkLoanDialog(loans: List<LoanEntity>, onDismiss: () -> Unit, onLink: (Long, LoanPaymentKind) -> Unit) {
    var loan by remember { mutableStateOf<LoanEntity?>(null) }
    var kind by remember { mutableStateOf(LoanPaymentKind.REPAYMENT) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Record against a loan") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (loans.isEmpty()) Text("No loans yet. Create one under Loans first.")
                SelectField("Loan", loans, loan, { it.title }, { loan = it })
                SelectField("As", listOf(LoanPaymentKind.REPAYMENT, LoanPaymentKind.ADDITIONAL_DISBURSEMENT, LoanPaymentKind.INTEREST_ONLY), kind, { it.label }, { kind = it })
            }
        },
        confirmButton = { TextButton(enabled = loan != null, onClick = { onLink(loan!!.id, kind) }) { Text("Link") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun LinkSplitDialog(splits: List<BillSplitSummary>, vm: TransactionDetailViewModel, onDismiss: () -> Unit, onLink: (Long, Long) -> Unit) {
    var split by remember { mutableStateOf<BillSplitSummary?>(null) }
    var people by remember { mutableStateOf(listOf<com.finora.app.data.db.BillParticipantEntity>()) }
    var person by remember { mutableStateOf<com.finora.app.data.db.BillParticipantEntity?>(null) }
    LaunchedEffect(split) { people = split?.let { vm.participants(it.split.id).filter { p -> !p.isMe } } ?: emptyList(); person = null }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Settle a shared bill") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val open = splits.filter { it.outstandingPaise > 0 }
                if (open.isEmpty()) Text("No shared bills have money outstanding.")
                SelectField("Bill", open, split, { "${it.split.title} · ${Money.format(it.outstandingPaise)} owed" }, { split = it })
                SelectField("Paid back by", people, person, { it.displayName }, { person = it })
                Text("The credit becomes a settlement against the bill, not income.", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(enabled = split != null && person != null, onClick = { onLink(split!!.split.id, person!!.id) }) { Text("Settle") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
