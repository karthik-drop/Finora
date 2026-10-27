package com.finora.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        AccountEntity::class, CategoryEntity::class, MerchantEntity::class, MerchantConfirmationEntity::class, MerchantAliasEntity::class,
        PersonEntity::class, TransactionEntity::class, ReceiptEntity::class, ReceiptItemEntity::class,
        BillSplitEntity::class, BillParticipantEntity::class, BillItemEntity::class, SettlementEntity::class,
        LoanEntity::class, LoanPaymentEntity::class, InvestmentEntity::class, InvestmentTxnEntity::class,
        InvestmentValuationEntity::class, SipPlanEntity::class, SipInstalmentEntity::class,
        DocumentEntity::class, DocumentLinkEntity::class, ReminderEntity::class, RecurringBillEntity::class,
        UnparsedMessageEntity::class, SettingEntity::class, PlaceLookupCacheEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
abstract class FinoraDatabase : RoomDatabase() {
    abstract fun transactions(): TransactionDao
    abstract fun accounts(): AccountDao
    abstract fun categories(): CategoryDao
    abstract fun merchants(): MerchantDao
    abstract fun people(): PersonDao
    abstract fun receipts(): ReceiptDao
    abstract fun billSplits(): BillSplitDao
    abstract fun loans(): LoanDao
    abstract fun investments(): InvestmentDao
    abstract fun documents(): DocumentDao
    abstract fun reminders(): ReminderDao
    abstract fun recurringBills(): RecurringBillDao
    abstract fun unparsedMessages(): UnparsedMessageDao
    abstract fun settings(): SettingDao
    abstract fun placeCache(): PlaceCacheDao

    companion object {
        const val NAME = "finora.db"

        fun build(context: Context, name: String = NAME): FinoraDatabase =
            Room.databaseBuilder(context.applicationContext, FinoraDatabase::class.java, name)
                .addMigrations(*Migrations.ALL)
                // Deliberately NO fallbackToDestructiveMigration: a missing migration must crash
                // in testing, never silently wipe somebody's financial records.
                .build()
    }
}
