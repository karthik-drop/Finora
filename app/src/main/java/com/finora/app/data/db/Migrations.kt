package com.finora.app.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Every schema change ships a hand-written migration and an exported schema JSON in
 * app/schemas/, which MigrationTest replays. Destructive fallback is never enabled.
 */
object Migrations {
    /** v2: merchant name aliases. Additive only — no existing row is touched. */
    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `merchant_aliases` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `merchantId` INTEGER NOT NULL, " +
                    "`aliasKey` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, " +
                    "FOREIGN KEY(`merchantId`) REFERENCES `merchants`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )"
            )
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_merchant_aliases_aliasKey` ON `merchant_aliases` (`aliasKey`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_merchant_aliases_merchantId` ON `merchant_aliases` (`merchantId`)")
        }
    }

    val ALL: Array<Migration> = arrayOf(MIGRATION_1_2)
}
