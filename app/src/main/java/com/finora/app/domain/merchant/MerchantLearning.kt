package com.finora.app.domain.merchant

import com.finora.app.domain.model.MerchantStatus
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/** Turns "SWIGGY*BANGALORE ", "PYU*Swiggy", "Swiggy Ltd." into one stable matching key. */
object MerchantNormalizer {
    private val GATEWAY_PREFIX = Regex("^(pyu|payu|rzp|razorpay|ccav|bharatpe|paytm|phonepe|gpay|bd|billdesk|cf|cashfree|pg|pos|ecom|upi)[*_ -]+", RegexOption.IGNORE_CASE)
    private val COMPANY_SUFFIX = Regex("\\b(private limited|pvt\\.? ?ltd\\.?|pvt|ltd\\.?|limited|llp|inc\\.?|india|co\\.?)$", RegexOption.IGNORE_CASE)
    private val TRAILING_CODES = Regex("[\\s*_#-]+[0-9]{2,}$")

    fun key(raw: String): String {
        var s = raw.trim()
        repeat(2) { s = GATEWAY_PREFIX.replace(s, "") }
        // "SWIGGY*BANGALORE" -> "SWIGGY": the part after a gateway star is usually a city or order code.
        if (s.contains('*')) s = s.substringBefore('*').ifBlank { s.substringAfter('*') }
        s = TRAILING_CODES.replace(s, "")
        s = s.lowercase(Locale.ENGLISH).replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()
        repeat(2) { s = COMPANY_SUFFIX.replace(s, "").trim() }
        return s
    }

    /** Title-case for display when nothing better (a confirmed name) is known. */
    fun displayName(raw: String): String =
        key(raw).split(' ').filter { it.isNotBlank() }.joinToString(" ") { w -> w.replaceFirstChar { it.titlecase(Locale.ENGLISH) } }
            .ifBlank { raw.trim() }
}

/** Jaro–Winkler similarity in [0, 1]. Used to notice "probably the same name", never to auto-apply. */
object Similarity {
    fun jaroWinkler(a: String, b: String): Double {
        if (a == b) return 1.0
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val range = max(0, max(a.length, b.length) / 2 - 1)
        val aMatch = BooleanArray(a.length)
        val bMatch = BooleanArray(b.length)
        var matches = 0
        for (i in a.indices) {
            val start = max(0, i - range)
            val end = min(i + range + 1, b.length)
            for (j in start until end) {
                if (bMatch[j] || a[i] != b[j]) continue
                aMatch[i] = true; bMatch[j] = true; matches++; break
            }
        }
        if (matches == 0) return 0.0
        var t = 0; var k = 0
        for (i in a.indices) {
            if (!aMatch[i]) continue
            while (!bMatch[k]) k++
            if (a[i] != b[k]) t++
            k++
        }
        val m = matches.toDouble()
        val jaro = (m / a.length + m / b.length + (m - t / 2.0) / m) / 3.0
        var prefix = 0
        while (prefix < min(4, min(a.length, b.length)) && a[prefix] == b[prefix]) prefix++
        return jaro + prefix * 0.1 * (1 - jaro)
    }
}

/** The facts the learning policy needs about a stored merchant profile. */
data class MerchantProfile(
    val id: Long,
    val key: String,
    val displayName: String,
    val categoryId: Long?,
    val confirmationCount: Int,
    val status: MerchantStatus,
    val sharedName: Boolean,
)

sealed interface MerchantMatch {
    /** Exactly this profile's key. */
    data class Exact(val profile: MerchantProfile) : MerchantMatch
    /** Not the same key, but close enough that it might be the same business. Always asks. */
    data class Similar(val profile: MerchantProfile, val score: Double) : MerchantMatch
    data object None : MerchantMatch
}

sealed interface LearningDecision {
    /** Never seen: ask, and a nearby-business lookup is allowed. */
    data object AskNewMerchant : LearningDecision
    /** Known, still learning (fewer than three confirmations): ask again, no map lookup. */
    data class AskConfirm(val profile: MerchantProfile, val confirmationsSoFar: Int) : LearningDecision
    /** Name close to a known merchant, or a name several businesses share: ask which. */
    data class AskClarify(val profile: MerchantProfile, val why: String) : LearningDecision
    /** Learned: apply silently, no question, no lookup. */
    data class AutoApply(val profile: MerchantProfile) : LearningDecision
}

/**
 * The three-confirmation policy, kept deliberately small and predictable:
 *
 *  - 1st, 2nd and 3rd matching transaction: ask the user to confirm merchant and category.
 *  - 4th onwards: apply the learned merchant and category automatically, without asking and
 *    without a map lookup — but only for an EXACT, unambiguous match.
 *
 * Only an explicit confirmation of the same merchant -> category mapping counts. Choosing a
 * different category is a correction: it replaces the mapping, and the old count does not carry
 * over to it (a correction does not count as confirming the mapping it replaced).
 */
object MerchantLearningPolicy {
    const val CONFIRMATIONS_REQUIRED = 3
    const val SIMILAR_THRESHOLD = 0.88

    /** [aliases] maps another normalised name to a merchant id, from the user's own confirmations. */
    fun match(rawName: String, profiles: List<MerchantProfile>, aliases: Map<String, Long> = emptyMap()): MerchantMatch {
        val key = MerchantNormalizer.key(rawName)
        if (key.isBlank()) return MerchantMatch.None
        profiles.firstOrNull { it.key == key }?.let { return MerchantMatch.Exact(it) }
        aliases[key]?.let { id -> profiles.firstOrNull { it.id == id } }?.let { return MerchantMatch.Exact(it) }
        val best = profiles.map { it to Similarity.jaroWinkler(key, it.key) }.maxByOrNull { it.second }
        return if (best != null && best.second >= SIMILAR_THRESHOLD) MerchantMatch.Similar(best.first, best.second) else MerchantMatch.None
    }

    fun decide(match: MerchantMatch): LearningDecision = when (match) {
        MerchantMatch.None -> LearningDecision.AskNewMerchant
        is MerchantMatch.Similar -> LearningDecision.AskClarify(match.profile, "The name is similar to ${match.profile.displayName} but not the same.")
        is MerchantMatch.Exact -> {
            val p = match.profile
            when {
                p.sharedName || p.status == MerchantStatus.AMBIGUOUS ->
                    LearningDecision.AskClarify(p, "Several businesses use this name.")
                p.categoryId != null && p.confirmationCount >= CONFIRMATIONS_REQUIRED -> LearningDecision.AutoApply(p)
                else -> LearningDecision.AskConfirm(p, p.confirmationCount)
            }
        }
    }

    data class Updated(val categoryId: Long?, val confirmationCount: Int, val status: MerchantStatus, val corrected: Boolean)

    /** The user pressed Confirm on a suggestion showing [categoryId]. */
    fun onConfirm(current: MerchantProfile?, categoryId: Long): Updated {
        if (current == null || current.categoryId == null) return Updated(categoryId, 1, statusFor(1), corrected = false)
        if (current.categoryId == categoryId) {
            val n = current.confirmationCount + 1
            return Updated(categoryId, n, statusFor(n), corrected = false)
        }
        return onCorrect(current, categoryId)
    }

    /**
     * The user chose a different category than the profile holds. The new mapping starts at ONE
     * confirmation (the user just stated it explicitly); the old mapping's count is discarded.
     *
     * Exception: correcting an ALREADY-LEARNED merchant (from a transaction it auto-categorised,
     * or from the learned-merchants screen) keeps it learned under the new category — the user
     * has told us the answer, and asking three more times would punish them for correcting us.
     */
    fun onCorrect(current: MerchantProfile, newCategoryId: Long): Updated {
        if (current.categoryId == newCategoryId) return Updated(newCategoryId, current.confirmationCount, current.status, corrected = false)
        val wasLearned = current.confirmationCount >= CONFIRMATIONS_REQUIRED
        val n = if (wasLearned) current.confirmationCount else 1
        return Updated(newCategoryId, n, statusFor(n), corrected = true)
    }

    fun statusFor(count: Int): MerchantStatus = if (count >= CONFIRMATIONS_REQUIRED) MerchantStatus.LEARNED else MerchantStatus.LEARNING

    fun progressLabel(count: Int): String = when {
        count >= CONFIRMATIONS_REQUIRED -> "Learned from your confirmations"
        count == 0 -> "Not confirmed yet"
        else -> "Confirmed $count of $CONFIRMATIONS_REQUIRED times"
    }
}
