package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.MedicalServices
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.quant.MarketEvaluation
import com.example.quant.MatchQuantAnalysis
import com.example.quant.QuantEngine
import com.example.quant.SportMode
import com.example.ui.AnalysisUiState
import com.example.ui.BetUiState
import com.example.ui.QuantFormatters
import com.example.ui.theme.MonoFontFamily

@Composable
fun QuantMatrixTab(
    uiState: BetUiState,
    onSelectSport: (SportMode) -> Unit,
    onSelectMatch: (Int) -> Unit,
    onSelectBasketballMatch: (Int) -> Unit,
    onToggleFullMatrix: () -> Unit,
    onUpdateMatchOdds: (String, Double, Double, Double, Double, Double, Double) -> Unit,
    onUpdateBasketballOdds: (String, Double, Double, Double, Double, Double, Double, Double, Double, Double, Double, Double) -> Unit,
    onNavigateToAnalyze: () -> Unit
) {
    if (uiState.sportMode == SportMode.BASKETBALL) {
        BasketballMatrixContent(
            uiState = uiState,
            onSelectSport = onSelectSport,
            onSelectMatch = onSelectBasketballMatch,
            onUpdateLinesAndOdds = onUpdateBasketballOdds,
            onNavigateToAnalyze = onNavigateToAnalyze
        )
        return
    }

    val successState = uiState.analysisState as? AnalysisUiState.Success
    if (successState == null || successState.bundle.matches.isEmpty()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item(key = "matrix_sport_toggle_empty") {
                SportModeSelectorRow(sportMode = uiState.sportMode, onSelectSport = onSelectSport)
            }
            item(key = "matrix_empty_state") {
                EmptyMatrixState(onNavigateToAnalyze = onNavigateToAnalyze)
            }
        }
        return
    }

    val matches = successState.bundle.matches
    val safeIndex = uiState.selectedMatchIndex.coerceIn(0, matches.lastIndex)
    val selectedMatch = matches[safeIndex]
    var showOddsDialog by remember(selectedMatch.rawMatch.id) { mutableStateOf(false) }
    val chipRowState = rememberLazyListState()

    LaunchedEffect(safeIndex, matches.size) {
        if (matches.isNotEmpty()) {
            chipRowState.animateScrollToItem(safeIndex)
        }
    }

    if (showOddsDialog) {
        EditBetclicOddsDialog(
            match = selectedMatch,
            onDismiss = { showOddsDialog = false },
            onConfirm = { o1, oX, o2, o25, u35, btts ->
                onUpdateMatchOdds(selectedMatch.rawMatch.id, o1, oX, o2, o25, u35, btts)
                showOddsDialog = false
            }
        )
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item(key = "matrix_sport_toggle") {
            SportModeSelectorRow(sportMode = uiState.sportMode, onSelectSport = onSelectSport)
        }

        item(key = "match_selector") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Matriz Quantitativa",
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
                            modifier = Modifier.testTag("football_prev_match")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                                contentDescription = "Jogo anterior"
                            )
                        }
                        IconButton(
                            onClick = { if (safeIndex < matches.lastIndex) onSelectMatch(safeIndex + 1) },
                            enabled = safeIndex < matches.lastIndex,
                            modifier = Modifier.testTag("football_next_match")
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
                    itemsIndexed(matches) { index, match ->
                        val isSelected = index == safeIndex
                        FilterChip(
                            selected = isSelected,
                            onClick = { onSelectMatch(index) },
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag("matrix_match_chip_$index"),
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
                                    text = "${index + 1}. ${match.rawMatch.equipaCasa} vs ${match.rawMatch.equipaFora}",
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

        item(key = "match_intel_${selectedMatch.rawMatch.id}") {
            MatchIntelCard(match = selectedMatch)
        }

        item(key = "dixon_coles_matrix_${selectedMatch.rawMatch.id}") {
            DixonColesHeatmapCard(
                match = selectedMatch,
                showFull12x12 = uiState.showFull12x12Matrix,
                onToggleFullMatrix = onToggleFullMatrix
            )
        }

        item(key = "market_valuation_${selectedMatch.rawMatch.id}") {
            MarketValuationTableCard(
                markets = selectedMatch.markets,
                onEditOddsClick = { showOddsDialog = true }
            )
        }
    }
}

@Composable
private fun EmptyMatrixState(onNavigateToAnalyze: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Card(
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
                    imageVector = Icons.Filled.GridOn,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.size(44.dp)
                )
                Text(
                    text = stringResource(R.string.empty_matrix_title),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = stringResource(R.string.empty_matrix_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
                Button(
                    onClick = onNavigateToAnalyze,
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("empty_matrix_go_boletins")
                ) {
                    Text(text = stringResource(R.string.tab_slips))
                }
            }
        }
    }
}

@Composable
private fun MatchIntelCard(match: MatchQuantAnalysis) {
    val raw = match.rawMatch
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
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

            Text(
                text = "${raw.equipaCasa} vs ${raw.equipaFora}",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                IntelStatBox(
                    title = "λ Casa (Elo)",
                    value = QuantFormatters.xg(match.adjustedLambdaHome),
                    sub = "xG base ${QuantFormatters.xg(raw.xgCasaEstimado)}",
                    modifier = Modifier.weight(1f)
                )
                IntelStatBox(
                    title = "λ Fora (Elo)",
                    value = QuantFormatters.xg(match.adjustedLambdaAway),
                    sub = "xG base ${QuantFormatters.xg(raw.xgForaEstimado)}",
                    modifier = Modifier.weight(1f)
                )
                IntelStatBox(
                    title = "Δ Elo (+65)",
                    value = String.format(java.util.Locale.US, "%+.0f", match.eloDiffWithHomeAdvantage),
                    sub = "E(H) ${QuantFormatters.percent(match.eloExpectedHomeScore)}",
                    modifier = Modifier.weight(1f)
                )
            }

            Surface(
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.MedicalServices,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.size(18.dp)
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = "Lesões, Castigos & Notícias",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = raw.lesoesChave,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun IntelStatBox(
    title: String,
    value: String,
    sub: String,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = title,
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
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = sub,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun DixonColesHeatmapCard(
    match: MatchQuantAnalysis,
    showFull12x12: Boolean,
    onToggleFullMatrix: () -> Unit
) {
    val gridSize = if (showFull12x12) QuantEngine.MATRIX_SIZE else 6
    val maxProb = match.scoreMatrix12x12
        .flatten()
        .maxOrNull()
        ?.coerceAtLeast(0.01) ?: 0.15

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = "Matriz Poisson + Dixon-Coles",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Cálculo 12×12 • ρ = ${QuantEngine.DIXON_COLES_RHO} (Linhas: Casa, Colunas: Fora)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                OutlinedButton(
                    onClick = onToggleFullMatrix,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .testTag("toggle_matrix_size_button")
                ) {
                    Text(
                        text = if (showFull12x12) "Foco 6×6" else "Ver Matriz Completa 12×12",
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // Top 5 Most Probable Exact Scorelines
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed(match.topScorelines) { _, score ->
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "${score.homeGoals}-${score.awayGoals}",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = QuantFormatters.percent(score.probability),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }

            // Heatmap Matrix Grid
            val hScroll = rememberScrollState()
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(hScroll),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // Header row (away goals)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Box(
                        modifier = Modifier.size(width = 36.dp, height = 24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "C\\F",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    for (a in 0 until gridSize) {
                        Box(
                            modifier = Modifier.size(width = 48.dp, height = 24.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = a.toString(),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.secondary
                            )
                        }
                    }
                }

                // Data rows (home goals)
                for (h in 0 until gridSize) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier.size(width = 36.dp, height = 34.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = h.toString(),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        for (a in 0 until gridSize) {
                            val p = match.scoreMatrix12x12[h][a]
                            val intensity = (p / maxProb).toFloat().coerceIn(0f, 1f)
                            val cellColor = when {
                                h > a -> MaterialTheme.colorScheme.primary.copy(alpha = 0.08f + intensity * 0.58f)
                                h == a -> MaterialTheme.colorScheme.tertiary.copy(alpha = 0.08f + intensity * 0.55f)
                                else -> MaterialTheme.colorScheme.secondary.copy(alpha = 0.08f + intensity * 0.58f)
                            }
                            Box(
                                modifier = Modifier
                                    .size(width = 48.dp, height = 34.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(cellColor),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = if (p >= 0.001) String.format(java.util.Locale.US, "%.1f", p * 100.0) else "·",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = if (intensity > 0.55f) FontWeight.Bold else FontWeight.Normal,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MarketValuationTableCard(
    markets: List<MarketEvaluation>,
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
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "Edge, +EV & 1/4 Kelly",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                OutlinedButton(
                    onClick = onEditOddsClick,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .testTag("edit_betclic_odds_button")
                ) {
                    Icon(
                        imageVector = Icons.Filled.Edit,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Ajustar Odds Betclic.pt",
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // Column headers
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Mercado", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1.25f))
                Text("Odd", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.End, maxLines = 1, softWrap = false, modifier = Modifier.weight(0.75f))
                Text("Real/Casa", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.End, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1.30f))
                Text("Edge", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.End, maxLines = 1, softWrap = false, modifier = Modifier.weight(0.95f))
                Text("Kelly", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.End, maxLines = 1, softWrap = false, modifier = Modifier.weight(0.85f))
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            markets.forEach { eval ->
                val isPositive = eval.edge > 0.0
                val rowBg = if (isPositive) {
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                } else {
                    MaterialTheme.colorScheme.surface.copy(alpha = 0.55f)
                }
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = rowBg
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = eval.market.code,
                            style = MaterialTheme.typography.labelLarge,
                            color = if (isPositive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1.25f)
                        )
                        Text(
                            text = QuantFormatters.odd(eval.odd),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            textAlign = TextAlign.End,
                            maxLines = 1,
                            softWrap = false,
                            modifier = Modifier.weight(0.75f)
                        )
                        Text(
                            text = "${QuantFormatters.percent(eval.trueProbability)}/${QuantFormatters.percent(eval.impliedProbability)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.End,
                            maxLines = 1,
                            softWrap = false,
                            modifier = Modifier.weight(1.30f)
                        )
                        Text(
                            text = QuantFormatters.signedPercentFraction(eval.edge),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = if (isPositive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.End,
                            maxLines = 1,
                            softWrap = false,
                            modifier = Modifier.weight(0.95f)
                        )
                        Text(
                            text = if (eval.quarterKellyFraction > 0.0) {
                                QuantFormatters.percent(eval.quarterKellyFraction)
                            } else {
                                "0.0%"
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = if (eval.quarterKellyFraction > 0.0) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant,
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
