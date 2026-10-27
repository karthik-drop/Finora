package com.finora.app.ui.more

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.FactCheck
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.finora.app.ui.Routes
import com.finora.app.ui.components.FinoraTopBar
import com.finora.app.ui.components.SectionCard

private data class Entry(val route: String, val title: String, val sub: String, val icon: ImageVector)

private val entries = listOf(
    Entry(Routes.REVIEW, "Review", "Confirm merchants, categories and purposes", Icons.Filled.FactCheck),
    Entry(Routes.LOANS, "Loans & lending", "Money lent, borrowed and formal loans", Icons.Filled.AccountBalanceWallet),
    Entry(Routes.ANALYTICS, "Analytics", "Reports, trends, net worth and insights", Icons.Filled.Insights),
    Entry(Routes.DOCUMENTS, "Documents", "Encrypted receipts, agreements and statements", Icons.Filled.Folder),
    Entry(Routes.SETTINGS, "Settings", "SMS, reminders, location, backup and privacy", Icons.Filled.Settings),
)

@Composable
fun MoreScreen(open: (String) -> Unit) {
    Scaffold(contentWindowInsets = WindowInsets(0), topBar = { FinoraTopBar("More") }) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(20.dp, 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(entries) { e ->
                SectionCard(onClick = { open(e.route) }, padding = 16.dp) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(e.icon, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(e.title, style = MaterialTheme.typography.titleSmall)
                            Text(e.sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Icon(Icons.Filled.ChevronRight, null)
                    }
                }
            }
        }
    }
}
