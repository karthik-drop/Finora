package com.finora.app.ui.transactions

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FactCheck
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.finora.app.AppContainer
import com.finora.app.core.Dates
import com.finora.app.core.Money
import com.finora.app.data.db.CategoryEntity
import com.finora.app.data.db.TxnRow
import com.finora.app.domain.model.CategoryKind
import com.finora.app.domain.model.ReviewStatus
import com.finora.app.domain.model.TxnType
import com.finora.app.ui.Routes
import com.finora.app.ui.appViewModel
import com.finora.app.ui.components.AmountText
import com.finora.app.ui.components.CategoryBadge
import com.finora.app.ui.components.EmptyState
import com.finora.app.ui.components.FinoraTopBar
import com.finora.app.ui.components.Pill
import com.finora.app.ui.components.SelectField
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth

enum class DateFilter(val label: String) { ALL("All time"), THIS_MONTH("This month"), LAST_MONTH("Last month"), LAST_90("Last 90 days"), THIS_YEAR("This year") }
enum class TypeFilter(val label: String, val type: TxnType?) {
    ALL("All", null), EXPENSE("Expenses", TxnType.EXPENSE), INCOME("Income", TxnType.INCOME), SALARY("Salary", TxnType.SALARY),
    TRANSFER("Transfers", TxnType.SELF_TRANSFER), REFUND("Refunds", TxnType.REFUND), PERSON("To people", TxnType.PERSON_PAYMENT),
}

data class TxnQuery(val text: String = "", val date: DateFilter = DateFilter.ALL, val type: TypeFilter = TypeFilter.ALL, val categoryId: Long? = null)

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class TransactionsViewModel(private val c: AppContainer) : ViewModel() {
    val query = MutableStateFlow(TxnQuery())
    val categories = c.db.categories().observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val reviewCount = c.db.transactions().observeNeedsReviewCount().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val rows = query.debounce(150).flatMapLatest { q ->
        val (from, to) = range(q.date)
        val text = q.text.trim()
        // A numeric query also matches the amount ("450" finds ₹450.00).
        val amount = Money.parseToPaise(text)
        c.db.transactions().search(
            q = if (text.isEmpty() || amount != null) null else "%$text%", from = from, to = to, categoryId = q.categoryId, accountId = null,
            merchantId = null, personId = null, type = q.type.type?.name, minPaise = amount, maxPaise = amount, limit = 500,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private fun range(d: DateFilter): Pair<Long?, Long?> {
        val m = YearMonth.now(Dates.zone)
        return when (d) {
            DateFilter.ALL -> null to null
            DateFilter.THIS_MONTH -> Dates.monthRange(m).let { it.first to it.last + 1 }
            DateFilter.LAST_MONTH -> Dates.monthRange(m.minusMonths(1)).let { it.first to it.last + 1 }
            DateFilter.LAST_90 -> Dates.startOfDayMillis(Dates.today().minusDays(90)) to null
            DateFilter.THIS_YEAR -> Dates.startOfDayMillis(LocalDate.of(m.year, 1, 1)) to null
        }
    }

    /** Bulk categorisation is offered only for plain expenses — retyping transfers or lending in bulk is never "safe". */
    fun bulkCategorize(ids: Set<Long>, category: CategoryEntity, rows: List<TxnRow>) = viewModelScope.launch {
        rows.filter { it.txn.id in ids && it.txn.type == TxnType.EXPENSE }.forEach { c.transactions.correctCategory(it.txn.id, category.id) }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TransactionsScreen(open: (String) -> Unit) {
    val vm = appViewModel { TransactionsViewModel(it) }
    val q by vm.query.collectAsStateWithLifecycle()
    val rows by vm.rows.collectAsStateWithLifecycle()
    val cats by vm.categories.collectAsStateWithLifecycle()
    val reviewCount by vm.reviewCount.collectAsStateWithLifecycle()
    var selection by remember { mutableStateOf(setOf<Long>()) }
    var bulkOpen by remember { mutableStateOf(false) }
    var searchText by rememberSaveable { mutableStateOf(q.text) }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            if (selection.isEmpty()) FinoraTopBar("Transactions", actions = {
                if (reviewCount > 0) TextButton(onClick = { open(Routes.REVIEW) }) { Icon(Icons.Filled.FactCheck, null); Spacer(Modifier.width(6.dp)); Text("Review $reviewCount") }
            }) else FinoraTopBar("${selection.size} selected", actions = {
                TextButton(onClick = { bulkOpen = true }) { Text("Set category") }
                IconButton(onClick = { selection = emptySet() }) { Icon(Icons.Filled.Close, "Clear selection") }
            })
        },
        floatingActionButton = { FloatingActionButton(onClick = { open(Routes.ADD_TXN) }) { Icon(Icons.Filled.Add, "Add transaction") } },
    ) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(bottom = 96.dp)) {
            item {
                OutlinedTextField(
                    value = searchText, onValueChange = { searchText = it; vm.query.value = q.copy(text = it) },
                    leadingIcon = { Icon(Icons.Filled.Search, null) }, placeholder = { Text("Merchant, person, note, amount…") },
                    singleLine = true, modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
                    shape = MaterialTheme.shapes.large,
                )
            }
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(DateFilter.entries.toList()) { d -> FilterChip(selected = q.date == d, onClick = { vm.query.value = q.copy(date = d) }, label = { Text(d.label) }) }
                }
            }
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = 20.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(TypeFilter.entries.toList()) { t -> FilterChip(selected = q.type == t, onClick = { vm.query.value = q.copy(type = t) }, label = { Text(t.label) }) }
                }
            }
            item {
                Row(Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) {
                    SelectField("Category", listOf<CategoryEntity?>(null) + cats, cats.firstOrNull { it.id == q.categoryId }, { it?.name ?: "All categories" }, { vm.query.value = q.copy(categoryId = it?.id) }, placeholder = "All categories")
                }
            }
            val list = rows
            if (list != null && list.isEmpty()) item {
                EmptyState(Icons.AutoMirrored.Filled.ReceiptLong, if (q == TxnQuery()) "Nothing recorded yet" else "No matches", if (q == TxnQuery()) "Transactions from SMS, imports and manual entries appear here." else "Try a different search or filter.")
            }
            list?.groupBy { Dates.toLocalDate(it.txn.occurredAt) }?.forEach { (day, dayRows) ->
                item(key = "h$day") {
                    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 4.dp)) {
                        Text(Dates.relativeDay(day), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                        val spent = dayRows.filter { !it.txn.reversed && it.txn.type.countsAs == TxnType.Flow.EXPENSE }.sumOf { it.txn.amountPaise }
                        if (spent > 0) Text("Spent ${Money.format(spent)}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                items(dayRows, key = { it.txn.id }) { r ->
                    val selected = r.txn.id in selection
                    TransactionRow(
                        r, selected = selected,
                        modifier = Modifier.padding(horizontal = 8.dp),
                        onLongClick = { selection = selection + r.txn.id },
                    ) { if (selection.isNotEmpty()) selection = if (selected) selection - r.txn.id else selection + r.txn.id else open(Routes.txn(r.txn.id)) }
                }
            }
        }
    }

    if (bulkOpen) {
        val eligible = rows.orEmpty().count { it.txn.id in selection && it.txn.type == TxnType.EXPENSE }
        var chosen by remember { mutableStateOf<CategoryEntity?>(null) }
        AlertDialog(
            onDismissRequest = { bulkOpen = false },
            title = { Text("Set category") },
            text = {
                Column {
                    Text("Applies to $eligible expense${if (eligible == 1) "" else "s"} of ${selection.size} selected. Transfers, lending and other types are left alone.", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.size(12.dp))
                    SelectField("Category", cats.filter { it.kind == CategoryKind.EXPENSE }, chosen, { it.name }, { chosen = it })
                }
            },
            confirmButton = { TextButton(enabled = chosen != null && eligible > 0, onClick = { vm.bulkCategorize(selection, chosen!!, rows.orEmpty()); selection = emptySet(); bulkOpen = false }) { Text("Apply") } },
            dismissButton = { TextButton(onClick = { bulkOpen = false }) { Text("Cancel") } },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TransactionRow(r: TxnRow, modifier: Modifier = Modifier, selected: Boolean = false, onLongClick: (() -> Unit)? = null, onClick: () -> Unit) {
    val t = r.txn
    val title = r.merchantName ?: r.personName ?: t.rawCounterparty ?: t.type.label
    Row(
        modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selected) Icon(Icons.Filled.CheckCircle, "Selected", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(40.dp))
        else CategoryBadge(r.categoryIcon ?: typeIcon(t.type), r.categoryColor)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                val sub = listOfNotNull(
                    r.categoryName ?: t.type.takeIf { it.countsAs == TxnType.Flow.NEUTRAL || it == TxnType.REFUND }?.label,
                    Dates.formatTime(t.occurredAt),
                ).joinToString(" · ")
                Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (t.reviewStatus == ReviewStatus.NEEDS_REVIEW) { Spacer(Modifier.width(6.dp)); Pill("Review") }
                if (t.reversed) { Spacer(Modifier.width(6.dp)); Pill("Reversed", MaterialTheme.colorScheme.surfaceContainerHighest, MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
        Spacer(Modifier.width(8.dp))
        AmountText(t.amountPaise, t.direction, t.type, strike = t.reversed)
    }
}

fun typeIcon(t: TxnType): String = when (t) {
    TxnType.SELF_TRANSFER, TxnType.CARD_BILL_PAYMENT, TxnType.ATM_WITHDRAWAL, TxnType.CASH_DEPOSIT -> "transfer"
    TxnType.SALARY -> "work"
    TxnType.INTEREST_EARNED, TxnType.INVESTMENT_PURCHASE, TxnType.INVESTMENT_REDEMPTION -> "savings"
    TxnType.LOAN_DISBURSEMENT, TxnType.LOAN_REPAYMENT, TxnType.LENT, TxnType.BORROWED, TxnType.LENDING_REPAYMENT_RECEIVED, TxnType.BORROWING_REPAID -> "bank"
    TxnType.BANK_FEE, TxnType.TAX -> "receipt"
    else -> "payments"
}
