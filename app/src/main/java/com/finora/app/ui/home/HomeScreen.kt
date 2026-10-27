package com.finora.app.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.FactCheck
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.finora.app.AppContainer
import com.finora.app.core.Dates
import com.finora.app.core.Money
import com.finora.app.data.db.ReminderEntity
import com.finora.app.data.db.TxnRow
import com.finora.app.domain.analytics.Analytics
import com.finora.app.domain.analytics.AnalyticsTxn
import com.finora.app.domain.analytics.PeriodSummary
import com.finora.app.domain.analytics.Slice
import com.finora.app.platform.reminders.ReminderTiming
import com.finora.app.ui.AccountBalance
import com.finora.app.ui.LendingTotals
import com.finora.app.ui.LoanRow
import com.finora.app.ui.PortfolioTotals
import com.finora.app.ui.Routes
import com.finora.app.ui.appViewModel
import com.finora.app.ui.buildHoldings
import com.finora.app.ui.buildLoans
import com.finora.app.ui.components.ChartSlice
import com.finora.app.ui.components.DonutChart
import com.finora.app.ui.components.EmptyState
import com.finora.app.ui.components.SectionCard
import com.finora.app.ui.components.SectionHeader
import com.finora.app.ui.components.StatTile
import com.finora.app.ui.components.parseHex
import com.finora.app.ui.latestBalances
import com.finora.app.ui.lendingTotals
import com.finora.app.ui.theme.AmountStyle
import com.finora.app.ui.theme.Finance
import com.finora.app.ui.totals
import com.finora.app.ui.transactions.TransactionRow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth

enum class Period(val label: String) { THIS_MONTH("This month"), LAST_MONTH("Last month"), THIS_YEAR("This year") }

data class HomeState(
    val period: Period = Period.THIS_MONTH,
    val summary: PeriodSummary = PeriodSummary(0, 0, 0),
    val categories: List<Pair<Slice, String?>> = emptyList(),
    val recent: List<TxnRow> = emptyList(),
    val balances: List<AccountBalance> = emptyList(),
    val reviewCount: Int = 0,
    val unparsedCount: Int = 0,
    val loans: List<LoanRow> = emptyList(),
    val lending: LendingTotals = LendingTotals(0, 0),
    val portfolio: PortfolioTotals? = null,
    val reminders: List<Pair<String, LocalDateTime>> = emptyList(),
    val loaded: Boolean = false,
)

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModel(c: AppContainer) : ViewModel() {
    private val period = MutableStateFlow(Period.THIS_MONTH)
    private val db = c.db

    private val rangeRows = period.flatMapLatest { p ->
        val (from, to) = range(p)
        db.transactions().observeRowsInRange(from, to)
    }

    private val money = combine(rangeRows, db.categories().observeAll(), db.transactions().observeRecent(6), db.accounts().observeAll(), db.transactions().observeWithBalances()) { rows, cats, recent, accounts, withBal ->
        val at = rows.map { r -> AnalyticsTxn(r.txn.amountPaise, r.txn.type, Dates.toLocalDate(r.txn.occurredAt), r.categoryName, r.merchantName, r.txn.reversed) }
        val colors = cats.associate { it.name to it.colorHex }
        Triple(Analytics.summary(at), Analytics.byCategory(at).map { it to colors[it.label] }, recent) to latestBalances(accounts, withBal)
    }

    private val obligations = combine(db.loans().observeAll(), db.loans().observeAllPayments(), db.investments().observeAll(), db.investments().observeAllTxns(), db.investments().observeAllValuations()) { loans, pays, invs, itx, vals ->
        val today = Dates.today()
        val loanRows = buildLoans(loans, pays, today)
        val holdings = buildHoldings(invs, itx, vals, today, 14)
        Triple(loanRows, lendingTotals(loanRows), if (invs.isEmpty()) null else totals(holdings))
    }

    private val counts = combine(db.transactions().observeNeedsReviewCount(), db.unparsedMessages().observeOpenCount(), db.reminders().observeAll()) { a, b, r -> Triple(a, b, r) }

    val state: StateFlow<HomeState> = combine(period, money, obligations, counts) { p, m, o, cnt ->
        val now = LocalDateTime.now()
        HomeState(
            period = p, summary = m.first.first, categories = m.first.second, recent = m.first.third, balances = m.second,
            reviewCount = cnt.first, unparsedCount = cnt.second,
            loans = o.first.filter { it.dueDate != null && it.state.totalOutstandingPaise > 0 && it.dueDate.isBefore(Dates.today().plusDays(31)) }.sortedBy { it.dueDate },
            lending = o.second, portfolio = o.third,
            reminders = cnt.third.filter(ReminderEntity::enabled).map { it.title to ReminderTiming.next(it, now) }.sortedBy { it.second }.take(3),
            loaded = true,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeState())

    fun setPeriod(p: Period) { period.value = p }

    private fun range(p: Period): Pair<Long, Long> {
        val m = YearMonth.now(Dates.zone)
        return when (p) {
            Period.THIS_MONTH -> Dates.monthRange(m).let { it.first to it.last + 1 }
            Period.LAST_MONTH -> Dates.monthRange(m.minusMonths(1)).let { it.first to it.last + 1 }
            Period.THIS_YEAR -> Dates.startOfDayMillis(LocalDate.of(m.year, 1, 1)) to Dates.startOfDayMillis(LocalDate.of(m.year + 1, 1, 1))
        }
    }
}

@Composable
fun HomeScreen(open: (String) -> Unit) {
    val vm = appViewModel { HomeViewModel(it) }
    val s by vm.state.collectAsStateWithLifecycle()
    val chart = Finance.colors.chart
    Scaffold(
        contentWindowInsets = WindowInsets(0),
        floatingActionButton = {
            FloatingActionButton(onClick = { open(Routes.ADD_TXN) }, containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary) {
                Icon(Icons.Filled.Add, "Add transaction")
            }
        },
    ) { pad ->
        LazyColumn(
            Modifier.fillMaxSize().padding(pad).windowInsetsPadding(WindowInsets.statusBars),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(greeting(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("Your money", style = MaterialTheme.typography.headlineMedium)
                    }
                }
            }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(Period.entries.toList()) { p -> FilterChip(selected = s.period == p, onClick = { vm.setPeriod(p) }, label = { Text(p.label) }) }
                }
            }
            item { HeroCard(s) }
            if (s.reviewCount > 0 || s.unparsedCount > 0) item {
                SectionCard(onClick = { open(Routes.REVIEW) }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.FactCheck, null, tint = MaterialTheme.colorScheme.secondary)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("${s.reviewCount} to review", style = MaterialTheme.typography.titleMedium)
                            Text(
                                listOfNotNull("Confirm merchants, categories and purposes", s.unparsedCount.takeIf { it > 0 }?.let { "$it unread messages" }).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Icon(Icons.Filled.ChevronRight, null)
                    }
                }
            }
            if (s.balances.isNotEmpty()) {
                item { SectionHeader("Balances") }
                item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(s.balances) { b ->
                            SectionCard(Modifier.width(200.dp), padding = 14.dp) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Filled.AccountBalance, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Spacer(Modifier.width(6.dp))
                                    Text(b.account.name, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                Spacer(Modifier.height(6.dp))
                                Text(Money.format(b.balancePaise), style = MaterialTheme.typography.titleMedium.merge(AmountStyle))
                                Text((if (b.fromSms) "From bank SMS · " else "Entered · ") + Dates.formatDay(b.asOf), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
            if (s.categories.isNotEmpty()) {
                item { SectionHeader("Where it went", "Analytics") { open(Routes.ANALYTICS) } }
                item {
                    SectionCard {
                        val top = s.categories.take(5)
                        val rest = s.categories.drop(5).sumOf { it.first.amountPaise }
                        val slices = top.mapIndexed { i, (sl, hex) -> ChartSlice(sl.label, sl.amountPaise, parseHex(hex, chart[i % chart.size])) } +
                            if (rest > 0) listOf(ChartSlice("Everything else", rest, MaterialTheme.colorScheme.outline)) else emptyList()
                        DonutChart(slices, "Spent", Money.formatCompact(s.summary.expensePaise))
                    }
                }
            }
            item { SectionHeader("Lending & loans", "Open") { open(Routes.LOANS) } }
            item {
                SectionCard(onClick = { open(Routes.LOANS) }) {
                    Row {
                        StatTile("Others owe you", Money.format(s.lending.lentOutstanding), Modifier.weight(1f), valueColor = Finance.colors.income)
                        StatTile("You owe", Money.format(s.lending.borrowedOutstanding), Modifier.weight(1f), valueColor = if (s.lending.borrowedOutstanding > 0) Finance.colors.expense else MaterialTheme.colorScheme.onSurface)
                    }
                    if (s.loans.isNotEmpty()) {
                        Spacer(Modifier.height(12.dp))
                        s.loans.take(3).forEach { r ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(r.loan.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                                    Text((if (r.overdue) "Overdue since " else "Due ") + Dates.formatDayShort(r.dueDate!!), style = MaterialTheme.typography.bodySmall, color = if (r.overdue) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Text(Money.format(r.state.totalOutstandingPaise), style = MaterialTheme.typography.bodyMedium.merge(AmountStyle))
                            }
                        }
                    }
                }
            }
            item { SectionHeader("Investments", "Update prices") { open(Routes.PORTFOLIO_UPDATE) } }
            item {
                SectionCard(onClick = { open(Routes.INVEST) }) {
                    val p = s.portfolio
                    if (p == null) {
                        Text("No investments yet", style = MaterialTheme.typography.titleMedium)
                        Text("Add stocks or funds and enter their prices every two weeks.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        Row {
                            StatTile("Estimated value", if (p.pricedCount > 0) Money.format(p.valuePaise) else "No prices yet", Modifier.weight(1f),
                                sub = p.oldestValuation?.let { "Prices as of ${Dates.formatDayShort(it)} or later" })
                            if (p.pricedCount > 0) StatTile("Gain / loss", (if (p.gainPaise >= 0) "+" else "") + Money.format(p.gainPaise), Modifier.weight(1f),
                                valueColor = if (p.gainPaise >= 0) Finance.colors.income else Finance.colors.expense)
                        }
                        if (p.staleCount > 0 || p.unpricedCount > 0) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                listOfNotNull(p.staleCount.takeIf { it > 0 }?.let { "$it with prices older than 14 days" }, p.unpricedCount.takeIf { it > 0 }?.let { "$it never priced (not counted)" }).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall, color = Finance.colors.warning,
                            )
                        }
                    }
                }
            }
            if (s.reminders.isNotEmpty()) {
                item { SectionHeader("Upcoming reminders") }
                item {
                    SectionCard {
                        s.reminders.forEach { (title, at) ->
                            Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.NotificationsActive, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.width(10.dp))
                                Text(title, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                                Text(Dates.relativeDay(at.toLocalDate()) + ", " + at.toLocalTime().let { String.format("%d:%02d %s", if (it.hour % 12 == 0) 12 else it.hour % 12, it.minute, if (it.hour < 12) "AM" else "PM") },
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
            item { SectionHeader("Recent activity", "See all") { open(Routes.TRANSACTIONS) } }
            if (s.loaded && s.recent.isEmpty()) item {
                SectionCard {
                    EmptyState(Icons.Filled.Sms, "No transactions yet", "Turn on SMS capture in Settings, import past bank messages, or add one by hand.", "Add a transaction") { open(Routes.ADD_TXN) }
                }
            } else item {
                SectionCard(padding = 6.dp) { s.recent.forEach { r -> TransactionRow(r) { open(Routes.txn(r.txn.id)) } } }
            }
        }
    }
}

@Composable
private fun HeroCard(s: HomeState) {
    // The hero keeps the brand evergreen in both themes: it is an object, not a page surface.
    Surface(shape = RoundedCornerShape(28.dp), color = Color.Transparent, modifier = Modifier.fillMaxWidth()) {
        Box(Modifier.clip(RoundedCornerShape(28.dp)).background(Brush.linearGradient(listOf(Color(0xFF0E4D45), Color(0xFF173F3A)))).padding(22.dp)) {
            Column {
                Text("Spent · ${s.period.label.lowercase()}", style = MaterialTheme.typography.labelLarge, color = Color.White.copy(alpha = 0.75f))
                Text(Money.format(s.summary.expensePaise), style = MaterialTheme.typography.displaySmall, color = Color.White)
                if (s.summary.refundsPaise > 0) Text("after ${Money.format(s.summary.refundsPaise)} in refunds", style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.7f))
                Spacer(Modifier.height(18.dp))
                Row {
                    HeroStat("Income", Money.format(s.summary.incomePaise), Modifier.weight(1f))
                    HeroStat("Saved", Money.format(s.summary.netPaise), Modifier.weight(1f))
                    HeroStat("Savings rate", s.summary.savingsRateBps?.let { "${it / 100}%" } ?: "—", Modifier.weight(0.8f))
                }
            }
        }
    }
}

@Composable
private fun HeroStat(label: String, value: String, modifier: Modifier) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.7f))
        Text(value, style = MaterialTheme.typography.titleSmall.merge(AmountStyle), color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private fun greeting(): String = when (java.time.LocalTime.now().hour) {
    in 5..11 -> "Good morning"
    in 12..16 -> "Good afternoon"
    else -> "Good evening"
}
