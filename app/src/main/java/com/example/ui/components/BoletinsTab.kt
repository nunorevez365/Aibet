package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoGraph
import androidx.compose.material.icons.filled.BookmarkAdded
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Savings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.quant.AnalysisBundle
import com.example.quant.BetSlipType
import com.example.quant.GeneratedSlip
import com.example.quant.GroundingSource
import com.example.quant.MonteCarloReport
import com.example.quant.QuantEngine
import com.example.quant.StakeMode
import com.example.quant.TemporalCycleInfo
import com.example.ui.AnalysisUiState
import com.example.ui.BetUiState
import com.example.ui.QuantFormatters
import com.example.ui.theme.MonoFontFamily

@Composable
fun BoletinsTab(
    uiState: BetUiState,
    onAnalyzeClick: () -> Unit,
    onStakeModeChange: (StakeMode) -> Unit,
    onBudgetInputChange: (String) -> Unit,
    onBudgetPresetClick: (Double) -> Unit,
    onToggleSlip3Variant: (Boolean) -> Unit,
    onSaveSlip: (GeneratedSlip) -> Unit,
    onSaveAllSlips: () -> Unit,
    onOpenMatrixForMatch: (Int) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item(key = "hero_header") {
            QuantHeroBanner(cycleInfo = uiState.cycleInfo)
        }

        item(key = "stake_panel") {
            BankrollControlCard(
                stakeMode = uiState.stakeMode,
                budgetInput = uiState.budgetInput,
                budgetAmount = uiState.budgetAmount,
                onStakeModeChange = onStakeModeChange,
                onBudgetInputChange = onBudgetInputChange,
                onBudgetPresetClick = onBudgetPresetClick
            )
        }

        item(key = "cta_analyze") {
            AnalyzePipelineButton(
                analysisState = uiState.analysisState,
                onClick = onAnalyzeClick
            )
        }

        when (val state = uiState.analysisState) {
            is AnalysisUiState.Idle -> {
                item(key = "idle_state") {
                    IdleMethodologyCard()
                }
            }

            is AnalysisUiState.Analyzing -> {
                item(key = "analyzing_card") {
                    PipelineProgressCard(
                        stepIndex = state.stepIndex,
                        stepLabel = state.stepLabel
                    )
                }
            }

            is AnalysisUiState.Error -> {
                item(key = "error_card") {
                    ErrorCard(
                        message = state.message,
                        onRetry = onAnalyzeClick
                    )
                }
            }

            is AnalysisUiState.Success -> {
                val bundle = state.bundle
                val activeSlip3 = if (uiState.useFullLeverageSlip3) bundle.slip3Full else bundle.slip3Base
                val activeMc = if (uiState.useFullLeverageSlip3) bundle.monteCarloFull else bundle.monteCarloBase

                item(key = "monte_carlo") {
                    MonteCarloTelemetryCard(
                        report = activeMc,
                        stakeMode = bundle.stakeMode,
                        matchCount = bundle.matches.size,
                        summaryNote = bundle.modelSummaryNote
                    )
                }

                item(key = "slips_header") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Boletins Quantitativos (3)",
                                style = MaterialTheme.typography.titleLarge,
                                color = MaterialTheme.colorScheme.onBackground
                            )
                            Text(
                                text = "Poisson 12×12 + Dixon-Coles (ρ=-0.13) + Elo",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        OutlinedButton(
                            onClick = onSaveAllSlips,
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag("save_all_slips_button"),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.BookmarkAdded,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = stringResource(R.string.label_save_all_slips),
                                style = MaterialTheme.typography.labelLarge
                            )
                        }
                    }
                }

                item(key = "slip_1") {
                    GeneratedSlipCard(
                        slip = bundle.slip1,
                        accentColor = MaterialTheme.colorScheme.primary,
                        accentContainer = MaterialTheme.colorScheme.primaryContainer,
                        isSaved = uiState.savedSlipKeys.contains("${bundle.slip1.type.code}_${bundle.slip1.isFullLeverageVariant}"),
                        bundle = bundle,
                        onSave = { onSaveSlip(bundle.slip1) },
                        onOpenMatchMatrix = onOpenMatrixForMatch
                    )
                }

                item(key = "slip_2") {
                    GeneratedSlipCard(
                        slip = bundle.slip2,
                        accentColor = MaterialTheme.colorScheme.secondary,
                        accentContainer = MaterialTheme.colorScheme.secondaryContainer,
                        isSaved = uiState.savedSlipKeys.contains("${bundle.slip2.type.code}_${bundle.slip2.isFullLeverageVariant}"),
                        bundle = bundle,
                        onSave = { onSaveSlip(bundle.slip2) },
                        onOpenMatchMatrix = onOpenMatrixForMatch
                    )
                }

                item(key = "slip_3") {
                    GeneratedSlipCard(
                        slip = activeSlip3,
                        accentColor = MaterialTheme.colorScheme.tertiary,
                        accentContainer = MaterialTheme.colorScheme.tertiaryContainer,
                        isSaved = uiState.savedSlipKeys.contains("${activeSlip3.type.code}_${activeSlip3.isFullLeverageVariant}"),
                        bundle = bundle,
                        showLeverageToggle = true,
                        isFullLeverage = uiState.useFullLeverageSlip3,
                        onToggleLeverage = onToggleSlip3Variant,
                        onSave = { onSaveSlip(activeSlip3) },
                        onOpenMatchMatrix = onOpenMatrixForMatch
                    )
                }

                if (bundle.groundingSources.isNotEmpty() || bundle.searchQueries.isNotEmpty()) {
                    item(key = "grounding_sources") {
                        GroundingSourcesCard(
                            sources = bundle.groundingSources,
                            queries = bundle.searchQueries
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun QuantHeroBanner(cycleInfo: TemporalCycleInfo) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 164.dp)
        ) {
            Image(
                painter = painterResource(id = R.drawable.img_quant_hero_1795077792709),
                contentDescription = stringResource(R.string.cd_hero_banner),
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize()
            )
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(
                        Brush.horizontalGradient(
                            colors = listOf(
                                MaterialTheme.colorScheme.background.copy(alpha = 0.94f),
                                MaterialTheme.colorScheme.background.copy(alpha = 0.72f),
                                MaterialTheme.colorScheme.background.copy(alpha = 0.35f)
                            )
                        )
                    )
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.primaryContainer,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f))
                    ) {
                        Text(
                            text = cycleInfo.cycle.code,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.9f)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Schedule,
                                contentDescription = stringResource(R.string.cd_info_cycle),
                                tint = MaterialTheme.colorScheme.secondary,
                                modifier = Modifier.size(13.dp)
                            )
                            Text(
                                text = "${cycleInfo.currentTimeWet} ${cycleInfo.timezoneLabel}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }

                Text(
                    text = stringResource(R.string.app_name),
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onBackground
                )

                Text(
                    text = "${cycleInfo.cycle.daySpanLabel} • ${cycleInfo.startDateIso} → ${cycleInfo.endDateIso}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.secondary
                )

                Text(
                    text = cycleInfo.cycle.targetCompetitions,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun BankrollControlCard(
    stakeMode: StakeMode,
    budgetInput: String,
    budgetAmount: Double,
    onStakeModeChange: (StakeMode) -> Unit,
    onBudgetInputChange: (String) -> Unit,
    onBudgetPresetClick: (Double) -> Unit
) {
    val allocation = QuantEngine.computeStakeAllocation(stakeMode, budgetAmount)
    val presets = if (stakeMode == StakeMode.FREEBET) {
        listOf(5.0, 10.0, 15.0, 25.0)
    } else {
        listOf(20.0, 50.0, 100.0, 250.0)
    }

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
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                StakeMode.entries.forEach { mode ->
                    val selected = stakeMode == mode
                    Surface(
                        onClick = { onStakeModeChange(mode) },
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                            .testTag("mode_${mode.name.lowercase()}"),
                        shape = MaterialTheme.shapes.small,
                        color = if (selected) {
                            if (mode == StakeMode.FREEBET) MaterialTheme.colorScheme.tertiaryContainer
                            else MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerHigh
                        },
                        border = BorderStroke(
                            width = 1.dp,
                            color = if (selected) {
                                if (mode == StakeMode.FREEBET) MaterialTheme.colorScheme.tertiary
                                else MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.outline
                            }
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = if (mode == StakeMode.FREEBET) Icons.Filled.Bolt else Icons.Outlined.Savings,
                                contentDescription = null,
                                tint = if (selected) {
                                    if (mode == StakeMode.FREEBET) MaterialTheme.colorScheme.tertiary
                                    else MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = mode.label,
                                style = MaterialTheme.typography.labelLarge,
                                color = if (selected) MaterialTheme.colorScheme.onSurface
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = budgetInput,
                    onValueChange = onBudgetInputChange,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("budget_amount_input"),
                    singleLine = true,
                    label = {
                        Text(
                            text = if (stakeMode == StakeMode.FREEBET) {
                                stringResource(R.string.label_budget_freebet)
                            } else {
                                stringResource(R.string.label_budget_real)
                            }
                        )
                    },
                    textStyle = MaterialTheme.typography.titleMedium.copy(fontFamily = MonoFontFamily),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline
                    )
                )

                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(presets) { amount ->
                        val isSelected = kotlin.math.abs(budgetAmount - amount) < 0.01
                        FilterChip(
                            selected = isSelected,
                            onClick = { onBudgetPresetClick(amount) },
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag("preset_${amount.toInt()}"),
                            label = {
                                Text(
                                    text = "${amount.toInt()}€",
                                    fontFamily = MonoFontFamily
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                selectedLabelColor = MaterialTheme.colorScheme.primary
                            )
                        )
                    }
                }
            }

            // Live Stake Allocation Breakdown Strip
            Surface(
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.75f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Gestão Automática de Stake",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        if (stakeMode == StakeMode.REAL_BANKROLL) {
                            Text(
                                text = "Caixa Intocável (50%): ${QuantFormatters.euro(allocation.protectedCash)}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        } else {
                            Text(
                                text = "SNR • Lucro Líquido em Saldo Real",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.tertiary
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        AllocationPill(
                            label = "B1 (40%)",
                            value = QuantFormatters.euro(allocation.slip1Stake),
                            accent = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.weight(1f)
                        )
                        AllocationPill(
                            label = "B2 (40%)",
                            value = QuantFormatters.euro(allocation.slip2Stake),
                            accent = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.weight(1f)
                        )
                        AllocationPill(
                            label = "B3 (20%)",
                            value = QuantFormatters.euro(allocation.slip3Stake),
                            accent = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AllocationPill(
    label: String,
    value: String,
    accent: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = value,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = accent
            )
        }
    }
}

@Composable
private fun AnalyzePipelineButton(
    analysisState: AnalysisUiState,
    onClick: () -> Unit
) {
    val isLoading = analysisState is AnalysisUiState.Analyzing
    Button(
        onClick = onClick,
        enabled = !isLoading,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .testTag("analyze_generate_button"),
        shape = MaterialTheme.shapes.medium,
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            disabledContainerColor = MaterialTheme.colorScheme.primaryContainer,
            disabledContentColor = MaterialTheme.colorScheme.primary
        )
    ) {
        if (isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.5.dp,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = stringResource(R.string.cta_analyzing),
                style = MaterialTheme.typography.titleSmall
            )
        } else {
            Icon(
                imageVector = Icons.Filled.AutoGraph,
                contentDescription = null,
                modifier = Modifier.size(22.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.cta_analyze_generate),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun PipelineProgressCard(stepIndex: Int, stepLabel: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.secondary.copy(alpha = 0.5f)),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.Public,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.secondary
                )
                Text(
                    text = "Pipeline Quantitativo Ativo",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            LinearProgressIndicator(
                progress = { (stepIndex / 4f).coerceIn(0.15f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(CircleShape),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceContainerHigh
            )
            Text(
                text = stepLabel,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.secondary
            )
        }
    }
}

@Composable
private fun ErrorCard(message: String, onRetry: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
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
                    imageVector = Icons.Filled.ErrorOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error
                )
                Text(
                    text = "Erro na Recolha de Dados",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.error
                )
            }
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            OutlinedButton(
                onClick = onRetry,
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("retry_analyze_button"),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.error)
            ) {
                Icon(
                    imageVector = Icons.Filled.Refresh,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = stringResource(R.string.cta_retry),
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
private fun IdleMethodologyCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = stringResource(R.string.empty_slips_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = stringResource(R.string.empty_slips_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                PipelineBadgeLine("1", "Fontes reais via Google Search: Flashscore, ZeroZero, FBref, Transfermarkt, Betclic.pt")
                PipelineBadgeLine("2", "Motor local Kotlin: Matriz Poisson 12×12 + Dixon-Coles (ρ = -0.13) + Elo dinâmico")
                PipelineBadgeLine("3", "Valuation: Edge real, +EV (Real / Freebet SNR), Kelly 1/4 e Monte Carlo 100.000×")
            }
        }
    }
}

@Composable
private fun PipelineBadgeLine(step: String, text: String) {
    Row(
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.size(20.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = step,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
            }
        }
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun MonteCarloTelemetryCard(
    report: MonteCarloReport,
    stakeMode: StakeMode,
    matchCount: Int,
    summaryNote: String
) {
    val barColorPrimary = MaterialTheme.colorScheme.primary
    val barColorSecondary = MaterialTheme.colorScheme.secondary
    val barColorTertiary = MaterialTheme.colorScheme.tertiary
    val barColorMuted = MaterialTheme.colorScheme.outline

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.secondary.copy(alpha = 0.45f)),
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
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.TrendingUp,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.secondary
                    )
                    Text(
                        text = stringResource(R.string.label_monte_carlo_title),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer
                ) {
                    Text(
                        text = "$matchCount jogos",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
            }

            if (summaryNote.isNotBlank()) {
                Text(
                    text = summaryNote,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                KpiMetricTile(
                    label = "P(≥1 Boletim)",
                    value = QuantFormatters.percent(report.probAtLeastOneWin),
                    accent = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f)
                )
                KpiMetricTile(
                    label = if (stakeMode == StakeMode.FREEBET) "Retorno Médio" else "EV Líquido",
                    value = QuantFormatters.signedEuro(report.expectedNetReturn),
                    accent = if (report.expectedNetReturn >= 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(1f)
                )
                KpiMetricTile(
                    label = "ROI Esperado",
                    value = QuantFormatters.signedPercentDirect(report.expectedRoiPercent),
                    accent = if (report.expectedRoiPercent >= 0) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(1f)
                )
            }

            // Correlated Win Distribution Canvas Bar Chart
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "Distribuição Conjunta de Boletins Ganhos (Correlacionada)",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(68.dp)
                ) {
                    val freqs = report.histogramFrequencies
                    val maxFreq = freqs.maxOrNull()?.coerceAtLeast(0.01f) ?: 1f
                    val count = freqs.size.coerceAtLeast(1)
                    val gap = 14.dp.toPx()
                    val barWidth = (size.width - gap * (count - 1)) / count
                    val colors = listOf(barColorMuted, barColorSecondary, barColorPrimary, barColorTertiary)

                    freqs.forEachIndexed { idx, freq ->
                        val ratio = (freq / maxFreq).coerceIn(0.05f, 1f)
                        val barH = size.height * ratio
                        val left = idx * (barWidth + gap)
                        drawRoundRect(
                            color = colors.getOrElse(idx) { barColorPrimary },
                            topLeft = Offset(left, size.height - barH),
                            size = Size(barWidth, barH),
                            cornerRadius = CornerRadius(8.dp.toPx(), 8.dp.toPx())
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    report.histogramLabels.forEachIndexed { idx, label ->
                        val pct = report.histogramFrequencies.getOrElse(idx) { 0f } * 100f
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = label,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = String.format(java.util.Locale.US, "%.1f%%", pct),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "P95 Retorno: ${QuantFormatters.signedEuro(report.percentile95Return)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "P(3/3): ${QuantFormatters.percent(report.probAllThreeWin)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary
                )
            }
        }
    }
}

@Composable
private fun KpiMetricTile(
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
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
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
                maxLines = 1
            )
        }
    }
}

@Composable
private fun GeneratedSlipCard(
    slip: GeneratedSlip,
    accentColor: Color,
    accentContainer: Color,
    isSaved: Boolean,
    bundle: AnalysisBundle,
    showLeverageToggle: Boolean = false,
    isFullLeverage: Boolean = false,
    onToggleLeverage: (Boolean) -> Unit = {},
    onSave: () -> Unit,
    onOpenMatchMatrix: (Int) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("slip_card_${slip.type.code.lowercase()}"),
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
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHighest
                ) {
                    Text(
                        text = "Odd @ ${QuantFormatters.odd(slip.combinedOdd)}",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = accentColor,
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
                            .testTag("slip3_variant_base"),
                        label = { Text(stringResource(R.string.label_variant_base)) },
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
                            .testTag("slip3_variant_full"),
                        label = { Text(stringResource(R.string.label_variant_full)) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = accentContainer,
                            selectedLabelColor = accentColor
                        )
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            // Individual legs
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
                                        text = leg.market.displayName,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = accentColor
                                    )
                                    Text(
                                        text = "P=${QuantFormatters.percent(leg.trueProbability)}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            Column(
                                horizontalAlignment = Alignment.End,
                                verticalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                Text(
                                    text = QuantFormatters.odd(leg.odd),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontFamily = MonoFontFamily,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "Edge ${QuantFormatters.signedPercentFraction(leg.edge)}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (leg.edge >= 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }
            }

            // Financial metrics footer
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                KpiMetricTile(
                    label = "Stake",
                    value = QuantFormatters.euro(slip.stake),
                    accent = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                KpiMetricTile(
                    label = if (slip.stakeMode == StakeMode.FREEBET) "Lucro Líquido" else "Retorno Bruto",
                    value = QuantFormatters.euro(slip.potentialGrossReturn),
                    accent = accentColor,
                    modifier = Modifier.weight(1f)
                )
                KpiMetricTile(
                    label = "EV Estimado",
                    value = QuantFormatters.signedEuro(slip.expectedValue),
                    accent = if (slip.expectedValue >= 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(1f)
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Prob. Real: ${QuantFormatters.percent(slip.combinedTrueProb)}  •  Edge: ${QuantFormatters.signedPercentDirect(slip.edgePercent)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                OutlinedButton(
                    onClick = onSave,
                    enabled = !isSaved,
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("save_slip_${slip.type.code.lowercase()}"),
                    border = BorderStroke(1.dp, if (isSaved) MaterialTheme.colorScheme.outline else accentColor)
                ) {
                    Icon(
                        imageVector = if (isSaved) Icons.Filled.CheckCircle else Icons.Filled.BookmarkBorder,
                        contentDescription = null,
                        tint = if (isSaved) MaterialTheme.colorScheme.primary else accentColor,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (isSaved) stringResource(R.string.label_saved) else stringResource(R.string.label_save_slip),
                        color = if (isSaved) MaterialTheme.colorScheme.primary else accentColor
                    )
                }
            }
        }
    }
}

@Composable
private fun GroundingSourcesCard(
    sources: List<GroundingSource>,
    queries: List<String>
) {
    val uriHandler = LocalUriHandler.current

    AnimatedVisibility(visible = true, enter = fadeIn()) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Shield,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = stringResource(R.string.label_sources_title),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                if (queries.isNotEmpty()) {
                    Text(
                        text = "Pesquisas: ${queries.take(4).joinToString(" • ")}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(sources.take(10)) { source ->
                        Surface(
                            onClick = {
                                runCatching { uriHandler.openUri(source.uri) }
                            },
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            modifier = Modifier.heightIn(min = 48.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = source.title,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.secondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Icon(
                                    imageVector = Icons.Outlined.OpenInNew,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.secondary,
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
