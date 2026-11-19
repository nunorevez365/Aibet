package com.example.data

import android.util.Log
import com.example.quant.BasketballFetchResult
import com.example.quant.GroundingSource
import com.example.quant.RawBasketballMatchData
import com.example.quant.TemporalCycleInfo
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.Schema
import com.google.firebase.ai.type.generationConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.round

private const val TAG = "BasketballRepository"
private const val MODEL_NAME = "gemini-3.5-flash-lite"
private const val ENRICH_TIMEOUT_MS = 10_000L

/**
 * Hybrid Real-Time Basketball Data Pipeline:
 * 1. Fetches real confirmed NBA/WNBA/NCAA fixtures, PPG/OPPG standings, and live Spread/Over-Under
 *    lines from ESPN public APIs (zero API key, zero Gemini quota).
 * 2. Optionally enriches pace/back-to-back/star-injury adjustments via Firebase AI Logic
 *    (`gemini-3.5-flash-lite`, structured JSON), degrading seamlessly on 429/403 so live
 *    ESPN basketball data always loads cleanly.
 */
class BasketballRepository(
    private val espnBasketball: EspnBasketballDataSource = EspnBasketballDataSource()
) {

    private val enrichmentSchema: Schema = Schema.obj(
        properties = mapOf(
            "resumo_ritmo" to Schema.string(
                description = "Resumo curto em português do ritmo (pace) e perfil de pontos desta jornada de basquetebol."
            ),
            "ajustes" to Schema.array(
                items = Schema.obj(
                    properties = mapOf(
                        "id" to Schema.string(description = "ID exato do jogo fornecido"),
                        "pts_casa_ajustado" to Schema.double(description = "Projeção calibrada de pontos da equipa da casa"),
                        "pts_fora_ajustado" to Schema.double(description = "Projeção calibrada de pontos da equipa visitante"),
                        "nota_ritmo_baixas" to Schema.string(description = "Nota curta sobre ritmo (pace), tendência de Q1/Over e baixas relevantes em português")
                    )
                )
            )
        )
    )

    private val enrichmentModel by lazy {
        Firebase.ai.generativeModel(
            modelName = MODEL_NAME,
            generationConfig = generationConfig {
                temperature = 0.2f
                responseMimeType = "application/json"
                responseSchema = enrichmentSchema
            }
        )
    }

    suspend fun fetchBasketballMatches(
        cycleInfo: TemporalCycleInfo,
        onStageUpdate: ((String) -> Unit)? = null
    ): BasketballFetchResult {
        onStageUpdate?.invoke("Passo 2/4 • A consultar jogos oficiais, médias PPG e linhas NBA/NCAA (ESPN)…")
        val payload = espnBasketball.fetchConfirmedMatches(cycleInfo)

        if (payload.matches.size < 2) {
            val detail = payload.diagnosticError ?: "Poucos jogos oficiais de Basquetebol nos dias selecionados."
            throw IllegalStateException(
                "$detail Seleciona o ciclo Sex–Dom, Seg–Qui ou marca mais dias no seletor acima."
            )
        }

        onStageUpdate?.invoke("Passo 2/4 • A calibrar ritmo (pace) e projeção por quartos via Gemini AI (${payload.matches.size} jogos)…")
        return tryEnrichWithGemini(payload.effectiveCycleInfo, payload.matches, payload.sources)
    }

    private suspend fun tryEnrichWithGemini(
        cycleInfo: TemporalCycleInfo,
        baseMatches: List<RawBasketballMatchData>,
        sources: List<GroundingSource>
    ): BasketballFetchResult {
        val windowNote = "Calendário oficial Basquetebol (${cycleInfo.startDateIso} a ${cycleInfo.endDateIso})"
        val lines = baseMatches.joinToString("\n") { m ->
            "- id=${m.id} | ${m.competicao} | ${m.equipaCasa} vs ${m.equipaFora} | ${m.dataHoraWet} | Proj=${m.expectedPtsHome}/${m.expectedPtsAway} | O/U=${m.totalLine} | Spread=${m.homeSpreadLine}"
        }

        val prompt = """
            És um analista quantitativo de basquetebol (NBA/NCAA). Recebeste jogos 100% REAIS confirmados pela ESPN com projeção empírica de pontos.
            Para cada jogo (mantendo o mesmo id), faz um ajuste fino conservador de pontos (±4.0 pts máximo) se o ritmo (pace), back-to-back ou perfil defensivo o justificar, e escreve uma nota objetiva em português (máx. 110 caracteres) sobre tendência de pontos no 1.º Quarto e ritmo.
            Jogos:
            $lines
        """.trimIndent()

        return try {
            val response = withTimeoutOrNull(ENRICH_TIMEOUT_MS) {
                enrichmentModel.generateContent(prompt)
            }
            if (response == null) {
                Log.w(TAG, "Gemini basketball enrichment timed out after ${ENRICH_TIMEOUT_MS}ms; using direct ESPN live data")
                return buildDirectResult(
                    cycleInfo,
                    baseMatches,
                    sources,
                    "$windowNote • Modo Direto ESPN ativo (PPG/OPPG, quartos Q1–Q4 e linhas reais)."
                )
            }
            val jsonStr = response.text?.trim().orEmpty()
            if (jsonStr.isBlank()) {
                return buildDirectResult(cycleInfo, baseMatches, sources, "$windowNote • Motor estatístico direto ativo.")
            }

            val root = JSONObject(jsonStr)
            val summary = root.optString("resumo_ritmo", "").trim()
            val ajustesArr = root.optJSONArray("ajustes") ?: JSONArray()
            val ajusteById = HashMap<String, JSONObject>()
            for (i in 0 until ajustesArr.length()) {
                val item = ajustesArr.optJSONObject(i) ?: continue
                val id = item.optString("id", "").trim()
                if (id.isNotBlank()) ajusteById[id] = item
            }

            val merged = baseMatches.map { match ->
                val adj = ajusteById[match.id] ?: return@map match
                val adjH = adj.optDouble("pts_casa_ajustado", Double.NaN)
                val adjA = adj.optDouble("pts_fora_ajustado", Double.NaN)
                val note = adj.optString("nota_ritmo_baixas", "").trim()

                val minPts = if (match.isNbaFormat) 85.0 else 50.0
                val maxPts = if (match.isNbaFormat) 140.0 else 105.0

                // Conservative blend: 70% empirical ESPN PPG/OPPG + 30% Gemini pace adjustment
                val finalH = if (!adjH.isNaN() && adjH in minPts..maxPts) {
                    round((match.expectedPtsHome * 0.70 + adjH * 0.30) * 10.0) / 10.0
                } else {
                    match.expectedPtsHome
                }
                val finalA = if (!adjA.isNaN() && adjA in minPts..maxPts) {
                    round((match.expectedPtsAway * 0.70 + adjA * 0.30) * 10.0) / 10.0
                } else {
                    match.expectedPtsAway
                }
                val mergedNotes = if (note.isNotBlank()) "$note • ${match.paceNotes}" else match.paceNotes

                match.copy(
                    expectedPtsHome = finalH,
                    expectedPtsAway = finalA,
                    paceNotes = mergedNotes
                )
            }

            BasketballFetchResult(
                cycleInfo = cycleInfo,
                matches = merged,
                sources = sources,
                searchQueries = listOf("ESPN Basketball Scoreboard", "ESPN PPG/OPPG Standings", "Gemini $MODEL_NAME"),
                modelSummaryNote = if (summary.isNotBlank()) "$windowNote • $summary" else "$windowNote • Calibrado com Gemini AI."
            )
        } catch (e: TimeoutCancellationException) {
            Log.w(TAG, "Gemini basketball enrichment timed out (${e.message}); using direct ESPN live data")
            buildDirectResult(
                cycleInfo,
                baseMatches,
                sources,
                "$windowNote • Modo Direto ESPN ativo (PPG/OPPG, quartos Q1–Q4 e linhas reais)."
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Gemini basketball enrichment unavailable (${e.message}); using direct ESPN live data")
            buildDirectResult(
                cycleInfo,
                baseMatches,
                sources,
                "$windowNote • Modo Direto ESPN ativo (PPG/OPPG, quartos Q1–Q4 e linhas reais)."
            )
        }
    }

    private fun buildDirectResult(
        cycleInfo: TemporalCycleInfo,
        matches: List<RawBasketballMatchData>,
        sources: List<GroundingSource>,
        note: String
    ): BasketballFetchResult {
        return BasketballFetchResult(
            cycleInfo = cycleInfo,
            matches = matches,
            sources = sources,
            searchQueries = listOf("ESPN Live Basketball API", "ESPN Standings (PPG/OPPG)"),
            modelSummaryNote = note
        )
    }
}
