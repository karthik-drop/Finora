package com.finora.app.domain

import com.finora.app.core.Decimal4
import com.finora.app.core.Money
import com.finora.app.domain.merchant.LearningDecision
import com.finora.app.domain.merchant.MerchantLearningPolicy
import com.finora.app.domain.merchant.MerchantMatch
import com.finora.app.domain.merchant.MerchantNormalizer
import com.finora.app.domain.merchant.MerchantProfile
import com.finora.app.domain.model.MerchantStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MoneyTest {
    @Test fun parsesRupeeStringsToExactPaise() {
        assertEquals(12345678L, Money.parseToPaise("1,23,456.78"))
        assertEquals(50000L, Money.parseToPaise("₹ 500"))
        assertEquals(9950L, Money.parseToPaise("Rs.99.5"))
        assertEquals(100000L, Money.parseToPaise("INR 1,000"))
        assertEquals(1L, Money.parseToPaise("0.01"))
        assertNull(Money.parseToPaise("1.234"))
        assertNull(Money.parseToPaise("abc"))
        assertNull(Money.parseToPaise(""))
    }

    @Test fun formatsWithIndianGrouping() {
        assertEquals("₹1,23,45,678.90", Money.format(1234567890))
        assertEquals("₹999.00", Money.format(99900))
        assertEquals("₹0.05", Money.format(5))
        assertEquals("-₹1,000.00", Money.format(-100000))
        assertEquals("₹12.3L", Money.formatCompact(12_30_000_00))
    }

    @Test fun allocationAlwaysSumsExactly() {
        assertEquals(listOf(34L, 33L, 33L), Money.allocate(100, listOf(1, 1, 1)))
        val parts = Money.allocate(100_001, listOf(3, 7, 11, 13))
        assertEquals(100_001L, parts.sum())
        assertEquals(-10L, Money.allocate(-10, listOf(1, 2)).sum())
        assertEquals(listOf(0L, 0L), Money.allocate(0, listOf(5, 5)))
    }

    @Test fun decimal4ValuesUnitsTimesPriceInPaise() {
        // 12.345 units × ₹45.6789 = ₹563.906... -> 56391 paise
        assertEquals(56391L, Decimal4.valuePaise(Decimal4.parse("12.345")!!, Decimal4.parse("45.6789")!!))
        assertNull(Decimal4.parse("1.23456"))
        assertEquals("12.345", Decimal4.format(Decimal4.parse("12.345")!!))
    }
}

class MerchantLearningTest {
    private fun profile(count: Int, category: Long? = 7, shared: Boolean = false, key: String = "swiggy") =
        MerchantProfile(1, key, "Swiggy", category, count, MerchantLearningPolicy.statusFor(count), shared)

    @Test fun normalisesGatewayPrefixesAndCompanySuffixes() {
        assertEquals("swiggy", MerchantNormalizer.key("PYU*SWIGGY"))
        assertEquals("swiggy", MerchantNormalizer.key("SWIGGY*BANGALORE"))
        assertEquals("zomato", MerchantNormalizer.key("Zomato Ltd."))
        assertEquals("amazon pay", MerchantNormalizer.key("AMAZON PAY INDIA PRIVATE LIMITED"))
    }

    @Test fun firstSecondAndThirdTransactionsAskFourthAppliesAutomatically() {
        // Unknown merchant: ask, and a lookup is allowed.
        assertEquals(LearningDecision.AskNewMerchant, MerchantLearningPolicy.decide(MerchantLearningPolicy.match("SWIGGY", emptyList())))
        // Confirm #1
        var u = MerchantLearningPolicy.onConfirm(null, 7)
        assertEquals(1, u.confirmationCount)
        // 2nd transaction: still asks.
        assertTrue(MerchantLearningPolicy.decide(MerchantMatch.Exact(profile(1))) is LearningDecision.AskConfirm)
        u = MerchantLearningPolicy.onConfirm(profile(1), 7)
        assertEquals(2, u.confirmationCount)
        // 3rd transaction: still asks.
        assertTrue(MerchantLearningPolicy.decide(MerchantMatch.Exact(profile(2))) is LearningDecision.AskConfirm)
        u = MerchantLearningPolicy.onConfirm(profile(2), 7)
        assertEquals(3, u.confirmationCount)
        assertEquals(MerchantStatus.LEARNED, u.status)
        // 4th transaction: automatic, no question.
        assertTrue(MerchantLearningPolicy.decide(MerchantMatch.Exact(profile(3))) is LearningDecision.AutoApply)
        assertTrue(MerchantLearningPolicy.decide(MerchantMatch.Exact(profile(10))) is LearningDecision.AutoApply)
    }

    @Test fun aCorrectionIsNotAConfirmationOfTheOldMapping() {
        val u = MerchantLearningPolicy.onConfirm(profile(2, category = 7), 9)
        assertTrue(u.corrected)
        assertEquals(9L, u.categoryId)
        // The two earlier confirmations were of category 7 and do not carry over.
        assertEquals(1, u.confirmationCount)
        assertTrue(MerchantLearningPolicy.decide(MerchantMatch.Exact(profile(1, category = 9))) is LearningDecision.AskConfirm)
    }

    @Test fun correctingALearnedMerchantOverridesAndAppliesToTheFuture() {
        val u = MerchantLearningPolicy.onCorrect(profile(5, category = 7), 9)
        assertTrue(u.corrected)
        assertEquals(9L, u.categoryId)
        assertEquals(MerchantStatus.LEARNED, u.status)
        val next = MerchantLearningPolicy.decide(MerchantMatch.Exact(profile(u.confirmationCount, category = u.categoryId)))
        assertTrue(next is LearningDecision.AutoApply)
        assertEquals(9L, (next as LearningDecision.AutoApply).profile.categoryId)
    }

    @Test fun aSimilarButDifferentNameAsksForClarificationEvenWhenLearned() {
        val m = MerchantLearningPolicy.match("SWIGGYY", listOf(profile(8)))
        assertTrue(m is MerchantMatch.Similar)
        assertTrue(MerchantLearningPolicy.decide(m) is LearningDecision.AskClarify)
    }

    @Test fun anUnrelatedNameIsNew() {
        assertTrue(MerchantLearningPolicy.match("BIG BAZAAR", listOf(profile(8))) is MerchantMatch.None)
    }

    @Test fun aNameSharedByManyBusinessesAlwaysAsks() {
        assertTrue(MerchantLearningPolicy.decide(MerchantMatch.Exact(profile(9, shared = true))) is LearningDecision.AskClarify)
    }

    @Test fun anAliasConfirmedByTheUserIsAnExactMatch() {
        val m = MerchantLearningPolicy.match("BUNDL TECHNOLOGIES", listOf(profile(5)), mapOf("bundl technologies" to 1L))
        assertTrue(m is MerchantMatch.Exact)
    }

    @Test fun aLearnedMerchantWithoutACategoryStillAsks() {
        assertTrue(MerchantLearningPolicy.decide(MerchantMatch.Exact(profile(4, category = null))) is LearningDecision.AskConfirm)
    }
}
