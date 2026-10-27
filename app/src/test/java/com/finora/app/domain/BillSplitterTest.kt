package com.finora.app.domain

import com.finora.app.domain.model.SplitMode
import com.finora.app.domain.split.BillSplitter
import com.finora.app.domain.split.SplitInput
import com.finora.app.domain.split.SplitItem
import com.finora.app.domain.split.SplitResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BillSplitterTest {
    private val people = listOf("You", "Asha", "Ben")

    private fun ok(input: SplitInput) = BillSplitter.split(input).let { assertTrue("$it", it is SplitResult.Ok); it as SplitResult.Ok }

    @Test fun itemizedSplitSharesTaxAndServiceProportionally() {
        val r = ok(SplitInput(people, SplitMode.ITEMIZED, items = listOf(
            SplitItem("Pizza", 600_00, listOf(0, 1, 2)), // 200 each
            SplitItem("Beer", 400_00, listOf(1)),
            SplitItem("Salad", 200_00, listOf(2)),
        ), taxPaise = 120_00, servicePaise = 60_00, totalPaise = 1_380_00))
        // Subtotals 200, 600, 400 of 1200 -> tax 20/60/40, service 10/30/20.
        assertEquals(listOf(230_00L, 690_00L, 460_00L), r.shares.map { it.totalPaise })
        assertEquals(1_380_00L, r.shares.sumOf { it.totalPaise })
    }

    @Test fun roundingIsToExactPaiseAndAlwaysReconciles() {
        val r = ok(SplitInput(people, SplitMode.EQUAL, subtotalPaise = 100_00, taxPaise = 5_00, totalPaise = 105_00))
        assertEquals(105_00L, r.shares.sumOf { it.totalPaise })
        assertEquals(listOf(3334L, 3333L, 3333L), r.shares.map { it.subtotalPaise })
    }

    @Test fun discountIsSharedProportionally() {
        val r = ok(SplitInput(listOf("A", "B"), SplitMode.CUSTOM, customSubtotals = listOf(300_00, 100_00), subtotalPaise = 400_00, discountPaise = 40_00, totalPaise = 360_00))
        assertEquals(listOf(30_00L, 10_00L), r.shares.map { it.discountPaise })
        assertEquals(listOf(270_00L, 90_00L), r.shares.map { it.totalPaise })
    }

    @Test fun aMismatchedTotalIsRefusedUnlessAcceptedAsRounding() {
        val input = SplitInput(people, SplitMode.EQUAL, subtotalPaise = 999_60, totalPaise = 1_000_00)
        assertTrue(BillSplitter.split(input) is SplitResult.Invalid)
        val r = ok(input.copy(acceptedRoundingPaise = 40))
        assertEquals(1_000_00L, r.shares.sumOf { it.totalPaise })
    }

    @Test fun unassignedItemsAndBadCustomAmountsAreRefused() {
        assertTrue(BillSplitter.split(SplitInput(people, SplitMode.ITEMIZED, items = listOf(SplitItem("Tea", 50_00, emptyList())), totalPaise = 50_00)) is SplitResult.Invalid)
        assertTrue(BillSplitter.split(SplitInput(listOf("A", "B"), SplitMode.CUSTOM, customSubtotals = listOf(10_00, 10_00), subtotalPaise = 30_00, totalPaise = 30_00)) is SplitResult.Invalid)
        assertTrue(BillSplitter.split(SplitInput(emptyList(), SplitMode.EQUAL, subtotalPaise = 1, totalPaise = 1)) is SplitResult.Invalid)
    }

    @Test fun partialSettlementsReduceWhatIsOwedAndCannotExceedIt() {
        val bal = BillSplitter.balance(690_00, isPayer = false, settlements = listOf(200_00, 90_00))
        assertEquals(400_00L, bal.remainingPaise)
        assertNull(BillSplitter.validateSettlement(bal, 400_00))
        assertNotNull(BillSplitter.validateSettlement(bal, 400_01))
        assertNotNull(BillSplitter.validateSettlement(bal, 0))
        assertTrue(BillSplitter.balance(690_00, false, listOf(690_00)).isSettled)
    }

    @Test fun thePayerOwesNothing() {
        assertEquals(0L, BillSplitter.balance(230_00, isPayer = true, settlements = emptyList()).owedPaise)
    }
}
