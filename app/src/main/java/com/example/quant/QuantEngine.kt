package com.example.quant

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random

/**
 * Pure local quantitative sports-betting engine.
 * Never trusts LLM probability estimates — computes every metric locally on-device:
 * 1. Portugal Continental (WET/WEST) Temporal Cycle detection
 * 2. Dynamic Elo Rating adjustment with home advantage
 * 3. Bivariate Poisson 12x12 Matrix with Dixon-Coles low-score correction (rho = -0.13)
 * 4. Implied probability, Edge, +EV (Real Bankroll & Freebet SNR), Fractional 1/4 Kelly
 * 5. Automatic 3-Slip Portfolio Construction (Value +EV, Solid Base, Leverage Base/Full)
 * 6. Correlated 100,000-iteration Monte Carlo simulation sampling joint match scorelines
 */
object QuantEngine {

    const val MATRIX_SIZE = 12
    const val DIXON_COLES_RHO = -0.13
    const val ELO_HOME_ADVANTAGE = 65.0
    const val MONTE_CARLO_ITERATIONS = 100_000
    private const val QUARTER_KELLY_MULTIPLIER = 0.25
    private const val MAX_KELLY_CAP = 0.10

    private val logFactorials: DoubleArray = DoubleArray(MATRIX_SIZE).also { arr ->
        var acc = 0.0
        arr[0] = 0.0
        for (k in 1 until MATRIX_SIZE) {
            acc += ln(k.toDouble())
            arr[k] = acc
        }
    }

    /**
     * Step 1: Detects current date/time in Portugal Continental (Europe/Lisbon: WET / WEST).
     * - Cycle A (Monday to Thursday): UEFA competitions, national cups, midweek league fixtures.
     * - Cycle B (Friday to Sunday): Major European leagues weekend fixtures.
     */
    fun detectCurrentPortugalCycle(nowMillis: Long = System.currentTimeMillis()): TemporalCycleInfo {
        val lisbonTz = TimeZone.getTimeZone("Europe/Lisbon")
        val cal = Calendar.getInstance(lisbonTz).apply { timeInMillis = nowMillis }

        val dayOfWeek = cal.get(Calendar.DAY_OF_WEEK)
        val isWeekendCycle = dayOfWeek == Calendar.FRIDAY ||
            dayOfWeek == Calendar.SATURDAY ||
            dayOfWeek == Calendar.SUNDAY

        val cycle = if (isWeekendCycle) {
            TemporalCycle.CYCLE_B_WEEKEND
        } else {
            TemporalCycle.CYCLE_A_MIDWEEK
        }

        // Remaining days in active cycle (inclusive of today through end of cycle)
        val daysUntilCycleEnd = when (dayOfWeek) {
            Calendar.MONDAY -> 3      // Mon -> Thu
            Calendar.TUESDAY -> 2     // Tue -> Thu
            Calendar.WEDNESDAY -> 1   // Wed -> Thu
            Calendar.THURSDAY -> 0    // Thu
            Calendar.FRIDAY -> 2      // Fri -> Sun
            Calendar.SATURDAY -> 1    // Sat -> Sun
            else -> 0                 // Sun
        }

        val isoFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = lisbonTz }
        val timeFmt = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale("pt", "PT")).apply {
            timeZone = lisbonTz
        }
        val tzLabel = if (lisbonTz.inDaylightTime(cal.time)) "WEST (UTC+1)" else "WET (UTC+0)"

        val exactDates = mutableListOf<String>()
        val cursor = (cal.clone() as Calendar)
        for (offset in 0..daysUntilCycleEnd) {
            if (offset > 0) cursor.add(Calendar.DAY_OF_MONTH, 1)
            exactDates.add(isoFmt.format(cursor.time))
        }

        return TemporalCycleInfo(
            cycle = cycle,
            currentTimeWet = timeFmt.format(cal.time),
            timezoneLabel = tzLabel,
            startDateIso = exactDates.first(),
            endDateIso = exactDates.last(),
            exactDatesIso = exactDates
        )
    }

    /**
     * Generates the next [dayCount] calendar days in Portugal Continental (Europe/Lisbon)
     * so the user can pick exact days or switch cleanly between Fri-Sun and Mon-Thu cycles.
     */
    fun buildSelectableDays(
        nowMillis: Long = System.currentTimeMillis(),
        dayCount: Int = 8
    ): List<SelectableDay> {
        val lisbonTz = TimeZone.getTimeZone("Europe/Lisbon")
        val isoFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = lisbonTz }
        val dmFmt = SimpleDateFormat("dd/MM", Locale("pt", "PT")).apply { timeZone = lisbonTz }

        val cursor = Calendar.getInstance(lisbonTz).apply { timeInMillis = nowMillis }
        val list = mutableListOf<SelectableDay>()
        for (i in 0 until dayCount) {
            if (i > 0) cursor.add(Calendar.DAY_OF_MONTH, 1)
            val dow = cursor.get(Calendar.DAY_OF_WEEK)
            val weekday = when (dow) {
                Calendar.MONDAY -> "Seg"
                Calendar.TUESDAY -> "Ter"
                Calendar.WEDNESDAY -> "Qua"
                Calendar.THURSDAY -> "Qui"
                Calendar.FRIDAY -> "Sex"
                Calendar.SATURDAY -> "Sáb"
                else -> "Dom"
            }
            val isWeekend = dow == Calendar.FRIDAY || dow == Calendar.SATURDAY || dow == Calendar.SUNDAY
            list.add(
                SelectableDay(
                    isoDate = isoFmt.format(cursor.time),
                    shortWeekday = weekday,
                    dayMonth = dmFmt.format(cursor.time),
                    isWeekend = isWeekend
                )
            )
        }
        return list
    }

    /**
     * Computes exact ISO dates for quick cycle presets (Fri-Sun weekend, Mon-Thu midweek, or Today).
     */
    fun getDatesForPreset(
        preset: CyclePreset,
        nowMillis: Long = System.currentTimeMillis()
    ): List<String> {
        val days = buildSelectableDays(nowMillis, dayCount = 10)
        return when (preset) {
            CyclePreset.TODAY -> listOf(days.first().isoDate)
            CyclePreset.WEEKEND_FRI_SUN -> {
                // Find the current or next contiguous Fri-Sat-Sun block
                val firstWeekendIdx = days.indexOfFirst { it.isWeekend }.coerceAtLeast(0)
                val block = mutableListOf<String>()
                var idx = firstWeekendIdx
                while (idx < days.size && days[idx].isWeekend) {
                    block.add(days[idx].isoDate)
                    idx++
                }
                block.ifEmpty { listOf(days.first().isoDate) }
            }
            CyclePreset.MIDWEEK_MON_THU -> {
                // Find the current (if Mon-Wed) or next full Mon-Thu block (if Thu/Fri/Sat/Sun, pick next Mon-Thu unless Thu has games)
                val firstDay = days.first()
                val startSearchIdx = if (!firstDay.isWeekend && firstDay.shortWeekday != "Qui") {
                    0
                } else {
                    // On Thursday or weekend, jump to the upcoming Monday-Thursday cycle
                    days.indexOfFirst { it.shortWeekday == "Seg" }.takeIf { it >= 0 } ?: 0
                }
                val block = mutableListOf<String>()
                var idx = startSearchIdx
                while (idx < days.size && !days[idx].isWeekend) {
                    block.add(days[idx].isoDate)
                    idx++
                }
                block.ifEmpty { listOf(days.first().isoDate) }
            }
            CyclePreset.CUSTOM -> listOf(days.first().isoDate)
        }
    }

    /**
     * Builds a [TemporalCycleInfo] strictly bound to the user-selected ISO dates in Portugal WET/WEST.
     */
    fun buildCycleInfoForDates(
        selectedDatesIso: Collection<String>,
        nowMillis: Long = System.currentTimeMillis()
    ): TemporalCycleInfo {
        val sorted = selectedDatesIso.distinct().sorted()
        if (sorted.isEmpty()) return detectCurrentPortugalCycle(nowMillis)

        val lisbonTz = TimeZone.getTimeZone("Europe/Lisbon")
        val calNow = Calendar.getInstance(lisbonTz).apply { timeInMillis = nowMillis }
        val timeFmt = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale("pt", "PT")).apply {
            timeZone = lisbonTz
        }
        val tzLabel = if (lisbonTz.inDaylightTime(calNow.time)) "WEST (UTC+1)" else "WET (UTC+0)"

        val isoFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = lisbonTz }
        var weekendCount = 0
        var midweekCount = 0
        for (iso in sorted) {
            val parsed = runCatching { isoFmt.parse(iso) }.getOrNull() ?: continue
            val c = Calendar.getInstance(lisbonTz).apply { time = parsed }
            val dow = c.get(Calendar.DAY_OF_WEEK)
            if (dow == Calendar.FRIDAY || dow == Calendar.SATURDAY || dow == Calendar.SUNDAY) {
                weekendCount++
            } else {
                midweekCount++
            }
        }

        val cycle = if (weekendCount >= midweekCount) {
            TemporalCycle.CYCLE_B_WEEKEND
        } else {
            TemporalCycle.CYCLE_A_MIDWEEK
        }

        return TemporalCycleInfo(
            cycle = cycle,
            currentTimeWet = timeFmt.format(calNow.time),
            timezoneLabel = tzLabel,
            startDateIso = sorted.first(),
            endDateIso = sorted.last(),
            exactDatesIso = sorted
        )
    }

    /**
     * Step 3: Computes full match probabilities using Elo-adjusted lambdas + 12x12 Dixon-Coles matrix.
     */
    fun analyzeMatch(raw: RawMatchData): MatchQuantAnalysis {
        // 1. Dynamic Elo Differential with Home Advantage
        val eloDiff = (raw.eloCasaEstimado + ELO_HOME_ADVANTAGE) - raw.eloForaEstimado
        val eloExpectedHome = 1.0 / (1.0 + 10.0.pow(-eloDiff / 400.0))

        // Gentle Elo calibration factor applied onto xG lambdas (preserves empirical xG scale while reflecting class gap)
        val eloShift = (eloExpectedHome - 0.50) * 0.24
        val lambdaHome = (raw.xgCasaEstimado.coerceIn(0.25, 4.20) * (1.0 + eloShift)).coerceIn(0.20, 4.50)
        val lambdaAway = (raw.xgForaEstimado.coerceIn(0.25, 4.20) * (1.0 - eloShift)).coerceIn(0.20, 4.50)

        // 2. Build 12x12 Poisson + Dixon-Coles Matrix (rho = -0.13)
        val matrix = buildDixonColesMatrix(lambdaHome, lambdaAway, DIXON_COLES_RHO)

        var prob1 = 0.0
        var probX = 0.0
        var prob2 = 0.0
        var probOver25 = 0.0
        var probUnder35 = 0.0
        var probBtts = 0.0
        val allScorelines = ArrayList<ScorelineProb>(MATRIX_SIZE * MATRIX_SIZE)

        for (h in 0 until MATRIX_SIZE) {
            for (a in 0 until MATRIX_SIZE) {
                val p = matrix[h][a]
                allScorelines.add(ScorelineProb(h, a, p))
                when {
                    h > a -> prob1 += p
                    h == a -> probX += p
                    else -> prob2 += p
                }
                if (h + a >= 3) probOver25 += p
                if (h + a <= 3) probUnder35 += p
                if (h >= 1 && a >= 1) probBtts += p
            }
        }

        val matchLabel = "${raw.equipaCasa} vs ${raw.equipaFora}"
        val markets = listOf(
            evaluateMarket(raw, matchLabel, MarketType.HOME_WIN, raw.odd1, prob1),
            evaluateMarket(raw, matchLabel, MarketType.DRAW, raw.oddX, probX),
            evaluateMarket(raw, matchLabel, MarketType.AWAY_WIN, raw.odd2, prob2),
            evaluateMarket(raw, matchLabel, MarketType.OVER_25, raw.oddOver25, probOver25),
            evaluateMarket(raw, matchLabel, MarketType.UNDER_35, raw.oddUnder35, probUnder35),
            evaluateMarket(raw, matchLabel, MarketType.BTTS, raw.oddBtts, probBtts)
        )

        val bestValue = markets.maxByOrNull { it.edge }
        val topScores = allScorelines.sortedByDescending { it.probability }.take(5)

        return MatchQuantAnalysis(
            rawMatch = raw,
            adjustedLambdaHome = lambdaHome,
            adjustedLambdaAway = lambdaAway,
            eloDiffWithHomeAdvantage = eloDiff,
            eloExpectedHomeScore = eloExpectedHome,
            scoreMatrix12x12 = matrix.map { it.toList() },
            prob1 = prob1,
            probX = probX,
            prob2 = prob2,
            probOver25 = probOver25,
            probUnder35 = probUnder35,
            probBtts = probBtts,
            markets = markets,
            bestValueMarket = bestValue,
            topScorelines = topScores
        )
    }

    /**
     * Exact normalized 12x12 Poisson + Dixon-Coles matrix with rho = -0.13.
     */
    fun buildDixonColesMatrix(
        lambdaHome: Double,
        lambdaAway: Double,
        rho: Double = DIXON_COLES_RHO
    ): Array<DoubleArray> {
        val homePmfs = DoubleArray(MATRIX_SIZE) { k -> poissonPmf(k, lambdaHome) }
        val awayPmfs = DoubleArray(MATRIX_SIZE) { k -> poissonPmf(k, lambdaAway) }

        val matrix = Array(MATRIX_SIZE) { DoubleArray(MATRIX_SIZE) }
        var sum = 0.0

        for (h in 0 until MATRIX_SIZE) {
            for (a in 0 until MATRIX_SIZE) {
                val tau = dixonColesTau(h, a, lambdaHome, lambdaAway, rho)
                val p = max(0.0, homePmfs[h] * awayPmfs[a] * tau)
                matrix[h][a] = p
                sum += p
            }
        }

        if (sum > 0.0) {
            for (h in 0 until MATRIX_SIZE) {
                for (a in 0 until MATRIX_SIZE) {
                    matrix[h][a] /= sum
                }
            }
        }
        return matrix
    }

    private fun poissonPmf(k: Int, lambda: Double): Double {
        if (lambda <= 0.0) return if (k == 0) 1.0 else 0.0
        return exp(k * ln(lambda) - lambda - logFactorials[k])
    }

    private fun dixonColesTau(
        homeGoals: Int,
        awayGoals: Int,
        lambda: Double,
        mu: Double,
        rho: Double
    ): Double {
        val rawTau = when {
            homeGoals == 0 && awayGoals == 0 -> 1.0 - (lambda * mu * rho)
            homeGoals == 0 && awayGoals == 1 -> 1.0 + (lambda * rho)
            homeGoals == 1 && awayGoals == 0 -> 1.0 + (mu * rho)
            homeGoals == 1 && awayGoals == 1 -> 1.0 - rho
            else -> 1.0
        }
        return max(0.01, rawTau)
    }

    private fun evaluateMarket(
        raw: RawMatchData,
        matchLabel: String,
        market: MarketType,
        odd: Double,
        trueProb: Double
    ): MarketEvaluation {
        val safeOdd = max(1.01, odd)
        val impliedProb = 1.0 / safeOdd
        val edge = trueProb - impliedProb
        val evReal = (trueProb * (safeOdd - 1.0)) - (1.0 - trueProb)
        val evFreebet = trueProb * (safeOdd - 1.0)

        // Fractional 1/4 Kelly for Real Bankroll
        val b = safeOdd - 1.0
        val q = 1.0 - trueProb
        val fullKelly = if (b > 0.0) ((b * trueProb) - q) / b else 0.0
        val quarterKelly = max(0.0, fullKelly * QUARTER_KELLY_MULTIPLIER).coerceAtMost(MAX_KELLY_CAP)

        return MarketEvaluation(
            matchId = raw.id,
            matchLabel = matchLabel,
            competition = raw.competicao,
            kickoffWet = raw.dataHoraWet,
            market = market,
            odd = safeOdd,
            trueProbability = trueProb,
            impliedProbability = impliedProb,
            edge = edge,
            evPerEuroReal = evReal,
            evPerEuroFreebet = evFreebet,
            quarterKellyFraction = quarterKelly
        )
    }

    /**
     * Step 4 & 5: Constructs the full AnalysisBundle (3 Slips + Base/Full Leverage + 100k Monte Carlo).
     */
    fun buildAnalysisBundle(
        fetchResult: GroundedFetchResult,
        stakeMode: StakeMode,
        totalBudget: Double
    ): AnalysisBundle {
        val safeBudget = totalBudget.coerceAtLeast(1.0)
        val analyzedMatches = fetchResult.matches.map { analyzeMatch(it) }

        val (stake1, stake2, stake3, protectedCash) = computeStakeAllocation(stakeMode, safeBudget)

        // Boletim 1: Valor Matemático +EV Puro (2 to 3 distinct-match selections with highest positive real Edge)
        val bestPerMatchByEdge = analyzedMatches.mapNotNull { m ->
            m.markets.maxByOrNull { it.edge }
        }.sortedByDescending { it.edge }

        val strictPositive = bestPerMatchByEdge.filter { it.edge > 0.005 }
        val slip1Legs = when {
            strictPositive.size >= 3 -> strictPositive.take(3)
            strictPositive.size == 2 -> strictPositive
            else -> bestPerMatchByEdge.take(min(3, max(2, bestPerMatchByEdge.size)))
        }

        // Boletim 2: Base Sólida / Favoritos Diretos (3 to 4 distinct-match selections with highest true probability)
        val solidCandidates = analyzedMatches.mapNotNull { m ->
            m.markets
                .filter { it.odd >= 1.18 }
                .sortedByDescending { candidate ->
                    // Prioritize direct favorites (1, 2) and Under 3.5 with high true probability and decent odds
                    val directBonus = if (candidate.market == MarketType.HOME_WIN || candidate.market == MarketType.AWAY_WIN) 0.04 else 0.0
                    candidate.trueProbability + directBonus
                }
                .firstOrNull()
        }.sortedByDescending { it.trueProbability }
        val slip2Legs = solidCandidates.take(min(4, max(3, solidCandidates.size)))

        // Boletim 3: "Boletim de Tudo" / Alavancagem (Versão Base & Versão Completa)
        val leverageCandidates = analyzedMatches.mapNotNull { m ->
            m.markets
                .filter { it.odd >= 1.35 }
                .maxByOrNull { eval ->
                    // Balance value edge and realistic hit probability for leverage accumulators
                    (eval.edge * 2.2) + (eval.trueProbability * 0.45)
                } ?: m.markets.maxByOrNull { it.edge }
        }.sortedByDescending { (it.edge * 2.0) + it.trueProbability }

        val slip3BaseLegs = leverageCandidates.take(min(5, max(4, leverageCandidates.size)))
        val slip3FullLegs = leverageCandidates // All confirmed matches combined

        val slip1 = buildSlip(
            type = BetSlipType.VALUE_EV,
            title = "Boletim 1 • Valor Matemático (+EV)",
            subtitle = "Seleções com maior Edge positivo (Prob Real > 1/Odd)",
            selections = slip1Legs,
            stake = stake1,
            stakeMode = stakeMode,
            isFullLeverageVariant = false
        )

        val slip2 = buildSlip(
            type = BetSlipType.SOLID_BASE,
            title = "Boletim 2 • Base Sólida",
            subtitle = "Favoritos diretos e mercados de elevada probabilidade real",
            selections = slip2Legs,
            stake = stake2,
            stakeMode = stakeMode,
            isFullLeverageVariant = false
        )

        val slip3Base = buildSlip(
            type = BetSlipType.ALL_IN_LEVERAGE,
            title = "Boletim 3 • Alavancagem (Base)",
            subtitle = "Acumulada otimizada de retorno assimétrico (${slip3BaseLegs.size} jogos)",
            selections = slip3BaseLegs,
            stake = stake3,
            stakeMode = stakeMode,
            isFullLeverageVariant = false
        )

        val slip3Full = buildSlip(
            type = BetSlipType.ALL_IN_LEVERAGE,
            title = "Boletim 3 • Boletim de Tudo (Completa)",
            subtitle = "Acumulada total com todos os jogos confirmados (${slip3FullLegs.size} jogos)",
            selections = slip3FullLegs,
            stake = stake3,
            stakeMode = stakeMode,
            isFullLeverageVariant = true
        )

        val mcBase = runCorrelatedMonteCarlo(
            matches = analyzedMatches,
            slip1 = slip1,
            slip2 = slip2,
            slip3 = slip3Base,
            stakeMode = stakeMode,
            protectedCash = protectedCash,
            iterations = MONTE_CARLO_ITERATIONS
        )

        val mcFull = runCorrelatedMonteCarlo(
            matches = analyzedMatches,
            slip1 = slip1,
            slip2 = slip2,
            slip3 = slip3Full,
            stakeMode = stakeMode,
            protectedCash = protectedCash,
            iterations = MONTE_CARLO_ITERATIONS
        )

        return AnalysisBundle(
            cycleInfo = fetchResult.cycleInfo,
            stakeMode = stakeMode,
            totalBudget = safeBudget,
            matches = analyzedMatches,
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

    /**
     * Exact stake split rule:
     * - Freebet (e.g., 5.00€): 40% B1 (2.00€), 40% B2 (2.00€), 20% B3 (1.00€), 0€ protected.
     * - Real Bankroll (e.g., 20.00€): 50% untouched cash (10.00€), remaining 50% split 40% B1 (4.00€), 40% B2 (4.00€), 20% B3 (2.00€).
     */
    fun computeStakeAllocation(stakeMode: StakeMode, totalBudget: Double): StakeAllocation {
        val safe = totalBudget.coerceAtLeast(1.0)
        return when (stakeMode) {
            StakeMode.FREEBET -> {
                val b1 = roundCents(safe * 0.40)
                val b2 = roundCents(safe * 0.40)
                val b3 = roundCents(safe - b1 - b2)
                StakeAllocation(b1, b2, b3, protectedCash = 0.0)
            }
            StakeMode.REAL_BANKROLL -> {
                val protectedCash = roundCents(safe * 0.50)
                val activePool = roundCents(safe - protectedCash)
                val b1 = roundCents(activePool * 0.40)
                val b2 = roundCents(activePool * 0.40)
                val b3 = roundCents(activePool - b1 - b2)
                StakeAllocation(b1, b2, b3, protectedCash = protectedCash)
            }
        }
    }

    data class StakeAllocation(
        val slip1Stake: Double,
        val slip2Stake: Double,
        val slip3Stake: Double,
        val protectedCash: Double
    )

    private fun buildSlip(
        type: BetSlipType,
        title: String,
        subtitle: String,
        selections: List<MarketEvaluation>,
        stake: Double,
        stakeMode: StakeMode,
        isFullLeverageVariant: Boolean
    ): GeneratedSlip {
        val combinedOdd = selections.fold(1.0) { acc, item -> acc * item.odd }
        val combinedTrueProb = selections.fold(1.0) { acc, item -> acc * item.trueProbability }
        val impliedCombinedProb = if (combinedOdd > 0.0) 1.0 / combinedOdd else 0.0
        val edgePercent = (combinedTrueProb - impliedCombinedProb) * 100.0

        val potentialGrossReturn = when (stakeMode) {
            StakeMode.FREEBET -> stake * (combinedOdd - 1.0) // SNR: stake not returned
            StakeMode.REAL_BANKROLL -> stake * combinedOdd
        }
        val potentialNetProfit = stake * (combinedOdd - 1.0)

        val expectedValue = when (stakeMode) {
            StakeMode.FREEBET -> combinedTrueProb * (combinedOdd - 1.0) * stake
            StakeMode.REAL_BANKROLL -> (combinedTrueProb * (combinedOdd - 1.0) * stake) - ((1.0 - combinedTrueProb) * stake)
        }

        return GeneratedSlip(
            type = type,
            title = title,
            subtitle = subtitle,
            selections = selections,
            combinedOdd = combinedOdd,
            combinedTrueProb = combinedTrueProb,
            stake = stake,
            stakeMode = stakeMode,
            potentialGrossReturn = max(0.0, potentialGrossReturn),
            potentialNetProfit = max(0.0, potentialNetProfit),
            expectedValue = expectedValue,
            edgePercent = edgePercent,
            isFullLeverageVariant = isFullLeverageVariant
        )
    }

    /**
     * Correlated 100,000-iteration Monte Carlo simulation.
     * In each iteration, samples ONE exact scoreline (h, a) per match from its 12x12 Dixon-Coles CDF,
     * preserving exact cross-market and cross-slip correlation across all 3 slips simultaneously.
     */
    fun runCorrelatedMonteCarlo(
        matches: List<MatchQuantAnalysis>,
        slip1: GeneratedSlip,
        slip2: GeneratedSlip,
        slip3: GeneratedSlip,
        stakeMode: StakeMode,
        protectedCash: Double,
        iterations: Int = MONTE_CARLO_ITERATIONS,
        seed: Long = 20261119L
    ): MonteCarloReport {
        val deployedStake = slip1.stake + slip2.stake + slip3.stake
        if (matches.isEmpty() || iterations <= 0) {
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
                bankrollProtectedAmount = protectedCash,
                totalDeployedStake = deployedStake,
                histogramLabels = listOf("0", "1", "2", "3"),
                histogramFrequencies = listOf(1f, 0f, 0f, 0f)
            )
        }

        // Index matches for ultra-fast O(1) lookup inside the 100k inner loop
        val matchIndexMap = HashMap<String, Int>(matches.size)
        val matchCdfs = Array(matches.size) { idx ->
            matchIndexMap[matches[idx].rawMatch.id] = idx
            val flatCdf = DoubleArray(MATRIX_SIZE * MATRIX_SIZE)
            var running = 0.0
            var pos = 0
            for (h in 0 until MATRIX_SIZE) {
                val row = matches[idx].scoreMatrix12x12[h]
                for (a in 0 until MATRIX_SIZE) {
                    running += row[a]
                    flatCdf[pos++] = running
                }
            }
            if (flatCdf.isNotEmpty()) flatCdf[flatCdf.lastIndex] = 1.0
            flatCdf
        }

        fun compileLegs(slip: GeneratedSlip): Pair<IntArray, IntArray> {
            val mIndices = IntArray(slip.selections.size)
            val mMarkets = IntArray(slip.selections.size)
            slip.selections.forEachIndexed { i, leg ->
                mIndices[i] = matchIndexMap[leg.matchId] ?: -1
                mMarkets[i] = leg.market.ordinal
            }
            return mIndices to mMarkets
        }

        val (s1MatchIdx, s1MarketOrd) = compileLegs(slip1)
        val (s2MatchIdx, s2MarketOrd) = compileLegs(slip2)
        val (s3MatchIdx, s3MarketOrd) = compileLegs(slip3)

        val sampledHome = IntArray(matches.size)
        val sampledAway = IntArray(matches.size)
        val netReturns = DoubleArray(iterations)

        val winReturn1 = slip1.potentialGrossReturn
        val winReturn2 = slip2.potentialGrossReturn
        val winReturn3 = slip3.potentialGrossReturn
        val costBasis = if (stakeMode == StakeMode.FREEBET) 0.0 else deployedStake

        var atLeastOneWins = 0
        var s1Wins = 0
        var s2Wins = 0
        var s3Wins = 0
        var allThreeWins = 0
        var sumNetReturn = 0.0

        // Count slips won distribution (0, 1, 2, 3 slips hit)
        val hitCounts = IntArray(4)
        val rng = Random(seed)

        for (iter in 0 until iterations) {
            // 1. Sample exact scoreline per match via binary search on 12x12 CDF
            for (m in 0 until matches.size) {
                val u = rng.nextDouble()
                val cdf = matchCdfs[m]
                var lo = 0
                var hi = cdf.size - 1
                while (lo < hi) {
                    val mid = (lo + hi) ushr 1
                    if (cdf[mid] < u) lo = mid + 1 else hi = mid
                }
                sampledHome[m] = lo / MATRIX_SIZE
                sampledAway[m] = lo % MATRIX_SIZE
            }

            // 2. Evaluate all 3 slips jointly under this exact simulated universe
            val w1 = evaluateCompiledSlip(s1MatchIdx, s1MarketOrd, sampledHome, sampledAway)
            val w2 = evaluateCompiledSlip(s2MatchIdx, s2MarketOrd, sampledHome, sampledAway)
            val w3 = evaluateCompiledSlip(s3MatchIdx, s3MarketOrd, sampledHome, sampledAway)

            var winsThisIter = 0
            var grossPayout = 0.0
            if (w1) {
                s1Wins++
                winsThisIter++
                grossPayout += winReturn1
            }
            if (w2) {
                s2Wins++
                winsThisIter++
                grossPayout += winReturn2
            }
            if (w3) {
                s3Wins++
                winsThisIter++
                grossPayout += winReturn3
            }

            if (winsThisIter >= 1) atLeastOneWins++
            if (winsThisIter == 3) allThreeWins++
            hitCounts[winsThisIter]++

            val net = grossPayout - costBasis
            netReturns[iter] = net
            sumNetReturn += net
        }

        netReturns.sort()
        val p95Index = ((iterations * 0.95).toInt()).coerceIn(0, iterations - 1)
        val expectedNet = sumNetReturn / iterations.toDouble()
        val expectedRoi = if (deployedStake > 0.0) (expectedNet / deployedStake) * 100.0 else 0.0

        return MonteCarloReport(
            iterations = iterations,
            probAtLeastOneWin = atLeastOneWins.toDouble() / iterations,
            probSlip1Win = s1Wins.toDouble() / iterations,
            probSlip2Win = s2Wins.toDouble() / iterations,
            probSlip3Win = s3Wins.toDouble() / iterations,
            probAllThreeWin = allThreeWins.toDouble() / iterations,
            expectedNetReturn = expectedNet,
            expectedRoiPercent = expectedRoi,
            percentile95Return = netReturns[p95Index],
            bankrollProtectedAmount = protectedCash,
            totalDeployedStake = deployedStake,
            histogramLabels = listOf("0 Boletins", "1 Boletim", "2 Boletins", "3 Boletins"),
            histogramFrequencies = hitCounts.map { it.toFloat() / iterations.toFloat() }
        )
    }

    private fun evaluateCompiledSlip(
        matchIndices: IntArray,
        marketOrdinals: IntArray,
        sampledHome: IntArray,
        sampledAway: IntArray
    ): Boolean {
        if (matchIndices.isEmpty()) return false
        for (i in matchIndices.indices) {
            val idx = matchIndices[i]
            if (idx < 0) return false
            val h = sampledHome[idx]
            val a = sampledAway[idx]
            val hit = when (marketOrdinals[i]) {
                0 -> h > a                  // HOME_WIN (1)
                1 -> h == a                 // DRAW (X)
                2 -> h < a                  // AWAY_WIN (2)
                3 -> (h + a) >= 3           // OVER_25
                4 -> (h + a) <= 3           // UNDER_35
                5 -> h >= 1 && a >= 1       // BTTS
                else -> false
            }
            if (!hit) return false
        }
        return true
    }

    private fun roundCents(value: Double): Double = kotlin.math.round(value * 100.0) / 100.0
}
