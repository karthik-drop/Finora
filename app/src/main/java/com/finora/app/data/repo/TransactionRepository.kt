package com.finora.app.data.repo

import androidx.room.withTransaction
import com.finora.app.data.db.AccountEntity
import com.finora.app.data.db.FinoraDatabase
import com.finora.app.data.db.MerchantConfirmationEntity
import com.finora.app.data.db.MerchantEntity
import com.finora.app.data.db.PersonEntity
import com.finora.app.data.db.TransactionEntity
import com.finora.app.data.db.UnparsedMessageEntity
import com.finora.app.domain.merchant.LearningDecision
import com.finora.app.domain.merchant.MerchantLearningPolicy
import com.finora.app.domain.merchant.MerchantNormalizer
import com.finora.app.domain.merchant.MerchantProfile
import com.finora.app.domain.model.AccountType
import com.finora.app.domain.model.ConfirmationKind
import com.finora.app.domain.model.Direction
import com.finora.app.domain.model.MerchantStatus
import com.finora.app.domain.model.PaymentMethod
import com.finora.app.domain.model.ReviewReason
import com.finora.app.domain.model.ReviewStatus
import com.finora.app.domain.model.TxnSource
import com.finora.app.domain.model.TxnType
import com.finora.app.domain.sms.ClassifierContext
import com.finora.app.domain.sms.Deduplication
import com.finora.app.domain.sms.ParsedSms
import com.finora.app.domain.sms.SmsParseResult
import com.finora.app.domain.sms.SmsParser
import com.finora.app.domain.sms.TransactionClassifier
import kotlin.math.abs

sealed interface ImportOutcome {
    data class Added(val id: Long, val needsReview: Boolean) : ImportOutcome
    data class Duplicate(val existingId: Long?) : ImportOutcome
    data object Unrecognized : ImportOutcome
    data object Ignored : ImportOutcome
}

/**
 * The SMS -> transaction pipeline and the review actions. Every write that must happen
 * together runs in one Room transaction.
 */
class TransactionRepository(
    private val db: FinoraDatabase,
    private val settings: SettingsRepository,
    private val parser: SmsParser = SmsParser(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val txns = db.transactions()

    suspend fun importSms(sender: String, body: String, receivedAt: Long): ImportOutcome {
        return when (val r = parser.parse(sender, body, receivedAt)) {
            is SmsParseResult.Ignored -> ImportOutcome.Ignored
            is SmsParseResult.Unrecognized -> {
                db.unparsedMessages().insert(
                    UnparsedMessageEntity(sender = sender, body = body, receivedAt = receivedAt, fingerprint = "sms:" + Deduplication.sha256(body.trim()).take(32))
                )
                ImportOutcome.Unrecognized
            }
            is SmsParseResult.Parsed -> importParsed(r.sms)
        }
    }

    suspend fun importParsed(sms: ParsedSms): ImportOutcome = db.withTransaction {
        val now = clock()
        val s = settings.current()
        val accounts = db.accounts().all()
        val ctx = ClassifierContext(
            ownAccountTails = accounts.mapNotNull { it.maskedNumber }.toSet(),
            ownUpiIds = s.ownUpiIds,
            ownNames = s.ownNames,
            knownMerchantKeys = db.merchants().all().map { it.normalizedKey }.toSet() + db.merchants().allAliases().map { it.aliasKey },
        )
        val fingerprint = Deduplication.fingerprint(sms.reference, sms.direction, sms.amountPaise, sms.accountTail, sms.occurredAt, sms.counterparty, sms.body)
        txns.getByFingerprint(fingerprint)?.let { return@withTransaction ImportOutcome.Duplicate(it.id) }

        val account = sms.accountTail?.let { tail ->
            db.accounts().byMasked(tail) ?: AccountEntity(
                name = (if (sms.isCreditCardAccount) "Card" else "Account") + " ••$tail",
                type = if (sms.isCreditCardAccount) AccountType.CREDIT_CARD else AccountType.BANK,
                maskedNumber = tail,
                createdAt = now,
            ).let { it.copy(id = db.accounts().insert(it)) }
        }

        // Cross-format duplicate: the bank and the UPI app announcing one payment.
        val candidate = Deduplication.Candidate(sms.amountPaise, sms.direction, sms.occurredAt, sms.reference, sms.accountTail)
        val nearby = txns.nearby(sms.amountPaise, sms.direction, sms.occurredAt - Deduplication.WINDOW_MILLIS, sms.occurredAt + Deduplication.WINDOW_MILLIS)
        nearby.firstOrNull { e ->
            val eTail = e.accountId?.let { id -> accounts.firstOrNull { it.id == id }?.maskedNumber ?: account?.takeIf { it.id == id }?.maskedNumber }
            Deduplication.isProbableDuplicate(candidate, Deduplication.Candidate(e.amountPaise, e.direction, e.occurredAt, e.reference, eTail))
        }?.let { existing ->
            // Keep whatever extra the second message knew.
            txns.update(
                existing.copy(
                    reference = existing.reference ?: sms.reference,
                    balanceAfterPaise = existing.balanceAfterPaise ?: sms.balancePaise,
                    rawCounterparty = existing.rawCounterparty ?: sms.counterparty,
                    updatedAt = now,
                )
            )
            return@withTransaction ImportOutcome.Duplicate(existing.id)
        }

        val cls = TransactionClassifier.classify(sms, ctx)
        var type = cls.type
        var reviewReason: ReviewReason? = cls.reviewReason
        var linkedId: Long? = null
        var categoryId: Long? = null
        var merchantId: Long? = null
        var personId: Long? = null
        var auto = false

        if (type == TxnType.INCOME && !s.reviewUnknownCredits) reviewReason = null

        // A reversal cancels the payment it names; both stay visible, neither counts.
        if (type == TxnType.REVERSAL) {
            val original = sms.reference?.let { ref -> txns.byReference(ref).firstOrNull { it.direction == Direction.DEBIT && !it.reversed } }
                ?: nearbyOriginal(sms)
            if (original != null) {
                txns.update(original.copy(reversed = true, reviewStatus = if (original.reviewStatus == ReviewStatus.NEEDS_REVIEW) ReviewStatus.RESOLVED else original.reviewStatus, updatedAt = now))
                linkedId = original.id
                merchantId = original.merchantId
            }
        }

        // A refund reduces the category of the purchase it came back from.
        if (type == TxnType.REFUND) {
            val original = sms.counterparty?.let { cp ->
                val key = MerchantNormalizer.key(cp)
                txns.inRange(sms.occurredAt - 120L * 86_400_000, sms.occurredAt)
                    .filter { it.direction == Direction.DEBIT && it.amountPaise >= sms.amountPaise && it.rawCounterparty?.let(MerchantNormalizer::key) == key }
                    .maxByOrNull { it.occurredAt }
            }
            if (original != null) {
                linkedId = original.id
                categoryId = original.categoryId
                merchantId = original.merchantId
            }
        }

        // Merchant learning applies to purchases.
        if (type == TxnType.EXPENSE && sms.counterparty != null) {
            val profiles = db.merchants().all().map { it.toProfile() }
            val aliases = db.merchants().allAliases().associate { it.aliasKey to it.merchantId }
            when (val d = MerchantLearningPolicy.decide(MerchantLearningPolicy.match(sms.counterparty, profiles, aliases))) {
                is LearningDecision.AutoApply -> {
                    merchantId = d.profile.id; categoryId = d.profile.categoryId; auto = true
                }
                is LearningDecision.AskConfirm -> {
                    merchantId = d.profile.id; categoryId = d.profile.categoryId; reviewReason = ReviewReason.CONFIRM_MERCHANT
                }
                is LearningDecision.AskClarify -> {
                    merchantId = d.profile.id; categoryId = d.profile.categoryId; reviewReason = ReviewReason.AMBIGUOUS_MERCHANT
                }
                LearningDecision.AskNewMerchant -> reviewReason = ReviewReason.NEW_MERCHANT
            }
        }

        // People: a known UPI id names the person; the purpose is still asked.
        if (type == TxnType.PERSON_PAYMENT || type == TxnType.PERSON_RECEIPT) {
            personId = sms.upiId?.lowercase()?.let { db.people().byUpi(it)?.id }
        }

        val entity = TransactionEntity(
            amountPaise = sms.amountPaise,
            direction = sms.direction,
            type = type,
            occurredAt = sms.occurredAt,
            accountId = account?.id,
            merchantId = merchantId,
            personId = personId,
            categoryId = categoryId,
            rawCounterparty = sms.counterparty ?: sms.upiId,
            reference = sms.reference,
            paymentMethod = sms.method,
            balanceAfterPaise = sms.balancePaise,
            feePaise = sms.feePaise,
            taxPaise = sms.taxPaise,
            notes = null,
            source = TxnSource.SMS,
            fingerprint = fingerprint,
            smsSender = sms.sender,
            smsBody = sms.body,
            reviewStatus = if (reviewReason != null) ReviewStatus.NEEDS_REVIEW else ReviewStatus.NONE,
            reviewReason = reviewReason,
            autoCategorized = auto,
            linkedTransactionId = linkedId,
            createdAt = now,
            updatedAt = now,
        )
        val id = txns.insert(entity)
        if (id == -1L) return@withTransaction ImportOutcome.Duplicate(null)

        pairOwnTransfer(entity.copy(id = id), accounts + listOfNotNull(account))
        ImportOutcome.Added(id, reviewReason != null)
    }

    private suspend fun nearbyOriginal(sms: ParsedSms): TransactionEntity? =
        txns.inRange(sms.occurredAt - 30L * 86_400_000, sms.occurredAt)
            .filter { it.direction == Direction.DEBIT && it.amountPaise == sms.amountPaise && !it.reversed }
            .maxByOrNull { it.occurredAt }

    /**
     * A debit from one own account and a credit of the same amount to ANOTHER own account within
     * ten minutes is a transfer between them: both legs become SELF_TRANSFER so neither counts.
     */
    private suspend fun pairOwnTransfer(t: TransactionEntity, accounts: List<AccountEntity>) {
        if (t.accountId == null || t.type !in setOf(TxnType.EXPENSE, TxnType.INCOME, TxnType.PERSON_PAYMENT, TxnType.PERSON_RECEIPT)) return
        val opposite = if (t.direction == Direction.DEBIT) Direction.CREDIT else Direction.DEBIT
        val window = 10 * 60_000L
        val other = txns.counterpartCandidates(t.amountPaise, opposite, t.occurredAt - window, t.occurredAt + window, t.id)
            .firstOrNull { it.accountId != null && it.accountId != t.accountId && accounts.any { a -> a.id == it.accountId } &&
                it.type in setOf(TxnType.EXPENSE, TxnType.INCOME, TxnType.PERSON_PAYMENT, TxnType.PERSON_RECEIPT, TxnType.SELF_TRANSFER) }
            ?: return
        val now = clock()
        listOf(t, other).forEach {
            txns.update(it.copy(type = TxnType.SELF_TRANSFER, reviewStatus = ReviewStatus.NONE, reviewReason = null, linkedTransactionId = if (it.id == t.id) other.id else t.id, merchantId = null, categoryId = null, updatedAt = now))
        }
    }

    // ---------------- Manual entry ----------------

    suspend fun addManual(
        amountPaise: Long, direction: Direction, type: TxnType, occurredAt: Long, accountId: Long?, categoryId: Long?,
        merchantName: String?, personId: Long?, purpose: String?, method: PaymentMethod?, notes: String?,
    ): Long = db.withTransaction {
        require(amountPaise > 0) { "Amount must be above zero" }
        val now = clock()
        val merchantId = merchantName?.takeIf { it.isNotBlank() }?.let { name ->
            val key = MerchantNormalizer.key(name)
            db.merchants().byKey(key)?.id
        }
        txns.insert(
            TransactionEntity(
                amountPaise = amountPaise, direction = direction, type = type, occurredAt = occurredAt, accountId = accountId,
                merchantId = merchantId, personId = personId, categoryId = categoryId, purpose = purpose,
                rawCounterparty = merchantName?.takeIf { it.isNotBlank() }, paymentMethod = method, notes = notes,
                source = TxnSource.MANUAL,
                // Manual entries are intentional; two identical ones are allowed, so the key is unique per entry.
                fingerprint = "manual:" + java.util.UUID.randomUUID(),
                createdAt = now, updatedAt = now,
            )
        )
    }

    suspend fun update(t: TransactionEntity) = txns.update(t.copy(updatedAt = clock()))
    suspend fun delete(t: TransactionEntity) = txns.delete(t)

    // ---------------- Review actions ----------------

    data class ConfirmResult(val merchantId: Long, val learnedNow: Boolean, val corrected: Boolean, val otherTransactionsWithDifferentCategory: Int)

    /**
     * The user confirmed (or chose) the merchant and category for a transaction. Resolves every
     * other pending transaction for the same merchant too — that is ONE decision, and it counts
     * as ONE confirmation, never one per row.
     */
    suspend fun confirmMerchant(
        txnId: Long, displayName: String, categoryId: Long, purpose: String?,
        place: PlaceInfo? = null, sharedName: Boolean? = null,
    ): ConfirmResult = db.withTransaction {
        val now = clock()
        val txn = txns.get(txnId) ?: error("Transaction not found")
        val raw = txn.rawCounterparty ?: displayName
        val key = MerchantNormalizer.key(raw).ifBlank { MerchantNormalizer.key(displayName) }
        // The suggested merchant is kept if the user kept its name: they have confirmed this
        // differently-worded message IS that business, which is recorded as an alias.
        val suggested = txn.merchantId?.let { db.merchants().get(it) }
            ?.takeIf { it.normalizedKey == key || MerchantNormalizer.key(displayName) == it.normalizedKey }
        val existing = suggested ?: db.merchants().byKey(key) ?: db.merchants().byAlias(key)
        if (existing != null && existing.normalizedKey != key) {
            db.merchants().insertAlias(com.finora.app.data.db.MerchantAliasEntity(merchantId = existing.id, aliasKey = key, createdAt = now))
        }
        val before = existing?.toProfile()
        val upd = MerchantLearningPolicy.onConfirm(before, categoryId)
        val merchant = if (existing == null) {
            val m = MerchantEntity(
                displayName = displayName.trim(), normalizedKey = key, categoryId = upd.categoryId, confirmationCount = upd.confirmationCount,
                status = upd.status, sharedName = sharedName ?: false, address = place?.address, latitude = place?.lat, longitude = place?.lon,
                osmRef = place?.osmRef, createdAt = now, updatedAt = now,
            )
            m.copy(id = db.merchants().insert(m))
        } else {
            existing.copy(
                displayName = displayName.trim().ifBlank { existing.displayName }, categoryId = upd.categoryId,
                confirmationCount = upd.confirmationCount,
                status = if (sharedName ?: existing.sharedName) MerchantStatus.AMBIGUOUS else upd.status,
                sharedName = sharedName ?: existing.sharedName,
                address = place?.address ?: existing.address, latitude = place?.lat ?: existing.latitude,
                longitude = place?.lon ?: existing.longitude, osmRef = place?.osmRef ?: existing.osmRef, updatedAt = now,
            ).also { db.merchants().update(it) }
        }
        db.merchants().insertConfirmation(
            MerchantConfirmationEntity(merchantId = merchant.id, transactionId = txnId, categoryId = categoryId, kind = if (upd.corrected) ConfirmationKind.CORRECTED else ConfirmationKind.CONFIRMED, createdAt = now)
        )
        val group = (txns.pendingForMerchant(merchant.id) + txn).distinctBy { it.id }.filter { it.id == txn.id || it.rawCounterparty?.let(MerchantNormalizer::key) == key } +
            txns.byReviewStatus(ReviewStatus.NEEDS_REVIEW).filter { it.merchantId == null && it.rawCounterparty?.let(MerchantNormalizer::key) == key && it.id != txn.id }
        group.forEach {
            txns.update(
                it.copy(
                    merchantId = merchant.id, categoryId = categoryId, purpose = if (it.id == txnId) purpose ?: it.purpose else it.purpose,
                    // Confirming a merchant settles that this was a purchase, not a payment to a person.
                    type = if (it.type == TxnType.PERSON_PAYMENT) TxnType.EXPENSE else it.type,
                    reviewStatus = ReviewStatus.RESOLVED, reviewReason = null, autoCategorized = false, updatedAt = now,
                )
            )
        }
        val others = txns.countMerchantTxnsWithOtherCategory(merchant.id, categoryId, txnId)
        ConfirmResult(merchant.id, learnedNow = (before?.confirmationCount ?: 0) < MerchantLearningPolicy.CONFIRMATIONS_REQUIRED && upd.confirmationCount >= MerchantLearningPolicy.CONFIRMATIONS_REQUIRED, corrected = upd.corrected, otherTransactionsWithDifferentCategory = others)
    }

    data class PlaceInfo(val address: String?, val lat: Double?, val lon: Double?, val osmRef: String?)

    /**
     * A correction from the transaction screen. Overrides the merchant's learned category for
     * the future; returns how many PAST transactions still carry another category, so the UI can
     * offer to fix those too (never done silently).
     */
    suspend fun correctCategory(txnId: Long, categoryId: Long): Int = db.withTransaction {
        val now = clock()
        val txn = txns.get(txnId) ?: error("Transaction not found")
        txns.update(txn.copy(categoryId = categoryId, autoCategorized = false, updatedAt = now))
        val m = txn.merchantId?.let { db.merchants().get(it) } ?: return@withTransaction 0
        val upd = MerchantLearningPolicy.onCorrect(m.toProfile(), categoryId)
        if (upd.corrected) {
            db.merchants().update(m.copy(categoryId = upd.categoryId, confirmationCount = upd.confirmationCount, status = if (m.sharedName) MerchantStatus.AMBIGUOUS else upd.status, updatedAt = now))
            db.merchants().insertConfirmation(MerchantConfirmationEntity(merchantId = m.id, transactionId = txnId, categoryId = categoryId, kind = ConfirmationKind.CORRECTED, createdAt = now))
        }
        txns.countMerchantTxnsWithOtherCategory(m.id, categoryId, txnId)
    }

    suspend fun recategorizePastForMerchant(merchantId: Long, categoryId: Long) = txns.recategorizeMerchant(merchantId, categoryId, clock())

    /** Resolve a person payment/receipt or any classification question. */
    suspend fun resolveType(txnId: Long, type: TxnType, personId: Long?, categoryId: Long?, purpose: String?) = db.withTransaction {
        val txn = txns.get(txnId) ?: error("Transaction not found")
        // Learn the person's UPI id from this payment, if it had one and they have none.
        if (personId != null) {
            val p = db.people().get(personId)
            val upi = txn.rawCounterparty?.takeIf { it.contains('@') }?.lowercase()
            if (p != null && p.upiId == null && upi != null) db.people().update(p.copy(upiId = upi))
        }
        txns.update(txn.copy(type = type, personId = personId ?: txn.personId, categoryId = categoryId ?: txn.categoryId, purpose = purpose ?: txn.purpose, reviewStatus = ReviewStatus.RESOLVED, reviewReason = null, updatedAt = clock()))
    }

    suspend fun dismissReview(txnId: Long) {
        val txn = txns.get(txnId) ?: return
        txns.update(txn.copy(reviewStatus = ReviewStatus.RESOLVED, reviewReason = null, updatedAt = clock()))
    }

    suspend fun createPerson(name: String, upiId: String? = null, phone: String? = null): Long =
        db.people().insert(PersonEntity(name = name.trim(), normalizedKey = MerchantNormalizer.key(name), upiId = upiId?.lowercase(), phone = phone, createdAt = clock()))
}

fun MerchantEntity.toProfile() = MerchantProfile(id, normalizedKey, displayName, categoryId, confirmationCount, status, sharedName)

/** Absolute difference helper used by tests and the review screen. */
internal fun closeInTime(a: Long, b: Long, windowMillis: Long) = abs(a - b) <= windowMillis
