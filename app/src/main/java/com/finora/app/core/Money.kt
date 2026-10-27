package com.finora.app.core

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Money is integer paise everywhere. Rupee strings are parsed and printed here and nowhere
 * else, so a float never reaches a stored amount.
 */
object Money {
    const val PAISE_PER_RUPEE = 100L

    /** Parses "1,23,456.78", "₹ 500", "Rs.99.5", "INR 1,000". Returns null for anything else. */
    fun parseToPaise(input: String?): Long? {
        if (input.isNullOrBlank()) return null
        val cleaned = input.trim()
            .replace("₹", "")
            .replace(Regex("(?i)^(inr|rs\\.?)"), "")
            .replace(",", "")
            .replace(" ", "")
        if (cleaned.isEmpty() || !cleaned.matches(Regex("-?\\d+(\\.\\d{0,2})?"))) return null
        return try {
            BigDecimal(cleaned).setScale(2, RoundingMode.UNNECESSARY).movePointRight(2).longValueExact()
        } catch (_: ArithmeticException) {
            null
        }
    }

    /** "₹1,23,456.78" with Indian digit grouping. Deterministic, locale-independent. */
    fun format(paise: Long, symbol: String = "₹", showPaise: Boolean = true): String {
        val negative = paise < 0
        val abs = if (paise == Long.MIN_VALUE) Long.MAX_VALUE else kotlin.math.abs(paise)
        val rupees = abs / PAISE_PER_RUPEE
        val fraction = abs % PAISE_PER_RUPEE
        val grouped = groupIndian(rupees.toString())
        val body = if (showPaise) "$grouped.${fraction.toString().padStart(2, '0')}" else grouped
        return (if (negative) "-" else "") + symbol + body
    }

    /** Compact form for chart axes and dense tiles: ₹1.2L, ₹3.4Cr, ₹950. */
    fun formatCompact(paise: Long, symbol: String = "₹"): String {
        val rupees = paise / 100.0
        val abs = kotlin.math.abs(rupees)
        val sign = if (rupees < 0) "-" else ""
        return when {
            abs >= 1_00_00_000 -> "$sign$symbol${trim(abs / 1_00_00_000)}Cr"
            abs >= 1_00_000 -> "$sign$symbol${trim(abs / 1_00_000)}L"
            abs >= 1_000 -> "$sign$symbol${trim(abs / 1_000)}K"
            else -> "$sign$symbol${abs.toLong()}"
        }
    }

    /** Plain editable form for text fields: "1234.50". */
    fun toInput(paise: Long): String =
        BigDecimal.valueOf(paise).movePointLeft(2).setScale(2, RoundingMode.UNNECESSARY).toPlainString()

    private fun trim(v: Double): String {
        val s = BigDecimal(v).setScale(1, RoundingMode.HALF_UP).toPlainString()
        return if (s.endsWith(".0")) s.dropLast(2) else s
    }

    internal fun groupIndian(digits: String): String {
        if (digits.length <= 3) return digits
        val last3 = digits.takeLast(3)
        val rest = digits.dropLast(3)
        val sb = StringBuilder()
        rest.reversed().chunked(2).forEach { sb.append(it).append(',') }
        return sb.reverse().toString().trimStart(',') + "," + last3
    }

    /** Distributes [total] across [weights] so the parts sum to EXACTLY [total] (largest remainder). */
    fun allocate(total: Long, weights: List<Long>): List<Long> {
        if (weights.isEmpty()) return emptyList()
        val sum = weights.sum()
        if (sum == 0L) return allocate(total, weights.map { 1L })
        val raw = weights.map { BigDecimal.valueOf(total).multiply(BigDecimal.valueOf(it)).divide(BigDecimal.valueOf(sum), 10, RoundingMode.HALF_EVEN) }
        val floors = raw.map { it.setScale(0, RoundingMode.FLOOR).toLong() }
        var remainder = total - floors.sum()
        val order = raw.indices.sortedByDescending { raw[it].subtract(BigDecimal.valueOf(floors[it])) }
        val result = floors.toMutableList()
        var i = 0
        val step = if (remainder >= 0) 1 else -1
        while (remainder != 0L) {
            result[order[i % order.size]] += step.toLong()
            remainder -= step
            i++
        }
        return result
    }
}

/**
 * Units and prices of investments carry 4 decimal places (mutual-fund units and NAVs are
 * published to 3–4 decimals), stored as Long scaled by 10,000.
 */
object Decimal4 {
    const val SCALE = 10_000L

    fun parse(input: String?): Long? {
        if (input.isNullOrBlank()) return null
        val cleaned = input.trim().replace(",", "").replace("₹", "")
        if (!cleaned.matches(Regex("-?\\d+(\\.\\d{0,4})?"))) return null
        return try {
            BigDecimal(cleaned).setScale(4, RoundingMode.UNNECESSARY).movePointRight(4).longValueExact()
        } catch (_: ArithmeticException) {
            null
        }
    }

    fun format(valueE4: Long, maxDecimals: Int = 4): String =
        BigDecimal.valueOf(valueE4).movePointLeft(4).setScale(maxDecimals, RoundingMode.HALF_UP)
            .stripTrailingZeros().let { if (it.scale() < 0) it.setScale(0) else it }.toPlainString()

    fun toBig(valueE4: Long): BigDecimal = BigDecimal.valueOf(valueE4).movePointLeft(4)

    /** units(E4) × price(E4 rupees) -> paise, rounded half-even. */
    fun valuePaise(unitsE4: Long, priceE4: Long): Long =
        toBig(unitsE4).multiply(toBig(priceE4)).movePointRight(2).setScale(0, RoundingMode.HALF_EVEN).longValueExact()
}
