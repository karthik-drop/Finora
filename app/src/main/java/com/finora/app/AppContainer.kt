package com.finora.app

import android.content.Context
import com.finora.app.data.db.FinoraDatabase
import com.finora.app.data.repo.AppSettings
import com.finora.app.data.repo.InvestmentRepository
import com.finora.app.data.repo.LoanRepository
import com.finora.app.data.repo.Seed
import com.finora.app.data.repo.SettingsRepository
import com.finora.app.data.repo.SplitRepository
import com.finora.app.data.repo.TransactionRepository
import com.finora.app.platform.backup.BackupManager
import com.finora.app.platform.location.LocationProvider
import com.finora.app.platform.ocr.ReceiptOcr
import com.finora.app.platform.places.NominatimPlaceSearch
import com.finora.app.platform.places.PlaceSearch
import com.finora.app.platform.reminders.ReminderScheduler
import com.finora.app.platform.sms.SmsInboxImporter
import com.finora.app.platform.vault.DocumentVault
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * The composition root: the only place that decides which concrete implementation backs each
 * dependency. Manual DI keeps the dependency graph visible and free of code generation.
 */
class AppContainer(private val context: Context) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val db: FinoraDatabase by lazy { FinoraDatabase.build(context) }
    val settings by lazy { SettingsRepository(db.settings()) }
    val transactions by lazy { TransactionRepository(db, settings) }
    val loans by lazy { LoanRepository(db) }
    val splits by lazy { SplitRepository(db) }
    val investments by lazy { InvestmentRepository(db) }
    val vault by lazy { DocumentVault(context, db) }
    val backup by lazy { BackupManager(context) { db } }
    val location by lazy { LocationProvider(context) }
    val ocr by lazy { ReceiptOcr(context) }
    val reminderScheduler by lazy { ReminderScheduler(context) }
    val smsInbox by lazy { SmsInboxImporter(context) }
    val csv by lazy { com.finora.app.data.repo.CsvService(db) }

    @Volatile private var cachedSettings = AppSettings()
    val placeSearch: PlaceSearch by lazy { NominatimPlaceSearch({ cachedSettings.placeSearchBaseUrl }, db.placeCache()) }

    fun start() {
        appScope.launch {
            Seed.run(db)
            reminderScheduler.scheduleAll(db.reminders().observeAll().first())
            settings.settings.collect { cachedSettings = it }
        }
    }
}
