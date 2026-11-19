package com.example.data

import android.util.Log
import com.example.quant.GroundingSource
import com.example.quant.RawTennisMatchData
import com.example.quant.TemporalCycleInfo
import com.example.quant.TennisFetchResult
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

private const val TAG = "TennisRepository"
private const val MODEL_NAME = "gemini-3.5-flash-lite"
private const val ENRICH_TIMEOUT_MS = 10_000L

/**
 * Hybrid Real-Time Tennis Data Pipeline:
 * 1. Fetches real confirmed ATP/WTA matches, official rankings, and surface profiles from ESPN.
 * 2. Optionally enriches serve-hold % and surface/fatigue notes via Firebase AI Logic
 *    (`gemini-3.5-flash-lite`, structured JSON), degrading cleanly to Direct ESPN mode on 429/403.
 */
class TennisRepository(
    private val espnTennis: EspnTennisDataSource = EspnTennisDataSource()
) {

    private val enrichmentSchema: Schema = Schema.obj(
        properties = mapOf(
            "resumo_jornada" to Schema.string(
                description = "Resumo curto em português sobre a superfície, serviço e tendência de jogos/sets nesta jornada ATP/WTA."
            ),
            "ajustes" to Schema.array(
                items = Schema.obj(
                    properties = mapOf(
                        "id" to Schema.string(description = "ID exato do encontro fornecido"),
                        "hold1_ajustado" to Schema.double(description = "Probabilidade calibrada de hold de serviço do Jogador 1 (entre 0.50 e 0.92)"),
                        "hold2_ajustado" to Schema.double(description = "Probabilidade calibrada de hold de serviço do Jogador 2 (entre 0.50 e 0.92)"),
                        "nota_piso_forma" to Schema.string(description = "Nota objetiva em português sobre serviço, resposta, piso e forma recente")
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

    suspend fun fetchTennisMatches(
        cycleInfo: TemporalCycleInfo,
        onStageUpdate: ((String) -> Unit)? = null
    ): TennisFetchResult {
        onStageUpdate?.invoke("Passo 2/4 • A consultar encontros oficiais ATP/WTA, rankings e superfícies (ESPN)…")
        val payload = espnTennis.fetchConfirmedMatches(cycleInfo)

        if (payload.matches.size < 2) {
            val detail = payload.diagnosticError ?: "Poucos encontros oficiais de Ténis nos dias selecionados."
            throw IllegalStateException(
                "$detail Seleciona o ciclo Sex–Dom, Seg–Qui ou marca mais dias no seletor acima."
            )
        }

        onStageUpdate?.invoke("Passo 2/4 • A calibrar serviço (hold %), piso e forma via Gemini AI (${payload.matches.size} encontros)…")
        return tryEnrichWithGemini(payload.effectiveCycleInfo, payload.matches, payload.sources)
    }

    private suspend fun tryEnrichWithGemini(
        cycleInfo: TemporalCycleInfo,
        baseMatches: List<RawTennisMatchData>,
        sources: List<GroundingSource>
    ): TennisFetchResult {
        val windowNote = "Calendário oficial Ténis ATP/WTA (${cycleInfo.startDateIso} a ${cycleInfo.endDateIso})"
        val lines = baseMatches.joinToString("\n") { m ->
            "- id=${m.id} | ${m.circuito} ${m.torneio} (${m.superficie.label}, Bo${m.bestOfSets}) | ${m.jogador1} (#${m.ranking1 ?: "?"}) vs ${m.jogador2} (#${m.ranking2 ?: "?"}) | HoldBase=${m.serveHoldProb1}/${m.serveHoldProb2}"
        }

        val prompt = """
            És um analista quantitativo de Ténis (ATP/WTA). Recebeste encontros 100% REAIS confirmados pela ESPN com estimativa base de hold de serviço por ranking e piso.
            Para cada encontro (mantendo o mesmo id), faz um ajuste fino conservador nas probabilidades de hold de serviço (±0.04 máximo) com base no perfil de serviço/resposta no piso indicado, e escreve uma nota curta em português (máx. 110 caracteres).
            Encontros:
            $lines
        """.trimIndent()

        return try {
            val response = withTimeoutOrNull(ENRICH_TIMEOUT_MS) {
                enrichmentModel.generateContent(prompt)
            }
            if (response == null) {
                Log.w(TAG, "Gemini tennis enrichment timed out after ${ENRICH_TIMEOUT_MS}ms; using direct ESPN live data")
                return buildDirectResult(
                    cycleInfo,
                    baseMatches,
                    sources,
                    "$windowNote • Modo Direto ESPN ativo (Rankings ATP/WTA, modelo Markov de serviço/sets e linhas reais)."
                )
            }
            val jsonStr = response.text?.trim().orEmpty()
            if (jsonStr.isBlank()) {
                return buildDirectResult(cycleInfo, baseMatches, sources, "$windowNote • Motor Markov direto ativo.")
            }

            val root = JSONObject(jsonStr)
            val summary = root.optString("resumo_jornada", "").trim()
            val arr = root.optJSONArray("ajustes") ?: JSONArray()
            val byId = HashMap<String, JSONObject>()
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val id = item.optString("id", "").trim()
                if (id.isNotBlank()) byId[id] = item
            }

            val merged = baseMatches.map { match ->
                val adj = byId[match.id] ?: return@map match
                val h1Adj = adj.optDouble("hold1_ajustado", Double.NaN)
                val h2Adj = adj.optDouble("hold2_ajustado", Double.NaN)
                val note = adj.optString("nota_piso_forma", "").trim()

                val finalH1 = if (!h1Adj.isNaN() && h1Adj in 0.48..0.93) {
                    round((match.serveHoldProb1 * 0.72 + h1Adj * 0.28) * 1000.0) / 1000.0
                } else {
                    match.serveHoldProb1
                }
                val finalH2 = if (!h2Adj.isNaN() && h2Adj in 0.48..0.93) {
                    round((match.serveHoldProb2 * 0.72 + h2Adj * 0.28) * 1000.0) / 1000.0
                } else {
                    match.serveHoldProb2
                }
                val mergedNotes = if (note.isNotBlank()) "$note • ${match.surfaceNotes}" else match.surfaceNotes

                match.copy(
                    serveHoldProb1 = finalH1,
                    serveHoldProb2 = finalH2,
                    surfaceNotes = mergedNotes
                )
            }

            TennisFetchResult(
                cycleInfo = cycleInfo,
                matches = merged,
                sources = sources,
                searchQueries = listOf("ESPN ATP/WTA Scoreboard", "ESPN Tennis Rankings", "Gemini $MODEL_NAME"),
                modelSummaryNote = if (summary.isNotBlank()) "$windowNote • $summary" else "$windowNote • Calibrado com Gemini AI."
            )
        } catch (e: TimeoutCancellationException) {
            Log.w(TAG, "Gemini tennis enrichment timed out (${e.message}); using direct ESPN live data")
            buildDirectResult(
                cycleInfo,
                baseMatches,
                sources,
                "$windowNote • Modo Direto ESPN ativo (Rankings ATP/WTA, modelo Markov de serviço/sets e linhas reais)."
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Gemini tennis enrichment unavailable (${e.message}); using direct ESPN live data")
            buildDirectResult(
                cycleInfo,
                baseMatches,
                sources,
                "$windowNote • Modo Direto ESPN ativo (Rankings ATP/WTA, modelo Markov de serviço/sets e linhas reais)."
            )
        }
    }

    private fun buildDirectResult(
        cycleInfo: TemporalCycleInfo,
        matches: List<RawTennisMatchData>,
        sources: List<GroundingSource>,
        note: String
    ): TennisFetchResult {
        return TennisFetchResult(
            cycleInfo = cycleInfo,
            matches = matches,
            sources = sources,
            searchQueries = listOf("ESPN Live ATP/WTA API", "ESPN Official Rankings"),
            modelSummaryNote = note
        )
    }
}
