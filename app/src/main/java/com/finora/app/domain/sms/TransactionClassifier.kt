package com.finora.app.domain.sms

import com.finora.app.domain.model.Direction
import com.finora.app.domain.model.PaymentMethod
import com.finora.app.domain.model.ReviewReason
import com.finora.app.domain.model.TxnType

/** What the classifier knows about the user, all from local data. */
data class ClassifierContext(
    /** Trailing digits of the user's own accounts and cards. */
    val ownAccountTails: Set<String> = emptySet(),
    /** The user's own UPI ids, lower case. */
    val ownUpiIds: Set<String> = emptySet(),
    /** Lower-cased names the user goes by, for "transfer to SELF / to <own name>". */
    val ownNames: Set<String> = emptySet(),
    /** Normalised names (and aliases) the user has confirmed as merchants: never a person. */
    val knownMerchantKeys: Set<String> = emptySet(),
)

data class Classification(
    val type: TxnType,
    /** Null when the type is certain enough to need no review. */
    val reviewReason: ReviewReason?,
    /** A plain sentence for the detail screen: why this type was chosen. */
    val explanation: String,
)

/**
 * Turns a parsed message into a transaction TYPE. Deliberately conservative: when a debit or
 * credit could be several things (a UPI payment to a person could be a gift, a loan, or a
 * share of dinner), it is typed as a neutral person payment and sent for review rather than
 * guessed into income or expense.
 */
object TransactionClassifier {

    fun classify(sms: ParsedSms, ctx: ClassifierContext): Classification {
        val k = sms.keywords
        val debit = sms.direction == Direction.DEBIT
        val lowerBody = sms.body.lowercase()

        if (sms.isReversal) return Classification(TxnType.REVERSAL, null, "The message says the transaction was reversed.")
        if (sms.isRefund) return Classification(TxnType.REFUND, null, "The message says a refund was credited.")
        if (Keyword.CARD_BILL in k) return Classification(TxnType.CARD_BILL_PAYMENT, null, "Credit-card bill payment: money moving between your own accounts, not spending.")
        if (Keyword.ATM in k) return Classification(TxnType.ATM_WITHDRAWAL, null, "Cash withdrawn at an ATM. Spending the cash is recorded separately.")
        if (Keyword.CASH_DEPOSIT in k && !debit) return Classification(TxnType.CASH_DEPOSIT, null, "Cash deposited into your account.")

        if (isOwnTransfer(sms, ctx)) {
            return Classification(TxnType.SELF_TRANSFER, null, "The other side is one of your own accounts, so this is neither income nor an expense.")
        }

        if (Keyword.LOAN in k && Keyword.DISBURSED in k && !debit) {
            return Classification(TxnType.LOAN_DISBURSEMENT, null, "A loan was disbursed to you. This is borrowed money, not income.")
        }
        if ((Keyword.EMI in k || Keyword.LOAN in k) && debit) {
            return Classification(TxnType.LOAN_REPAYMENT, ReviewReason.CLASSIFICATION, "Looks like a loan EMI. Link it to a loan to split principal from interest.")
        }
        if (Keyword.INVESTMENT in k) {
            return if (debit) Classification(TxnType.INVESTMENT_PURCHASE, null, "Paid to an investment platform: moves money into investments, not an expense.")
            else Classification(TxnType.INVESTMENT_REDEMPTION, null, "Received from an investment platform: a withdrawal of your own investment.")
        }
        if (Keyword.INTEREST in k && !debit && !Regex("loan|emi").containsMatchIn(lowerBody)) {
            return Classification(TxnType.INTEREST_EARNED, null, "Interest credited by the bank.")
        }
        if (Keyword.SALARY in k && !debit) return Classification(TxnType.SALARY, null, "The message mentions salary.")

        if (debit && (Keyword.CHARGES in k || Keyword.GST in k) && sms.counterparty == null && sms.method != PaymentMethod.UPI) {
            return if (Keyword.GST in k && Keyword.CHARGES !in k) Classification(TxnType.TAX, null, "Tax debited by the bank.")
            else Classification(TxnType.BANK_FEE, null, "A bank charge.")
        }

        // UPI to/from what looks like a person: purpose unknown, never guessed.
        if (sms.method == PaymentMethod.UPI || sms.method == PaymentMethod.IMPS || sms.method == PaymentMethod.NEFT) {
            val knownMerchant = sms.counterparty?.let { com.finora.app.domain.merchant.MerchantNormalizer.key(it) } in ctx.knownMerchantKeys
            if (!knownMerchant && looksLikePerson(sms)) {
                return if (debit) Classification(TxnType.PERSON_PAYMENT, ReviewReason.PURPOSE, "Paid to a person. Tell Finora whether this was an expense, money lent, a repayment or a shared bill.")
                else Classification(TxnType.PERSON_RECEIPT, ReviewReason.PURPOSE, "Money from a person. It might be income, a repayment of money you lent, or a bill settlement.")
            }
        }

        return if (debit) {
            if (sms.counterparty == null) Classification(TxnType.EXPENSE, ReviewReason.UNKNOWN_RECIPIENT, "A debit with no merchant named in the message.")
            else Classification(TxnType.EXPENSE, null, "A payment to a merchant.")
        } else {
            Classification(TxnType.INCOME, ReviewReason.CLASSIFICATION, "Money received. Confirm it is income and not a transfer or a repayment.")
        }
    }

    private fun isOwnTransfer(sms: ParsedSms, ctx: ClassifierContext): Boolean {
        val other = sms.counterpartyAccountTail
        if (other != null && other in ctx.ownAccountTails && other != sms.accountTail) return true
        val upi = sms.upiId?.lowercase()
        if (upi != null && upi in ctx.ownUpiIds) return true
        val cp = sms.counterparty?.lowercase()?.trim() ?: return false
        if (cp == "self" || cp.startsWith("self ")) return true
        return ctx.ownNames.any { it.isNotBlank() && cp == it }
    }

    private val BUSINESS_HINTS = Regex(
        "(?i)\\b(ltd|limited|pvt|private|llp|inc|corp|store|stores|mart|bazaar|restaurant|cafe|hotel|foods?|services|enterprises|traders|pharma|medical|hospital|petrol|fuel|bharat ?pe|paytm|razorpay|payu|billdesk|cashfree|ccavenue|amazon|flipkart|swiggy|zomato|uber|ola|irctc|jio|airtel|bescom|electricity|recharge|insurance|technologies|solutions|retail|super ?market|coffee|tea|chai|cafe|kitchen|bakery|bakers|sweets|pharmacy|medicals?|chemists?|clinic|salon|studio|cinemas?|petroleum|fuels|motors|resto|dhaba|biryani|pizza|burger|fresh|organic|agency|agencies|electronics|mobiles|textiles|fashion|jewell?ers|garments|hardware|books?|stationery|travels|tours|hotels?|lodge|canteen|juice|dairy|provisions|general|kirana)\\b"
    )

    /** A VPA with a merchant-style handle or business words is a merchant; a plain name is a person. */
    fun looksLikePerson(sms: ParsedSms): Boolean {
        val name = sms.counterparty ?: sms.upiId ?: return false
        if (BUSINESS_HINTS.containsMatchIn(name)) return false
        val upi = sms.upiId
        if (upi != null) {
            val handle = upi.substringBefore('@')
            if (Regex("(?i)(merchant|pay|shop|store|bharatpe|razorpay|paytmqr|q\\d{6,})").containsMatchIn(handle)) return false
            if (handle.all { it.isDigit() } && handle.length == 10) return true
        }
        if (Regex("(?i)p2m").containsMatchIn(sms.body)) return false
        if (Regex("(?i)p2a|p2p").containsMatchIn(sms.body)) return true
        val cp = sms.counterparty
        // Only a VPA to go on: a personal handle ("ramesh.k", "priya92") reads as a person;
        // business handles were filtered out above.
        if (cp == null || cp.contains('@')) {
            val handle = (upi ?: cp)?.substringBefore('@') ?: return false
            return handle.count(Char::isLetter) >= 3 && handle.all { it.isLetterOrDigit() || it == '.' || it == '_' }
        }
        // A person is "first last" (2–4 plain words). A single word ("DMART", "SWIGGY") is far
        // more often a merchant, and merchant learning will still ask the user to confirm it.
        val words = cp.split(Regex("\\s+")).filter { it.isNotBlank() }
        return words.size in 2..4 && words.all { w -> w.all { it.isLetter() || it == '.' } }
    }
}
