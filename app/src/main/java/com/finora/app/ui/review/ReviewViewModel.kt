package com.finora.app.ui.review

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.finora.app.AppContainer
import com.finora.app.core.Dates
import com.finora.app.data.db.LoanEntity
import com.finora.app.data.db.TxnRow
import com.finora.app.data.db.UnparsedMessageEntity
import com.finora.app.data.repo.TransactionRepository
import com.finora.app.domain.merchant.MerchantNormalizer
import com.finora.app.domain.merchant.PlaceRanking
import com.finora.app.domain.merchant.RankedPlace
import com.finora.app.domain.model.InterestMethod
import com.finora.app.domain.model.LoanKind
import com.finora.app.domain.model.LoanPaymentKind
import com.finora.app.domain.model.ReviewReason
import com.finora.app.domain.model.TxnType
import com.finora.app.platform.location.LocationProvider
import com.finora.app.platform.places.PlaceSearch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** One card on the review screen: transactions that one decision resolves. */
data class ReviewGroup(val key: String, val reason: ReviewReason, val title: String, val rows: List<TxnRow>) {
    val lead: TxnRow get() = rows.first()
}

sealed interface LookupState {
    data object Idle : LookupState
    data object Working : LookupState
    data class Done(val places: List<RankedPlace>, val note: String?) : LookupState
    data class Failed(val message: String) : LookupState
}

class ReviewViewModel(private val c: AppContainer) : ViewModel() {
    val settings = c.settings.settings.stateIn(viewModelScope, SharingStarted.Eagerly, com.finora.app.data.repo.AppSettings())
    val categories = c.db.categories().observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val people = c.db.people().observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val loans = c.db.loans().observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val splits = c.db.billSplits().observeSummaries().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val unparsed = c.db.unparsedMessages().observeOpen().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val merchants = c.db.merchants().observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Similar tasks grouped: every pending payment to one merchant (or one UPI id) is one card. */
    val groups = combine(c.db.transactions().observeNeedsReview(), merchants) { rows, _ ->
        rows.groupBy { r ->
            val reason = r.txn.reviewReason ?: ReviewReason.CLASSIFICATION
            val who = r.txn.merchantId?.toString() ?: r.txn.rawCounterparty?.let(MerchantNormalizer::key)?.ifBlank { null } ?: "txn${r.txn.id}"
            if (reason == ReviewReason.PURPOSE || reason == ReviewReason.CLASSIFICATION || reason == ReviewReason.UNKNOWN_RECIPIENT) "${reason.name}:txn${r.txn.id}" else "${reason.name}:$who"
        }.map { (k, list) ->
            val lead = list.first()
            ReviewGroup(k, lead.txn.reviewReason ?: ReviewReason.CLASSIFICATION, lead.merchantName ?: lead.personName ?: lead.txn.rawCounterparty?.let(MerchantNormalizer::displayName) ?: lead.txn.type.label, list)
        }.sortedWith(compareBy({ it.reason.ordinal }, { -it.lead.txn.occurredAt }))
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val lookup = MutableStateFlow<LookupState>(LookupState.Idle)
    val message = MutableStateFlow<String?>(null)

    fun hasLocationPermission() = c.location.hasPermission()

    fun resetLookup() { lookup.value = LookupState.Idle }

    /**
     * Only for a NEW merchant, only on request, only if the user enabled lookups. The current
     * location is context for ranking, not evidence of where the payment happened.
     */
    fun findNearby(row: TxnRow) = viewModelScope.launch {
        val s = settings.value
        if (!s.placeLookupEnabled) { lookup.value = LookupState.Failed("Online merchant lookup is off. Turn it on in Settings › Location & maps."); return@launch }
        lookup.value = LookupState.Working
        val name = row.txn.rawCounterparty ?: return@launch run { lookup.value = LookupState.Failed("This message has no merchant name to search for.") }
        val loc = if (s.locationEnabled) c.location.current() else LocationProvider.Result.NoPermission
        val here = (loc as? LocationProvider.Result.Found)?.point
        val ageMinutes = (System.currentTimeMillis() - row.txn.occurredAt) / 60_000
        val note = when {
            here == null && s.locationEnabled -> "Location unavailable, so results are not sorted by distance."
            here == null -> "Searched without your location."
            ageMinutes > 60 -> "This payment was ${if (ageMinutes > 1440) "${ageMinutes / 1440} days" else "${ageMinutes / 60} hours"} ago. Results are near where you are NOW, which may not be where you paid."
            else -> "Sorted by name match and distance from you. Being nearby does not prove this is the place."
        }
        lookup.value = when (val r = c.placeSearch.search(name, here)) {
            is PlaceSearch.Outcome.Found -> {
                val ranked = PlaceRanking.rank(name, here, r.candidates).take(6)
                if (ranked.isEmpty()) LookupState.Failed("No matching businesses found. Enter the details yourself.") else LookupState.Done(ranked, note)
            }
            is PlaceSearch.Outcome.Failed -> LookupState.Failed(r.message())
        }
    }

    private fun PlaceSearch.Outcome.Failed.message() = reason

    fun confirm(group: ReviewGroup, name: String, categoryId: Long, purpose: String?, place: RankedPlace?, shared: Boolean) = viewModelScope.launch {
        val r = c.transactions.confirmMerchant(
            group.lead.txn.id, name, categoryId, purpose?.ifBlank { null },
            place?.let { TransactionRepository.PlaceInfo(it.place.address, it.place.point.lat, it.place.point.lon, it.place.osmRef) }, shared,
        )
        message.value = when {
            r.learnedNow -> "Learned. Future payments to $name will be categorised automatically."
            r.corrected -> "Updated. $name now uses the category you chose."
            else -> null
        }
        lookup.value = LookupState.Idle
    }

    fun resolve(txnId: Long, type: TxnType, personId: Long?, categoryId: Long?, purpose: String?) = viewModelScope.launch {
        c.transactions.resolveType(txnId, type, personId, categoryId, purpose?.ifBlank { null })
    }

    fun lendOrBorrow(row: TxnRow, personId: Long?, personName: String, lent: Boolean) = viewModelScope.launch {
        c.loans.create(
            LoanEntity(kind = if (lent) LoanKind.LENT else LoanKind.BORROWED, title = (if (lent) "Lent to " else "Borrowed from ") + personName, personId = personId,
                principalPaise = row.txn.amountPaise, annualRateBps = 0, interestMethod = InterestMethod.NONE, startDate = Dates.toLocalDate(row.txn.occurredAt).toEpochDay(), createdAt = 0),
            linkTransactionId = row.txn.id,
        )
        message.value = "Recorded as a loan. Add an interest rate or due date from Loans."
    }

    fun linkLoan(row: TxnRow, loan: LoanEntity, kind: LoanPaymentKind) = viewModelScope.launch {
        runCatching { c.loans.addPayment(loan.id, kind, row.txn.amountPaise, Dates.toLocalDate(row.txn.occurredAt), row.txn.id, null) }
            .onFailure { message.value = it.message }
    }

    fun settle(row: TxnRow, splitId: Long, participantId: Long) = viewModelScope.launch {
        c.splits.settle(splitId, participantId, row.txn.amountPaise, row.txn.occurredAt, row.txn.id, null).onFailure { message.value = it.message }
    }

    suspend fun participants(splitId: Long) = c.db.billSplits().participants(splitId)

    suspend fun newPerson(name: String, upi: String?): Long = c.transactions.createPerson(name, upi)

    fun dismiss(group: ReviewGroup) = viewModelScope.launch { group.rows.forEach { c.transactions.dismissReview(it.txn.id) } }
    fun dismissMessage(m: UnparsedMessageEntity) = viewModelScope.launch { c.db.unparsedMessages().update(m.copy(dismissed = true)) }
    fun setLocationEnabled(on: Boolean) = viewModelScope.launch { c.settings.set(com.finora.app.data.repo.SettingsRepository.Keys.LOCATION, on) }
}
