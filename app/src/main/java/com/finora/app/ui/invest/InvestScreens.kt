package com.finora.app.ui.invest

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.EditCalendar
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
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
import com.finora.app.core.Decimal4
import com.finora.app.core.Money
import com.finora.app.data.db.InvestmentEntity
import com.finora.app.data.db.InvestmentTxnEntity
import com.finora.app.data.db.SipPlanEntity
import com.finora.app.data.repo.domain
import com.finora.app.domain.invest.PortfolioCalculator
import com.finora.app.domain.model.InvestmentTxnKind
import com.finora.app.domain.model.InvestmentType
import com.finora.app.ui.HoldingRow
import com.finora.app.ui.Routes
import com.finora.app.ui.appViewModel
import com.finora.app.ui.buildHoldings
import com.finora.app.ui.components.ChartSlice
import com.finora.app.ui.components.DateField
import com.finora.app.ui.components.DecimalField
import com.finora.app.ui.components.DonutChart
import com.finora.app.ui.components.EmptyState
import com.finora.app.ui.components.ErrorNote
import com.finora.app.ui.components.FinoraTopBar
import com.finora.app.ui.components.InfoNote
import com.finora.app.ui.components.KeyValueRow
import com.finora.app.ui.components.LineChart
import com.finora.app.ui.components.MoneyField
import com.finora.app.ui.components.Pill
import com.finora.app.ui.components.SectionCard
import com.finora.app.ui.components.SectionHeader
import com.finora.app.ui.components.SelectField
import com.finora.app.ui.components.StatTile
import com.finora.app.ui.theme.AmountStyle
import com.finora.app.ui.theme.Finance
import com.finora.app.ui.totals
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.time.LocalDate

class InvestmentsViewModel(c: AppContainer) : ViewModel() {
    val data = combine(c.db.investments().observeAll(), c.db.investments().observeAllTxns(), c.db.investments().observeAllValuations(), c.settings.settings) { i, t, v, s ->
        val rows = buildHoldings(i, t, v, Dates.today(), s.staleValuationDays)
        val history = PortfolioCalculator.history(i.associate { inv -> inv.id to (t.filter { it.investmentId == inv.id }.map { it.domain() } to v.filter { it.investmentId == inv.id }.map { it.domain() }) })
        Triple(rows, history, s.staleValuationDays)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}

fun pct(bps: Long?): String = bps?.let { (if (it >= 0) "+" else "") + BigDecimal(it).movePointLeft(2).setScale(2).toPlainString() + "%" } ?: "—"

@Composable
fun InvestmentsScreen(open: (String) -> Unit) {
    val vm = appViewModel { InvestmentsViewModel(it) }
    val data by vm.data.collectAsStateWithLifecycle()
    val chart = Finance.colors.chart
    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = { FinoraTopBar("Investments", actions = { TextButton(onClick = { open(Routes.PORTFOLIO_UPDATE) }) { Icon(Icons.Filled.EditCalendar, null); Spacer(Modifier.width(6.dp)); Text("Update prices") } }) },
        floatingActionButton = { ExtendedFloatingActionButton(onClick = { open(Routes.INVEST_NEW) }, icon = { Icon(Icons.Filled.Add, null) }, text = { Text("Add") }) },
    ) { pad ->
        val (rows, history, staleDays) = data ?: return@Scaffold
        val t = totals(rows)
        LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(20.dp, 8.dp, 20.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (rows.isEmpty()) item { EmptyState(Icons.Filled.ShowChart, "No investments yet", "Add stocks, mutual funds, ETFs or anything else you track. You enter prices and NAVs yourself — Finora never fetches them.", "Add an investment") { open(Routes.INVEST_NEW) } }
            else {
                item {
                    SectionCard {
                        Text("Estimated value", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(if (t.pricedCount > 0) Money.format(t.valuePaise) else "No prices entered", style = MaterialTheme.typography.displaySmall.merge(AmountStyle))
                        Text("From prices you entered" + (t.oldestValuation?.let { ", the oldest dated ${Dates.formatDay(it)}" } ?: "") + ". Not live market prices.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (t.pricedCount > 0) {
                            Spacer(Modifier.height(12.dp))
                            Row {
                                StatTile("Invested (priced)", Money.format(t.investedPaise), Modifier.weight(1f))
                                StatTile("Unrealised", (if (t.gainPaise >= 0) "+" else "") + Money.format(t.gainPaise), Modifier.weight(1f), valueColor = if (t.gainPaise >= 0) Finance.colors.income else Finance.colors.expense,
                                    sub = if (t.investedPaise > 0) pct(t.gainPaise * 10_000 / t.investedPaise) else null)
                            }
                        }
                        if (t.unpricedCount > 0) Text("${t.unpricedCount} holding${if (t.unpricedCount == 1) " has" else "s have"} no price yet and ${if (t.unpricedCount == 1) "is" else "are"} not included.", style = MaterialTheme.typography.bodySmall, color = Finance.colors.warning)
                        if (t.staleCount > 0) Text("${t.staleCount} price${if (t.staleCount == 1) " is" else "s are"} older than $staleDays days.", style = MaterialTheme.typography.bodySmall, color = Finance.colors.warning)
                    }
                }
                if (history.size >= 2) item {
                    SectionCard {
                        Text("Value at each update", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(8.dp))
                        LineChart(history.map { Dates.formatDayShort(it.date) to it.valuePaise }, partial = history.indices.filter { history[it].unpricedHoldings > 0 }.toSet())
                        if (history.any { it.unpricedHoldings > 0 }) Text("Hollow points had holdings with no price on that date.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                val alloc = PortfolioCalculator.allocation(rows.mapNotNull { r -> r.holding.currentValuePaise?.let { r.investment.type to it } })
                if (alloc.size > 1) item {
                    SectionCard {
                        Text("Allocation", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(8.dp))
                        DonutChart(alloc.mapIndexed { i, a -> ChartSlice(a.type.label, a.valuePaise, chart[i % chart.size]) }, "Priced", Money.formatCompact(t.valuePaise), diameter = 120.dp)
                    }
                }
                item { SectionHeader("Holdings") }
                items(rows, key = { it.investment.id }) { r -> HoldingCard(r) { open(Routes.investment(r.investment.id)) } }
            }
        }
    }
}

@Composable
private fun HoldingCard(r: HoldingRow, onClick: () -> Unit) {
    val h = r.holding
    SectionCard(onClick = onClick, padding = 16.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(r.investment.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(listOfNotNull(r.investment.type.label, r.investment.symbol, "${Decimal4.format(h.unitsE4)} units").joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                Text(h.latestValuation?.let { "₹${Decimal4.format(it.priceE4)} on ${Dates.formatDayShort(it.date)}" } ?: "No price entered yet", style = MaterialTheme.typography.bodySmall,
                    color = if (h.isStale) Finance.colors.warning else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(h.currentValuePaise?.let(Money::format) ?: "—", style = MaterialTheme.typography.titleSmall.merge(AmountStyle))
                h.unrealizedGainPaise?.let { g -> Text(pct(h.unrealizedGainBps), style = MaterialTheme.typography.labelMedium.merge(AmountStyle), color = if (g >= 0) Finance.colors.income else Finance.colors.expense) }
                if (h.isStale && h.unitsE4 > 0) Pill("Stale", MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer)
            }
        }
    }
}

class InvestmentEditViewModel(private val c: AppContainer) : ViewModel() {
    suspend fun create(name: String, symbol: String?, type: InvestmentType, firstBuy: InvestmentTxnEntity?, price: Pair<Long, LocalDate>?, notes: String?): Long {
        val id = c.investments.create(InvestmentEntity(name = name, symbol = symbol, type = type, notes = notes, createdAt = 0))
        firstBuy?.let { c.investments.addTxn(it.copy(investmentId = id)) }
        price?.let { c.investments.saveValuations(listOf(Triple(id, it.first, it.second))) }
        return id
    }
}

@Composable
fun InvestmentEditScreen(back: () -> Unit, saved: (Long) -> Unit) {
    val vm = appViewModel { InvestmentEditViewModel(it) }
    val scope = rememberCoroutineScope()
    var name by rememberSaveable { mutableStateOf("") }
    var symbol by rememberSaveable { mutableStateOf("") }
    var type by rememberSaveable { mutableStateOf(InvestmentType.MUTUAL_FUND) }
    var units by rememberSaveable { mutableStateOf("") }
    var price by rememberSaveable { mutableStateOf("") }
    var amount by rememberSaveable { mutableStateOf("") }
    var costs by rememberSaveable { mutableStateOf("") }
    var date by rememberSaveable { mutableStateOf(Dates.today().toEpochDay()) }
    var notes by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    Scaffold(contentWindowInsets = WindowInsets(0), topBar = { FinoraTopBar("Add investment", back) }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SelectField("Type", InvestmentType.entries.toList(), type, { it.label }, { type = it })
            OutlinedTextField(name, { name = it }, label = { Text(if (type == InvestmentType.MUTUAL_FUND) "Scheme name" else "Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(symbol, { symbol = it.uppercase() }, label = { Text(if (type == InvestmentType.MUTUAL_FUND) "Folio / code (optional)" else "Symbol, e.g. INFY (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            SectionHeader("First purchase (optional)")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DecimalField("Units", units, { units = it }, Modifier.weight(1f))
                DecimalField(if (type == InvestmentType.MUTUAL_FUND) "NAV" else "Price", price, { price = it }, Modifier.weight(1f), prefix = "₹")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MoneyField("Amount paid", amount, { amount = it }, Modifier.weight(1f), supporting = "Blank = units × price")
                MoneyField("Charges", costs, { costs = it }, Modifier.weight(1f))
            }
            DateField("Purchase date", LocalDate.ofEpochDay(date), { date = it.toEpochDay() }, maxDate = Dates.today())
            OutlinedTextField(notes, { notes = it }, label = { Text("Notes") }, minLines = 2, modifier = Modifier.fillMaxWidth())
            InfoNote("The purchase price is also saved as the first valuation. Update prices from Portfolio Update every two weeks.")
            ErrorNote(error)
            Button(modifier = Modifier.fillMaxWidth().height(52.dp), onClick = {
                val u = Decimal4.parse(units); val p = Decimal4.parse(price)
                val amt = if (amount.isBlank()) (if (u != null && p != null) Decimal4.valuePaise(u, p) else null) else Money.parseToPaise(amount)
                when {
                    name.isBlank() -> error = "Enter a name."
                    units.isNotBlank() && (u == null || u <= 0) -> error = "Units must be above zero."
                    units.isNotBlank() && (p == null || p <= 0) -> error = "Enter the purchase price."
                    else -> scope.launch {
                        val d = LocalDate.ofEpochDay(date)
                        val buy = if (u != null && p != null) InvestmentTxnEntity(investmentId = 0, kind = InvestmentTxnKind.BUY, date = date, unitsE4 = u, priceE4 = p, amountPaise = amt ?: 0, costsPaise = Money.parseToPaise(costs) ?: 0, createdAt = 0) else null
                        saved(vm.create(name.trim(), symbol.ifBlank { null }, type, buy, p?.let { it to d }, notes.ifBlank { null }))
                    }
                }
            }) { Text("Save") }
            Spacer(Modifier.height(24.dp))
        }
    }
}

class InvestmentDetailViewModel(private val c: AppContainer, val id: Long) : ViewModel() {
    val data = combine(c.db.investments().observe(id), c.db.investments().observeAllTxns(), c.db.investments().observeAllValuations(), c.db.investments().observeSips(), c.db.investments().observeInstalments()) { inv, t, v, sips, inst ->
        Detail(inv, t.filter { it.investmentId == id }, v.filter { it.investmentId == id }, sips.filter { it.investmentId == id }, inst)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    data class Detail(val inv: InvestmentEntity?, val txns: List<InvestmentTxnEntity>, val vals: List<com.finora.app.data.db.InvestmentValuationEntity>, val sips: List<SipPlanEntity>, val instalments: List<com.finora.app.data.db.SipInstalmentEntity>)

    fun addTxn(t: InvestmentTxnEntity) = viewModelScope.launch { c.investments.addTxn(t.copy(investmentId = id)) }
    fun deleteTxn(t: InvestmentTxnEntity) = viewModelScope.launch { c.investments.deleteTxn(t) }
    fun deleteValuation(v: com.finora.app.data.db.InvestmentValuationEntity) = viewModelScope.launch { c.investments.deleteValuation(v) }
    fun addSip(amount: Long, day: Int, start: LocalDate) = viewModelScope.launch { c.investments.addSip(SipPlanEntity(investmentId = id, amountPaise = amount, dayOfMonth = day, startDate = start.toEpochDay())) }
    fun stopSip(p: SipPlanEntity) = viewModelScope.launch { c.investments.updateSip(p.copy(active = false, endDate = Dates.today().toEpochDay())) }
    fun markSipPaid(p: SipPlanEntity, due: LocalDate, units: Long, nav: Long, amount: Long) = viewModelScope.launch { c.investments.markSipPaid(p, due, units, nav, amount) }
    fun archive(done: () -> Unit) = viewModelScope.launch { data.value?.inv?.let { c.investments.update(it.copy(archived = true)) }; done() }
}

@Composable
fun InvestmentDetailScreen(id: Long, back: () -> Unit) {
    val vm = appViewModel("inv$id") { InvestmentDetailViewModel(it, id) }
    val d by vm.data.collectAsStateWithLifecycle()
    var addKind by remember { mutableStateOf<InvestmentTxnKind?>(null) }
    var sipDialog by remember { mutableStateOf(false) }
    var paySip by remember { mutableStateOf<Pair<SipPlanEntity, LocalDate>?>(null) }
    var confirmArchive by remember { mutableStateOf(false) }

    Scaffold(contentWindowInsets = WindowInsets(0), topBar = { FinoraTopBar(d?.inv?.name ?: "Investment", back) { IconButton(onClick = { confirmArchive = true }) { Icon(Icons.Filled.Delete, "Archive") } } }) { pad ->
        val detail = d ?: return@Scaffold
        val inv = detail.inv ?: return@Scaffold
        val (h, errors) = PortfolioCalculator.holding(detail.txns.map { it.domain() }, detail.vals.map { it.domain() }, Dates.today())
        LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(20.dp, 8.dp, 20.dp, 40.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                SectionCard {
                    Text("Estimated value", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(h.currentValuePaise?.let(Money::format) ?: "No price yet", style = MaterialTheme.typography.displaySmall.merge(AmountStyle))
                    Text(h.latestValuation?.let { "${Decimal4.format(h.unitsE4)} units × ₹${Decimal4.format(it.priceE4)}, the price you entered for ${Dates.formatDay(it.date)}" + if (h.isStale) " (stale)" else "" } ?: "Enter a price in Portfolio Update to value this holding.",
                        style = MaterialTheme.typography.bodySmall, color = if (h.isStale) Finance.colors.warning else MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(12.dp))
                    Row {
                        StatTile("Invested (cost)", Money.format(h.investedPaise), Modifier.weight(1f))
                        StatTile("Unrealised", h.unrealizedGainPaise?.let { (if (it >= 0) "+" else "") + Money.format(it) } ?: "—", Modifier.weight(1f), sub = pct(h.unrealizedGainBps),
                            valueColor = when { h.unrealizedGainPaise == null -> MaterialTheme.colorScheme.onSurface; h.unrealizedGainPaise >= 0 -> Finance.colors.income; else -> Finance.colors.expense })
                    }
                    Spacer(Modifier.height(10.dp))
                    Row {
                        StatTile("Realised", Money.format(h.realizedGainPaise), Modifier.weight(1f))
                        StatTile("Dividends", Money.format(h.dividendsPaise), Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(10.dp))
                    Row {
                        StatTile("Put in", Money.format(h.contributedPaise), Modifier.weight(1f))
                        StatTile("Taken out", Money.format(h.withdrawnPaise), Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("Average-cost method. Realised figures are a tracking aid, not a capital-gains tax computation (which uses FIFO).", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (errors.isNotEmpty()) item { InfoNote("Some entries do not add up (e.g. more units sold than held). Those entries were skipped in these figures — check the history below.") }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { addKind = InvestmentTxnKind.BUY }, modifier = Modifier.weight(1f)) { Text("Buy") }
                    OutlinedButton(onClick = { addKind = InvestmentTxnKind.SELL }, modifier = Modifier.weight(1f)) { Text("Sell") }
                    OutlinedButton(onClick = { addKind = InvestmentTxnKind.DIVIDEND }, modifier = Modifier.weight(1f)) { Text("Dividend") }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { addKind = InvestmentTxnKind.SPLIT }, modifier = Modifier.weight(1f)) { Text("Split / bonus") }
                    OutlinedButton(onClick = { sipDialog = true }, modifier = Modifier.weight(1f)) { Text("Add SIP") }
                }
            }
            if (detail.sips.isNotEmpty()) {
                item { SectionHeader("SIPs") }
                items(detail.sips) { sip ->
                    val due = PortfolioCalculator.sipDueDates(LocalDate.ofEpochDay(sip.startDate), sip.dayOfMonth, sip.endDate?.let(LocalDate::ofEpochDay), Dates.today().minusMonths(6), Dates.today().plusMonths(1))
                    val paid = detail.instalments.filter { it.sipPlanId == sip.id && it.paid }.map { it.dueDate }.toSet()
                    SectionCard(padding = 14.dp) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("${Money.format(sip.amountPaise)} on day ${sip.dayOfMonth}" + if (!sip.active) " · stopped" else "", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                            if (sip.active) TextButton(onClick = { vm.stopSip(sip) }) { Text("Stop") }
                        }
                        Text("Scheduled ${due.size} · paid ${due.count { it.toEpochDay() in paid }} (last 6 months and next month). Planned instalments are separate from what you actually recorded.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        due.filter { it.toEpochDay() !in paid && !it.isAfter(Dates.today()) }.takeLast(3).forEach { dd ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Due ${Dates.formatDay(dd)} — not recorded", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                                TextButton(onClick = { paySip = sip to dd }) { Text("Mark paid") }
                            }
                        }
                    }
                }
            }
            item { SectionHeader("Prices you entered") }
            if (detail.vals.isEmpty()) item { Text("None yet.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            else item {
                SectionCard {
                    if (detail.vals.size >= 2) { LineChart(detail.vals.sortedBy { it.valuationDate }.map { Dates.formatDayShort(LocalDate.ofEpochDay(it.valuationDate)) to it.priceE4 / 100 }, height = 120.dp); Spacer(Modifier.height(8.dp)) }
                    detail.vals.sortedByDescending { it.valuationDate }.forEach { v ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(Dates.formatDay(LocalDate.ofEpochDay(v.valuationDate)), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            Text("₹${Decimal4.format(v.priceE4)}", style = MaterialTheme.typography.bodyMedium.merge(AmountStyle))
                            IconButton(onClick = { vm.deleteValuation(v) }) { Icon(Icons.Filled.Delete, "Delete valuation") }
                        }
                    }
                }
            }
            item { SectionHeader("History") }
            items(detail.txns.sortedByDescending { it.date }) { t ->
                SectionCard(padding = 12.dp) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("${t.kind.label} · ${Dates.formatDay(LocalDate.ofEpochDay(t.date))}", style = MaterialTheme.typography.titleSmall)
                            Text(when (t.kind) {
                                InvestmentTxnKind.SPLIT -> "${t.splitTo} for ${t.splitFrom}"
                                InvestmentTxnKind.DIVIDEND -> "Received"
                                else -> "${Decimal4.format(t.unitsE4)} units @ ₹${Decimal4.format(t.priceE4)}" + if (t.costsPaise > 0) " · charges ${Money.format(t.costsPaise)}" else ""
                            }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (t.amountPaise > 0) Text(Money.format(t.amountPaise), style = MaterialTheme.typography.bodyMedium.merge(AmountStyle))
                        IconButton(onClick = { vm.deleteTxn(t) }) { Icon(Icons.Filled.Delete, "Delete entry") }
                    }
                }
            }
        }
    }

    addKind?.let { k -> InvestmentTxnDialog(k, onDismiss = { addKind = null }) { vm.addTxn(it); addKind = null } }
    if (sipDialog) {
        var amount by remember { mutableStateOf("") }
        var day by remember { mutableStateOf("5") }
        var start by remember { mutableStateOf(Dates.today()) }
        AlertDialog(
            onDismissRequest = { sipDialog = false }, title = { Text("Planned SIP") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    MoneyField("Monthly amount", amount, { amount = it })
                    OutlinedTextField(day, { if (it.length <= 2 && it.all(Char::isDigit)) day = it }, label = { Text("Day of month") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true)
                    DateField("Starts", start, { start = it })
                    Text("Finora only tracks the plan; it never invests on your behalf.", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = { TextButton(onClick = {
                val a = Money.parseToPaise(amount); val dd = day.toIntOrNull()
                if (a != null && a > 0 && dd != null && dd in 1..31) { vm.addSip(a, dd, start); sipDialog = false }
            }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { sipDialog = false }) { Text("Cancel") } },
        )
    }
    paySip?.let { (sip, due) ->
        var units by remember { mutableStateOf("") }
        var nav by remember { mutableStateOf("") }
        var amount by remember { mutableStateOf(Money.toInput(sip.amountPaise)) }
        AlertDialog(
            onDismissRequest = { paySip = null }, title = { Text("SIP for ${Dates.formatDay(due)}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    MoneyField("Amount", amount, { amount = it })
                    DecimalField("Units allotted", units, { units = it })
                    DecimalField("NAV", nav, { nav = it }, prefix = "₹")
                }
            },
            confirmButton = { TextButton(onClick = {
                val u = Decimal4.parse(units); val n = Decimal4.parse(nav); val a = Money.parseToPaise(amount)
                if (u != null && u > 0 && n != null && n > 0 && a != null) { vm.markSipPaid(sip, due, u, n, a); paySip = null }
            }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { paySip = null }) { Text("Cancel") } },
        )
    }
    if (confirmArchive) AlertDialog(
        onDismissRequest = { confirmArchive = false }, title = { Text("Archive this investment?") },
        text = { Text("It disappears from the portfolio. Its history is kept in the database and in backups.") },
        confirmButton = { TextButton(onClick = { confirmArchive = false; vm.archive(back) }) { Text("Archive") } },
        dismissButton = { TextButton(onClick = { confirmArchive = false }) { Text("Cancel") } },
    )
}

@Composable
private fun InvestmentTxnDialog(initial: InvestmentTxnKind, onDismiss: () -> Unit, onSave: (InvestmentTxnEntity) -> Unit) {
    var kind by remember { mutableStateOf(initial) }
    var units by remember { mutableStateOf("") }
    var price by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    var costs by remember { mutableStateOf("") }
    var splitTo by remember { mutableStateOf("") }
    var splitFrom by remember { mutableStateOf("") }
    var date by remember { mutableStateOf(Dates.today()) }
    var err by remember { mutableStateOf<String?>(null) }
    val kinds = when (initial) {
        InvestmentTxnKind.SPLIT -> listOf(InvestmentTxnKind.SPLIT, InvestmentTxnKind.BONUS, InvestmentTxnKind.UNIT_ADJUSTMENT)
        InvestmentTxnKind.SELL -> listOf(InvestmentTxnKind.SELL, InvestmentTxnKind.WITHDRAWAL)
        InvestmentTxnKind.BUY -> listOf(InvestmentTxnKind.BUY, InvestmentTxnKind.SIP)
        else -> listOf(initial)
    }
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text(kind.label) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (kinds.size > 1) SelectField("Kind", kinds, kind, { it.label }, { kind = it })
                when (kind) {
                    InvestmentTxnKind.SPLIT -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(splitTo, { if (it.all(Char::isDigit)) splitTo = it }, label = { Text("New units") }, modifier = Modifier.weight(1f), singleLine = true)
                        OutlinedTextField(splitFrom, { if (it.all(Char::isDigit)) splitFrom = it }, label = { Text("for old") }, modifier = Modifier.weight(1f), singleLine = true)
                    }
                    InvestmentTxnKind.DIVIDEND -> MoneyField("Amount received", amount, { amount = it })
                    InvestmentTxnKind.BONUS, InvestmentTxnKind.UNIT_ADJUSTMENT -> DecimalField(if (kind == InvestmentTxnKind.BONUS) "Bonus units" else "Unit change (negative to reduce)", units, { units = it })
                    else -> {
                        DecimalField("Units", units, { units = it })
                        DecimalField("Price / NAV", price, { price = it }, prefix = "₹")
                        MoneyField(if (kind == InvestmentTxnKind.SELL || kind == InvestmentTxnKind.WITHDRAWAL) "Amount received" else "Amount paid", amount, { amount = it }, supporting = "Blank = units × price")
                        MoneyField("Charges (brokerage, STT, exit load)", costs, { costs = it })
                    }
                }
                DateField("Date", date, { date = it }, maxDate = Dates.today())
                err?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = { TextButton(onClick = {
            val u = Decimal4.parse(units); val p = Decimal4.parse(price)
            val amt = if (amount.isBlank() && u != null && p != null) Decimal4.valuePaise(u, p) else Money.parseToPaise(amount) ?: 0
            val base = InvestmentTxnEntity(investmentId = 0, kind = kind, date = date.toEpochDay(), createdAt = 0)
            val t = when (kind) {
                InvestmentTxnKind.SPLIT -> { val a = splitTo.toIntOrNull(); val b = splitFrom.toIntOrNull(); if (a == null || b == null || a <= 0 || b <= 0) null else base.copy(splitTo = a, splitFrom = b) }
                InvestmentTxnKind.DIVIDEND -> if (amt <= 0) null else base.copy(amountPaise = amt)
                InvestmentTxnKind.BONUS -> if (u == null || u <= 0) null else base.copy(unitsE4 = u)
                InvestmentTxnKind.UNIT_ADJUSTMENT -> if (u == null || u == 0L) null else base.copy(unitsE4 = u)
                else -> if (u == null || u <= 0 || p == null || p <= 0) null else base.copy(unitsE4 = u, priceE4 = p, amountPaise = amt, costsPaise = Money.parseToPaise(costs) ?: 0)
            }
            if (t == null) err = "Check the numbers." else onSave(t)
        }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

class PortfolioUpdateViewModel(private val c: AppContainer) : ViewModel() {
    val rows = combine(c.db.investments().observeAll(), c.db.investments().observeAllTxns(), c.db.investments().observeAllValuations(), c.settings.settings) { i, t, v, s ->
        buildHoldings(i, t, v, Dates.today(), s.staleValuationDays) to v
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    suspend fun save(entries: List<Triple<Long, Long, LocalDate>>): String? = runCatching { c.investments.saveValuations(entries) }.exceptionOrNull()?.message
}

/**
 * The biweekly ritual: every holding with its last price and date, a field for the new one, one
 * Save. Blank fields are skipped — a holding not updated keeps its old, dated price.
 */
@Composable
fun PortfolioUpdateScreen(back: () -> Unit) {
    val vm = appViewModel { PortfolioUpdateViewModel(it) }
    val data by vm.rows.collectAsStateWithLifecycle()
    val entries = remember { mutableStateMapOf<Long, String>() }
    var date by rememberSaveable { mutableStateOf(Dates.today().toEpochDay()) }
    var history by remember { mutableStateOf<Long?>(null) }
    val snack = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    Scaffold(contentWindowInsets = WindowInsets(0), topBar = { FinoraTopBar("Portfolio update", back) }, snackbarHost = { SnackbarHost(snack) }) { pad ->
        val (rows, vals) = data ?: return@Scaffold
        LazyColumn(Modifier.fillMaxSize().padding(pad).imePadding(), contentPadding = PaddingValues(20.dp, 8.dp, 20.dp, 40.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { InfoNote("Enter today's stock prices and fund NAVs from your broker or AMC statement. Leave a field blank to skip it — its last price stays, with its date.") }
            item { DateField("Prices are as of", LocalDate.ofEpochDay(date), { date = it.toEpochDay() }, maxDate = Dates.today()) }
            if (rows.isEmpty()) item { Text("No investments yet.", style = MaterialTheme.typography.bodyMedium) }
            items(rows, key = { it.investment.id }) { r ->
                val h = r.holding
                SectionCard(padding = 14.dp) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(r.investment.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(h.latestValuation?.let { "Last: ₹${Decimal4.format(it.priceE4)} · ${Dates.formatDay(it.date)} (${h.daysSinceValuation} days ago)" } ?: "Never priced",
                                style = MaterialTheme.typography.bodySmall, color = if (h.isStale) Finance.colors.warning else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        TextButton(onClick = { history = r.investment.id }) { Text("History") }
                    }
                    DecimalField(if (r.investment.type == InvestmentType.MUTUAL_FUND) "New NAV" else "New price", entries[r.investment.id] ?: "", { entries[r.investment.id] = it }, prefix = "₹")
                }
            }
            item {
                val filled = entries.filterValues { it.isNotBlank() }
                Button(enabled = filled.isNotEmpty(), modifier = Modifier.fillMaxWidth().height(52.dp), onClick = {
                    val parsed = filled.mapNotNull { (id, v) -> Decimal4.parse(v)?.takeIf { it > 0 }?.let { Triple(id, it, LocalDate.ofEpochDay(date)) } }
                    scope.launch {
                        if (parsed.size != filled.size) { snack.showSnackbar("Some prices are not valid numbers above zero."); return@launch }
                        val err = vm.save(parsed)
                        if (err != null) snack.showSnackbar(err) else { entries.clear(); snack.showSnackbar("Saved ${parsed.size} price${if (parsed.size == 1) "" else "s"}. ${rows.size - parsed.size} skipped.") }
                    }
                }) { Text("Save ${filled.size} update${if (filled.size == 1) "" else "s"}") }
            }
        }
        history?.let { invId ->
            val list = vals.filter { it.investmentId == invId }.sortedByDescending { it.valuationDate }
            AlertDialog(
                onDismissRequest = { history = null }, title = { Text("Previous prices") },
                text = {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        if (list.isEmpty()) Text("No prices entered yet.")
                        list.forEach { v -> KeyValueRow(Dates.formatDay(LocalDate.ofEpochDay(v.valuationDate)), "₹${Decimal4.format(v.priceE4)}") ; HorizontalDivider() }
                    }
                },
                confirmButton = { TextButton(onClick = { history = null }) { Text("Close") } },
            )
        }
    }
}
