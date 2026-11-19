package com.example.quant

enum class TemporalCycle(
    val code: String,
    val title: String,
    val daySpanLabel: String,
    val targetCompetitions: String
) {
    CYCLE_A_MIDWEEK(
        code = "CICLO A",
        title = "Ciclo A • Meio da Semana",
        daySpanLabel = "Segunda a Quinta",
        targetCompetitions = "UEFA Champions League, Europa League, Conference League, Taças Nacionais e jornadas intercalares oficiais"
    ),
    CYCLE_B_WEEKEND(
        code = "CICLO B",
        title = "Ciclo B • Fim de Semana",
        daySpanLabel = "Sexta a Domingo",
        targetCompetitions = "Liga Portugal Betclic, Premier League, LaLiga, Bundesliga, Serie A e Ligue 1"
    )
}

data class TemporalCycleInfo(
    val cycle: TemporalCycle,
    val currentTimeWet: String,
    val timezoneLabel: String,
    val startDateIso: String,
    val endDateIso: String,
    val exactDatesIso: List<String>
)

enum class CyclePreset(val label: String) {
    WEEKEND_FRI_SUN("Sex–Dom"),
    MIDWEEK_MON_THU("Seg–Qui"),
    TODAY("Hoje"),
    CUSTOM("Personalizado")
}

data class SelectableDay(
    val isoDate: String,
    val shortWeekday: String,
    val dayMonth: String,
    val isWeekend: Boolean
)

enum class StakeMode(val label: String) {
    FREEBET("Modo Freebet"),
    REAL_BANKROLL("Banca Real")
}

enum class MarketType(val code: String, val displayName: String) {
    HOME_WIN("1", "Vitória Casa (1)"),
    DRAW("X", "Empate (X)"),
    AWAY_WIN("2", "Vitória Fora (2)"),
    OVER_25("Mais 2.5", "Mais de 2.5 Golos"),
    UNDER_35("Menos 3.5", "Menos de 3.5 Golos"),
    BTTS("BTTS", "Ambas Marcam (Sim)")
}

data class RawMatchData(
    val id: String,
    val dataHoraWet: String,
    val competicao: String,
    val equipaCasa: String,
    val equipaFora: String,
    val xgCasaEstimado: Double,
    val xgForaEstimado: Double,
    val lesoesChave: String,
    val eloCasaEstimado: Double = 1650.0,
    val eloForaEstimado: Double = 1620.0,
    val odd1: Double,
    val oddX: Double,
    val odd2: Double,
    val oddOver25: Double,
    val oddUnder35: Double,
    val oddBtts: Double,
    val fontesVerificadas: List<String> = emptyList()
)

data class MarketEvaluation(
    val matchId: String,
    val matchLabel: String,
    val competition: String,
    val kickoffWet: String,
    val market: MarketType,
    val odd: Double,
    val trueProbability: Double,
    val impliedProbability: Double,
    val edge: Double,
    val evPerEuroReal: Double,
    val evPerEuroFreebet: Double,
    val quarterKellyFraction: Double
) {
    val isPositiveEdge: Boolean get() = edge > 0.0
}

data class ScorelineProb(
    val homeGoals: Int,
    val awayGoals: Int,
    val probability: Double
)

data class MatchQuantAnalysis(
    val rawMatch: RawMatchData,
    val adjustedLambdaHome: Double,
    val adjustedLambdaAway: Double,
    val eloDiffWithHomeAdvantage: Double,
    val eloExpectedHomeScore: Double,
    val scoreMatrix12x12: List<List<Double>>,
    val prob1: Double,
    val probX: Double,
    val prob2: Double,
    val probOver25: Double,
    val probUnder35: Double,
    val probBtts: Double,
    val markets: List<MarketEvaluation>,
    val bestValueMarket: MarketEvaluation?,
    val topScorelines: List<ScorelineProb>
)

enum class BetSlipType(val code: String, val badge: String) {
    VALUE_EV("VALUE_EV", "BOLETIM 1 • +EV PURO"),
    SOLID_BASE("SOLID_BASE", "BOLETIM 2 • BASE SÓLIDA"),
    ALL_IN_LEVERAGE("ALL_IN_LEVERAGE", "BOLETIM 3 • ALAVANCAGEM")
}

data class GeneratedSlip(
    val type: BetSlipType,
    val title: String,
    val subtitle: String,
    val selections: List<MarketEvaluation>,
    val combinedOdd: Double,
    val combinedTrueProb: Double,
    val stake: Double,
    val stakeMode: StakeMode,
    val potentialGrossReturn: Double,
    val potentialNetProfit: Double,
    val expectedValue: Double,
    val edgePercent: Double,
    val isFullLeverageVariant: Boolean = false
)

data class MonteCarloReport(
    val iterations: Int,
    val probAtLeastOneWin: Double,
    val probSlip1Win: Double,
    val probSlip2Win: Double,
    val probSlip3Win: Double,
    val probAllThreeWin: Double,
    val expectedNetReturn: Double,
    val expectedRoiPercent: Double,
    val percentile95Return: Double,
    val bankrollProtectedAmount: Double,
    val totalDeployedStake: Double,
    val histogramLabels: List<String>,
    val histogramFrequencies: List<Float>
)

data class GroundingSource(
    val title: String,
    val uri: String
)

data class GroundedFetchResult(
    val cycleInfo: TemporalCycleInfo,
    val matches: List<RawMatchData>,
    val sources: List<GroundingSource>,
    val searchQueries: List<String>,
    val modelSummaryNote: String
)

data class AnalysisBundle(
    val cycleInfo: TemporalCycleInfo,
    val stakeMode: StakeMode,
    val totalBudget: Double,
    val matches: List<MatchQuantAnalysis>,
    val slip1: GeneratedSlip,
    val slip2: GeneratedSlip,
    val slip3Base: GeneratedSlip,
    val slip3Full: GeneratedSlip,
    val monteCarloBase: MonteCarloReport,
    val monteCarloFull: MonteCarloReport,
    val groundingSources: List<GroundingSource>,
    val searchQueries: List<String>,
    val modelSummaryNote: String
)

enum class BetStatus(val dbValue: String, val label: String) {
    PENDING("PENDING", "Pendente"),
    WON("WON", "Ganho"),
    LOST("LOST", "Perdido");

    companion object {
        fun fromDb(value: String): BetStatus = entries.firstOrNull { it.dbValue == value } ?: PENDING
    }
}

data class RoiSummary(
    val totalSlips: Int,
    val settledCount: Int,
    val wonCount: Int,
    val lostCount: Int,
    val pendingCount: Int,
    val winRatePercent: Double,
    val totalStakedReal: Double,
    val totalNetProfit: Double,
    val roiPercent: Double,
    val freebetNetProfit: Double,
    val equityCurvePoints: List<Double>
)
