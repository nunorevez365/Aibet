package com.example.ui

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.AnalysisCacheStore
import com.example.data.BasketballRepository
import com.example.data.BetHistoryRepository
import com.example.data.BetSlipEntity
import com.example.data.GeminiRepository
import com.example.data.SavedUserSettings
import com.example.data.TennisRepository
import com.example.quant.AnalysisBundle
import com.example.quant.BasketballAnalysisBundle
import com.example.quant.BasketballFetchResult
import com.example.quant.BasketballQuantEngine
import com.example.quant.BasketballSlip
import com.example.quant.BetStatus
import com.example.quant.CyclePreset
import com.example.quant.GeneratedSlip
import com.example.quant.GroundedFetchResult
import com.example.quant.QuantEngine
import com.example.quant.RoiSummary
import com.example.quant.SelectableDay
import com.example.quant.SportMode
import com.example.quant.StakeMode
import com.example.quant.TemporalCycleInfo
import com.example.quant.TennisAnalysisBundle
import com.example.quant.TennisFetchResult
import com.example.quant.TennisQuantEngine
import com.example.quant.TennisSlip
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
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
    data class Error(val message: String, val canRestoreCached: Boolean = false) : AnalysisUiState
}

sealed interface BasketballUiState {
    data object Idle : BasketballUiState
    data class Analyzing(val stepIndex: Int, val stepLabel: String) : BasketballUiState
    data class Success(val bundle: BasketballAnalysisBundle) : BasketballUiState
    data class Error(val message: String, val canRestoreCached: Boolean = false) : BasketballUiState
}

sealed interface TennisUiState {
    data object Idle : TennisUiState
    data class Analyzing(val stepIndex: Int, val stepLabel: String) : TennisUiState
    data class Success(val bundle: TennisAnalysisBundle) : TennisUiState
    data class Error(val message: String, val canRestoreCached: Boolean = false) : TennisUiState
}

enum class HistoryFilter(val label: String) {
    ALL("Todos"),
    PENDING("Pendentes"),
    WON("Ganhos"),
    LOST("Perdidos")
}

data class BetUiState(
    val sportMode: SportMode = SportMode.FOOTBALL,
    val selectableDays: List<SelectableDay> = QuantEngine.buildSelectableDays(dayCount = 10),
    val activePreset: CyclePreset = CyclePreset.WEEKEND_FRI_SUN,
    val selectedDatesIso: Set<String> = QuantEngine.getDatesForPreset(CyclePreset.WEEKEND_FRI_SUN).toSet(),
    val cycleInfo: TemporalCycleInfo = QuantEngine.buildCycleInfoForDates(
        QuantEngine.getDatesForPreset(CyclePreset.WEEKEND_FRI_SUN)
    ),
    val stakeMode: StakeMode = StakeMode.FREEBET,
    val budgetInput: String = "5.00",
    val budgetAmount: Double = 5.0,
    val useFullLeverageSlip3: Boolean = false,
    val selectedMatchIndex: Int = 0,
    val selectedBasketballMatchIndex: Int = 0,
    val selectedTennisMatchIndex: Int = 0,
    val showFull12x12Matrix: Boolean = false,
    val historyFilter: HistoryFilter = HistoryFilter.ALL,
    val savedSlipKeys: Set<String> = emptySet(),
    val savedBasketballSlipKeys: Set<String> = emptySet(),
    val savedTennisSlipKeys: Set<String> = emptySet(),
    val analysisState: AnalysisUiState = AnalysisUiState.Idle,
    val basketballState: BasketballUiState = BasketballUiState.Idle,
    val tennisState: TennisUiState = TennisUiState.Idle
)

class BetViewModel(
    private val geminiRepository: GeminiRepository,
    private val basketballRepository: BasketballRepository = BasketballRepository(),
    private val tennisRepository: TennisRepository = TennisRepository(),
    private val historyRepository: BetHistoryRepository,
    private val cacheStore: AnalysisCacheStore? = null
) : ViewModel() {

    private var lastFetchResult: GroundedFetchResult? = null
    private var lastBasketballFetchResult: BasketballFetchResult? = null
    private var lastTennisFetchResult: TennisFetchResult? = null

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

    init {
        restorePersistedState()
    }

    private fun restorePersistedState() {
        val store = cacheStore ?: return
        viewModelScope.launch {
            val validIsoSet = _uiState.value.selectableDays.map { it.isoDate }.toSet()
            val savedSettings = withContext(Dispatchers.IO) {
                store.loadSettings(validIsoSet)
            }
            if (savedSettings != null) {
                val restoredCycle = QuantEngine.buildCycleInfoForDates(savedSettings.selectedDatesIso)
                _uiState.update {
                    it.copy(
                        sportMode = savedSettings.sportMode,
                        stakeMode = savedSettings.stakeMode,
                        budgetAmount = savedSettings.budgetAmount,
                        budgetInput = String.format(java.util.Locale.US, "%.2f", savedSettings.budgetAmount),
                        useFullLeverageSlip3 = savedSettings.useFullLeverageSlip3,
                        activePreset = savedSettings.activePreset,
                        selectedDatesIso = savedSettings.selectedDatesIso,
                        cycleInfo = restoredCycle
                    )
                }
            }

            val cachedFootball = withContext(Dispatchers.IO) {
                runCatching { store.loadLatestAnalysis() }.getOrNull()
            }
            if (cachedFootball != null && cachedFootball.matches.size >= 2) {
                lastFetchResult = cachedFootball
                val snapshot = _uiState.value
                val restoredBundle = withContext(Dispatchers.Default) {
                    QuantEngine.buildAnalysisBundle(
                        fetchResult = cachedFootball,
                        stakeMode = snapshot.stakeMode,
                        totalBudget = snapshot.budgetAmount
                    )
                }
                _uiState.update { it.copy(analysisState = AnalysisUiState.Success(restoredBundle)) }
            }

            val cachedBasket = withContext(Dispatchers.IO) {
                runCatching { store.loadLatestBasketballAnalysis() }.getOrNull()
            }
            if (cachedBasket != null && cachedBasket.matches.size >= 2) {
                lastBasketballFetchResult = cachedBasket
                val snapshot = _uiState.value
                val restoredBasket = withContext(Dispatchers.Default) {
                    BasketballQuantEngine.buildAnalysisBundle(
                        fetchResult = cachedBasket,
                        stakeMode = snapshot.stakeMode,
                        totalBudget = snapshot.budgetAmount
                    )
                }
                _uiState.update { it.copy(basketballState = BasketballUiState.Success(restoredBasket)) }
            }

            val cachedTennis = withContext(Dispatchers.IO) {
                runCatching { store.loadLatestTennisAnalysis() }.getOrNull()
            }
            if (cachedTennis != null && cachedTennis.matches.size >= 2) {
                lastTennisFetchResult = cachedTennis
                val snapshot = _uiState.value
                val restoredTennis = withContext(Dispatchers.Default) {
                    TennisQuantEngine.buildAnalysisBundle(
                        fetchResult = cachedTennis,
                        stakeMode = snapshot.stakeMode,
                        totalBudget = snapshot.budgetAmount
                    )
                }
                _uiState.update { it.copy(tennisState = TennisUiState.Success(restoredTennis)) }
            }
        }
    }

    private fun persistCurrentSettings() {
        val store = cacheStore ?: return
        val s = _uiState.value
        store.saveSettings(
            SavedUserSettings(
                sportMode = s.sportMode,
                stakeMode = s.stakeMode,
                budgetAmount = s.budgetAmount,
                useFullLeverageSlip3 = s.useFullLeverageSlip3,
                activePreset = s.activePreset,
                selectedDatesIso = s.selectedDatesIso
            )
        )
    }

    fun setSportMode(mode: SportMode) {
        if (_uiState.value.sportMode == mode) return
        _uiState.update { it.copy(sportMode = mode) }
        persistCurrentSettings()
    }

    fun selectCyclePreset(preset: CyclePreset) {
        val now = System.currentTimeMillis()
        val days = QuantEngine.buildSelectableDays(nowMillis = now, dayCount = 10)
        val dates = QuantEngine.getDatesForPreset(preset, nowMillis = now).toSet()
        val updatedCycle = QuantEngine.buildCycleInfoForDates(dates, nowMillis = now)
        _uiState.update {
            it.copy(
                selectableDays = days,
                activePreset = preset,
                selectedDatesIso = dates,
                cycleInfo = updatedCycle
            )
        }
        persistCurrentSettings()
    }

    fun toggleDateSelection(isoDate: String) {
        val now = System.currentTimeMillis()
        val current = _uiState.value.selectedDatesIso
        val next = if (isoDate in current) {
            if (current.size <= 1) current else current - isoDate
        } else {
            current + isoDate
        }
        val updatedCycle = QuantEngine.buildCycleInfoForDates(next, nowMillis = now)
        _uiState.update {
            it.copy(
                activePreset = CyclePreset.CUSTOM,
                selectedDatesIso = next,
                cycleInfo = updatedCycle
            )
        }
        persistCurrentSettings()
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
                savedSlipKeys = emptySet(),
                savedBasketballSlipKeys = emptySet(),
                savedTennisSlipKeys = emptySet()
            )
        }
        persistCurrentSettings()
        recalculateLocallyIfReady()
    }

    fun onBudgetInputChanged(raw: String) {
        val sanitized = raw.replace(',', '.')
        val parsed = sanitized.toDoubleOrNull()
        _uiState.update {
            it.copy(
                budgetInput = raw,
                budgetAmount = parsed?.coerceIn(1.0, 50_000.0) ?: it.budgetAmount,
                savedSlipKeys = emptySet(),
                savedBasketballSlipKeys = emptySet(),
                savedTennisSlipKeys = emptySet()
            )
        }
        if (parsed != null && parsed >= 1.0) {
            persistCurrentSettings()
            recalculateLocallyIfReady()
        }
    }

    fun selectBudgetPreset(amount: Double) {
        _uiState.update {
            it.copy(
                budgetAmount = amount,
                budgetInput = String.format(java.util.Locale.US, "%.2f", amount),
                savedSlipKeys = emptySet(),
                savedBasketballSlipKeys = emptySet(),
                savedTennisSlipKeys = emptySet()
            )
        }
        persistCurrentSettings()
        recalculateLocallyIfReady()
    }

    fun setUseFullLeverageSlip3(useFull: Boolean) {
        _uiState.update { it.copy(useFullLeverageSlip3 = useFull) }
        persistCurrentSettings()
    }

    fun selectMatchIndex(index: Int) {
        _uiState.update { it.copy(selectedMatchIndex = index.coerceAtLeast(0)) }
    }

    fun selectBasketballMatchIndex(index: Int) {
        _uiState.update { it.copy(selectedBasketballMatchIndex = index.coerceAtLeast(0)) }
    }

    fun selectTennisMatchIndex(index: Int) {
        _uiState.update { it.copy(selectedTennisMatchIndex = index.coerceAtLeast(0)) }
    }

    fun toggleFull12x12Matrix() {
        _uiState.update { it.copy(showFull12x12Matrix = !it.showFull12x12Matrix) }
    }

    fun setHistoryFilter(filter: HistoryFilter) {
        _uiState.update { it.copy(historyFilter = filter) }
    }

    fun restoreLastCachedAnalysis() {
        val snapshot = _uiState.value
        when (snapshot.sportMode) {
            SportMode.BASKETBALL -> {
                val cached = lastBasketballFetchResult ?: return
                viewModelScope.launch {
                    val bundle = withContext(Dispatchers.Default) {
                        BasketballQuantEngine.buildAnalysisBundle(cached, snapshot.stakeMode, snapshot.budgetAmount)
                    }
                    _uiState.update { it.copy(basketballState = BasketballUiState.Success(bundle)) }
                    _snackbarMessages.tryEmit("Última análise de Basquetebol restaurada.")
                }
            }
            SportMode.TENNIS -> {
                val cached = lastTennisFetchResult ?: return
                viewModelScope.launch {
                    val bundle = withContext(Dispatchers.Default) {
                        TennisQuantEngine.buildAnalysisBundle(cached, snapshot.stakeMode, snapshot.budgetAmount)
                    }
                    _uiState.update { it.copy(tennisState = TennisUiState.Success(bundle)) }
                    _snackbarMessages.tryEmit("Última análise de Ténis restaurada.")
                }
            }
            SportMode.FOOTBALL -> {
                val cached = lastFetchResult ?: return
                viewModelScope.launch {
                    val bundle = withContext(Dispatchers.Default) {
                        QuantEngine.buildAnalysisBundle(cached, snapshot.stakeMode, snapshot.budgetAmount)
                    }
                    _uiState.update { it.copy(analysisState = AnalysisUiState.Success(bundle)) }
                    _snackbarMessages.tryEmit("Última análise de Futebol restaurada.")
                }
            }
        }
    }

    fun analyzeAndGenerateSlips() {
        when (_uiState.value.sportMode) {
            SportMode.FOOTBALL -> analyzeFootballPipeline()
            SportMode.BASKETBALL -> analyzeBasketballPipeline()
            SportMode.TENNIS -> analyzeTennisPipeline()
        }
    }

    private fun analyzeFootballPipeline() {
        if (_uiState.value.analysisState is AnalysisUiState.Analyzing) return

        viewModelScope.launch {
            try {
                val selectedDates = _uiState.value.selectedDatesIso
                val cycleInfo = QuantEngine.buildCycleInfoForDates(selectedDates)
                _uiState.update {
                    it.copy(
                        cycleInfo = cycleInfo,
                        savedSlipKeys = emptySet(),
                        analysisState = AnalysisUiState.Analyzing(
                            stepIndex = 1,
                            stepLabel = "Passo 1/4 • ${cycleInfo.cycle.code}: ${cycleInfo.startDateIso} a ${cycleInfo.endDateIso} (${cycleInfo.timezoneLabel})…"
                        )
                    )
                }

                _uiState.update {
                    it.copy(
                        analysisState = AnalysisUiState.Analyzing(
                            stepIndex = 2,
                            stepLabel = "Passo 2/4 • A consultar jogos oficiais de Futebol nos dias selecionados…"
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

                cacheStore?.let { store ->
                    withContext(Dispatchers.IO) {
                        runCatching { store.saveLatestAnalysis(fetched) }
                    }
                }

                _uiState.update {
                    it.copy(
                        analysisState = AnalysisUiState.Analyzing(
                            stepIndex = 3,
                            stepLabel = "Passo 3/4 • A calcular Poisson 12×12 + Dixon-Coles (ρ=-0.13) + Elo para ${fetched.matches.size} jogos…"
                        )
                    )
                }

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
            } catch (e: TimeoutCancellationException) {
                Log.e(TAG, "Football pipeline timed out", e)
                _uiState.update {
                    it.copy(
                        analysisState = AnalysisUiState.Error(
                            message = "Tempo limite excedido ao obter dados de Futebol. Tenta novamente.",
                            canRestoreCached = lastFetchResult != null
                        )
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Football pipeline failed", e)
                _uiState.update {
                    it.copy(
                        analysisState = AnalysisUiState.Error(
                            message = e.message ?: "Erro ao executar o pipeline de Futebol.",
                            canRestoreCached = lastFetchResult != null
                        )
                    )
                }
            }
        }
    }

    private fun analyzeBasketballPipeline() {
        if (_uiState.value.basketballState is BasketballUiState.Analyzing) return

        viewModelScope.launch {
            try {
                val selectedDates = _uiState.value.selectedDatesIso
                val cycleInfo = QuantEngine.buildCycleInfoForDates(selectedDates)
                _uiState.update {
                    it.copy(
                        cycleInfo = cycleInfo,
                        savedBasketballSlipKeys = emptySet(),
                        basketballState = BasketballUiState.Analyzing(
                            stepIndex = 1,
                            stepLabel = "Passo 1/4 • Basquetebol: ${cycleInfo.startDateIso} a ${cycleInfo.endDateIso} (${cycleInfo.timezoneLabel})…"
                        )
                    )
                }

                _uiState.update {
                    it.copy(
                        basketballState = BasketballUiState.Analyzing(
                            stepIndex = 2,
                            stepLabel = "Passo 2/4 • A consultar jogos oficiais NBA/NCAA, médias PPG e linhas…"
                        )
                    )
                }
                val fetched = withContext(Dispatchers.IO) {
                    basketballRepository.fetchBasketballMatches(
                        cycleInfo = cycleInfo,
                        onStageUpdate = { stageMsg ->
                            _uiState.update { state ->
                                state.copy(
                                    basketballState = BasketballUiState.Analyzing(
                                        stepIndex = 2,
                                        stepLabel = stageMsg
                                    )
                                )
                            }
                        }
                    )
                }
                lastBasketballFetchResult = fetched

                cacheStore?.let { store ->
                    withContext(Dispatchers.IO) {
                        runCatching { store.saveLatestBasketballAnalysis(fetched) }
                    }
                }

                _uiState.update {
                    it.copy(
                        basketballState = BasketballUiState.Analyzing(
                            stepIndex = 3,
                            stepLabel = "Passo 3/4 • A projetar pontos Q1–Q4, Handicap, Over/Under e Edge para ${fetched.matches.size} jogos…"
                        )
                    )
                }

                _uiState.update {
                    it.copy(
                        basketballState = BasketballUiState.Analyzing(
                            stepIndex = 4,
                            stepLabel = "Passo 4/4 • A simular 100.000 cenários por quartos (Q1–Q4 + Prol.) em Monte Carlo…"
                        )
                    )
                }
                val stateSnapshot = _uiState.value
                val bundle = withContext(Dispatchers.Default) {
                    BasketballQuantEngine.buildAnalysisBundle(
                        fetchResult = fetched,
                        stakeMode = stateSnapshot.stakeMode,
                        totalBudget = stateSnapshot.budgetAmount
                    )
                }

                _uiState.update {
                    it.copy(
                        cycleInfo = fetched.cycleInfo,
                        selectedBasketballMatchIndex = 0,
                        basketballState = BasketballUiState.Success(bundle)
                    )
                }
            } catch (e: TimeoutCancellationException) {
                Log.e(TAG, "Basketball pipeline timed out", e)
                _uiState.update {
                    it.copy(
                        basketballState = BasketballUiState.Error(
                            message = "Tempo limite excedido ao obter dados de Basquetebol. Tenta novamente.",
                            canRestoreCached = lastBasketballFetchResult != null
                        )
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Basketball pipeline failed", e)
                _uiState.update {
                    it.copy(
                        basketballState = BasketballUiState.Error(
                            message = e.message ?: "Erro ao executar o pipeline de Basquetebol.",
                            canRestoreCached = lastBasketballFetchResult != null
                        )
                    )
                }
            }
        }
    }

    private fun analyzeTennisPipeline() {
        if (_uiState.value.tennisState is TennisUiState.Analyzing) return

        viewModelScope.launch {
            try {
                val selectedDates = _uiState.value.selectedDatesIso
                val cycleInfo = QuantEngine.buildCycleInfoForDates(selectedDates)
                _uiState.update {
                    it.copy(
                        cycleInfo = cycleInfo,
                        savedTennisSlipKeys = emptySet(),
                        tennisState = TennisUiState.Analyzing(
                            stepIndex = 1,
                            stepLabel = "Passo 1/4 • Ténis ATP/WTA: ${cycleInfo.startDateIso} a ${cycleInfo.endDateIso} (${cycleInfo.timezoneLabel})…"
                        )
                    )
                }

                _uiState.update {
                    it.copy(
                        tennisState = TennisUiState.Analyzing(
                            stepIndex = 2,
                            stepLabel = "Passo 2/4 • A consultar encontros oficiais ATP/WTA, rankings e superfícies…"
                        )
                    )
                }
                val fetched = withContext(Dispatchers.IO) {
                    tennisRepository.fetchTennisMatches(
                        cycleInfo = cycleInfo,
                        onStageUpdate = { stageMsg ->
                            _uiState.update { state ->
                                state.copy(
                                    tennisState = TennisUiState.Analyzing(
                                        stepIndex = 2,
                                        stepLabel = stageMsg
                                    )
                                )
                            }
                        }
                    )
                }
                lastTennisFetchResult = fetched

                cacheStore?.let { store ->
                    withContext(Dispatchers.IO) {
                        runCatching { store.saveLatestTennisAnalysis(fetched) }
                    }
                }

                _uiState.update {
                    it.copy(
                        tennisState = TennisUiState.Analyzing(
                            stepIndex = 3,
                            stepLabel = "Passo 3/4 • A calcular Markov de serviço, sets exatos e total de jogos para ${fetched.matches.size} encontros…"
                        )
                    )
                }

                _uiState.update {
                    it.copy(
                        tennisState = TennisUiState.Analyzing(
                            stepIndex = 4,
                            stepLabel = "Passo 4/4 • A simular 100.000 cenários set-a-set em Monte Carlo…"
                        )
                    )
                }
                val stateSnapshot = _uiState.value
                val bundle = withContext(Dispatchers.Default) {
                    TennisQuantEngine.buildAnalysisBundle(
                        fetchResult = fetched,
                        stakeMode = stateSnapshot.stakeMode,
                        totalBudget = stateSnapshot.budgetAmount
                    )
                }

                _uiState.update {
                    it.copy(
                        cycleInfo = fetched.cycleInfo,
                        selectedTennisMatchIndex = 0,
                        tennisState = TennisUiState.Success(bundle)
                    )
                }
            } catch (e: TimeoutCancellationException) {
                Log.e(TAG, "Tennis pipeline timed out", e)
                _uiState.update {
                    it.copy(
                        tennisState = TennisUiState.Error(
                            message = "Tempo limite excedido ao obter dados de Ténis. Tenta novamente.",
                            canRestoreCached = lastTennisFetchResult != null
                        )
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Tennis pipeline failed", e)
                _uiState.update {
                    it.copy(
                        tennisState = TennisUiState.Error(
                            message = e.message ?: "Erro ao executar o pipeline de Ténis.",
                            canRestoreCached = lastTennisFetchResult != null
                        )
                    )
                }
            }
        }
    }

    private fun recalculateLocallyIfReady() {
        val snapshot = _uiState.value
        val fetchedFootball = lastFetchResult
        val fetchedBasket = lastBasketballFetchResult
        val fetchedTennis = lastTennisFetchResult

        viewModelScope.launch {
            if (fetchedFootball != null) {
                val recomputed = withContext(Dispatchers.Default) {
                    QuantEngine.buildAnalysisBundle(fetchedFootball, snapshot.stakeMode, snapshot.budgetAmount)
                }
                _uiState.update { it.copy(analysisState = AnalysisUiState.Success(recomputed)) }
            }
            if (fetchedBasket != null) {
                val recomputedBk = withContext(Dispatchers.Default) {
                    BasketballQuantEngine.buildAnalysisBundle(fetchedBasket, snapshot.stakeMode, snapshot.budgetAmount)
                }
                _uiState.update { it.copy(basketballState = BasketballUiState.Success(recomputedBk)) }
            }
            if (fetchedTennis != null) {
                val recomputedTn = withContext(Dispatchers.Default) {
                    TennisQuantEngine.buildAnalysisBundle(fetchedTennis, snapshot.stakeMode, snapshot.budgetAmount)
                }
                _uiState.update { it.copy(tennisState = TennisUiState.Success(recomputedTn)) }
            }
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
        val updatedFetch = current.copy(matches = updatedMatches)
        lastFetchResult = updatedFetch
        _uiState.update { it.copy(savedSlipKeys = emptySet()) }

        cacheStore?.let { store ->
            viewModelScope.launch(Dispatchers.IO) {
                runCatching { store.saveLatestAnalysis(updatedFetch) }
            }
        }

        recalculateLocallyIfReady()
        _snackbarMessages.tryEmit("Odds Betclic.pt guardadas • Boletins e Monte Carlo recalculados.")
    }

    fun updateBasketballLinesAndOdds(
        matchId: String,
        totalLine: Double,
        homeSpreadLine: Double,
        q1TotalLine: Double,
        oddHomeMl: Double,
        oddAwayMl: Double,
        oddHomeSpread: Double,
        oddAwaySpread: Double,
        oddTotalOver: Double,
        oddTotalUnder: Double,
        oddQ1Over: Double,
        oddQ1Under: Double
    ) {
        val current = lastBasketballFetchResult ?: return
        val updatedMatches = current.matches.map { m ->
            if (m.id == matchId) {
                val safeTotal = totalLine.coerceIn(100.0, 290.0)
                val safeQ1 = q1TotalLine.coerceIn(20.0, 85.0)
                val safeCushion = if (m.isNbaFormat) 6.5 else 5.5
                m.copy(
                    totalLine = safeTotal,
                    homeSpreadLine = homeSpreadLine.coerceIn(-40.0, 40.0),
                    q1TotalLine = safeQ1,
                    htTotalLine = BasketballQuantEngine.roundHalf(safeTotal * 0.505),
                    homeTeamTotalLine = BasketballQuantEngine.roundHalf((safeTotal - homeSpreadLine) / 2.0),
                    altSafeTotalLine = BasketballQuantEngine.roundHalf(safeTotal - safeCushion),
                    oddHomeMl = oddHomeMl.coerceAtLeast(1.02),
                    oddAwayMl = oddAwayMl.coerceAtLeast(1.02),
                    oddHomeSpread = oddHomeSpread.coerceAtLeast(1.02),
                    oddAwaySpread = oddAwaySpread.coerceAtLeast(1.02),
                    oddTotalOver = oddTotalOver.coerceAtLeast(1.02),
                    oddTotalUnder = oddTotalUnder.coerceAtLeast(1.02),
                    oddQ1Over = oddQ1Over.coerceAtLeast(1.02),
                    oddQ1Under = oddQ1Under.coerceAtLeast(1.02)
                )
            } else {
                m
            }
        }
        val updatedFetch = current.copy(matches = updatedMatches)
        lastBasketballFetchResult = updatedFetch
        _uiState.update { it.copy(savedBasketballSlipKeys = emptySet()) }

        cacheStore?.let { store ->
            viewModelScope.launch(Dispatchers.IO) {
                runCatching { store.saveLatestBasketballAnalysis(updatedFetch) }
            }
        }

        recalculateLocallyIfReady()
        _snackbarMessages.tryEmit("Linhas e odds Betclic Basquetebol guardadas e recalculadas.")
    }

    fun updateTennisLinesAndOdds(
        matchId: String,
        totalGamesLine: Double,
        p1GameHcLine: Double,
        oddP1Ml: Double,
        oddP2Ml: Double,
        oddP1Set1: Double,
        oddP2Set1: Double,
        oddGamesOver: Double,
        oddGamesUnder: Double,
        oddP1Hc: Double,
        oddP2Hc: Double,
        oddStraight: Double,
        oddOverSets: Double,
        oddTiebreak: Double
    ) {
        val current = lastTennisFetchResult ?: return
        val updatedMatches = current.matches.map { m ->
            if (m.id == matchId) {
                m.copy(
                    totalGamesLine = totalGamesLine.coerceIn(14.5, 52.5),
                    p1GameHandicapLine = p1GameHcLine.coerceIn(-14.5, 14.5),
                    oddP1Ml = oddP1Ml.coerceAtLeast(1.02),
                    oddP2Ml = oddP2Ml.coerceAtLeast(1.02),
                    oddP1Set1 = oddP1Set1.coerceAtLeast(1.02),
                    oddP2Set1 = oddP2Set1.coerceAtLeast(1.02),
                    oddGamesOver = oddGamesOver.coerceAtLeast(1.02),
                    oddGamesUnder = oddGamesUnder.coerceAtLeast(1.02),
                    oddP1GameHc = oddP1Hc.coerceAtLeast(1.02),
                    oddP2GameHc = oddP2Hc.coerceAtLeast(1.02),
                    oddP1StraightSets = oddStraight.coerceAtLeast(1.02),
                    oddOverSets = oddOverSets.coerceAtLeast(1.02),
                    oddTiebreakYes = oddTiebreak.coerceAtLeast(1.02)
                )
            } else {
                m
            }
        }
        val updatedFetch = current.copy(matches = updatedMatches)
        lastTennisFetchResult = updatedFetch
        _uiState.update { it.copy(savedTennisSlipKeys = emptySet()) }

        cacheStore?.let { store ->
            viewModelScope.launch(Dispatchers.IO) {
                runCatching { store.saveLatestTennisAnalysis(updatedFetch) }
            }
        }

        recalculateLocallyIfReady()
        _snackbarMessages.tryEmit("Linhas e odds Betclic Ténis guardadas e recalculadas.")
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
            _snackbarMessages.tryEmit("Os 3 boletins de Futebol foram registados no histórico.")
        }
    }

    fun saveBasketballSlipToHistory(slip: BasketballSlip) {
        val cycleLabel = "BASKET • ${_uiState.value.cycleInfo.startDateIso}"
        val key = "${slip.type.code}_${slip.isFullLeverageVariant}"
        viewModelScope.launch {
            historyRepository.saveBasketballSlip(slip, cycleLabel)
            _uiState.update { it.copy(savedBasketballSlipKeys = it.savedBasketballSlipKeys + key) }
            _snackbarMessages.tryEmit("${slip.title} guardado no histórico Room.")
        }
    }

    fun saveAllActiveBasketballSlips() {
        val success = _uiState.value.basketballState as? BasketballUiState.Success ?: return
        val bundle = success.bundle
        val activeSlip3 = if (_uiState.value.useFullLeverageSlip3) bundle.slip3Full else bundle.slip3Base
        val slips = listOf(bundle.slip1, bundle.slip2, activeSlip3)
        val cycleLabel = "BASKET • ${bundle.cycleInfo.startDateIso}"
        val keys = slips.map { "${it.type.code}_${it.isFullLeverageVariant}" }.toSet()

        viewModelScope.launch {
            historyRepository.saveBasketballSlips(slips, cycleLabel)
            _uiState.update { it.copy(savedBasketballSlipKeys = it.savedBasketballSlipKeys + keys) }
            _snackbarMessages.tryEmit("Os 3 boletins de Basquetebol foram registados no histórico.")
        }
    }

    fun saveTennisSlipToHistory(slip: TennisSlip) {
        val cycleLabel = "TÉNIS • ${_uiState.value.cycleInfo.startDateIso}"
        val key = "${slip.type.code}_${slip.isFullLeverageVariant}"
        viewModelScope.launch {
            historyRepository.saveTennisSlip(slip, cycleLabel)
            _uiState.update { it.copy(savedTennisSlipKeys = it.savedTennisSlipKeys + key) }
            _snackbarMessages.tryEmit("${slip.title} guardado no histórico Room.")
        }
    }

    fun saveAllActiveTennisSlips() {
        val success = _uiState.value.tennisState as? TennisUiState.Success ?: return
        val bundle = success.bundle
        val activeSlip3 = if (_uiState.value.useFullLeverageSlip3) bundle.slip3Full else bundle.slip3Base
        val slips = listOf(bundle.slip1, bundle.slip2, activeSlip3)
        val cycleLabel = "TÉNIS • ${bundle.cycleInfo.startDateIso}"
        val keys = slips.map { "${it.type.code}_${it.isFullLeverageVariant}" }.toSet()

        viewModelScope.launch {
            historyRepository.saveTennisSlips(slips, cycleLabel)
            _uiState.update { it.copy(savedTennisSlipKeys = it.savedTennisSlipKeys + keys) }
            _snackbarMessages.tryEmit("Os 3 boletins de Ténis foram registados no histórico.")
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
        private val basketballRepository: BasketballRepository = BasketballRepository(),
        private val tennisRepository: TennisRepository = TennisRepository(),
        private val historyRepository: BetHistoryRepository,
        private val cacheStore: AnalysisCacheStore? = null
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return BetViewModel(
                geminiRepository = geminiRepository,
                basketballRepository = basketballRepository,
                tennisRepository = tennisRepository,
                historyRepository = historyRepository,
                cacheStore = cacheStore
            ) as T
        }
    }
}
