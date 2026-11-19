package com.example

import com.example.quant.GroundedFetchResult
import com.example.quant.QuantEngine
import com.example.quant.RawMatchData
import com.example.quant.StakeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class QuantEngineTest {

    @Test
    fun dixonColesMatrix12x12_normalizesToOneAndValidProbabilities() {
        val matrix = QuantEngine.buildDixonColesMatrix(1.85, 1.15, QuantEngine.DIXON_COLES_RHO)
        assertEquals(12, matrix.size)
        assertEquals(12, matrix[0].size)

        var total = 0.0
        for (h in 0 until 12) {
            for (a in 0 until 12) {
                assertTrue(matrix[h][a] >= 0.0)
                total += matrix[h][a]
            }
        }
        assertEquals(1.0, total, 1e-9)
    }

    @Test
    fun stakeAllocation_matchesExactFreebetAndRealBankrollRules() {
        val freebet = QuantEngine.computeStakeAllocation(StakeMode.FREEBET, 5.0)
        assertEquals(2.0, freebet.slip1Stake, 1e-6)
        assertEquals(2.0, freebet.slip2Stake, 1e-6)
        assertEquals(1.0, freebet.slip3Stake, 1e-6)
        assertEquals(0.0, freebet.protectedCash, 1e-6)

        val real = QuantEngine.computeStakeAllocation(StakeMode.REAL_BANKROLL, 20.0)
        assertEquals(10.0, real.protectedCash, 1e-6)
        assertEquals(4.0, real.slip1Stake, 1e-6)
        assertEquals(4.0, real.slip2Stake, 1e-6)
        assertEquals(2.0, real.slip3Stake, 1e-6)
    }

    @Test
    fun fullAnalysisBundle_computes100kCorrelatedMonteCarloAndThreeSlips() {
        val sampleMatches = listOf(
            RawMatchData("m1", "2026-11-19 20:00 WET", "UEFA Champions League", "Sporting CP", "Club Brugge", 2.15, 0.90, "Sem baixas", 1780.0, 1650.0, 1.62, 4.10, 5.20, 1.75, 1.42, 1.78),
            RawMatchData("m2", "2026-11-19 20:00 WET", "UEFA Champions League", "SL Benfica", "AS Monaco", 1.95, 1.05, "Sem baixas", 1790.0, 1690.0, 1.78, 3.80, 4.40, 1.70, 1.48, 1.68),
            RawMatchData("m3", "2026-11-19 17:45 WET", "UEFA Europa League", "FC Porto", "Lazio", 1.70, 1.15, "Sem baixas", 1750.0, 1730.0, 2.15, 3.40, 3.30, 1.88, 1.32, 1.74),
            RawMatchData("m4", "2026-11-19 20:00 WET", "UEFA Conference League", "Vitória SC", "Fiorentina", 1.40, 1.45, "Sem baixas", 1620.0, 1710.0, 3.10, 3.30, 2.30, 1.92, 1.30, 1.75)
        )
        val fetch = GroundedFetchResult(
            cycleInfo = QuantEngine.detectCurrentPortugalCycle(),
            matches = sampleMatches,
            sources = emptyList(),
            searchQueries = emptyList(),
            modelSummaryNote = "Teste unitário"
        )

        val bundle = QuantEngine.buildAnalysisBundle(fetch, StakeMode.FREEBET, 5.0)
        assertEquals(4, bundle.matches.size)
        assertTrue(bundle.slip1.selections.size in 2..3)
        assertTrue(bundle.slip2.selections.size in 3..4)
        assertEquals(4, bundle.slip3Full.selections.size)
        assertEquals(100_000, bundle.monteCarloBase.iterations)
        assertTrue(bundle.monteCarloBase.probAtLeastOneWin in 0.0..1.0)
        assertTrue(bundle.monteCarloBase.expectedNetReturn > 0.0)
    }

    @Test
    fun geminiParser_handlesFencedJsonAndPortugueseCommaDecimals() {
        val repo = com.example.data.GeminiRepository()
        val fencedSample = """
            Aqui estão os jogos encontrados:
            ```json
            {
              "resumo_ciclo": "Jogos confirmados na Liga Portugal e UEFA",
              "jogos": [
                {
                  "data_hora_wet": "2026-11-19 20:00 WET",
                  "competicao": "Liga Portugal",
                  "equipa_casa": "Sporting CP",
                  "equipa_fora": "SC Braga",
                  "xg_casa_estimado": "2,05",
                  "xg_fora_estimado": "0,95",
                  "lesoes_chave": "Sem baixas",
                  "odd_1": "1,58",
                  "odd_x": "4,10",
                  "odd_2": "5,40",
                  "odd_over25": "1,72",
                  "odd_under35": "1,44",
                  "odd_btts": "1,75"
                },
                {
                  "data_hora_wet": "2026-11-19 20:45 WET",
                  "competicao": "Liga Portugal",
                  "equipa_casa": "SL Benfica",
                  "equipa_fora": "FC Porto",
                  "xg_casa_estimado": 1.75,
                  "xg_fora_estimado": 1.25,
                  "lesoes_chave": "Sem baixas",
                  "odd_1": 2.05,
                  "odd_x": 3.45,
                  "odd_2": 3.60,
                  "odd_over25": 1.85,
                  "odd_under35": 1.35,
                  "odd_btts": 1.70
                }
              ]
            }
            ```
        """.trimIndent()

        val result = repo.parseGroundedJson(
            rawText = fencedSample,
            cycleInfo = QuantEngine.detectCurrentPortugalCycle(),
            sources = emptyList(),
            searchQueries = emptyList()
        )
        assertEquals(2, result.matches.size)
        assertEquals(2.05, result.matches[0].xgCasaEstimado, 1e-6)
        assertEquals(1.58, result.matches[0].odd1, 1e-6)
    }

    @Test
    fun espnDataSource_convertsAmericanOddsToEuropeanDecimalAccurately() {
        val espn = com.example.data.EspnLiveDataSource()
        assertEquals(1.67, espn.americanToDecimal(-150.0), 0.01)
        assertEquals(4.00, espn.americanToDecimal(300.0), 0.01)
        assertEquals(2.00, espn.americanToDecimal(100.0), 0.01)
    }

    @Test
    fun cycleDayPresets_returnCorrectFriSunAndMonThuDatesAndStrictCycleInfo() {
        val lisbonTz = java.util.TimeZone.getTimeZone("Europe/Lisbon")
        val thuCal = java.util.Calendar.getInstance(lisbonTz).apply {
            set(2026, java.util.Calendar.NOVEMBER, 19, 11, 0, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }
        val thuMillis = thuCal.timeInMillis

        val selectable = QuantEngine.buildSelectableDays(nowMillis = thuMillis, dayCount = 10)
        assertEquals(10, selectable.size)
        assertEquals("2026-11-19", selectable.first().isoDate)
        assertEquals("Qui", selectable.first().shortWeekday)

        val weekendDates = QuantEngine.getDatesForPreset(com.example.quant.CyclePreset.WEEKEND_FRI_SUN, thuMillis)
        assertEquals(listOf("2026-11-20", "2026-11-21", "2026-11-22"), weekendDates)

        val midweekDates = QuantEngine.getDatesForPreset(com.example.quant.CyclePreset.MIDWEEK_MON_THU, thuMillis)
        assertEquals(listOf("2026-11-23", "2026-11-24", "2026-11-25", "2026-11-26"), midweekDates)

        val todayDates = QuantEngine.getDatesForPreset(com.example.quant.CyclePreset.TODAY, thuMillis)
        assertEquals(listOf("2026-11-19"), todayDates)

        val weekendCycle = QuantEngine.buildCycleInfoForDates(weekendDates, thuMillis)
        assertEquals(com.example.quant.TemporalCycle.CYCLE_B_WEEKEND, weekendCycle.cycle)
        assertEquals("2026-11-20", weekendCycle.startDateIso)
        assertEquals("2026-11-22", weekendCycle.endDateIso)
        assertEquals(weekendDates, weekendCycle.exactDatesIso)
    }

    @Test
    fun espnDateParsing_and_cacheStoreRoundTrip_workReliably() {
        val espn = com.example.data.EspnLiveDataSource()
        assertEquals("20261120", espn.isoToCompactDate("2026-11-20"))
        assertTrue(espn.parseUtcDate("2026-11-24T20:00Z") != null)
        assertTrue(espn.parseUtcDate("2026-11-21T15:00:00Z") != null)
        assertTrue(espn.parseUtcDate("2026-11-21T15:00:00.000Z") != null)

        val cycle = QuantEngine.buildCycleInfoForDates(listOf("2026-11-20", "2026-11-21", "2026-11-22"))
        val original = GroundedFetchResult(
            cycleInfo = cycle,
            matches = listOf(
                RawMatchData("m1", "21/11 18:00 WET", "Liga Portugal Betclic", "Sporting CP", "SC Braga", 2.05, 0.95, "Forma forte", 1780.0, 1660.0, 1.55, 4.20, 5.50, 1.72, 1.44, 1.75),
                RawMatchData("m2", "22/11 20:30 WET", "Liga Portugal Betclic", "SL Benfica", "FC Porto", 1.70, 1.20, "Clássico", 1790.0, 1760.0, 2.05, 3.40, 3.60, 1.85, 1.35, 1.72)
            ),
            sources = listOf(com.example.quant.GroundingSource("ESPN • Liga Portugal", "https://www.espn.com/soccer/scoreboard/_/league/por.1")),
            searchQueries = listOf("ESPN Live Scoreboard API"),
            modelSummaryNote = "Teste cache local"
        )
        val json = com.example.data.AnalysisCacheStore.serializeFetchResult(original)
        val restored = com.example.data.AnalysisCacheStore.deserializeFetchResult(json)
        assertTrue(restored != null)
        assertEquals(2, restored!!.matches.size)
        assertEquals("Sporting CP", restored.matches[0].equipaCasa)
        assertEquals(1.55, restored.matches[0].odd1, 1e-6)
        assertEquals(listOf("2026-11-20", "2026-11-21", "2026-11-22"), restored.cycleInfo.exactDatesIso)
    }

    @Test
    fun basketballQuantEngine_quartersMonteCarloAndCache_workAccurately() {
        val sampleBasket = listOf(
            com.example.quant.RawBasketballMatchData(
                id = "nba1", dataHoraWet = "21/11 00:30 WET", competicao = "NBA",
                equipaCasa = "Boston Celtics", equipaFora = "New York Knicks",
                expectedPtsHome = 118.4, expectedPtsAway = 110.6, paceNotes = "Ritmo alto",
                homeWinRate = 0.72, awayWinRate = 0.58, isNbaFormat = true,
                totalLine = 224.5, homeSpreadLine = -5.5, q1TotalLine = 56.5, htTotalLine = 113.5,
                homeTeamTotalLine = 115.5, altSafeTotalLine = 218.5,
                oddHomeMl = 1.48, oddAwayMl = 2.75, oddHomeSpread = 1.90, oddAwaySpread = 1.88,
                oddTotalOver = 1.90, oddTotalUnder = 1.88, oddQ1Over = 1.88, oddQ1Under = 1.88,
                oddHtOver = 1.87, oddHomeTeamOver = 1.88, oddAltSafeOver = 1.42
            ),
            com.example.quant.RawBasketballMatchData(
                id = "nba2", dataHoraWet = "21/11 03:00 WET", competicao = "NBA",
                equipaCasa = "Los Angeles Lakers", equipaFora = "Denver Nuggets",
                expectedPtsHome = 114.2, expectedPtsAway = 115.8, paceNotes = "Equilibrado",
                homeWinRate = 0.56, awayWinRate = 0.61, isNbaFormat = true,
                totalLine = 228.5, homeSpreadLine = 1.5, q1TotalLine = 57.5, htTotalLine = 115.5,
                homeTeamTotalLine = 113.5, altSafeTotalLine = 222.5,
                oddHomeMl = 2.05, oddAwayMl = 1.80, oddHomeSpread = 1.88, oddAwaySpread = 1.90,
                oddTotalOver = 1.88, oddTotalUnder = 1.88, oddQ1Over = 1.87, oddQ1Under = 1.87,
                oddHtOver = 1.87, oddHomeTeamOver = 1.86, oddAltSafeOver = 1.40
            ),
            com.example.quant.RawBasketballMatchData(
                id = "nba3", dataHoraWet = "21/11 01:00 WET", competicao = "NBA",
                equipaCasa = "Oklahoma City Thunder", equipaFora = "Golden State Warriors",
                expectedPtsHome = 120.5, expectedPtsAway = 112.0, paceNotes = "Ataque rápido",
                homeWinRate = 0.78, awayWinRate = 0.55, isNbaFormat = true,
                totalLine = 229.5, homeSpreadLine = -6.5, q1TotalLine = 57.5, htTotalLine = 115.5,
                homeTeamTotalLine = 118.5, altSafeTotalLine = 223.5,
                oddHomeMl = 1.40, oddAwayMl = 3.05, oddHomeSpread = 1.90, oddAwaySpread = 1.88,
                oddTotalOver = 1.88, oddTotalUnder = 1.88, oddQ1Over = 1.87, oddQ1Under = 1.87,
                oddHtOver = 1.87, oddHomeTeamOver = 1.86, oddAltSafeOver = 1.40
            )
        )

        val cycle = QuantEngine.buildCycleInfoForDates(listOf("2026-11-21"))
        val fetch = com.example.quant.BasketballFetchResult(
            cycleInfo = cycle,
            matches = sampleBasket,
            sources = emptyList(),
            searchQueries = listOf("ESPN Live Basketball API"),
            modelSummaryNote = "Teste Basquetebol"
        )

        val bundle = com.example.quant.BasketballQuantEngine.buildAnalysisBundle(fetch, StakeMode.FREEBET, 5.0)
        assertEquals(3, bundle.matches.size)
        assertEquals(4, bundle.matches[0].quarters.size)
        assertEquals(11, bundle.matches[0].markets.size)
        assertTrue(bundle.matches[0].probHomeWin > 0.65)
        assertEquals(100_000, bundle.monteCarloBase.iterations)
        assertTrue(bundle.monteCarloBase.probAtLeastOneWin in 0.05..1.0)
        assertTrue(bundle.monteCarloBase.expectedNetReturn > 0.0)

        // Verify ESPN US timezone day expansion & Basketball JSON cache round-trip
        val espnBk = com.example.data.EspnBasketballDataSource()
        val expanded = espnBk.expandCompactDaysForUsTimezone(listOf("2026-11-21"))
        assertEquals(listOf("20261120", "20261121"), expanded)

        val json = com.example.data.AnalysisCacheStore.serializeBasketballFetchResult(fetch)
        val restored = com.example.data.AnalysisCacheStore.deserializeBasketballFetchResult(json)
        assertTrue(restored != null)
        assertEquals(3, restored!!.matches.size)
        assertEquals("Boston Celtics", restored.matches[0].equipaCasa)
        assertEquals(56.5, restored.matches[0].q1TotalLine, 1e-6)
    }
}
