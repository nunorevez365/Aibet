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
 * Automatically calibrates against ESPN's HTTP `Date` server header so skewed emulator/device
 * clocks (e.g. future dates) still query the true current official football calendar.
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

    private data class HttpResponse(
        val body: String,
        val serverDateMillis: Long
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
        LeagueConfig("uefa.nations", "UEFA Nations League", 1.35, 1700.0)
    )

    private val weekendLeagues = listOf(
        LeagueConfig("por.1", "Liga Portugal Betclic", 1.35, 1640.0),
        LeagueConfig("eng.1", "Premier League", 1.48, 1740.0),
        LeagueConfig("esp.1", "LaLiga", 1.34, 1710.0),
        LeagueConfig("ger.1", "Bundesliga", 1.56, 1700.0),
        LeagueConfig("ita.1", "Serie A", 1.32, 1700.0),
        LeagueConfig("fra.1", "Ligue 1", 1.38, 1660.0),
        LeagueConfig("uefa.champions", "UEFA Champions League", 1.48, 1780.0),
        LeagueConfig("uefa.europa", "UEFA Europa League", 1.42, 1690.0)
    )

    data class EspnFetchPayload(
        val effectiveCycleInfo: TemporalCycleInfo,
        val matches: List<RawMatchData>,
        val sources: List<GroundingSource>,
        val expandedWindow: Boolean,
        val diagnosticError: String? = null
    )

    suspend fun fetchConfirmedMatches(deviceCycleInfo: TemporalCycleInfo): EspnFetchPayload =
        withContext(Dispatchers.IO) {
            // 0. Calibrate real UTC time from ESPN server HTTP Date header (fixes skewed emulator clocks!)
            val probeResult = runCatching {
                httpGet("https://site.api.espn.com/apis/site/v2/sports/soccer/eng.1/scoreboard")
            }
            val serverNow = probeResult.getOrNull()?.serverDateMillis ?: 0L
            val deviceNow = System.currentTimeMillis()
            val effectiveNow = if (serverNow > 1_600_000_000_000L && abs(serverNow - deviceNow) > 43_200_000L) {
                Log.i(TAG, "Calibrated clock from ESPN HTTP Date header: server=$serverNow vs device=$deviceNow")
                serverNow
            } else {
                deviceNow
            }

            val effectiveCycle = QuantEngine.detectCurrentPortugalCycle(effectiveNow)
            val leagues = if (effectiveCycle.cycle == TemporalCycle.CYCLE_A_MIDWEEK) {
                midweekLeagues
            } else {
                weekendLeagues
            }

            // 1. Try exact active cycle date range (using calibrated real clock)
            val startCompact = effectiveCycle.startDateIso.replace("-", "")
            val endCompact = effectiveCycle.endDateIso.replace("-", "")
            val cycleRange = if (startCompact == endCompact) startCompact else "$startCompact-$endCompact"

            var expanded = false
            var collected = fetchAcrossLeagues(leagues, dateRangeParam = cycleRange, preMatchOnly = true)

            // 2. If fewer than 4 upcoming official fixtures exist on exact cycle dates,
            // query ESPN's default current matchday (no ?dates) + next 7 real days
            if (collected.first.size < 4) {
                expanded = true
                val range7d = buildNextNDaysRange(baseMillis = effectiveNow, days = 7)
                val next7 = fetchAcrossLeagues(leagues, dateRangeParam = range7d, preMatchOnly = true)
                val mergedMatches = (collected.first + next7.first)
                    .distinctBy { "${it.equipaCasa.lowercase()}_${it.equipaFora.lowercase()}" }
                val mergedSources = (collected.second + next7.second).distinctBy { it.uri }
                collected = mergedMatches to mergedSources
            }

            // 3. Query ESPN default active matchday (no ?dates) & next 14 days if still < 4
            if (collected.first.size < 4) {
                expanded = true
                val defaultRound = fetchAcrossLeagues(leagues, dateRangeParam = null, preMatchOnly = true)
                val range14d = buildNextNDaysRange(baseMillis = effectiveNow, days = 14)
                val next14 = fetchAcrossLeagues(leagues, dateRangeParam = range14d, preMatchOnly = true)
                val mergedMatches = (collected.first + defaultRound.first + next14.first)
                    .distinctBy { "${it.equipaCasa.lowercase()}_${it.equipaFora.lowercase()}" }
                val mergedSources = (collected.second + defaultRound.second + next14.second).distinctBy { it.uri }
                collected = mergedMatches to mergedSources
            }

            // 4. Last ESPN resort (e.g., off-season week where all default scoreboard matches are "in"/"post"):
            // accept current scoreboard fixtures so we still inspect real ESPN data if reachable
            if (collected.first.size < 2) {
                val anyCurrent = fetchAcrossLeagues(leagues, dateRangeParam = null, preMatchOnly = false)
                val mergedMatches = (collected.first + anyCurrent.first)
                    .distinctBy { "${it.equipaCasa.lowercase()}_${it.equipaFora.lowercase()}" }
                val mergedSources = (collected.second + anyCurrent.second).distinctBy { it.uri }
                collected = mergedMatches to mergedSources
            }

            val prioritized = collected.first
                .distinctBy { "${it.equipaCasa.lowercase()}_${it.equipaFora.lowercase()}" }
                .take(8)

            val diag = if (prioritized.isEmpty()) {
                probeResult.exceptionOrNull()?.message ?: "Sem eventos devolvidos pela ESPN."
            } else {
                null
            }

            EspnFetchPayload(
                effectiveCycleInfo = effectiveCycle,
                matches = prioritized,
                sources = collected.second.distinctBy { it.uri },
                expandedWindow = expanded,
                diagnosticError = diag
            )
        }

    private suspend fun fetchAcrossLeagues(
        leagues: List<LeagueConfig>,
        dateRangeParam: String?,
        preMatchOnly: Boolean
    ): Pair<List<RawMatchData>, List<GroundingSource>> = coroutineScope {
        val jobs = leagues.map { league ->
            async {
                runCatching {
                    val query = if (dateRangeParam != null) "?dates=$dateRangeParam" else ""
                    val scoreboardUrl =
                        "https://site.api.espn.com/apis/site/v2/sports/soccer/${league.slug}/scoreboard$query"
                    val response = httpGet(scoreboardUrl) ?: return@runCatching null
                    val scoreboardJson = JSONObject(response.body)
                    val events = scoreboardJson.optJSONArray("events") ?: JSONArray()
                    if (events.length() == 0) return@runCatching null

                    val standingsMap = fetchLeagueStandings(league.slug)
                    val parsed = parseScoreboardEvents(
                        events = events,
                        league = league,
                        standings = standingsMap,
                        preMatchOnly = preMatchOnly
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
        val response = runCatching { httpGet(url) }.getOrNull() ?: return emptyMap()
        return runCatching { parseStandingsMap(JSONObject(response.body)) }.getOrDefault(emptyMap())
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
        preMatchOnly: Boolean
    ): List<RawMatchData> {
        val results = mutableListOf<RawMatchData>()
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
            if (preMatchOnly && statusState != "pre") continue

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

            val kickoffWet = formatUtcToPortugalWet(comp.optString("date", event.optString("date", "")))
            val formInfo = buildFormSummary(
                homeName,
                homeObj.optString("form", ""),
                awayName,
                awayObj.optString("form", ""),
                extractedOdds.fromProviderName
            )

            results.add(
                RawMatchData(
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

    private fun formatUtcToPortugalWet(isoUtc: String): String {
        if (isoUtc.isBlank()) return "Horário por confirmar"
        val lisbonTz = TimeZone.getTimeZone("Europe/Lisbon")
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
            if (parsed != null) {
                val out = SimpleDateFormat("dd/MM HH:mm", Locale("pt", "PT")).apply {
                    timeZone = lisbonTz
                }
                val suffix = if (lisbonTz.inDaylightTime(parsed)) "WEST" else "WET"
                return "${out.format(parsed)} $suffix"
            }
        }
        return isoUtc.take(16)
    }

    private fun buildNextNDaysRange(baseMillis: Long, days: Int): String {
        val lisbonTz = TimeZone.getTimeZone("Europe/Lisbon")
        val fmt = SimpleDateFormat("yyyyMMdd", Locale.US).apply { timeZone = lisbonTz }
        val start = Calendar.getInstance(lisbonTz).apply { timeInMillis = baseMillis }
        val end = (start.clone() as Calendar).apply { add(Calendar.DAY_OF_MONTH, days) }
        return "${fmt.format(start.time)}-${fmt.format(end.time)}"
    }

    private fun httpGet(urlString: String): HttpResponse? {
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
            val serverDate = conn.date
            if (code in 200..299) {
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                HttpResponse(body = body, serverDateMillis = serverDate)
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
