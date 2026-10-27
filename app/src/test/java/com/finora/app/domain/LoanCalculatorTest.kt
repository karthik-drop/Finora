package com.finora.app.domain

import com.finora.app.domain.loan.LoanCalculator
import com.finora.app.domain.loan.LoanEvent
import com.finora.app.domain.loan.LoanTerms
import com.finora.app.domain.model.CompoundingFrequency
import com.finora.app.domain.model.InterestMethod
import com.finora.app.domain.model.LoanPaymentKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class LoanCalculatorTest {
    private val start = LocalDate.of(2025, 1, 1)
    private fun terms(p: Long, bps: Int, m: InterestMethod, c: CompoundingFrequency = CompoundingFrequency.MONTHLY) = LoanTerms(p, bps, m, c, start)

    @Test fun noInterestOnlyPrincipalIsOwed() {
        val s = LoanCalculator.state(terms(1_000_00, 0, InterestMethod.NONE), emptyList(), start.plusDays(400))
        assertEquals(1_000_00L, s.totalOutstandingPaise)
        assertEquals(0L, s.interestOutstandingPaise)
    }

    @Test fun simpleInterestAccruesDailyOnPrincipal() {
        // ₹1,00,000 at 12% for 365 days = ₹12,000.
        val s = LoanCalculator.state(terms(1_00_000_00, 1200, InterestMethod.SIMPLE), emptyList(), start.plusDays(365))
        assertEquals(1_00_000_00L, s.principalOutstandingPaise)
        assertEquals(12_000_00L, s.interestOutstandingPaise)
    }

    @Test fun partialRepaymentPaysInterestFirstThenPrincipal() {
        // After 73 days at 10% on ₹73,000: interest = 73000 × 0.10 × 73/365 = ₹1,460.
        val t = terms(73_000_00, 1000, InterestMethod.SIMPLE)
        val pay = LoanEvent(start.plusDays(73), LoanPaymentKind.REPAYMENT, 10_000_00)
        val s = LoanCalculator.state(t, listOf(pay), start.plusDays(73))
        val applied = s.payments.single()
        assertEquals(1_460_00L, applied.toInterestPaise)
        assertEquals(8_540_00L, applied.toPrincipalPaise)
        assertEquals(64_460_00L, s.principalOutstandingPaise)
        assertEquals(0L, s.interestOutstandingPaise)
        assertEquals(1_460_00L, s.interestPaidPaise)
        assertEquals(8_540_00L, s.principalRepaidPaise)
    }

    @Test fun returnOfPrincipalIsNeverCountedAsInterest() {
        val s = LoanCalculator.state(terms(50_000_00, 0, InterestMethod.NONE), listOf(LoanEvent(start.plusDays(10), LoanPaymentKind.REPAYMENT, 20_000_00)), start.plusDays(10))
        assertEquals(0L, s.interestPaidPaise)
        assertEquals(20_000_00L, s.principalRepaidPaise)
        assertEquals(30_000_00L, s.principalOutstandingPaise)
    }

    @Test fun compoundInterestCapitalisesEachPeriod() {
        // Yearly compounding over two years at 10%: 1,00,000 -> 1,21,000 (allowing day-count drift of a few paise).
        val s = LoanCalculator.state(terms(1_00_000_00, 1000, InterestMethod.COMPOUND, CompoundingFrequency.YEARLY), emptyList(), start.plusYears(2))
        val interest = s.interestOutstandingPaise
        assertTrue("interest was $interest", kotlin.math.abs(interest - 21_000_00L) < 100_00) // 2025 has 365 days, 2026 365
        val simple = LoanCalculator.state(terms(1_00_000_00, 1000, InterestMethod.SIMPLE), emptyList(), start.plusYears(2))
        assertTrue(interest > simple.interestOutstandingPaise)
    }

    @Test fun fullRepaymentSettlesAndOverpaymentIsReported() {
        val t = terms(10_000_00, 0, InterestMethod.NONE)
        val s = LoanCalculator.state(t, listOf(LoanEvent(start.plusDays(5), LoanPaymentKind.REPAYMENT, 10_500_00)), start.plusDays(5))
        assertTrue(s.isSettled)
        assertEquals(500_00L, s.overpaidPaise)
        assertEquals(10_000_00L, s.principalRepaidPaise)
    }

    @Test fun additionalDisbursementAddsPrincipal() {
        val s = LoanCalculator.state(terms(10_000_00, 0, InterestMethod.NONE), listOf(LoanEvent(start.plusDays(5), LoanPaymentKind.ADDITIONAL_DISBURSEMENT, 5_000_00)), start.plusDays(6))
        assertEquals(15_000_00L, s.totalPrincipalPaise)
        assertEquals(15_000_00L, s.principalOutstandingPaise)
    }

    @Test fun interestOnlyPaymentLeavesPrincipalUntouched() {
        val t = terms(1_00_000_00, 1200, InterestMethod.SIMPLE)
        val s = LoanCalculator.state(t, listOf(LoanEvent(start.plusDays(365), LoanPaymentKind.INTEREST_ONLY, 15_000_00)), start.plusDays(365))
        assertEquals(1_00_000_00L, s.principalOutstandingPaise)
        assertEquals(12_000_00L, s.interestPaidPaise)
        assertEquals(3_000_00L, s.overpaidPaise)
    }

    @Test fun emiMatchesTheStandardFormula() {
        // ₹1,00,000 at 10% for 12 months: EMI ₹8,791.59.
        assertEquals(8_791_59L, LoanCalculator.emi(1_00_000_00, 1000, 12))
        assertEquals(8_334L, LoanCalculator.emi(1_00_000, 0, 12))
    }

    @Test fun scheduleRepaysExactlyThePrincipal() {
        val rows = LoanCalculator.schedule(1_00_000_00, 1000, 12, LocalDate.of(2025, 2, 1))
        assertEquals(12, rows.size)
        assertEquals(0L, rows.last().balanceAfterPaise)
        assertEquals(1_00_000_00L, rows.sumOf { it.principalPaise })
        assertTrue(rows.first().interestPaise > rows.last().interestPaise)
    }
}
