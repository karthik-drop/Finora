package com.finora.app

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.finora.app.data.db.FinoraDatabase
import com.finora.app.data.repo.ImportOutcome
import com.finora.app.data.repo.Seed
import com.finora.app.data.repo.SettingsRepository
import com.finora.app.data.repo.TransactionRepository
import com.finora.app.platform.crypto.PasswordCipher
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/** On-device checks for what only a real Android runtime exercises: SQLite, the keystore-free crypto path, and the UI. */
@RunWith(AndroidJUnit4::class)
class DatabaseOnDeviceTest {
    @Test fun importPipelineOnRealSqlite() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), FinoraDatabase::class.java).build()
        Seed.run(db)
        val repo = TransactionRepository(db, SettingsRepository(db.settings()))
        val body = "Rs.450.00 debited from A/c XX1234 to VPA swiggy@icici (UPI Ref No 407312345678)."
        assertTrue(repo.importSms("AX-HDFCBK", body, System.currentTimeMillis()) is ImportOutcome.Added)
        assertTrue(repo.importSms("AX-HDFCBK", body, System.currentTimeMillis()) is ImportOutcome.Duplicate)
        assertEquals(1, db.transactions().count())
        db.close()
    }

    @Test fun backupCipherRoundTripsAndRejectsWrongPassword() {
        val plain = "finora".repeat(1000).toByteArray()
        val out = ByteArrayOutputStream()
        PasswordCipher.encryptingStream("correct horse".toCharArray(), out).use { it.write(plain) }
        val back = PasswordCipher.decryptingStream("correct horse".toCharArray(), ByteArrayInputStream(out.toByteArray())).use { it.readBytes() }
        assertArrayEquals(plain, back)
        val wrong = runCatching { PasswordCipher.decryptingStream("wrong password".toCharArray(), ByteArrayInputStream(out.toByteArray())).use { it.readBytes() } }
        assertTrue(wrong.isFailure)
    }
}

@RunWith(AndroidJUnit4::class)
class NavigationSmokeTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun homeOpensAndPrimarySectionsAreReachable() {
        compose.onNodeWithText("Your money").assertIsDisplayed()
        compose.onNodeWithText("Activity").performClick()
        compose.onNodeWithText("Transactions").assertIsDisplayed()
        compose.onNodeWithText("Invest").performClick()
        compose.onNodeWithText("Investments").assertIsDisplayed()
        compose.onNodeWithText("More").performClick()
        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithText("Automatic SMS capture").assertIsDisplayed()
    }
}
