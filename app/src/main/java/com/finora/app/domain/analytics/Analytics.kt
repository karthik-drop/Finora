package com.finora.app.domain.analytics

import com.finora.app.core.Money
import com.finora.app.domain.model.TxnType
import java.time.LocalDate
import java.time.YearMonth
import kotlin.math.abs

data class AnalyticsTxn(
    val amountPaise: Long,
    val type: TxnType,
    val date: LocalDate,
    val categoryName: String?,
    val merchantName: String?,
    val reversed: Boolean = false,
)

data class PeriodSummary(
    val incomePaise: Long,
    val expensePaise: Long,
    /** Refunds already subtracted from [expensePaise]; shown so the reduction is visible. */
    val refundsPaise: Long,
) {
    val netPaise: Long get() = incomePaise - expensePaise
    /** Percent × 100; null without income (a savings rate over nothing is undefined, not 0). */
    val savingsRateBps: Long? get() = if (incomePaise > 0) netPaise * 10_000 / incomePaise else null
}

data class Slice(val label: String, val amountPaise: Long, val count: Int)

data class MonthPoint(val month: YearMonth, val incomePaise: Long, val expensePaise: Long) {
    val netPaise: Long get() = incomePaise - expensePaise
}

data class RecurringExpense(val merchant: String, val typicalPaise: Long, val months: Int, val lastDate: LocalDate)

/** Every insight carries the sentence that explains it from the user's own numbers. */
data class Insight(val title: String, val explanation: String, val severity: Severity) {
    enum class Severity { INFO, WATCH }
}

/**
 * Deterministic, explainable arithmetic over recorded transactions. Only [TxnType.countsAs]
 * decides what is income or expense, so transfers, principal, lending and investments can
 * never inflate either side.
 */
object Analytics {

    fun summary(txns: List<AnalyticsTxn>): PeriodSummary {
        var income = 0L
        var expense = 0L
        var refunds = 0L
        for (t in txns) {
            if (t.reversed) continue
            when (t.type.countsAs) {
                TxnType.Flow.INCOME -> income += t.amountPaise
                TxnType.Flow.EXPENSE -> expense += t.amountPaise
                TxnType.Flow.EXPENSE_REDUCTION -> refunds += t.amountPaise
                TxnType.Flow.NEUTRAL -> Unit
            }
        }
        return PeriodSummary(income, (expense - refunds).coerceAtLeast(0), refunds)
    }

    /** Spending by category, refunds netted against the category they came back to. */
    fun byCategory(txns: List<AnalyticsTxn>): List<Slice> = groupSpend(txns) { it.categoryName ?: "Uncategorised" }

    fun byMerchant(txns: List<AnalyticsTxn>, limit: Int = 10): List<Slice> =
        groupSpend(txns.filter { it.merchantName != null }) { it.merchantName!! }.take(limit)

    private fun groupSpend(txns: List<AnalyticsTxn>, key: (AnalyticsTxn) -> String): List<Slice> {
        val sums = linkedMapOf<String, Pair<Long, Int>>()
        for (t in txns) {
            if (t.reversed) continue
            val sign = when (t.type.countsAs) {
                TxnType.Flow.EXPENSE -> 1
                TxnType.Flow.EXPENSE_REDUCTION -> -1
                else -> continue
            }
            val k = key(t)
            val (a, c) = sums[k] ?: (0L to 0)
            sums[k] = (a + sign * t.amountPaise) to (c + if (sign > 0) 1 else 0)
        }
        return sums.map { (k, v) -> Slice(k, v.first.coerceAtLeast(0), v.second) }.filter { it.amountPaise > 0 }.sortedByDescending { it.amountPaise }
    }

    fun monthly(txns: List<AnalyticsTxn>, months: List<YearMonth>): List<MonthPoint> {
        val byMonth = txns.groupBy { YearMonth.from(it.date) }
        return months.map { m ->
            val s = summary(byMonth[m].orEmpty())
            MonthPoint(m, s.incomePaise, s.expensePaise)
        }
    }

    /**
     * A merchant paid in at least [minMonths] distinct months, at amounts within 15% of their
     * median — the shape of a subscription or a regular bill.
     */
    fun recurring(txns: List<AnalyticsTxn>, minMonths: Int = 3): List<RecurringExpense> =
        txns.filter { !it.reversed && it.type.countsAs == TxnType.Flow.EXPENSE && it.merchantName != null }
            .groupBy { it.merchantName!! }
            .mapNotNull { (merchant, list) ->
                val median = list.map { it.amountPaise }.sorted().let { it[it.size / 2] }
                val similar = list.filter { abs(it.amountPaise - median) <= median * 15 / 100 }
                val months = similar.map { YearMonth.from(it.date) }.distinct().size
                if (months >= minMonths) RecurringExpense(merchant, median, months, similar.maxOf { it.date }) else null
            }
            .sortedByDescending { it.typicalPaise }

    /**
     * A category whose spending this month is at least 50% AND ₹1,000 above its average over
     * the previous three months. Both thresholds, so a ₹40 -> ₹80 jump does not raise an alarm.
     */
    fun unusualSpending(current: List<Slice>, previousMonths: List<List<Slice>>): List<Insight> {
        if (previousMonths.isEmpty()) return emptyList()
        return current.mapNotNull { slice ->
            val history = previousMonths.map { m -> m.firstOrNull { it.label == slice.label }?.amountPaise ?: 0L }
            val avg = history.sum() / history.size
            val diff = slice.amountPaise - avg
            if (avg > 0 && diff >= avg / 2 && diff >= 1_000_00) {
                Insight(
                    "${slice.label} is higher than usual",
                    "${Money.format(slice.amountPaise)} this month against an average of ${Money.format(avg)} over the previous ${history.size} months.",
                    Insight.Severity.WATCH,
                )
            } else null
        }
    }

    fun monthComparison(thisMonth: PeriodSummary, lastMonth: PeriodSummary): Insight? {
        if (lastMonth.expensePaise == 0L) return null
        val diff = thisMonth.expensePaise - lastMonth.expensePaise
        val pct = diff * 100 / lastMonth.expensePaise
        val direction = if (diff >= 0) "more" else "less"
        return Insight(
            "Spending ${abs(pct)}% $direction than last month",
            "${Money.format(thisMonth.expensePaise)} so far this month against ${Money.format(lastMonth.expensePaise)} last month.",
            if (pct > 20) Insight.Severity.WATCH else Insight.Severity.INFO,
        )
    }

    data class Projection(val days: Int, val expectedOutflowPaise: Long, val items: List<Pair<String, Long>>)

    /** Known obligations only (recurring bills the user entered and loan instalments). An estimate, labelled as one. */
    fun projectOutflows(obligations: List<Pair<String, Long>>, days: Int = 30): Projection =
        Projection(days, obligations.sumOf { it.second }, obligations.sortedByDescending { it.second })
}
