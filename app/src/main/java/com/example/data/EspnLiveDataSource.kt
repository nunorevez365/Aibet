package com.example.data

import android.util.Log
import com.example.quant.GroundingSource
import com.example.quant.QuantEngine
import com.example.quant.RawMatchData
import com.example.quant.TemporalCycle
import com.example.quant.TemporalCycleInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.round

private const val TAG = "EspnLiveDataSource"

/**
 * Real-time public soccer fixtures, season standings, and live odds client using ESPN's
 * official JSON endpoints (zero API key, zero Gemini quota consumption).
 *
 * Strictly filters matches to the exact Portugal Continental (WET/WEST) dates selected by the user.
 */
class EspnLiveDataSource {

    private data class LeagueConfig(
        val slug: String,
        val displayName: String,
        val defaultAvgGoalsPerTeam: Double,
        val baseElo: Double
    )

    private data class TeamSeasonStats(
        val teamId: String,
        val teamName: String,
        val gamesPlayed: Int,
        val goalsFor: Int,
        val goalsAgainst: Int,
        val points: Int
    ) {
        val gfPerGame: Double get() = if (gamesPlayed > 0) goalsFor.toDouble() / gamesPlayed else 1.35
        val gaPerGame: Double get() = if (gamesPlayed > 0) goalsAgainst.toDouble() / gamesPlayed else 1.35
        val ppg: Double get() = if (gamesPlayed > 0) points.toDouble() / gamesPlayed else 1.35
    }

    private data class DatedMatch(
        val kickoffMillis: Long,
        val match: RawMatchData
    )

    private val midweekLeagues = listOf(
        LeagueConfig("uefa.champions", "UEFA Champions League", 1.48, 1780.0),
        LeagueConfig("uefa.europa", "UEFA Europa League", 1.42, 1690.0),
        LeagueConfig("uefa.europa.conf", "UEFA Conference League", 1.38, 1620.0),
        LeagueConfig("por.1", "Liga Portugal Betclic", 1.35, 1640.0),
        LeagueConfig("eng.1", "Premier League", 1.48, 1740.0),
        LeagueConfig("esp.1", "LaLiga", 1.34, 1710.0),
        LeagueConfig("ger.1", "Bundesliga", 1.56, 1700.0),
        LeagueConfig("ita.1", "Serie A", 1.32, 1700.0),
        LeagueConfig("fra.1", "Ligue 1", 1.38, 1660.0),
        LeagueConfig("eng.league_cup", "EFL Cup", 1.40, 1660.0),
        LeagueConfig("esp.copa_del_rey", "Copa del Rey", 1.38, 1650.0),
        LeagueConfig("uefa.nations", "UEFA Nations League", 1.35, 1700.0)
    )

    private val weekendLeagues = listOf(
        LeagueConfig("por.1", "Liga Portugal Betclic", 1.35, 1640.0),
        LeagueConfig("eng.1", "Premier League", 1.48, 1740.0),
        LeagueConfig("esp.1", "LaLiga", 1.34, 1710.0),
        LeagueConfig("ger.1", "Bundesliga", 1.56, 1700.0),
        LeagueConfig("ita.1", "Serie A", 1.32, 1700.0),
        LeagueConfig("fra.1", "Ligue 1", 1.38, 1660.0),
        LeagueConfig("ned.1", "Eredivisie", 1.55, 1620.0),
        LeagueConfig("eng.fa", "FA Cup", 1.40, 1660.0),
        LeagueConfig("uefa.nations", "UEFA Nations League", 1.35, 1700.0)
    )

    data class EspnFetchPayload(
        val effectiveCycleInfo: TemporalCycleInfo,
        val matches: List<RawMatchData>,
        val sources: List<GroundingSource>,
        val expandedWindow: Boolean,
        val diagnosticError: String? = null
    )

    suspend fun fetchConfirmedMatches(selectedCycleInfo: TemporalCycleInfo): EspnFetchPayload =
        withContext(Dispatchers.IO) {
            val allowedDatesWet = selectedCycleInfo.exactDatesIso.toSet()
            val leagues = if (selectedCycleInfo.cycle == TemporalCycle.CYCLE_B_WEEKEND) {
                weekendLeagues
            } else {
                midweekLeagues
            }

            // Query ESPN across [startDate - 1 day, endDate + 1 day] to avoid UTC/WET midnight boundary loss,
            // then STRICTLY keep only fixtures whose Portugal WET/WEST date is inside allowedDatesWet!
            val queryRange = buildBufferedCompactRange(
                startIso = selectedCycleInfo.startDateIso,
                endIso = selectedCycleInfo.endDateIso
            )

            val (datedMatches, sources) = fetchAcrossLeagues(
                leagues = leagues,
                dateRangeParam = queryRange,
                allowedDatesWet = allowedDatesWet
            )

            val sortedMatches = datedMatches
                .sortedBy { it.kickoffMillis }
                .map { it.match }
                .distinctBy { "${it.equipaCasa.lowercase()}_${it.equipaFora.lowercase()}" }
                .take(10)

            val diag = if (sortedMatches.isEmpty()) {
                val rangeLabel = if (selectedCycleInfo.startDateIso == selectedCycleInfo.endDateIso) {
                    selectedCycleInfo.startDateIso
                } else {
                    "${selectedCycleInfo.startDateIso} a ${selectedCycleInfo.endDateIso}"
                }
                "Sem jogos oficiais agendados nas ligas principais entre $rangeLabel."
            } else {
                null
            }

            EspnFetchPayload(
                effectiveCycleInfo = selectedCycleInfo,
                matches = sortedMatches,
                sources = sources.distinctBy { it.uri },
                expandedWindow = false,
                diagnosticError = diag
            )
        }

    private suspend fun fetchAcrossLeagues(
        leagues: List<LeagueConfig>,
        dateRangeParam: String,
        allowedDatesWet: Set<String>
    ): Pair<List<DatedMatch>, List<GroundingSource>> = coroutineScope {
        val jobs = leagues.map { league ->
            async {
                runCatching {
                    val scoreboardUrl =
                        "https://site.api.espn.com/apis/site/v2/sports/soccer/${league.slug}/scoreboard?dates=$dateRangeParam"
                    val body = httpGet(scoreboardUrl) ?: return@runCatching null
                    val scoreboardJson = JSONObject(body)
                    val events = scoreboardJson.optJSONArray("events") ?: JSONArray()
                    if (events.length() == 0) return@runCatching null

                    // Pre-check if any event actually falls inside the user's selected WET/WEST dates before fetching standings
                    val hasMatchingDate = (0 until events.length()).any { idx ->
                        val ev = events.optJSONObject(idx) ?: return@any false
                        val comp = ev.optJSONArray("competitions")?.optJSONObject(0) ?: return@any false
                        val utcStr = comp.optString("date", ev.optString("date", ""))
                        val wetIso = extractPortugalIsoDate(utcStr)
                        wetIso != null && wetIso in allowedDatesWet
                    }
                    if (!hasMatchingDate) return@runCatching null

                    val standingsMap = fetchLeagueStandings(league.slug)
                    val parsed = parseScoreboardEvents(
                        events = events,
                        league = league,
                        standings = standingsMap,
                        allowedDatesWet = allowedDatesWet
                    )
                    if (parsed.isEmpty()) {
                        null
                    } else {
                        val source = GroundingSource(
                            title = "ESPN • ${league.displayName}",
                            uri = "https://www.espn.com/soccer/scoreboard/_/league/${league.slug}"
                        )
                        parsed to source
                    }
                }.onFailure { err ->
                    Log.w(TAG, "ESPN fetch skipped for ${league.slug}: ${err.message}")
                }.getOrNull()
            }
        }

        val results = jobs.awaitAll().filterNotNull()
        val allMatches = results.flatMap { it.first }
        val allSources = results.map { it.second }
        allMatches to allSources
    }

    private fun fetchLeagueStandings(slug: String): Map<String, TeamSeasonStats> {
        val url = "https://site.api.espn.com/apis/v2/sports/soccer/$slug/standings"
        val body = runCatching { httpGet(url) }.getOrNull() ?: return emptyMap()
        return runCatching { parseStandingsMap(JSONObject(body)) }.getOrDefault(emptyMap())
    }

    private fun parseStandingsMap(root: JSONObject): Map<String, TeamSeasonStats> {
        val map = mutableMapOf<String, TeamSeasonStats>()

        fun consumeEntries(entries: JSONArray?) {
            if (entries == null) return
            for (i in 0 until entries.length()) {
                val entry = entries.optJSONObject(i) ?: continue
                val team = entry.optJSONObject("team") ?: continue
                val teamId = team.optString("id", "").trim()
                val teamName = team.optString("displayName", team.optString("name", "")).trim()
                if (teamId.isBlank() && teamName.isBlank()) continue

                val statsArr = entry.optJSONArray("stats") ?: JSONArray()
                var gp = 0
                var gf = 0
                var ga = 0
                var pts = 0
                for (s in 0 until statsArr.length()) {
                    val stat = statsArr.optJSONObject(s) ?: continue
                    val name = stat.optString("name", "")
                    val value = stat.optDouble("value", Double.NaN)
                    if (value.isNaN()) continue
                    when (name) {
                        "gamesPlayed" -> gp = value.toInt()
                        "pointsFor" -> gf = value.toInt()
                        "pointsAgainst" -> ga = value.toInt()
                        "points" -> pts = value.toInt()
                    }
                }
                val record = TeamSeasonStats(teamId, teamName, gp, gf, ga, pts)
                if (teamId.isNotBlank()) map[teamId] = record
                if (teamName.isNotBlank()) map[teamName.lowercase(Locale.ROOT)] = record
            }
        }

        consumeEntries(root.optJSONObject("standings")?.optJSONArray("entries"))
        val children = root.optJSONArray("children")
        if (children != null) {
            for (c in 0 until children.length()) {
                val child = children.optJSONObject(c) ?: continue
                consumeEntries(child.optJSONObject("standings")?.optJSONArray("entries"))
            }
        }
        return map
    }

    private fun parseScoreboardEvents(
        events: JSONArray,
        league: LeagueConfig,
        standings: Map<String, TeamSeasonStats>,
        allowedDatesWet: Set<String>
    ): List<DatedMatch> {
        val results = mutableListOf<DatedMatch>()
        val leagueAvgGf = standings.values
            .filter { it.gamesPlayed >= 3 }
            .map { it.gfPerGame }
            .takeIf { it.isNotEmpty() }
            ?.average()
            ?.coerceIn(1.05, 1.85)
            ?: league.defaultAvgGoalsPerTeam

        for (i in 0 until events.length()) {
            val event = events.optJSONObject(i) ?: continue
            val eventId = event.optString("id", "ev_$i")
            val comp = event.optJSONArray("competitions")?.optJSONObject(0) ?: continue

            val statusState = comp.optJSONObject("status")
                ?.optJSONObject("type")
                ?.optString("state", "")
                .orEmpty()
                .lowercase(Locale.ROOT)
            // Skip already finished games; keep upcoming ("pre") fixtures
            if (statusState == "post") continue

            val utcStr = comp.optString("date", event.optString("date", ""))
            val parsedDate = parseUtcDate(utcStr) ?: continue
            val wetIsoDate = formatDateToPortugalIso(parsedDate)
            // STRICT filter: discard any fixture not falling on the user's selected WET/WEST dates!
            if (wetIsoDate !in allowedDatesWet) continue

            val competitors = comp.optJSONArray("competitors") ?: continue
            var homeObj: JSONObject? = null
            var awayObj: JSONObject? = null
            for (c in 0 until competitors.length()) {
                val item = competitors.optJSONObject(c) ?: continue
                when (item.optString("homeAway", "").lowercase(Locale.ROOT)) {
                    "home" -> homeObj = item
                    "away" -> awayObj = item
                }
            }
            if (homeObj == null || awayObj == null) continue

            val homeTeamObj = homeObj.optJSONObject("team") ?: continue
            val awayTeamObj = awayObj.optJSONObject("team") ?: continue
            val homeName = homeTeamObj.optString("displayName", homeTeamObj.optString("shortDisplayName", "")).trim()
            val awayName = awayTeamObj.optString("displayName", awayTeamObj.optString("shortDisplayName", "")).trim()
            if (homeName.isBlank() || awayName.isBlank()) continue

            val homeId = homeTeamObj.optString("id", "")
            val awayId = awayTeamObj.optString("id", "")

            val homeStats = standings[homeId] ?: standings[homeName.lowercase(Locale.ROOT)]
            val awayStats = standings[awayId] ?: standings[awayName.lowercase(Locale.ROOT)]

            val (xgHome, xgAway) = estimateEmpiricalXg(
                homeStats = homeStats,
                awayStats = awayStats,
                homeForm = homeObj.optString("form", ""),
                awayForm = awayObj.optString("form", ""),
                leagueAvg = leagueAvgGf
            )

            val eloHome = estimateEmpiricalElo(homeStats, homeObj.optString("form", ""), league.baseElo)
            val eloAway = estimateEmpiricalElo(awayStats, awayObj.optString("form", ""), league.baseElo)

            val extractedOdds = extractRealOrBaselineOdds(
                oddsArray = comp.optJSONArray("odds"),
                xgHome = xgHome,
                xgAway = xgAway
            )

            val kickoffWet = formatDateToPortugalLabel(parsedDate)
            val formInfo = buildFormSummary(
                homeName,
                homeObj.optString("form", ""),
                awayName,
                awayObj.optString("form", ""),
                extractedOdds.fromProviderName
            )

            results.add(
                DatedMatch(
                    kickoffMillis = parsedDate.time,
                    match = RawMatchData(
                        id = "espn_${league.slug}_$eventId",
                        dataHoraWet = kickoffWet,
                        competicao = league.displayName,
                        equipaCasa = homeName,
                        equipaFora = awayName,
                        xgCasaEstimado = xgHome,
                        xgForaEstimado = xgAway,
                        lesoesChave = formInfo,
                        eloCasaEstimado = eloHome,
                        eloForaEstimado = eloAway,
                        odd1 = extractedOdds.odd1,
                        oddX = extractedOdds.oddX,
                        odd2 = extractedOdds.odd2,
                        oddOver25 = extractedOdds.oddOver25,
                        oddUnder35 = extractedOdds.oddUnder35,
                        oddBtts = extractedOdds.oddBtts,
                        fontesVerificadas = listOfNotNull(
                            "ESPN (${league.displayName})",
                            extractedOdds.fromProviderName?.let { "Odds: $it" }
                        )
                    )
                )
            )
        }
        return results
    }

    private fun estimateEmpiricalXg(
        homeStats: TeamSeasonStats?,
        awayStats: TeamSeasonStats?,
        homeForm: String,
        awayForm: String,
        leagueAvg: Double
    ): Pair<Double, Double> {
        val priorGames = 4.0

        val homeAttack = if (homeStats != null && homeStats.gamesPlayed > 0) {
            (homeStats.goalsFor + priorGames * leagueAvg) / (homeStats.gamesPlayed + priorGames)
        } else {
            leagueAvg * formMultiplier(homeForm)
        }
        val homeDefense = if (homeStats != null && homeStats.gamesPlayed > 0) {
            (homeStats.goalsAgainst + priorGames * leagueAvg) / (homeStats.gamesPlayed + priorGames)
        } else {
            leagueAvg / formMultiplier(homeForm)
        }

        val awayAttack = if (awayStats != null && awayStats.gamesPlayed > 0) {
            (awayStats.goalsFor + priorGames * leagueAvg) / (awayStats.gamesPlayed + priorGames)
        } else {
            leagueAvg * formMultiplier(awayForm)
        }
        val awayDefense = if (awayStats != null && awayStats.gamesPlayed > 0) {
            (awayStats.goalsAgainst + priorGames * leagueAvg) / (awayStats.gamesPlayed + priorGames)
        } else {
            leagueAvg / formMultiplier(awayForm)
        }

        val rawHomeXg = ((homeAttack * awayDefense) / leagueAvg) * 1.11
        val rawAwayXg = ((awayAttack * homeDefense) / leagueAvg) * 0.91

        return round2(rawHomeXg.coerceIn(0.45, 3.30)) to round2(rawAwayXg.coerceIn(0.35, 3.00))
    }

    private fun formMultiplier(form: String): Double {
        if (form.isBlank()) return 1.0
        val recent = form.uppercase(Locale.ROOT).takeLast(5)
        var score = 0.0
        for (ch in recent) {
            score += when (ch) {
                'W' -> 1.0
                'D' -> 0.0
                'L' -> -1.0
                else -> 0.0
            }
        }
        val normalized = score / max(1, recent.length)
        return (1.0 + normalized * 0.14).coerceIn(0.84, 1.18)
    }

    private fun estimateEmpiricalElo(
        stats: TeamSeasonStats?,
        form: String,
        baseElo: Double
    ): Double {
        val seasonAdj = if (stats != null && stats.gamesPlayed >= 2) {
            val ppgDelta = (stats.ppg - 1.38) * 145.0
            val gdPerGame = (stats.goalsFor - stats.goalsAgainst).toDouble() / stats.gamesPlayed
            ppgDelta + (gdPerGame * 55.0)
        } else {
            0.0
        }
        val formAdj = (formMultiplier(form) - 1.0) * 180.0
        return round(baseElo + seasonAdj + formAdj).coerceIn(1350.0, 2050.0)
    }

    private data class ExtractedOdds(
        val odd1: Double,
        val oddX: Double,
        val odd2: Double,
        val oddOver25: Double,
        val oddUnder35: Double,
        val oddBtts: Double,
        val fromProviderName: String?
    )

    private fun extractRealOrBaselineOdds(
        oddsArray: JSONArray?,
        xgHome: Double,
        xgAway: Double
    ): ExtractedOdds {
        var providerName: String? = null
        var realOdd1: Double? = null
        var realOddX: Double? = null
        var realOdd2: Double? = null

        if (oddsArray != null && oddsArray.length() > 0) {
            val first = oddsArray.optJSONObject(0)
            if (first != null) {
                providerName = first.optJSONObject("provider")?.optString("name", null)
                realOdd1 = parseEspnOddObject(first.optJSONObject("homeTeamOdds"))
                realOddX = parseEspnOddObject(first.optJSONObject("drawOdds"))
                realOdd2 = parseEspnOddObject(first.optJSONObject("awayTeamOdds"))
            }
        }

        val rawMatrix = QuantEngine.buildDixonColesMatrix(xgHome, xgAway, rho = 0.0)
        var p1 = 0.0
        var pX = 0.0
        var p2 = 0.0
        var pO25 = 0.0
        var pU35 = 0.0
        var pBtts = 0.0
        for (h in 0 until QuantEngine.MATRIX_SIZE) {
            for (a in 0 until QuantEngine.MATRIX_SIZE) {
                val p = rawMatrix[h][a]
                when {
                    h > a -> p1 += p
                    h == a -> pX += p
                    else -> p2 += p
                }
                if (h + a >= 3) pO25 += p
                if (h + a <= 3) pU35 += p
                if (h >= 1 && a >= 1) pBtts += p
            }
        }

        val margin = 1.055
        fun fairToBookOdd(prob: Double): Double {
            val safe = prob.coerceIn(0.05, 0.90)
            return round2((1.0 / (safe * margin)).coerceIn(1.08, 18.0))
        }

        return ExtractedOdds(
            odd1 = realOdd1 ?: fairToBookOdd(p1),
            oddX = realOddX ?: fairToBookOdd(pX),
            odd2 = realOdd2 ?: fairToBookOdd(p2),
            oddOver25 = fairToBookOdd(pO25),
            oddUnder35 = fairToBookOdd(pU35),
            oddBtts = fairToBookOdd(pBtts),
            fromProviderName = if (realOdd1 != null && realOdd2 != null) providerName ?: "ESPN Odds" else null
        )
    }

    private fun parseEspnOddObject(obj: JSONObject?): Double? {
        if (obj == null) return null
        val dec = obj.optDouble("decimal", Double.NaN)
        if (!dec.isNaN() && dec > 1.02) return round2(dec)

        val moneyLineNum = obj.optDouble("moneyLine", Double.NaN)
        if (!moneyLineNum.isNaN() && abs(moneyLineNum) >= 100.0) {
            return americanToDecimal(moneyLineNum)
        }
        val oddsStr = obj.optString("odds", "").trim()
        val parsedStr = oddsStr.replace("+", "").toDoubleOrNull()
        if (parsedStr != null && abs(parsedStr) >= 100.0) {
            return americanToDecimal(parsedStr)
        }
        return null
    }

    internal fun americanToDecimal(american: Double): Double {
        val raw = if (american > 0.0) {
            1.0 + (american / 100.0)
        } else {
            1.0 + (100.0 / abs(american))
        }
        return round2(raw.coerceIn(1.04, 25.0))
    }

    private fun buildFormSummary(
        home: String,
        homeForm: String,
        away: String,
        awayForm: String,
        oddsProvider: String?
    ): String {
        val parts = mutableListOf<String>()
        if (homeForm.isNotBlank() || awayForm.isNotBlank()) {
            parts.add("Forma recente: $home (${homeForm.ifBlank { "—" }}) vs $away (${awayForm.ifBlank { "—" }})")
        }
        if (oddsProvider != null) {
            parts.add("Odds 1X2 ao vivo ($oddsProvider) • Editável para Betclic.pt")
        } else {
            parts.add("Odds de mercado base • Toca em 'Odds Betclic' na Matriz para ajustar ao cêntimo")
        }
        return parts.joinToString(" • ")
    }

    private fun buildBufferedCompactRange(startIso: String, endIso: String): String {
        val lisbonTz = TimeZone.getTimeZone("Europe/Lisbon")
        val isoFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = lisbonTz }
        val compactFmt = SimpleDateFormat("yyyyMMdd", Locale.US).apply { timeZone = lisbonTz }
        val startDate = runCatching { isoFmt.parse(startIso) }.getOrNull() ?: Date()
        val endDate = runCatching { isoFmt.parse(endIso) }.getOrNull() ?: startDate

        val startCal = Calendar.getInstance(lisbonTz).apply {
            time = startDate
            add(Calendar.DAY_OF_MONTH, -1)
        }
        val endCal = Calendar.getInstance(lisbonTz).apply {
            time = endDate
            add(Calendar.DAY_OF_MONTH, 1)
        }
        return "${compactFmt.format(startCal.time)}-${compactFmt.format(endCal.time)}"
    }

    private fun parseUtcDate(isoUtc: String): Date? {
        if (isoUtc.isBlank()) return null
        val utcTz = TimeZone.getTimeZone("UTC")
        val parsers = listOf(
            "yyyy-MM-dd'T'HH:mm'Z'",
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
            "yyyy-MM-dd'T'HH:mm:ssXXX"
        )
        for (pattern in parsers) {
            val parsed = runCatching {
                SimpleDateFormat(pattern, Locale.US).apply { timeZone = utcTz }.parse(isoUtc)
            }.getOrNull()
            if (parsed != null) return parsed
        }
        return null
    }

    private fun extractPortugalIsoDate(isoUtc: String): String? {
        val date = parseUtcDate(isoUtc) ?: return null
        return formatDateToPortugalIso(date)
    }

    private fun formatDateToPortugalIso(date: Date): String {
        val lisbonTz = TimeZone.getTimeZone("Europe/Lisbon")
        return SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = lisbonTz }.format(date)
    }

    private fun formatDateToPortugalLabel(date: Date): String {
        val lisbonTz = TimeZone.getTimeZone("Europe/Lisbon")
        val out = SimpleDateFormat("dd/MM HH:mm", Locale("pt", "PT")).apply {
            timeZone = lisbonTz
        }
        val suffix = if (lisbonTz.inDaylightTime(date)) "WEST" else "WET"
        return "${out.format(date)} $suffix"
    }

    private fun httpGet(urlString: String): String? {
        val conn = (URL(urlString).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 9_000
            readTimeout = 9_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 15) BetQuantAI/1.0")
            setRequestProperty("Accept", "application/json")
        }
        return try {
            val code = conn.responseCode
            if (code in 200..299) {
                conn.inputStream.bufferedReader().use { it.readText() }
            } else {
                Log.w(TAG, "HTTP $code for $urlString")
                null
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun round2(value: Double): Double = round(value * 100.0) / 100.0
}
