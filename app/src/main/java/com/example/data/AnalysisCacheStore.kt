package com.example.data

import android.content.Context
import com.example.quant.BasketballFetchResult
import com.example.quant.CyclePreset
import com.example.quant.GroundedFetchResult
import com.example.quant.GroundingSource
import com.example.quant.QuantEngine
import com.example.quant.RawBasketballMatchData
import com.example.quant.RawMatchData
import com.example.quant.RawTennisMatchData
import com.example.quant.SportMode
import com.example.quant.StakeMode
import com.example.quant.TemporalCycle
import com.example.quant.TemporalCycleInfo
import com.example.quant.TennisFetchResult
import com.example.quant.TennisSurface
import org.json.JSONArray
import org.json.JSONObject

data class SavedUserSettings(
    val sportMode: SportMode,
    val stakeMode: StakeMode,
    val budgetAmount: Double,
    val useFullLeverageSlip3: Boolean,
    val activePreset: CyclePreset,
    val selectedDatesIso: Set<String>
)

/**
 * Automatically persists both Football (slot 1) and Basketball (slot 2) latest analyses
 * in Room and user bankroll/sport/cycle preferences in SharedPreferences so everything
 * survives app restarts and offline periods seamlessly.
 */
class AnalysisCacheStore(
    context: Context,
    private val cachedAnalysisDao: CachedAnalysisDao
) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun saveSettings(settings: SavedUserSettings) {
        prefs.edit()
            .putString(KEY_SPORT_MODE, settings.sportMode.name)
            .putString(KEY_STAKE_MODE, settings.stakeMode.name)
            .putFloat(KEY_BUDGET_AMOUNT, settings.budgetAmount.toFloat())
            .putBoolean(KEY_FULL_LEVERAGE, settings.useFullLeverageSlip3)
            .putString(KEY_ACTIVE_PRESET, settings.activePreset.name)
            .putStringSet(KEY_SELECTED_DATES, settings.selectedDatesIso)
            .apply()
    }

    fun loadSettings(validSelectableIsoDates: Set<String>): SavedUserSettings? {
        if (!prefs.contains(KEY_STAKE_MODE)) return null
        val sport = runCatching {
            SportMode.valueOf(prefs.getString(KEY_SPORT_MODE, SportMode.FOOTBALL.name)!!)
        }.getOrDefault(SportMode.FOOTBALL)

        val mode = runCatching {
            StakeMode.valueOf(prefs.getString(KEY_STAKE_MODE, StakeMode.FREEBET.name)!!)
        }.getOrDefault(StakeMode.FREEBET)

        val budget = prefs.getFloat(KEY_BUDGET_AMOUNT, 5.0f).toDouble().coerceIn(1.0, 50_000.0)
        val fullLev = prefs.getBoolean(KEY_FULL_LEVERAGE, false)
        val preset = runCatching {
            CyclePreset.valueOf(prefs.getString(KEY_ACTIVE_PRESET, CyclePreset.WEEKEND_FRI_SUN.name)!!)
        }.getOrDefault(CyclePreset.WEEKEND_FRI_SUN)

        val storedDates = prefs.getStringSet(KEY_SELECTED_DATES, emptySet()).orEmpty()
        val validDates = storedDates.filter { it in validSelectableIsoDates }.toSet()
        val finalDates = if (validDates.isNotEmpty()) {
            validDates
        } else {
            val fallbackPreset = if (preset == CyclePreset.CUSTOM) CyclePreset.WEEKEND_FRI_SUN else preset
            QuantEngine.getDatesForPreset(fallbackPreset).toSet()
        }

        return SavedUserSettings(
            sportMode = sport,
            stakeMode = mode,
            budgetAmount = budget,
            useFullLeverageSlip3 = fullLev,
            activePreset = if (validDates.isNotEmpty()) preset else CyclePreset.WEEKEND_FRI_SUN,
            selectedDatesIso = finalDates
        )
    }

    suspend fun saveLatestAnalysis(result: GroundedFetchResult) {
        val json = serializeFetchResult(result)
        val entity = CachedAnalysisEntity(
            id = SLOT_FOOTBALL,
            updatedAtMillis = System.currentTimeMillis(),
            cycleCode = result.cycleInfo.cycle.code,
            startDateIso = result.cycleInfo.startDateIso,
            endDateIso = result.cycleInfo.endDateIso,
            exactDatesCsv = result.cycleInfo.exactDatesIso.joinToString(","),
            payloadJson = json
        )
        cachedAnalysisDao.upsert(entity)
    }

    suspend fun loadLatestAnalysis(): GroundedFetchResult? {
        val entity = cachedAnalysisDao.getBySlot(SLOT_FOOTBALL) ?: return null
        return runCatching { deserializeFetchResult(entity.payloadJson) }.getOrNull()
    }

    suspend fun saveLatestBasketballAnalysis(result: BasketballFetchResult) {
        val json = serializeBasketballFetchResult(result)
        val entity = CachedAnalysisEntity(
            id = SLOT_BASKETBALL,
            updatedAtMillis = System.currentTimeMillis(),
            cycleCode = result.cycleInfo.cycle.code,
            startDateIso = result.cycleInfo.startDateIso,
            endDateIso = result.cycleInfo.endDateIso,
            exactDatesCsv = result.cycleInfo.exactDatesIso.joinToString(","),
            payloadJson = json
        )
        cachedAnalysisDao.upsert(entity)
    }

    suspend fun loadLatestBasketballAnalysis(): BasketballFetchResult? {
        val entity = cachedAnalysisDao.getBySlot(SLOT_BASKETBALL) ?: return null
        return runCatching { deserializeBasketballFetchResult(entity.payloadJson) }.getOrNull()
    }

    suspend fun saveLatestTennisAnalysis(result: TennisFetchResult) {
        val json = serializeTennisFetchResult(result)
        val entity = CachedAnalysisEntity(
            id = SLOT_TENNIS,
            updatedAtMillis = System.currentTimeMillis(),
            cycleCode = result.cycleInfo.cycle.code,
            startDateIso = result.cycleInfo.startDateIso,
            endDateIso = result.cycleInfo.endDateIso,
            exactDatesCsv = result.cycleInfo.exactDatesIso.joinToString(","),
            payloadJson = json
        )
        cachedAnalysisDao.upsert(entity)
    }

    suspend fun loadLatestTennisAnalysis(): TennisFetchResult? {
        val entity = cachedAnalysisDao.getBySlot(SLOT_TENNIS) ?: return null
        return runCatching { deserializeTennisFetchResult(entity.payloadJson) }.getOrNull()
    }

    companion object {
        private const val SLOT_FOOTBALL = 1
        private const val SLOT_BASKETBALL = 2
        private const val SLOT_TENNIS = 3

        private const val PREFS_NAME = "betquant_prefs"
        private const val KEY_SPORT_MODE = "sport_mode"
        private const val KEY_STAKE_MODE = "stake_mode"
        private const val KEY_BUDGET_AMOUNT = "budget_amount"
        private const val KEY_FULL_LEVERAGE = "full_leverage_slip3"
        private const val KEY_ACTIVE_PRESET = "active_preset"
        private const val KEY_SELECTED_DATES = "selected_dates_iso"

        private fun encodeCycleInfo(cycleInfo: TemporalCycleInfo): JSONObject = JSONObject().apply {
            put("cycleName", cycleInfo.cycle.name)
            put("currentTimeWet", cycleInfo.currentTimeWet)
            put("timezoneLabel", cycleInfo.timezoneLabel)
            put("startDateIso", cycleInfo.startDateIso)
            put("endDateIso", cycleInfo.endDateIso)
            put("exactDatesIso", JSONArray(cycleInfo.exactDatesIso))
        }

        private fun decodeCycleInfo(cycleObj: JSONObject): TemporalCycleInfo {
            val cycle = runCatching {
                TemporalCycle.valueOf(cycleObj.optString("cycleName", TemporalCycle.CYCLE_B_WEEKEND.name))
            }.getOrDefault(TemporalCycle.CYCLE_B_WEEKEND)

            val exactDates = mutableListOf<String>()
            val exactDatesArr = cycleObj.optJSONArray("exactDatesIso")
            if (exactDatesArr != null) {
                for (i in 0 until exactDatesArr.length()) {
                    val d = exactDatesArr.optString(i, "")
                    if (d.isNotBlank()) exactDates.add(d)
                }
            }
            val startIso = cycleObj.optString("startDateIso", exactDates.firstOrNull().orEmpty())
            val endIso = cycleObj.optString("endDateIso", exactDates.lastOrNull().orEmpty())
            return TemporalCycleInfo(
                cycle = cycle,
                currentTimeWet = cycleObj.optString("currentTimeWet", ""),
                timezoneLabel = cycleObj.optString("timezoneLabel", "WET (UTC+0)"),
                startDateIso = startIso,
                endDateIso = endIso,
                exactDatesIso = if (exactDates.isNotEmpty()) exactDates else listOfNotNull(startIso.takeIf { it.isNotBlank() })
            )
        }

        internal fun serializeFetchResult(result: GroundedFetchResult): String {
            val root = JSONObject()
            root.put("cycleInfo", encodeCycleInfo(result.cycleInfo))
            root.put("modelSummaryNote", result.modelSummaryNote)
            root.put("searchQueries", JSONArray(result.searchQueries))

            val sourcesArr = JSONArray()
            result.sources.forEach { src ->
                sourcesArr.put(JSONObject().apply { put("title", src.title); put("uri", src.uri) })
            }
            root.put("sources", sourcesArr)

            val matchesArr = JSONArray()
            result.matches.forEach { m ->
                matchesArr.put(
                    JSONObject().apply {
                        put("id", m.id)
                        put("dataHoraWet", m.dataHoraWet)
                        put("competicao", m.competicao)
                        put("equipaCasa", m.equipaCasa)
                        put("equipaFora", m.equipaFora)
                        put("xgCasaEstimado", m.xgCasaEstimado)
                        put("xgForaEstimado", m.xgForaEstimado)
                        put("lesoesChave", m.lesoesChave)
                        put("eloCasaEstimado", m.eloCasaEstimado)
                        put("eloForaEstimado", m.eloForaEstimado)
                        put("odd1", m.odd1)
                        put("oddX", m.oddX)
                        put("odd2", m.odd2)
                        put("oddOver25", m.oddOver25)
                        put("oddUnder35", m.oddUnder35)
                        put("oddBtts", m.oddBtts)
                        put("fontesVerificadas", JSONArray(m.fontesVerificadas))
                    }
                )
            }
            root.put("matches", matchesArr)
            return root.toString()
        }

        internal fun deserializeFetchResult(jsonString: String): GroundedFetchResult? {
            val root = JSONObject(jsonString)
            val cycleObj = root.optJSONObject("cycleInfo") ?: return null
            val cycleInfo = decodeCycleInfo(cycleObj)

            val sources = mutableListOf<GroundingSource>()
            val sourcesArr = root.optJSONArray("sources")
            if (sourcesArr != null) {
                for (i in 0 until sourcesArr.length()) {
                    val s = sourcesArr.optJSONObject(i) ?: continue
                    sources.add(GroundingSource(s.optString("title", ""), s.optString("uri", "")))
                }
            }

            val queries = mutableListOf<String>()
            val queriesArr = root.optJSONArray("searchQueries")
            if (queriesArr != null) {
                for (i in 0 until queriesArr.length()) {
                    val q = queriesArr.optString(i, "")
                    if (q.isNotBlank()) queries.add(q)
                }
            }

            val matches = mutableListOf<RawMatchData>()
            val matchesArr = root.optJSONArray("matches") ?: return null
            for (i in 0 until matchesArr.length()) {
                val m = matchesArr.optJSONObject(i) ?: continue
                val verified = mutableListOf<String>()
                val vArr = m.optJSONArray("fontesVerificadas")
                if (vArr != null) {
                    for (j in 0 until vArr.length()) {
                        val v = vArr.optString(j, "")
                        if (v.isNotBlank()) verified.add(v)
                    }
                }
                matches.add(
                    RawMatchData(
                        id = m.optString("id", "cached_$i"),
                        dataHoraWet = m.optString("dataHoraWet", ""),
                        competicao = m.optString("competicao", ""),
                        equipaCasa = m.optString("equipaCasa", ""),
                        equipaFora = m.optString("equipaFora", ""),
                        xgCasaEstimado = m.optDouble("xgCasaEstimado", 1.35),
                        xgForaEstimado = m.optDouble("xgForaEstimado", 1.15),
                        lesoesChave = m.optString("lesoesChave", ""),
                        eloCasaEstimado = m.optDouble("eloCasaEstimado", 1650.0),
                        eloForaEstimado = m.optDouble("eloForaEstimado", 1620.0),
                        odd1 = m.optDouble("odd1", 2.00),
                        oddX = m.optDouble("oddX", 3.40),
                        odd2 = m.optDouble("odd2", 3.60),
                        oddOver25 = m.optDouble("oddOver25", 1.85),
                        oddUnder35 = m.optDouble("oddUnder35", 1.38),
                        oddBtts = m.optDouble("oddBtts", 1.80),
                        fontesVerificadas = verified
                    )
                )
            }

            if (matches.size < 2) return null

            return GroundedFetchResult(
                cycleInfo = cycleInfo,
                matches = matches,
                sources = sources,
                searchQueries = queries,
                modelSummaryNote = root.optString("modelSummaryNote", "Análise guardada localmente.")
            )
        }

        internal fun serializeBasketballFetchResult(result: BasketballFetchResult): String {
            val root = JSONObject()
            root.put("cycleInfo", encodeCycleInfo(result.cycleInfo))
            root.put("modelSummaryNote", result.modelSummaryNote)
            root.put("searchQueries", JSONArray(result.searchQueries))

            val sourcesArr = JSONArray()
            result.sources.forEach { src ->
                sourcesArr.put(JSONObject().apply { put("title", src.title); put("uri", src.uri) })
            }
            root.put("sources", sourcesArr)

            val matchesArr = JSONArray()
            result.matches.forEach { m ->
                matchesArr.put(
                    JSONObject().apply {
                        put("id", m.id)
                        put("dataHoraWet", m.dataHoraWet)
                        put("competicao", m.competicao)
                        put("equipaCasa", m.equipaCasa)
                        put("equipaFora", m.equipaFora)
                        put("expectedPtsHome", m.expectedPtsHome)
                        put("expectedPtsAway", m.expectedPtsAway)
                        put("paceNotes", m.paceNotes)
                        put("homeWinRate", m.homeWinRate)
                        put("awayWinRate", m.awayWinRate)
                        put("isNbaFormat", m.isNbaFormat)
                        put("totalLine", m.totalLine)
                        put("homeSpreadLine", m.homeSpreadLine)
                        put("q1TotalLine", m.q1TotalLine)
                        put("htTotalLine", m.htTotalLine)
                        put("homeTeamTotalLine", m.homeTeamTotalLine)
                        put("altSafeTotalLine", m.altSafeTotalLine)
                        put("oddHomeMl", m.oddHomeMl)
                        put("oddAwayMl", m.oddAwayMl)
                        put("oddHomeSpread", m.oddHomeSpread)
                        put("oddAwaySpread", m.oddAwaySpread)
                        put("oddTotalOver", m.oddTotalOver)
                        put("oddTotalUnder", m.oddTotalUnder)
                        put("oddQ1Over", m.oddQ1Over)
                        put("oddQ1Under", m.oddQ1Under)
                        put("oddHtOver", m.oddHtOver)
                        put("oddHomeTeamOver", m.oddHomeTeamOver)
                        put("oddAltSafeOver", m.oddAltSafeOver)
                        put("fontesVerificadas", JSONArray(m.fontesVerificadas))
                    }
                )
            }
            root.put("matches", matchesArr)
            return root.toString()
        }

        internal fun deserializeBasketballFetchResult(jsonString: String): BasketballFetchResult? {
            val root = JSONObject(jsonString)
            val cycleObj = root.optJSONObject("cycleInfo") ?: return null
            val cycleInfo = decodeCycleInfo(cycleObj)

            val sources = mutableListOf<GroundingSource>()
            val sourcesArr = root.optJSONArray("sources")
            if (sourcesArr != null) {
                for (i in 0 until sourcesArr.length()) {
                    val s = sourcesArr.optJSONObject(i) ?: continue
                    sources.add(GroundingSource(s.optString("title", ""), s.optString("uri", "")))
                }
            }

            val queries = mutableListOf<String>()
            val queriesArr = root.optJSONArray("searchQueries")
            if (queriesArr != null) {
                for (i in 0 until queriesArr.length()) {
                    val q = queriesArr.optString(i, "")
                    if (q.isNotBlank()) queries.add(q)
                }
            }

            val matches = mutableListOf<RawBasketballMatchData>()
            val matchesArr = root.optJSONArray("matches") ?: return null
            for (i in 0 until matchesArr.length()) {
                val m = matchesArr.optJSONObject(i) ?: continue
                val verified = mutableListOf<String>()
                val vArr = m.optJSONArray("fontesVerificadas")
                if (vArr != null) {
                    for (j in 0 until vArr.length()) {
                        val v = vArr.optString(j, "")
                        if (v.isNotBlank()) verified.add(v)
                    }
                }
                matches.add(
                    RawBasketballMatchData(
                        id = m.optString("id", "bk_cached_$i"),
                        dataHoraWet = m.optString("dataHoraWet", ""),
                        competicao = m.optString("competicao", "NBA"),
                        equipaCasa = m.optString("equipaCasa", ""),
                        equipaFora = m.optString("equipaFora", ""),
                        expectedPtsHome = m.optDouble("expectedPtsHome", 114.0),
                        expectedPtsAway = m.optDouble("expectedPtsAway", 111.0),
                        paceNotes = m.optString("paceNotes", ""),
                        homeWinRate = m.optDouble("homeWinRate", 0.50),
                        awayWinRate = m.optDouble("awayWinRate", 0.50),
                        isNbaFormat = m.optBoolean("isNbaFormat", true),
                        totalLine = m.optDouble("totalLine", 224.5),
                        homeSpreadLine = m.optDouble("homeSpreadLine", -3.5),
                        q1TotalLine = m.optDouble("q1TotalLine", 56.5),
                        htTotalLine = m.optDouble("htTotalLine", 113.5),
                        homeTeamTotalLine = m.optDouble("homeTeamTotalLine", 114.5),
                        altSafeTotalLine = m.optDouble("altSafeTotalLine", 218.5),
                        oddHomeMl = m.optDouble("oddHomeMl", 1.72),
                        oddAwayMl = m.optDouble("oddAwayMl", 2.15),
                        oddHomeSpread = m.optDouble("oddHomeSpread", 1.88),
                        oddAwaySpread = m.optDouble("oddAwaySpread", 1.88),
                        oddTotalOver = m.optDouble("oddTotalOver", 1.88),
                        oddTotalUnder = m.optDouble("oddTotalUnder", 1.88),
                        oddQ1Over = m.optDouble("oddQ1Over", 1.87),
                        oddQ1Under = m.optDouble("oddQ1Under", 1.87),
                        oddHtOver = m.optDouble("oddHtOver", 1.87),
                        oddHomeTeamOver = m.optDouble("oddHomeTeamOver", 1.86),
                        oddAltSafeOver = m.optDouble("oddAltSafeOver", 1.40),
                        fontesVerificadas = verified
                    )
                )
            }

            if (matches.size < 2) return null

            return BasketballFetchResult(
                cycleInfo = cycleInfo,
                matches = matches,
                sources = sources,
                searchQueries = queries,
                modelSummaryNote = root.optString("modelSummaryNote", "Análise Basquetebol guardada localmente.")
            )
        }

        internal fun serializeTennisFetchResult(result: TennisFetchResult): String {
            val root = JSONObject()
            root.put("cycleInfo", encodeCycleInfo(result.cycleInfo))
            root.put("modelSummaryNote", result.modelSummaryNote)
            root.put("searchQueries", JSONArray(result.searchQueries))

            val sourcesArr = JSONArray()
            result.sources.forEach { src ->
                sourcesArr.put(JSONObject().apply { put("title", src.title); put("uri", src.uri) })
            }
            root.put("sources", sourcesArr)

            val matchesArr = JSONArray()
            result.matches.forEach { m ->
                matchesArr.put(
                    JSONObject().apply {
                        put("id", m.id)
                        put("dataHoraWet", m.dataHoraWet)
                        put("torneio", m.torneio)
                        put("ronda", m.ronda)
                        put("circuito", m.circuito)
                        put("superficie", m.superficie.name)
                        put("bestOfSets", m.bestOfSets)
                        put("jogador1", m.jogador1)
                        put("jogador2", m.jogador2)
                        put("ranking1", m.ranking1 ?: -1)
                        put("ranking2", m.ranking2 ?: -1)
                        put("serveHoldProb1", m.serveHoldProb1)
                        put("serveHoldProb2", m.serveHoldProb2)
                        put("surfaceNotes", m.surfaceNotes)
                        put("totalGamesLine", m.totalGamesLine)
                        put("p1GameHandicapLine", m.p1GameHandicapLine)
                        put("oddP1Ml", m.oddP1Ml)
                        put("oddP2Ml", m.oddP2Ml)
                        put("oddP1Set1", m.oddP1Set1)
                        put("oddP2Set1", m.oddP2Set1)
                        put("oddGamesOver", m.oddGamesOver)
                        put("oddGamesUnder", m.oddGamesUnder)
                        put("oddP1GameHc", m.oddP1GameHc)
                        put("oddP2GameHc", m.oddP2GameHc)
                        put("oddP1StraightSets", m.oddP1StraightSets)
                        put("oddOverSets", m.oddOverSets)
                        put("oddTiebreakYes", m.oddTiebreakYes)
                        put("fontesVerificadas", JSONArray(m.fontesVerificadas))
                    }
                )
            }
            root.put("matches", matchesArr)
            return root.toString()
        }

        internal fun deserializeTennisFetchResult(jsonString: String): TennisFetchResult? {
            val root = JSONObject(jsonString)
            val cycleObj = root.optJSONObject("cycleInfo") ?: return null
            val cycleInfo = decodeCycleInfo(cycleObj)

            val sources = mutableListOf<GroundingSource>()
            val sourcesArr = root.optJSONArray("sources")
            if (sourcesArr != null) {
                for (i in 0 until sourcesArr.length()) {
                    val s = sourcesArr.optJSONObject(i) ?: continue
                    sources.add(GroundingSource(s.optString("title", ""), s.optString("uri", "")))
                }
            }

            val queries = mutableListOf<String>()
            val queriesArr = root.optJSONArray("searchQueries")
            if (queriesArr != null) {
                for (i in 0 until queriesArr.length()) {
                    val q = queriesArr.optString(i, "")
                    if (q.isNotBlank()) queries.add(q)
                }
            }

            val matches = mutableListOf<RawTennisMatchData>()
            val matchesArr = root.optJSONArray("matches") ?: return null
            for (i in 0 until matchesArr.length()) {
                val m = matchesArr.optJSONObject(i) ?: continue
                val verified = mutableListOf<String>()
                val vArr = m.optJSONArray("fontesVerificadas")
                if (vArr != null) {
                    for (j in 0 until vArr.length()) {
                        val v = vArr.optString(j, "")
                        if (v.isNotBlank()) verified.add(v)
                    }
                }
                val r1 = m.optInt("ranking1", -1).takeIf { it > 0 }
                val r2 = m.optInt("ranking2", -1).takeIf { it > 0 }
                val surf = runCatching {
                    TennisSurface.valueOf(m.optString("superficie", TennisSurface.HARD.name))
                }.getOrDefault(TennisSurface.HARD)

                matches.add(
                    RawTennisMatchData(
                        id = m.optString("id", "tn_cached_$i"),
                        dataHoraWet = m.optString("dataHoraWet", ""),
                        torneio = m.optString("torneio", "ATP Tour"),
                        ronda = m.optString("ronda", "Ronda Oficial"),
                        circuito = m.optString("circuito", "ATP"),
                        superficie = surf,
                        bestOfSets = m.optInt("bestOfSets", 3),
                        jogador1 = m.optString("jogador1", ""),
                        jogador2 = m.optString("jogador2", ""),
                        ranking1 = r1,
                        ranking2 = r2,
                        serveHoldProb1 = m.optDouble("serveHoldProb1", 0.79),
                        serveHoldProb2 = m.optDouble("serveHoldProb2", 0.77),
                        surfaceNotes = m.optString("surfaceNotes", ""),
                        totalGamesLine = m.optDouble("totalGamesLine", 22.5),
                        p1GameHandicapLine = m.optDouble("p1GameHandicapLine", -2.5),
                        oddP1Ml = m.optDouble("oddP1Ml", 1.70),
                        oddP2Ml = m.optDouble("oddP2Ml", 2.15),
                        oddP1Set1 = m.optDouble("oddP1Set1", 1.75),
                        oddP2Set1 = m.optDouble("oddP2Set1", 2.05),
                        oddGamesOver = m.optDouble("oddGamesOver", 1.87),
                        oddGamesUnder = m.optDouble("oddGamesUnder", 1.87),
                        oddP1GameHc = m.optDouble("oddP1GameHc", 1.87),
                        oddP2GameHc = m.optDouble("oddP2GameHc", 1.87),
                        oddP1StraightSets = m.optDouble("oddP1StraightSets", 2.50),
                        oddOverSets = m.optDouble("oddOverSets", 2.20),
                        oddTiebreakYes = m.optDouble("oddTiebreakYes", 2.10),
                        fontesVerificadas = verified
                    )
                )
            }

            if (matches.size < 2) return null

            return TennisFetchResult(
                cycleInfo = cycleInfo,
                matches = matches,
                sources = sources,
                searchQueries = queries,
                modelSummaryNote = root.optString("modelSummaryNote", "Análise Ténis guardada localmente.")
            )
        }
    }
}
