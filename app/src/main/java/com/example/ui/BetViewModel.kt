package com.example.ui

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.BetHistoryRepository
import com.example.data.BetSlipEntity
import com.example.data.GeminiRepository
import com.example.quant.AnalysisBundle
import com.example.quant.BetStatus
import com.example.quant.GeneratedSlip
import com.example.quant.GroundedFetchResult
import com.example.quant.QuantEngine
import com.example.quant.RoiSummary
import com.example.quant.StakeMode
import com.example.quant.TemporalCycleInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "BetViewModel"

sealed interface AnalysisUiState {
    data object Idle : AnalysisUiState
    data class Analyzing(val stepIndex: Int, val stepLabel: String) : AnalysisUiState
    data class Success(val bundle: AnalysisBundle) : AnalysisUiState
    data class Error(val message: String) : AnalysisUiState
}

enum class HistoryFilter(val label: String) {
    ALL("Todos"),
    PENDING("Pendentes"),
    WON("Ganhos"),
    LOST("Perdidos")
}

data class BetUiState(
    val cycleInfo: TemporalCycleInfo = QuantEngine.detectCurrentPortugalCycle(),
    val stakeMode: StakeMode = StakeMode.FREEBET,
    val budgetInput: String = "5.00",
    val budgetAmount: Double = 5.0,
    val useFullLeverageSlip3: Boolean = false,
    val selectedMatchIndex: Int = 0,
    val showFull12x12Matrix: Boolean = false,
    val historyFilter: HistoryFilter = HistoryFilter.ALL,
    val savedSlipKeys: Set<String> = emptySet(),
    val analysisState: AnalysisUiState = AnalysisUiState.Idle
)

class BetViewModel(
    private val geminiRepository: GeminiRepository,
    private val historyRepository: BetHistoryRepository
) : ViewModel() {

    private var lastFetchResult: GroundedFetchResult? = null

    private val _uiState = MutableStateFlow(BetUiState())
    val uiState: StateFlow<BetUiState> = _uiState.asStateFlow()

    private val _snackbarMessages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val snackbarMessages: SharedFlow<String> = _snackbarMessages.asSharedFlow()

    val savedSlips: StateFlow<List<BetSlipEntity>> = historyRepository.allSlips
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000L),
            initialValue = emptyList()
        )

    val roiSummary: StateFlow<RoiSummary> = savedSlips
        .map { BetHistoryRepository.computeRoiSummary(it) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000L),
            initialValue = BetHistoryRepository.computeRoiSummary(emptyList())
        )

    fun refreshCycleClock() {
        _uiState.update { it.copy(cycleInfo = QuantEngine.detectCurrentPortugalCycle()) }
    }

    fun setStakeMode(mode: StakeMode) {
        val defaultBudget = if (mode == StakeMode.FREEBET) 5.0 else 20.0
        val currentState = _uiState.value
        val nextBudget = if (
            (currentState.stakeMode == StakeMode.FREEBET && currentState.budgetAmount == 5.0) ||
            (currentState.stakeMode == StakeMode.REAL_BANKROLL && currentState.budgetAmount == 20.0)
        ) {
            defaultBudget
        } else {
            currentState.budgetAmount
        }

        _uiState.update {
            it.copy(
                stakeMode = mode,
                budgetAmount = nextBudget,
                budgetInput = String.format(java.util.Locale.US, "%.2f", nextBudget),
                savedSlipKeys = emptySet()
            )
        }
        recalculateLocallyIfReady()
    }

    fun onBudgetInputChanged(raw: String) {
        val sanitized = raw.replace(',', '.')
        val parsed = sanitized.toDoubleOrNull()
        _uiState.update {
            it.copy(
                budgetInput = raw,
                budgetAmount = parsed?.coerceIn(1.0, 50_000.0) ?: it.budgetAmount,
                savedSlipKeys = emptySet()
            )
        }
        if (parsed != null && parsed >= 1.0) {
            recalculateLocallyIfReady()
        }
    }

    fun selectBudgetPreset(amount: Double) {
        _uiState.update {
            it.copy(
                budgetAmount = amount,
                budgetInput = String.format(java.util.Locale.US, "%.2f", amount),
                savedSlipKeys = emptySet()
            )
        }
        recalculateLocallyIfReady()
    }

    fun setUseFullLeverageSlip3(useFull: Boolean) {
        _uiState.update { it.copy(useFullLeverageSlip3 = useFull) }
    }

    fun selectMatchIndex(index: Int) {
        _uiState.update { it.copy(selectedMatchIndex = index.coerceAtLeast(0)) }
    }

    fun toggleFull12x12Matrix() {
        _uiState.update { it.copy(showFull12x12Matrix = !it.showFull12x12Matrix) }
    }

    fun setHistoryFilter(filter: HistoryFilter) {
        _uiState.update { it.copy(historyFilter = filter) }
    }

    /**
     * Executes the complete 5-step BetQuant AI pipeline:
     * 1. Portugal WET/WEST cycle detection
     * 2. Gemini + Google Search Grounding structured JSON fetch
     * 3. Local Poisson 12x12 + Dixon-Coles (rho = -0.13) + Elo + Edge/Kelly
     * 4. 3-Slip portfolio builder + 100,000 correlated Monte Carlo iterations
     */
    fun analyzeAndGenerateSlips() {
        if (_uiState.value.analysisState is AnalysisUiState.Analyzing) return

        viewModelScope.launch {
            try {
                // Step 1: Detect live Portugal Continental WET/WEST cycle
                val cycleInfo = QuantEngine.detectCurrentPortugalCycle()
                _uiState.update {
                    it.copy(
                        cycleInfo = cycleInfo,
                        savedSlipKeys = emptySet(),
                        analysisState = AnalysisUiState.Analyzing(
                            stepIndex = 1,
                            stepLabel = "Passo 1/4 • A validar ${cycleInfo.cycle.code} (${cycleInfo.startDateIso} a ${cycleInfo.endDateIso} WET)…"
                        )
                    )
                }

                // Step 2: Gemini + Google Search Grounding (Flashscore, ZeroZero, FBref, Transfermarkt, Betclic.pt)
                _uiState.update {
                    it.copy(
                        analysisState = AnalysisUiState.Analyzing(
                            stepIndex = 2,
                            stepLabel = "Passo 2/4 • Google Search Grounding (Flashscore, FBref, Transfermarkt, OddsPortal)…"
                        )
                    )
                }
                val fetched = withContext(Dispatchers.IO) {
                    geminiRepository.fetchGroundedCycleMatches(
                        cycleInfo = cycleInfo,
                        onStageUpdate = { stageMsg ->
                            _uiState.update { state ->
                                state.copy(
                                    analysisState = AnalysisUiState.Analyzing(
                                        stepIndex = 2,
                                        stepLabel = stageMsg
                                    )
                                )
                            }
                        }
                    )
                }
                lastFetchResult = fetched

                // Step 3: Local Mathematical Engine (Poisson 12x12, Dixon-Coles, Elo, +EV, 1/4 Kelly)
                _uiState.update {
                    it.copy(
                        analysisState = AnalysisUiState.Analyzing(
                            stepIndex = 3,
                            stepLabel = "Passo 3/4 • A calcular Poisson 12×12 + Dixon-Coles (ρ=-0.13) + Elo para ${fetched.matches.size} jogos…"
                        )
                    )
                }

                // Step 4: 3 Slips + Correlated 100,000-iteration Monte Carlo simulation
                _uiState.update {
                    it.copy(
                        analysisState = AnalysisUiState.Analyzing(
                            stepIndex = 4,
                            stepLabel = "Passo 4/4 • A simular 100.000 cenários correlacionados de Monte Carlo…"
                        )
                    )
                }
                val stateSnapshot = _uiState.value
                val bundle = withContext(Dispatchers.Default) {
                    QuantEngine.buildAnalysisBundle(
                        fetchResult = fetched,
                        stakeMode = stateSnapshot.stakeMode,
                        totalBudget = stateSnapshot.budgetAmount
                    )
                }

                _uiState.update {
                    it.copy(
                        cycleInfo = fetched.cycleInfo,
                        selectedMatchIndex = 0,
                        analysisState = AnalysisUiState.Success(bundle)
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Pipeline execution failed", e)
                _uiState.update {
                    it.copy(
                        analysisState = AnalysisUiState.Error(
                            e.message ?: "Erro ao executar o pipeline quantitativo."
                        )
                    )
                }
            }
        }
    }

    private fun recalculateLocallyIfReady() {
        val fetched = lastFetchResult ?: return
        val snapshot = _uiState.value
        viewModelScope.launch {
            val recomputed = withContext(Dispatchers.Default) {
                QuantEngine.buildAnalysisBundle(
                    fetchResult = fetched,
                    stakeMode = snapshot.stakeMode,
                    totalBudget = snapshot.budgetAmount
                )
            }
            _uiState.update { it.copy(analysisState = AnalysisUiState.Success(recomputed)) }
        }
    }

    fun updateMatchOdds(
        matchId: String,
        odd1: Double,
        oddX: Double,
        odd2: Double,
        oddOver25: Double,
        oddUnder35: Double,
        oddBtts: Double
    ) {
        val current = lastFetchResult ?: return
        val updatedMatches = current.matches.map { m ->
            if (m.id == matchId) {
                m.copy(
                    odd1 = odd1.coerceAtLeast(1.02),
                    oddX = oddX.coerceAtLeast(1.02),
                    odd2 = odd2.coerceAtLeast(1.02),
                    oddOver25 = oddOver25.coerceAtLeast(1.02),
                    oddUnder35 = oddUnder35.coerceAtLeast(1.02),
                    oddBtts = oddBtts.coerceAtLeast(1.02)
                )
            } else {
                m
            }
        }
        lastFetchResult = current.copy(matches = updatedMatches)
        _uiState.update { it.copy(savedSlipKeys = emptySet()) }
        recalculateLocallyIfReady()
        _snackbarMessages.tryEmit("Odds Betclic.pt atualizadas • Boletins e Monte Carlo recalculados.")
    }

    fun saveSlipToHistory(slip: GeneratedSlip) {
        val cycleLabel = "${_uiState.value.cycleInfo.cycle.code} • ${_uiState.value.cycleInfo.startDateIso}"
        val key = "${slip.type.code}_${slip.isFullLeverageVariant}"
        viewModelScope.launch {
            historyRepository.saveGeneratedSlip(slip, cycleLabel)
            _uiState.update { it.copy(savedSlipKeys = it.savedSlipKeys + key) }
            _snackbarMessages.tryEmit("${slip.title} guardado no histórico Room.")
        }
    }

    fun saveAllActiveSlips() {
        val success = _uiState.value.analysisState as? AnalysisUiState.Success ?: return
        val bundle = success.bundle
        val activeSlip3 = if (_uiState.value.useFullLeverageSlip3) bundle.slip3Full else bundle.slip3Base
        val slips = listOf(bundle.slip1, bundle.slip2, activeSlip3)
        val cycleLabel = "${bundle.cycleInfo.cycle.code} • ${bundle.cycleInfo.startDateIso}"
        val keys = slips.map { "${it.type.code}_${it.isFullLeverageVariant}" }.toSet()

        viewModelScope.launch {
            historyRepository.saveGeneratedSlips(slips, cycleLabel)
            _uiState.update { it.copy(savedSlipKeys = it.savedSlipKeys + keys) }
            _snackbarMessages.tryEmit("Os 3 boletins foram registados no histórico local.")
        }
    }

    fun markSlipOutcome(slip: BetSlipEntity, status: BetStatus) {
        viewModelScope.launch {
            historyRepository.updateOutcome(slip, status)
            _snackbarMessages.tryEmit("Estado atualizado para ${status.label}.")
        }
    }

    fun deleteSavedSlip(id: Long) {
        viewModelScope.launch {
            historyRepository.deleteSlip(id)
            _snackbarMessages.tryEmit("Boletim removido do histórico.")
        }
    }

    class Factory(
        private val geminiRepository: GeminiRepository,
        private val historyRepository: BetHistoryRepository
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return BetViewModel(geminiRepository, historyRepository) as T
        }
    }
}
