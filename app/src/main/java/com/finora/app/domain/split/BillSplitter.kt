package com.finora.app.domain.split

import com.finora.app.core.Money
import com.finora.app.domain.model.SplitMode

data class SplitItem(val label: String, val amountPaise: Long, val sharedBy: List<Int>)

data class SplitInput(
    /** Participant indexes are positions in this list. */
    val participants: List<String>,
    val mode: SplitMode,
    val items: List<SplitItem> = emptyList(),
    /** CUSTOM mode: each participant's share of the SUBTOTAL (before tax, discount, service). */
    val customSubtotals: List<Long> = emptyList(),
    /** EQUAL / CUSTOM: the subtotal being split. ITEMIZED derives it from the items. */
    val subtotalPaise: Long = 0,
    val taxPaise: Long = 0,
    val discountPaise: Long = 0,
    val servicePaise: Long = 0,
    /** The amount actually charged, as printed on the bill. */
    val totalPaise: Long,
    /** A difference the user has explicitly accepted (bill rounded to the rupee, etc.). */
    val acceptedRoundingPaise: Long = 0,
)

data class ParticipantShare(
    val index: Int,
    val subtotalPaise: Long,
    val taxPaise: Long,
    val discountPaise: Long,
    val servicePaise: Long,
    val totalPaise: Long,
)

sealed interface SplitResult {
    data class Ok(val shares: List<ParticipantShare>, val subtotalPaise: Long) : SplitResult
    data class Invalid(val message: String) : SplitResult
}

/**
 * Splits a bill to the exact paisa.
 *
 *  - Items are shared equally among the people they are assigned to.
 *  - Tax, service and discount are distributed in proportion to each person's subtotal.
 *  - Every distribution uses largest-remainder rounding, so the shares always add up to the
 *    exact total; no paisa is created or lost.
 *  - The parts must reconcile with the printed total. A difference is only allowed if the user
 *    has accepted it explicitly, and it is distributed like tax.
 */
object BillSplitter {

    fun split(input: SplitInput): SplitResult {
        val n = input.participants.size
        if (n == 0) return SplitResult.Invalid("Add at least one person.")
        if (listOf(input.taxPaise, input.discountPaise, input.servicePaise, input.totalPaise).any { it < 0 }) {
            return SplitResult.Invalid("Amounts cannot be negative.")
        }

        val subtotals: List<Long> = when (input.mode) {
            SplitMode.ITEMIZED -> {
                if (input.items.isEmpty()) return SplitResult.Invalid("Add at least one item.")
                val acc = LongArray(n)
                for (item in input.items) {
                    if (item.amountPaise < 0) return SplitResult.Invalid("“${item.label}” has a negative amount.")
                    val people = item.sharedBy.distinct()
                    if (people.isEmpty()) return SplitResult.Invalid("Assign “${item.label}” to at least one person.")
                    if (people.any { it !in 0 until n }) return SplitResult.Invalid("“${item.label}” is assigned to someone who is not on this bill.")
                    Money.allocate(item.amountPaise, people.map { 1L }).forEachIndexed { i, part -> acc[people[i]] += part }
                }
                acc.toList()
            }
            SplitMode.EQUAL -> Money.allocate(input.subtotalPaise, List(n) { 1L })
            SplitMode.CUSTOM -> {
                if (input.customSubtotals.size != n) return SplitResult.Invalid("Enter an amount for every person.")
                if (input.customSubtotals.any { it < 0 }) return SplitResult.Invalid("Amounts cannot be negative.")
                if (input.customSubtotals.sum() != input.subtotalPaise) {
                    return SplitResult.Invalid("Custom amounts add up to ${Money.format(input.customSubtotals.sum())}, not ${Money.format(input.subtotalPaise)}.")
                }
                input.customSubtotals
            }
        }
        val subtotal = subtotals.sum()
        if (input.discountPaise > subtotal + input.taxPaise + input.servicePaise) {
            return SplitResult.Invalid("The discount is larger than the bill.")
        }

        val computed = subtotal + input.taxPaise + input.servicePaise - input.discountPaise
        val difference = input.totalPaise - computed
        if (difference != input.acceptedRoundingPaise) {
            return SplitResult.Invalid(
                "Items and charges add up to ${Money.format(computed)} but the bill total is ${Money.format(input.totalPaise)} " +
                    "(difference ${Money.format(difference)}). Correct the amounts, or accept the difference as rounding."
            )
        }

        // Proportional to subtotal; with a zero subtotal (all items free) fall back to equal.
        val weights = if (subtotal == 0L) List(n) { 1L } else subtotals
        val tax = Money.allocate(input.taxPaise + input.acceptedRoundingPaise, weights)
        val service = Money.allocate(input.servicePaise, weights)
        val discount = Money.allocate(input.discountPaise, weights)
        val shares = (0 until n).map { i ->
            ParticipantShare(i, subtotals[i], tax[i], discount[i], service[i], subtotals[i] + tax[i] + service[i] - discount[i])
        }
        check(shares.sumOf { it.totalPaise } == input.totalPaise) { "split does not reconcile" }
        return SplitResult.Ok(shares, subtotal)
    }

    data class Balance(val owedPaise: Long, val settledPaise: Long) {
        val remainingPaise: Long get() = owedPaise - settledPaise
        val isSettled: Boolean get() = remainingPaise <= 0
    }

    /** What a participant owes the payer. The payer owes nobody for their own share. */
    fun balance(shareTotalPaise: Long, isPayer: Boolean, settlements: List<Long>): Balance =
        Balance(if (isPayer) 0 else shareTotalPaise, settlements.sum())

    /** A settlement may not exceed what is still owed — that would be a second payment, not a settlement. */
    fun validateSettlement(balance: Balance, amountPaise: Long): String? = when {
        amountPaise <= 0 -> "Enter an amount above zero."
        amountPaise > balance.remainingPaise -> "Only ${Money.format(balance.remainingPaise)} is still owed."
        else -> null
    }
}
