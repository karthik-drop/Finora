package com.finora.app.sms

import com.finora.app.domain.model.Direction
import com.finora.app.domain.model.ReviewReason
import com.finora.app.domain.model.TxnType
import com.finora.app.domain.sms.ClassifierContext
import com.finora.app.domain.sms.Deduplication
import com.finora.app.domain.sms.ParsedSms
import com.finora.app.domain.sms.SmsParseResult
import com.finora.app.domain.sms.SmsParser
import com.finora.app.domain.sms.TransactionClassifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class ClassifierAndDedupTest {
    private val parser = SmsParser(ZoneId.of("Asia/Kolkata"))
    private val t0 = 1_773_475_200_000L

    private fun p(body: String) = (parser.parse("AX-BANK", body, t0) as SmsParseResult.Parsed).sms
    private fun classify(body: String, ctx: ClassifierContext = ClassifierContext()) = TransactionClassifier.classify(p(body), ctx)

    @Test fun transferToOwnAccountIsNeitherIncomeNorExpense() {
        val c = classify("Rs 10,000.00 debited from A/c XX1234 and credited to A/c XX5678 via IMPS. Ref 512345678901.", ClassifierContext(ownAccountTails = setOf("1234", "5678")))
        assertEquals(TxnType.SELF_TRANSFER, c.type)
        assertEquals(TxnType.Flow.NEUTRAL, c.type.countsAs)
    }

    @Test fun transferToSomeoneElsesAccountIsNotSelfTransfer() {
        val c = classify("Rs 10,000.00 debited from A/c XX1234 and credited to A/c XX9999 via IMPS. Ref 512345678901.", ClassifierContext(ownAccountTails = setOf("1234")))
        assertNotEquals(TxnType.SELF_TRANSFER, c.type)
    }

    @Test fun paymentToOwnUpiIdIsSelfTransfer() {
        val c = classify("Rs 2000 debited from A/c XX1234 to VPA me.myself@okaxis UPI Ref 401234567890", ClassifierContext(ownUpiIds = setOf("me.myself@okaxis")))
        assertEquals(TxnType.SELF_TRANSFER, c.type)
    }

    @Test fun salaryCreditIsSalary() {
        assertEquals(TxnType.SALARY, classify("A/c XX5678 credited with INR 85,000.00 by NEFT from ACME CORP towards SALARY MAR26").type)
    }

    @Test fun upiToAPersonAsksForThePurposeAndIsNotAnExpense() {
        val c = classify("Rs 500.00 sent to RAHUL SHARMA via UPI from A/c XX1234. UPI Ref 401299999999")
        assertEquals(TxnType.PERSON_PAYMENT, c.type)
        assertEquals(ReviewReason.PURPOSE, c.reviewReason)
        assertEquals(TxnType.Flow.NEUTRAL, c.type.countsAs)
    }

    @Test fun upiFromAPersonIsNotAssumedToBeIncome() {
        val c = classify("Rs 1500.00 credited to a/c XX9012 by VPA ramesh.k@oksbi (UPI Ref no 607212345601).")
        assertEquals(TxnType.PERSON_RECEIPT, c.type)
        assertEquals(TxnType.Flow.NEUTRAL, c.type.countsAs)
    }

    @Test fun upiToAMerchantIsAnExpense() {
        val c = classify("Rs.450.00 debited from A/c XX1234 to VPA swiggy.instamart@icici (UPI Ref No 407312345678).")
        assertEquals(TxnType.EXPENSE, c.type)
    }

    @Test fun cardBillPaymentIsNeutral() {
        assertEquals(TxnType.CARD_BILL_PAYMENT, classify("Payment of Rs 12,000.00 has been received towards your credit card XX4321.").type)
    }

    @Test fun atmIsNeutral() {
        assertEquals(TxnType.ATM_WITHDRAWAL, classify("Rs.5000 withdrawn from ATM using card XX8899 on 14-03-26.").type)
    }

    @Test fun emiDebitIsLoanRepaymentNotExpense() {
        val c = classify("EMI of Rs 15,234.00 for Loan A/c XX7788 debited from A/c XX1234 on 05-03-2026.")
        assertEquals(TxnType.LOAN_REPAYMENT, c.type)
        assertEquals(TxnType.Flow.NEUTRAL, c.type.countsAs)
    }

    @Test fun loanDisbursementIsNotIncome() {
        assertEquals(TxnType.LOAN_DISBURSEMENT, classify("Your personal loan of Rs 2,00,000.00 has been disbursed and credited to A/c XX1234.").type)
    }

    @Test fun investmentPlatformDebitIsNotAnExpense() {
        assertEquals(TxnType.INVESTMENT_PURCHASE, classify("Rs 5,000.00 debited from A/c XX1234 to ZERODHA BROKING LTD via NEFT.").type)
    }

    @Test fun refundReducesSpendingAndReversalCountsForNothing() {
        assertEquals(TxnType.Flow.EXPENSE_REDUCTION, classify("Refund of Rs.799.00 from MYNTRA has been credited to your card ending 4321.").type.countsAs)
        assertEquals(TxnType.Flow.NEUTRAL, classify("Your UPI txn of Rs 300.00 with Ref No 407300000111 has been reversed and credited to A/c XX1234.").type.countsAs)
    }

    @Test fun unexplainedCreditIsReviewedBeforeCountingAsIncome() {
        val c = classify("Your A/c XX1234 has been credited with Rs 3,000.00 on 10-03-2026.")
        assertEquals(TxnType.INCOME, c.type)
        assertEquals(ReviewReason.CLASSIFICATION, c.reviewReason)
    }

    // ---- duplicates ----

    @Test fun sameMessageTwiceHasTheSameFingerprint() {
        val body = "Rs 60 debited from A/c XX1234 to CHAI POINT via UPI."
        val a = p(body); val b = p(body)
        assertEquals(fp(a), fp(b))
    }

    @Test fun referenceFingerprintIgnoresWordingButNotDirection() {
        val bank = p("Rs.450.00 debited from A/c XX1234 to VPA swiggy@icici (UPI Ref No 407312345678).")
        val app = p("You paid Rs 450 to Swiggy. UPI Ref: 407312345678")
        assertEquals(fp(bank), fp(app))
        val reversal = p("Your UPI txn of Rs 450.00 with Ref No 407312345678 has been reversed and credited.")
        assertNotEquals(fp(bank), fp(reversal))
    }

    @Test fun crossFormatDuplicateWithinWindowIsCaught() {
        val a = Deduplication.Candidate(45000, Direction.DEBIT, t0, null, "1234")
        val b = Deduplication.Candidate(45000, Direction.DEBIT, t0 + 90_000, "407312345678", null)
        assertTrue(Deduplication.isProbableDuplicate(b, a))
    }

    @Test fun twoGenuinePaymentsOfTheSameAmountAreNotDuplicates() {
        val a = Deduplication.Candidate(45000, Direction.DEBIT, t0, "111111111111", "1234")
        val b = Deduplication.Candidate(45000, Direction.DEBIT, t0 + 60_000, "222222222222", "1234")
        assertFalse(Deduplication.isProbableDuplicate(b, a))
        val later = Deduplication.Candidate(45000, Direction.DEBIT, t0 + 20 * 60_000, null, "1234")
        assertFalse(Deduplication.isProbableDuplicate(later, a.copy(reference = null)))
        val otherAccount = Deduplication.Candidate(45000, Direction.DEBIT, t0, null, "9999")
        assertFalse(Deduplication.isProbableDuplicate(otherAccount, a.copy(reference = null)))
    }

    @Test fun refundIsNotADuplicateOfThePurchase() {
        val purchase = Deduplication.Candidate(79900, Direction.DEBIT, t0, null, "4321")
        val refund = Deduplication.Candidate(79900, Direction.CREDIT, t0 + 60_000, null, "4321")
        assertFalse(Deduplication.isProbableDuplicate(refund, purchase))
    }

    @Test fun parsedMessageWithNoCounterpartyLeavesItNull() {
        assertNull(p("Rs.5000 withdrawn from ATM using card XX8899.").counterparty)
    }

    private fun fp(s: ParsedSms) = Deduplication.fingerprint(s.reference, s.direction, s.amountPaise, s.accountTail, s.occurredAt, s.counterparty, s.body)
}
