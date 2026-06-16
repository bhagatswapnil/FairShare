package com.splitexpense.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.splitexpense.ui.theme.Bg
import com.splitexpense.ui.theme.Surface3
import com.splitexpense.ui.theme.Text1
import com.splitexpense.ui.theme.Text2
import com.splitexpense.ui.theme.Text3
import kotlin.math.atan2
import kotlin.math.sqrt

data class ChartEntry(val label: String, val value: Float, val color: Color)

// ── Interactive Donut Chart ───────────────────────────────────────────────────
// Selection highlights the tapped slice (dims others, draws a gap border around
// selected) and shows its details in the centre hole.
// No translation — keeps the donut perfectly circular regardless of slice size.
@Composable
fun InteractivePieChart(
    entries: List<ChartEntry>,
    modifier: Modifier = Modifier,
    chartSize: Dp = 220.dp,
    centerLabel: String = "",
    currencySymbol: String = "₹"
) {
    if (entries.isEmpty()) return

    val total = entries.sumOf { it.value.toDouble() }.toFloat().coerceAtLeast(1f)
    var selectedIndex by remember(entries) { mutableStateOf(-1) }

    val sliceAngles: List<Pair<Float, Float>> = remember(entries, total) {
        var start = -90f
        entries.map { entry ->
            val sweep = (entry.value / total) * 360f
            (start to sweep).also { start += sweep }
        }
    }

    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {

        // ── Donut canvas + centre overlay ─────────────────────────────────────
        Box(modifier = Modifier.size(chartSize), contentAlignment = Alignment.Center) {

            Canvas(
                modifier = Modifier
                    .size(chartSize)
                    .pointerInput(entries) {
                        detectTapGestures { tapOffset ->
                            val cx    = size.width / 2f
                            val cy    = size.height / 2f
                            val dx    = tapOffset.x - cx
                            val dy    = tapOffset.y - cy
                            val dist  = sqrt(dx * dx + dy * dy)
                            val outerR = minOf(size.width, size.height) / 2f
                            val innerR = outerR * 0.45f

                            if (dist < innerR || dist > outerR) {
                                selectedIndex = -1
                                return@detectTapGestures
                            }

                            val tapAngle = ((Math.toDegrees(
                                atan2(dy.toDouble(), dx.toDouble())
                            ) + 360) % 360).toFloat()

                            var cumStart = 270f
                            var found    = -1
                            entries.forEachIndexed { i, entry ->
                                val sweep = (entry.value / total) * 360f
                                val end   = cumStart + sweep
                                val inSlice = if (end <= 360f) {
                                    tapAngle >= cumStart && tapAngle < end
                                } else {
                                    tapAngle >= cumStart || tapAngle < end - 360f
                                }
                                if (inSlice && found == -1) found = i
                                cumStart = end % 360f
                            }
                            selectedIndex = if (found == selectedIndex) -1 else found
                        }
                    }
            ) {
                val outerR = size.minDimension / 2f
                val innerR = outerR * 0.45f
                val cx     = size.width  / 2f
                val cy     = size.height / 2f
                val midR   = (innerR + outerR) / 2f
                val ringW  = outerR - innerR
                val hasSel = selectedIndex >= 0
                // A 2.5dp gap separates the selected slice from its neighbours.
                val gapPx  = 2.5.dp.toPx()

                // ── 1. All non-selected slices ───────────────────────────────
                sliceAngles.forEachIndexed { i, (startAngle, sweepAngle) ->
                    if (i != selectedIndex) {
                        drawArc(
                            color      = entries[i].color.copy(alpha = if (hasSel) 0.28f else 1f),
                            startAngle = startAngle,
                            sweepAngle = if (entries.size == 1) sweepAngle else sweepAngle - 0.8f,
                            useCenter  = false,
                            topLeft    = Offset(cx - midR, cy - midR),
                            size       = Size(midR * 2f, midR * 2f),
                            style      = Stroke(width = ringW)
                        )
                    }
                }

                // ── 2. Selected slice: bg gap ring → coloured ring (z-above) ──
                if (hasSel) {
                    val (sa, sw) = sliceAngles[selectedIndex]
                    // Background-coloured arc slightly wider than the ring — creates
                    // a clean separation border without any translation geometry.
                    drawArc(
                        color      = Bg,
                        startAngle = sa,
                        sweepAngle = sw,          // full sweep, no gap needed here
                        useCenter  = false,
                        topLeft    = Offset(cx - midR, cy - midR),
                        size       = Size(midR * 2f, midR * 2f),
                        style      = Stroke(width = ringW + gapPx * 2f)
                    )
                    // The actual selected arc on top
                    drawArc(
                        color      = entries[selectedIndex].color,
                        startAngle = sa,
                        sweepAngle = if (entries.size == 1) sw else sw - 0.8f,
                        useCenter  = false,
                        topLeft    = Offset(cx - midR, cy - midR),
                        size       = Size(midR * 2f, midR * 2f),
                        style      = Stroke(width = ringW)
                    )
                }

                // ── 3. Donut hole ─────────────────────────────────────────────
                drawCircle(color = Bg, radius = innerR, center = Offset(cx, cy))
                drawCircle(
                    color  = Surface3,
                    radius = innerR,
                    center = Offset(cx, cy),
                    style  = Stroke(width = 1.dp.toPx())
                )
            }

            // Centre text — fits inside the donut hole
            val sel = if (selectedIndex in entries.indices) entries[selectedIndex] else null
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier.widthIn(max = chartSize * 0.42f)
            ) {
                if (sel != null) {
                    Text(
                        "$currencySymbol${"%.0f".format(sel.value)}",
                        style     = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color     = sel.color,
                        textAlign = TextAlign.Center,
                        maxLines  = 1
                    )
                    Text(
                        sel.label,
                        style     = MaterialTheme.typography.labelSmall,
                        color     = Text2,
                        textAlign = TextAlign.Center,
                        maxLines  = 1
                    )
                    Text(
                        "${"%.0f".format(sel.value / total * 100)}%",
                        style     = MaterialTheme.typography.labelSmall,
                        color     = Text3,
                        textAlign = TextAlign.Center
                    )
                } else {
                    Text(
                        centerLabel.ifEmpty { "$currencySymbol${"%.0f".format(total)}" },
                        style     = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color     = Text1,
                        textAlign = TextAlign.Center,
                        maxLines  = 1
                    )
                    Text(
                        "TOTAL",
                        style     = MaterialTheme.typography.labelSmall,
                        color     = Text3,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }

        Spacer(Modifier.height(24.dp))

        // ── Tappable legend ───────────────────────────────────────────────────
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            entries.forEachIndexed { i, entry ->
                val isSelected = i == selectedIndex
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            if (isSelected) entry.color.copy(alpha = 0.10f)
                            else Color.Transparent,
                            RoundedCornerShape(8.dp)
                        )
                        .then(
                            if (isSelected)
                                Modifier.border(1.dp, entry.color.copy(alpha = 0.35f), RoundedCornerShape(8.dp))
                            else Modifier
                        )
                        .clickable { selectedIndex = if (isSelected) -1 else i }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(Modifier.size(10.dp).background(entry.color, CircleShape))
                    Text(
                        entry.label,
                        style      = MaterialTheme.typography.bodySmall,
                        color      = if (isSelected) Text1 else Text2,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                        modifier   = Modifier.weight(1f)
                    )
                    Text(
                        "$currencySymbol${"%.0f".format(entry.value)}",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = if (isSelected) entry.color else Text2
                    )
                    Text(
                        "${"%.0f".format(entry.value / total * 100)}%",
                        style    = MaterialTheme.typography.labelSmall,
                        color    = Text3,
                        modifier = Modifier.width(30.dp),
                        textAlign = TextAlign.End
                    )
                }
            }
        }
    }
}

// ── Backward-compat: CategoryChart now shows the interactive pie ───────────────
@Composable
fun CategoryChart(entries: List<ChartEntry>, modifier: Modifier = Modifier, currencySymbol: String = "₹") =
    InteractivePieChart(entries = entries, modifier = modifier, currencySymbol = currencySymbol)

// ── Horizontal bar chart ──────────────────────────────────────────────────────
@Composable
fun HorizontalBarChart(entries: List<ChartEntry>, modifier: Modifier = Modifier, currencySymbol: String = "₹") {
    if (entries.isEmpty()) return
    val maxVal = entries.maxOf { it.value }.coerceAtLeast(1f)

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        entries.forEach { entry ->
            val fraction = (entry.value / maxVal).coerceIn(0f, 1f)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text     = entry.label,
                    style    = MaterialTheme.typography.bodySmall,
                    color    = Text2,
                    modifier = Modifier.width(80.dp),
                    maxLines = 1
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(16.dp)
                        .background(Surface3, RoundedCornerShape(8.dp))
                ) {
                    if (fraction > 0f) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(fraction)
                                .fillMaxHeight()
                                .background(entry.color, RoundedCornerShape(8.dp))
                        )
                    }
                }
                Text(
                    text  = "$currencySymbol${"%.0f".format(entry.value)}",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = Text2,
                    modifier = Modifier.width(60.dp)
                )
            }
        }
    }
}

// Backward-compat aliases
@Composable
fun DonutChart(
    entries: List<ChartEntry>,
    modifier: Modifier = Modifier,
    centerLabel: String = "",
    currencySymbol: String = "₹"
) = InteractivePieChart(entries = entries, modifier = modifier, centerLabel = centerLabel, currencySymbol = currencySymbol)

@Composable
fun ChartLegend(entries: List<ChartEntry>, modifier: Modifier = Modifier) { /* no-op */ }
