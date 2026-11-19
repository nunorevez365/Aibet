package com.example.data

import android.util.Log
import com.example.quant.GroundedFetchResult
import com.example.quant.GroundingSource
import com.example.quant.QuantEngine
import com.example.quant.RawMatchData
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

private const val TAG = "GeminiRepository"
private const val MODEL_NAME = "gemini-3.5-flash-lite"
private const val ENRICH_TIMEOUT_MS = 10_000L

/**
 * Hybrid Real-Time Data Pipeline:
 * 1. Fetches 100% real, confirmed official fixtures, current-season standings (GF/GA/PPG),
 *    and live odds directly from ESPN public soccer APIs (zero API key, zero Gemini quota!).
 * 2. Enriches those confirmed fixtures with Firebase AI Logic (`gemini-3.5-flash-lite`,
 *    Structured JSON Output without quota-heavy Search Grounding tool) for tactical injury notes
 *    and xG/Elo calibration. If the user's Firebase Spark tier hits a 429 quota limit, the app
 *    degrades gracefully and returns the verified live ESPN fixtures immediately with zero errors!
 */
class GeminiRepository(
    private val espnDataSource: EspnLiveDataSource = EspnLiveDataSource()
) {

    private val enrichmentSchema: Schema = Schema.obj(
        properties = mapOf(
            "resumo_tatico" to Schema.string(
                description = "Resumo curto em português do perfil tático desta jornada."
            ),
            "ajustes" to Schema.array(
                items = Schema.obj(
                    properties = mapOf(
                        "id" to Schema.string(description = "ID exato do jogo fornecido"),
                        "xg_casa_ajustado" to Schema.double(description = "xG estimado calibrado para a equipa da casa"),
                        "xg_fora_ajustado" to Schema.double(description = "xG estimado calibrado para a equipa visitante"),
                        "notas_taticas_lesoes" to Schema.string(description = "Contexto tático, ritmo de golos e nota de baixas relevantes em português")
                    )
                )
            )
        )
    )

    // Standard structured JSON model (no Tool.googleSearch() -> avoids Spark limit:0 / 429 grounding cap)
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

    suspend fun fetchGroundedCycleMatches(
        cycleInfo: TemporalCycleInfo = QuantEngine.detectCurrentPortugalCycle(),
        onStageUpdate: ((String) -> Unit)? = null
    ): GroundedFetchResult {
        // Stage 1: Real-time official fixtures & standings via ESPN (zero Gemini quota, server-clock calibrated!)
        onStageUpdate?.invoke("Passo 2/4 • A consultar calendário oficial, classificações e odds reais (ESPN)…")
        val espnPayload = espnDataSource.fetchConfirmedMatches(cycleInfo)

        if (espnPayload.matches.size < 2) {
            val detail = espnPayload.diagnosticError ?: "Poucos jogos oficiais nos dias selecionados."
            throw IllegalStateException(
                "$detail Seleciona o ciclo Sex–Dom, Seg–Qui ou marca mais dias no seletor acima."
            )
        }

        val calibratedCycle = espnPayload.effectiveCycleInfo

        // Stage 2: Optional Gemini tactical enrichment (never blocks if Spark quota hits 429!)
        onStageUpdate?.invoke("Passo 2/4 • A calibrar xG e contexto tático via Gemini AI (${espnPayload.matches.size} jogos reais)…")
        val enrichedResult = tryEnrichWithGemini(
            cycleInfo = calibratedCycle,
            baseMatches = espnPayload.matches,
            sources = espnPayload.sources,
            expandedTo7Days = espnPayload.expandedWindow
        )

        return enrichedResult
    }

    private suspend fun tryEnrichWithGemini(
        cycleInfo: TemporalCycleInfo,
        baseMatches: List<RawMatchData>,
        sources: List<GroundingSource>,
        expandedTo7Days: Boolean
    ): GroundedFetchResult {
        val windowNote = if (expandedTo7Days) {
            "Calendário oficial ESPN alargado para os próximos dias confirmados"
        } else {
            "Calendário oficial ESPN confirmado no ${cycleInfo.cycle.code} (${cycleInfo.startDateIso} a ${cycleInfo.endDateIso})"
        }

        val matchLines = baseMatches.joinToString("\n") { m ->
            "- id=${m.id} | ${m.competicao} | ${m.equipaCasa} vs ${m.equipaFora} | ${m.dataHoraWet} | xG_emp=${m.xgCasaEstimado}/${m.xgForaEstimado} | Elo=${m.eloCasaEstimado.toInt()}/${m.eloForaEstimado.toInt()}"
        }

        val prompt = """
            És um analista quantitativo de futebol. Recebeste abaixo jogos 100% REAIS e confirmados pela ESPN com xG empírico derivado dos golos da época atual.
            Para cada jogo (mantendo exatamente o mesmo id), faz um ajuste fino conservador de xG (±0.25 máximo) se o perfil tático das equipas o justificar e escreve uma nota objetiva em português (máx. 120 caracteres) sobre estilo de jogo, tendência de golos e cuidados com ausências.
            Jogos confirmados:
            $matchLines
        """.trimIndent()

        return try {
            val response = withTimeoutOrNull(ENRICH_TIMEOUT_MS) {
                enrichmentModel.generateContent(prompt)
            }
            if (response == null) {
                Log.w(TAG, "Gemini football enrichment timed out after ${ENRICH_TIMEOUT_MS}ms; using direct ESPN live telemetry")
                return buildDirectEspnResult(
                    cycleInfo,
                    baseMatches,
                    sources,
                    "$windowNote • Modo Direto ESPN ativo — dados oficiais de calendário e classificações carregados em tempo real."
                )
            }
            val jsonStr = response.text?.trim().orEmpty()
            if (jsonStr.isBlank()) {
                return buildDirectEspnResult(cycleInfo, baseMatches, sources, "$windowNote • Motor estatístico direto ativo.")
            }

            val root = JSONObject(jsonStr)
            val summary = root.optString("resumo_tatico", "").trim()
            val ajustesArr = root.optJSONArray("ajustes") ?: JSONArray()
            val ajusteById = HashMap<String, JSONObject>()
            for (i in 0 until ajustesArr.length()) {
                val item = ajustesArr.optJSONObject(i) ?: continue
                val id = item.optString("id", "").trim()
                if (id.isNotBlank()) ajusteById[id] = item
            }

            val merged = baseMatches.map { match ->
                val adj = ajusteById[match.id]
                if (adj == null) {
                    match
                } else {
                    val adjXgH = optFlexibleDouble(adj, "xg_casa_ajustado")
                    val adjXgA = optFlexibleDouble(adj, "xg_fora_ajustado")
                    val note = adj.optString("notas_taticas_lesoes", "").trim()

                    // Conservative blend: 65% empirical ESPN season GF/GA + 35% Gemini tactical calibration
                    val finalXgH = if (!adjXgH.isNaN() && adjXgH in 0.35..3.80) {
                        kotlin.math.round((match.xgCasaEstimado * 0.65 + adjXgH * 0.35) * 100.0) / 100.0
                    } else {
                        match.xgCasaEstimado
                    }
                    val finalXgA = if (!adjXgA.isNaN() && adjXgA in 0.35..3.80) {
                        kotlin.math.round((match.xgForaEstimado * 0.65 + adjXgA * 0.35) * 100.0) / 100.0
                    } else {
                        match.xgForaEstimado
                    }
                    val mergedNotes = if (note.isNotBlank()) "$note • ${match.lesoesChave}" else match.lesoesChave

                    match.copy(
                        xgCasaEstimado = finalXgH,
                        xgForaEstimado = finalXgA,
                        lesoesChave = mergedNotes
                    )
                }
            }

            GroundedFetchResult(
                cycleInfo = cycleInfo,
                matches = merged,
                sources = sources,
                searchQueries = listOf("ESPN Live Scoreboard", "ESPN Season Standings", "Gemini $MODEL_NAME"),
                modelSummaryNote = if (summary.isNotBlank()) "$windowNote • $summary" else "$windowNote • Calibrado com Gemini AI."
            )
        } catch (e: TimeoutCancellationException) {
            Log.w(TAG, "Gemini football enrichment timed out (${e.message}); proceeding with direct ESPN live telemetry")
            buildDirectEspnResult(
                cycleInfo,
                baseMatches,
                sources,
                "$windowNote • Modo Direto ESPN ativo — dados oficiais de calendário e classificações carregados em tempo real."
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Seamless resilience: if Firebase Spark hits 429 quota or App Check 403 on an unregistered debug phone,
            // continue immediately with 100% real ESPN live data + local QuantEngine math!
            Log.w(TAG, "Gemini enrichment unavailable (${e.message}); proceeding with direct ESPN live telemetry")
            val lower = e.message.orEmpty().lowercase()
            val reason = when {
                lower.contains("429") || lower.contains("quota") || lower.contains("resource_exhausted") || lower.contains("limit") ->
                    "Quota Gemini em pausa — motor quantitativo a operar com dados oficiais ESPN em tempo real."
                lower.contains("403") || lower.contains("app check") ->
                    "Modo Direto ESPN ativo (App Check em debug local) — jogos, xG empírico e odds reais carregados."
                else ->
                    "Modo Direto ESPN ativo — dados oficiais de calendário e classificações carregados em tempo real."
            }
            buildDirectEspnResult(cycleInfo, baseMatches, sources, "$windowNote • $reason")
        }
    }

    private fun buildDirectEspnResult(
        cycleInfo: TemporalCycleInfo,
        matches: List<RawMatchData>,
        sources: List<GroundingSource>,
        note: String
    ): GroundedFetchResult {
        return GroundedFetchResult(
            cycleInfo = cycleInfo,
            matches = matches,
            sources = sources,
            searchQueries = listOf("ESPN Live Scoreboard API", "ESPN Standings (GF/GA/PPG)"),
            modelSummaryNote = note
        )
    }

    internal fun parseGroundedJson(
        rawText: String,
        cycleInfo: TemporalCycleInfo,
        sources: List<GroundingSource>,
        searchQueries: List<String>
    ): GroundedFetchResult {
        val cleaned = stripMarkdownFences(rawText)
        val (summaryNote, jogosArray) = extractSummaryAndMatchesArray(cleaned)

        val matches = mutableListOf<RawMatchData>()
        for (i in 0 until jogosArray.length()) {
            val obj = jogosArray.optJSONObject(i) ?: continue
            val home = obj.optString("equipa_casa", "").trim()
            val away = obj.optString("equipa_fora", "").trim()
            val comp = obj.optString("competicao", "").trim()
            val kickoff = obj.optString("data_hora_wet", "").trim()

            if (home.isBlank() || away.isBlank() || home.equals(away, ignoreCase = true)) continue

            val xgHome = optFlexibleDouble(obj, "xg_casa_estimado")
            val xgAway = optFlexibleDouble(obj, "xg_fora_estimado")
            val odd1 = optFlexibleDouble(obj, "odd_1")
            val oddX = optFlexibleDouble(obj, "odd_x")
            val odd2 = optFlexibleDouble(obj, "odd_2")
            val oddO25 = optFlexibleDouble(obj, "odd_over25")
            val oddU35 = optFlexibleDouble(obj, "odd_under35")
            val oddBtts = optFlexibleDouble(obj, "odd_btts")

            if (xgHome.isNaN() || xgAway.isNaN() || odd1 <= 1.01 || oddX <= 1.01 || odd2 <= 1.01) {
                continue
            }

            matches.add(
                RawMatchData(
                    id = "match_${i}_${home.lowercase().take(4)}_${away.lowercase().take(4)}",
                    dataHoraWet = kickoff.ifBlank { cycleInfo.startDateIso },
                    competicao = comp.ifBlank { cycleInfo.cycle.code },
                    equipaCasa = home,
                    equipaFora = away,
                    xgCasaEstimado = xgHome.coerceIn(0.30, 4.00),
                    xgForaEstimado = xgAway.coerceIn(0.30, 4.00),
                    lesoesChave = obj.optString("lesoes_chave", "Sem baixas confirmadas de destaque").trim(),
                    eloCasaEstimado = optFlexibleDouble(obj, "elo_casa_estimado").takeIf { !it.isNaN() } ?: 1650.0,
                    eloForaEstimado = optFlexibleDouble(obj, "elo_fora_estimado").takeIf { !it.isNaN() } ?: 1620.0,
                    odd1 = odd1,
                    oddX = oddX,
                    odd2 = odd2,
                    oddOver25 = if (oddO25.isNaN() || oddO25 <= 1.01) 1.85 else oddO25,
                    oddUnder35 = if (oddU35.isNaN() || oddU35 <= 1.01) 1.38 else oddU35,
                    oddBtts = if (oddBtts.isNaN() || oddBtts <= 1.01) 1.80 else oddBtts
                )
            )
        }

        if (matches.size < 2) {
            throw IllegalStateException("Não foram encontrados jogos suficientes confirmados ($summaryNote).")
        }

        return GroundedFetchResult(
            cycleInfo = cycleInfo,
            matches = matches,
            sources = sources,
            searchQueries = searchQueries,
            modelSummaryNote = summaryNote
        )
    }

    private fun stripMarkdownFences(raw: String): String {
        val trimmed = raw.trim()
        if (!trimmed.contains("```")) return trimmed
        val fenceRegex = Regex("```(?:json|JSON)?\\s*([\\s\\S]*?)```")
        val match = fenceRegex.find(trimmed)
        return match?.groupValues?.getOrNull(1)?.trim() ?: trimmed
    }

    private fun extractSummaryAndMatchesArray(text: String): Pair<String, JSONArray> {
        val trimmed = text.trim()
        val firstObj = trimmed.indexOf('{')
        val lastObj = trimmed.lastIndexOf('}')
        val firstArr = trimmed.indexOf('[')
        val lastArr = trimmed.lastIndexOf(']')

        if (firstObj >= 0 && lastObj > firstObj && (firstArr < 0 || firstObj < firstArr)) {
            val root = JSONObject(trimmed.substring(firstObj, lastObj + 1))
            val summary = root.optString("resumo_ciclo", "Dados confirmados.")
            val arr = root.optJSONArray("jogos")
                ?: root.optJSONArray("matches")
                ?: JSONArray()
            return summary to arr
        }
        if (firstArr >= 0 && lastArr > firstArr) {
            return "Dados confirmados." to JSONArray(trimmed.substring(firstArr, lastArr + 1))
        }
        throw IllegalStateException("A resposta não contém um bloco JSON estruturado.")
    }

    private fun optFlexibleDouble(obj: JSONObject, key: String): Double {
        if (!obj.has(key) || obj.isNull(key)) return Double.NaN
        val direct = obj.optDouble(key, Double.NaN)
        if (!direct.isNaN()) return direct
        val asText = obj.optString(key, "")
            .trim()
            .replace(',', '.')
            .replace(Regex("[^0-9.\\-]"), "")
        return asText.toDoubleOrNull() ?: Double.NaN
    }
}
