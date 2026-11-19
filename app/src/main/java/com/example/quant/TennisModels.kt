package com.example.quant

enum class TennisSurface(
    val label: String,
    val atpBaseHold: Double,
    val wtaBaseHold: Double
) {
    HARD("Piso Rápido", 0.795, 0.665),
    CLAY("Terra Batida", 0.745, 0.625),
    GRASS("Relva", 0.830, 0.715),
    INDOOR("Indoor Hard", 0.825, 0.690)
}

enum class TennisMarketType(val code: String, val displayName: String) {
    P1_ML("ML J1", "Vencedor Jogador 1"),
    P2_ML("ML J2", "Vencedor Jogador 2"),
    P1_SET1("Set 1 J1", "1.º Set — Jogador 1"),
    P2_SET1("Set 1 J2", "1.º Set — Jogador 2"),
    GAMES_OVER("Mais Jogos", "Total de Jogos — Mais"),
    GAMES_UNDER("Menos Jogos", "Total de Jogos — Menos"),
    P1_GAME_HANDICAP("HC Jogos J1", "Handicap de Jogos J1"),
    P2_GAME_HANDICAP("HC Jogos J2", "Handicap de Jogos J2"),
    P1_STRAIGHT_SETS("J1 2-0", "Resultado Exato Sets J1"),
    OVER_SETS("Mais Sets", "Total de Sets — Mais"),
    TIEBREAK_YES("Tie-Break", "Haverá Tie-Break (Sim)")
}

data class RawTennisMatchData(
    val id: String,
    val dataHoraWet: String,
    val torneio: String,
    val ronda: String,
    val circuito: String, // "ATP" or "WTA"
    val superficie: TennisSurface,
    val bestOfSets: Int = 3,
    val jogador1: String,
    val jogador2: String,
    val ranking1: Int?,
    val ranking2: Int?,
    val serveHoldProb1: Double,
    val serveHoldProb2: Double,
    val surfaceNotes: String,
    // Editable Betclic.pt lines & odds
    val totalGamesLine: Double,
    val p1GameHandicapLine: Double, // Negative if J1 is favored, e.g. -2.5
    val oddP1Ml: Double,
    val oddP2Ml: Double,
    val oddP1Set1: Double,
    val oddP2Set1: Double,
    val oddGamesOver: Double,
    val oddGamesUnder: Double,
    val oddP1GameHc: Double,
    val oddP2GameHc: Double,
    val oddP1StraightSets: Double,
    val oddOverSets: Double,
    val oddTiebreakYes: Double,
    val fontesVerificadas: List<String> = emptyList()
)

data class SetScoreProbability(
    val scoreLabel: String, // e.g., "2-0", "2-1", "1-2", "0-2"
    val probability: Double,
    val isPlayer1Win: Boolean
)

data class TennisMatchAnalysis(
    val rawMatch: RawTennisMatchData,
    val probP1Win: Double,
    val probP2Win: Double,
    val probP1Set: Double,
    val probP2Set: Double,
    val expectedTotalGames: Double,
    val expectedP1GameMargin: Double,
    val expectedGamesPerSet: Double,
    val probTiebreakInMatch: Double,
    val probOverSets: Double,
    val setScoreDistribution: List<SetScoreProbability>,
    val markets: List<TennisMarketEvaluation>,
    val bestValueMarket: TennisMarketEvaluation?
)

data class TennisMarketEvaluation(
    val matchId: String,
    val matchLabel: String,
    val competition: String,
    val kickoffWet: String,
    val market: TennisMarketType,
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

data class TennisFetchResult(
    val cycleInfo: TemporalCycleInfo,
    val matches: List<RawTennisMatchData>,
    val sources: List<GroundingSource>,
    val searchQueries: List<String>,
    val modelSummaryNote: String
)

data class TennisSlip(
    val type: BetSlipType,
    val title: String,
    val subtitle: String,
    val selections: List<TennisMarketEvaluation>,
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

data class TennisAnalysisBundle(
    val cycleInfo: TemporalCycleInfo,
    val stakeMode: StakeMode,
    val totalBudget: Double,
    val matches: List<TennisMatchAnalysis>,
    val slip1: TennisSlip,
    val slip2: TennisSlip,
    val slip3Base: TennisSlip,
    val slip3Full: TennisSlip,
    val monteCarloBase: MonteCarloReport,
    val monteCarloFull: MonteCarloReport,
    val groundingSources: List<GroundingSource>,
    val searchQueries: List<String>,
    val modelSummaryNote: String
)
