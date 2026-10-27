package com.finora.app.domain.sms

import com.finora.app.domain.model.Direction
import java.security.MessageDigest
import kotlin.math.abs

/**
 * Two layers, so one import can never become two transactions:
 *
 * 1. A deterministic [fingerprint], stored in a UNIQUE column. The same message imported twice,
 *    or re-read from the inbox, produces the same key and is rejected by the database itself.
 * 2. [isProbableDuplicate] for the cross-format case: a bank and a UPI app announcing the same
 *    payment in different words. Same amount, same direction, within a few minutes, and nothing
 *    that contradicts it (a different reference, a different account).
 */
object Deduplication {

    /** Two notifications of one payment arrive within this window. */
    const val WINDOW_MILLIS = 5 * 60 * 1000L

    fun fingerprint(
        reference: String?,
        direction: Direction,
        amountPaise: Long,
        accountTail: String?,
        occurredAt: Long,
        counterparty: String?,
        body: String?,
    ): String {
        val ref = reference?.trim()?.uppercase()
        if (!ref.isNullOrEmpty()) {
            // A reversal of a payment reuses its reference; direction keeps the two apart.
            return "ref:$ref:${direction.name}:$amountPaise"
        }
        if (!body.isNullOrBlank()) {
            return "sms:" + sha256(body.replace(Regex("\\s+"), " ").trim()).take(32)
        }
        val minute = occurredAt / 60_000
        val cp = counterparty?.lowercase()?.replace(Regex("[^a-z0-9]"), "") ?: ""
        return "h:${accountTail ?: "-"}:${direction.name}:$amountPaise:$minute:$cp"
    }

    data class Candidate(
        val amountPaise: Long,
        val direction: Direction,
        val occurredAt: Long,
        val reference: String?,
        val accountTail: String?,
    )

    fun isProbableDuplicate(new: Candidate, existing: Candidate): Boolean {
        if (new.amountPaise != existing.amountPaise || new.direction != existing.direction) return false
        if (abs(new.occurredAt - existing.occurredAt) > WINDOW_MILLIS) return false
        // A different reference on both sides means two genuinely separate payments of the same amount.
        if (!new.reference.isNullOrBlank() && !existing.reference.isNullOrBlank() &&
            !new.reference.equals(existing.reference, ignoreCase = true)
        ) return false
        if (new.accountTail != null && existing.accountTail != null && new.accountTail != existing.accountTail) return false
        return true
    }

    fun sha256(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}
