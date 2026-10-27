package com.finora.app.ui.transactions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.finora.app.AppContainer
import com.finora.app.core.Dates
import com.finora.app.core.Money
import com.finora.app.domain.model.CategoryKind
import com.finora.app.domain.model.Direction
import com.finora.app.domain.model.PaymentMethod
import com.finora.app.domain.model.TxnType
import com.finora.app.ui.appViewModel
import com.finora.app.ui.components.DateField
import com.finora.app.ui.components.ErrorNote
import com.finora.app.ui.components.FinoraTopBar
import com.finora.app.ui.components.MoneyField
import com.finora.app.ui.components.SelectField
import com.finora.app.ui.components.TimeField
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime

class AddTransactionViewModel(private val c: AppContainer) : ViewModel() {
    val categories = c.db.categories().observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val accounts = c.db.accounts().observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val people = c.db.people().observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun save(amount: Long, direction: Direction, type: TxnType, at: Long, accountId: Long?, categoryId: Long?, merchant: String?, personId: Long?, purpose: String?, method: PaymentMethod?, notes: String?, done: () -> Unit) =
        viewModelScope.launch {
            c.transactions.addManual(amount, direction, type, at, accountId, categoryId, merchant, personId, purpose?.ifBlank { null }, method, notes?.ifBlank { null })
            done()
        }
}

/** Every field is rememberSaveable, so rotation or the process being killed in the background does not lose the form. */
@Composable
fun AddTransactionScreen(back: () -> Unit) {
    val vm = appViewModel { AddTransactionViewModel(it) }
    val cats by vm.categories.collectAsStateWithLifecycle()
    val accounts by vm.accounts.collectAsStateWithLifecycle()
    val people by vm.people.collectAsStateWithLifecycle()

    var amount by rememberSaveable { mutableStateOf("") }
    var direction by rememberSaveable { mutableStateOf(Direction.DEBIT) }
    var type by rememberSaveable { mutableStateOf(TxnType.EXPENSE) }
    var date by rememberSaveable { mutableStateOf(Dates.today().toEpochDay()) }
    var minutes by rememberSaveable { mutableStateOf(LocalTime.now().let { it.hour * 60 + it.minute }) }
    var accountId by rememberSaveable { mutableStateOf<Long?>(null) }
    var categoryId by rememberSaveable { mutableStateOf<Long?>(null) }
    var merchant by rememberSaveable { mutableStateOf("") }
    var personId by rememberSaveable { mutableStateOf<Long?>(null) }
    var purpose by rememberSaveable { mutableStateOf("") }
    var method by rememberSaveable { mutableStateOf<PaymentMethod?>(null) }
    var notes by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    Scaffold(contentWindowInsets = WindowInsets(0), topBar = { FinoraTopBar("Add transaction", back) }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf(Direction.DEBIT to "Money out", Direction.CREDIT to "Money in").forEachIndexed { i, (d, label) ->
                    SegmentedButton(selected = direction == d, onClick = {
                        direction = d
                        type = if (d == Direction.DEBIT) TxnType.EXPENSE else TxnType.INCOME
                        categoryId = null
                    }, shape = SegmentedButtonDefaults.itemShape(i, 2)) { Text(label) }
                }
            }
            MoneyField("Amount", amount, { amount = it })
            SelectField("Type", TxnType.entries.filter { compatible(it, direction) && it != TxnType.REVERSAL }, type, { it.label }, { type = it })
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                DateField("Date", LocalDate.ofEpochDay(date), { date = it.toEpochDay() }, Modifier.weight(1f), maxDate = Dates.today())
                TimeField("Time", minutes, { minutes = it }, Modifier.weight(1f))
            }
            val kind = if (direction == Direction.DEBIT) CategoryKind.EXPENSE else CategoryKind.INCOME
            if (type.countsAs != TxnType.Flow.NEUTRAL) SelectField("Category", cats.filter { it.kind == kind }, cats.firstOrNull { it.id == categoryId }, { it.name }, { categoryId = it.id })
            OutlinedTextField(merchant, { merchant = it }, label = { Text(if (direction == Direction.DEBIT) "Paid to (merchant)" else "Received from") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            SelectField("Person", listOf<com.finora.app.data.db.PersonEntity?>(null) + people, people.firstOrNull { it.id == personId }, { it?.name ?: "None" }, { personId = it?.id }, placeholder = "None")
            SelectField("Account", listOf<com.finora.app.data.db.AccountEntity?>(null) + accounts, accounts.firstOrNull { it.id == accountId }, { it?.name ?: "None" }, { accountId = it?.id }, placeholder = "None")
            SelectField("Method", PaymentMethod.entries.toList(), method, { it.label }, { method = it }, placeholder = "Not specified")
            OutlinedTextField(purpose, { purpose = it }, label = { Text("Purpose (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(notes, { notes = it }, label = { Text("Notes (optional)") }, minLines = 2, modifier = Modifier.fillMaxWidth())
            ErrorNote(error)
            Button(onClick = {
                val paise = Money.parseToPaise(amount)
                when {
                    paise == null || paise <= 0 -> error = "Enter an amount above zero."
                    else -> {
                        val at = LocalDate.ofEpochDay(date).atTime(minutes / 60, minutes % 60).atZone(Dates.zone).toInstant().toEpochMilli()
                        vm.save(paise, direction, type, at, accountId, categoryId, merchant.ifBlank { null }, personId, purpose, method, notes, back)
                    }
                }
            }, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Save", style = MaterialTheme.typography.titleSmall) }
            Spacer(Modifier.height(24.dp))
        }
    }
}
