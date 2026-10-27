package com.finora.app.sms

import com.finora.app.domain.model.Direction
import com.finora.app.domain.model.PaymentMethod
import com.finora.app.domain.sms.ParsedSms
import com.finora.app.domain.sms.SmsParseResult
import com.finora.app.domain.sms.SmsParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Representative wordings of Indian bank, card and UPI messages (account numbers, names and
 * references are made up). Each asserts the fields a real message of that shape carries.
 */
class SmsParserTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private val parser = SmsParser(zone)
    private val received = LocalDateTime.of(2026, 3, 14, 13, 5).atZone(zone).toInstant().toEpochMilli()

    private fun parse(body: String, sender: String = "AX-BANKSM"): ParsedSms =
        when (val r = parser.parse(sender, body, received)) {
            is SmsParseResult.Parsed -> r.sms
            else -> fail("expected a parse, got $r").let { error("unreachable") }
        }

    private fun at(y: Int, m: Int, d: Int, h: Int = 0, min: Int = 0) = LocalDateTime.of(y, m, d, h, min).atZone(zone).toInstant().toEpochMilli()

    @Test fun upiDebitWithVpaReferenceAndBalance() {
        val s = parse("Rs.450.00 debited from A/c XX1234 on 14-03-26 to VPA swiggy.instamart@icici (UPI Ref No 407312345678). Avl Bal Rs 12,345.67. Not you? Call 18002586161")
        assertEquals(45000L, s.amountPaise)
        assertEquals(Direction.DEBIT, s.direction)
        assertEquals("1234", s.accountTail)
        assertEquals("407312345678", s.reference)
        assertEquals(1234567L, s.balancePaise)
        assertEquals(PaymentMethod.UPI, s.method)
        assertEquals("swiggy.instamart@icici", s.upiId)
        assertTrue(s.dateFromMessage)
    }

    @Test fun cardSpendAtMerchantWithTime() {
        val s = parse("Spent INR 2,499.00 on your HDFC Bank Credit Card ending 4321 at AMAZON PAY INDIA on 2026-03-12 18:42:10. Avl Limit: INR 1,05,000.00")
        assertEquals(249900L, s.amountPaise)
        assertEquals(Direction.DEBIT, s.direction)
        assertEquals("4321", s.accountTail)
        assertEquals("AMAZON PAY INDIA", s.counterparty)
        assertTrue(s.isCard)
        assertTrue(s.isCreditCardAccount)
        assertEquals(at(2026, 3, 12, 18, 42) + 10_000, s.occurredAt)
    }

    @Test fun neftCreditFromCompany() {
        val s = parse("Dear Customer, your A/c XXXXXX5678 is credited with INR 85,000.00 on 01-Mar-2026 by NEFT from ACME TECHNOLOGIES PVT LTD towards SALARY MAR26. Avl bal: INR 97,345.67")
        assertEquals(8500000L, s.amountPaise)
        assertEquals(Direction.CREDIT, s.direction)
        assertEquals("5678", s.accountTail)
        assertEquals(PaymentMethod.NEFT, s.method)
        assertTrue(s.keywords.contains(com.finora.app.domain.sms.Keyword.SALARY))
        assertEquals("ACME TECHNOLOGIES PVT LTD", s.counterparty)
    }

    @Test fun upiCreditFromPerson() {
        val s = parse("Rs 1500.00 credited to a/c **9012 on 13/03/26 by a/c linked to VPA ramesh.k@oksbi (UPI Ref no 607212345601).")
        assertEquals(150000L, s.amountPaise)
        assertEquals(Direction.CREDIT, s.direction)
        assertEquals("ramesh.k@oksbi", s.upiId)
        assertEquals("607212345601", s.reference)
    }

    @Test fun atmWithdrawalHasNoMerchant() {
        val s = parse("Rs.5000 withdrawn from ATM using card XX8899 on 14-03-26 at HSR LAYOUT. Avl bal Rs.20,111.00")
        assertEquals(500000L, s.amountPaise)
        assertEquals("atm_withdrawal", s.ruleId)
        assertNull(s.counterparty)
        assertEquals(PaymentMethod.ATM, s.method)
    }

    @Test fun refundIsACreditFlaggedAsRefund() {
        val s = parse("Refund of Rs.799.00 from MYNTRA has been credited to your card ending 4321 on 10-03-2026.")
        assertEquals(79900L, s.amountPaise)
        assertEquals(Direction.CREDIT, s.direction)
        assertTrue(s.isRefund)
        assertEquals("MYNTRA", s.counterparty)
    }

    @Test fun reversalCarriesTheOriginalReference() {
        val s = parse("Your UPI txn of Rs 300.00 with Ref No 407300000111 has been reversed and credited to A/c XX1234.")
        assertTrue(s.isReversal)
        assertEquals(Direction.CREDIT, s.direction)
        assertEquals("407300000111", s.reference)
    }

    @Test fun cardBillPaymentIsRecognised() {
        val s = parse("Payment of Rs 12,000.00 has been received towards your credit card XX4321. Thank you.")
        assertEquals("card_bill_payment", s.ruleId)
        assertEquals(1200000L, s.amountPaise)
    }

    @Test fun chargesAndGstAreSeparatedFromTheAmount() {
        val s = parse("INR 590.00 debited from A/c XX1234 towards annual card fee. Charges INR 500.00 GST INR 90.00. Avl bal INR 9,410.00")
        assertEquals(59000L, s.amountPaise)
        assertEquals(50000L, s.feePaise)
        assertEquals(9000L, s.taxPaise)
        assertEquals(941000L, s.balancePaise)
    }

    @Test fun balanceIsNeverMistakenForTheAmount() {
        val s = parse("Avl Bal Rs 50,000.00. Rs 120.00 debited from A/c XX1234 for UPI txn to RAPIDO.")
        assertEquals(12000L, s.amountPaise)
        assertEquals(5000000L, s.balancePaise)
    }

    @Test fun messageWithoutDateUsesArrivalTime() {
        val s = parse("Rs 60 debited from A/c XX1234 to CHAI POINT via UPI.")
        assertEquals(received, s.occurredAt)
        assertTrue(!s.dateFromMessage)
    }

    @Test fun futureDateInMessageIsNotTrusted() {
        val s = parse("Rs 60 debited from A/c XX1234 on 14-03-2031 to CHAI POINT via UPI.")
        assertEquals(received, s.occurredAt)
    }

    @Test fun otpIsIgnored() {
        assertTrue(parser.parse("AX-BANK", "123456 is your OTP for txn of Rs 2,000.00 at FLIPKART. Do not share.", received) is SmsParseResult.Ignored)
    }

    @Test fun promotionIsIgnored() {
        assertTrue(parser.parse("AX-BANK", "Congratulations! You are eligible for a pre-approved loan of Rs 5,00,000. Apply now.", received) is SmsParseResult.Ignored)
    }

    @Test fun collectRequestIsNotAPayment() {
        assertTrue(parser.parse("AX-BANK", "Suresh has requested money from you. On approving, Rs 500.00 will be debited from your account.", received) is SmsParseResult.Ignored)
    }

    @Test fun failedTransactionIsIgnored() {
        assertTrue(parser.parse("AX-BANK", "Your transaction of Rs 899.00 at ZOMATO has failed. Amount debited, if any, will be refunded.", received) is SmsParseResult.Ignored)
    }

    @Test fun nonFinancialMessageIsIgnored() {
        assertTrue(parser.parse("JM-JIOINF", "Your data pack expires today. Recharge now.", received) is SmsParseResult.Ignored)
    }

    @Test fun malformedMessagesNeverThrow() {
        listOf("", "Rs.", "debited", "Rs 1,2,,3 debited ₹₹", "INR .50 credited", "\u0000\u0001", "A".repeat(5000) + " Rs 10 debited").forEach {
            parser.parse("X", it, received) // must not throw
        }
    }

    @Test fun gatewayInfoStringIsReducedToTheName() {
        val s = parse("Your A/c XX1234 is debited for Rs.349.00 on 11-03-2026. Info: UPI/P2M/412345678901/ZOMATO LTD")
        assertEquals("ZOMATO LTD", s.counterparty)
    }
}
