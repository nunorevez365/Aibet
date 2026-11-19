package com.example.quant

import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Pure local quantitative basketball engine for Betclic.pt markets:
 * - Quarter-by-quarter scoring projections (Q1, Q2, Q3, Q4 + 1st Half HT)
 * - Gaussian margin & total distribution modeling (NBA 48-min & NCAA/FIBA 40-min variances)
 * - Full valuation of 11 Betclic.pt markets: ML 1/2, Spread (Handicap), Total Over/Under,
 *   Q1 Points Over/Under, 1st Half Over, Home Team Total, and Alternative Safety Total Margin
 * - 3-Slip Portfolio Builder (+EV Pure, Solid Base, Leverage Base/Full)
 * - 100,000-iteration correlated quarter-by-quarter Monte Carlo simulation (including OT!)
 */
object BasketballQuantEngine {

    const val MONTE_CARLO_ITERATIONS = 100_000
    private const val QUARTER_KELLY_MULTIPLIER = 0.25
    private const val MAX_KELLY_CAP = 0.10

    // Empirical scoring share across regulation quarters (Q1..Q4)
    private val QUARTER_WEIGHTS = doubleArrayOf(0.254, 0.253, 0.248, 0.245)

    fun analyzeMatch(raw: RawBasketballMatchData): BasketballMatchAnalysis {
        val projHome = raw.expectedPtsHome.coerceIn(52.0, 145.0)
        val projAway = raw.expectedPtsAway.coerceIn(52.0, 145.0)
        val projTotal = projHome + projAway
        val projMargin = projHome - projAway

        // Empirically calibrated standard deviations (NBA 48-min vs 40-min leagues)
        val sigmaMargin = if (raw.isNbaFormat) 12.2 else 10.8
        val sigmaTotal = if (raw.isNbaFormat) 17.5 else 14.5
        val sigmaQ1 = if (raw.isNbaFormat) 8.9 else 7.6
        val sigmaHt = if (raw.isNbaFormat) 12.6 else 10.6
        val sigmaTeam = if (raw.isNbaFormat) 10.6 else 9.2

        val quarterLabels = listOf("Q1", "Q2", "Q3", "Q4")
        val quarters = quarterLabels.mapIndexed { idx, label ->
            val w = QUARTER_WEIGHTS[idx]
            val qH = round1(projHome * w)
            val qA = round1(projAway * w)
            QuarterProjection(
                quarterLabel = label,
                homePoints = qH,
                awayPoints = qA,
                totalPoints = round1(qH + qA),
                marginHome = round1(qH - qA)
            )
        }
        val firstHalfHome = round1(quarters[0].homePoints + quarters[1].homePoints)
        val firstHalfAway = round1(quarters[0].awayPoints + quarters[1].awayPoints)

        // True probabilities via Normal CDF
        val probHomeWin = normalCdf(projMargin / sigmaMargin).coerceIn(0.03, 0.97)
        val probAwayWin = (1.0 - probHomeWin).coerceIn(0.03, 0.97)

        // Spread: Home covers `homeSpreadLine` (e.g. -5.5 means Home must win by > 5.5, i.e., margin > -homeSpreadLine)
        val probHomeSpread = normalCdf((projMargin + raw.homeSpreadLine) / sigmaMargin).coerceIn(0.03, 0.97)
        val probAwaySpread = (1.0 - probHomeSpread).coerceIn(0.03, 0.97)

        // Full-game Total Over / Under
        val probTotalOver = (1.0 - normalCdf((raw.totalLine - projTotal) / sigmaTotal)).coerceIn(0.03, 0.97)
        val probTotalUnder = (1.0 - probTotalOver).coerceIn(0.03, 0.97)

        // Q1 Total Over / Under
        val projQ1Total = projTotal * QUARTER_WEIGHTS[0]
        val probQ1Over = (1.0 - normalCdf((raw.q1TotalLine - projQ1Total) / sigmaQ1)).coerceIn(0.03, 0.97)
        val probQ1Under = (1.0 - probQ1Over).coerceIn(0.03, 0.97)

        // 1st Half (Q1+Q2) Total Over
        val projHtTotal = projTotal * (QUARTER_WEIGHTS[0] + QUARTER_WEIGHTS[1])
        val probHtOver = (1.0 - normalCdf((raw.htTotalLine - projHtTotal) / sigmaHt)).coerceIn(0.03, 0.97)

        // Home Team Total Over
        val probHomeTeamOver = (1.0 - normalCdf((raw.homeTeamTotalLine - projHome) / sigmaTeam)).coerceIn(0.03, 0.97)

        // Alternative Safe Total (margin cushion below main line, e.g., Total - 6.0 pts)
        val probAltSafeOver = (1.0 - normalCdf((raw.altSafeTotalLine - projTotal) / sigmaTotal)).coerceIn(0.05, 0.96)

        // Margin probability buckets for Canvas chart
        val pHomeBig = (1.0 - normalCdf((9.5 - projMargin) / sigmaMargin)).toFloat().coerceIn(0f, 1f)
        val pHomeClose = (normalCdf((9.5 - projMargin) / sigmaMargin) - normalCdf((0.0 - projMargin) / sigmaMargin)).toFloat().coerceIn(0f, 1f)
        val pAwayClose = (normalCdf((0.0 - projMargin) / sigmaMargin) - normalCdf((-9.5 - projMargin) / sigmaMargin)).toFloat().coerceIn(0f, 1f)
        val pAwayBig = normalCdf((-9.5 - projMargin) / sigmaMargin).toFloat().coerceIn(0f, 1f)
        val marginBuckets = listOf(
            "Casa +10" to pHomeBig,
            "Casa 1-9" to pHomeClose,
            "Fora 1-9" to pAwayClose,
            "Fora +10" to pAwayBig
        )

        val matchLabel = "${raw.equipaCasa} vs ${raw.equipaFora}"
        val spreadSignHome = if (raw.homeSpreadLine >= 0) "+${formatHalf(raw.homeSpreadLine)}" else formatHalf(raw.homeSpreadLine)
        val awayLine = -raw.homeSpreadLine
        val spreadSignAway = if (awayLine >= 0) "+${formatHalf(awayLine)}" else formatHalf(awayLine)

        val markets = listOf(
            evaluateMarket(raw, matchLabel, BasketballMarketType.HOME_ML, "ML Casa (${raw.equipaCasa.take(10)})", 0.0, raw.oddHomeMl, probHomeWin),
            evaluateMarket(raw, matchLabel, BasketballMarketType.AWAY_ML, "ML Fora (${raw.equipaFora.take(10)})", 0.0, raw.oddAwayMl, probAwayWin),
            evaluateMarket(raw, matchLabel, BasketballMarketType.HOME_SPREAD, "HC Casa $spreadSignHome", raw.homeSpreadLine, raw.oddHomeSpread, probHomeSpread),
            evaluateMarket(raw, matchLabel, BasketballMarketType.AWAY_SPREAD, "HC Fora $spreadSignAway", awayLine, raw.oddAwaySpread, probAwaySpread),
            evaluateMarket(raw, matchLabel, BasketballMarketType.TOTAL_OVER, "Mais ${formatHalf(raw.totalLine)} pts", raw.totalLine, raw.oddTotalOver, probTotalOver),
            evaluateMarket(raw, matchLabel, BasketballMarketType.TOTAL_UNDER, "Menos ${formatHalf(raw.totalLine)} pts", raw.totalLine, raw.oddTotalUnder, probTotalUnder),
            evaluateMarket(raw, matchLabel, BasketballMarketType.Q1_OVER, "Q1 Mais ${formatHalf(raw.q1TotalLine)}", raw.q1TotalLine, raw.oddQ1Over, probQ1Over),
            evaluateMarket(raw, matchLabel, BasketballMarketType.Q1_UNDER, "Q1 Menos ${formatHalf(raw.q1TotalLine)}", raw.q1TotalLine, raw.oddQ1Under, probQ1Under),
            evaluateMarket(raw, matchLabel, BasketballMarketType.HT_OVER, "1ªP Mais ${formatHalf(raw.htTotalLine)}", raw.htTotalLine, raw.oddHtOver, probHtOver),
            evaluateMarket(raw, matchLabel, BasketballMarketType.HOME_TEAM_OVER, "Casa Mais ${formatHalf(raw.homeTeamTotalLine)} pts", raw.homeTeamTotalLine, raw.oddHomeTeamOver, probHomeTeamOver),
            evaluateMarket(raw, matchLabel, BasketballMarketType.ALT_TOTAL_SAFE, "Alt. Mais ${formatHalf(raw.altSafeTotalLine)}", raw.altSafeTotalLine, raw.oddAltSafeOver, probAltSafeOver)
        )

        val bestValue = markets.maxByOrNull { it.edge }

        return BasketballMatchAnalysis(
            rawMatch = raw,
            projectedHomeTotal = round1(projHome),
            projectedAwayTotal = round1(projAway),
            projectedCombinedTotal = round1(projTotal),
            projectedHomeMargin = round1(projMargin),
            quarters = quarters,
            firstHalfHome = firstHalfHome,
            firstHalfAway = firstHalfAway,
            probHomeWin = probHomeWin,
            probAwayWin = probAwayWin,
            marginBuckets = marginBuckets,
            markets = markets,
            bestValueMarket = bestValue
        )
    }

    private fun evaluateMarket(
        raw: RawBasketballMatchData,
        matchLabel: String,
        market: BasketballMarketType,
        lineLabel: String,
        numericLine: Double,
        odd: Double,
        trueProb: Double
    ): BasketballMarketEvaluation {
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

        return BasketballMarketEvaluation(
            matchId = raw.id,
            matchLabel = matchLabel,
            competition = raw.competicao,
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
        fetchResult: BasketballFetchResult,
        stakeMode: StakeMode,
        totalBudget: Double
    ): BasketballAnalysisBundle {
        val analyses = fetchResult.matches.map { analyzeMatch(it) }
        val allocation = QuantEngine.computeStakeAllocation(stakeMode, totalBudget)

        // Slip 1 (40%): +EV Pure Value (2-3 distinct matches, highest edge across Q1, Spread, Total, ML)
        val slip1Legs = selectSlip1Legs(analyses, stakeMode)
        val slip1 = createSlip(
            type = BetSlipType.VALUE_EV,
            title = "Boletim 1 • Valor +EV Basquetebol",
            subtitle = "Maior Edge real em Pontos/Quartos, Handicap e Moneyline",
            selections = slip1Legs,
            stake = allocation.slip1Stake,
            stakeMode = stakeMode
        )

        // Slip 2 (40%): Solid Base (3-4 distinct matches, high probability safe totals / favorites)
        val slip2Legs = selectSlip2Legs(analyses)
        val slip2 = createSlip(
            type = BetSlipType.SOLID_BASE,
            title = "Boletim 2 • Base Sólida Pontos",
            subtitle = "Linhas com margem de segurança (Alt. Pontos / Favoritos)",
            selections = slip2Legs,
            stake = allocation.slip2Stake,
            stakeMode = stakeMode
        )

        // Slip 3 (20%): Leverage Base (3) & Full (4)
        val slip3BaseLegs = selectSlip3Legs(analyses, maxLegs = 3)
        val slip3FullLegs = selectSlip3Legs(analyses, maxLegs = 4)

        val slip3Base = createSlip(
            type = BetSlipType.ALL_IN_LEVERAGE,
            title = "Boletim 3 • Alavancagem (Base)",
            subtitle = "Múltipla de 3 jogos (Quartos, Spread & Totais)",
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

        val mcBase = runCorrelatedMonteCarlo(
            matches = analyses,
            slips = listOf(slip1, slip2, slip3Base),
            stakeMode = stakeMode,
            protectedBankroll = allocation.protectedCash
        )
        val mcFull = runCorrelatedMonteCarlo(
            matches = analyses,
            slips = listOf(slip1, slip2, slip3Full),
            stakeMode = stakeMode,
            protectedBankroll = allocation.protectedCash
        )

        return BasketballAnalysisBundle(
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
        analyses: List<BasketballMatchAnalysis>,
        stakeMode: StakeMode
    ): List<BasketballMarketEvaluation> {
        val candidates = analyses.mapNotNull { match ->
            match.markets
                .filter { it.odd in 1.45..4.50 && it.market != BasketballMarketType.ALT_TOTAL_SAFE }
                .maxByOrNull { eval ->
                    if (stakeMode == StakeMode.FREEBET) {
                        eval.edge * 1.4 + (eval.evPerEuroFreebet * 0.35)
                    } else {
                        eval.edge * 1.6 + eval.evPerEuroReal
                    }
                }
        }.sortedByDescending { it.edge }

        val targetCount = min(3, max(2, analyses.size))
        return candidates.take(targetCount)
    }

    private fun selectSlip2Legs(analyses: List<BasketballMatchAnalysis>): List<BasketballMarketEvaluation> {
        val candidates = analyses.mapNotNull { match ->
            match.markets
                .filter { it.trueProbability >= 0.56 && it.odd in 1.18..2.05 }
                .maxByOrNull { (it.trueProbability * 0.72) + (it.edge * 1.15) }
                ?: match.markets.maxByOrNull { it.trueProbability }
        }.sortedByDescending { it.trueProbability + it.edge }

        val targetCount = min(4, max(2, analyses.size))
        return candidates.take(targetCount)
    }

    private fun selectSlip3Legs(
        analyses: List<BasketballMatchAnalysis>,
        maxLegs: Int
    ): List<BasketballMarketEvaluation> {
        val candidates = analyses.mapNotNull { match ->
            match.markets
                .filter { it.odd in 1.55..3.40 }
                .maxByOrNull { (it.edge * 1.45) + (it.trueProbability * 0.45) }
                ?: match.markets.maxByOrNull { it.edge }
        }.sortedByDescending { it.edge + it.trueProbability * 0.3 }

        return candidates.take(min(maxLegs, analyses.size))
    }

    private fun createSlip(
        type: BetSlipType,
        title: String,
        subtitle: String,
        selections: List<BasketballMarketEvaluation>,
        stake: Double,
        stakeMode: StakeMode,
        isFullLeverageVariant: Boolean = false
    ): BasketballSlip {
        val combinedOdd = selections.fold(1.0) { acc, leg -> acc * leg.odd }
        val combinedTrueProb = selections.fold(1.0) { acc, leg -> acc * leg.trueProbability }
        val impliedCombinedProb = if (combinedOdd > 0.0) 1.0 / combinedOdd else 0.0
        val edgePct = (combinedTrueProb - impliedCombinedProb) * 100.0

        val grossReturn: Double
        val netProfitIfWin: Double
        val expectedVal: Double

        if (stakeMode == StakeMode.FREEBET) {
            netProfitIfWin = stake * max(0.0, combinedOdd - 1.0)
            grossReturn = netProfitIfWin
            expectedVal = combinedTrueProb * netProfitIfWin
        } else {
            grossReturn = stake * combinedOdd
            netProfitIfWin = stake * (combinedOdd - 1.0)
            expectedVal = (combinedTrueProb * grossReturn) - stake
        }

        return BasketballSlip(
            type = type,
            title = title,
            subtitle = subtitle,
            selections = selections,
            combinedOdd = round2(combinedOdd),
            combinedTrueProb = combinedTrueProb,
            stake = round2(stake),
            stakeMode = stakeMode,
            potentialGrossReturn = round2(grossReturn),
            potentialNetProfit = round2(netProfitIfWin),
            expectedValue = round2(expectedVal),
            edgePercent = edgePct,
            isFullLeverageVariant = isFullLeverageVariant
        )
    }

    /**
     * Correlated 100,000-iteration quarter-by-quarter Monte Carlo simulation.
     * Samples Q1..Q4 scores for every match (plus OT if tied) so Q1, 1st Half, Team Total,
     * Game Total, Spread, and Moneyline correlations are preserved accurately.
     */
    fun runCorrelatedMonteCarlo(
        matches: List<BasketballMatchAnalysis>,
        slips: List<BasketballSlip>,
        stakeMode: StakeMode,
        protectedBankroll: Double,
        iterations: Int = MONTE_CARLO_ITERATIONS,
        seed: Long = 20261119L
    ): MonteCarloReport {
        val totalDeployed = slips.sumOf { it.stake }
        if (matches.isEmpty() || slips.isEmpty() || totalDeployed <= 0.0) {
            return MonteCarloReport(
                iterations = iterations,
                probAtLeastOneWin = 0.0,
                probSlip1Win = 0.0,
                probSlip2Win = 0.0,
                probSlip3Win = 0.0,
                probAllThreeWin = 0.0,
                expectedNetReturn = 0.0,
                expectedRoiPercent = 0.0,
                percentile95Return = 0.0,
                bankrollProtectedAmount = protectedBankroll,
                totalDeployedStake = totalDeployed,
                histogramLabels = listOf("0/3", "1/3", "2/3", "3/3"),
                histogramFrequencies = listOf(1f, 0f, 0f, 0f)
            )
        }

        val rng = Random(seed)
        val matchIndexById = matches.mapIndexed { idx, m -> m.rawMatch.id to idx }.toMap()
        val matchCount = matches.size

        // Precompute per-quarter team mean & SD (with gentle shared pace shock + independent team shock)
        val qHomeMeans = Array(matchCount) { mIdx ->
            DoubleArray(4) { q -> matches[mIdx].projectedHomeTotal * QUARTER_WEIGHTS[q] }
        }
        val qAwayMeans = Array(matchCount) { mIdx ->
            DoubleArray(4) { q -> matches[mIdx].projectedAwayTotal * QUARTER_WEIGHTS[q] }
        }
        val qPaceSd = DoubleArray(matchCount) { mIdx ->
            if (matches[mIdx].rawMatch.isNbaFormat) 2.5 else 2.0
        }
        val qTeamSd = DoubleArray(matchCount) { mIdx ->
            if (matches[mIdx].rawMatch.isNbaFormat) 4.3 else 3.8
        }

        // Pre-index slip legs
        data class IndexedLeg(val matchIdx: Int, val market: BasketballMarketType, val line: Double)
        val indexedSlips = slips.map { slip ->
            slip.selections.mapNotNull { leg ->
                val idx = matchIndexById[leg.matchId] ?: return@mapNotNull null
                IndexedLeg(idx, leg.market, leg.numericLine)
            }
        }

        val simQ1Total = DoubleArray(matchCount)
        val simHtTotal = DoubleArray(matchCount)
        val simHomeFinal = DoubleArray(matchCount)
        val simAwayFinal = DoubleArray(matchCount)

        var atLeastOneWins = 0
        var slip1Wins = 0
        var slip2Wins = 0
        var slip3Wins = 0
        var allThreeWins = 0
        val winBuckets = IntArray(4)
        var sumNetReturn = 0.0
        val sampleReturns = DoubleArray(iterations)

        for (iter in 0 until iterations) {
            for (m in 0 until matchCount) {
                var hSum = 0.0
                var aSum = 0.0
                var q1Sum = 0.0
                var htSum = 0.0
                val pSd = qPaceSd[m]
                val tSd = qTeamSd[m]

                for (q in 0 until 4) {
                    val paceShock = nextGaussian(rng) * pSd
                    val hQ = max(8.0, qHomeMeans[m][q] + paceShock + nextGaussian(rng) * tSd)
                    val aQ = max(8.0, qAwayMeans[m][q] + paceShock + nextGaussian(rng) * tSd)
                    hSum += hQ
                    aSum += aQ
                    if (q == 0) q1Sum = hQ + aQ
                    if (q == 1) htSum = hSum + aSum
                }

                // Overtime if scores land within 0.5 pt (tie after regulation)
                if (abs(hSum - aSum) < 0.55) {
                    val otBase = if (matches[m].rawMatch.isNbaFormat) 10.5 else 8.5
                    val otH = max(2.0, otBase + nextGaussian(rng) * 3.2)
                    val otA = max(2.0, otBase + nextGaussian(rng) * 3.2)
                    hSum += otH
                    aSum += otA
                    if (abs(hSum - aSum) < 0.01) {
                        if (rng.nextBoolean()) hSum += 1.0 else aSum += 1.0
                    }
                }

                simQ1Total[m] = q1Sum
                simHtTotal[m] = htSum
                simHomeFinal[m] = hSum
                simAwayFinal[m] = aSum
            }

            var wonCount = 0
            var netIter = if (stakeMode == StakeMode.REAL_BANKROLL) -totalDeployed else 0.0

            for (sIdx in slips.indices) {
                val legs = indexedSlips[sIdx]
                if (legs.isEmpty()) continue
                var allHit = true
                for (leg in legs) {
                    val m = leg.matchIdx
                    val h = simHomeFinal[m]
                    val a = simAwayFinal[m]
                    val total = h + a
                    val margin = h - a
                    val hit = when (leg.market) {
                        BasketballMarketType.HOME_ML -> h > a
                        BasketballMarketType.AWAY_ML -> a > h
                        BasketballMarketType.HOME_SPREAD -> (margin + leg.line) > 0.0
                        BasketballMarketType.AWAY_SPREAD -> (-margin + leg.line) > 0.0
                        BasketballMarketType.TOTAL_OVER -> total > leg.line
                        BasketballMarketType.TOTAL_UNDER -> total < leg.line
                        BasketballMarketType.Q1_OVER -> simQ1Total[m] > leg.line
                        BasketballMarketType.Q1_UNDER -> simQ1Total[m] < leg.line
                        BasketballMarketType.HT_OVER -> simHtTotal[m] > leg.line
                        BasketballMarketType.HOME_TEAM_OVER -> h > leg.line
                        BasketballMarketType.ALT_TOTAL_SAFE -> total > leg.line
                    }
                    if (!hit) {
                        allHit = false
                        break
                    }
                }

                if (allHit) {
                    wonCount++
                    if (sIdx == 0) slip1Wins++
                    if (sIdx == 1) slip2Wins++
                    if (sIdx == 2) slip3Wins++
                    netIter += slips[sIdx].potentialGrossReturn
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
        val expectedNet = sumNetReturn / iterations
        val roiPct = if (totalDeployed > 0.0) (expectedNet / totalDeployed) * 100.0 else 0.0

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
            bankrollProtectedAmount = protectedBankroll,
            totalDeployedStake = totalDeployed,
            histogramLabels = listOf("0/3", "1/3", "2/3", "3/3"),
            histogramFrequencies = winBuckets.map { it.toFloat() / iterations.toFloat() }
        )
    }

    /**
     * Standard Normal CDF via Abramowitz & Stegun formula 7.1.26 (max error < 1.5e-7).
     */
    internal fun normalCdf(z: Double): Double {
        val x = z / sqrt(2.0)
        val sign = if (x < 0) -1.0 else 1.0
        val ax = abs(x)
        val t = 1.0 / (1.0 + 0.3275911 * ax)
        val y = 1.0 - (((((1.061405429 * t - 1.453152027) * t) + 1.421413741) * t - 0.284496736) * t + 0.254829592) * t * exp(-ax * ax)
        return 0.5 * (1.0 + sign * y)
    }

    private fun nextGaussian(rng: Random): Double {
        val u1 = rng.nextDouble().coerceIn(1e-12, 1.0 - 1e-12)
        val u2 = rng.nextDouble()
        return sqrt(-2.0 * ln(u1)) * cos(2.0 * Math.PI * u2)
    }

    fun roundHalf(value: Double): Double = round(value - 0.5) + 0.5

    fun formatHalf(value: Double): String = String.format(Locale.US, "%.1f", value)

    private fun round1(value: Double): Double = round(value * 10.0) / 10.0
    private fun round2(value: Double): Double = round(value * 100.0) / 100.0
}
