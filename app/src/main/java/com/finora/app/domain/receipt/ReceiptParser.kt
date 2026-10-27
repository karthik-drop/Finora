package com.finora.app.domain.receipt

import com.finora.app.core.Money
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

data class ReceiptLine(val name: String, val quantityMilli: Long, val unitPricePaise: Long, val totalPaise: Long)

data class ParsedReceipt(
    val merchant: String?,
    val date: LocalDate?,
    val items: List<ReceiptLine>,
    val taxPaise: Long,
    val discountPaise: Long,
    val servicePaise: Long,
    val totalPaise: Long?,
) {
    val itemsSubtotalPaise: Long get() = items.sumOf { it.totalPaise }
    /** Difference between the printed total and items + charges; non-zero means the OCR needs a look. */
    val unreconciledPaise: Long? get() = totalPaise?.let { it - (itemsSubtotalPaise + taxPaise + servicePaise - discountPaise) }
}

/**
 * Reads OCR text from a printed bill. OCR is imperfect and receipt layouts vary wildly, so this
 * produces a DRAFT the user always reviews and corrects; [ParsedReceipt.unreconciledPaise] tells
 * the review screen how far the draft is from the printed total.
 */
object ReceiptParser {
    private val AMOUNT = "([0-9][0-9,]*\\.[0-9]{2}|[0-9][0-9,]*)"
    private val LINE_QTY_PRICE = Regex("^(.+?)\\s+([0-9]+(?:\\.[0-9]{1,3})?)\\s*[xX@*]?\\s+$AMOUNT\\s+$AMOUNT$")
    private val LINE_PRICE = Regex("^(.+?)\\s+(?:₹|Rs\\.?|INR)?\\s*$AMOUNT$")
    private val TOTAL = Regex("(?i)^(grand\\s*total|net\\s*(amount|payable|total)|total\\s*(amount|payable|due)?|amount\\s*(payable|due)|bill\\s*amount)\\b[^0-9]*$AMOUNT")
    private val SUBTOTAL = Regex("(?i)^(sub\\s*-?\\s*total|item\\s*total|gross)\\b")
    private val TAX = Regex("(?i)^(c\\s*gst|s\\s*gst|i\\s*gst|gst|vat|tax|cess|ugst)\\b[^\\n]*?$AMOUNT$")
    private val DISCOUNT = Regex("(?i)^(discount|disc\\.?|less|promo|coupon|offer|savings)\\b[^\\n]*?$AMOUNT$")
    private val SERVICE = Regex("(?i)^(service\\s*charge|svc\\s*chg|s\\.?\\s*c\\.?|tip|gratuity|packing|delivery)\\b[^\\n]*?$AMOUNT$")
    private val ROUND_OFF = Regex("(?i)^(round\\s*off|rounding)\\b")
    private val NOT_ITEM = Regex("(?i)(gstin|fssai|invoice|bill\\s*no|table|cashier|phone|tel|date|time|thank|visit|order|token|cash|card|upi|change|paid|balance|qty|item|rate|amount|amt|hsn|mrp|pax|server|steward)")

    private val dateFormats = listOf("dd/MM/yyyy", "dd-MM-yyyy", "dd.MM.yyyy", "dd/MM/yy", "dd-MM-yy", "yyyy-MM-dd", "dd MMM yyyy", "dd-MMM-yyyy", "dd-MMM-yy")
        .map { DateTimeFormatter.ofPattern(it, Locale.ENGLISH) }
    private val DATE = Regex("(\\d{1,2}[/.-]\\d{1,2}[/.-]\\d{2,4}|\\d{4}-\\d{2}-\\d{2}|\\d{1,2}[ -][A-Za-z]{3}[ -]\\d{2,4})")

    fun parse(text: String): ParsedReceipt {
        val lines = text.lines().map { it.trim().replace(Regex("\\s+"), " ") }.filter { it.isNotEmpty() }
        var total: Long? = null
        var tax = 0L
        var discount = 0L
        var service = 0L
        val items = mutableListOf<ReceiptLine>()
        var date: LocalDate? = null
        var reachedTotals = false

        for (line in lines) {
            if (date == null) date = DATE.find(line)?.value?.let(::parseDate)
            TOTAL.find(line)?.let { m ->
                // The LAST total line wins: "Total" before tax, "Grand Total" after.
                Money.parseToPaise(m.groupValues.last())?.let { total = it }
                reachedTotals = true
                return@let
            } ?: run {
                when {
                    SUBTOTAL.containsMatchIn(line) -> reachedTotals = true
                    ROUND_OFF.containsMatchIn(line) -> Unit
                    TAX.find(line) != null -> { tax += amountOf(TAX.find(line)!!); reachedTotals = true }
                    DISCOUNT.find(line) != null -> { discount += amountOf(DISCOUNT.find(line)!!).let(Math::abs); reachedTotals = true }
                    SERVICE.find(line) != null -> { service += amountOf(SERVICE.find(line)!!); reachedTotals = true }
                    !reachedTotals -> parseItem(line)?.let(items::add)
                }
            }
        }
        val merchant = lines.take(4).firstOrNull { l ->
            l.count(Char::isLetter) >= 3 && !NOT_ITEM.containsMatchIn(l) && !l.any(Char::isDigit) || l.count(Char::isLetter) >= 3 && l == l.uppercase() && !l.any(Char::isDigit)
        }
        return ParsedReceipt(merchant, date, items, tax, discount, service, total)
    }

    private fun amountOf(m: MatchResult): Long = Money.parseToPaise(m.groupValues.last()) ?: 0

    fun parseItem(line: String): ReceiptLine? {
        if (NOT_ITEM.containsMatchIn(line.substringBefore(' ')) && line.split(' ').size <= 3) return null
        LINE_QTY_PRICE.find(line)?.let { m ->
            val name = m.groupValues[1].trim()
            val qtyMilli = (m.groupValues[2].toBigDecimal().movePointRight(3)).toLong()
            val unit = Money.parseToPaise(m.groupValues[3]) ?: return null
            val tot = Money.parseToPaise(m.groupValues[4]) ?: return null
            if (name.count(Char::isLetter) < 2 || qtyMilli <= 0) return null
            return ReceiptLine(name, qtyMilli, unit, tot)
        }
        LINE_PRICE.find(line)?.let { m ->
            val name = m.groupValues[1].trim().trimEnd('.', ':', '-')
            val tot = Money.parseToPaise(m.groupValues[2]) ?: return null
            if (name.count(Char::isLetter) < 2 || NOT_ITEM.containsMatchIn(name)) return null
            return ReceiptLine(name, 1000, tot, tot)
        }
        return null
    }

    private fun parseDate(s: String): LocalDate? {
        for (f in dateFormats) {
            val d = runCatching { LocalDate.parse(s, f) }.getOrNull() ?: continue
            return if (d.year < 100) d.withYear(2000 + d.year) else d
        }
        return null
    }
}
