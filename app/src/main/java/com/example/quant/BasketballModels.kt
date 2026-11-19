package com.example.quant

enum class SportMode(val label: String) {
    FOOTBALL("Futebol"),
    BASKETBALL("Basquetebol")
}

enum class BasketballMarketType(val code: String, val displayName: String) {
    HOME_ML("ML 1", "Vencedor Casa (c/ Prol.)"),
    AWAY_ML("ML 2", "Vencedor Fora (c/ Prol.)"),
    HOME_SPREAD("HC Casa", "Handicap Casa"),
    AWAY_SPREAD("HC Fora", "Handicap Fora"),
    TOTAL_OVER("Mais Pts", "Total Jogo — Mais"),
    TOTAL_UNDER("Menos Pts", "Total Jogo — Menos"),
    Q1_OVER("Q1 Mais", "1.º Quarto — Mais"),
    Q1_UNDER("Q1 Menos", "1.º Quarto — Menos"),
    HT_OVER("1ªP Mais", "1.ª Parte — Mais"),
    HOME_TEAM_OVER("Casa Pts", "Pontos Casa — Mais"),
    ALT_TOTAL_SAFE("Alt. Seguro", "Margem Segura (Alt. Mais)")
}

data class RawBasketballMatchData(
    val id: String,
    val dataHoraWet: String,
    val competicao: String,
    val equipaCasa: String,
    val equipaFora: String,
    val expectedPtsHome: Double,
    val expectedPtsAway: Double,
    val paceNotes: String,
    val homeWinRate: Double,
    val awayWinRate: Double,
    val isNbaFormat: Boolean = true,
    // Betclic.pt editable lines & odds
    val totalLine: Double,
    val homeSpreadLine: Double, // Negative if home is favored, e.g. -5.5
    val q1TotalLine: Double,
    val htTotalLine: Double,
    val homeTeamTotalLine: Double,
    val altSafeTotalLine: Double,
    val oddHomeMl: Double,
    val oddAwayMl: Double,
    val oddHomeSpread: Double,
    val oddAwaySpread: Double,
    val oddTotalOver: Double,
    val oddTotalUnder: Double,
    val oddQ1Over: Double,
    val oddQ1Under: Double,
    val oddHtOver: Double,
    val oddHomeTeamOver: Double,
    val oddAltSafeOver: Double,
    val fontesVerificadas: List<String> = emptyList()
)

data class QuarterProjection(
    val quarterLabel: String,
    val homePoints: Double,
    val awayPoints: Double,
    val totalPoints: Double,
    val marginHome: Double
)

data class BasketballMarketEvaluation(
    val matchId: String,
    val matchLabel: String,
    val competition: String,
    val kickoffWet: String,
    val market: BasketballMarketType,
    val lineLabel: String,
    val numericLine: Double,
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

data class BasketballMatchAnalysis(
    val rawMatch: RawBasketballMatchData,
    val projectedHomeTotal: Double,
    val projectedAwayTotal: Double,
    val projectedCombinedTotal: Double,
    val projectedHomeMargin: Double,
    val quarters: List<QuarterProjection>,
    val firstHalfHome: Double,
    val firstHalfAway: Double,
    val probHomeWin: Double,
    val probAwayWin: Double,
    val marginBuckets: List<Pair<String, Float>>,
    val markets: List<BasketballMarketEvaluation>,
    val bestValueMarket: BasketballMarketEvaluation?
)

data class BasketballFetchResult(
    val cycleInfo: TemporalCycleInfo,
    val matches: List<RawBasketballMatchData>,
    val sources: List<GroundingSource>,
    val searchQueries: List<String>,
    val modelSummaryNote: String
)

data class BasketballSlip(
    val type: BetSlipType,
    val title: String,
    val subtitle: String,
    val selections: List<BasketballMarketEvaluation>,
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

data class BasketballAnalysisBundle(
    val cycleInfo: TemporalCycleInfo,
    val stakeMode: StakeMode,
    val totalBudget: Double,
    val matches: List<BasketballMatchAnalysis>,
    val slip1: BasketballSlip,
    val slip2: BasketballSlip,
    val slip3Base: BasketballSlip,
    val slip3Full: BasketballSlip,
    val monteCarloBase: MonteCarloReport,
    val monteCarloFull: MonteCarloReport,
    val groundingSources: List<GroundingSource>,
    val searchQueries: List<String>,
    val modelSummaryNote: String
)
