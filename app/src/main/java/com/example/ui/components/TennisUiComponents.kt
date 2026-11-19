package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material.icons.filled.SportsTennis
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.quant.SportMode
import com.example.quant.StakeMode
import com.example.quant.TennisAnalysisBundle
import com.example.quant.TennisMarketEvaluation
import com.example.quant.TennisMatchAnalysis
import com.example.quant.TennisQuantEngine
import com.example.quant.TennisSlip
import com.example.ui.BetUiState
import com.example.ui.QuantFormatters
import com.example.ui.TennisUiState
import com.example.ui.theme.MonoFontFamily
import java.util.Locale

@Composable
fun TennisSlipCard(
    slip: TennisSlip,
    accentColor: Color,
    accentContainer: Color,
    isSaved: Boolean,
    bundle: TennisAnalysisBundle,
    showLeverageToggle: Boolean = false,
    isFullLeverage: Boolean = false,
    onToggleLeverage: (Boolean) -> Unit = {},
    onSave: () -> Unit,
    onOpenMatchMatrix: (Int) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("tennis_slip_card_${slip.type.code.lowercase()}"),
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
                            .testTag("tennis_slip3_base"),
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
                            .testTag("tennis_slip3_full"),
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
                TennisKpiTile("Stake", QuantFormatters.euro(slip.stake), MaterialTheme.colorScheme.onSurface, Modifier.weight(1f))
                TennisKpiTile(
                    label = if (slip.stakeMode == StakeMode.FREEBET) "Lucro Líquido" else "Retorno Bruto",
                    value = QuantFormatters.euro(slip.potentialGrossReturn),
                    accent = accentColor,
                    modifier = Modifier.weight(1f)
                )
                TennisKpiTile(
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
                        .testTag("save_tennis_slip_${slip.type.code.lowercase()}"),
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
private fun TennisKpiTile(
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
fun TennisMatrixContent(
    uiState: BetUiState,
    onSelectSport: (SportMode) -> Unit,
    onSelectMatch: (Int) -> Unit,
    onUpdateLinesAndOdds: (
        String, Double, Double, Double, Double, Double, Double, Double, Double, Double, Double, Double, Double, Double
    ) -> Unit,
    onNavigateToAnalyze: () -> Unit
) {
    val successState = uiState.tennisState as? TennisUiState.Success
    if (successState == null || successState.bundle.matches.isEmpty()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item(key = "tennis_sport_switch_empty") {
                SportModeSelectorRow(sportMode = uiState.sportMode, onSelectSport = onSelectSport)
            }
            item(key = "tennis_empty_card") {
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
                            imageVector = Icons.Filled.SportsTennis,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.size(44.dp)
                        )
                        Text(
                            text = "Matriz Markov de Ténis",
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            textAlign = TextAlign.Center
                        )
                        Text(
                            text = "Executa a análise de Ténis no separador Boletins para ver distribuição exata de sets (2-0 / 2-1), hold de serviço, total de jogos e mercados Betclic.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                        Button(
                            onClick = onNavigateToAnalyze,
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag("empty_tennis_matrix_go_boletins")
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
    val safeIndex = uiState.selectedTennisMatchIndex.coerceIn(0, matches.lastIndex)
    val selectedMatch = matches[safeIndex]
    var showOddsDialog by remember(selectedMatch.rawMatch.id) { mutableStateOf(false) }
    val chipRowState = rememberLazyListState()

    LaunchedEffect(safeIndex, matches.size) {
        if (matches.isNotEmpty()) {
            chipRowState.animateScrollToItem(safeIndex)
        }
    }

    if (showOddsDialog) {
        EditTennisOddsDialog(
            match = selectedMatch,
            onDismiss = { showOddsDialog = false },
            onConfirm = { totG, hc1, oMl1, oMl2, oS1, oS2, oOver, oUnder, oHc1, oHc2, oStr, oOvS, oTb ->
                onUpdateLinesAndOdds(
                    selectedMatch.rawMatch.id,
                    totG, hc1, oMl1, oMl2, oS1, oS2, oOver, oUnder, oHc1, oHc2, oStr, oOvS, oTb
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
        item(key = "tennis_matrix_sport_toggle") {
            SportModeSelectorRow(sportMode = uiState.sportMode, onSelectSport = onSelectSport)
        }

        item(key = "tennis_match_selector") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Projeção Ténis ATP/WTA",
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
                            modifier = Modifier.testTag("tennis_prev_match")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                                contentDescription = "Jogo anterior"
                            )
                        }
                        IconButton(
                            onClick = { if (safeIndex < matches.lastIndex) onSelectMatch(safeIndex + 1) },
                            enabled = safeIndex < matches.lastIndex,
                            modifier = Modifier.testTag("tennis_next_match")
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
                        val p1 = TennisQuantEngine.shortSurname(match.rawMatch.jogador1)
                        val p2 = TennisQuantEngine.shortSurname(match.rawMatch.jogador2)
                        FilterChip(
                            selected = isSelected,
                            onClick = { onSelectMatch(idx) },
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag("tennis_matrix_chip_$idx"),
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
                                    text = "${idx + 1}. $p1 vs $p2",
                                    maxLines = 1,
                                    softWrap = false,
                                    overflow = TextOverflow.Ellipsis
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                                selectedLabelColor = MaterialTheme.colorScheme.secondary,
                                selectedLeadingIconColor = MaterialTheme.colorScheme.secondary
                            )
                        )
                    }
                }
            }
        }

        item(key = "tennis_intel_${selectedMatch.rawMatch.id}") {
            TennisIntelCard(match = selectedMatch)
        }

        item(key = "tennis_sets_${selectedMatch.rawMatch.id}") {
            TennisSetScoreDistributionCard(match = selectedMatch)
        }

        item(key = "tennis_markets_${selectedMatch.rawMatch.id}") {
            TennisMarketTableCard(
                markets = selectedMatch.markets,
                onEditOddsClick = { showOddsDialog = true }
            )
        }
    }
}

@Composable
private fun TennisIntelCard(match: TennisMatchAnalysis) {
    val raw = match.rawMatch
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.secondary.copy(alpha = 0.55f)),
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
                    text = "${raw.circuito} • ${raw.torneio} • ${raw.superficie.label}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.secondary,
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

            val r1 = raw.ranking1?.let { " (#$it)" } ?: ""
            val r2 = raw.ranking2?.let { " (#$it)" } ?: ""
            Text(
                text = "${raw.jogador1}$r1 vs ${raw.jogador2}$r2",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TennisKpiTile(
                    label = "Total Jogos",
                    value = String.format(Locale.US, "%.1f", match.expectedTotalGames),
                    accent = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.weight(1f)
                )
                TennisKpiTile(
                    label = "Hold J1 / J2",
                    value = "${(raw.serveHoldProb1 * 100).toInt()}%/${(raw.serveHoldProb2 * 100).toInt()}%",
                    accent = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.weight(1f)
                )
                TennisKpiTile(
                    label = "P(J1)",
                    value = QuantFormatters.percent(match.probP1Win),
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
                    text = raw.surfaceNotes,
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
private fun TennisSetScoreDistributionCard(match: TennisMatchAnalysis) {
    val raw = match.rawMatch
    val p1Short = TennisQuantEngine.shortSurname(raw.jogador1)
    val p2Short = TennisQuantEngine.shortSurname(raw.jogador2)

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
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Filled.SportsTennis,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = "Probabilidade de Sets (Bo${raw.bestOfSets})",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    text = "${String.format(Locale.US, "%.1f", match.expectedGamesPerSet)} jg/set",
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = MonoFontFamily,
                    color = MaterialTheme.colorScheme.secondary,
                    maxLines = 1,
                    softWrap = false
                )
            }

            // Exact set-score probability bars
            match.setScoreDistribution.forEach { item ->
                val winnerName = if (item.isPlayer1Win) p1Short else p2Short
                val barColor = if (item.isPlayer1Win) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "$winnerName ${item.scoreLabel}",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.width(106.dp)
                    )
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(14.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(item.probability.toFloat().coerceIn(0.03f, 1f))
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(6.dp))
                                .background(barColor)
                        )
                    }
                    Text(
                        text = QuantFormatters.percent(item.probability),
                        style = MaterialTheme.typography.labelMedium,
                        fontFamily = MonoFontFamily,
                        fontWeight = FontWeight.Bold,
                        color = barColor,
                        textAlign = TextAlign.End,
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier.width(48.dp)
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TennisKpiTile(
                    label = "Set 1 (J1)",
                    value = QuantFormatters.percent(match.probP1Set),
                    accent = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f)
                )
                TennisKpiTile(
                    label = if (raw.bestOfSets == 5) "+3.5 Sets" else "+2.5 Sets",
                    value = QuantFormatters.percent(match.probOverSets),
                    accent = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.weight(1f)
                )
                TennisKpiTile(
                    label = "Tie-Break",
                    value = QuantFormatters.percent(match.probTiebreakInMatch),
                    accent = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun TennisMarketTableCard(
    markets: List<TennisMarketEvaluation>,
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
                        tint = MaterialTheme.colorScheme.secondary
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
                        .testTag("edit_tennis_odds_button")
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
