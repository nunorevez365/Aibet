package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.BetSlipEntity
import com.example.quant.BetStatus
import com.example.quant.RoiSummary
import com.example.ui.HistoryFilter
import com.example.ui.QuantFormatters
import com.example.ui.theme.MonoFontFamily

@Composable
fun HistoryRoiTab(
    slips: List<BetSlipEntity>,
    roiSummary: RoiSummary,
    activeFilter: HistoryFilter,
    onFilterChange: (HistoryFilter) -> Unit,
    onMarkOutcome: (BetSlipEntity, BetStatus) -> Unit,
    onDeleteSlip: (Long) -> Unit
) {
    val filteredSlips = slips.filter { slip ->
        val status = BetStatus.fromDb(slip.status)
        when (activeFilter) {
            HistoryFilter.ALL -> true
            HistoryFilter.PENDING -> status == BetStatus.PENDING
            HistoryFilter.WON -> status == BetStatus.WON
            HistoryFilter.LOST -> status == BetStatus.LOST
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item(key = "roi_dashboard") {
            RoiPerformanceCard(roiSummary = roiSummary)
        }

        item(key = "filter_chips") {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(HistoryFilter.entries) { filter ->
                    FilterChip(
                        selected = activeFilter == filter,
                        onClick = { onFilterChange(filter) },
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("history_filter_${filter.name.lowercase()}"),
                        label = { Text(filter.label) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.primary
                        )
                    )
                }
            }
        }

        if (filteredSlips.isEmpty()) {
            item(key = "empty_history") {
                EmptyHistoryCard(isFiltered = slips.isNotEmpty())
            }
        } else {
            items(filteredSlips, key = { it.id }) { slip ->
                SavedSlipHistoryCard(
                    slip = slip,
                    onMarkOutcome = { status -> onMarkOutcome(slip, status) },
                    onDelete = { onDeleteSlip(slip.id) }
                )
            }
        }
    }
}

@Composable
private fun RoiPerformanceCard(roiSummary: RoiSummary) {
    val profitColor = if (roiSummary.totalNetProfit >= 0.0) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.error
    }
    val zeroLineColor = MaterialTheme.colorScheme.outline

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Lucro Líquido Acumulado",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = QuantFormatters.signedEuro(roiSummary.totalNetProfit),
                        style = MaterialTheme.typography.headlineMedium,
                        fontFamily = MonoFontFamily,
                        fontWeight = FontWeight.Bold,
                        color = profitColor,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = if (roiSummary.roiPercent >= 0.0) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.errorContainer
                    }
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalAlignment = Alignment.End
                    ) {
                        Text(
                            text = "ROI Real",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            softWrap = false
                        )
                        Text(
                            text = QuantFormatters.signedPercentDirect(roiSummary.roiPercent),
                            style = MaterialTheme.typography.titleMedium,
                            fontFamily = MonoFontFamily,
                            fontWeight = FontWeight.Bold,
                            color = profitColor,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }
            }

            // Custom Canvas Equity Curve
            if (roiSummary.equityCurvePoints.size >= 2) {
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(84.dp)
                ) {
                    val pts = roiSummary.equityCurvePoints
                    val minVal = (pts.minOrNull() ?: 0.0).coerceAtMost(0.0)
                    val maxVal = (pts.maxOrNull() ?: 1.0).coerceAtLeast(1.0)
                    val range = (maxVal - minVal).coerceAtLeast(1.0)

                    val zeroY = (size.height - ((0.0 - minVal) / range) * size.height).toFloat()
                    drawLine(
                        color = zeroLineColor,
                        start = Offset(0f, zeroY),
                        end = Offset(size.width, zeroY),
                        strokeWidth = 1.dp.toPx()
                    )

                    val stepX = size.width / (pts.size - 1).coerceAtLeast(1)
                    val linePath = Path()
                    val fillPath = Path()

                    pts.forEachIndexed { idx, v ->
                        val x = idx * stepX
                        val y = (size.height - ((v - minVal) / range) * (size.height - 12.dp.toPx()) - 6.dp.toPx()).toFloat()
                        if (idx == 0) {
                            linePath.moveTo(x, y)
                            fillPath.moveTo(x, zeroY)
                            fillPath.lineTo(x, y)
                        } else {
                            linePath.lineTo(x, y)
                            fillPath.lineTo(x, y)
                        }
                        if (idx == pts.lastIndex) {
                            fillPath.lineTo(x, zeroY)
                            fillPath.close()
                        }
                    }

                    drawPath(
                        path = fillPath,
                        brush = Brush.verticalGradient(
                            colors = listOf(profitColor.copy(alpha = 0.28f), Color.Transparent)
                        )
                    )
                    drawPath(
                        path = linePath,
                        color = profitColor,
                        style = Stroke(
                            width = 2.5.dp.toPx(),
                            cap = StrokeCap.Round,
                            join = StrokeJoin.Round
                        )
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                RoiStatChip(
                    label = "Win Rate",
                    value = QuantFormatters.percentDirect(roiSummary.winRatePercent),
                    accent = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.weight(1f)
                )
                RoiStatChip(
                    label = "G / P / Pend",
                    value = "${roiSummary.wonCount}/${roiSummary.lostCount}/${roiSummary.pendingCount}",
                    accent = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                RoiStatChip(
                    label = "Freebet → Real",
                    value = QuantFormatters.signedEuro(roiSummary.freebetNetProfit),
                    accent = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun RoiStatChip(
    label: String,
    value: String,
    accent: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = value,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = accent,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun SavedSlipHistoryCard(
    slip: BetSlipEntity,
    onMarkOutcome: (BetStatus) -> Unit,
    onDelete: () -> Unit
) {
    val status = BetStatus.fromDb(slip.status)
    val statusColor = when (status) {
        BetStatus.WON -> MaterialTheme.colorScheme.primary
        BetStatus.LOST -> MaterialTheme.colorScheme.error
        BetStatus.PENDING -> MaterialTheme.colorScheme.tertiary
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("history_slip_card_${slip.id}"),
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, statusColor.copy(alpha = 0.45f)),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = statusColor.copy(alpha = 0.16f)
                        ) {
                            Text(
                                text = status.label.uppercase(),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = statusColor,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                        Text(
                            text = "${slip.cycleLabel} • ${QuantFormatters.timestampWet(slip.createdAtMillis)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = slip.title,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.testTag("delete_slip_${slip.id}")
                ) {
                    Icon(
                        imageVector = Icons.Filled.DeleteOutline,
                        contentDescription = stringResource(R.string.cd_delete_slip),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Surface(
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f)
            ) {
                Text(
                    text = slip.selectionsSummary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(10.dp)
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Odd @ ${QuantFormatters.odd(slip.combinedOdd)} • Stake ${QuantFormatters.euro(slip.stake)} (${if (slip.isFreebet) "Freebet" else "Real"})",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = QuantFormatters.signedEuro(slip.netProfit),
                    style = MaterialTheme.typography.titleSmall,
                    fontFamily = MonoFontFamily,
                    fontWeight = FontWeight.Bold,
                    color = statusColor,
                    maxLines = 1,
                    softWrap = false
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            // Tactile 48dp Outcome Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutcomeActionPill(
                    label = stringResource(R.string.label_status_won),
                    selected = status == BetStatus.WON,
                    accent = MaterialTheme.colorScheme.primary,
                    icon = Icons.Filled.CheckCircle,
                    onClick = { onMarkOutcome(BetStatus.WON) },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("mark_won_${slip.id}")
                )
                OutcomeActionPill(
                    label = stringResource(R.string.label_status_lost),
                    selected = status == BetStatus.LOST,
                    accent = MaterialTheme.colorScheme.error,
                    icon = Icons.Filled.Cancel,
                    onClick = { onMarkOutcome(BetStatus.LOST) },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("mark_lost_${slip.id}")
                )
                OutcomeActionPill(
                    label = stringResource(R.string.label_status_pending),
                    selected = status == BetStatus.PENDING,
                    accent = MaterialTheme.colorScheme.tertiary,
                    icon = Icons.Filled.HourglassEmpty,
                    onClick = { onMarkOutcome(BetStatus.PENDING) },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("mark_pending_${slip.id}")
                )
            }
        }
    }
}

@Composable
private fun OutcomeActionPill(
    label: String,
    selected: Boolean,
    accent: Color,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        modifier = modifier.heightIn(min = 48.dp),
        shape = MaterialTheme.shapes.small,
        color = if (selected) accent.copy(alpha = 0.22f) else MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, if (selected) accent else MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (selected) accent else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(15.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = if (selected) accent else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun EmptyHistoryCard(isFiltered: Boolean) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.Analytics,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(40.dp)
            )
            Text(
                text = if (isFiltered) "Sem boletins neste filtro" else stringResource(R.string.empty_history_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center
            )
            Text(
                text = if (isFiltered) {
                    "Altera o filtro acima para visualizar outros estados registados."
                } else {
                    stringResource(R.string.empty_history_desc)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}
