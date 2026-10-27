package com.finora.app.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.finora.app.core.Dates
import com.finora.app.data.db.CategoryEntity
import com.finora.app.data.db.FinoraDatabase
import com.finora.app.data.db.LoanEntity
import com.finora.app.data.repo.CsvService
import com.finora.app.data.repo.ImportOutcome
import com.finora.app.data.repo.LoanRepository
import com.finora.app.data.repo.Seed
import com.finora.app.data.repo.SettingsRepository
import com.finora.app.data.repo.SplitRepository
import com.finora.app.data.repo.TransactionRepository
import com.finora.app.domain.analytics.Analytics
import com.finora.app.domain.analytics.AnalyticsTxn
import com.finora.app.domain.model.CategoryKind
import com.finora.app.domain.model.Direction
import com.finora.app.domain.model.InterestMethod
import com.finora.app.domain.model.LoanKind
import com.finora.app.domain.model.LoanPaymentKind
import com.finora.app.domain.model.MerchantStatus
import com.finora.app.domain.model.ReviewReason
import com.finora.app.domain.model.ReviewStatus
import com.finora.app.domain.model.SplitMode
import com.finora.app.domain.model.TxnType
import com.finora.app.domain.sms.SmsParser
import com.finora.app.domain.split.SplitItem
import com.finora.app.platform.places.NominatimPlaceSearch
import com.finora.app.platform.places.PlaceSearch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RepositoryIntegrationTest {
    private lateinit var db: FinoraDatabase
    private lateinit var repo: TransactionRepository
    private val zone = ZoneId.of("Asia/Kolkata")
    private var now = LocalDateTime.of(2026, 3, 14, 13, 0).atZone(zone).toInstant().toEpochMilli()
    private var food = 0L
    private var shopping = 0L

    @Before fun setUp() = runBlocking {
        Dates.zone = zone
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), FinoraDatabase::class.java).allowMainThreadQueries().build()
        Seed.run(db)
        repo = TransactionRepository(db, SettingsRepository(db.settings()), SmsParser(zone)) { now }
        food = db.categories().byName("Food & dining")!!.id
        shopping = db.categories().byName("Shopping")!!.id
    }

    @After fun tearDown() = db.close()

    private suspend fun sms(body: String, at: Long = now) = repo.importSms("AX-HDFCBK", body, at)
    private fun minutes(m: Long) = now + m * 60_000

    @Test fun theSameMessageImportedTwiceIsStoredOnce() = runBlocking {
        val body = "Rs.450.00 debited from A/c XX1234 on 14-03-26 to VPA swiggy@icici (UPI Ref No 407312345678). Avl Bal Rs 12,345.67"
        assertTrue(sms(body) is ImportOutcome.Added)
        assertTrue(sms(body) is ImportOutcome.Duplicate)
        assertEquals(1, db.transactions().count())
    }

    @Test fun bankAndUpiAppMessagesForOnePaymentBecomeOneTransaction() = runBlocking {
        assertTrue(sms("Rs 899.00 debited from A/c XX1234 to ZOMATO via UPI.") is ImportOutcome.Added)
        val second = sms("You paid Rs 899.00 to Zomato. UPI Ref: 412398765432", minutes(2))
        assertTrue(second is ImportOutcome.Duplicate)
        assertEquals(1, db.transactions().count())
        // The second message's reference was kept on the one transaction.
        assertEquals("412398765432", db.transactions().all().single().reference)
    }

    @Test fun twoRealPaymentsOfTheSameAmountAreBothKept() = runBlocking {
        sms("Rs 120.00 debited from A/c XX1234 to RAPIDO. UPI Ref 401111111111")
        sms("Rs 120.00 debited from A/c XX1234 to RAPIDO. UPI Ref 402222222222", minutes(1))
        assertEquals(2, db.transactions().count())
    }

    @Test fun aReversalCancelsTheOriginalSoNeitherCounts() = runBlocking {
        sms("Rs 300.00 debited from A/c XX1234 to BOOKMYSHOW. UPI Ref No 407300000111")
        sms("Your UPI txn of Rs 300.00 with Ref No 407300000111 has been reversed and credited to A/c XX1234.", minutes(30))
        val all = db.transactions().all()
        assertEquals(2, all.size)
        val original = all.first { it.direction == Direction.DEBIT }
        val reversal = all.first { it.direction == Direction.CREDIT }
        assertTrue(original.reversed)
        assertEquals(TxnType.REVERSAL, reversal.type)
        assertEquals(original.id, reversal.linkedTransactionId)
        val s = Analytics.summary(all.map { AnalyticsTxn(it.amountPaise, it.type, LocalDate.now(), null, null, it.reversed) })
        assertEquals(0L, s.expensePaise)
        assertEquals(0L, s.incomePaise)
    }

    @Test fun aRefundIsLinkedToThePurchaseAndTakesItsCategory() = runBlocking {
        sms("Spent INR 2,000.00 on your Credit Card ending 4321 at MYNTRA on 10-03-2026.")
        val purchase = db.transactions().all().single()
        repo.confirmMerchant(purchase.id, "Myntra", shopping, null)
        sms("Refund of Rs.799.00 from MYNTRA has been credited to your card ending 4321 on 13-03-2026.")
        val refund = db.transactions().all().first { it.type == TxnType.REFUND }
        assertEquals(purchase.id, refund.linkedTransactionId)
        assertEquals(shopping, refund.categoryId)
    }

    @Test fun debitAndCreditBetweenOwnAccountsBecomeATransfer() = runBlocking {
        sms("Rs 5,000.00 credited to A/c XX5678 on 14-03-26.") // creates account 5678
        sms("Rs 5,000.00 debited from A/c XX1234 on 14-03-26 via IMPS.", minutes(1))
        val all = db.transactions().all()
        assertTrue(all.all { it.type == TxnType.SELF_TRANSFER })
        assertTrue(all.none { it.reviewStatus == ReviewStatus.NEEDS_REVIEW })
    }

    @Test fun threeConfirmationsThenTheFourthIsAutomatic() = runBlocking {
        val bodies = (1..5).map { "Rs ${100 + it}.00 debited from A/c XX1234 to BLUE TOKAI COFFEE via UPI. UPI Ref 40000000000$it" }
        // 1st: a new merchant.
        sms(bodies[0])
        var t = db.transactions().all().last()
        assertEquals(ReviewReason.NEW_MERCHANT, t.reviewReason)
        repo.confirmMerchant(t.id, "Blue Tokai", food, null)
        // 2nd and 3rd: known, still learning, so they ask to confirm.
        for (i in 1..2) {
            now = minutes(60L * i * 24)
            sms(bodies[i])
            t = db.transactions().all().maxBy { it.id }
            assertEquals(ReviewReason.CONFIRM_MERCHANT, t.reviewReason)
            assertEquals(food, t.categoryId)
            repo.confirmMerchant(t.id, "Blue Tokai", food, null)
        }
        val m = db.merchants().byKey("blue tokai coffee")!!
        assertEquals(3, m.confirmationCount)
        assertEquals(MerchantStatus.LEARNED, m.status)
        // 4th: applied automatically, nothing to review.
        now = minutes(60L * 24 * 5)
        sms(bodies[3])
        t = db.transactions().all().maxBy { it.id }
        assertEquals(ReviewStatus.NONE, t.reviewStatus)
        assertEquals(food, t.categoryId)
        assertTrue(t.autoCategorized)
        assertEquals(m.id, t.merchantId)
    }

    @Test fun groupedPendingPaymentsCountAsOneConfirmation() = runBlocking {
        sms("Rs 101.00 debited from A/c XX1234 to THIRD WAVE COFFEE via UPI. UPI Ref 400000000001")
        sms("Rs 102.00 debited from A/c XX1234 to THIRD WAVE COFFEE via UPI. UPI Ref 400000000002", minutes(30))
        val first = db.transactions().all().first()
        repo.confirmMerchant(first.id, "Third Wave", food, null)
        assertEquals(1, db.merchants().byKey("third wave coffee")!!.confirmationCount)
        assertEquals(0, db.transactions().needsReviewCount())
    }

    @Test fun aCorrectionOverridesLearningForFutureTransactions() = runBlocking {
        repeat(3) { i ->
            now = minutes(1440L * (i + 1))
            sms("Rs 50${i}.00 debited from A/c XX1234 to DMART via UPI. UPI Ref 50000000000$i")
            repo.confirmMerchant(db.transactions().all().maxBy { it.id }.id, "DMart", food, null)
        }
        now = minutes(1440L * 5)
        sms("Rs 999.00 debited from A/c XX1234 to DMART via UPI. UPI Ref 500000000009")
        val auto = db.transactions().all().maxBy { it.id }
        assertTrue(auto.autoCategorized)
        val groceries = db.categories().byName("Groceries")!!.id
        val older = repo.correctCategory(auto.id, groceries)
        assertEquals(3, older) // the three earlier ones still say Food; the UI offers to fix them
        now = minutes(1440L * 6)
        sms("Rs 777.00 debited from A/c XX1234 to DMART via UPI. UPI Ref 500000000010")
        val next = db.transactions().all().maxBy { it.id }
        assertEquals(groceries, next.categoryId)
        assertTrue(next.autoCategorized)
    }

    @Test fun aChangedMerchantNameIsAskedAboutAndThenRemembered() = runBlocking {
        repeat(3) { i ->
            now = minutes(1440L * (i + 1))
            sms("Rs 20${i}.00 debited from A/c XX1234 to SWIGGY via UPI. UPI Ref 60000000000$i")
            repo.confirmMerchant(db.transactions().all().maxBy { it.id }.id, "Swiggy", food, null)
        }
        now = minutes(1440L * 5)
        sms("Rs 250.00 debited from A/c XX1234 to SWIGGYY via UPI. UPI Ref 600000000009")
        val t = db.transactions().all().maxBy { it.id }
        assertEquals(ReviewReason.AMBIGUOUS_MERCHANT, t.reviewReason)
        repo.confirmMerchant(t.id, "Swiggy", food, null)
        now = minutes(1440L * 6)
        sms("Rs 260.00 debited from A/c XX1234 to SWIGGYY via UPI. UPI Ref 600000000010")
        assertTrue(db.transactions().all().maxBy { it.id }.autoCategorized)
    }

    @Test fun anUnrecognisedMessageIsKeptForReviewNotGuessed() = runBlocking {
        assertEquals(ImportOutcome.Unrecognized, sms("Txn alert: INR 400.00 posted on a/c XX12 ref 99887766 at 10:42"))
        assertEquals(0, db.transactions().count())
        assertEquals(1, db.unparsedMessages().observeOpenCount().first())
    }

    @Test fun linkingALoanRepaymentRetypesTheTransactionOnceOnly() = runBlocking {
        val loans = LoanRepository(db) { now }
        val loanId = loans.create(LoanEntity(kind = LoanKind.LENT, title = "Lent to Ravi", principalPaise = 10_000_00, annualRateBps = 0, interestMethod = InterestMethod.NONE, startDate = LocalDate.of(2026, 3, 1).toEpochDay(), createdAt = 0))
        sms("Rs 4,000.00 credited to a/c XX1234 by VPA ravi.k@okhdfc (UPI Ref no 607200000001).")
        val txn = db.transactions().all().single()
        assertEquals(TxnType.PERSON_RECEIPT, txn.type)
        loans.addPayment(loanId, LoanPaymentKind.REPAYMENT, txn.amountPaise, LocalDate.of(2026, 3, 14), txn.id, null)
        assertEquals(TxnType.LENDING_REPAYMENT_RECEIVED, db.transactions().get(txn.id)!!.type)
        assertEquals(TxnType.Flow.NEUTRAL, TxnType.LENDING_REPAYMENT_RECEIVED.countsAs)
        val again = runCatching { loans.addPayment(loanId, LoanPaymentKind.REPAYMENT, txn.amountPaise, LocalDate.of(2026, 3, 14), txn.id, null) }
        assertTrue(again.isFailure)
        assertEquals(1, db.loans().allPayments().size)
    }

    @Test fun aBillSettlementFromSmsIsNotIncomeAndCannotBeRecordedTwice() = runBlocking {
        val splits = SplitRepository(db) { now }
        val id = splits.create(SplitRepository.Draft("Dinner", SplitMode.ITEMIZED, listOf(Triple("You", null, true), Triple("Asha", null, false)),
            listOf(SplitItem("Thali", 600_00, listOf(0, 1))), emptyList(), 0, 0, 0, 0, 600_00, 0, 0, now)).getOrThrow()
        val asha = db.billSplits().participants(id).first { !it.isMe }
        sms("Rs 300.00 credited to a/c XX1234 by VPA asha.m@okicici (UPI Ref no 607200000002).")
        val txn = db.transactions().all().single()
        assertTrue(splits.settle(id, asha.id, 300_00, now, txn.id, null).isSuccess)
        assertEquals(TxnType.BILL_SETTLEMENT, db.transactions().get(txn.id)!!.type)
        assertTrue(splits.settle(id, asha.id, 1, now, txn.id, null).isFailure)
        assertTrue(splits.settle(id, asha.id, 1, now, null, null).isFailure) // nothing left owed
    }

    @Test fun csvExportReimportsWithoutDuplicates() = runBlocking {
        sms("Rs 60 debited from A/c XX1234 to CHAI POINT via UPI.")
        sms("Rs 899.00 debited from A/c XX1234 to ZOMATO via UPI. UPI Ref 412398765432", minutes(10))
        val csv = CsvService(db) { now }
        val text = csv.export(null, null)
        val preview = csv.preview(text)
        assertEquals(2, preview.rows.size)
        assertEquals(0, preview.newRows.size)
        assertEquals(0, csv.commit(preview))
        assertEquals(2, db.transactions().count())
        val foreign = "date,amount,direction,merchant\n2026-03-01 10:00,1500,DEBIT,Landlord\n2026-03-01 10:00,1500,DEBIT,Landlord\n"
        val p2 = csv.preview(foreign)
        assertEquals(1, p2.newRows.size)
        assertEquals(1, csv.commit(p2))
        assertEquals(0, csv.commit(csv.preview(foreign)))
    }

    @Test fun aFailedPlaceLookupIsReportedAndACachedOneNeedsNoNetwork() = runBlocking {
        val unreachable = NominatimPlaceSearch({ "https://127.0.0.1:1" }, db.placeCache()) { now }
        val r = unreachable.search("Blue Tokai", null)
        assertTrue(r is PlaceSearch.Outcome.Failed)
        db.placeCache().put(com.finora.app.data.db.PlaceLookupCacheEntity("blue tokai|any", """[{"lat":"12.97","lon":"77.59","name":"Blue Tokai","display_name":"Blue Tokai, Indiranagar","type":"cafe","osm_type":"node","osm_id":42}]""", now))
        val cached = unreachable.search("BLUE TOKAI", null)
        assertTrue(cached is PlaceSearch.Outcome.Found && cached.fromCache && cached.candidates.single().name == "Blue Tokai")
    }

    @Test fun manualEntriesWorkWithoutAnyNetworkOrSms() = runBlocking {
        val id = repo.addManual(25_000, Direction.DEBIT, TxnType.EXPENSE, now, null, food, "Corner bakery", null, null, null, null)
        assertFalse(id == -1L)
        assertEquals(food, db.transactions().get(id)!!.categoryId)
        assertNull(db.transactions().get(id)!!.merchantId)
        db.categories().insert(CategoryEntity(name = "Pets", kind = CategoryKind.EXPENSE, iconKey = "category", colorHex = "#000000"))
        assertTrue(db.categories().all().any { it.name == "Pets" })
    }
}
