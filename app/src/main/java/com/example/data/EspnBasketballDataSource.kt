package com.example.data

import android.util.Log
import com.example.quant.BasketballQuantEngine
import com.example.quant.GroundingSource
import com.example.quant.RawBasketballMatchData
import com.example.quant.TemporalCycleInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
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
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.round

private const val TAG = "EspnBasketballSource"

/**
 * Real-time public basketball fixtures, standings (PPG/OPPG/Win%), and live lines (Over/Under,
 * Spread, Moneyline) from ESPN official basketball endpoints (NBA primary + NCAA/WNBA secondary).
 *
 * Because NBA evening games in US Eastern Time (e.g. 19:30 EST) tip off after midnight in Portugal
 * (00:30 WET the next calendar day), we query both `selectedDate` AND `selectedDate - 1 day` in
 * single-date `?dates=YYYYMMDD` format and strictly keep only games falling on the user's selected
 * Portugal Continental (WET/WEST) calendar dates.
 */
class EspnBasketballDataSource {

    private data class BasketLeagueConfig(
        val slug: String,
        val displayName: String,
        val defaultPpg: Double,
        val homeCourtBoost: Double,
        val isNbaFormat: Boolean
    )

    private data class BasketTeamStats(
        val teamId: String,
        val teamName: String,
        val avgPtsFor: Double,
        val avgPtsAgainst: Double,
        val winPercent: Double
    )

    private data class DatedBasketMatch(
        val kickoffMillis: Long,
        val isLive: Boolean,
        val priority: Int,
        val match: RawBasketballMatchData
    )

    private val primaryLeagues = listOf(
        BasketLeagueConfig("nba", "NBA", 114.2, 2.8, isNbaFormat = true),
        BasketLeagueConfig("wnba", "WNBA", 82.0, 2.4, isNbaFormat = false)
    )

    private val secondaryLeagues = listOf(
        BasketLeagueConfig("mens-college-basketball", "NCAA Div. I", 74.8, 3.2, isNbaFormat = false)
    )

    data class BasketFetchPayload(
        val effectiveCycleInfo: TemporalCycleInfo,
        val matches: List<RawBasketballMatchData>,
        val sources: List<GroundingSource>,
        val diagnosticError: String? = null
    )

    suspend fun fetchConfirmedMatches(selectedCycleInfo: TemporalCycleInfo): BasketFetchPayload =
        withContext(Dispatchers.IO) {
            val allowedDatesWet = selectedCycleInfo.exactDatesIso.toSet()
            val queryCompactDays = expandCompactDaysForUsTimezone(selectedCycleInfo.exactDatesIso)

            val successHttp = AtomicInteger(0)
            val failedHttp = AtomicInteger(0)

            val primaryResult = fetchLeagues(
                leagues = primaryLeagues,
                compactDays = queryCompactDays,
                allowedDatesWet = allowedDatesWet,
                priorityBase = 0,
                successHttp = successHttp,
                failedHttp = failedHttp
            )

            var allMatches = primaryResult.first
            val allSources = primaryResult.second.toMutableList()
            val observedDates = primaryResult.third.toMutableSet()

            // If NBA/WNBA has fewer than 4 games on the chosen days, also pull top NCAA Div I fixtures
            if (allMatches.size < 4) {
                val secResult = fetchLeagues(
                    leagues = secondaryLeagues,
                    compactDays = queryCompactDays,
                    allowedDatesWet = allowedDatesWet,
                    priorityBase = 1,
                    successHttp = successHttp,
                    failedHttp = failedHttp
                )
                allMatches = allMatches + secResult.first
                allSources.addAll(secResult.second)
                observedDates.addAll(secResult.third)
            }

            val sortedMatches = allMatches
                .sortedWith(
                    compareBy<DatedBasketMatch> { it.priority }
                        .thenBy { it.isLive }
                        .thenBy { it.kickoffMillis }
                )
                .map { it.match }
                .distinctBy { "${it.equipaCasa.lowercase(Locale.ROOT)}_${it.equipaFora.lowercase(Locale.ROOT)}" }
                .take(10)

            val diag = when {
                sortedMatches.size >= 2 -> null
                successHttp.get() == 0 && failedHttp.get() > 0 -> {
                    "Falha de ligação à rede ao consultar jogos oficiais de Basquetebol (ESPN). Verifica a ligação e tenta novamente."
                }
                sortedMatches.size == 1 -> {
                    val only = sortedMatches.first()
                    "Apenas 1 jogo oficial de Basquetebol encontrado (${only.equipaCasa} vs ${only.equipaFora}). Marca mais 1 dia no seletor."
                }
                else -> {
                    val rangeLabel = if (selectedCycleInfo.startDateIso == selectedCycleInfo.endDateIso) {
                        selectedCycleInfo.startDateIso
                    } else {
                        "${selectedCycleInfo.startDateIso} a ${selectedCycleInfo.endDateIso}"
                    }
                    val hint = if (observedDates.isNotEmpty()) {
                        val pretty = observedDates.sorted().take(4).joinToString(", ") { formatIsoToShortPt(it) }
                        " Próximos dias com jogos confirmados: $pretty."
                    } else {
                        ""
                    }
                    "Sem jogos oficiais de Basquetebol (NBA/NCAA) entre $rangeLabel.$hint"
                }
            }

            BasketFetchPayload(
                effectiveCycleInfo = selectedCycleInfo,
                matches = sortedMatches,
                sources = allSources.distinctBy { it.uri },
                diagnosticError = diag
            )
        }

    private suspend fun fetchLeagues(
        leagues: List<BasketLeagueConfig>,
        compactDays: List<String>,
        allowedDatesWet: Set<String>,
        priorityBase: Int,
        successHttp: AtomicInteger,
        failedHttp: AtomicInteger
    ): Triple<List<DatedBasketMatch>, List<GroundingSource>, Set<String>> = coroutineScope {
        val semaphore = Semaphore(6)
        val jobs = leagues.map { league ->
            async {
                runCatching {
                    val uniqueEvents = LinkedHashMap<String, JSONObject>()
                    val observed = LinkedHashSet<String>()

                    for (dayCompact in compactDays) {
                        val url = "https://site.api.espn.com/apis/site/v2/sports/basketball/${league.slug}/scoreboard?dates=$dayCompact"
                        val body = semaphore.withPermit { httpGet(url, successHttp, failedHttp) } ?: continue
                        collectEvents(body, uniqueEvents, observed)
                    }

                    // Safety default scoreboard call if day queries had no matching WET dates
                    val hasMatch = uniqueEvents.values.any { ev ->
                        val comp = ev.optJSONArray("competitions")?.optJSONObject(0) ?: return@any false
                        val utc = comp.optString("date", ev.optString("date", ""))
                        val wet = extractPortugalIsoDate(utc)
                        wet != null && wet in allowedDatesWet
                    }
                    if (!hasMatch) {
                        val defUrl = "https://site.api.espn.com/apis/site/v2/sports/basketball/${league.slug}/scoreboard"
                        val body = semaphore.withPermit { httpGet(defUrl, successHttp, failedHttp) }
                        if (body != null) collectEvents(body, uniqueEvents, observed)
                    }

                    val matching = uniqueEvents.values.filter { ev ->
                        val comp = ev.optJSONArray("competitions")?.optJSONObject(0) ?: return@filter false
                        val utc = comp.optString("date", ev.optString("date", ""))
                        val wet = extractPortugalIsoDate(utc)
                        wet != null && wet in allowedDatesWet
                    }
                    if (matching.isEmpty()) {
                        return@runCatching Triple(emptyList<DatedBasketMatch>(), null as GroundingSource?, observed.toSet())
                    }

                    val standings = semaphore.withPermit { fetchStandings(league.slug, league.defaultPpg) }
                    val parsed = parseEvents(matching, league, standings, allowedDatesWet, priorityBase)
                    val source = if (parsed.isNotEmpty()) {
                        GroundingSource(
                            title = "ESPN • ${league.displayName}",
                            uri = "https://www.espn.com/${league.slug}/scoreboard"
                        )
                    } else null
                    Triple(parsed, source, observed.toSet())
                }.onFailure { err ->
                    Log.w(TAG, "Basketball fetch skipped for ${league.slug}: ${err.message}")
                }.getOrNull()
            }
        }

        val results = jobs.awaitAll().filterNotNull()
        Triple(
            results.flatMap { it.first },
            results.mapNotNull { it.second },
            results.flatMap { it.third }.toSet()
        )
    }

    private fun collectEvents(
        body: String,
        target: MutableMap<String, JSONObject>,
        observedDates: MutableSet<String>
    ) {
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return
        val events = root.optJSONArray("events") ?: return
        for (i in 0 until events.length()) {
            val ev = events.optJSONObject(i) ?: continue
            val id = ev.optString("id", "").ifBlank { "bk_${target.size}_$i" }
            target.putIfAbsent(id, ev)

            val comp = ev.optJSONArray("competitions")?.optJSONObject(0)
            val state = comp?.optJSONObject("status")?.optJSONObject("type")?.optString("state", "")
                .orEmpty().lowercase(Locale.ROOT)
            if (state != "post") {
                val utc = comp?.optString("date", ev.optString("date", "")) ?: ev.optString("date", "")
                extractPortugalIsoDate(utc)?.let { observedDates.add(it) }
            }
        }
    }

    private fun fetchStandings(slug: String, defaultPpg: Double): Map<String, BasketTeamStats> {
        val url = "https://site.api.espn.com/apis/v2/sports/basketball/$slug/standings"
        val body = runCatching { httpGet(url, null, null) }.getOrNull() ?: return emptyMap()
        return runCatching { parseStandings(JSONObject(body), defaultPpg) }.getOrDefault(emptyMap())
    }

    private fun parseStandings(root: JSONObject, defaultPpg: Double): Map<String, BasketTeamStats> {
        val map = mutableMapOf<String, BasketTeamStats>()

        fun consumeEntries(entries: JSONArray?) {
            if (entries == null) return
            for (i in 0 until entries.length()) {
                val entry = entries.optJSONObject(i) ?: continue
                val team = entry.optJSONObject("team") ?: continue
                val id = team.optString("id", "").trim()
                val name = team.optString("displayName", team.optString("name", "")).trim()
                if (id.isBlank() && name.isBlank()) continue

                val statsArr = entry.optJSONArray("stats") ?: JSONArray()
                var avgPf = Double.NaN
                var avgPa = Double.NaN
                var totalPf = Double.NaN
                var totalPa = Double.NaN
                var wins = 0.0
                var losses = 0.0
                var winPct = Double.NaN

                for (s in 0 until statsArr.length()) {
                    val stat = statsArr.optJSONObject(s) ?: continue
                    val sName = stat.optString("name", "")
                    val v = stat.optDouble("value", Double.NaN)
                    if (v.isNaN()) continue
                    when (sName) {
                        "avgPointsFor" -> avgPf = v
                        "avgPointsAgainst" -> avgPa = v
                        "pointsFor" -> totalPf = v
                        "pointsAgainst" -> totalPa = v
                        "wins" -> wins = v
                        "losses" -> losses = v
                        "winPercent" -> winPct = v
                    }
                }

                val gp = (wins + losses).coerceAtLeast(1.0)
                val finalPf = when {
                    !avgPf.isNaN() && avgPf > 40.0 -> avgPf
                    !totalPf.isNaN() && totalPf > 40.0 -> totalPf / gp
                    else -> defaultPpg
                }
                val finalPa = when {
                    !avgPa.isNaN() && avgPa > 40.0 -> avgPa
                    !totalPa.isNaN() && totalPa > 40.0 -> totalPa / gp
                    else -> defaultPpg
                }
                val finalWinPct = when {
                    !winPct.isNaN() && winPct in 0.0..1.0 -> winPct
                    wins + losses > 0 -> wins / (wins + losses)
                    else -> 0.50
                }

                val record = BasketTeamStats(id, name, finalPf, finalPa, finalWinPct)
                if (id.isNotBlank()) map[id] = record
                if (name.isNotBlank()) map[name.lowercase(Locale.ROOT)] = record
            }
        }

        consumeEntries(root.optJSONObject("standings")?.optJSONArray("entries"))
        val children = root.optJSONArray("children")
        if (children != null) {
            for (c in 0 until children.length()) {
                val child = children.optJSONObject(c) ?: continue
                consumeEntries(child.optJSONObject("standings")?.optJSONArray("entries"))
                val grandChildren = child.optJSONArray("children")
                if (grandChildren != null) {
                    for (g in 0 until grandChildren.length()) {
                        consumeEntries(grandChildren.optJSONObject(g)?.optJSONObject("standings")?.optJSONArray("entries"))
                    }
                }
            }
        }
        return map
    }

    private fun parseEvents(
        events: List<JSONObject>,
        league: BasketLeagueConfig,
        standings: Map<String, BasketTeamStats>,
        allowedDatesWet: Set<String>,
        priorityBase: Int
    ): List<DatedBasketMatch> {
        val results = mutableListOf<DatedBasketMatch>()
        val leagueAvg = standings.values
            .map { it.avgPtsFor }
            .takeIf { it.size >= 4 }
            ?.average()
            ?.coerceIn(60.0, 128.0)
            ?: league.defaultPpg

        events.forEachIndexed { idx, event ->
            val eventId = event.optString("id", "bk_$idx")
            val comp = event.optJSONArray("competitions")?.optJSONObject(0) ?: return@forEachIndexed
            val state = comp.optJSONObject("status")?.optJSONObject("type")?.optString("state", "")
                .orEmpty().lowercase(Locale.ROOT)
            if (state == "post") return@forEachIndexed

            val utcStr = comp.optString("date", event.optString("date", ""))
            val parsedDate = parseUtcDate(utcStr) ?: return@forEachIndexed
            val wetIso = formatDateToPortugalIso(parsedDate)
            if (wetIso !in allowedDatesWet) return@forEachIndexed

            val competitors = comp.optJSONArray("competitors") ?: return@forEachIndexed
            var homeObj: JSONObject? = null
            var awayObj: JSONObject? = null
            for (c in 0 until competitors.length()) {
                val item = competitors.optJSONObject(c) ?: continue
                when (item.optString("homeAway", "").lowercase(Locale.ROOT)) {
                    "home" -> homeObj = item
                    "away" -> awayObj = item
                }
            }
            if (homeObj == null || awayObj == null) return@forEachIndexed

            val homeTeam = homeObj.optJSONObject("team") ?: return@forEachIndexed
            val awayTeam = awayObj.optJSONObject("team") ?: return@forEachIndexed
            val homeName = homeTeam.optString("displayName", homeTeam.optString("shortDisplayName", "")).trim()
            val awayName = awayTeam.optString("displayName", awayTeam.optString("shortDisplayName", "")).trim()
            if (homeName.isBlank() || awayName.isBlank()) return@forEachIndexed

            val homeStats = standings[homeTeam.optString("id", "")] ?: standings[homeName.lowercase(Locale.ROOT)]
            val awayStats = standings[awayTeam.optString("id", "")] ?: standings[awayName.lowercase(Locale.ROOT)]

            val homeWinRate = homeStats?.winPercent ?: parseRecordWinRate(homeObj.optJSONArray("records")) ?: 0.50
            val awayWinRate = awayStats?.winPercent ?: parseRecordWinRate(awayObj.optJSONArray("records")) ?: 0.50

            // Empirical points projection blending team attack + opponent defense + home-court boost
            val homeOff = homeStats?.avgPtsFor ?: (leagueAvg + (homeWinRate - 0.5) * 10.0)
            val homeDef = homeStats?.avgPtsAgainst ?: (leagueAvg - (homeWinRate - 0.5) * 8.0)
            val awayOff = awayStats?.avgPtsFor ?: (leagueAvg + (awayWinRate - 0.5) * 10.0)
            val awayDef = awayStats?.avgPtsAgainst ?: (leagueAvg - (awayWinRate - 0.5) * 8.0)

            val halfBoost = league.homeCourtBoost / 2.0
            val projHome = round1(((homeOff + awayDef) / 2.0 + halfBoost).coerceIn(55.0, 138.0))
            val projAway = round1(((awayOff + homeDef) / 2.0 - halfBoost).coerceIn(55.0, 136.0))

            // Extract real ESPN betting lines (overUnder, spread, moneyline) when available
            val oddsArr = comp.optJSONArray("odds")
            val firstOdds = oddsArr?.optJSONObject(0)
            val providerName = firstOdds?.optJSONObject("provider")?.optString("name", null)

            val rawOverUnder = firstOdds?.optDouble("overUnder", Double.NaN) ?: Double.NaN
            val totalLine = if (!rawOverUnder.isNaN() && rawOverUnder in 110.0..280.0) {
                BasketballQuantEngine.roundHalf(rawOverUnder)
            } else {
                BasketballQuantEngine.roundHalf(projHome + projAway)
            }

            // ESPN `spread` is typically the home team's spread (negative if home is favored)
            val rawSpread = firstOdds?.optDouble("spread", Double.NaN) ?: Double.NaN
            val homeSpreadLine = if (!rawSpread.isNaN() && abs(rawSpread) <= 35.0) {
                BasketballQuantEngine.roundHalf(rawSpread)
            } else {
                BasketballQuantEngine.roundHalf(-(projHome - projAway))
            }

            val q1TotalLine = BasketballQuantEngine.roundHalf(totalLine * 0.252)
            val htTotalLine = BasketballQuantEngine.roundHalf(totalLine * 0.505)
            val impliedHomeShare = (totalLine - homeSpreadLine) / 2.0
            val homeTeamTotalLine = BasketballQuantEngine.roundHalf(impliedHomeShare)
            val safeCushion = if (league.isNbaFormat) 6.5 else 5.5
            val altSafeTotalLine = BasketballQuantEngine.roundHalf(totalLine - safeCushion)

            val sigmaMargin = if (league.isNbaFormat) 12.2 else 10.8
            val fairHomeProb = BasketballQuantEngine.normalCdf((-homeSpreadLine) / sigmaMargin).coerceIn(0.08, 0.92)
            val fairAwayProb = 1.0 - fairHomeProb

            val realHomeMl = parseEspnOdd(firstOdds?.optJSONObject("homeTeamOdds"))
            val realAwayMl = parseEspnOdd(firstOdds?.optJSONObject("awayTeamOdds"))
            val oddHomeMl = realHomeMl ?: round2((1.0 / (fairHomeProb * 1.05)).coerceIn(1.06, 12.0))
            val oddAwayMl = realAwayMl ?: round2((1.0 / (fairAwayProb * 1.05)).coerceIn(1.06, 12.0))

            // Standard Betclic.pt basketball 2-way lines (~1.86–1.90) & alternative safe line (~1.38–1.44)
            val oddHomeSpread = parseSpreadOdd(firstOdds?.optJSONObject("homeTeamOdds")) ?: 1.88
            val oddAwaySpread = parseSpreadOdd(firstOdds?.optJSONObject("awayTeamOdds")) ?: 1.88
            val oddTotalOver = parseAmericanField(firstOdds, "overOdds") ?: 1.88
            val oddTotalUnder = parseAmericanField(firstOdds, "underOdds") ?: 1.88

            val notes = buildString {
                append(
                    String.format(
                        Locale.US,
                        "Médias época: %s %.1f/%.1f pts (%.0f%% V) vs %s %.1f/%.1f pts (%.0f%% V)",
                        homeName.take(14), homeOff, homeDef, homeWinRate * 100.0,
                        awayName.take(14), awayOff, awayDef, awayWinRate * 100.0
                    )
                )
                if (providerName != null) {
                    append(" • Linhas ao vivo ($providerName) • Editável para Betclic.pt")
                } else {
                    append(" • Linhas base • Toca em 'Ajustar Linhas & Odds Betclic.pt' na Matriz")
                }
            }

            results.add(
                DatedBasketMatch(
                    kickoffMillis = parsedDate.time,
                    isLive = state == "in",
                    priority = priorityBase,
                    match = RawBasketballMatchData(
                        id = "espn_bk_${league.slug}_$eventId",
                        dataHoraWet = formatDateToPortugalLabel(parsedDate),
                        competicao = league.displayName,
                        equipaCasa = homeName,
                        equipaFora = awayName,
                        expectedPtsHome = projHome,
                        expectedPtsAway = projAway,
                        paceNotes = notes,
                        homeWinRate = homeWinRate,
                        awayWinRate = awayWinRate,
                        isNbaFormat = league.isNbaFormat,
                        totalLine = totalLine,
                        homeSpreadLine = homeSpreadLine,
                        q1TotalLine = q1TotalLine,
                        htTotalLine = htTotalLine,
                        homeTeamTotalLine = homeTeamTotalLine,
                        altSafeTotalLine = altSafeTotalLine,
                        oddHomeMl = oddHomeMl,
                        oddAwayMl = oddAwayMl,
                        oddHomeSpread = oddHomeSpread,
                        oddAwaySpread = oddAwaySpread,
                        oddTotalOver = oddTotalOver,
                        oddTotalUnder = oddTotalUnder,
                        oddQ1Over = 1.87,
                        oddQ1Under = 1.87,
                        oddHtOver = 1.87,
                        oddHomeTeamOver = 1.86,
                        oddAltSafeOver = 1.40,
                        fontesVerificadas = listOfNotNull(
                            "ESPN • ${league.displayName}",
                            providerName?.let { "Linhas: $it" }
                        )
                    )
                )
            )
        }
        return results
    }

    private fun parseRecordWinRate(records: JSONArray?): Double? {
        if (records == null || records.length() == 0) return null
        val summary = records.optJSONObject(0)?.optString("summary", "").orEmpty().trim()
        val parts = summary.split("-")
        if (parts.size < 2) return null
        val w = parts[0].toDoubleOrNull() ?: return null
        val l = parts[1].toDoubleOrNull() ?: return null
        return if (w + l > 0.0) (w / (w + l)).coerceIn(0.10, 0.90) else null
    }

    private fun parseEspnOdd(obj: JSONObject?): Double? {
        if (obj == null) return null
        val ml = obj.optDouble("moneyLine", Double.NaN)
        if (!ml.isNaN() && abs(ml) >= 100.0) return americanToDecimal(ml)
        return null
    }

    private fun parseSpreadOdd(obj: JSONObject?): Double? {
        if (obj == null) return null
        val sp = obj.optDouble("spreadOdds", Double.NaN)
        if (!sp.isNaN() && abs(sp) >= 100.0) return americanToDecimal(sp)
        return null
    }

    private fun parseAmericanField(obj: JSONObject?, key: String): Double? {
        if (obj == null) return null
        val v = obj.optDouble(key, Double.NaN)
        if (!v.isNaN() && abs(v) >= 100.0) return americanToDecimal(v)
        return null
    }

    internal fun americanToDecimal(american: Double): Double {
        val raw = if (american > 0.0) 1.0 + (american / 100.0) else 1.0 + (100.0 / abs(american))
        return round2(raw.coerceIn(1.04, 25.0))
    }

    /**
     * Expands each selected Portugal WET date D to include both (D - 1 day) and D so US evening
     * tip-offs (00:00–04:00 WET next day) are never missed by ESPN's US Eastern date bucket.
     */
    internal fun expandCompactDaysForUsTimezone(isoDates: List<String>): List<String> {
        val lisbonTz = TimeZone.getTimeZone("Europe/Lisbon")
        val isoFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = lisbonTz }
        val compactFmt = SimpleDateFormat("yyyyMMdd", Locale.US).apply { timeZone = lisbonTz }
        val set = LinkedHashSet<String>()
        for (iso in isoDates) {
            val d = runCatching { isoFmt.parse(iso) }.getOrNull() ?: continue
            val prev = Calendar.getInstance(lisbonTz).apply {
                time = d
                add(Calendar.DAY_OF_MONTH, -1)
            }
            set.add(compactFmt.format(prev.time))
            set.add(compactFmt.format(d))
        }
        return set.toList()
    }

    private fun parseUtcDate(isoUtc: String): Date? {
        if (isoUtc.isBlank()) return null
        val utcTz = TimeZone.getTimeZone("UTC")
        val patterns = listOf(
            "yyyy-MM-dd'T'HH:mm'Z'",
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
            "yyyy-MM-dd'T'HH:mmXXX",
            "yyyy-MM-dd'T'HH:mm:ssXXX"
        )
        for (pattern in patterns) {
            val parsed = runCatching {
                SimpleDateFormat(pattern, Locale.US).apply {
                    timeZone = utcTz
                    isLenient = false
                }.parse(isoUtc)
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
        val out = SimpleDateFormat("dd/MM HH:mm", Locale("pt", "PT")).apply { timeZone = lisbonTz }
        val suffix = if (lisbonTz.inDaylightTime(date)) "WEST" else "WET"
        return "${out.format(date)} $suffix"
    }

    private fun formatIsoToShortPt(iso: String): String {
        val p = iso.split("-")
        return if (p.size == 3) "${p[2]}/${p[1]}" else iso
    }

    private fun httpGet(
        urlString: String,
        successCounter: AtomicInteger?,
        failedCounter: AtomicInteger?
    ): String? {
        return try {
            val conn = (URL(urlString).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 9_000
                readTimeout = 9_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 15) BetQuantAI/1.0")
                setRequestProperty("Accept", "application/json")
            }
            try {
                val code = conn.responseCode
                if (code in 200..299) {
                    successCounter?.incrementAndGet()
                    conn.inputStream.bufferedReader().use { it.readText() }
                } else {
                    failedCounter?.incrementAndGet()
                    Log.w(TAG, "HTTP $code for $urlString")
                    null
                }
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            failedCounter?.incrementAndGet()
            Log.w(TAG, "HTTP error for $urlString: ${e.message}")
            null
        }
    }

    private fun round1(v: Double): Double = round(v * 10.0) / 10.0
    private fun round2(v: Double): Double = round(v * 100.0) / 100.0
}
