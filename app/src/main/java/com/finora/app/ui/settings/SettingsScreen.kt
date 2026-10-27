package com.finora.app.ui.settings

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.finora.app.AppContainer
import com.finora.app.core.Dates
import com.finora.app.data.db.ReminderEntity
import com.finora.app.data.repo.AppSettings
import com.finora.app.data.repo.SettingsRepository.Keys
import com.finora.app.data.repo.ThemeMode
import com.finora.app.ui.Routes
import com.finora.app.ui.appViewModel
import com.finora.app.ui.components.FinoraTopBar
import com.finora.app.ui.components.InfoNote
import com.finora.app.ui.components.SectionCard
import com.finora.app.ui.components.SectionHeader
import com.finora.app.ui.components.TimeField
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(private val c: AppContainer) : ViewModel() {
    val settings = c.settings.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())
    val reminders = c.db.reminders().observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun set(key: String, v: Boolean) = viewModelScope.launch { c.settings.set(key, v) }
    fun set(key: String, v: String) = viewModelScope.launch { c.settings.set(key, v) }
    fun setSet(key: String, v: String) = viewModelScope.launch { c.settings.setSet(key, v.split(',', '\n').toSet()) }

    fun updateReminder(r: ReminderEntity) = viewModelScope.launch {
        c.db.reminders().update(r)
        c.reminderScheduler.schedule(r)
    }

    suspend fun importInbox(days: Int) = c.smsInbox.import(System.currentTimeMillis() - days * 86_400_000L) { s, b, at -> c.transactions.importSms(s, b, at) }
}

@Composable
fun SettingsScreen(open: (String) -> Unit, back: (() -> Unit)?) {
    val vm = appViewModel { SettingsViewModel(it) }
    val s by vm.settings.collectAsStateWithLifecycle()
    val reminders by vm.reminders.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snack = remember { SnackbarHostState() }
    var smsExplain by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    var importDays by remember { mutableStateOf<Int?>(null) }
    var locExplain by remember { mutableStateOf(false) }

    var hasSms by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) == android.content.pm.PackageManager.PERMISSION_GRANTED) }
    val smsPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        val ok = granted[Manifest.permission.RECEIVE_SMS] == true
        hasSms = granted[Manifest.permission.READ_SMS] == true
        vm.set(Keys.SMS, ok)
        if (!ok) scope.launch { snack.showSnackbar("SMS permission was not granted. You can still add transactions by hand or import a CSV.") }
    }
    val readSms = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        hasSms = ok
        if (!ok) { importDays = null; scope.launch { snack.showSnackbar("Reading the inbox needs SMS permission.") } }
    }
    val locPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        vm.set(Keys.LOCATION, granted.values.any { it })
        if (granted.values.none { it }) scope.launch { snack.showSnackbar("Location stays off. Merchant search still works without it.") }
    }
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val canNotify = com.finora.app.platform.reminders.ReminderScheduler.canNotify(context)

    LaunchedEffect(importDays, hasSms) {
        val d = importDays ?: return@LaunchedEffect
        if (!hasSms) return@LaunchedEffect
        importing = true
        val r = vm.importInbox(d)
        importing = false; importDays = null
        snack.showSnackbar("Read ${r.scanned} messages: ${r.added} added, ${r.duplicates} already recorded, ${r.unrecognized} need a look.")
    }

    Scaffold(contentWindowInsets = WindowInsets(0), topBar = { FinoraTopBar("Settings", back) }, snackbarHost = { SnackbarHost(snack) }) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(20.dp, 4.dp, 20.dp, 40.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { SectionHeader("Appearance") }
            item {
                SectionCard {
                    Text("Theme", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(8.dp))
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        ThemeMode.entries.forEachIndexed { i, m ->
                            SegmentedButton(selected = s.themeMode == m, onClick = { vm.set(Keys.THEME, m.name) }, shape = SegmentedButtonDefaults.itemShape(i, 3)) {
                                Text(m.name.lowercase().replaceFirstChar(Char::uppercase))
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Text("Currency", style = MaterialTheme.typography.titleSmall)
                    Text("Indian Rupee (₹, INR). Amounts are stored as exact paise.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            item { SectionHeader("Automatic SMS capture") }
            item {
                SectionCard {
                    ToggleRow("Read bank & UPI messages", "Parsed on this phone only. Messages are never sent anywhere.", s.smsCaptureEnabled) { on ->
                        if (on) smsExplain = true else vm.set(Keys.SMS, false)
                    }
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    Text("Import past messages", style = MaterialTheme.typography.titleSmall)
                    Text("Reads your inbox once. Duplicates are recognised and skipped.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    if (importing) Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.width(20.dp).height(20.dp), strokeWidth = 2.dp); Spacer(Modifier.width(10.dp)); Text("Reading messages…") }
                    else Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(30, 90, 365).forEach { d ->
                            OutlinedButton(onClick = { importDays = d; if (!hasSms) readSms.launch(Manifest.permission.READ_SMS) }) { Text(if (d == 365) "1 year" else "$d days") }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("Not every bank's wording can be read. Messages Finora cannot understand go to Review instead of being guessed.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            item { SectionHeader("Review") }
            item {
                SectionCard {
                    ToggleRow("Ask about unexplained credits", "Money arriving without a reason is confirmed before it counts as income.", s.reviewUnknownCredits) { vm.set(Keys.REVIEW_CREDITS, it) }
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    var upi by remember(s.ownUpiIds) { mutableStateOf(s.ownUpiIds.joinToString(", ")) }
                    var names by remember(s.ownNames) { mutableStateOf(s.ownNames.joinToString(", ")) }
                    Text("Your own identities", style = MaterialTheme.typography.titleSmall)
                    Text("Payments to these are transfers between your own accounts, not spending.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedTextField(upi, { upi = it }, label = { Text("Your UPI ids, comma separated") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(names, { names = it }, label = { Text("Names your bank uses for you") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    TextButton(onClick = { vm.setSet(Keys.OWN_UPI, upi); vm.setSet(Keys.OWN_NAMES, names) }) { Text("Save") }
                }
            }

            item { SectionHeader("Reminders") }
            item {
                SectionCard {
                    if (!canNotify && Build.VERSION.SDK_INT >= 33) {
                        InfoNote("Notifications are off for Finora, so reminders cannot appear.")
                        TextButton(onClick = { notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS) }) { Text("Allow notifications") }
                    }
                    reminders.forEach { r ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(r.title, style = MaterialTheme.typography.titleSmall)
                                Text(if (r.intervalDays > 1) "Every ${r.intervalDays} days" else "Daily", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(r.enabled, { vm.updateReminder(r.copy(enabled = it)) })
                        }
                        if (r.enabled) {
                            TimeField("Time", r.timeOfDayMinutes, { vm.updateReminder(r.copy(timeOfDayMinutes = it)) })
                            if (r.intervalDays > 1) Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                                listOf(7, 14, 30).forEach { d -> OutlinedButton(onClick = { vm.updateReminder(r.copy(intervalDays = d, anchorDate = Dates.today().toEpochDay())) }, enabled = r.intervalDays != d) { Text("$d days") } }
                            }
                        }
                        HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    }
                    Text("Reminders are scheduled on this phone. Battery optimisation and Doze can delay them by some minutes; exempting Finora from battery optimisation makes them more punctual.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = { runCatching { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } }) { Text("Battery settings") }
                }
            }

            item { SectionHeader("Location & maps") }
            item {
                SectionCard {
                    ToggleRow("Online merchant lookup", "Search OpenStreetMap for an unfamiliar merchant, only when you press Search. Learned merchants are never looked up again.", s.placeLookupEnabled) { vm.set(Keys.LOOKUP, it) }
                    ToggleRow("Use my location for lookups", "Asked once per search, foreground only, never tracked or stored.", s.locationEnabled) { on -> if (on) locExplain = true else vm.set(Keys.LOCATION, false) }
                    if (s.locationEnabled) ToggleRow("Precise location", "Approximate (about 1 km) is usually enough.", s.preciseLocation) { vm.set(Keys.PRECISE, it) }
                    ToggleRow("Map preview", "Shows a small OpenStreetMap map of a chosen place. Downloads map tiles.", s.mapPreviewEnabled) { vm.set(Keys.MAP, it) }
                    var url by remember(s.placeSearchBaseUrl) { mutableStateOf(s.placeSearchBaseUrl) }
                    OutlinedTextField(url, { url = it.trim() }, label = { Text("Place search server (Nominatim-compatible, https)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    TextButton(enabled = url.startsWith("https://"), onClick = { vm.set(Keys.PLACE_URL, url) }) { Text("Save server") }
                    Text("The public OpenStreetMap server allows light use (1 search per second, no bulk). A self-hosted server can be used instead.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            item { SectionHeader("Data") }
            item {
                SectionCard(padding = 4.dp) {
                    NavRow("Learned merchants", "Edit, correct or reset what Finora learned") { open(Routes.MERCHANTS) }
                    NavRow("Categories", "Default and your own expense categories") { open(Routes.CATEGORIES) }
                    NavRow("People", "Friends, family and payees") { open(Routes.PEOPLE) }
                    NavRow("Accounts", "Bank accounts, cards and balances") { open(Routes.ACCOUNTS) }
                    NavRow("Recurring bills & subscriptions", "Used for upcoming obligations") { open(Routes.RECURRING) }
                    NavRow("Backup, restore & export", "Encrypted backup file and CSV") { open(Routes.BACKUP) }
                    NavRow("Privacy", "What stays on this phone, and what does not") { open(Routes.PRIVACY) }
                }
            }
            item {
                Text("Finora ${com.finora.app.BuildConfig.VERSION_NAME} · no account, no cloud, no AI, no ads.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
            }
        }
    }

    if (smsExplain) AlertDialog(
        onDismissRequest = { smsExplain = false },
        title = { Text("Read transaction SMS?") },
        text = { Text("Finora reads incoming messages from banks, card issuers and UPI apps to record transactions automatically. Everything is processed on this phone; no message ever leaves it. Other messages are ignored and not stored.") },
        confirmButton = { TextButton(onClick = { smsExplain = false; smsPermission.launch(arrayOf(Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_SMS)) }) { Text("Continue") } },
        dismissButton = { TextButton(onClick = { smsExplain = false }) { Text("Not now") } },
    )
    if (locExplain) AlertDialog(
        onDismissRequest = { locExplain = false },
        title = { Text("Use location for merchant search?") },
        text = { Text("When you search for an unfamiliar merchant, Finora can sort nearby businesses by distance. Your location is read once for that search and sent only with it. There is no background location and no history. Being near a shop is never treated as proof you paid there.") },
        confirmButton = { TextButton(onClick = {
            locExplain = false
            locPermission.launch(if (s.preciseLocation) arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION) else arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION))
        }) { Text("Continue") } },
        dismissButton = { TextButton(onClick = { locExplain = false }) { Text("Not now") } },
    )
}

@Composable
fun ToggleRow(title: String, sub: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked, onChange)
    }
}

@Composable
fun NavRow(title: String, sub: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.Filled.ChevronRight, null)
    }
}
