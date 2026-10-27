package com.finora.app.domain.loan

import com.finora.app.domain.model.CompoundingFrequency
import com.finora.app.domain.model.InterestMethod
import com.finora.app.domain.model.LoanPaymentKind
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.time.LocalDate
import java.time.temporal.ChronoUnit

data class LoanTerms(
    val principalPaise: Long,
    val annualRateBps: Int,
    val method: InterestMethod,
    val compounding: CompoundingFrequency,
    val startDate: LocalDate,
)

data class LoanEvent(val date: LocalDate, val kind: LoanPaymentKind, val amountPaise: Long)

data class AppliedPayment(
    val date: LocalDate,
    val kind: LoanPaymentKind,
    val amountPaise: Long,
    val toInterestPaise: Long,
    val toPrincipalPaise: Long,
    val principalAfterPaise: Long,
)

data class LoanState(
    val asOf: LocalDate,
    /** Principal lent/borrowed in total, including additional disbursements. */
    val totalPrincipalPaise: Long,
    val principalOutstandingPaise: Long,
    /** Interest accrued and not yet paid, as of [asOf]. */
    val interestOutstandingPaise: Long,
    val interestPaidPaise: Long,
    val principalRepaidPaise: Long,
    val waivedPaise: Long,
    val payments: List<AppliedPayment>,
    /** Money paid beyond everything owed — shown, never silently absorbed. */
    val overpaidPaise: Long,
) {
    val totalOutstandingPaise: Long get() = principalOutstandingPaise + interestOutstandingPaise
    val isSettled: Boolean get() = principalOutstandingPaise == 0L && interestOutstandingPaise == 0L
}

/**
 * Principal and interest are tracked as two separate balances throughout.
 *
 * Assumptions, shown to the user on the loan screen:
 *  - Interest accrues daily on an actual/365 basis.
 *  - SIMPLE: interest accrues on outstanding principal only and never earns interest itself.
 *  - COMPOUND: unpaid interest is added to the interest-bearing balance at the end of each
 *    compounding period (monthly/quarterly/half-yearly/yearly, counted from the start date).
 *  - A repayment pays accrued interest first, then principal.
 *  - Amounts are kept exact internally and rounded to the paisa only when shown.
 */
object LoanCalculator {
    private val MC = MathContext(34)
    private val DAYS_IN_YEAR = BigDecimal(365)

    fun state(terms: LoanTerms, events: List<LoanEvent>, asOf: LocalDate): LoanState {
        var principal = BigDecimal.valueOf(terms.principalPaise)
        var totalPrincipal = terms.principalPaise
        // Interest that has been capitalised (compound) and so itself bears interest.
        var capitalised = BigDecimal.ZERO
        // Interest accrued since the last capitalisation, not yet bearing interest.
        var accrued = BigDecimal.ZERO
        var interestPaid = 0L
        var principalRepaid = 0L
        var waived = 0L
        var overpaid = 0L
        val applied = mutableListOf<AppliedPayment>()

        val dailyRate = BigDecimal(terms.annualRateBps).divide(BigDecimal(10_000), MC).divide(DAYS_IN_YEAR, MC)
        var cursor = terms.startDate
        val boundaries = if (terms.method == InterestMethod.COMPOUND) compoundingBoundaries(terms, asOf) else emptyList()
        var nextBoundary = 0

        fun accrueTo(date: LocalDate) {
            if (terms.method == InterestMethod.NONE || !date.isAfter(cursor)) {
                if (date.isAfter(cursor)) cursor = date
                return
            }
            while (nextBoundary < boundaries.size && !boundaries[nextBoundary].isAfter(date)) {
                val b = boundaries[nextBoundary]
                if (b.isAfter(cursor)) {
                    val days = ChronoUnit.DAYS.between(cursor, b)
                    accrued += (principal + capitalised).multiply(dailyRate, MC).multiply(BigDecimal(days), MC)
                    cursor = b
                }
                capitalised += accrued
                accrued = BigDecimal.ZERO
                nextBoundary++
            }
            val days = ChronoUnit.DAYS.between(cursor, date)
            if (days > 0) {
                val base = if (terms.method == InterestMethod.COMPOUND) principal + capitalised else principal
                accrued += base.multiply(dailyRate, MC).multiply(BigDecimal(days), MC)
            }
            cursor = date
        }

        for (e in events.filter { !it.date.isAfter(asOf) }.sortedBy { it.date }) {
            accrueTo(e.date)
            when (e.kind) {
                LoanPaymentKind.ADDITIONAL_DISBURSEMENT -> {
                    principal += BigDecimal.valueOf(e.amountPaise)
                    totalPrincipal += e.amountPaise
                    applied += AppliedPayment(e.date, e.kind, e.amountPaise, 0, 0, round(principal))
                }
                LoanPaymentKind.REPAYMENT, LoanPaymentKind.INTEREST_ONLY, LoanPaymentKind.WAIVER -> {
                    var remaining = BigDecimal.valueOf(e.amountPaise)
                    val interestDue = capitalised + accrued
                    val toInterest = remaining.min(interestDue)
                    // Settle capitalised interest first, then fresh accrual.
                    val fromCap = toInterest.min(capitalised)
                    capitalised -= fromCap
                    accrued -= (toInterest - fromCap)
                    remaining -= toInterest
                    var toPrincipal = BigDecimal.ZERO
                    if (e.kind != LoanPaymentKind.INTEREST_ONLY) {
                        toPrincipal = remaining.min(principal)
                        principal -= toPrincipal
                        remaining -= toPrincipal
                    }
                    val interestPart = round(toInterest)
                    val principalPart = e.amountPaise - interestPart - round(remaining)
                    if (e.kind == LoanPaymentKind.WAIVER) waived += e.amountPaise - round(remaining)
                    else {
                        interestPaid += interestPart
                        principalRepaid += principalPart
                    }
                    if (remaining.signum() > 0) overpaid += round(remaining)
                    applied += AppliedPayment(e.date, e.kind, e.amountPaise, interestPart, principalPart, round(principal))
                }
            }
        }
        accrueTo(asOf)

        return LoanState(
            asOf = asOf,
            totalPrincipalPaise = totalPrincipal,
            principalOutstandingPaise = round(principal),
            interestOutstandingPaise = round(capitalised + accrued),
            interestPaidPaise = interestPaid,
            principalRepaidPaise = principalRepaid,
            waivedPaise = waived,
            payments = applied,
            overpaidPaise = overpaid,
        )
    }

    private fun compoundingBoundaries(terms: LoanTerms, until: LocalDate): List<LocalDate> {
        val months = 12 / terms.compounding.periodsPerYear
        val out = mutableListOf<LocalDate>()
        var i = 1L
        while (true) {
            val b = terms.startDate.plusMonths(months * i)
            if (b.isAfter(until)) break
            out += b
            i++
        }
        return out
    }

    private fun round(v: BigDecimal): Long = v.setScale(0, RoundingMode.HALF_EVEN).toLong()

    // ---- EMI (reducing-balance) schedule for formal loans ----

    data class EmiRow(val number: Int, val dueDate: LocalDate, val emiPaise: Long, val interestPaise: Long, val principalPaise: Long, val balanceAfterPaise: Long)

    /** Standard EMI = P·r·(1+r)^n / ((1+r)^n − 1), with r the monthly rate. */
    fun emi(principalPaise: Long, annualRateBps: Int, months: Int): Long {
        require(months > 0) { "Tenure must be at least one month" }
        if (annualRateBps == 0) return BigDecimal.valueOf(principalPaise).divide(BigDecimal(months), 0, RoundingMode.CEILING).toLong()
        val r = BigDecimal(annualRateBps).divide(BigDecimal(10_000 * 12), MC)
        val factor = BigDecimal.ONE.add(r).pow(months, MC)
        val emi = BigDecimal.valueOf(principalPaise).multiply(r, MC).multiply(factor, MC).divide(factor.subtract(BigDecimal.ONE), MC)
        return emi.setScale(0, RoundingMode.HALF_UP).toLong()
    }

    /** The last row absorbs rounding so the balance ends at exactly zero. */
    fun schedule(principalPaise: Long, annualRateBps: Int, months: Int, firstDue: LocalDate, emiOverride: Long? = null): List<EmiRow> {
        val emi = emiOverride ?: emi(principalPaise, annualRateBps, months)
        val r = BigDecimal(annualRateBps).divide(BigDecimal(10_000 * 12), MC)
        var balance = principalPaise
        val rows = mutableListOf<EmiRow>()
        for (n in 1..months) {
            val interest = BigDecimal.valueOf(balance).multiply(r, MC).setScale(0, RoundingMode.HALF_UP).toLong()
            var principalPart = emi - interest
            var payment = emi
            if (n == months || principalPart >= balance) {
                principalPart = balance
                payment = principalPart + interest
            }
            balance -= principalPart
            rows += EmiRow(n, firstDue.plusMonths((n - 1).toLong()), payment, interest, principalPart, balance)
            if (balance <= 0) break
        }
        return rows
    }
}
