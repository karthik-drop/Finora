package com.finora.app.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.finora.app.core.Money
import com.finora.app.ui.theme.AmountStyle
import com.finora.app.ui.theme.Finance

data class ChartSlice(val label: String, val value: Long, val color: Color)

/** A donut with a gap between segments and a legend that carries the actual amounts. */
@Composable
fun DonutChart(slices: List<ChartSlice>, centerLabel: String, centerValue: String, modifier: Modifier = Modifier, diameter: Dp = 150.dp) {
    val total = slices.sumOf { it.value }.coerceAtLeast(1)
    val progress = remember(slices) { Animatable(0f) }
    LaunchedEffect(slices) { progress.animateTo(1f, tween(650)) }
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    Row(modifier.semantics { contentDescription = slices.joinToString { "${it.label} ${Money.format(it.value)}" } }, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(diameter), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(diameter)) {
                val stroke = size.minDimension * 0.13f
                val inset = stroke / 2
                val arcSize = Size(size.width - stroke, size.height - stroke)
                drawArc(track, 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
                var start = -90f
                val gap = if (slices.size > 1) 2.5f else 0f
                slices.forEach { s ->
                    val sweep = 360f * s.value / total * progress.value
                    if (sweep > gap) drawArc(s.color, start + gap / 2, sweep - gap, false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Butt))
                    start += 360f * s.value / total * progress.value
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(centerLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(centerValue, style = MaterialTheme.typography.titleMedium.merge(AmountStyle), maxLines = 1)
            }
        }
        Spacer(Modifier.width(18.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            slices.take(6).forEach { s ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(s.color))
                    Spacer(Modifier.width(8.dp))
                    Text(s.label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${s.value * 100 / total}%", style = MaterialTheme.typography.labelMedium.merge(AmountStyle), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

data class BarGroup(val label: String, val a: Long, val b: Long)

/** Paired bars (income vs expense per month) on a shared zero baseline, with value labels below. */
@Composable
fun PairedBarChart(groups: List<BarGroup>, aLabel: String, bLabel: String, modifier: Modifier = Modifier, height: Dp = 170.dp) {
    val aColor = Finance.colors.income
    val bColor = Finance.colors.expense
    val grid = MaterialTheme.colorScheme.outlineVariant
    val max = groups.maxOfOrNull { maxOf(it.a, it.b) }?.coerceAtLeast(1) ?: 1
    val progress = remember(groups) { Animatable(0f) }
    LaunchedEffect(groups) { progress.animateTo(1f, tween(600)) }
    Column(modifier.semantics { contentDescription = groups.joinToString { "${it.label}: $aLabel ${Money.format(it.a)}, $bLabel ${Money.format(it.b)}" } }) {
        Canvas(Modifier.fillMaxWidth().height(height)) {
            val n = groups.size.coerceAtLeast(1)
            val slot = size.width / n
            val barW = (slot * 0.28f).coerceAtMost(22.dp.toPx())
            listOf(0.25f, 0.5f, 0.75f, 1f).forEach { f ->
                val y = size.height * (1 - f)
                drawLine(grid, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
            }
            groups.forEachIndexed { i, g ->
                val cx = slot * i + slot / 2
                val ha = size.height * g.a / max * progress.value
                val hb = size.height * g.b / max * progress.value
                drawRoundRect(aColor, Offset(cx - barW - 2, size.height - ha), Size(barW, ha), CornerRadius(6f, 6f))
                drawRoundRect(bColor, Offset(cx + 2, size.height - hb), Size(barW, hb), CornerRadius(6f, 6f))
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
            groups.forEach { g -> Text(g.label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center, maxLines = 1) }
        }
        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Legend(aColor, aLabel); Spacer(Modifier.width(16.dp)); Legend(bColor, bLabel)
        }
    }
}

@Composable
fun Legend(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** A line with a soft fill. Points marked [partial] are drawn hollow — a value with unpriced holdings. */
@Composable
fun LineChart(points: List<Pair<String, Long>>, modifier: Modifier = Modifier, height: Dp = 160.dp, partial: Set<Int> = emptySet()) {
    val color = MaterialTheme.colorScheme.primary
    val bg = MaterialTheme.colorScheme.surfaceContainerLow
    if (points.isEmpty()) return
    val min = points.minOf { it.second }
    val max = points.maxOf { it.second }
    val span = (max - min).coerceAtLeast(1)
    Column(modifier.semantics { contentDescription = points.joinToString { "${it.first} ${Money.format(it.second)}" } }) {
        Canvas(Modifier.fillMaxWidth().height(height)) {
            val pad = 8.dp.toPx()
            fun x(i: Int) = if (points.size == 1) size.width / 2 else pad + (size.width - 2 * pad) * i / (points.size - 1)
            fun y(v: Long) = pad + (size.height - 2 * pad) * (1 - (v - min).toFloat() / span)
            val line = Path()
            val fill = Path()
            points.forEachIndexed { i, p ->
                if (i == 0) { line.moveTo(x(i), y(p.second)); fill.moveTo(x(i), size.height); fill.lineTo(x(i), y(p.second)) }
                else { line.lineTo(x(i), y(p.second)); fill.lineTo(x(i), y(p.second)) }
            }
            fill.lineTo(x(points.lastIndex), size.height); fill.close()
            drawPath(fill, androidx.compose.ui.graphics.Brush.verticalGradient(listOf(color.copy(alpha = 0.22f), color.copy(alpha = 0f))))
            drawPath(line, color, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round))
            points.forEachIndexed { i, p ->
                drawCircle(color, 4.dp.toPx(), Offset(x(i), y(p.second)))
                if (i in partial) drawCircle(bg, 2.dp.toPx(), Offset(x(i), y(p.second)))
            }
        }
        Row(Modifier.fillMaxWidth()) {
            Text(points.first().first, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            if (points.size > 1) Text(points.last().first, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** A horizontal bar used for category/merchant ranking rows. */
@Composable
fun RankBar(label: String, value: Long, max: Long, color: Color, trailing: String = Money.format(value)) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(trailing, style = MaterialTheme.typography.bodyMedium.merge(AmountStyle))
        }
        Spacer(Modifier.height(6.dp))
        Box(Modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
            Box(Modifier.fillMaxWidth((value.toFloat() / max.coerceAtLeast(1)).coerceIn(0f, 1f)).height(6.dp).clip(CircleShape).background(color))
        }
    }
}
