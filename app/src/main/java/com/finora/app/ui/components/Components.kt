package com.finora.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.CardGiftcard
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Flight
import androidx.compose.material.icons.filled.Gavel
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LocalGasStation
import androidx.compose.material.icons.filled.LocalGroceryStore
import androidx.compose.material.icons.filled.LocalHospital
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Savings
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.ShoppingBag
import androidx.compose.material.icons.filled.Spa
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material.icons.filled.Subscriptions
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Work
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.finora.app.core.Money
import com.finora.app.domain.model.Direction
import com.finora.app.domain.model.TxnType
import com.finora.app.ui.theme.AmountStyle
import com.finora.app.ui.theme.Finance

val ScreenPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FinoraTopBar(title: String, onBack: (() -> Unit)? = null, actions: @Composable RowScope.() -> Unit = {}) {
    TopAppBar(
        title = { Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        navigationIcon = { if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
        actions = actions,
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
    )
}

@Composable
fun SectionCard(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, padding: Dp = 18.dp, content: @Composable ColumnScope.() -> Unit) {
    val colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    val shape = MaterialTheme.shapes.large
    val elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    val border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
    if (onClick != null) {
        Card(onClick = onClick, modifier = modifier.fillMaxWidth(), shape = shape, colors = colors, elevation = elevation, border = border) { Column(Modifier.padding(padding), content = content) }
    } else {
        Card(modifier = modifier.fillMaxWidth(), shape = shape, colors = colors, elevation = elevation, border = border) { Column(Modifier.padding(padding), content = content) }
    }
}

@Composable
fun SectionHeader(title: String, action: String? = null, onAction: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        if (action != null && onAction != null) TextButton(onClick = onAction) { Text(action) }
    }
}

/** Signed, coloured amount. Neutral types (transfers, lending, investments) are never green or red. */
@Composable
fun AmountText(paise: Long, direction: Direction?, type: TxnType? = null, modifier: Modifier = Modifier, style: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.titleMedium, strike: Boolean = false) {
    val flow = type?.countsAs
    val color = when {
        strike -> MaterialTheme.colorScheme.onSurfaceVariant
        flow == TxnType.Flow.INCOME || flow == TxnType.Flow.EXPENSE_REDUCTION -> Finance.colors.income
        flow == TxnType.Flow.EXPENSE -> MaterialTheme.colorScheme.onSurface
        else -> Finance.colors.neutral
    }
    val sign = when (direction) { Direction.CREDIT -> "+"; Direction.DEBIT -> "−"; null -> "" }
    Text(
        sign + Money.format(paise),
        style = style.merge(AmountStyle).copy(textDecoration = if (strike) androidx.compose.ui.text.style.TextDecoration.LineThrough else null),
        color = color, modifier = modifier, maxLines = 1,
    )
}

@Composable
fun StatTile(label: String, value: String, modifier: Modifier = Modifier, sub: String? = null, valueColor: Color = MaterialTheme.colorScheme.onSurface) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.size(2.dp))
        Text(value, style = MaterialTheme.typography.titleLarge.merge(AmountStyle), color = valueColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (sub != null) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
    }
}

@Composable
fun EmptyState(icon: ImageVector, title: String, body: String, action: String? = null, modifier: Modifier = Modifier, onAction: (() -> Unit)? = null) {
    Column(modifier.fillMaxWidth().padding(vertical = 40.dp, horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(64.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(28.dp))
        }
        Spacer(Modifier.size(16.dp))
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.size(6.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        if (action != null && onAction != null) {
            Spacer(Modifier.size(16.dp))
            androidx.compose.material3.FilledTonalButton(onClick = onAction) { Text(action) }
        }
    }
}

fun parseHex(hex: String?, fallback: Color): Color = runCatching { Color(android.graphics.Color.parseColor(hex)) }.getOrDefault(fallback)

fun iconFor(key: String?): ImageVector = when (key) {
    "restaurant" -> Icons.Filled.Restaurant
    "grocery" -> Icons.Filled.LocalGroceryStore
    "shopping" -> Icons.Filled.ShoppingBag
    "transport" -> Icons.Filled.DirectionsCar
    "fuel" -> Icons.Filled.LocalGasStation
    "bolt" -> Icons.Filled.Bolt
    "home" -> Icons.Filled.Home
    "health" -> Icons.Filled.LocalHospital
    "movie" -> Icons.Filled.Movie
    "flight" -> Icons.Filled.Flight
    "school" -> Icons.Filled.School
    "spa" -> Icons.Filled.Spa
    "subscriptions" -> Icons.Filled.Subscriptions
    "gift" -> Icons.Filled.CardGiftcard
    "receipt" -> Icons.AutoMirrored.Filled.ReceiptLong
    "gavel" -> Icons.Filled.Gavel
    "work" -> Icons.Filled.Work
    "storefront" -> Icons.Filled.Storefront
    "savings" -> Icons.Filled.Savings
    "payments" -> Icons.Filled.Payments
    "transfer" -> Icons.Filled.SwapHoriz
    "bank" -> Icons.Filled.AccountBalance
    else -> Icons.Filled.Category
}

@Composable
fun CategoryBadge(iconKey: String?, colorHex: String?, size: Dp = 40.dp) {
    val c = parseHex(colorHex, MaterialTheme.colorScheme.primary)
    Box(Modifier.size(size).clip(MaterialTheme.shapes.small).background(c.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
        Icon(iconFor(iconKey), null, tint = c, modifier = Modifier.size(size * 0.5f))
    }
}

@Composable
fun Pill(text: String, color: Color = MaterialTheme.colorScheme.secondaryContainer, textColor: Color = MaterialTheme.colorScheme.onSecondaryContainer) {
    Surface(color = color, shape = CircleShape) {
        Text(text, style = MaterialTheme.typography.labelSmall, color = textColor, modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp), maxLines = 1)
    }
}

@Composable
fun MoneyField(label: String, value: String, onValueChange: (String) -> Unit, modifier: Modifier = Modifier, error: String? = null, supporting: String? = null) {
    OutlinedTextField(
        value = value,
        onValueChange = { v -> if (v.isEmpty() || v.matches(Regex("[0-9,]*(\\.[0-9]{0,2})?"))) onValueChange(v) },
        label = { Text(label) },
        prefix = { Text("₹") },
        singleLine = true,
        isError = error != null,
        supportingText = (error ?: supporting)?.let { { Text(it) } },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = modifier.fillMaxWidth(),
    )
}

@Composable
fun DecimalField(label: String, value: String, onValueChange: (String) -> Unit, modifier: Modifier = Modifier, decimals: Int = 4, prefix: String? = null, supporting: String? = null) {
    OutlinedTextField(
        value = value,
        onValueChange = { v -> if (v.isEmpty() || v.matches(Regex("-?[0-9,]*(\\.[0-9]{0,$decimals})?"))) onValueChange(v) },
        label = { Text(label) },
        prefix = prefix?.let { { Text(it) } },
        singleLine = true,
        supportingText = supporting?.let { { Text(it) } },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = modifier.fillMaxWidth(),
    )
}

/** A compact select: label + current value; opens a menu. Avoids the experimental exposed-dropdown APIs. */
@Composable
fun <T> SelectField(label: String, options: List<T>, selected: T?, display: (T) -> String, onSelect: (T) -> Unit, modifier: Modifier = Modifier, placeholder: String = "Choose") {
    var open by remember { mutableStateOf(false) }
    Box(modifier.fillMaxWidth()) {
        Surface(
            shape = MaterialTheme.shapes.extraSmall,
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            color = Color.Transparent,
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(MaterialTheme.shapes.extraSmall).clickable { open = true },
        ) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(selected?.let(display) ?: placeholder, style = MaterialTheme.typography.bodyLarge, color = if (selected == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Icon(Icons.Filled.ExpandMore, null)
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { o -> DropdownMenuItem(text = { Text(display(o)) }, onClick = { onSelect(o); open = false }) }
        }
    }
}

@Composable
fun KeyValueRow(key: String, value: String, valueColor: Color = MaterialTheme.colorScheme.onSurface, bold: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.Top) {
        Text(key, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(12.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium.merge(AmountStyle), color = valueColor, fontWeight = if (bold) FontWeight.SemiBold else null, textAlign = androidx.compose.ui.text.style.TextAlign.End)
    }
}

@Composable
fun InfoNote(text: String, modifier: Modifier = Modifier) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.small, modifier = modifier.fillMaxWidth()) {
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(12.dp))
    }
}

@Composable
fun ErrorNote(text: String?) {
    AnimatedVisibility(visible = text != null) {
        Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth()) {
            Text(text ?: "", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.padding(12.dp))
        }
    }
}

val VerticalSpacing = Arrangement.spacedBy(12.dp)
