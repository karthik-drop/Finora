package com.finora.app.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import com.finora.app.domain.model.Direction
import com.finora.app.domain.model.ReviewStatus
import kotlinx.coroutines.flow.Flow

/** A transaction with the names a list row needs, in one query (no per-row lookups). */
data class TxnRow(
    @Embedded val txn: TransactionEntity,
    val merchantName: String?,
    val personName: String?,
    val categoryName: String?,
    val categoryColor: String?,
    val categoryIcon: String?,
    val accountName: String?,
)

private const val TXN_ROW_SELECT = """
    SELECT t.*, m.displayName AS merchantName, p.name AS personName, c.name AS categoryName,
           c.colorHex AS categoryColor, c.iconKey AS categoryIcon, a.name AS accountName
    FROM transactions t
    LEFT JOIN merchants m ON m.id = t.merchantId
    LEFT JOIN people p ON p.id = t.personId
    LEFT JOIN categories c ON c.id = t.categoryId
    LEFT JOIN accounts a ON a.id = t.accountId
"""

@Dao
interface TransactionDao {
    /** IGNORE on the unique fingerprint: a duplicate returns -1 and changes nothing. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(txn: TransactionEntity): Long

    @Update suspend fun update(txn: TransactionEntity)
    @Delete suspend fun delete(txn: TransactionEntity)

    @Query("SELECT * FROM transactions WHERE id = :id") suspend fun get(id: Long): TransactionEntity?
    @Query("SELECT * FROM transactions WHERE fingerprint = :fp") suspend fun getByFingerprint(fp: String): TransactionEntity?

    @Query("$TXN_ROW_SELECT WHERE t.id = :id") fun observeRow(id: Long): Flow<TxnRow?>

    @Query("$TXN_ROW_SELECT ORDER BY t.occurredAt DESC LIMIT :limit") fun observeRecent(limit: Int): Flow<List<TxnRow>>

    @Query(
        """$TXN_ROW_SELECT
        WHERE (:from IS NULL OR t.occurredAt >= :from) AND (:to IS NULL OR t.occurredAt < :to)
          AND (:categoryId IS NULL OR t.categoryId = :categoryId)
          AND (:accountId IS NULL OR t.accountId = :accountId)
          AND (:merchantId IS NULL OR t.merchantId = :merchantId)
          AND (:personId IS NULL OR t.personId = :personId)
          AND (:type IS NULL OR t.type = :type)
          AND (:minPaise IS NULL OR t.amountPaise >= :minPaise)
          AND (:maxPaise IS NULL OR t.amountPaise <= :maxPaise)
          AND (:q IS NULL OR t.rawCounterparty LIKE :q OR m.displayName LIKE :q OR p.name LIKE :q
               OR t.notes LIKE :q OR t.purpose LIKE :q OR c.name LIKE :q OR t.reference LIKE :q)
        ORDER BY t.occurredAt DESC, t.id DESC
        LIMIT :limit"""
    )
    fun search(
        q: String?, from: Long?, to: Long?, categoryId: Long?, accountId: Long?, merchantId: Long?,
        personId: Long?, type: String?, minPaise: Long?, maxPaise: Long?, limit: Int,
    ): Flow<List<TxnRow>>

    @Query("$TXN_ROW_SELECT WHERE t.reviewStatus = 'NEEDS_REVIEW' ORDER BY t.occurredAt DESC")
    fun observeNeedsReview(): Flow<List<TxnRow>>

    @Query("SELECT COUNT(*) FROM transactions WHERE reviewStatus = 'NEEDS_REVIEW'")
    fun observeNeedsReviewCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM transactions WHERE reviewStatus = 'NEEDS_REVIEW'")
    suspend fun needsReviewCount(): Int

    @Query("SELECT * FROM transactions WHERE occurredAt >= :from AND occurredAt < :to ORDER BY occurredAt")
    suspend fun inRange(from: Long, to: Long): List<TransactionEntity>

    @Query("$TXN_ROW_SELECT WHERE t.occurredAt >= :from AND t.occurredAt < :to ORDER BY t.occurredAt")
    fun observeRowsInRange(from: Long, to: Long): Flow<List<TxnRow>>

    @Query("SELECT * FROM transactions ORDER BY occurredAt")
    suspend fun all(): List<TransactionEntity>

    /** Candidates for the cross-format duplicate check: same amount and direction, close in time. */
    @Query(
        """SELECT * FROM transactions WHERE amountPaise = :amount AND direction = :direction
           AND occurredAt BETWEEN :from AND :to"""
    )
    suspend fun nearby(amount: Long, direction: Direction, from: Long, to: Long): List<TransactionEntity>

    @Query("SELECT * FROM transactions WHERE reference = :ref") suspend fun byReference(ref: String): List<TransactionEntity>

    /** The opposite leg of a possible own-account transfer. */
    @Query(
        """SELECT * FROM transactions WHERE amountPaise = :amount AND direction = :direction
           AND occurredAt BETWEEN :from AND :to AND id != :excludeId AND reversed = 0"""
    )
    suspend fun counterpartCandidates(amount: Long, direction: Direction, from: Long, to: Long, excludeId: Long): List<TransactionEntity>

    @Query("SELECT * FROM transactions WHERE merchantId = :merchantId ORDER BY occurredAt DESC")
    suspend fun byMerchant(merchantId: Long): List<TransactionEntity>

    @Query("SELECT COUNT(*) FROM transactions WHERE merchantId = :merchantId AND (categoryId IS NULL OR categoryId != :categoryId) AND id != :excludeId")
    suspend fun countMerchantTxnsWithOtherCategory(merchantId: Long, categoryId: Long, excludeId: Long): Int

    @Query("UPDATE transactions SET categoryId = :categoryId, updatedAt = :now WHERE merchantId = :merchantId")
    suspend fun recategorizeMerchant(merchantId: Long, categoryId: Long, now: Long)

    @Query(
        """SELECT * FROM transactions WHERE merchantId = :merchantId AND reviewStatus = 'NEEDS_REVIEW'"""
    )
    suspend fun pendingForMerchant(merchantId: Long): List<TransactionEntity>

    @Query("SELECT * FROM transactions WHERE accountId IS NOT NULL AND balanceAfterPaise IS NOT NULL ORDER BY occurredAt DESC")
    fun observeWithBalances(): Flow<List<TransactionEntity>>

    @Query("SELECT * FROM transactions WHERE reviewStatus = :status")
    suspend fun byReviewStatus(status: ReviewStatus): List<TransactionEntity>

    @Query("SELECT * FROM transactions WHERE loanId = :loanId ORDER BY occurredAt")
    fun observeForLoan(loanId: Long): Flow<List<TransactionEntity>>

    @Query("SELECT COUNT(*) FROM transactions") suspend fun count(): Int

    @Query("$TXN_ROW_SELECT WHERE t.occurredAt >= :from ORDER BY t.occurredAt DESC")
    fun observeRowsSince(from: Long): Flow<List<TxnRow>>
}

@Dao
interface AccountDao {
    @Insert suspend fun insert(a: AccountEntity): Long
    @Update suspend fun update(a: AccountEntity)
    @Delete suspend fun delete(a: AccountEntity)
    @Query("SELECT * FROM accounts WHERE archived = 0 ORDER BY name") fun observeAll(): Flow<List<AccountEntity>>
    @Query("SELECT * FROM accounts") suspend fun all(): List<AccountEntity>
    @Query("SELECT * FROM accounts WHERE maskedNumber = :masked LIMIT 1") suspend fun byMasked(masked: String): AccountEntity?
    @Query("SELECT * FROM accounts WHERE id = :id") suspend fun get(id: Long): AccountEntity?
}

@Dao
interface CategoryDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insert(c: CategoryEntity): Long
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertAll(c: List<CategoryEntity>)
    @Update suspend fun update(c: CategoryEntity)
    @Query("SELECT * FROM categories WHERE archived = 0 ORDER BY kind, name") fun observeAll(): Flow<List<CategoryEntity>>
    @Query("SELECT * FROM categories") suspend fun all(): List<CategoryEntity>
    @Query("SELECT * FROM categories WHERE id = :id") suspend fun get(id: Long): CategoryEntity?
    @Query("SELECT * FROM categories WHERE name = :name LIMIT 1") suspend fun byName(name: String): CategoryEntity?
    @Query("SELECT COUNT(*) FROM categories") suspend fun count(): Int
}

@Dao
interface MerchantDao {
    @Insert suspend fun insert(m: MerchantEntity): Long
    @Update suspend fun update(m: MerchantEntity)
    @Delete suspend fun delete(m: MerchantEntity)
    @Query("SELECT * FROM merchants WHERE id = :id") suspend fun get(id: Long): MerchantEntity?
    @Query("SELECT * FROM merchants WHERE normalizedKey = :key LIMIT 1") suspend fun byKey(key: String): MerchantEntity?
    @Query("SELECT * FROM merchants") suspend fun all(): List<MerchantEntity>
    @Query("SELECT * FROM merchants ORDER BY displayName") fun observeAll(): Flow<List<MerchantEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertAlias(a: MerchantAliasEntity): Long
    @Query("SELECT * FROM merchant_aliases") suspend fun allAliases(): List<MerchantAliasEntity>
    @Query("SELECT m.* FROM merchants m JOIN merchant_aliases a ON a.merchantId = m.id WHERE a.aliasKey = :key LIMIT 1") suspend fun byAlias(key: String): MerchantEntity?
    @Query("DELETE FROM merchant_aliases WHERE merchantId = :merchantId") suspend fun clearAliases(merchantId: Long)

    @Insert suspend fun insertConfirmation(c: MerchantConfirmationEntity): Long
    @Query("SELECT * FROM merchant_confirmations WHERE merchantId = :merchantId ORDER BY createdAt DESC")
    fun observeConfirmations(merchantId: Long): Flow<List<MerchantConfirmationEntity>>
    @Query("DELETE FROM merchant_confirmations WHERE merchantId = :merchantId") suspend fun clearConfirmations(merchantId: Long)
}

@Dao
interface PersonDao {
    @Insert suspend fun insert(p: PersonEntity): Long
    @Update suspend fun update(p: PersonEntity)
    @Delete suspend fun delete(p: PersonEntity)
    @Query("SELECT * FROM people ORDER BY name") fun observeAll(): Flow<List<PersonEntity>>
    @Query("SELECT * FROM people") suspend fun all(): List<PersonEntity>
    @Query("SELECT * FROM people WHERE id = :id") suspend fun get(id: Long): PersonEntity?
    @Query("SELECT * FROM people WHERE upiId = :upi LIMIT 1") suspend fun byUpi(upi: String): PersonEntity?
}

data class ReceiptWithItems(
    @Embedded val receipt: ReceiptEntity,
    @androidx.room.Relation(parentColumn = "id", entityColumn = "receiptId") val items: List<ReceiptItemEntity>,
)

@Dao
interface ReceiptDao {
    @Insert suspend fun insert(r: ReceiptEntity): Long
    @Insert suspend fun insertItems(items: List<ReceiptItemEntity>)
    @Transaction @Query("SELECT * FROM receipts WHERE id = :id") suspend fun get(id: Long): ReceiptWithItems?
}

data class BillSplitSummary(
    @Embedded val split: BillSplitEntity,
    val participantCount: Int,
    /** What others still owe the payer, across all non-payer participants. */
    val outstandingPaise: Long,
)

@Dao
interface BillSplitDao {
    @Insert suspend fun insert(s: BillSplitEntity): Long
    @Update suspend fun update(s: BillSplitEntity)
    @Delete suspend fun delete(s: BillSplitEntity)
    @Insert suspend fun insertParticipant(p: BillParticipantEntity): Long
    @Update suspend fun updateParticipants(p: List<BillParticipantEntity>)
    @Insert suspend fun insertItems(items: List<BillItemEntity>)
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertSettlement(s: SettlementEntity): Long
    @Delete suspend fun deleteSettlement(s: SettlementEntity)

    @Query("SELECT * FROM bill_splits WHERE id = :id") fun observe(id: Long): Flow<BillSplitEntity?>
    @Query("SELECT * FROM bill_splits WHERE id = :id") suspend fun get(id: Long): BillSplitEntity?
    @Query("SELECT * FROM bill_participants WHERE billSplitId = :id ORDER BY isMe DESC, id") fun observeParticipants(id: Long): Flow<List<BillParticipantEntity>>
    @Query("SELECT * FROM bill_participants WHERE billSplitId = :id") suspend fun participants(id: Long): List<BillParticipantEntity>
    @Query("SELECT * FROM bill_items WHERE billSplitId = :id ORDER BY position") fun observeItems(id: Long): Flow<List<BillItemEntity>>
    @Query("SELECT * FROM settlements WHERE billSplitId = :id ORDER BY occurredAt") fun observeSettlements(id: Long): Flow<List<SettlementEntity>>
    @Query("SELECT * FROM settlements WHERE billSplitId = :id") suspend fun settlements(id: Long): List<SettlementEntity>

    @Query(
        """SELECT s.*,
             (SELECT COUNT(*) FROM bill_participants p WHERE p.billSplitId = s.id) AS participantCount,
             COALESCE((SELECT SUM(p.totalSharePaise) FROM bill_participants p
                        WHERE p.billSplitId = s.id AND (s.paidByParticipantId IS NULL OR p.id != s.paidByParticipantId)), 0)
             - COALESCE((SELECT SUM(st.amountPaise) FROM settlements st WHERE st.billSplitId = s.id), 0) AS outstandingPaise
           FROM bill_splits s ORDER BY s.occurredAt DESC"""
    )
    fun observeSummaries(): Flow<List<BillSplitSummary>>

    @Query("SELECT * FROM bill_participants") suspend fun allParticipants(): List<BillParticipantEntity>
    @Query("SELECT * FROM settlements") suspend fun allSettlements(): List<SettlementEntity>
    @Query("SELECT * FROM bill_splits") suspend fun all(): List<BillSplitEntity>
}

@Dao
interface LoanDao {
    @Insert suspend fun insert(l: LoanEntity): Long
    @Update suspend fun update(l: LoanEntity)
    @Delete suspend fun delete(l: LoanEntity)
    @Query("SELECT * FROM loans ORDER BY status, COALESCE(dueDate, 9999999)") fun observeAll(): Flow<List<LoanEntity>>
    @Query("SELECT * FROM loans") suspend fun all(): List<LoanEntity>
    @Query("SELECT * FROM loans WHERE id = :id") fun observe(id: Long): Flow<LoanEntity?>
    @Query("SELECT * FROM loans WHERE id = :id") suspend fun get(id: Long): LoanEntity?
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertPayment(p: LoanPaymentEntity): Long
    @Delete suspend fun deletePayment(p: LoanPaymentEntity)
    @Query("SELECT * FROM loan_payments WHERE loanId = :loanId ORDER BY date, id") fun observePayments(loanId: Long): Flow<List<LoanPaymentEntity>>
    @Query("SELECT * FROM loan_payments ORDER BY date, id") fun observeAllPayments(): Flow<List<LoanPaymentEntity>>
    @Query("SELECT * FROM loan_payments") suspend fun allPayments(): List<LoanPaymentEntity>
    @Query("SELECT * FROM loan_payments WHERE transactionId = :txnId LIMIT 1") suspend fun paymentForTransaction(txnId: Long): LoanPaymentEntity?
}

@Dao
interface InvestmentDao {
    @Insert suspend fun insert(i: InvestmentEntity): Long
    @Update suspend fun update(i: InvestmentEntity)
    @Delete suspend fun delete(i: InvestmentEntity)
    @Query("SELECT * FROM investments WHERE archived = 0 ORDER BY name") fun observeAll(): Flow<List<InvestmentEntity>>
    @Query("SELECT * FROM investments") suspend fun all(): List<InvestmentEntity>
    @Query("SELECT * FROM investments WHERE id = :id") fun observe(id: Long): Flow<InvestmentEntity?>

    @Insert suspend fun insertTxn(t: InvestmentTxnEntity): Long
    @Delete suspend fun deleteTxn(t: InvestmentTxnEntity)
    @Query("SELECT * FROM investment_transactions ORDER BY date, id") fun observeAllTxns(): Flow<List<InvestmentTxnEntity>>
    @Query("SELECT * FROM investment_transactions") suspend fun allTxns(): List<InvestmentTxnEntity>

    /** REPLACE on (investment, day): re-entering a day's price corrects it rather than duplicating it. */
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertValuation(v: InvestmentValuationEntity): Long
    @Delete suspend fun deleteValuation(v: InvestmentValuationEntity)
    @Query("SELECT * FROM investment_valuations ORDER BY valuationDate, id") fun observeAllValuations(): Flow<List<InvestmentValuationEntity>>
    @Query("SELECT * FROM investment_valuations") suspend fun allValuations(): List<InvestmentValuationEntity>

    @Insert suspend fun insertSip(s: SipPlanEntity): Long
    @Update suspend fun updateSip(s: SipPlanEntity)
    @Query("SELECT * FROM sip_plans ORDER BY id") fun observeSips(): Flow<List<SipPlanEntity>>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertInstalment(i: SipInstalmentEntity)
    @Query("SELECT * FROM sip_instalments ORDER BY dueDate") fun observeInstalments(): Flow<List<SipInstalmentEntity>>
}

@Dao
interface DocumentDao {
    @Insert suspend fun insert(d: DocumentEntity): Long
    @Update suspend fun update(d: DocumentEntity)
    @Delete suspend fun delete(d: DocumentEntity)
    @Query(
        """SELECT * FROM documents WHERE (:q IS NULL OR title LIKE :q OR tags LIKE :q OR notes LIKE :q OR originalFileName LIKE :q)
           AND (:kind IS NULL OR kind = :kind) ORDER BY createdAt DESC"""
    )
    fun search(q: String?, kind: String?): Flow<List<DocumentEntity>>
    @Query("SELECT * FROM documents WHERE id = :id") fun observe(id: Long): Flow<DocumentEntity?>
    @Query("SELECT * FROM documents WHERE id = :id") suspend fun get(id: Long): DocumentEntity?
    @Query("SELECT * FROM documents") suspend fun all(): List<DocumentEntity>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun link(l: DocumentLinkEntity)
    @Delete suspend fun unlink(l: DocumentLinkEntity)
    @Query("SELECT * FROM document_links WHERE documentId = :id") fun observeLinks(id: Long): Flow<List<DocumentLinkEntity>>
    @Query(
        """SELECT d.* FROM documents d JOIN document_links l ON l.documentId = d.id
           WHERE l.target = :target AND l.targetId = :targetId ORDER BY d.createdAt DESC"""
    )
    fun observeFor(target: String, targetId: Long): Flow<List<DocumentEntity>>
}

@Dao
interface ReminderDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertIfAbsent(r: ReminderEntity)
    @Update suspend fun update(r: ReminderEntity)
    @Query("SELECT * FROM reminders ORDER BY timeOfDayMinutes") fun observeAll(): Flow<List<ReminderEntity>>
    @Query("SELECT * FROM reminders WHERE `key` = :key") suspend fun byKey(key: String): ReminderEntity?
}

@Dao
interface RecurringBillDao {
    @Insert suspend fun insert(b: RecurringBillEntity): Long
    @Update suspend fun update(b: RecurringBillEntity)
    @Delete suspend fun delete(b: RecurringBillEntity)
    @Query("SELECT * FROM recurring_bills ORDER BY dayOfMonth") fun observeAll(): Flow<List<RecurringBillEntity>>
}

@Dao
interface UnparsedMessageDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insert(m: UnparsedMessageEntity): Long
    @Update suspend fun update(m: UnparsedMessageEntity)
    @Query("SELECT * FROM unparsed_messages WHERE dismissed = 0 ORDER BY receivedAt DESC") fun observeOpen(): Flow<List<UnparsedMessageEntity>>
    @Query("SELECT COUNT(*) FROM unparsed_messages WHERE dismissed = 0") fun observeOpenCount(): Flow<Int>
}

@Dao
interface SettingDao {
    @Upsert suspend fun put(s: SettingEntity)
    @Query("SELECT value FROM settings WHERE `key` = :key") suspend fun get(key: String): String?
    @Query("SELECT * FROM settings") fun observeAll(): Flow<List<SettingEntity>>
}

@Dao
interface PlaceCacheDao {
    @Upsert suspend fun put(e: PlaceLookupCacheEntity)
    @Query("SELECT * FROM place_lookup_cache WHERE queryKey = :key") suspend fun get(key: String): PlaceLookupCacheEntity?
}
