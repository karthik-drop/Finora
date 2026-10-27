package com.finora.app.ui.analytics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.finora.app.AppContainer
import com.finora.app.core.Dates
import com.finora.app.core.Money
import com.finora.app.domain.analytics.Analytics
import com.finora.app.domain.analytics.AnalyticsTxn
import com.finora.app.domain.analytics.Insight
import com.finora.app.domain.analytics.MonthPoint
import com.finora.app.domain.analytics.PeriodSummary
import com.finora.app.domain.analytics.RecurringExpense
import com.finora.app.domain.analytics.Slice
import com.finora.app.ui.appViewModel
import com.finora.app.ui.buildHoldings
import com.finora.app.ui.buildLoans
import com.finora.app.ui.components.BarGroup
import com.finora.app.ui.components.EmptyState
import com.finora.app.ui.components.FinoraTopBar
import com.finora.app.ui.components.InfoNote
import com.finora.app.ui.components.KeyValueRow
import com.finora.app.ui.components.PairedBarChart
import com.finora.app.ui.components.RankBar
import com.finora.app.ui.components.SectionCard
import com.finora.app.ui.components.SectionHeader
import com.finora.app.ui.components.StatTile
import com.finora.app.ui.components.parseHex
import com.finora.app.ui.latestBalances
import com.finora.app.ui.lendingTotals
import com.finora.app.ui.theme.AmountStyle
import com.finora.app.ui.theme.Finance
import com.finora.app.ui.totals
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import java.time.YearMonth

data class NetWorth(val cashPaise: Long, val accountsKnown: Int, val investmentsPaise: Long, val lentPaise: Long, val borrowedPaise: Long) {
    val totalPaise: Long get() = cashPaise + investmentsPaise + lentPaise - borrowedPaise
}

data class AnalyticsState(
    val month: YearMonth,
    val summary: PeriodSummary,
    val lastMonth: PeriodSummary,
    val trend: List<MonthPoint>,
    val categories: List<Pair<Slice, String?>>,
    val merchants: List<Slice>,
    val recurring: List<RecurringExpense>,
    val insights: List<Insight>,
    val netWorth: NetWorth,
    val projection: Analytics.Projection,
)

@OptIn(ExperimentalCoroutinesApi::class)
class AnalyticsViewModel(c: AppContainer) : ViewModel() {
    val month = MutableStateFlow(YearMonth.now(Dates.zone))
    private val db = c.db

    private val txns = month.flatMapLatest { m ->
        // Twelve months back for trends, recurring detection and the three-month baseline.
        val from = Dates.monthRange(m.minusMonths(11)).first
        db.transactions().observeRowsInRange(from, Dates.monthRange(m).last + 1)
    }

    private val worth = combine(db.accounts().observeAll(), db.transactions().observeWithBalances(), db.loans().observeAll(), db.loans().observeAllPayments(),
        combine(db.investments().observeAll(), db.investments().observeAllTxns(), db.investments().observeAllValuations()) { a, b, cc -> Triple(a, b, cc) }) { acc, bal, loans, pays, inv ->
        val today = Dates.today()
        val balances = latestBalances(acc, bal)
        val lt = lendingTotals(buildLoans(loans, pays, today))
        val pt = totals(buildHoldings(inv.first, inv.second, inv.third, today, 14))
        NetWorth(balances.sumOf { it.balancePaise }, balances.size, pt.valuePaise, lt.lentOutstanding, lt.borrowedOutstanding) to
            buildLoans(loans, pays, today).filter { it.dueDate != null && !it.dueDate.isAfter(today.plusDays(30)) && it.state.totalOutstandingPaise > 0 && it.loan.kind != com.finora.app.domain.model.LoanKind.LENT }
                .map { it.loan.title to (it.loan.emiPaise ?: it.state.totalOutstandingPaise) }
    }

    val state = combine(month, txns, db.categories().observeAll(), worth, db.recurringBills().observeAll()) { m, rows, cats, w, bills ->
        val all = rows.map { AnalyticsTxn(it.txn.amountPaise, it.txn.type, Dates.toLocalDate(it.txn.occurredAt), it.categoryName, it.merchantName, it.txn.reversed) }
        val inMonth = all.filter { YearMonth.from(it.date) == m }
        val prev = all.filter { YearMonth.from(it.date) == m.minusMonths(1) }
        val months = (11 downTo 0).map { m.minusMonths(it.toLong()) }
        val colors = cats.associate { it.name to it.colorHex }
        val catSlices = Analytics.byCategory(inMonth)
        val baseline = (1..3).map { k -> Analytics.byCategory(all.filter { YearMonth.from(it.date) == m.minusMonths(k.toLong()) }) }
        val s = Analytics.summary(inMonth)
        val lastS = Analytics.summary(prev)
        val insights = listOfNotNull(Analytics.monthComparison(s, lastS)) + Analytics.unusualSpending(catSlices, baseline.filter { b -> b.isNotEmpty() })
        val obligations = bills.filter { it.active }.map { it.name to it.amountPaise } + w.second
        AnalyticsState(m, s, lastS, Analytics.monthly(all, months), catSlices.map { it to colors[it.label] }, Analytics.byMerchant(inMonth, 8), Analytics.recurring(all), insights, w.first, Analytics.projectOutflows(obligations))
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}

@Composable
fun AnalyticsScreen(back: (() -> Unit)?) {
    val vm = appViewModel { AnalyticsViewModel(it) }
    val st by vm.state.collectAsStateWithLifecycle()
    val chart = Finance.colors.chart
    Scaffold(contentWindowInsets = WindowInsets(0), topBar = { FinoraTopBar("Analytics", back) }) { pad ->
        val s = st ?: return@Scaffold
        LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(20.dp, 4.dp, 20.dp, 40.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { vm.month.value = s.month.minusMonths(1) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Previous month") }
                    Text(Dates.formatMonth(s.month), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    IconButton(enabled = s.month < YearMonth.now(Dates.zone), onClick = { vm.month.value = s.month.plusMonths(1) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Next month") }
                }
            }
            item {
                SectionCard {
                    Row {
                        StatTile("Income", Money.format(s.summary.incomePaise), Modifier.weight(1f), valueColor = Finance.colors.income)
                        StatTile("Spending", Money.format(s.summary.expensePaise), Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(10.dp))
                    Row {
                        StatTile("Net", Money.format(s.summary.netPaise), Modifier.weight(1f), valueColor = if (s.summary.netPaise >= 0) Finance.colors.income else Finance.colors.expense)
                        StatTile("Savings rate", s.summary.savingsRateBps?.let { "${it / 100}%" } ?: "—", Modifier.weight(1f), sub = if (s.summary.savingsRateBps == null) "No income recorded" else null)
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("Transfers between your accounts, loan principal, lending, investments and settlements are not income or spending.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (s.insights.isNotEmpty()) {
                item { SectionHeader("Worth a look") }
                s.insights.forEach { i ->
                    item {
                        SectionCard(padding = 14.dp) {
                            Row(verticalAlignment = Alignment.Top) {
                                Icon(if (i.severity == Insight.Severity.WATCH) Icons.Filled.WarningAmber else Icons.Filled.Insights, null, tint = if (i.severity == Insight.Severity.WATCH) Finance.colors.warning else MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.width(10.dp))
                                Column { Text(i.title, style = MaterialTheme.typography.titleSmall); Text(i.explanation, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            }
                        }
                    }
                }
            }
            item { SectionHeader("Cash flow, 12 months") }
            item {
                SectionCard {
                    if (s.trend.all { it.incomePaise == 0L && it.expensePaise == 0L }) Text("Nothing recorded yet.", style = MaterialTheme.typography.bodyMedium)
                    else PairedBarChart(s.trend.takeLast(6).map { BarGroup(it.month.month.name.take(3).lowercase().replaceFirstChar(Char::uppercase), it.incomePaise, it.expensePaise) }, "Income", "Spending")
                }
            }
            item { SectionHeader("By category") }
            item {
                SectionCard {
                    if (s.categories.isEmpty()) EmptyState(Icons.Filled.Insights, "No spending this month", "Categorised expenses appear here.")
                    val max = s.categories.maxOfOrNull { it.first.amountPaise } ?: 1
                    s.categories.forEachIndexed { i, (sl, hex) -> RankBar(sl.label, sl.amountPaise, max, parseHex(hex, chart[i % chart.size]), "${Money.format(sl.amountPaise)} · ${sl.count}") }
                }
            }
            if (s.merchants.isNotEmpty()) {
                item { SectionHeader("Top merchants") }
                item {
                    SectionCard {
                        val max = s.merchants.maxOf { it.amountPaise }
                        s.merchants.forEach { RankBar(it.label, it.amountPaise, max, MaterialTheme.colorScheme.tertiary, "${Money.format(it.amountPaise)} · ${it.count}×") }
                    }
                }
            }
            item { SectionHeader("Net worth (recorded)") }
            item {
                SectionCard {
                    Text(Money.format(s.netWorth.totalPaise), style = MaterialTheme.typography.headlineMedium.merge(AmountStyle))
                    Spacer(Modifier.height(8.dp))
                    KeyValueRow("Account balances (${s.netWorth.accountsKnown} known)", Money.format(s.netWorth.cashPaise))
                    KeyValueRow("Investments (priced)", Money.format(s.netWorth.investmentsPaise))
                    KeyValueRow("Owed to you", Money.format(s.netWorth.lentPaise))
                    KeyValueRow("You owe", "−" + Money.format(s.netWorth.borrowedPaise))
                    Spacer(Modifier.height(6.dp))
                    Text("Only what Finora has a number for: latest balances from bank SMS or that you typed, the prices you entered, and loans you recorded. Anything else is left out, not guessed.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (s.recurring.isNotEmpty()) {
                item { SectionHeader("Looks recurring") }
                item {
                    SectionCard {
                        s.recurring.take(8).forEach { r -> KeyValueRow("${r.merchant} · ${r.months} months", "~" + Money.format(r.typicalPaise)) }
                        Text("Paid in 3+ different months at a similar amount (within 15%).", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            item { SectionHeader("Next 30 days (estimate)") }
            item {
                SectionCard {
                    if (s.projection.items.isEmpty()) Text("Add recurring bills in Settings, or loans with due dates, to see what is coming up.", style = MaterialTheme.typography.bodyMedium)
                    else {
                        Text(Money.format(s.projection.expectedOutflowPaise), style = MaterialTheme.typography.titleLarge.merge(AmountStyle))
                        s.projection.items.forEach { (n, a) -> KeyValueRow(n, Money.format(a)) }
                    }
                    InfoNote("An estimate from bills and loans you entered. It does not predict other spending.")
                }
            }
        }
    }
}
