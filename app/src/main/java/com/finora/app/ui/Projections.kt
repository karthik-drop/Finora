package com.finora.app.ui

import com.finora.app.data.db.AccountEntity
import com.finora.app.data.db.InvestmentEntity
import com.finora.app.data.db.InvestmentTxnEntity
import com.finora.app.data.db.InvestmentValuationEntity
import com.finora.app.data.db.LoanEntity
import com.finora.app.data.db.LoanPaymentEntity
import com.finora.app.data.db.TransactionEntity
import com.finora.app.data.repo.domain
import com.finora.app.data.repo.event
import com.finora.app.data.repo.terms
import com.finora.app.domain.invest.Holding
import com.finora.app.domain.invest.PortfolioCalculator
import com.finora.app.domain.invest.PortfolioError
import com.finora.app.domain.loan.LoanCalculator
import com.finora.app.domain.loan.LoanState
import com.finora.app.domain.model.LoanKind
import com.finora.app.domain.model.LoanStatus
import java.time.LocalDate

/** Read-side shapes shared by several screens, computed from rows already loaded. */
data class HoldingRow(val investment: InvestmentEntity, val holding: Holding, val errors: List<PortfolioError>)

fun buildHoldings(
    investments: List<InvestmentEntity>, txns: List<InvestmentTxnEntity>, valuations: List<InvestmentValuationEntity>, today: LocalDate, staleDays: Int,
): List<HoldingRow> {
    val t = txns.groupBy { it.investmentId }
    val v = valuations.groupBy { it.investmentId }
    return investments.map { inv ->
        val (h, e) = PortfolioCalculator.holding(t[inv.id].orEmpty().map { it.domain() }, v[inv.id].orEmpty().map { it.domain() }, today, staleDays.toLong())
        HoldingRow(inv, h, e)
    }
}

data class PortfolioTotals(val valuePaise: Long, val investedPaise: Long, val pricedCount: Int, val unpricedCount: Int, val staleCount: Int, val oldestValuation: LocalDate?) {
    val gainPaise: Long get() = valuePaise - investedPaise
}

/** Totals over PRICED holdings only; unpriced ones are counted, never valued at zero. */
fun totals(rows: List<HoldingRow>): PortfolioTotals {
    val held = rows.filter { it.holding.unitsE4 > 0 }
    val priced = held.filter { it.holding.currentValuePaise != null }
    return PortfolioTotals(
        valuePaise = priced.sumOf { it.holding.currentValuePaise!! },
        investedPaise = priced.sumOf { it.holding.investedPaise },
        pricedCount = priced.size,
        unpricedCount = held.size - priced.size,
        staleCount = held.count { it.holding.isStale },
        oldestValuation = priced.mapNotNull { it.holding.latestValuation?.date }.minOrNull(),
    )
}

data class LoanRow(val loan: LoanEntity, val state: LoanState, val payments: List<LoanPaymentEntity>, val dueDate: LocalDate?, val overdue: Boolean)

fun buildLoans(loans: List<LoanEntity>, payments: List<LoanPaymentEntity>, today: LocalDate): List<LoanRow> {
    val byLoan = payments.groupBy { it.loanId }
    return loans.map { l ->
        val p = byLoan[l.id].orEmpty()
        val state = LoanCalculator.state(l.terms(), p.map { it.event() }, today)
        val due = nextDue(l, p, today)
        LoanRow(l, state, p, due, l.status == LoanStatus.ACTIVE && due != null && due.isBefore(today) && state.totalOutstandingPaise > 0)
    }
}

/** For an EMI loan: the next unpaid instalment date (by count of repayments); else the loan's due date. */
fun nextDue(l: LoanEntity, payments: List<LoanPaymentEntity>, today: LocalDate): LocalDate? {
    if (l.status == LoanStatus.CLOSED) return null
    val tenure = l.tenureMonths
    if (l.kind == LoanKind.FORMAL_LOAN && tenure != null && tenure > 0) {
        val paid = payments.count { it.kind == com.finora.app.domain.model.LoanPaymentKind.REPAYMENT }
        if (paid >= tenure) return null
        return LocalDate.ofEpochDay(l.startDate).plusMonths((paid + 1).toLong())
    }
    return l.dueDate?.let(LocalDate::ofEpochDay)
}

data class LendingTotals(val lentOutstanding: Long, val borrowedOutstanding: Long)

fun lendingTotals(rows: List<LoanRow>): LendingTotals = LendingTotals(
    rows.filter { it.loan.kind == LoanKind.LENT && it.loan.status == LoanStatus.ACTIVE }.sumOf { it.state.totalOutstandingPaise },
    rows.filter { it.loan.kind != LoanKind.LENT && it.loan.status == LoanStatus.ACTIVE }.sumOf { it.state.totalOutstandingPaise },
)

data class AccountBalance(val account: AccountEntity, val balancePaise: Long, val asOf: Long, val fromSms: Boolean)

/** The most recent KNOWN balance per account: the latest SMS-reported or manually typed one. Unknown stays absent. */
fun latestBalances(accounts: List<AccountEntity>, withBalances: List<TransactionEntity>): List<AccountBalance> = accounts.mapNotNull { a ->
    val sms = withBalances.firstOrNull { it.accountId == a.id }?.let { AccountBalance(a, it.balanceAfterPaise!!, it.occurredAt, true) }
    val manual = if (a.manualBalancePaise != null && a.manualBalanceAt != null) AccountBalance(a, a.manualBalancePaise, a.manualBalanceAt, false) else null
    listOfNotNull(sms, manual).maxByOrNull { it.asOf }
}
