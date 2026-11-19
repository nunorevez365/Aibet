package com.example.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.outlined.Analytics
import androidx.compose.material.icons.outlined.GridOn
import androidx.compose.material.icons.outlined.ReceiptLong
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.ui.components.BoletinsTab
import com.example.ui.components.HistoryRoiTab
import com.example.ui.components.QuantMatrixTab
import com.example.ui.theme.DeepSlateNavy
import com.example.ui.theme.ObsidianVoid

private data class QuantNavTab(
    val titleRes: Int,
    val testTag: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector
)

private val QUANT_TABS = listOf(
    QuantNavTab(
        titleRes = R.string.tab_slips,
        testTag = "nav_tab_boletins",
        selectedIcon = Icons.Filled.ReceiptLong,
        unselectedIcon = Icons.Outlined.ReceiptLong
    ),
    QuantNavTab(
        titleRes = R.string.tab_matrix,
        testTag = "nav_tab_matrix",
        selectedIcon = Icons.Filled.GridOn,
        unselectedIcon = Icons.Outlined.GridOn
    ),
    QuantNavTab(
        titleRes = R.string.tab_history,
        testTag = "nav_tab_history",
        selectedIcon = Icons.Filled.Analytics,
        unselectedIcon = Icons.Outlined.Analytics
    )
)

@Composable
fun MainScreen(viewModel: BetViewModel) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val savedSlips by viewModel.savedSlips.collectAsStateWithLifecycle()
    val roiSummary by viewModel.roiSummary.collectAsStateWithLifecycle()

    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        viewModel.snackbarMessages.collect { msg ->
            snackbarHostState.showSnackbar(msg)
        }
    }

    // System back button returns to Boletins tab before exiting the app
    BackHandler(enabled = selectedTab != 0) {
        selectedTab = 0
    }

    val backgroundBrush = remember {
        Brush.verticalGradient(
            colors = listOf(ObsidianVoid, DeepSlateNavy, ObsidianVoid)
        )
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundBrush)
    ) {
        val useNavigationRail = maxWidth >= 640.dp

        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = androidx.compose.ui.graphics.Color.Transparent,
            contentWindowInsets = WindowInsets.safeDrawing,
            snackbarHost = {
                SnackbarHost(hostState = snackbarHostState) { data ->
                    Snackbar(
                        snackbarData = data,
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                        actionColor = MaterialTheme.colorScheme.primary
                    )
                }
            },
            bottomBar = {
                if (!useNavigationRail) {
                    NavigationBar(
                        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
                        tonalElevation = 4.dp,
                        windowInsets = WindowInsets.navigationBars
                    ) {
                        QUANT_TABS.forEachIndexed { index, tab ->
                            val isSelected = selectedTab == index
                            NavigationBarItem(
                                selected = isSelected,
                                onClick = { selectedTab = index },
                                modifier = Modifier.testTag(tab.testTag),
                                icon = {
                                    Icon(
                                        imageVector = if (isSelected) tab.selectedIcon else tab.unselectedIcon,
                                        contentDescription = stringResource(tab.titleRes)
                                    )
                                },
                                label = {
                                    Text(
                                        text = stringResource(tab.titleRes),
                                        style = MaterialTheme.typography.labelLarge,
                                        maxLines = 1,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                    )
                                },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = MaterialTheme.colorScheme.primary,
                                    selectedTextColor = MaterialTheme.colorScheme.primary,
                                    indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            )
                        }
                    }
                }
            }
        ) { innerPadding ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                if (useNavigationRail) {
                    NavigationRail(
                        modifier = Modifier.fillMaxHeight(),
                        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f)
                    ) {
                        Spacer(modifier = Modifier.height(16.dp))
                        QUANT_TABS.forEachIndexed { index, tab ->
                            val isSelected = selectedTab == index
                            NavigationRailItem(
                                selected = isSelected,
                                onClick = { selectedTab = index },
                                modifier = Modifier.testTag(tab.testTag),
                                icon = {
                                    Icon(
                                        imageVector = if (isSelected) tab.selectedIcon else tab.unselectedIcon,
                                        contentDescription = stringResource(tab.titleRes)
                                    )
                                },
                                label = {
                                    Text(
                                        text = stringResource(tab.titleRes),
                                        style = MaterialTheme.typography.labelLarge,
                                        maxLines = 1,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                    )
                                },
                                colors = NavigationRailItemDefaults.colors(
                                    selectedIconColor = MaterialTheme.colorScheme.primary,
                                    selectedTextColor = MaterialTheme.colorScheme.primary,
                                    indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            )
                        }
                    }
                }

                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.TopCenter
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .widthIn(max = 840.dp)
                    ) {
                        Crossfade(
                            targetState = selectedTab,
                            animationSpec = tween(220),
                            label = "quant_tab_transition"
                        ) { tabIndex ->
                            when (tabIndex) {
                                0 -> BoletinsTab(
                                    uiState = uiState,
                                    onSelectSport = viewModel::setSportMode,
                                    onSelectCyclePreset = viewModel::selectCyclePreset,
                                    onToggleDate = viewModel::toggleDateSelection,
                                    onAnalyzeClick = viewModel::analyzeAndGenerateSlips,
                                    onRestoreCachedAnalysis = viewModel::restoreLastCachedAnalysis,
                                    onStakeModeChange = viewModel::setStakeMode,
                                    onBudgetInputChange = viewModel::onBudgetInputChanged,
                                    onBudgetPresetClick = viewModel::selectBudgetPreset,
                                    onToggleSlip3Variant = viewModel::setUseFullLeverageSlip3,
                                    onSaveSlip = viewModel::saveSlipToHistory,
                                    onSaveAllSlips = viewModel::saveAllActiveSlips,
                                    onSaveBasketballSlip = viewModel::saveBasketballSlipToHistory,
                                    onSaveAllBasketballSlips = viewModel::saveAllActiveBasketballSlips,
                                    onOpenMatrixForMatch = { matchIdx ->
                                        viewModel.selectMatchIndex(matchIdx)
                                        selectedTab = 1
                                    },
                                    onOpenMatrixForBasketballMatch = { matchIdx ->
                                        viewModel.selectBasketballMatchIndex(matchIdx)
                                        selectedTab = 1
                                    }
                                )
                                1 -> QuantMatrixTab(
                                    uiState = uiState,
                                    onSelectSport = viewModel::setSportMode,
                                    onSelectMatch = viewModel::selectMatchIndex,
                                    onSelectBasketballMatch = viewModel::selectBasketballMatchIndex,
                                    onToggleFullMatrix = viewModel::toggleFull12x12Matrix,
                                    onUpdateMatchOdds = viewModel::updateMatchOdds,
                                    onUpdateBasketballOdds = viewModel::updateBasketballLinesAndOdds,
                                    onNavigateToAnalyze = { selectedTab = 0 }
                                )
                                else -> HistoryRoiTab(
                                    slips = savedSlips,
                                    roiSummary = roiSummary,
                                    activeFilter = uiState.historyFilter,
                                    onFilterChange = viewModel::setHistoryFilter,
                                    onMarkOutcome = viewModel::markSlipOutcome,
                                    onDeleteSlip = viewModel::deleteSavedSlip
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
