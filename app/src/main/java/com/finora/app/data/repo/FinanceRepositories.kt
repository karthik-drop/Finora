package com.finora.app.data.repo

import androidx.room.withTransaction
import com.finora.app.data.db.BillItemEntity
import com.finora.app.data.db.BillParticipantEntity
import com.finora.app.data.db.BillSplitEntity
import com.finora.app.data.db.FinoraDatabase
import com.finora.app.data.db.InvestmentEntity
import com.finora.app.data.db.InvestmentTxnEntity
import com.finora.app.data.db.InvestmentValuationEntity
import com.finora.app.data.db.LoanEntity
import com.finora.app.data.db.LoanPaymentEntity
import com.finora.app.data.db.ReceiptEntity
import com.finora.app.data.db.ReceiptItemEntity
import com.finora.app.data.db.SettlementEntity
import com.finora.app.data.db.SipInstalmentEntity
import com.finora.app.data.db.SipPlanEntity
import com.finora.app.data.db.TransactionEntity
import com.finora.app.domain.invest.InvTxn
import com.finora.app.domain.invest.Valuation
import com.finora.app.domain.loan.LoanEvent
import com.finora.app.domain.loan.LoanTerms
import com.finora.app.domain.model.Direction
import com.finora.app.domain.model.InvestmentTxnKind
import com.finora.app.domain.model.LoanKind
import com.finora.app.domain.model.LoanPaymentKind
import com.finora.app.domain.model.ReviewStatus
import com.finora.app.domain.model.SplitMode
import com.finora.app.domain.model.TxnType
import com.finora.app.domain.receipt.ParsedReceipt
import com.finora.app.domain.split.BillSplitter
import com.finora.app.domain.split.SplitInput
import com.finora.app.domain.split.SplitItem
import com.finora.app.domain.split.SplitResult
import java.time.LocalDate

class LoanRepository(private val db: FinoraDatabase, private val clock: () -> Long = System::currentTimeMillis) {
    private val dao = db.loans()

    suspend fun create(loan: LoanEntity, linkTransactionId: Long? = null): Long = db.withTransaction {
        require(loan.principalPaise > 0) { "Principal must be above zero" }
        val id = dao.insert(loan.copy(createdAt = clock()))
        // The transfer that created the loan is lending/borrowing, never an expense or income.
        linkTransactionId?.let { txnId ->
            db.transactions().get(txnId)?.let { t ->
                val type = when (loan.kind) {
                    LoanKind.LENT -> TxnType.LENT
                    LoanKind.BORROWED -> TxnType.BORROWED
                    LoanKind.FORMAL_LOAN -> TxnType.LOAN_DISBURSEMENT
                }
                db.transactions().update(t.copy(type = type, loanId = id, reviewStatus = ReviewStatus.RESOLVED, reviewReason = null, updatedAt = clock()))
            }
        }
        id
    }

    suspend fun update(loan: LoanEntity) = dao.update(loan)
    suspend fun delete(loan: LoanEntity) = dao.delete(loan)

    /**
     * Records a payment. When it IS an imported transaction, the transaction is linked and
     * retyped (repayment received / repaid borrowing / loan repayment) so it is not ALSO counted
     * as income or expense — and the unique transactionId stops it being recorded twice.
     */
    suspend fun addPayment(loanId: Long, kind: LoanPaymentKind, amountPaise: Long, date: LocalDate, transactionId: Long?, note: String?): Long = db.withTransaction {
        require(amountPaise > 0) { "Amount must be above zero" }
        val loan = dao.get(loanId) ?: error("Loan not found")
        if (transactionId != null) {
            check(dao.paymentForTransaction(transactionId) == null) { "That transaction is already recorded against a loan." }
            db.transactions().get(transactionId)?.let { t ->
                val type = when (loan.kind) {
                    LoanKind.LENT -> if (kind == LoanPaymentKind.ADDITIONAL_DISBURSEMENT) TxnType.LENT else TxnType.LENDING_REPAYMENT_RECEIVED
                    LoanKind.BORROWED -> if (kind == LoanPaymentKind.ADDITIONAL_DISBURSEMENT) TxnType.BORROWED else TxnType.BORROWING_REPAID
                    LoanKind.FORMAL_LOAN -> if (kind == LoanPaymentKind.ADDITIONAL_DISBURSEMENT) TxnType.LOAN_DISBURSEMENT else TxnType.LOAN_REPAYMENT
                }
                db.transactions().update(t.copy(type = type, loanId = loanId, personId = t.personId ?: loan.personId, reviewStatus = ReviewStatus.RESOLVED, reviewReason = null, updatedAt = clock()))
            }
        }
        dao.insertPayment(LoanPaymentEntity(loanId = loanId, kind = kind, amountPaise = amountPaise, date = date.toEpochDay(), transactionId = transactionId, note = note, createdAt = clock()))
    }

    suspend fun deletePayment(p: LoanPaymentEntity) = db.withTransaction {
        dao.deletePayment(p)
        p.transactionId?.let { id -> db.transactions().get(id)?.let { t -> db.transactions().update(t.copy(loanId = null, updatedAt = clock())) } }
    }

    suspend fun close(loan: LoanEntity, on: LocalDate) = dao.update(loan.copy(status = com.finora.app.domain.model.LoanStatus.CLOSED, closedAt = on.toEpochDay()))
    suspend fun reopen(loan: LoanEntity) = dao.update(loan.copy(status = com.finora.app.domain.model.LoanStatus.ACTIVE, closedAt = null))
}

fun LoanEntity.terms() = LoanTerms(principalPaise, annualRateBps, interestMethod, compounding, LocalDate.ofEpochDay(startDate))
fun LoanPaymentEntity.event() = LoanEvent(LocalDate.ofEpochDay(date), kind, amountPaise)

class SplitRepository(private val db: FinoraDatabase, private val clock: () -> Long = System::currentTimeMillis) {
    private val dao = db.billSplits()

    data class Draft(
        val title: String,
        val mode: SplitMode,
        /** (display name, personId or null, isMe) */
        val participants: List<Triple<String, Long?, Boolean>>,
        val items: List<SplitItem>,
        val customSubtotals: List<Long>,
        val subtotalPaise: Long,
        val taxPaise: Long,
        val discountPaise: Long,
        val servicePaise: Long,
        val totalPaise: Long,
        val acceptedRoundingPaise: Long,
        val paidByIndex: Int,
        val occurredAt: Long,
        val receipt: ParsedReceipt? = null,
        val transactionId: Long? = null,
        val notes: String? = null,
    ) {
        fun input() = SplitInput(participants.map { it.first }, mode, items, customSubtotals, subtotalPaise, taxPaise, discountPaise, servicePaise, totalPaise, acceptedRoundingPaise)
    }

    /** Validates, then writes the bill, its items, every participant's share and the receipt in one transaction. */
    suspend fun create(d: Draft): Result<Long> {
        val result = BillSplitter.split(d.input())
        if (result is SplitResult.Invalid) return Result.failure(IllegalArgumentException(result.message))
        val ok = result as SplitResult.Ok
        return Result.success(db.withTransaction {
            val now = clock()
            val receiptId = d.receipt?.let { r ->
                val rid = db.receipts().insert(
                    ReceiptEntity(merchantName = r.merchant, receiptDate = r.date?.let { com.finora.app.core.Dates.startOfDayMillis(it) }, subtotalPaise = r.itemsSubtotalPaise,
                        taxPaise = r.taxPaise, discountPaise = r.discountPaise, servicePaise = r.servicePaise, totalPaise = r.totalPaise ?: d.totalPaise, rawText = null, transactionId = d.transactionId, createdAt = now)
                )
                db.receipts().insertItems(r.items.mapIndexed { i, it -> ReceiptItemEntity(receiptId = rid, name = it.name, quantityMilli = it.quantityMilli, unitPricePaise = it.unitPricePaise, totalPaise = it.totalPaise, position = i) })
                rid
            }
            val splitId = dao.insert(
                BillSplitEntity(title = d.title.ifBlank { "Shared bill" }, receiptId = receiptId, transactionId = d.transactionId, mode = d.mode,
                    subtotalPaise = ok.subtotalPaise, taxPaise = d.taxPaise, discountPaise = d.discountPaise, servicePaise = d.servicePaise,
                    totalPaise = d.totalPaise, roundingAdjustmentPaise = d.acceptedRoundingPaise, occurredAt = d.occurredAt, notes = d.notes, createdAt = now)
            )
            val ids = d.participants.mapIndexed { i, (name, personId, isMe) ->
                val s = ok.shares[i]
                dao.insertParticipant(BillParticipantEntity(billSplitId = splitId, personId = personId, displayName = name, isMe = isMe,
                    subtotalSharePaise = s.subtotalPaise, taxSharePaise = s.taxPaise, discountSharePaise = s.discountPaise, serviceSharePaise = s.servicePaise, totalSharePaise = s.totalPaise))
            }
            dao.update(dao.get(splitId)!!.copy(paidByParticipantId = ids.getOrNull(d.paidByIndex)))
            if (d.mode == SplitMode.ITEMIZED) {
                dao.insertItems(d.items.mapIndexed { i, it -> BillItemEntity(billSplitId = splitId, label = it.label, amountPaise = it.amountPaise, sharedBy = it.sharedBy.map { ids[it] }.joinToString(","), position = i) })
            }
            // The original payment, if it came from SMS: the user only spent their own share.
            d.transactionId?.let { tid ->
                db.transactions().get(tid)?.let { t -> db.transactions().update(t.copy(billSplitId = splitId, updatedAt = now)) }
            }
            splitId
        })
    }

    /**
     * Money a participant paid back. Recorded against the bill — when it arrived as an SMS
     * credit, that transaction becomes a settlement (neutral), never a second income.
     */
    suspend fun settle(splitId: Long, participantId: Long, amountPaise: Long, occurredAt: Long, transactionId: Long?, note: String?): Result<Long> = db.withTransaction {
        val split = dao.get(splitId) ?: return@withTransaction Result.failure(IllegalStateException("Bill not found"))
        val participant = dao.participants(splitId).firstOrNull { it.id == participantId } ?: return@withTransaction Result.failure(IllegalStateException("Person not on this bill"))
        val paid = dao.settlements(splitId).filter { it.participantId == participantId }.map { it.amountPaise }
        val bal = BillSplitter.balance(participant.totalSharePaise, split.paidByParticipantId == participantId, paid)
        BillSplitter.validateSettlement(bal, amountPaise)?.let { return@withTransaction Result.failure(IllegalArgumentException(it)) }
        if (transactionId != null) {
            if (db.billSplits().allSettlements().any { it.transactionId == transactionId }) {
                return@withTransaction Result.failure(IllegalArgumentException("That transaction already settles a share."))
            }
            db.transactions().get(transactionId)?.let { t ->
                db.transactions().update(t.copy(type = TxnType.BILL_SETTLEMENT, billSplitId = splitId, personId = t.personId ?: participant.personId, reviewStatus = ReviewStatus.RESOLVED, reviewReason = null, updatedAt = clock()))
            }
        }
        Result.success(dao.insertSettlement(SettlementEntity(billSplitId = splitId, participantId = participantId, amountPaise = amountPaise, occurredAt = occurredAt, transactionId = transactionId, note = note)))
    }

    suspend fun deleteSettlement(s: SettlementEntity) = db.withTransaction {
        dao.deleteSettlement(s)
        s.transactionId?.let { id -> db.transactions().get(id)?.let { t -> db.transactions().update(t.copy(type = if (t.direction == Direction.CREDIT) TxnType.PERSON_RECEIPT else TxnType.PERSON_PAYMENT, billSplitId = null, reviewStatus = ReviewStatus.NEEDS_REVIEW, reviewReason = com.finora.app.domain.model.ReviewReason.PURPOSE, updatedAt = clock())) } }
    }

    suspend fun delete(split: BillSplitEntity) = dao.delete(split)
}

class InvestmentRepository(private val db: FinoraDatabase, private val clock: () -> Long = System::currentTimeMillis) {
    private val dao = db.investments()

    suspend fun create(i: InvestmentEntity): Long = dao.insert(i.copy(createdAt = clock()))
    suspend fun update(i: InvestmentEntity) = dao.update(i)
    suspend fun delete(i: InvestmentEntity) = dao.delete(i)

    suspend fun addTxn(t: InvestmentTxnEntity): Long = db.withTransaction {
        val id = dao.insertTxn(t.copy(createdAt = clock()))
        // An imported bank debit/credit for this purchase is an investment move, not spending.
        t.transactionId?.let { tid ->
            db.transactions().get(tid)?.let { bank ->
                val type = if (t.kind in setOf(InvestmentTxnKind.SELL, InvestmentTxnKind.WITHDRAWAL)) TxnType.INVESTMENT_REDEMPTION
                else if (t.kind == InvestmentTxnKind.DIVIDEND) TxnType.INCOME else TxnType.INVESTMENT_PURCHASE
                db.transactions().update(bank.copy(type = type, reviewStatus = ReviewStatus.RESOLVED, reviewReason = null, updatedAt = clock()))
            }
        }
        id
    }

    suspend fun deleteTxn(t: InvestmentTxnEntity) = dao.deleteTxn(t)

    /** One Portfolio Update session: every entered price saved together, skipped ones untouched. */
    suspend fun saveValuations(entries: List<Triple<Long, Long, LocalDate>>) = db.withTransaction {
        val now = clock()
        entries.forEach { (investmentId, priceE4, date) ->
            require(priceE4 > 0) { "Prices must be above zero" }
            require(!date.isAfter(LocalDate.now().plusDays(1))) { "A valuation date cannot be in the future" }
            dao.upsertValuation(InvestmentValuationEntity(investmentId = investmentId, priceE4 = priceE4, valuationDate = date.toEpochDay(), enteredAt = now))
        }
    }

    suspend fun deleteValuation(v: InvestmentValuationEntity) = dao.deleteValuation(v)

    suspend fun addSip(plan: SipPlanEntity): Long = dao.insertSip(plan)
    suspend fun updateSip(plan: SipPlanEntity) = dao.updateSip(plan)

    /** Marking an instalment paid records the actual purchase; scheduled and actual stay separate. */
    suspend fun markSipPaid(plan: SipPlanEntity, due: LocalDate, unitsE4: Long, navE4: Long, amountPaise: Long) = db.withTransaction {
        val txnId = dao.insertTxn(
            InvestmentTxnEntity(investmentId = plan.investmentId, kind = InvestmentTxnKind.SIP, date = due.toEpochDay(), unitsE4 = unitsE4, priceE4 = navE4, amountPaise = amountPaise, createdAt = clock())
        )
        dao.upsertInstalment(SipInstalmentEntity(sipPlanId = plan.id, dueDate = due.toEpochDay(), paid = true, investmentTxnId = txnId))
    }
}

fun InvestmentTxnEntity.domain() = InvTxn(kind, LocalDate.ofEpochDay(date), unitsE4, priceE4, amountPaise, costsPaise, splitTo, splitFrom)
fun InvestmentValuationEntity.domain() = Valuation(LocalDate.ofEpochDay(valuationDate), priceE4)

/** Used by analytics: a transaction row's flow for the date math. */
fun TransactionEntity.localDate(): LocalDate = com.finora.app.core.Dates.toLocalDate(occurredAt)
