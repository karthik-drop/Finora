package com.finora.app.domain.sms

import com.finora.app.core.Money
import com.finora.app.domain.model.Direction
import com.finora.app.domain.model.PaymentMethod
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoField
import java.util.Locale

/** Everything one message told us. Absent fields stay null — nothing is guessed to fill them. */
data class ParsedSms(
    val amountPaise: Long,
    val direction: Direction,
    /** From the message when it carried one; otherwise the time the SMS arrived. */
    val occurredAt: Long,
    val dateFromMessage: Boolean,
    val counterparty: String?,
    val upiId: String?,
    val accountTail: String?,
    /** A transfer whose OTHER side is an account number, e.g. "to A/c XX5678". */
    val counterpartyAccountTail: String?,
    val reference: String?,
    val method: PaymentMethod?,
    val balancePaise: Long?,
    val feePaise: Long,
    val taxPaise: Long,
    val isRefund: Boolean,
    val isReversal: Boolean,
    val isCard: Boolean,
    val isCreditCardAccount: Boolean,
    val keywords: Set<Keyword>,
    val ruleId: String,
    val sender: String,
    val body: String,
)

enum class Keyword { SALARY, ATM, CASH_DEPOSIT, LOAN, EMI, DISBURSED, INTEREST, CHARGES, GST, CARD_BILL, INVESTMENT, AUTOPAY }

sealed interface SmsParseResult {
    data class Parsed(val sms: ParsedSms) : SmsParseResult
    /** Looks financial, but no rule could read it with confidence: goes to the review list. */
    data class Unrecognized(val reason: String) : SmsParseResult
    /** OTP, promotion, balance alert, payment request — not a transaction. Not stored. */
    data class Ignored(val reason: String) : SmsParseResult
}

/**
 * A rule recognises one family of message wording and extracts the fields it can. Rules are
 * tried in order; the first that returns a result wins. Adding support for a new bank is adding
 * a rule (and a test with a real sample), never editing the others.
 */
fun interface SmsRule {
    fun parse(ctx: SmsContext): ParsedSms?
}

class SmsContext(val sender: String, val body: String, val receivedAt: Long, val zone: ZoneId) {
    val normalized: String = body.replace(Regex("\\s+"), " ").trim()
    val lower: String = normalized.lowercase(Locale.ENGLISH)
}

class SmsParser(
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val rules: List<Pair<String, SmsRule>> = DefaultRules.all(),
) {
    fun parse(sender: String, body: String, receivedAt: Long): SmsParseResult {
        val ctx = SmsContext(sender, body, receivedAt, zone)
        ignoreReason(ctx)?.let { return SmsParseResult.Ignored(it) }
        if (!looksFinancial(ctx)) return SmsParseResult.Ignored("not a transaction message")
        for ((_, rule) in rules) {
            val parsed = try { rule.parse(ctx) } catch (_: RuntimeException) { null }
            if (parsed != null && parsed.amountPaise > 0) return SmsParseResult.Parsed(parsed)
        }
        return SmsParseResult.Unrecognized("no rule matched this wording")
    }

    private fun ignoreReason(ctx: SmsContext): String? {
        val l = ctx.lower
        return when {
            Regex("\\b(otp|one[- ]time password|verification code)\\b").containsMatchIn(l) -> "one-time password"
            // A collect request has not moved money yet.
            Regex("(has requested|requested money|collect request|is requesting)").containsMatchIn(l) -> "payment request"
            Regex("\\b(will be debited|is due|due on|due date|min(imum)? amount due|total amount due)\\b").containsMatchIn(l) &&
                !Regex("\\b(debited|credited|spent|paid)\\b(?! on)").containsMatchIn(l.replace("will be debited", "")) -> "bill or due-date reminder"
            Regex("\\b(pre-?approved|offer|cashback upto|apply now|eligible for|win |congratulations)\\b").containsMatchIn(l) &&
                !Regex("\\b(debited|credited)\\b").containsMatchIn(l) -> "promotion"
            // "Amount debited, if any, will be refunded" is still a failure; only money actually back counts.
            Regex("\\b(failed|declined|unsuccessful)\\b").containsMatchIn(l) && !Regex("\\b(reversed|credited)\\b").containsMatchIn(l) -> "failed transaction"
            else -> null
        }
    }

    private fun looksFinancial(ctx: SmsContext): Boolean =
        Fields.AMOUNT.containsMatchIn(ctx.normalized) &&
            Regex("\\b(debited|credited|spent|paid|sent|received|withdrawn|deposited|transferred|purchase|txn|transaction|refund|reversed|reversal|debit|credit)\\b")
                .containsMatchIn(ctx.lower)
}

/** Field extractors shared by every rule. Each is independent and individually testable. */
object Fields {
    val AMOUNT = Regex("(?i)(?:INR|Rs\\.?|₹)\\s*([0-9][0-9,]*(?:\\.[0-9]{1,2})?)")

    fun amounts(text: String): List<Pair<IntRange, Long>> =
        AMOUNT.findAll(text).mapNotNull { m -> Money.parseToPaise(m.groupValues[1])?.let { m.range to it } }.toList()

    private val BALANCE = Regex(
        "(?i)(?:avl\\.?\\s*bal(?:ance)?|available\\s+bal(?:ance)?|avbl\\s+bal|a/c\\s+bal(?:ance)?|bal(?:ance)?\\s*(?:is|:)|total\\s+bal(?:ance)?)\\s*(?:is|:|-)?\\s*(?:INR|Rs\\.?|₹)?\\s*([0-9][0-9,]*(?:\\.[0-9]{1,2})?)"
    )

    fun balance(text: String): Pair<IntRange, Long>? =
        BALANCE.find(text)?.let { m -> Money.parseToPaise(m.groupValues[1])?.let { m.groups[1]!!.range to it } }

    private val CHARGE = Regex("(?i)(?:charges?|fee|fees)\\s*(?:of|:)?\\s*(?:INR|Rs\\.?|₹)\\s*([0-9][0-9,]*(?:\\.[0-9]{1,2})?)")
    private val GST = Regex("(?i)(?:GST|IGST|CGST|SGST)\\s*(?:of|:)?\\s*(?:INR|Rs\\.?|₹)\\s*([0-9][0-9,]*(?:\\.[0-9]{1,2})?)")

    fun fee(text: String): Pair<IntRange, Long>? =
        CHARGE.find(text)?.let { m -> Money.parseToPaise(m.groupValues[1])?.let { m.groups[1]!!.range to it } }

    fun gst(text: String): Pair<IntRange, Long>? =
        GST.find(text)?.let { m -> Money.parseToPaise(m.groupValues[1])?.let { m.groups[1]!!.range to it } }

    private val ACCOUNT = listOf(
        Regex("(?i)(?:a/c|acct|account|ac)\\s*(?:no\\.?|number)?\\s*(?:ending(?:\\s+with)?|ending\\s+in)?\\s*[:.]?\\s*(?:[xX*]+|\\.{2,})\\s*-?([0-9]{3,6})\\b"),
        Regex("(?i)(?:a/c|acct|account)\\s*(?:no\\.?)?\\s*(?:ending(?:\\s+with|\\s+in)?)\\s*([0-9]{3,6})\\b"),
        Regex("(?i)\\bcard\\s*(?:no\\.?)?\\s*(?:ending(?:\\s+with|\\s+in)?)?\\s*[:.]?\\s*(?:[xX*]+)?\\s*-?([0-9]{4})\\b"),
        Regex("(?i)\\b(?:[xX*]{2,})([0-9]{3,6})\\b"),
    )

    /** The first account-like reference in the text, as its trailing digits only. */
    fun accountTail(text: String): Pair<IntRange, String>? {
        ACCOUNT.forEach { r -> r.find(text)?.let { return it.groups[1]!!.range to it.groupValues[1].takeLast(4) } }
        return null
    }

    fun allAccountTails(text: String): List<Pair<IntRange, String>> =
        ACCOUNT.flatMap { r -> r.findAll(text).map { it.groups[1]!!.range to it.groupValues[1].takeLast(4) }.toList() }
            .distinctBy { it.first.first }.sortedBy { it.first.first }

    private val REFERENCE = Regex(
        "(?i)(?:upi\\s*ref(?:erence)?(?:\\s*no\\.?)?|ref(?:erence)?\\s*(?:no|num|number|id)?\\.?|rrn|utr(?:\\s*no\\.?)?|txn\\s*(?:id|no\\.?)|transaction\\s*(?:id|ref(?:erence)?|no\\.?))\\s*[:.#-]?\\s*([A-Za-z0-9]{6,30})"
    )

    fun reference(text: String): String? =
        REFERENCE.findAll(text).map { it.groupValues[1] }.firstOrNull { it.any(Char::isDigit) }

    private val UPI_ID = Regex("\\b([a-zA-Z0-9._-]{2,64}@[a-zA-Z][a-zA-Z0-9]{1,30})\\b")
    fun upiId(text: String): String? = UPI_ID.find(text)?.groupValues?.get(1)?.takeIf { !it.contains("..") && !it.endsWith(".com") }

    private val COUNTERPARTY = listOf(
        // "at AMAZON PAY on", "at SWIGGY.", "at Big Bazaar via"
        Regex("(?i)\\bat\\s+([A-Za-z0-9&'._*/ -]{2,60}?)(?=\\s+(?:on|via|using|for|ref|txn|avl|upi|dated|with|has|is|was|towards)\\b|\\s*[;,(]|\\.(?:\\s|$)|$)"),
        // "to Ramesh Kumar via UPI", "trf to SWIGGY Ref", "paid to xyz@okicici"
        Regex("(?i)\\b(?:trf|transfer(?:red)?|sent|paid|payment|transferred)?\\s*to\\s+(?:vpa\\s+)?([A-Za-z0-9&'._@*/ -]{2,60}?)(?=\\s+(?:on|via|using|ref|upi|for|txn|avl|dated|from|has|is|was|towards)\\b|\\s*[;,(]|\\.(?:\\s|$)|$)"),
        // "from RAHUL SHARMA", "credited by NEFT from ACME CORP"
        Regex("(?i)\\b(?:from|by)\\s+(?:vpa\\s+)?([A-Za-z0-9&'._@*/ -]{2,60}?)(?=\\s+(?:on|via|using|ref|upi|for|txn|avl|dated|to|from|has|is|was|towards)\\b|\\s*[;,(]|\\.(?:\\s|$)|$)"),
        // "Info: UPI/P2M/123456/Zomato" or "Info: NEFT-ACME"
        Regex("(?i)\\binfo[:\\s-]+([A-Za-z0-9&'._@*/ -]{2,80}?)(?=\\s*[;,(]|\\.(?:\\s|$)|\\s+avl|$)"),
    )

    private val NOT_A_NAME = Regex(
        "(?i)^(your|you|a/c|ac|acct|account|card|the|beneficiary|bank|upi|imps|neft|rtgs|ref|xx\\d+|[xX*]+\\d+|\\d+|rs|inr|self|mobile|registered)\\b.*"
    )

    fun counterparty(text: String, direction: Direction): String? {
        val ordered = if (direction == Direction.CREDIT) listOf(2, 3, 1, 0) else listOf(0, 1, 3, 2)
        for (i in ordered) {
            for (m in COUNTERPARTY[i].findAll(text)) {
                val name = cleanName(m.groupValues[1]) ?: continue
                return name
            }
        }
        return null
    }

    /** Trims gateways' slash-separated info strings to the name part, and rejects non-names. */
    fun cleanName(raw: String): String? {
        var s = raw.trim().trim('.', '-', '/', '*', ' ')
        if (s.count { it == '/' } >= 2) {
            // "UPI/P2M/412345678901/ZOMATO LTD" -> the last alphabetic segment
            s = s.split('/').map { it.trim() }.lastOrNull { seg -> seg.count(Char::isLetter) >= 2 && !seg.equals("upi", true) && !seg.matches(Regex("(?i)p2[ma]|dr|cr")) } ?: return null
        }
        // "a/c linked to …" is an account phrase, not a name.
        if (s.length < 2 || NOT_A_NAME.matches(s) || raw.count { it == '/' } < 2 && NOT_A_NAME.matches(raw.trim())) return null
        if (s.count(Char::isLetter) < 2) return null
        return s.replace(Regex("\\s{2,}"), " ")
    }

    private val formats: List<Pair<DateTimeFormatter, Boolean>> = listOf(
        "dd-MM-yyyy HH:mm:ss" to true, "dd-MM-yyyy HH:mm" to true, "dd/MM/yyyy HH:mm:ss" to true, "dd/MM/yyyy HH:mm" to true,
        "dd-MMM-yyyy HH:mm" to true, "yyyy-MM-dd HH:mm:ss" to true,
        "dd-MM-yyyy" to false, "dd/MM/yyyy" to false, "dd-MM-yy" to false, "dd/MM/yy" to false, "dd.MM.yy" to false, "dd.MM.yyyy" to false,
        "dd-MMM-yyyy" to false, "dd-MMM-yy" to false, "dd MMM yyyy" to false, "dd MMM yy" to false, "ddMMMyy" to false,
        "ddMMMyyyy" to false, "yyyy-MM-dd" to false, "dd MMM, yyyy" to false, "MMM dd, yyyy" to false,
    ).map { (p, hasTime) ->
        DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern(p).toFormatter(Locale.ENGLISH) to hasTime
    }

    private val DATE_CANDIDATE = Regex(
        "(?i)\\b(\\d{1,2}[-/.](?:\\d{1,2}|[A-Za-z]{3})[-/.]\\d{2,4}(?:[ ,]+\\d{1,2}:\\d{2}(?::\\d{2})?)?|\\d{4}-\\d{2}-\\d{2}(?: \\d{2}:\\d{2}:\\d{2})?|\\d{1,2}\\s?[A-Za-z]{3},?\\s?\\d{2,4}|[A-Za-z]{3} \\d{1,2}, \\d{4})\\b"
    )

    private val TRAILING_TIME = Regex("[ ]+\\d{1,2}:\\d{2}(?::\\d{2})?$")

    private val TIME = Regex("\\b(\\d{1,2}):(\\d{2})(?::(\\d{2}))?\\s*(AM|PM|am|pm)?\\b")

    /**
     * The message's own date, if it has one that is plausible (not in the future relative to
     * arrival, not years old). An implausible date falls back to arrival time rather than
     * dating a purchase to 2031.
     */
    fun dateTime(text: String, receivedAt: Long, zone: ZoneId): Long? {
        val received = java.time.Instant.ofEpochMilli(receivedAt).atZone(zone)
        for (m in DATE_CANDIDATE.findAll(text)) {
            val raw = m.groupValues[1].replace(",", " ").replace(Regex("\\s+"), " ").trim()
            for ((fmt, hasTime) in formats) {
                val candidate = try {
                    if (hasTime) LocalDateTime.parse(raw, fmt)
                    else {
                        val d = LocalDate.parse(raw.replace(TRAILING_TIME, ""), fmt)
                        val t = TIME.find(text)?.let { tm ->
                            var h = tm.groupValues[1].toInt()
                            val min = tm.groupValues[2].toInt()
                            val ap = tm.groupValues[4].lowercase()
                            if (ap == "pm" && h < 12) h += 12
                            if (ap == "am" && h == 12) h = 0
                            if (h in 0..23 && min in 0..59) LocalTime.of(h, min) else null
                        }
                        if (t != null) d.atTime(t) else d.atTime(received.toLocalTime())
                    }
                } catch (_: DateTimeParseException) {
                    null
                } catch (_: java.time.DateTimeException) {
                    null
                } ?: continue
                val fixed = fixTwoDigitYear(candidate)
                val instant = fixed.atZone(zone)
                val plausible = !instant.isAfter(received.plusDays(1)) && instant.isAfter(received.minusYears(2))
                if (plausible) return instant.toInstant().toEpochMilli()
            }
        }
        return null
    }

    private fun fixTwoDigitYear(dt: LocalDateTime): LocalDateTime =
        if (dt.get(ChronoField.YEAR) < 100) dt.withYear(2000 + dt.year) else dt
}
