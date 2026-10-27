package com.finora.app.domain.invest

import com.finora.app.core.Decimal4
import com.finora.app.domain.model.InvestmentTxnKind
import com.finora.app.domain.model.InvestmentType
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.time.LocalDate
import java.time.temporal.ChronoUnit

data class InvTxn(
    val kind: InvestmentTxnKind,
    val date: LocalDate,
    val unitsE4: Long = 0,
    val priceE4: Long = 0,
    val amountPaise: Long = 0,
    val costsPaise: Long = 0,
    val splitTo: Int = 0,
    val splitFrom: Int = 0,
)

data class Valuation(val date: LocalDate, val priceE4: Long)

data class Holding(
    val unitsE4: Long,
    /** Cost of the units still held (average-cost method), including purchase costs. */
    val investedPaise: Long,
    /** Every rupee put in: purchases, SIPs and their costs. */
    val contributedPaise: Long,
    /** Every rupee taken out by selling or redeeming, net of costs. */
    val withdrawnPaise: Long,
    val dividendsPaise: Long,
    val realizedGainPaise: Long,
    /** Null when no price has ever been entered — never zero, never invented. */
    val latestValuation: Valuation?,
    val currentValuePaise: Long?,
    val unrealizedGainPaise: Long?,
    /** Percent × 100 (12.34% = 1234), null when there is nothing to divide by or no price. */
    val unrealizedGainBps: Long?,
    val isStale: Boolean,
    val daysSinceValuation: Long?,
)

sealed interface PortfolioError {
    data class SoldMoreThanHeld(val date: LocalDate) : PortfolioError
    data class BadSplit(val date: LocalDate) : PortfolioError
}

/**
 * All portfolio figures, computed locally from what the user recorded. Prices and NAVs are
 * ONLY ever the ones the user typed — there is no feed — so every value carries its date and
 * a stale flag, and a holding with no price has no value rather than a value of zero.
 *
 * Cost basis uses the AVERAGE-COST method (stated in the UI). Indian capital-gains tax uses
 * FIFO, so realised figures here are a tracking aid, not a tax computation.
 */
object PortfolioCalculator {
    const val STALE_AFTER_DAYS = 14L
    private val MC = MathContext(34)

    fun holding(txns: List<InvTxn>, valuations: List<Valuation>, today: LocalDate, staleAfterDays: Long = STALE_AFTER_DAYS): Pair<Holding, List<PortfolioError>> {
        val errors = mutableListOf<PortfolioError>()
        var units = BigDecimal.ZERO
        var cost = BigDecimal.ZERO
        var contributed = 0L
        var withdrawn = 0L
        var dividends = 0L
        var realized = BigDecimal.ZERO

        for (t in txns.sortedWith(compareBy({ it.date }, { order(it.kind) }))) {
            val u = Decimal4.toBig(t.unitsE4)
            when (t.kind) {
                InvestmentTxnKind.BUY, InvestmentTxnKind.SIP -> {
                    units += u
                    val paid = t.amountPaise + t.costsPaise
                    cost += BigDecimal.valueOf(paid)
                    contributed += paid
                }
                InvestmentTxnKind.SELL, InvestmentTxnKind.WITHDRAWAL -> {
                    if (u > units) {
                        errors += PortfolioError.SoldMoreThanHeld(t.date)
                        continue
                    }
                    val avg = if (units.signum() == 0) BigDecimal.ZERO else cost.divide(units, MC)
                    val costOut = avg.multiply(u, MC)
                    val proceeds = t.amountPaise - t.costsPaise
                    realized += BigDecimal.valueOf(proceeds) - costOut
                    cost -= costOut
                    units -= u
                    withdrawn += proceeds
                    if (units.signum() == 0) cost = BigDecimal.ZERO
                }
                InvestmentTxnKind.DIVIDEND -> dividends += t.amountPaise
                InvestmentTxnKind.SPLIT -> {
                    if (t.splitTo <= 0 || t.splitFrom <= 0) {
                        errors += PortfolioError.BadSplit(t.date)
                        continue
                    }
                    // Cost unchanged; units scale. Rounded to the 4-decimal unit precision.
                    units = units.multiply(BigDecimal(t.splitTo)).divide(BigDecimal(t.splitFrom), 4, RoundingMode.HALF_EVEN)
                }
                InvestmentTxnKind.BONUS -> units += u // zero cost: lowers average cost
                InvestmentTxnKind.UNIT_ADJUSTMENT -> {
                    units += u
                    if (units.signum() < 0) {
                        errors += PortfolioError.SoldMoreThanHeld(t.date)
                        units = BigDecimal.ZERO
                    }
                }
            }
        }

        val unitsE4 = units.movePointRight(4).setScale(0, RoundingMode.HALF_EVEN).toLong()
        val invested = cost.setScale(0, RoundingMode.HALF_EVEN).toLong()
        val latest = valuations.filter { !it.date.isAfter(today) }.maxByOrNull { it.date }
        val value = latest?.let { Decimal4.valuePaise(unitsE4, it.priceE4) }
        val gain = value?.let { it - invested }
        val gainBps = if (gain != null && invested > 0) BigDecimal.valueOf(gain).multiply(BigDecimal(10_000)).divide(BigDecimal.valueOf(invested), 0, RoundingMode.HALF_EVEN).toLong() else null
        val days = latest?.let { ChronoUnit.DAYS.between(it.date, today) }
        return Holding(
            unitsE4 = unitsE4,
            investedPaise = invested,
            contributedPaise = contributed,
            withdrawnPaise = withdrawn,
            dividendsPaise = dividends,
            realizedGainPaise = realized.setScale(0, RoundingMode.HALF_EVEN).toLong(),
            latestValuation = latest,
            currentValuePaise = value,
            unrealizedGainPaise = gain,
            unrealizedGainBps = gainBps,
            isStale = days == null || days > staleAfterDays,
            daysSinceValuation = days,
        ) to errors
    }

    /** Buys before sells on the same day, so an intraday round trip does not read as overselling. */
    private fun order(kind: InvestmentTxnKind) = when (kind) {
        InvestmentTxnKind.BUY, InvestmentTxnKind.SIP, InvestmentTxnKind.BONUS -> 0
        InvestmentTxnKind.SPLIT, InvestmentTxnKind.UNIT_ADJUSTMENT -> 1
        else -> 2
    }

    fun unitsAsOf(txns: List<InvTxn>, date: LocalDate): Long =
        holding(txns.filter { !it.date.isAfter(date) }, emptyList(), date).first.unitsE4

    data class SnapshotPoint(
        val date: LocalDate,
        val valuePaise: Long,
        /** Holdings that had units on this date but no price on or before it. */
        val unpricedHoldings: Int,
    )

    /**
     * Portfolio value on each date a valuation was entered: units held then × the latest price
     * entered on or before then. A holding with no price yet is COUNTED as unpriced rather than
     * valued at zero, so the chart can say the point is partial.
     */
    fun history(holdings: Map<Long, Pair<List<InvTxn>, List<Valuation>>>): List<SnapshotPoint> {
        val dates = holdings.values.flatMap { it.second.map(Valuation::date) }.distinct().sorted()
        return dates.map { d ->
            var total = 0L
            var unpriced = 0
            for ((txns, vals) in holdings.values) {
                val units = unitsAsOf(txns, d)
                if (units == 0L) continue
                val price = vals.filter { !it.date.isAfter(d) }.maxByOrNull { it.date }
                if (price == null) unpriced++ else total += Decimal4.valuePaise(units, price.priceE4)
            }
            SnapshotPoint(d, total, unpriced)
        }
    }

    data class AllocationSlice(val type: InvestmentType, val valuePaise: Long, val shareBps: Long)

    fun allocation(values: List<Pair<InvestmentType, Long>>): List<AllocationSlice> {
        val total = values.sumOf { it.second }
        if (total <= 0) return emptyList()
        return values.groupBy({ it.first }, { it.second }).map { (type, v) ->
            val sum = v.sum()
            AllocationSlice(type, sum, sum * 10_000 / total)
        }.sortedByDescending { it.valuePaise }
    }

    /** Scheduled SIP dates between [from] and [to], for "planned vs actual". */
    fun sipDueDates(start: LocalDate, dayOfMonth: Int, end: LocalDate?, from: LocalDate, to: LocalDate): List<LocalDate> {
        val out = mutableListOf<LocalDate>()
        var m = java.time.YearMonth.from(start)
        val last = java.time.YearMonth.from(listOfNotNull(end, to).min())
        while (!m.isAfter(last)) {
            val d = m.atDay(minOf(dayOfMonth, m.lengthOfMonth()))
            if (!d.isBefore(start) && !d.isBefore(from) && !d.isAfter(to) && (end == null || !d.isAfter(end))) out += d
            m = m.plusMonths(1)
        }
        return out
    }
}
