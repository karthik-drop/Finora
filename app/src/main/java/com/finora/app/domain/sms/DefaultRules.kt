package com.finora.app.domain.sms

import com.finora.app.domain.model.Direction
import com.finora.app.domain.model.PaymentMethod

/**
 * The shipped rules, most specific first. Each one is a recogniser for a family of wordings
 * used by Indian banks, card issuers and UPI apps; the shared field extractors in [Fields] do
 * the reading. A rule returning null means "not my wording", and the next one is tried.
 */
object DefaultRules {

    fun all(): List<Pair<String, SmsRule>> = listOf(
        "card_bill_payment" to SmsRule { ctx ->
            val l = ctx.lower
            val isCardBill = Regex("(payment|paid).{0,60}(received|credited|towards|against).{0,40}credit ?card|credit ?card (bill|dues?) (payment|paid)|towards your .{0,20}card (ending|xx)").containsMatchIn(l) ||
                Regex("\\b(cred|card bill|credit card bill|ccbill|bbps.{0,20}credit card)\\b").containsMatchIn(l) && Regex("\\b(debited|paid|sent)\\b").containsMatchIn(l)
            if (!isCardBill) return@SmsRule null
            // From the card's side it is a credit; from the bank's side a debit.
            val direction = if (Regex("\\b(received|credited)\\b").containsMatchIn(l) && !Regex("\\bdebited\\b").containsMatchIn(l)) Direction.CREDIT else Direction.DEBIT
            build(ctx, "card_bill_payment", direction, extra = setOf(Keyword.CARD_BILL), creditCardAccount = direction == Direction.CREDIT)
        },
        "atm_withdrawal" to SmsRule { ctx ->
            if (!Regex("\\b(atm|cash withdrawal|withdrawn at|cash wdl|atm wdl|nwd)\\b").containsMatchIn(ctx.lower)) return@SmsRule null
            if (!Regex("\\b(withdrawn|withdrawal|debited|wdl)\\b").containsMatchIn(ctx.lower)) return@SmsRule null
            build(ctx, "atm_withdrawal", Direction.DEBIT, method = PaymentMethod.ATM, extra = setOf(Keyword.ATM))
        },
        "reversal" to SmsRule { ctx ->
            if (!Regex("\\b(reversed|reversal|reverted|refunded back|credited back)\\b").containsMatchIn(ctx.lower)) return@SmsRule null
            build(ctx, "reversal", Direction.CREDIT, reversal = true)
        },
        "refund" to SmsRule { ctx ->
            if (!Regex("\\brefund").containsMatchIn(ctx.lower)) return@SmsRule null
            if (!Regex("\\b(credited|processed|initiated|received|refunded)\\b").containsMatchIn(ctx.lower)) return@SmsRule null
            build(ctx, "refund", Direction.CREDIT, refund = true)
        },
        "card_spend" to SmsRule { ctx ->
            val l = ctx.lower
            val cardish = Regex("\\b(credit card|debit card|card (no\\.? )?(ending|xx|\\*)|card x+\\d|on your .{0,25}card|card \\d{4})").containsMatchIn(l)
            val spendish = Regex("\\b(spent|purchase|used|txn of|transaction of|debited|charged)\\b").containsMatchIn(l)
            if (!cardish || !spendish || Regex("\\bcredited\\b").containsMatchIn(l) && !Regex("\\b(spent|debited|purchase)\\b").containsMatchIn(l)) return@SmsRule null
            val creditCard = Regex("\\bcredit card\\b").containsMatchIn(l)
            build(ctx, "card_spend", Direction.DEBIT, method = PaymentMethod.CARD, card = true, creditCardAccount = creditCard)
        },
        "debit" to SmsRule { ctx ->
            val direction = firstDirection(ctx.lower) ?: return@SmsRule null
            if (direction != Direction.DEBIT) return@SmsRule null
            build(ctx, "debit", Direction.DEBIT)
        },
        "credit" to SmsRule { ctx ->
            val direction = firstDirection(ctx.lower) ?: return@SmsRule null
            if (direction != Direction.CREDIT) return@SmsRule null
            build(ctx, "credit", Direction.CREDIT)
        },
    )

    private val DEBIT_WORDS = Regex("\\b(debited|debit of|spent|paid|sent|withdrawn|transferred|purchase|dr\\.?)\\b")
    private val CREDIT_WORDS = Regex("\\b(credited|credit of|received|deposited|cr\\.?)\\b")

    /** Whichever direction the message states FIRST — "debited ... credited to beneficiary" is a debit. */
    fun firstDirection(lower: String): Direction? {
        val d = DEBIT_WORDS.find(lower)?.range?.first
        val c = CREDIT_WORDS.find(lower)?.range?.first
        return when {
            d == null && c == null -> null
            c == null -> Direction.DEBIT
            d == null -> Direction.CREDIT
            d < c -> Direction.DEBIT
            else -> Direction.CREDIT
        }
    }

    private fun build(
        ctx: SmsContext,
        ruleId: String,
        direction: Direction,
        method: PaymentMethod? = null,
        card: Boolean = false,
        refund: Boolean = false,
        reversal: Boolean = false,
        creditCardAccount: Boolean = false,
        extra: Set<Keyword> = emptySet(),
    ): ParsedSms? {
        val text = ctx.normalized
        val balance = Fields.balance(text)
        val fee = Fields.fee(text)
        val gst = Fields.gst(text)
        val excluded = listOfNotNull(balance?.first, fee?.first, gst?.first)
        // The transaction amount: the first amount that is not the balance, a fee or the GST.
        val amount = Fields.amounts(text).firstOrNull { (range, _) -> excluded.none { it.first in range || range.first in it } }
            ?.second ?: return null

        val tails = Fields.allAccountTails(text)
        val own = tails.firstOrNull()?.second
        // In "A/c XX1234 debited ... credited to A/c XX5678", the second number is the other side.
        val other = tails.drop(1).firstOrNull { it.second != own }?.second
        val upi = Fields.upiId(text)
        val l = ctx.lower
        val resolvedMethod = method ?: when {
            Regex("\\bupi\\b|vpa|@ok|@ybl|@paytm|@axl|@ibl|@upi").containsMatchIn(l) || upi != null -> PaymentMethod.UPI
            Regex("\\bimps\\b").containsMatchIn(l) -> PaymentMethod.IMPS
            Regex("\\bneft\\b").containsMatchIn(l) -> PaymentMethod.NEFT
            Regex("\\brtgs\\b").containsMatchIn(l) -> PaymentMethod.RTGS
            Regex("\\b(nach|ecs|si |standing instruction|autopay|auto-debit|mandate)").containsMatchIn(l) -> PaymentMethod.AUTO_DEBIT
            Regex("\\b(net ?banking|netbanking)\\b").containsMatchIn(l) -> PaymentMethod.NETBANKING
            Regex("\\bcheque|chq\\b").containsMatchIn(l) -> PaymentMethod.CHEQUE
            Regex("\\bcard\\b").containsMatchIn(l) -> PaymentMethod.CARD
            else -> null
        }

        val keywords = buildSet {
            addAll(extra)
            if (Regex("\\bsalary|\\bsal\\b|payroll").containsMatchIn(l)) add(Keyword.SALARY)
            if (Regex("cash deposit|deposited in cash|cash dep\\b|cdm").containsMatchIn(l)) add(Keyword.CASH_DEPOSIT)
            if (Regex("\\bloan\\b").containsMatchIn(l)) add(Keyword.LOAN)
            if (Regex("\\bemi\\b").containsMatchIn(l)) add(Keyword.EMI)
            if (Regex("\\bdisburs").containsMatchIn(l)) add(Keyword.DISBURSED)
            if (Regex("\\binterest\\b|int\\.? ?(pd|paid|cr|credited)").containsMatchIn(l)) add(Keyword.INTEREST)
            if (Regex("\\b(charges?|fee)\\b").containsMatchIn(l)) add(Keyword.CHARGES)
            if (Regex("\\b(gst|igst|cgst|sgst)\\b").containsMatchIn(l)) add(Keyword.GST)
            if (Regex("\\b(mutual fund|sip\\b|zerodha|groww|upstox|kuvera|coin by|nse clearing|indian clearing|iccl|bse ltd|cams|kfintech|karvy|smallcase|paytm money|angel one)").containsMatchIn(l)) add(Keyword.INVESTMENT)
            if (Regex("\\b(autopay|auto-debit|nach|mandate|standing instruction)").containsMatchIn(l)) add(Keyword.AUTOPAY)
        }

        val counterparty = when {
            ruleId == "atm_withdrawal" -> null
            else -> Fields.counterparty(text, direction)?.takeIf { cp ->
                // An account mask is not a name.
                !cp.matches(Regex("(?i)(a/c|ac|account)?\\s*[xX*]*\\d+"))
            }
        }

        val dated = Fields.dateTime(text, ctx.receivedAt, ctx.zone)
        return ParsedSms(
            amountPaise = amount,
            direction = direction,
            occurredAt = dated ?: ctx.receivedAt,
            dateFromMessage = dated != null,
            counterparty = counterparty,
            upiId = upi,
            accountTail = own,
            counterpartyAccountTail = other,
            reference = Fields.reference(text),
            method = resolvedMethod,
            balancePaise = balance?.second,
            feePaise = fee?.second ?: 0,
            taxPaise = gst?.second ?: 0,
            isRefund = refund,
            isReversal = reversal,
            isCard = card || resolvedMethod == PaymentMethod.CARD,
            isCreditCardAccount = creditCardAccount,
            keywords = keywords,
            ruleId = ruleId,
            sender = ctx.sender,
            body = ctx.body,
        )
    }
}
