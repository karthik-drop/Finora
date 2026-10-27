package com.finora.app.domain

import com.finora.app.core.Decimal4
import com.finora.app.data.db.ReminderEntity
import com.finora.app.domain.analytics.Analytics
import com.finora.app.domain.analytics.AnalyticsTxn
import com.finora.app.domain.analytics.Slice
import com.finora.app.domain.csv.Csv
import com.finora.app.domain.csv.TransactionCsv
import com.finora.app.domain.invest.InvTxn
import com.finora.app.domain.invest.PortfolioCalculator
import com.finora.app.domain.invest.PortfolioError
import com.finora.app.domain.invest.Valuation
import com.finora.app.domain.merchant.GeoPoint
import com.finora.app.domain.merchant.PlaceCandidate
import com.finora.app.domain.merchant.PlaceRanking
import com.finora.app.domain.model.Direction
import com.finora.app.domain.model.InvestmentTxnKind
import com.finora.app.domain.model.TxnType
import com.finora.app.domain.receipt.ReceiptParser
import com.finora.app.platform.reminders.ReminderTiming
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class PortfolioCalculatorTest {
    private val d0 = LocalDate.of(2026, 1, 1)
    private fun u(s: String) = Decimal4.parse(s)!!

    @Test fun unitsCostAndGainFromManualPrices() {
        val txns = listOf(
            InvTxn(InvestmentTxnKind.BUY, d0, u("10"), u("100"), 1_000_00, 20_00),
            InvTxn(InvestmentTxnKind.BUY, d0.plusDays(10), u("10"), u("120"), 1_200_00, 0),
        )
        val (h, errors) = PortfolioCalculator.holding(txns, listOf(Valuation(d0.plusDays(20), u("150"))), d0.plusDays(21))
        assertTrue(errors.isEmpty())
        assertEquals(u("20"), h.unitsE4)
        assertEquals(2_220_00L, h.investedPaise)
        assertEquals(3_000_00L, h.currentValuePaise)
        assertEquals(780_00L, h.unrealizedGainPaise)
        // 780 / 2220 = 35.14%
        assertEquals(3514L, h.unrealizedGainBps)
        assertFalse(h.isStale)
    }

    @Test fun sellUsesAverageCostAndRealisesTheGain() {
        val txns = listOf(
            InvTxn(InvestmentTxnKind.BUY, d0, u("10"), u("100"), 1_000_00),
            InvTxn(InvestmentTxnKind.BUY, d0.plusDays(1), u("10"), u("200"), 2_000_00),
            InvTxn(InvestmentTxnKind.SELL, d0.plusDays(2), u("5"), u("250"), 1_250_00, 10_00),
        )
        val (h, _) = PortfolioCalculator.holding(txns, emptyList(), d0.plusDays(3))
        // Average cost 150; 5 units cost 750; proceeds 1250 - 10 = 1240; realised 490.
        assertEquals(490_00L, h.realizedGainPaise)
        assertEquals(2_250_00L, h.investedPaise)
        assertEquals(u("15"), h.unitsE4)
        assertEquals(1_240_00L, h.withdrawnPaise)
        assertEquals(3_000_00L, h.contributedPaise)
    }

    @Test fun noPriceMeansNoValueNotZero() {
        val (h, _) = PortfolioCalculator.holding(listOf(InvTxn(InvestmentTxnKind.BUY, d0, u("1"), u("10"), 10_00)), emptyList(), d0)
        assertNull(h.currentValuePaise)
        assertNull(h.unrealizedGainBps)
        assertTrue(h.isStale)
    }

    @Test fun pricesOlderThanFourteenDaysAreStale() {
        val txns = listOf(InvTxn(InvestmentTxnKind.BUY, d0, u("1"), u("10"), 10_00))
        val vals = listOf(Valuation(d0, u("10")))
        assertFalse(PortfolioCalculator.holding(txns, vals, d0.plusDays(14)).first.isStale)
        assertTrue(PortfolioCalculator.holding(txns, vals, d0.plusDays(15)).first.isStale)
    }

    @Test fun aFutureDatedPriceIsNotUsed() {
        val txns = listOf(InvTxn(InvestmentTxnKind.BUY, d0, u("1"), u("10"), 10_00))
        val (h, _) = PortfolioCalculator.holding(txns, listOf(Valuation(d0, u("10")), Valuation(d0.plusDays(30), u("99"))), d0.plusDays(5))
        assertEquals(u("10"), h.latestValuation!!.priceE4)
    }

    @Test fun splitAndBonusChangeUnitsNotCost() {
        val txns = listOf(
            InvTxn(InvestmentTxnKind.BUY, d0, u("10"), u("1000"), 10_000_00),
            InvTxn(InvestmentTxnKind.SPLIT, d0.plusDays(5), splitTo = 5, splitFrom = 1),
            InvTxn(InvestmentTxnKind.BONUS, d0.plusDays(6), u("10")),
        )
        val (h, _) = PortfolioCalculator.holding(txns, emptyList(), d0.plusDays(7))
        assertEquals(u("60"), h.unitsE4)
        assertEquals(10_000_00L, h.investedPaise)
    }

    @Test fun sellingMoreThanHeldIsReportedAndSkipped() {
        val (h, errors) = PortfolioCalculator.holding(listOf(InvTxn(InvestmentTxnKind.SELL, d0, u("1"), u("10"), 10_00)), emptyList(), d0)
        assertTrue(errors.single() is PortfolioError.SoldMoreThanHeld)
        assertEquals(0L, h.unitsE4)
    }

    @Test fun biweeklyHistoryValuesEachSnapshotAndFlagsUnpricedHoldings() {
        val a = listOf(InvTxn(InvestmentTxnKind.BUY, d0, u("10"), u("100"), 1_000_00)) to listOf(Valuation(d0, u("100")), Valuation(d0.plusDays(14), u("110")), Valuation(d0.plusDays(28), u("105")))
        val b = listOf(InvTxn(InvestmentTxnKind.BUY, d0.plusDays(10), u("2"), u("500"), 1_000_00)) to listOf(Valuation(d0.plusDays(28), u("520")))
        val h = PortfolioCalculator.history(mapOf(1L to a, 2L to b))
        assertEquals(listOf(d0, d0.plusDays(14), d0.plusDays(28)), h.map { it.date })
        assertEquals(listOf(1_000_00L, 1_100_00L, 1_050_00L + 1_040_00L), h.map { it.valuePaise })
        // On day 14 fund B was held but had no price yet: partial, not zero-valued silently.
        assertEquals(listOf(0, 1, 0), h.map { it.unpricedHoldings })
    }

    @Test fun sipScheduleClampsToShortMonths() {
        val dates = PortfolioCalculator.sipDueDates(LocalDate.of(2026, 1, 31), 31, null, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 3, 31))
        assertEquals(listOf(LocalDate.of(2026, 1, 31), LocalDate.of(2026, 2, 28), LocalDate.of(2026, 3, 31)), dates)
    }
}

class AnalyticsTest {
    private val d = LocalDate.of(2026, 3, 10)
    private fun t(a: Long, type: TxnType, cat: String? = "Food", m: String? = "Cafe", reversed: Boolean = false, date: LocalDate = d) = AnalyticsTxn(a, type, date, cat, m, reversed)

    @Test fun transfersLendingAndInvestmentsNeverInflateIncomeOrSpending() {
        val s = Analytics.summary(listOf(
            t(50_000_00, TxnType.SALARY, "Salary"), t(1_000_00, TxnType.EXPENSE),
            t(20_000_00, TxnType.SELF_TRANSFER), t(5_000_00, TxnType.LENT), t(5_000_00, TxnType.LENDING_REPAYMENT_RECEIVED),
            t(10_000_00, TxnType.INVESTMENT_PURCHASE), t(12_000_00, TxnType.CARD_BILL_PAYMENT), t(3_000_00, TxnType.LOAN_REPAYMENT),
            t(2_000_00, TxnType.BILL_SETTLEMENT), t(500_00, TxnType.PERSON_PAYMENT),
        ))
        assertEquals(50_000_00L, s.incomePaise)
        assertEquals(1_000_00L, s.expensePaise)
    }

    @Test fun refundsReduceSpendingAndReversedTransactionsCountForNothing() {
        val s = Analytics.summary(listOf(t(1_000_00, TxnType.EXPENSE), t(300_00, TxnType.REFUND), t(700_00, TxnType.EXPENSE, reversed = true)))
        assertEquals(700_00L, s.expensePaise)
        assertEquals(300_00L, s.refundsPaise)
    }

    @Test fun savingsRateIsUndefinedWithoutIncome() {
        assertNull(Analytics.summary(listOf(t(100, TxnType.EXPENSE))).savingsRateBps)
        assertEquals(2000L, Analytics.summary(listOf(t(100_00, TxnType.INCOME), t(80_00, TxnType.EXPENSE))).savingsRateBps)
    }

    @Test fun unusualSpendingNeedsBothRatioAndAbsoluteJump() {
        val now = listOf(Slice("Food", 9_000_00, 10), Slice("Tea", 80_00, 4))
        val prev = List(3) { listOf(Slice("Food", 4_000_00, 8), Slice("Tea", 40_00, 2)) }
        val insights = Analytics.unusualSpending(now, prev)
        assertEquals(1, insights.size)
        assertTrue(insights.single().explanation.contains("₹9,000.00"))
    }

    @Test fun recurringNeedsThreeMonthsAtASimilarAmount() {
        val list = (0..3).map { t(649_00, TxnType.EXPENSE, m = "Netflix", date = d.minusMonths(it.toLong())) } + t(120_00, TxnType.EXPENSE, m = "Cafe")
        val r = Analytics.recurring(list)
        assertEquals("Netflix", r.single().merchant)
        assertEquals(4, r.single().months)
    }
}

class CsvTest {
    @Test fun escapesQuotesCommasAndNewlinesAndRoundTrips() {
        val fields = listOf("plain", "with, comma", "with \"quote\"", "multi\nline", "")
        assertEquals(listOf(fields), Csv.parse(Csv.row(fields)))
    }

    @Test fun neutralisesSpreadsheetFormulas() {
        assertEquals("'=HYPERLINK(\"x\")".let { "\"" + it.replace("\"", "\"\"") + "\"" }, Csv.escape("=HYPERLINK(\"x\")"))
        assertEquals("-12.5", Csv.escape("-12.5"))
    }

    @Test fun transactionExportImportsBackIdentically() {
        val row = TransactionCsv.Row(LocalDateTime.of(2026, 3, 1, 9, 30), 12_345_67, Direction.DEBIT, TxnType.EXPENSE, "Big Bazaar, Indiranagar", null, "Groceries", "weekly shop", "HDFC ••1234", "UPI", "407312345678", "note \"x\"", "ref:407312345678:DEBIT:1234567")
        val back = TransactionCsv.read(TransactionCsv.write(listOf(row)))
        assertTrue(back.errors.isEmpty())
        assertEquals(row, back.rows.single())
    }

    @Test fun badRowsAreReportedNotImported() {
        val r = TransactionCsv.read("date,amount\n2026-03-01 10:00,abc\nnot a date,10\n2026-03-02,250.50\n")
        assertEquals(1, r.rows.size)
        assertEquals(25050L, r.rows.single().amountPaise)
        assertEquals(2, r.errors.size)
    }
}

class ReceiptParserTest {
    @Test fun readsItemsChargesAndTotal() {
        val text = """
            TOIT BREWPUB
            100 Ft Road, Indiranagar
            Date: 12/03/2026  Table 7
            Paneer Tikka 1 320.00 320.00
            Pint Lager 2 300.00 600.00
            Fries 180.00
            Sub Total 1100.00
            CGST 2.5% 27.50
            SGST 2.5% 27.50
            Service Charge 55.00
            Discount 100.00
            Grand Total 1110.00
            Thank you, visit again
        """.trimIndent()
        val r = ReceiptParser.parse(text)
        assertEquals("TOIT BREWPUB", r.merchant)
        assertEquals(LocalDate.of(2026, 3, 12), r.date)
        assertEquals(listOf("Paneer Tikka", "Pint Lager", "Fries"), r.items.map { it.name })
        assertEquals(2000L, r.items[1].quantityMilli)
        assertEquals(1_100_00L, r.itemsSubtotalPaise)
        assertEquals(55_00L, r.taxPaise)
        assertEquals(55_00L, r.servicePaise)
        assertEquals(100_00L, r.discountPaise)
        assertEquals(1_110_00L, r.totalPaise)
        assertEquals(0L, r.unreconciledPaise)
    }

    @Test fun garbageProducesAnEmptyDraftNotACrash() {
        val r = ReceiptParser.parse("~~~ \n ### \n 12 \n")
        assertTrue(r.items.isEmpty())
        assertNull(r.totalPaise)
    }
}

class ReminderTimingTest {
    private fun r(min: Int, interval: Int = 1, anchor: LocalDate? = null) = ReminderEntity(key = "k", title = "t", timeOfDayMinutes = min, intervalDays = interval, enabled = true, anchorDate = anchor?.toEpochDay())

    @Test fun dailyFiresTodayIfStillAheadElseTomorrow() {
        val now = LocalDateTime.of(2026, 3, 14, 14, 0)
        assertEquals(LocalDateTime.of(2026, 3, 14, 14, 30), ReminderTiming.next(r(14 * 60 + 30), now))
        assertEquals(LocalDateTime.of(2026, 3, 15, 14, 30), ReminderTiming.next(r(14 * 60 + 30), now.withHour(15)))
        assertEquals(LocalDateTime.of(2026, 3, 14, 21, 30), ReminderTiming.next(r(21 * 60 + 30), now))
    }

    @Test fun biweeklyCountsFromTheAnchor() {
        val anchor = LocalDate.of(2026, 3, 1)
        assertEquals(LocalDateTime.of(2026, 3, 15, 10, 0), ReminderTiming.next(r(600, 14, anchor), LocalDateTime.of(2026, 3, 2, 9, 0)))
        assertEquals(LocalDateTime.of(2026, 3, 29, 10, 0), ReminderTiming.next(r(600, 14, anchor), LocalDateTime.of(2026, 3, 15, 11, 0)))
    }
}

class PlaceRankingTest {
    @Test fun rankingPrefersNameMatchThenProximity() {
        val here = GeoPoint(12.9716, 77.5946)
        val near = PlaceCandidate("Starbucks", null, GeoPoint(12.9720, 77.5950), "cafe", null)
        val far = PlaceCandidate("Starbucks", null, GeoPoint(13.05, 77.70), "cafe", null)
        val wrong = PlaceCandidate("Barbeque Nation", null, GeoPoint(12.9717, 77.5947), "restaurant", null)
        val ranked = PlaceRanking.rank("STARBUCKS COFFEE", here, listOf(far, wrong, near))
        assertEquals(near, ranked.first().place)
        assertTrue(ranked.none { it.place == wrong })
        assertEquals("Food & dining", ranked.first().suggestedCategory)
    }
}
