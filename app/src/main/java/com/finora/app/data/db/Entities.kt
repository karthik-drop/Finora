package com.finora.app.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.finora.app.domain.model.AccountType
import com.finora.app.domain.model.CategoryKind
import com.finora.app.domain.model.CompoundingFrequency
import com.finora.app.domain.model.ConfirmationKind
import com.finora.app.domain.model.Direction
import com.finora.app.domain.model.DocumentKind
import com.finora.app.domain.model.InterestMethod
import com.finora.app.domain.model.InvestmentTxnKind
import com.finora.app.domain.model.InvestmentType
import com.finora.app.domain.model.LinkTarget
import com.finora.app.domain.model.LoanKind
import com.finora.app.domain.model.LoanPaymentKind
import com.finora.app.domain.model.LoanStatus
import com.finora.app.domain.model.MerchantStatus
import com.finora.app.domain.model.PaymentMethod
import com.finora.app.domain.model.ReviewReason
import com.finora.app.domain.model.ReviewStatus
import com.finora.app.domain.model.SplitMode
import com.finora.app.domain.model.TxnSource
import com.finora.app.domain.model.TxnType

// Amounts are integer paise. Dates that are calendar days are epoch days; instants are epoch millis.

@Entity(tableName = "accounts", indices = [Index("maskedNumber")])
data class AccountEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val type: AccountType,
    /** Last digits only, e.g. "1234". Never the full number. */
    val maskedNumber: String?,
    /** A balance the user typed, with the day it was true. Null = unknown, never zero. */
    val manualBalancePaise: Long? = null,
    val manualBalanceAt: Long? = null,
    val archived: Boolean = false,
    val createdAt: Long,
)

@Entity(tableName = "categories", indices = [Index(value = ["name", "kind"], unique = true)])
data class CategoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val kind: CategoryKind,
    val iconKey: String,
    val colorHex: String,
    val isDefault: Boolean = false,
    val archived: Boolean = false,
)

@Entity(
    tableName = "merchants",
    foreignKeys = [ForeignKey(entity = CategoryEntity::class, parentColumns = ["id"], childColumns = ["categoryId"], onDelete = ForeignKey.SET_NULL)],
    indices = [Index(value = ["normalizedKey"], unique = true), Index("categoryId")],
)
data class MerchantEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val displayName: String,
    /** The key SMS text is matched on — see MerchantNormalizer. */
    val normalizedKey: String,
    val categoryId: Long?,
    /** Explicit confirmations of THIS name -> category mapping. */
    val confirmationCount: Int = 0,
    val status: MerchantStatus = MerchantStatus.LEARNING,
    /** Set by the user when several businesses share this name: always ask. */
    val sharedName: Boolean = false,
    val address: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val osmRef: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(
    tableName = "merchant_confirmations",
    foreignKeys = [
        ForeignKey(entity = MerchantEntity::class, parentColumns = ["id"], childColumns = ["merchantId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("merchantId"), Index("transactionId")],
)
data class MerchantConfirmationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val merchantId: Long,
    val transactionId: Long?,
    val categoryId: Long?,
    val kind: ConfirmationKind,
    val createdAt: Long,
)

/**
 * Another normalised name that is the SAME business as [merchantId] — recorded when the user
 * confirms that "AMAZON PAY INDIA" is their "Amazon". Added in schema v2.
 */
@Entity(
    tableName = "merchant_aliases",
    foreignKeys = [ForeignKey(entity = MerchantEntity::class, parentColumns = ["id"], childColumns = ["merchantId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["aliasKey"], unique = true), Index("merchantId")],
)
data class MerchantAliasEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val merchantId: Long,
    val aliasKey: String,
    val createdAt: Long,
)

@Entity(tableName = "people", indices = [Index("normalizedKey"), Index("upiId")])
data class PersonEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val normalizedKey: String,
    val phone: String? = null,
    val upiId: String? = null,
    val notes: String? = null,
    val createdAt: Long,
)

@Entity(
    tableName = "transactions",
    foreignKeys = [
        ForeignKey(entity = AccountEntity::class, parentColumns = ["id"], childColumns = ["accountId"], onDelete = ForeignKey.SET_NULL),
        ForeignKey(entity = MerchantEntity::class, parentColumns = ["id"], childColumns = ["merchantId"], onDelete = ForeignKey.SET_NULL),
        ForeignKey(entity = PersonEntity::class, parentColumns = ["id"], childColumns = ["personId"], onDelete = ForeignKey.SET_NULL),
        ForeignKey(entity = CategoryEntity::class, parentColumns = ["id"], childColumns = ["categoryId"], onDelete = ForeignKey.SET_NULL),
    ],
    indices = [
        Index(value = ["fingerprint"], unique = true),
        Index("occurredAt"), Index("accountId"), Index("merchantId"), Index("personId"),
        Index("categoryId"), Index("reviewStatus"), Index("reference"), Index("loanId"), Index("billSplitId"),
    ],
)
data class TransactionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val amountPaise: Long,
    val direction: Direction,
    val type: TxnType,
    val occurredAt: Long,
    val accountId: Long? = null,
    val merchantId: Long? = null,
    val personId: Long? = null,
    val categoryId: Long? = null,
    /** Separate from category: a dining merchant can be a work meal or a shared bill. */
    val purpose: String? = null,
    /** The counterparty text exactly as the message gave it. */
    val rawCounterparty: String? = null,
    val reference: String? = null,
    val paymentMethod: PaymentMethod? = null,
    val balanceAfterPaise: Long? = null,
    val feePaise: Long = 0,
    val taxPaise: Long = 0,
    val notes: String? = null,
    val source: TxnSource,
    /** Dedupe key; UNIQUE so a repeated import can never insert twice. */
    val fingerprint: String,
    val smsSender: String? = null,
    val smsBody: String? = null,
    val reviewStatus: ReviewStatus = ReviewStatus.NONE,
    val reviewReason: ReviewReason? = null,
    /** True when the category came from a learned merchant rather than a person choosing it. */
    val autoCategorized: Boolean = false,
    /** The original debit a refund/reversal belongs to. */
    val linkedTransactionId: Long? = null,
    /** A reversed transaction stays visible but counts for nothing. */
    val reversed: Boolean = false,
    val loanId: Long? = null,
    val billSplitId: Long? = null,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(
    tableName = "receipts",
    indices = [Index("transactionId"), Index("documentId")],
)
data class ReceiptEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val transactionId: Long? = null,
    val documentId: Long? = null,
    val merchantName: String?,
    val receiptDate: Long?,
    val subtotalPaise: Long,
    val taxPaise: Long,
    val discountPaise: Long,
    val servicePaise: Long,
    val totalPaise: Long,
    val rawText: String?,
    val createdAt: Long,
)

@Entity(
    tableName = "receipt_items",
    foreignKeys = [ForeignKey(entity = ReceiptEntity::class, parentColumns = ["id"], childColumns = ["receiptId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("receiptId")],
)
data class ReceiptItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val receiptId: Long,
    val name: String,
    /** Quantity × 1000, so 1.5 kg is 1500. */
    val quantityMilli: Long,
    val unitPricePaise: Long,
    val totalPaise: Long,
    val position: Int,
)

@Entity(
    tableName = "bill_splits",
    indices = [Index("receiptId"), Index("transactionId"), Index("occurredAt")],
)
data class BillSplitEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val receiptId: Long? = null,
    val transactionId: Long? = null,
    val mode: SplitMode,
    val subtotalPaise: Long,
    val taxPaise: Long,
    val discountPaise: Long,
    val servicePaise: Long,
    val totalPaise: Long,
    /** Explicit, user-accepted difference between the bill total and its parts (rounding on the printed bill). */
    val roundingAdjustmentPaise: Long = 0,
    /** Participant id of whoever paid the bill. */
    val paidByParticipantId: Long? = null,
    val occurredAt: Long,
    val notes: String? = null,
    val createdAt: Long,
)

@Entity(
    tableName = "bill_participants",
    foreignKeys = [
        ForeignKey(entity = BillSplitEntity::class, parentColumns = ["id"], childColumns = ["billSplitId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = PersonEntity::class, parentColumns = ["id"], childColumns = ["personId"], onDelete = ForeignKey.SET_NULL),
    ],
    indices = [Index("billSplitId"), Index("personId")],
)
data class BillParticipantEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val billSplitId: Long,
    /** Null = the user themself. */
    val personId: Long?,
    val displayName: String,
    val isMe: Boolean,
    val subtotalSharePaise: Long,
    val taxSharePaise: Long,
    val discountSharePaise: Long,
    val serviceSharePaise: Long,
    val totalSharePaise: Long,
)

@Entity(
    tableName = "bill_items",
    foreignKeys = [ForeignKey(entity = BillSplitEntity::class, parentColumns = ["id"], childColumns = ["billSplitId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("billSplitId")],
)
data class BillItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val billSplitId: Long,
    val label: String,
    val amountPaise: Long,
    /** Comma-separated participant ids sharing this item, equally. */
    val sharedBy: String,
    val position: Int,
)

@Entity(
    tableName = "settlements",
    foreignKeys = [
        ForeignKey(entity = BillSplitEntity::class, parentColumns = ["id"], childColumns = ["billSplitId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = BillParticipantEntity::class, parentColumns = ["id"], childColumns = ["participantId"], onDelete = ForeignKey.CASCADE),
    ],
    // One SMS-imported transaction can settle at most one share, ever.
    indices = [Index("billSplitId"), Index("participantId"), Index(value = ["transactionId"], unique = true)],
)
data class SettlementEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val billSplitId: Long,
    val participantId: Long,
    val amountPaise: Long,
    val occurredAt: Long,
    val transactionId: Long? = null,
    val note: String? = null,
)

@Entity(
    tableName = "loans",
    foreignKeys = [ForeignKey(entity = PersonEntity::class, parentColumns = ["id"], childColumns = ["personId"], onDelete = ForeignKey.SET_NULL)],
    indices = [Index("personId"), Index("status"), Index("dueDate")],
)
data class LoanEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val kind: LoanKind,
    val title: String,
    val personId: Long? = null,
    val lenderName: String? = null,
    val principalPaise: Long,
    /** 12.5% a year = 1250. */
    val annualRateBps: Int,
    val interestMethod: InterestMethod,
    val compounding: CompoundingFrequency = CompoundingFrequency.MONTHLY,
    val startDate: Long,
    val dueDate: Long? = null,
    /** Months; for a formal EMI loan. */
    val tenureMonths: Int? = null,
    val emiPaise: Long? = null,
    val status: LoanStatus = LoanStatus.ACTIVE,
    val closedAt: Long? = null,
    val remindDaysBefore: Int = 2,
    val notes: String? = null,
    val createdAt: Long,
)

@Entity(
    tableName = "loan_payments",
    foreignKeys = [ForeignKey(entity = LoanEntity::class, parentColumns = ["id"], childColumns = ["loanId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("loanId"), Index(value = ["transactionId"], unique = true)],
)
data class LoanPaymentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val loanId: Long,
    val kind: LoanPaymentKind,
    val amountPaise: Long,
    /** Epoch day. */
    val date: Long,
    /** The imported transaction this payment IS — linking prevents counting it twice. */
    val transactionId: Long? = null,
    val note: String? = null,
    val createdAt: Long,
)

@Entity(tableName = "investments", indices = [Index("type")])
data class InvestmentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val symbol: String?,
    val type: InvestmentType,
    val notes: String? = null,
    val archived: Boolean = false,
    val createdAt: Long,
)

@Entity(
    tableName = "investment_transactions",
    foreignKeys = [ForeignKey(entity = InvestmentEntity::class, parentColumns = ["id"], childColumns = ["investmentId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("investmentId"), Index("date"), Index(value = ["transactionId"], unique = true)],
)
data class InvestmentTxnEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val investmentId: Long,
    val kind: InvestmentTxnKind,
    /** Epoch day. */
    val date: Long,
    /** Units × 10,000. Signed only for UNIT_ADJUSTMENT. */
    val unitsE4: Long = 0,
    /** Per-unit price in rupees × 10,000. */
    val priceE4: Long = 0,
    /** Cash that moved, excluding costs. */
    val amountPaise: Long = 0,
    /** Brokerage, STT, stamp duty, exit load. */
    val costsPaise: Long = 0,
    /** SPLIT: new units per [splitFrom] old units. */
    val splitTo: Int = 0,
    val splitFrom: Int = 0,
    val transactionId: Long? = null,
    val note: String? = null,
    val createdAt: Long,
)

@Entity(
    tableName = "investment_valuations",
    foreignKeys = [ForeignKey(entity = InvestmentEntity::class, parentColumns = ["id"], childColumns = ["investmentId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["investmentId", "valuationDate"], unique = true), Index("valuationDate")],
)
data class InvestmentValuationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val investmentId: Long,
    /** Manually entered price or NAV, rupees × 10,000. */
    val priceE4: Long,
    /** Epoch day the price was true for. */
    val valuationDate: Long,
    val enteredAt: Long,
)

@Entity(
    tableName = "sip_plans",
    foreignKeys = [ForeignKey(entity = InvestmentEntity::class, parentColumns = ["id"], childColumns = ["investmentId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("investmentId")],
)
data class SipPlanEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val investmentId: Long,
    val amountPaise: Long,
    val dayOfMonth: Int,
    val startDate: Long,
    val endDate: Long? = null,
    val active: Boolean = true,
)

@Entity(
    tableName = "sip_instalments",
    foreignKeys = [ForeignKey(entity = SipPlanEntity::class, parentColumns = ["id"], childColumns = ["sipPlanId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["sipPlanId", "dueDate"], unique = true), Index("investmentTxnId")],
)
data class SipInstalmentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sipPlanId: Long,
    val dueDate: Long,
    val paid: Boolean,
    val investmentTxnId: Long? = null,
)

@Entity(tableName = "documents", indices = [Index("kind"), Index("createdAt")])
data class DocumentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val kind: DocumentKind,
    /** Name of the encrypted file inside the app's private vault directory. */
    val storedFileName: String,
    val originalFileName: String?,
    val mimeType: String,
    val sizeBytes: Long,
    /** Comma-separated, lower case. */
    val tags: String = "",
    val notes: String? = null,
    val createdAt: Long,
)

@Entity(
    tableName = "document_links",
    primaryKeys = ["documentId", "target", "targetId"],
    foreignKeys = [ForeignKey(entity = DocumentEntity::class, parentColumns = ["id"], childColumns = ["documentId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["target", "targetId"])],
)
data class DocumentLinkEntity(
    val documentId: Long,
    val target: LinkTarget,
    val targetId: Long,
)

@Entity(tableName = "reminders", indices = [Index(value = ["key"], unique = true)])
data class ReminderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Stable id: "review_afternoon", "review_evening", "portfolio". */
    val key: String,
    val title: String,
    /** Minutes after midnight. */
    val timeOfDayMinutes: Int,
    val intervalDays: Int,
    val enabled: Boolean,
    /** Epoch day the interval is counted from (portfolio reminder). */
    val anchorDate: Long? = null,
)

@Entity(tableName = "recurring_bills", indices = [Index("active")])
data class RecurringBillEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val amountPaise: Long,
    val dayOfMonth: Int,
    val categoryId: Long? = null,
    val isSubscription: Boolean = false,
    val active: Boolean = true,
    val createdAt: Long,
)

/** A message that looked financial but no rule could read. Kept for review, never sent anywhere. */
@Entity(tableName = "unparsed_messages", indices = [Index(value = ["fingerprint"], unique = true)])
data class UnparsedMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sender: String,
    val body: String,
    val receivedAt: Long,
    val fingerprint: String,
    val dismissed: Boolean = false,
)

@Entity(tableName = "settings")
data class SettingEntity(
    @PrimaryKey val key: String,
    val value: String,
)

@Entity(tableName = "place_lookup_cache")
data class PlaceLookupCacheEntity(
    /** Normalised name + rounded coordinates, so a repeated lookup never hits the network. */
    @PrimaryKey val queryKey: String,
    val resultJson: String,
    val fetchedAt: Long,
)
