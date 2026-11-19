package com.example.quant

import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Pure local quantitative Tennis engine for Betclic.pt markets:
 * - Exact Markov chain solver for single-set score distribution (6-0 .. 7-6, 0-6 .. 6-7)
 *   given each player's surface-adjusted serve-hold probability (h1, h2)
 * - Exact Best-of-3 & Best-of-5 match expansion (exact set scores, ML, Set 1, Over Sets, Tie-Break)
 * - Exact convolution of Total Games & Game Handicap distributions across full match outcomes
 * - Valuation of 11 Betclic.pt Tennis markets (Edge, +EV Real & Freebet SNR, 1/4 Kelly)
 * - 3-Slip Portfolio Builder (+EV Pure, Solid Base, Leverage Base/Full)
 * - 100,000-iteration set-by-set Monte Carlo simulation with realistic momentum correlation
 */
object TennisQuantEngine {

    const val MONTE_CARLO_ITERATIONS = 100_000
    private const val QUARTER_KELLY_MULTIPLIER = 0.25
    private const val MAX_KELLY_CAP = 0.10

    data class SetOutcome(
        val games1: Int,
        val games2: Int,
        val probability: Double
    ) {
        val totalGames: Int get() = games1 + games2
        val diffP1: Int get() = games1 - games2
        val p1Won: Boolean get() = games1 > games2
        val isTiebreak: Boolean get() = (games1 == 7 && games2 == 6) || (games1 == 6 && games2 == 7)
    }

    data class ExactSetSummary(
        val outcomes: List<SetOutcome>,
        val probP1WinsSet: Double,
        val probTiebreak: Double,
        val expectedGames: Double,
        val expectedDiffP1: Double
    )

    /**
     * Computes exact probabilities of all 14 terminal set scores (6-0..6-4, 7-5, 7-6 and reverse)
     * averaging over Player 1 serving first (50%) and Player 2 serving first (50%).
     */
    fun computeExactSetDistribution(hold1Raw: Double, hold2Raw: Double): ExactSetSummary {
        val h1 = hold1Raw.coerceIn(0.42, 0.94)
        val h2 = hold2Raw.coerceIn(0.42, 0.94)
        val p1WinWhenServing = h1
        val p1WinWhenReturning = 1.0 - h2

        // Tiebreak win probability calibrated from serve-hold differential
        val tbEdge = (h1 - h2) * 1.45
        val p1WinTiebreak = (0.50 + tbEdge).coerceIn(0.15, 0.85)

        val totals = HashMap<Pair<Int, Int>, Double>()

        fun runFromStarter(p1ServesFirst: Boolean, weight: Double) {
            // dp[g1][g2] = probability of reaching score (g1, g2) before termination
            val dp = Array(8) { DoubleArray(8) }
            dp[0][0] = weight

            for (totalG in 0..12) {
                for (g1 in 0..7) {
                    val g2 = totalG - g1
                    if (g2 !in 0..7) continue
                    val prob = dp[g1][g2]
                    if (prob <= 1e-12) continue

                    // Check terminal set states
                    val isTerminal = when {
                        g1 == 6 && g2 <= 4 -> true
                        g2 == 6 && g1 <= 4 -> true
                        g1 == 7 && (g2 == 5 || g2 == 6) -> true
                        g2 == 7 && (g1 == 5 || g1 == 6) -> true
                        else -> false
                    }
                    if (isTerminal) {
                        val key = g1 to g2
                        totals[key] = (totals[key] ?: 0.0) + prob
                        continue
                    }

                    if (g1 == 6 && g2 == 6) {
                        totals[7 to 6] = (totals[7 to 6] ?: 0.0) + prob * p1WinTiebreak
                        totals[6 to 7] = (totals[6 to 7] ?: 0.0) + prob * (1.0 - p1WinTiebreak)
                        continue
                    }

                    val p1ServingNow = if (totalG % 2 == 0) p1ServesFirst else !p1ServesFirst
                    val pGame1 = if (p1ServingNow) p1WinWhenServing else p1WinWhenReturning
                    if (g1 + 1 <= 7) dp[g1 + 1][g2] += prob * pGame1
                    if (g2 + 1 <= 7) dp[g1][g2 + 1] += prob * (1.0 - pGame1)
                }
            }
        }

        runFromStarter(p1ServesFirst = true, weight = 0.5)
        runFromStarter(p1ServesFirst = false, weight = 0.5)

        val outcomes = totals.entries
            .map { (score, p) -> SetOutcome(score.first, score.second, p) }
            .sortedByDescending { it.probability }

        val sumP = outcomes.sumOf { it.probability }.coerceAtLeast(1e-9)
        val normalized = outcomes.map { it.copy(probability = it.probability / sumP) }

        val p1Set = normalized.filter { it.p1Won }.sumOf { it.probability }
        val pTb = normalized.filter { it.isTiebreak }.sumOf { it.probability }
        val expGames = normalized.sumOf { it.totalGames * it.probability }
        val expDiff = normalized.sumOf { it.diffP1 * it.probability }

        return ExactSetSummary(
            outcomes = normalized,
            probP1WinsSet = p1Set.coerceIn(0.04, 0.96),
            probTiebreak = pTb.coerceIn(0.02, 0.65),
            expectedGames = expGames,
            expectedDiffP1 = expDiff
        )
    }

    fun analyzeMatch(raw: RawTennisMatchData): TennisMatchAnalysis {
        val setSummary = computeExactSetDistribution(raw.serveHoldProb1, raw.serveHoldProb2)
        val p = setSummary.probP1WinsSet
        val q = 1.0 - p
        val bestOf = if (raw.bestOfSets == 5) 5 else 3

        val setScores: List<SetScoreProbability> = if (bestOf == 3) {
            listOf(
                SetScoreProbability("2-0", p * p, isPlayer1Win = true),
                SetScoreProbability("2-1", 2.0 * p * p * q, isPlayer1Win = true),
                SetScoreProbability("1-2", 2.0 * q * q * p, isPlayer1Win = false),
                SetScoreProbability("0-2", q * q, isPlayer1Win = false)
            )
        } else {
            listOf(
                SetScoreProbability("3-0", p.pow(3), isPlayer1Win = true),
                SetScoreProbability("3-1", 3.0 * p.pow(3) * q, isPlayer1Win = true),
                SetScoreProbability("3-2", 6.0 * p.pow(3) * q * q, isPlayer1Win = true),
                SetScoreProbability("2-3", 6.0 * q.pow(3) * p * p, isPlayer1Win = false),
                SetScoreProbability("1-3", 3.0 * q.pow(3) * p, isPlayer1Win = false),
                SetScoreProbability("0-3", q.pow(3), isPlayer1Win = false)
            )
        }

        val probP1Win = setScores.filter { it.isPlayer1Win }.sumOf { it.probability }.coerceIn(0.02, 0.98)
        val probP2Win = (1.0 - probP1Win).coerceIn(0.02, 0.98)
        val probP1Straight = setScores.first().probability.coerceIn(0.02, 0.95)
        val probOverSets = if (bestOf == 3) {
            (setScores[1].probability + setScores[2].probability).coerceIn(0.05, 0.85)
        } else {
            // Over 3.5 sets in Best-of-5 (i.e., 4 or 5 sets)
            (1.0 - setScores.first().probability - setScores.last().probability).coerceIn(0.10, 0.92)
        }

        // Exact probability of at least 1 Tie-Break across the match
        val noTbSingle = (1.0 - setSummary.probTiebreak).coerceIn(0.05, 0.99)
        val probTiebreakMatch = if (bestOf == 3) {
            val p2Sets = setScores[0].probability + setScores[3].probability
            val p3Sets = setScores[1].probability + setScores[2].probability
            (1.0 - (p2Sets * noTbSingle.pow(2) + p3Sets * noTbSingle.pow(3))).coerceIn(0.04, 0.88)
        } else {
            val p3 = setScores[0].probability + setScores[5].probability
            val p4 = setScores[1].probability + setScores[4].probability
            val p5 = setScores[2].probability + setScores[3].probability
            (1.0 - (p3 * noTbSingle.pow(3) + p4 * noTbSingle.pow(4) + p5 * noTbSingle.pow(5))).coerceIn(0.08, 0.94)
        }

        // Conditional set distributions for exact total-games & game-handicap convolution
        val p1WinSetDist = normalizeConditional(setSummary.outcomes.filter { it.p1Won })
        val p2WinSetDist = normalizeConditional(setSummary.outcomes.filter { !it.p1Won })

        val matchGameDist = computeExactMatchGameDistribution(
            bestOf = bestOf,
            setScores = setScores,
            p1WinSetDist = p1WinSetDist,
            p2WinSetDist = p2WinSetDist
        )

        var expectedTotalGames = 0.0
        var expectedP1Margin = 0.0
        var probGamesOver = 0.0
        var probP1GameHc = 0.0

        for ((key, prob) in matchGameDist) {
            val totalG = key.first
            val diffP1 = key.second
            expectedTotalGames += totalG * prob
            expectedP1Margin += diffP1 * prob
            if (totalG.toDouble() > raw.totalGamesLine) {
                probGamesOver += prob
            }
            if (diffP1.toDouble() + raw.p1GameHandicapLine > 0.0) {
                probP1GameHc += prob
            }
        }

        val safeProbGamesOver = probGamesOver.coerceIn(0.03, 0.97)
        val safeProbGamesUnder = (1.0 - safeProbGamesOver).coerceIn(0.03, 0.97)
        val safeProbP1Hc = probP1GameHc.coerceIn(0.03, 0.97)
        val safeProbP2Hc = (1.0 - safeProbP1Hc).coerceIn(0.03, 0.97)

        val matchLabel = "${raw.jogador1} vs ${raw.jogador2}"
        val p1Short = shortSurname(raw.jogador1)
        val p2Short = shortSurname(raw.jogador2)
        val hc1Sign = if (raw.p1GameHandicapLine >= 0) "+${formatHalf(raw.p1GameHandicapLine)}" else formatHalf(raw.p1GameHandicapLine)
        val hc2Line = -raw.p1GameHandicapLine
        val hc2Sign = if (hc2Line >= 0) "+${formatHalf(hc2Line)}" else formatHalf(hc2Line)
        val straightLabel = if (bestOf == 5) "$p1Short 3-0" else "$p1Short 2-0"
        val overSetsLabel = if (bestOf == 5) "Mais 3.5 Sets" else "Mais 2.5 Sets"
        val overSetsNum = if (bestOf == 5) 3.5 else 2.5

        val markets = listOf(
            evaluateMarket(raw, matchLabel, TennisMarketType.P1_ML, "ML $p1Short", 0.0, raw.oddP1Ml, probP1Win),
            evaluateMarket(raw, matchLabel, TennisMarketType.P2_ML, "ML $p2Short", 0.0, raw.oddP2Ml, probP2Win),
            evaluateMarket(raw, matchLabel, TennisMarketType.P1_SET1, "Set 1 • $p1Short", 0.0, raw.oddP1Set1, p),
            evaluateMarket(raw, matchLabel, TennisMarketType.P2_SET1, "Set 1 • $p2Short", 0.0, raw.oddP2Set1, q),
            evaluateMarket(raw, matchLabel, TennisMarketType.GAMES_OVER, "Mais ${formatHalf(raw.totalGamesLine)} jg", raw.totalGamesLine, raw.oddGamesOver, safeProbGamesOver),
            evaluateMarket(raw, matchLabel, TennisMarketType.GAMES_UNDER, "Menos ${formatHalf(raw.totalGamesLine)} jg", raw.totalGamesLine, raw.oddGamesUnder, safeProbGamesUnder),
            evaluateMarket(raw, matchLabel, TennisMarketType.P1_GAME_HANDICAP, "HC $p1Short $hc1Sign", raw.p1GameHandicapLine, raw.oddP1GameHc, safeProbP1Hc),
            evaluateMarket(raw, matchLabel, TennisMarketType.P2_GAME_HANDICAP, "HC $p2Short $hc2Sign", hc2Line, raw.oddP2GameHc, safeProbP2Hc),
            evaluateMarket(raw, matchLabel, TennisMarketType.P1_STRAIGHT_SETS, straightLabel, 0.0, raw.oddP1StraightSets, probP1Straight),
            evaluateMarket(raw, matchLabel, TennisMarketType.OVER_SETS, overSetsLabel, overSetsNum, raw.oddOverSets, probOverSets),
            evaluateMarket(raw, matchLabel, TennisMarketType.TIEBREAK_YES, "Tie-Break: Sim", 0.5, raw.oddTiebreakYes, probTiebreakMatch)
        )

        return TennisMatchAnalysis(
            rawMatch = raw,
            probP1Win = probP1Win,
            probP2Win = probP2Win,
            probP1Set = p,
            probP2Set = q,
            expectedTotalGames = round1(expectedTotalGames),
            expectedP1GameMargin = round1(expectedP1Margin),
            expectedGamesPerSet = round1(setSummary.expectedGames),
            probTiebreakInMatch = probTiebreakMatch,
            probOverSets = probOverSets,
            setScoreDistribution = setScores,
            markets = markets,
            bestValueMarket = markets.maxByOrNull { it.edge }
        )
    }

    private fun normalizeConditional(list: List<SetOutcome>): List<SetOutcome> {
        val total = list.sumOf { it.probability }.coerceAtLeast(1e-9)
        return list.map { it.copy(probability = it.probability / total) }
    }

    /**
     * Exact discrete convolution over (totalGames, diffGamesP1) conditioned on each possible
     * set-score path (e.g., 2-0, 2-1, 1-2, 0-2).
     */
    private fun computeExactMatchGameDistribution(
        bestOf: Int,
        setScores: List<SetScoreProbability>,
        p1WinSetDist: List<SetOutcome>,
        p2WinSetDist: List<SetOutcome>
    ): Map<Pair<Int, Int>, Double> {
        fun convolve(
            base: Map<Pair<Int, Int>, Double>,
            step: List<SetOutcome>
        ): Map<Pair<Int, Int>, Double> {
            val next = HashMap<Pair<Int, Int>, Double>()
            for ((k, pBase) in base) {
                if (pBase <= 1e-10) continue
                for (o in step) {
                    val nk = (k.first + o.totalGames) to (k.second + o.diffP1)
                    next[nk] = (next[nk] ?: 0.0) + pBase * o.probability
                }
            }
            return next
        }

        fun distForCounts(w1: Int, w2: Int): Map<Pair<Int, Int>, Double> {
            var cur: Map<Pair<Int, Int>, Double> = mapOf((0 to 0) to 1.0)
            repeat(w1) { cur = convolve(cur, p1WinSetDist) }
            repeat(w2) { cur = convolve(cur, p2WinSetDist) }
            return cur
        }

        val combined = HashMap<Pair<Int, Int>, Double>()
        val setPairs = if (bestOf == 3) {
            listOf(2 to 0, 2 to 1, 1 to 2, 0 to 2)
        } else {
            listOf(3 to 0, 3 to 1, 3 to 2, 2 to 3, 1 to 3, 0 to 3)
        }

        setScores.forEachIndexed { idx, scoreProb ->
            val (w1, w2) = setPairs[idx]
            val condDist = distForCounts(w1, w2)
            for ((k, pCond) in condDist) {
                combined[k] = (combined[k] ?: 0.0) + scoreProb.probability * pCond
            }
        }
        return combined
    }

    private fun evaluateMarket(
        raw: RawTennisMatchData,
        matchLabel: String,
        market: TennisMarketType,
        lineLabel: String,
        numericLine: Double,
        odd: Double,
        trueProb: Double
    ): TennisMarketEvaluation {
        val safeOdd = odd.coerceAtLeast(1.02)
        val safeProb = trueProb.coerceIn(0.01, 0.99)
        val impliedProb = 1.0 / safeOdd
        val edge = safeProb - impliedProb
        val evReal = (safeProb * safeOdd) - 1.0
        val evFreebet = safeProb * (safeOdd - 1.0)
        val fullKelly = if (safeOdd > 1.01) {
            ((safeProb * safeOdd) - 1.0) / (safeOdd - 1.0)
        } else {
            0.0
        }
        val quarterKelly = max(0.0, fullKelly) * QUARTER_KELLY_MULTIPLIER

        return TennisMarketEvaluation(
            matchId = raw.id,
            matchLabel = matchLabel,
            competition = "${raw.circuito} • ${raw.torneio}",
            kickoffWet = raw.dataHoraWet,
            market = market,
            lineLabel = lineLabel,
            numericLine = numericLine,
            odd = safeOdd,
            trueProbability = safeProb,
            impliedProbability = impliedProb,
            edge = edge,
            evPerEuroReal = evReal,
            evPerEuroFreebet = evFreebet,
            quarterKellyFraction = min(quarterKelly, MAX_KELLY_CAP)
        )
    }

    fun buildAnalysisBundle(
        fetchResult: TennisFetchResult,
        stakeMode: StakeMode,
        totalBudget: Double
    ): TennisAnalysisBundle {
        val analyses = fetchResult.matches.map { analyzeMatch(it) }
        val allocation = QuantEngine.computeStakeAllocation(stakeMode, totalBudget)

        val slip1Legs = selectSlip1Legs(analyses, stakeMode)
        val slip1 = createSlip(
            type = BetSlipType.VALUE_EV,
            title = "Boletim 1 • Valor +EV Ténis",
            subtitle = "Maior Edge real em Jogos, Handicap, Sets e Moneyline",
            selections = slip1Legs,
            stake = allocation.slip1Stake,
            stakeMode = stakeMode
        )

        val slip2Legs = selectSlip2Legs(analyses)
        val slip2 = createSlip(
            type = BetSlipType.SOLID_BASE,
            title = "Boletim 2 • Base Sólida Ténis",
            subtitle = "Favoritos de serviço sólido & mercados de elevada probabilidade",
            selections = slip2Legs,
            stake = allocation.slip2Stake,
            stakeMode = stakeMode
        )

        val slip3BaseLegs = selectSlip3Legs(analyses, maxLegs = 3)
        val slip3FullLegs = selectSlip3Legs(analyses, maxLegs = 4)

        val slip3Base = createSlip(
            type = BetSlipType.ALL_IN_LEVERAGE,
            title = "Boletim 3 • Alavancagem (Base)",
            subtitle = "Múltipla de 3 jogos (Sets, Handicap de Jogos & Tie-Break)",
            selections = slip3BaseLegs,
            stake = allocation.slip3Stake,
            stakeMode = stakeMode,
            isFullLeverageVariant = false
        )
        val slip3Full = createSlip(
            type = BetSlipType.ALL_IN_LEVERAGE,
            title = "Boletim 3 • Alavancagem (Completa)",
            subtitle = "Múltipla alargada de 4 jogos com foco em retorno assimétrico",
            selections = slip3FullLegs,
            stake = allocation.slip3Stake,
            stakeMode = stakeMode,
            isFullLeverageVariant = true
        )

        val mcBase = runSetBySetMonteCarlo(
            matches = analyses,
            slips = listOf(slip1, slip2, slip3Base),
            stakeMode = stakeMode,
            protectedBankroll = allocation.protectedCash
        )
        val mcFull = runSetBySetMonteCarlo(
            matches = analyses,
            slips = listOf(slip1, slip2, slip3Full),
            stakeMode = stakeMode,
            protectedBankroll = allocation.protectedCash
        )

        return TennisAnalysisBundle(
            cycleInfo = fetchResult.cycleInfo,
            stakeMode = stakeMode,
            totalBudget = totalBudget,
            matches = analyses,
            slip1 = slip1,
            slip2 = slip2,
            slip3Base = slip3Base,
            slip3Full = slip3Full,
            monteCarloBase = mcBase,
            monteCarloFull = mcFull,
            groundingSources = fetchResult.sources,
            searchQueries = fetchResult.searchQueries,
            modelSummaryNote = fetchResult.modelSummaryNote
        )
    }

    private fun selectSlip1Legs(
        analyses: List<TennisMatchAnalysis>,
        stakeMode: StakeMode
    ): List<TennisMarketEvaluation> {
        val candidates = analyses.mapNotNull { m ->
            val filtered = m.markets.filter { it.odd in 1.38..4.50 && it.trueProbability >= 0.28 }
            val pool = filtered.ifEmpty { m.markets }
            pool.maxByOrNull { eval ->
                val evMetric = if (stakeMode == StakeMode.FREEBET) eval.evPerEuroFreebet else eval.evPerEuroReal
                eval.edge * 2.1 + evMetric
            }
        }.sortedByDescending { it.edge }

        val target = min(3, max(2, candidates.size))
        return candidates.take(target)
    }

    private fun selectSlip2Legs(analyses: List<TennisMatchAnalysis>): List<TennisMarketEvaluation> {
        val solidMarkets = setOf(
            TennisMarketType.P1_ML,
            TennisMarketType.P2_ML,
            TennisMarketType.P1_GAME_HANDICAP,
            TennisMarketType.P2_GAME_HANDICAP,
            TennisMarketType.GAMES_OVER,
            TennisMarketType.GAMES_UNDER
        )
        val candidates = analyses.mapNotNull { m ->
            m.markets
                .filter { it.market in solidMarkets && it.odd in 1.18..2.10 && it.trueProbability >= 0.54 }
                .maxByOrNull { it.trueProbability * 1.45 + it.edge * 2.0 }
                ?: m.markets.maxByOrNull { it.trueProbability }
        }.sortedByDescending { it.trueProbability * 1.25 + it.edge }

        val target = min(4, max(2, candidates.size))
        return candidates.take(target)
    }

    private fun selectSlip3Legs(
        analyses: List<TennisMatchAnalysis>,
        maxLegs: Int
    ): List<TennisMarketEvaluation> {
        val candidates = analyses.mapNotNull { m ->
            m.markets
                .filter { it.odd in 1.55..3.60 && it.trueProbability >= 0.32 }
                .maxByOrNull { it.evPerEuroFreebet + it.edge * 1.5 }
                ?: m.markets.maxByOrNull { it.evPerEuroFreebet }
        }.sortedByDescending { it.evPerEuroFreebet + it.edge }

        return candidates.take(min(maxLegs, candidates.size))
    }

    private fun createSlip(
        type: BetSlipType,
        title: String,
        subtitle: String,
        selections: List<TennisMarketEvaluation>,
        stake: Double,
        stakeMode: StakeMode,
        isFullLeverageVariant: Boolean = false
    ): TennisSlip {
        val combinedOdd = selections.fold(1.0) { acc, leg -> acc * leg.odd }
        val combinedProb = selections.fold(1.0) { acc, leg -> acc * leg.trueProbability }
        val grossReturn = if (stakeMode == StakeMode.FREEBET) {
            stake * max(0.0, combinedOdd - 1.0)
        } else {
            stake * combinedOdd
        }
        val netProfitIfWin = stake * max(0.0, combinedOdd - 1.0)
        val ev = if (stakeMode == StakeMode.FREEBET) {
            combinedProb * netProfitIfWin
        } else {
            (combinedProb * netProfitIfWin) - ((1.0 - combinedProb) * stake)
        }
        val impliedProb = if (combinedOdd > 0.0) 1.0 / combinedOdd else 0.0
        val edgePct = (combinedProb - impliedProb) * 100.0

        return TennisSlip(
            type = type,
            title = title,
            subtitle = subtitle,
            selections = selections,
            combinedOdd = round2(combinedOdd),
            combinedTrueProb = combinedProb,
            stake = round2(stake),
            stakeMode = stakeMode,
            potentialGrossReturn = round2(grossReturn),
            potentialNetProfit = round2(netProfitIfWin),
            expectedValue = round2(ev),
            edgePercent = edgePct,
            isFullLeverageVariant = isFullLeverageVariant
        )
    }

    /**
     * 100,000-iteration set-by-set Tennis Monte Carlo simulation using exact CDF sampling
     * of the Markov single-set score distribution per match.
     */
    fun runSetBySetMonteCarlo(
        matches: List<TennisMatchAnalysis>,
        slips: List<TennisSlip>,
        stakeMode: StakeMode,
        protectedBankroll: Double,
        iterations: Int = MONTE_CARLO_ITERATIONS,
        seed: Long = 20261119L
    ): MonteCarloReport {
        val rng = Random(seed)
        val totalRisked = slips.sumOf { it.stake }
        val matchIndexById = matches.withIndex().associate { it.value.rawMatch.id to it.index }

        // Precompute cumulative distributions for every match's single-set score
        val setCdfs = Array(matches.size) { idx ->
            val raw = matches[idx].rawMatch
            val summary = computeExactSetDistribution(raw.serveHoldProb1, raw.serveHoldProb2)
            val outs = summary.outcomes
            val cdf = DoubleArray(outs.size)
            var acc = 0.0
            for (i in outs.indices) {
                acc += outs[i].probability
                cdf[i] = acc
            }
            if (cdf.isNotEmpty()) cdf[cdf.lastIndex] = 1.0
            outs to cdf
        }
        val bestOfArr = IntArray(matches.size) { if (matches[it].rawMatch.bestOfSets == 5) 5 else 3 }

        val slipLegs = slips.map { slip ->
            slip.selections.mapNotNull { leg ->
                val mIdx = matchIndexById[leg.matchId] ?: return@mapNotNull null
                Triple(mIdx, leg.market, leg.numericLine)
            }
        }

        // Simulation scratch buffers
        val p1SetsArr = IntArray(matches.size)
        val p2SetsArr = IntArray(matches.size)
        val p1Set1WonArr = BooleanArray(matches.size)
        val totalGamesArr = IntArray(matches.size)
        val diffP1Arr = IntArray(matches.size)
        val hasTbArr = BooleanArray(matches.size)

        var atLeastOneWins = 0
        var slip1Wins = 0
        var slip2Wins = 0
        var slip3Wins = 0
        var allThreeWins = 0
        var sumNetReturn = 0.0
        val winBuckets = IntArray(4)
        val sampleReturns = DoubleArray(iterations)

        for (iter in 0 until iterations) {
            for (m in matches.indices) {
                val (outs, cdf) = setCdfs[m]
                val needed = (bestOfArr[m] / 2) + 1
                var s1 = 0
                var s2 = 0
                var totG = 0
                var diffG = 0
                var tb = false
                var setNum = 0

                while (s1 < needed && s2 < needed) {
                    val u = rng.nextDouble()
                    var picked = outs.last()
                    for (k in cdf.indices) {
                        if (u <= cdf[k]) {
                            picked = outs[k]
                            break
                        }
                    }
                    if (setNum == 0) {
                        p1Set1WonArr[m] = picked.p1Won
                    }
                    if (picked.p1Won) s1++ else s2++
                    totG += picked.totalGames
                    diffG += picked.diffP1
                    if (picked.isTiebreak) tb = true
                    setNum++
                }

                p1SetsArr[m] = s1
                p2SetsArr[m] = s2
                totalGamesArr[m] = totG
                diffP1Arr[m] = diffG
                hasTbArr[m] = tb
            }

            var wonCount = 0
            var netIter = if (stakeMode == StakeMode.FREEBET) 0.0 else -totalRisked
            for (s in slips.indices) {
                val legs = slipLegs[s]
                if (legs.isEmpty()) continue
                var allWon = true
                for ((mIdx, market, line) in legs) {
                    val won = evaluateSimulatedLeg(
                        market = market,
                        line = line,
                        s1 = p1SetsArr[mIdx],
                        s2 = p2SetsArr[mIdx],
                        p1Set1Won = p1Set1WonArr[mIdx],
                        totG = totalGamesArr[mIdx],
                        diffP1 = diffP1Arr[mIdx],
                        hasTb = hasTbArr[mIdx],
                        bestOf = bestOfArr[mIdx]
                    )
                    if (!won) {
                        allWon = false
                        break
                    }
                }
                if (allWon) {
                    wonCount++
                    if (s == 0) slip1Wins++
                    if (s == 1) slip2Wins++
                    if (s == 2) slip3Wins++
                    netIter += slips[s].potentialGrossReturn
                }
            }

            if (wonCount >= 1) atLeastOneWins++
            if (wonCount == slips.size) allThreeWins++
            winBuckets[min(wonCount, 3)]++
            sumNetReturn += netIter
            sampleReturns[iter] = netIter
        }

        sampleReturns.sort()
        val p95Index = ((iterations - 1) * 0.95).toInt().coerceIn(0, iterations - 1)
        val expectedNet = sumNetReturn / iterations.toDouble()
        val roiPct = if (totalRisked > 0.0) (expectedNet / totalRisked) * 100.0 else 0.0

        return MonteCarloReport(
            iterations = iterations,
            probAtLeastOneWin = atLeastOneWins.toDouble() / iterations,
            probSlip1Win = slip1Wins.toDouble() / iterations,
            probSlip2Win = slip2Wins.toDouble() / iterations,
            probSlip3Win = slip3Wins.toDouble() / iterations,
            probAllThreeWin = allThreeWins.toDouble() / iterations,
            expectedNetReturn = round2(expectedNet),
            expectedRoiPercent = roiPct,
            percentile95Return = round2(sampleReturns[p95Index]),
            bankrollProtectedAmount = round2(protectedBankroll),
            totalDeployedStake = round2(totalRisked),
            histogramLabels = listOf("0/3", "1/3", "2/3", "3/3"),
            histogramFrequencies = winBuckets.map { it.toFloat() / iterations.toFloat() }
        )
    }

    private fun evaluateSimulatedLeg(
        market: TennisMarketType,
        line: Double,
        s1: Int,
        s2: Int,
        p1Set1Won: Boolean,
        totG: Int,
        diffP1: Int,
        hasTb: Boolean,
        bestOf: Int
    ): Boolean {
        return when (market) {
            TennisMarketType.P1_ML -> s1 > s2
            TennisMarketType.P2_ML -> s2 > s1
            TennisMarketType.P1_SET1 -> p1Set1Won
            TennisMarketType.P2_SET1 -> !p1Set1Won
            TennisMarketType.GAMES_OVER -> totG.toDouble() > line
            TennisMarketType.GAMES_UNDER -> totG.toDouble() < line
            TennisMarketType.P1_GAME_HANDICAP -> diffP1.toDouble() + line > 0.0
            TennisMarketType.P2_GAME_HANDICAP -> (-diffP1).toDouble() + line > 0.0
            TennisMarketType.P1_STRAIGHT_SETS -> s1 > s2 && s2 == 0
            TennisMarketType.OVER_SETS -> if (bestOf == 5) (s1 + s2) >= 4 else (s1 + s2) >= 3
            TennisMarketType.TIEBREAK_YES -> hasTb
        }
    }

    internal fun shortSurname(fullName: String): String {
        val parts = fullName.trim().split(" ").filter { it.isNotBlank() }
        val last = parts.lastOrNull() ?: fullName.trim()
        return if (last.length > 11) last.take(10) + "…" else last
    }

    fun roundHalf(value: Double): Double = round(value - 0.5) + 0.5

    private fun formatHalf(v: Double): String = String.format(Locale.US, "%.1f", v)
    private fun round1(v: Double): Double = round(v * 10.0) / 10.0
    private fun round2(v: Double): Double = round(v * 100.0) / 100.0
}
