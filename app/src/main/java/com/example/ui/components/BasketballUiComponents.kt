package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.SportsBasketball
import androidx.compose.material.icons.filled.SportsSoccer
import androidx.compose.material.icons.filled.SportsTennis
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.quant.BasketballAnalysisBundle
import com.example.quant.BasketballMarketEvaluation
import com.example.quant.BasketballMatchAnalysis
import com.example.quant.BasketballQuantEngine
import com.example.quant.BasketballSlip
import com.example.quant.SportMode
import com.example.quant.StakeMode
import com.example.ui.BasketballUiState
import com.example.ui.BetUiState
import com.example.ui.QuantFormatters
import com.example.ui.theme.MonoFontFamily
import java.util.Locale

@Composable
fun SportModeSelectorRow(
    sportMode: SportMode,
    onSelectSport: (SportMode) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        SportMode.entries.forEach { mode ->
            val selected = sportMode == mode
            val accent = when (mode) {
                SportMode.FOOTBALL -> MaterialTheme.colorScheme.primary
                SportMode.BASKETBALL -> MaterialTheme.colorScheme.tertiary
                SportMode.TENNIS -> MaterialTheme.colorScheme.secondary
            }
            val container = when (mode) {
                SportMode.FOOTBALL -> MaterialTheme.colorScheme.primaryContainer
                SportMode.BASKETBALL -> MaterialTheme.colorScheme.tertiaryContainer
                SportMode.TENNIS -> MaterialTheme.colorScheme.secondaryContainer
            }

            Surface(
                onClick = { onSelectSport(mode) },
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    .testTag("sport_mode_${mode.name.lowercase()}"),
                shape = MaterialTheme.shapes.medium,
                color = if (selected) container else MaterialTheme.colorScheme.surfaceVariant,
                border = BorderStroke(
                    width = if (selected) 1.5.dp else 1.dp,
                    color = if (selected) accent else MaterialTheme.colorScheme.outline
                )
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = when (mode) {
                            SportMode.FOOTBALL -> Icons.Filled.SportsSoccer
                            SportMode.BASKETBALL -> Icons.Filled.SportsBasketball
                            SportMode.TENNIS -> Icons.Filled.SportsTennis
                        },
                        contentDescription = null,
                        tint = if (selected) accent else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(5.dp))
                    Text(
                        text = mode.shortLabel,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                        color = if (selected) accent else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
fun BasketballSlipCard(
    slip: BasketballSlip,
    accentColor: Color,
    accentContainer: Color,
    isSaved: Boolean,
    bundle: BasketballAnalysisBundle,
    showLeverageToggle: Boolean = false,
    isFullLeverage: Boolean = false,
    onToggleLeverage: (Boolean) -> Unit = {},
    onSave: () -> Unit,
    onOpenMatchMatrix: (Int) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("basket_slip_card_${slip.type.code.lowercase()}"),
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, accentColor.copy(alpha = 0.55f)),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = accentContainer
                ) {
                    Text(
                        text = slip.type.badge,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = accentColor,
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHighest
                ) {
                    Text(
                        text = "Odd @ ${QuantFormatters.odd(slip.combinedOdd)}",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = accentColor,
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = slip.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = slip.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (showLeverageToggle) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = !isFullLeverage,
                        onClick = { onToggleLeverage(false) },
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                            .testTag("basket_slip3_base"),
                        label = {
                            Text(
                                text = stringResource(R.string.label_variant_base),
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = accentContainer,
                            selectedLabelColor = accentColor
                        )
                    )
                    FilterChip(
                        selected = isFullLeverage,
                        onClick = { onToggleLeverage(true) },
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                            .testTag("basket_slip3_full"),
                        label = {
                            Text(
                                text = stringResource(R.string.label_variant_full),
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = accentContainer,
                            selectedLabelColor = accentColor
                        )
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                slip.selections.forEach { leg ->
                    val matchIdx = bundle.matches.indexOfFirst { it.rawMatch.id == leg.matchId }
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(MaterialTheme.shapes.small)
                            .clickable(enabled = matchIdx >= 0) {
                                if (matchIdx >= 0) onOpenMatchMatrix(matchIdx)
                            },
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.75f)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                Text(
                                    text = leg.matchLabel,
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "${leg.competition} • ${leg.kickoffWet}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = leg.lineLabel,
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = accentColor,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = "P=${QuantFormatters.percent(leg.trueProbability)}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        softWrap = false
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.width(8.dp))

                            Column(
                                horizontalAlignment = Alignment.End,
                                verticalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                Text(
                                    text = QuantFormatters.odd(leg.odd),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontFamily = MonoFontFamily,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    softWrap = false
                                )
                                Text(
                                    text = "Edge ${QuantFormatters.signedPercentFraction(leg.edge)}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (leg.edge >= 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                BasketKpiTile("Stake", QuantFormatters.euro(slip.stake), MaterialTheme.colorScheme.onSurface, Modifier.weight(1f))
                BasketKpiTile(
                    label = if (slip.stakeMode == StakeMode.FREEBET) "Lucro Líquido" else "Retorno Bruto",
                    value = QuantFormatters.euro(slip.potentialGrossReturn),
                    accent = accentColor,
                    modifier = Modifier.weight(1f)
                )
                BasketKpiTile(
                    label = "EV Estimado",
                    value = QuantFormatters.signedEuro(slip.expectedValue),
                    accent = if (slip.expectedValue >= 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(1f)
                )
            }

            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "Prob. Real: ${QuantFormatters.percent(slip.combinedTrueProb)}  •  Edge: ${QuantFormatters.signedPercentDirect(slip.edgePercent)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                OutlinedButton(
                    onClick = onSave,
                    enabled = !isSaved,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .testTag("save_basket_slip_${slip.type.code.lowercase()}"),
                    border = BorderStroke(1.dp, if (isSaved) MaterialTheme.colorScheme.outline else accentColor)
                ) {
                    Icon(
                        imageVector = if (isSaved) Icons.Filled.CheckCircle else Icons.Filled.BookmarkBorder,
                        contentDescription = null,
                        tint = if (isSaved) MaterialTheme.colorScheme.primary else accentColor,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (isSaved) stringResource(R.string.label_saved) else stringResource(R.string.label_save_slip),
                        color = if (isSaved) MaterialTheme.colorScheme.primary else accentColor,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun BasketKpiTile(
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
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                fontFamily = MonoFontFamily,
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
fun BasketballMatrixContent(
    uiState: BetUiState,
    onSelectSport: (SportMode) -> Unit,
    onSelectMatch: (Int) -> Unit,
    onUpdateLinesAndOdds: (String, Double, Double, Double, Double, Double, Double, Double, Double, Double, Double, Double) -> Unit,
    onNavigateToAnalyze: () -> Unit
) {
    val successState = uiState.basketballState as? BasketballUiState.Success
    if (successState == null || successState.bundle.matches.isEmpty()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item(key = "sport_switch_empty") {
                SportModeSelectorRow(sportMode = uiState.sportMode, onSelectSport = onSelectSport)
            }
            item(key = "basket_empty_card") {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.large,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.SportsBasketball,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.size(44.dp)
                        )
                        Text(
                            text = "Matriz de Quartos & Pontos",
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            textAlign = TextAlign.Center
                        )
                        Text(
                            text = "Executa a análise de Basquetebol no separador Boletins para ver projeções Q1–Q4, margens e todos os mercados Betclic.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                        Button(
                            onClick = onNavigateToAnalyze,
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag("empty_basket_matrix_go_boletins")
                        ) {
                            Text(text = stringResource(R.string.tab_slips))
                        }
                    }
                }
            }
        }
        return
    }

    val matches = successState.bundle.matches
    val safeIndex = uiState.selectedBasketballMatchIndex.coerceIn(0, matches.lastIndex)
    val selectedMatch = matches[safeIndex]
    var showOddsDialog by remember(selectedMatch.rawMatch.id) { mutableStateOf(false) }
    val chipRowState = rememberLazyListState()

    LaunchedEffect(safeIndex, matches.size) {
        if (matches.isNotEmpty()) {
            chipRowState.animateScrollToItem(safeIndex)
        }
    }

    if (showOddsDialog) {
        EditBasketballOddsDialog(
            match = selectedMatch,
            onDismiss = { showOddsDialog = false },
            onConfirm = { totalL, spreadL, q1L, oHml, oAml, oHsp, oAsp, oOver, oUnder, oQ1O, oQ1U ->
                onUpdateLinesAndOdds(
                    selectedMatch.rawMatch.id,
                    totalL, spreadL, q1L, oHml, oAml, oHsp, oAsp, oOver, oUnder, oQ1O, oQ1U
                )
                showOddsDialog = false
            }
        )
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item(key = "basket_matrix_sport_toggle") {
            SportModeSelectorRow(sportMode = uiState.sportMode, onSelectSport = onSelectSport)
        }

        item(key = "basket_match_selector") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Projeção Basquetebol",
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onBackground,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "Jogo ${safeIndex + 1} de ${matches.size} • toca num jogo para alternar",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = { if (safeIndex > 0) onSelectMatch(safeIndex - 1) },
                            enabled = safeIndex > 0,
                            modifier = Modifier.testTag("basket_prev_match")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                                contentDescription = "Jogo anterior"
                            )
                        }
                        IconButton(
                            onClick = { if (safeIndex < matches.lastIndex) onSelectMatch(safeIndex + 1) },
                            enabled = safeIndex < matches.lastIndex,
                            modifier = Modifier.testTag("basket_next_match")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                contentDescription = "Próximo jogo"
                            )
                        }
                    }
                }
                LazyRow(
                    state = chipRowState,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    itemsIndexed(matches) { idx, match ->
                        val isSelected = idx == safeIndex
                        val homeShort = shortTeamLabel(match.rawMatch.equipaCasa)
                        val awayShort = shortTeamLabel(match.rawMatch.equipaFora)
                        FilterChip(
                            selected = isSelected,
                            onClick = { onSelectMatch(idx) },
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag("basket_matrix_chip_$idx"),
                            leadingIcon = if (isSelected) {
                                {
                                    Icon(
                                        imageVector = Icons.Filled.CheckCircle,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            } else {
                                null
                            },
                            label = {
                                Text(
                                    text = "${idx + 1}. $homeShort vs $awayShort",
                                    maxLines = 1,
                                    softWrap = false,
                                    overflow = TextOverflow.Ellipsis
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.tertiaryContainer,
                                selectedLabelColor = MaterialTheme.colorScheme.tertiary,
                                selectedLeadingIconColor = MaterialTheme.colorScheme.tertiary
                            )
                        )
                    }
                }
            }
        }

        item(key = "basket_intel_${selectedMatch.rawMatch.id}") {
            BasketballIntelCard(match = selectedMatch)
        }

        item(key = "basket_quarters_${selectedMatch.rawMatch.id}") {
            QuarterProjectionCard(match = selectedMatch)
        }

        item(key = "basket_margin_${selectedMatch.rawMatch.id}") {
            MarginDistributionCard(match = selectedMatch)
        }

        item(key = "basket_markets_${selectedMatch.rawMatch.id}") {
            BasketballMarketTableCard(
                markets = selectedMatch.markets,
                onEditOddsClick = { showOddsDialog = true }
            )
        }
    }
}

@Composable
private fun BasketballIntelCard(match: BasketballMatchAnalysis) {
    val raw = match.rawMatch
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.tertiary.copy(alpha = 0.5f)),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = raw.competicao,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.tertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = raw.dataHoraWet,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    softWrap = false
                )
            }

            Text(
                text = "${raw.equipaCasa} vs ${raw.equipaFora}",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                BasketKpiTile(
                    label = "Total Pts",
                    value = String.format(Locale.US, "%.1f", match.projectedCombinedTotal),
                    accent = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.weight(1f)
                )
                BasketKpiTile(
                    label = "Margem Casa",
                    value = String.format(Locale.US, "%+.1f", match.projectedHomeMargin),
                    accent = if (match.projectedHomeMargin >= 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.weight(1f)
                )
                BasketKpiTile(
                    label = "P(Casa)",
                    value = QuantFormatters.percent(match.probHomeWin),
                    accent = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f)
                )
            }

            Surface(
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Text(
                    text = raw.paceNotes,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp)
                )
            }
        }
    }
}

@Composable
private fun QuarterProjectionCard(match: BasketballMatchAnalysis) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.Timer,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = "Pontos Projetados por Quarto (Q1–Q4)",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            // Header Row
            Row(modifier = Modifier.fillMaxWidth()) {
                Text("Período", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1.0f))
                Text("Casa", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.End, modifier = Modifier.weight(0.9f))
                Text("Fora", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.End, modifier = Modifier.weight(0.9f))
                Text("Total", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.End, modifier = Modifier.weight(1.0f))
                Text("Margem", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.End, modifier = Modifier.weight(0.95f))
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            match.quarters.forEach { q ->
                QuarterRowItem(
                    label = q.quarterLabel,
                    homePts = q.homePoints,
                    awayPts = q.awayPoints,
                    totalPts = q.totalPoints,
                    margin = q.marginHome,
                    highlighted = q.quarterLabel == "Q1"
                )
            }

            QuarterRowItem(
                label = "1ª Parte",
                homePts = match.firstHalfHome,
                awayPts = match.firstHalfAway,
                totalPts = match.firstHalfHome + match.firstHalfAway,
                margin = match.firstHalfHome - match.firstHalfAway,
                highlighted = false
            )
            QuarterRowItem(
                label = "Final",
                homePts = match.projectedHomeTotal,
                awayPts = match.projectedAwayTotal,
                totalPts = match.projectedCombinedTotal,
                margin = match.projectedHomeMargin,
                highlighted = true
            )
        }
    }
}

@Composable
private fun QuarterRowItem(
    label: String,
    homePts: Double,
    awayPts: Double,
    totalPts: Double,
    margin: Double,
    highlighted: Boolean
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (highlighted) {
            MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.45f)
        } else {
            MaterialTheme.colorScheme.surface.copy(alpha = 0.55f)
        }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = if (highlighted) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                modifier = Modifier.weight(1.0f)
            )
            Text(
                text = String.format(Locale.US, "%.1f", homePts),
                style = MaterialTheme.typography.labelMedium,
                fontFamily = MonoFontFamily,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.End,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.weight(0.9f)
            )
            Text(
                text = String.format(Locale.US, "%.1f", awayPts),
                style = MaterialTheme.typography.labelMedium,
                fontFamily = MonoFontFamily,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.End,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.weight(0.9f)
            )
            Text(
                text = String.format(Locale.US, "%.1f", totalPts),
                style = MaterialTheme.typography.labelMedium,
                fontFamily = MonoFontFamily,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.secondary,
                textAlign = TextAlign.End,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.weight(1.0f)
            )
            Text(
                text = String.format(Locale.US, "%+.1f", margin),
                style = MaterialTheme.typography.labelMedium,
                fontFamily = MonoFontFamily,
                fontWeight = FontWeight.Bold,
                color = if (margin >= 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                textAlign = TextAlign.End,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.weight(0.95f)
            )
        }
    }
}

@Composable
private fun MarginDistributionCard(match: BasketballMatchAnalysis) {
    val c1 = MaterialTheme.colorScheme.primary
    val c2 = MaterialTheme.colorScheme.secondary
    val c3 = MaterialTheme.colorScheme.tertiary
    val c4 = MaterialTheme.colorScheme.error

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "Distribuição de Margem de Vitória",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp)
            ) {
                val buckets = match.marginBuckets
                val maxVal = buckets.maxOfOrNull { it.second }?.coerceAtLeast(0.05f) ?: 1f
                val gap = 12.dp.toPx()
                val barWidth = (size.width - gap * (buckets.size - 1)) / buckets.size.coerceAtLeast(1)
                val colors = listOf(c1, c2, c3, c4)
                buckets.forEachIndexed { idx, (_, prob) ->
                    val barH = (prob / maxVal).coerceIn(0.06f, 1f) * size.height
                    drawRoundRect(
                        color = colors.getOrElse(idx) { c1 },
                        topLeft = Offset(idx * (barWidth + gap), size.height - barH),
                        size = Size(barWidth, barH),
                        cornerRadius = CornerRadius(8.dp.toPx(), 8.dp.toPx())
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                match.marginBuckets.forEach { (label, prob) ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = String.format(Locale.US, "%.1f%%", prob * 100f),
                            style = MaterialTheme.typography.labelMedium,
                            fontFamily = MonoFontFamily,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BasketballMarketTableCard(
    markets: List<BasketballMarketEvaluation>,
    onEditOddsClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Speed,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.tertiary
                    )
                    Text(
                        text = "Mercados Betclic • Edge & Kelly",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                OutlinedButton(
                    onClick = onEditOddsClick,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .testTag("edit_basket_odds_button")
                ) {
                    Icon(
                        imageVector = Icons.Filled.Edit,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Ajustar Linhas & Odds Betclic.pt",
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Row(modifier = Modifier.fillMaxWidth()) {
                Text("Mercado / Linha", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1.55f))
                Text("Odd", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.End, maxLines = 1, softWrap = false, modifier = Modifier.weight(0.65f))
                Text("Real", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.End, maxLines = 1, softWrap = false, modifier = Modifier.weight(0.75f))
                Text("Edge", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.End, maxLines = 1, softWrap = false, modifier = Modifier.weight(0.85f))
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            markets.forEach { eval ->
                val isPositive = eval.edge > 0.0
                val rowBg = if (isPositive) {
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                } else {
                    MaterialTheme.colorScheme.surface.copy(alpha = 0.55f)
                }
                Surface(shape = RoundedCornerShape(8.dp), color = rowBg) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = eval.lineLabel,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = if (isPositive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1.55f)
                        )
                        Text(
                            text = QuantFormatters.odd(eval.odd),
                            style = MaterialTheme.typography.labelMedium,
                            fontFamily = MonoFontFamily,
                            color = MaterialTheme.colorScheme.onSurface,
                            textAlign = TextAlign.End,
                            maxLines = 1,
                            softWrap = false,
                            modifier = Modifier.weight(0.65f)
                        )
                        Text(
                            text = QuantFormatters.percent(eval.trueProbability),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.End,
                            maxLines = 1,
                            softWrap = false,
                            modifier = Modifier.weight(0.75f)
                        )
                        Text(
                            text = QuantFormatters.signedPercentFraction(eval.edge),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = if (isPositive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.End,
                            maxLines = 1,
                            softWrap = false,
                            modifier = Modifier.weight(0.85f)
                        )
                    }
                }
            }
        }
    }
}

private fun shortTeamLabel(fullName: String): String {
    val trimmed = fullName.trim()
    if (trimmed.length <= 12) return trimmed
    val parts = trimmed.split(" ").filter { it.isNotBlank() }
    val nickname = parts.lastOrNull() ?: trimmed
    return if (nickname.length >= 4) nickname else trimmed.take(12)
}

