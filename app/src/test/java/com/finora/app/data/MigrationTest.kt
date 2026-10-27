package com.finora.app.data

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import com.finora.app.data.db.FinoraDatabase
import com.finora.app.data.db.Migrations
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Replays the exported schemas in app/schemas: creates a v1 database with real rows, runs the
 * shipped migration and lets Room validate the result against the v2 schema. Data written at
 * v1 must survive.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), FinoraDatabase::class.java)

    @Test fun migrate1To2KeepsDataAndMatchesTheSchema() {
        helper.createDatabase("migration-test", 1).use { db ->
            db.execSQL("INSERT INTO categories (id, name, kind, iconKey, colorHex, isDefault, archived) VALUES (1, 'Food', 'EXPENSE', 'restaurant', '#000', 1, 0)")
            db.execSQL("INSERT INTO merchants (id, displayName, normalizedKey, categoryId, confirmationCount, status, sharedName, createdAt, updatedAt) VALUES (1, 'Swiggy', 'swiggy', 1, 3, 'LEARNED', 0, 0, 0)")
            db.execSQL(
                "INSERT INTO transactions (id, amountPaise, direction, type, occurredAt, merchantId, categoryId, source, fingerprint, feePaise, taxPaise, reviewStatus, autoCategorized, reversed, createdAt, updatedAt) " +
                    "VALUES (1, 45000, 'DEBIT', 'EXPENSE', 0, 1, 1, 'SMS', 'fp1', 0, 0, 'NONE', 1, 0, 0, 0)"
            )
        }
        val db = helper.runMigrationsAndValidate("migration-test", 2, true, *Migrations.ALL)
        db.query("SELECT amountPaise, merchantId FROM transactions WHERE id = 1").use { c ->
            c.moveToFirst()
            assertEquals(45000L, c.getLong(0))
            assertEquals(1L, c.getLong(1))
        }
        db.execSQL("INSERT INTO merchant_aliases (merchantId, aliasKey, createdAt) VALUES (1, 'bundl technologies', 0)")
        db.query("SELECT COUNT(*) FROM merchant_aliases").use { c -> c.moveToFirst(); assertEquals(1, c.getInt(0)) }
        db.close()
    }
}
