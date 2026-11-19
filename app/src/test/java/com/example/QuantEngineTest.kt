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
}
