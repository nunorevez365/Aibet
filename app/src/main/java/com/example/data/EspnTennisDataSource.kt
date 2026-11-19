package com.example.data

import android.util.Log
import com.example.quant.GroundingSource
import com.example.quant.RawTennisMatchData
import com.example.quant.TemporalCycleInfo
import com.example.quant.TennisQuantEngine
import com.example.quant.TennisSurface
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
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.round

private const val TAG = "EspnTennisSource"

/**
 * Real-time public Tennis fixtures, ATP/WTA rankings, surface inference, and live lines
 * from ESPN's official Tennis JSON endpoints (`atp` and `wta`).
 *
 * ESPN's Tennis scoreboard nests matches inside `events[i].groupings[g].competitions[c]`
 * (by draw: Men's Singles, Women's Singles, etc.) OR directly in `events[i].competitions[c]`.
 * Both layouts are parsed cleanly, strictly filtered to the user's selected Portugal WET/WEST
 * calendar dates.
 */
class EspnTennisDataSource {

    private data class TourConfig(
        val slug: String,
        val tourCode: String,
        val displayName: String
    )

    private data class DatedTennisMatch(
        val kickoffMillis: Long,
        val isLive: Boolean,
        val isSingles: Boolean,
        val match: RawTennisMatchData
    )

    private val tours = listOf(
        TourConfig("atp", "ATP", "ATP Tour"),
        TourConfig("wta", "WTA", "WTA Tour")
    )

    data class TennisFetchPayload(
        val effectiveCycleInfo: TemporalCycleInfo,
        val matches: List<RawTennisMatchData>,
        val sources: List<GroundingSource>,
        val diagnosticError: String? = null
    )

    suspend fun fetchConfirmedMatches(selectedCycleInfo: TemporalCycleInfo): TennisFetchPayload =
        withContext(Dispatchers.IO) {
            val allowedDatesWet = selectedCycleInfo.exactDatesIso.toSet()
            val compactDays = selectedCycleInfo.exactDatesIso
                .mapNotNull { isoToCompactDate(it) }
                .distinct()

            val successHttp = AtomicInteger(0)
            val failedHttp = AtomicInteger(0)
            val semaphore = Semaphore(6)

            val tourResults = coroutineScope {
                tours.map { tour ->
                    async {
                        runCatching {
                            val rankings = semaphore.withPermit { fetchRankingsMap(tour.slug) }
                            val collected = LinkedHashMap<String, DatedTennisMatch>()
                            val observedDates = LinkedHashSet<String>()

                            // 1. Query day-by-day using single-date `?dates=YYYYMMDD`
                            for (dayCompact in compactDays) {
                                val url = "https://site.api.espn.com/apis/site/v2/sports/tennis/${tour.slug}/scoreboard?dates=$dayCompact"
                                val body = semaphore.withPermit { httpGet(url, successHttp, failedHttp) } ?: continue
                                parseScoreboardJson(
                                    body = body,
                                    tour = tour,
                                    rankings = rankings,
                                    allowedDatesWet = allowedDatesWet,
                                    outMatches = collected,
                                    observedUpcomingDates = observedDates
                                )
                            }

                            // 2. Also inspect default `/scoreboard` to capture active tournaments & upcoming date hints
                            if (collected.isEmpty()) {
                                val defUrl = "https://site.api.espn.com/apis/site/v2/sports/tennis/${tour.slug}/scoreboard"
                                val body = semaphore.withPermit { httpGet(defUrl, successHttp, failedHttp) }
                                if (body != null) {
                                    parseScoreboardJson(
                                        body = body,
                                        tour = tour,
                                        rankings = rankings,
                                        allowedDatesWet = allowedDatesWet,
                                        outMatches = collected,
                                        observedUpcomingDates = observedDates
                                    )
                                }
                            }

                            val matchList = collected.values.toList()
                            val source = if (matchList.isNotEmpty()) {
                                GroundingSource(
                                    title = "ESPN • ${tour.displayName}",
                                    uri = "https://www.espn.com/tennis/scoreboard/_/tour/${tour.slug}"
                                )
                            } else {
                                null
                            }
                            Triple(matchList, source, observedDates.toSet())
                        }.onFailure { err ->
                            Log.w(TAG, "Tennis fetch skipped for ${tour.slug}: ${err.message}")
                        }.getOrNull()
                    }
                }.awaitAll().filterNotNull()
            }

            val allDated = tourResults.flatMap { it.first }
            val allSources = tourResults.mapNotNull { it.second }.distinctBy { it.uri }
            val observedDates = tourResults.flatMap { it.third }.distinct().sorted()

            // Prioritize Singles over Doubles, upcoming over live, chronological order
            val singlesOnly = allDated.filter { it.isSingles }
            val chosenPool = if (singlesOnly.size >= 2) singlesOnly else allDated

            val sortedMatches = chosenPool
                .sortedWith(
                    compareByDescending<DatedTennisMatch> { it.isSingles }
                        .thenBy { it.isLive }
                        .thenBy { it.kickoffMillis }
                )
                .map { it.match }
                .distinctBy { "${it.jogador1.lowercase(Locale.ROOT)}_${it.jogador2.lowercase(Locale.ROOT)}" }
                .take(10)

            val diag = when {
                sortedMatches.size >= 2 -> null
                successHttp.get() == 0 && failedHttp.get() > 0 -> {
                    "Falha de ligação à rede ao consultar jogos oficiais de Ténis (ATP/WTA). Verifica a ligação e tenta novamente."
                }
                sortedMatches.size == 1 -> {
                    val only = sortedMatches.first()
                    "Apenas 1 encontro oficial de Ténis encontrado (${only.jogador1} vs ${only.jogador2}). Marca mais dias no seletor acima."
                }
                else -> {
                    val rangeLabel = if (selectedCycleInfo.startDateIso == selectedCycleInfo.endDateIso) {
                        selectedCycleInfo.startDateIso
                    } else {
                        "${selectedCycleInfo.startDateIso} a ${selectedCycleInfo.endDateIso}"
                    }
                    val hint = if (observedDates.isNotEmpty()) {
                        val pretty = observedDates.take(4).joinToString(", ") { formatIsoToShortPt(it) }
                        " Próximos dias com encontros ATP/WTA confirmados: $pretty."
                    } else {
                        ""
                    }
                    "Sem encontros oficiais ATP/WTA agendados entre $rangeLabel.$hint"
                }
            }

            TennisFetchPayload(
                effectiveCycleInfo = selectedCycleInfo,
                matches = sortedMatches,
                sources = allSources,
                diagnosticError = diag
            )
        }

    internal fun parseScoreboardJson(
        body: String,
        tourSlug: String,
        tourCode: String,
        rankings: Map<String, Int>,
        allowedDatesWet: Set<String>
    ): List<RawTennisMatchData> {
        val out = LinkedHashMap<String, DatedTennisMatch>()
        val obs = LinkedHashSet<String>()
        parseScoreboardJson(
            body = body,
            tour = TourConfig(tourSlug, tourCode, "$tourCode Tour"),
            rankings = rankings,
            allowedDatesWet = allowedDatesWet,
            outMatches = out,
            observedUpcomingDates = obs
        )
        return out.values.map { it.match }
    }

    private fun parseScoreboardJson(
        body: String,
        tour: TourConfig,
        rankings: Map<String, Int>,
        allowedDatesWet: Set<String>,
        outMatches: MutableMap<String, DatedTennisMatch>,
        observedUpcomingDates: MutableSet<String>
    ) {
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return
        val events = root.optJSONArray("events") ?: return

        for (i in 0 until events.length()) {
            val event = events.optJSONObject(i) ?: continue
            val tournamentName = event.optString("name", event.optString("shortName", "${tour.tourCode} Tournament")).trim()
            val surface = inferSurface(tournamentName)

            // Layout A: ESPN Tennis nested groupings -> competitions
            val groupings = event.optJSONArray("groupings")
            if (groupings != null && groupings.length() > 0) {
                for (g in 0 until groupings.length()) {
                    val grp = groupings.optJSONObject(g) ?: continue
                    val grpSlug = grp.optJSONObject("grouping")?.optString("slug", "").orEmpty().lowercase(Locale.ROOT)
                    val isDoubles = grpSlug.contains("double") || grpSlug.contains("mixed")
                    val comps = grp.optJSONArray("competitions") ?: continue
                    for (c in 0 until comps.length()) {
                        val comp = comps.optJSONObject(c) ?: continue
                        consumeCompetition(
                            comp = comp,
                            tournamentName = tournamentName,
                            surface = surface,
                            tour = tour,
                            isSingles = !isDoubles,
                            rankings = rankings,
                            allowedDatesWet = allowedDatesWet,
                            outMatches = outMatches,
                            observedUpcomingDates = observedUpcomingDates
                        )
                    }
                }
            }

            // Layout B: Direct event.competitions
            val directComps = event.optJSONArray("competitions")
            if (directComps != null && directComps.length() > 0) {
                for (c in 0 until directComps.length()) {
                    val comp = directComps.optJSONObject(c) ?: continue
                    val typeSlug = comp.optJSONObject("type")?.optString("slug", "").orEmpty().lowercase(Locale.ROOT)
                    val isDoubles = typeSlug.contains("double")
                    consumeCompetition(
                        comp = comp,
                        tournamentName = tournamentName,
                        surface = surface,
                        tour = tour,
                        isSingles = !isDoubles,
                        rankings = rankings,
                        allowedDatesWet = allowedDatesWet,
                        outMatches = outMatches,
                        observedUpcomingDates = observedUpcomingDates
                    )
                }
            }
        }
    }

    private fun consumeCompetition(
        comp: JSONObject,
        tournamentName: String,
        surface: TennisSurface,
        tour: TourConfig,
        isSingles: Boolean,
        rankings: Map<String, Int>,
        allowedDatesWet: Set<String>,
        outMatches: MutableMap<String, DatedTennisMatch>,
        observedUpcomingDates: MutableSet<String>
    ) {
        val statusState = comp.optJSONObject("status")
            ?.optJSONObject("type")
            ?.optString("state", "")
            .orEmpty()
            .lowercase(Locale.ROOT)
        if (statusState == "post") return

        val utcStr = comp.optString("date", "")
        val parsedDate = parseUtcDate(utcStr) ?: return
        val wetIso = formatDateToPortugalIso(parsedDate)
        observedUpcomingDates.add(wetIso)

        if (wetIso !in allowedDatesWet) return

        val competitors = comp.optJSONArray("competitors") ?: return
        if (competitors.length() < 2) return

        val c1 = competitors.optJSONObject(0) ?: return
        val c2 = competitors.optJSONObject(1) ?: return

        val p1Name = extractPlayerName(c1)
        val p2Name = extractPlayerName(c2)
        if (p1Name.isBlank() || p2Name.isBlank() ||
            p1Name.equals("TBD", ignoreCase = true) ||
            p2Name.equals("TBD", ignoreCase = true)
        ) return

        val rank1 = extractRank(c1, p1Name, rankings)
        val rank2 = extractRank(c2, p2Name, rankings)

        val roundName = comp.optJSONObject("round")?.optString("displayName", "")
            ?.ifBlank { "Ronda Oficial" } ?: "Ronda Oficial"
        val regPeriods = comp.optJSONObject("format")?.optJSONObject("regulation")?.optInt("periods", 0) ?: 0
        val bestOf = when {
            regPeriods == 5 -> 5
            regPeriods == 3 -> 3
            tour.tourCode == "ATP" && isSingles && isGrandSlam(tournamentName) -> 5
            else -> 3
        }

        val (hold1, hold2) = estimateServeHoldProbs(
            tourCode = tour.tourCode,
            surface = surface,
            rank1 = rank1,
            rank2 = rank2
        )

        // Compute model fair lines & odds, overriding ML if live ESPN odds exist
        val previewSummary = TennisQuantEngine.computeExactSetDistribution(hold1, hold2)
        val pSet = previewSummary.probP1WinsSet
        val qSet = 1.0 - pSet
        val fairP1Win = if (bestOf == 5) {
            (pSet * pSet * pSet * (1.0 + 3.0 * qSet + 6.0 * qSet * qSet)).coerceIn(0.05, 0.95)
        } else {
            (pSet * pSet * (1.0 + 2.0 * qSet)).coerceIn(0.05, 0.95)
        }
        val fairP2Win = 1.0 - fairP1Win

        val oddsObj = comp.optJSONArray("odds")?.optJSONObject(0)
        val providerName = oddsObj?.optJSONObject("provider")?.optString("name", null)
        val realOdd1 = parseAmericanOrDecimal(oddsObj?.optJSONObject("homeTeamOdds"))
        val realOdd2 = parseAmericanOrDecimal(oddsObj?.optJSONObject("awayTeamOdds"))

        val margin = 1.055
        fun fairToOdd(prob: Double): Double {
            val safe = prob.coerceIn(0.05, 0.93)
            return round2((1.0 / (safe * margin)).coerceIn(1.06, 16.0))
        }

        val oddP1Ml = realOdd1 ?: fairToOdd(fairP1Win)
        val oddP2Ml = realOdd2 ?: fairToOdd(fairP2Win)
        val oddP1Set1 = fairToOdd(pSet)
        val oddP2Set1 = fairToOdd(qSet)

        // Expected total games & handicap lines rounded to realistic Betclic .5 lines
        val expectedSets = if (bestOf == 5) {
            val p3 = pSet * pSet * pSet + qSet * qSet * qSet
            val p4 = 3.0 * pSet * pSet * pSet * qSet + 3.0 * qSet * qSet * qSet * pSet
            val p5 = 1.0 - p3 - p4
            3.0 * p3 + 4.0 * p4 + 5.0 * p5
        } else {
            2.0 * (pSet * pSet + qSet * qSet) + 3.0 * (2.0 * pSet * qSet)
        }
        val rawTotalGames = (previewSummary.expectedGames * expectedSets).coerceIn(16.5, 45.5)
        val totalGamesLine = TennisQuantEngine.roundHalf(rawTotalGames)

        val rawP1Margin = (previewSummary.expectedDiffP1 * expectedSets).coerceIn(-10.5, 10.5)
        val p1GameHcLine = TennisQuantEngine.roundHalf(-rawP1Margin)

        val probStraightP1 = if (bestOf == 5) pSet * pSet * pSet else pSet * pSet
        val probOverSets = if (bestOf == 5) {
            1.0 - (pSet * pSet * pSet + qSet * qSet * qSet)
        } else {
            2.0 * pSet * qSet
        }
        val noTb = 1.0 - previewSummary.probTiebreak
        val probTbMatch = (1.0 - Math.pow(noTb, expectedSets)).coerceIn(0.08, 0.88)

        val compId = comp.optString("id", "").ifBlank { "${tour.slug}_${p1Name}_${p2Name}" }
        val r1Label = rank1?.let { "#$it" } ?: "S/R"
        val r2Label = rank2?.let { "#$it" } ?: "S/R"
        val notes = buildString {
            append("${tour.tourCode} • ${surface.label} • Bo$bestOf • ")
            append("${TennisQuantEngine.shortSurname(p1Name)} ($r1Label, Hold ${(hold1 * 100).toInt()}%) vs ")
            append("${TennisQuantEngine.shortSurname(p2Name)} ($r2Label, Hold ${(hold2 * 100).toInt()}%)")
            if (providerName != null) {
                append(" • Odds ao vivo ($providerName)")
            } else {
                append(" • Linhas base • Editável para Betclic.pt")
            }
        }

        val rawMatch = RawTennisMatchData(
            id = "espn_tn_${tour.slug}_$compId",
            dataHoraWet = formatDateToPortugalLabel(parsedDate),
            torneio = tournamentName,
            ronda = roundName,
            circuito = tour.tourCode,
            superficie = surface,
            bestOfSets = bestOf,
            jogador1 = p1Name,
            jogador2 = p2Name,
            ranking1 = rank1,
            ranking2 = rank2,
            serveHoldProb1 = round3(hold1),
            serveHoldProb2 = round3(hold2),
            surfaceNotes = notes,
            totalGamesLine = totalGamesLine,
            p1GameHandicapLine = p1GameHcLine,
            oddP1Ml = oddP1Ml,
            oddP2Ml = oddP2Ml,
            oddP1Set1 = oddP1Set1,
            oddP2Set1 = oddP2Set1,
            oddGamesOver = 1.87,
            oddGamesUnder = 1.87,
            oddP1GameHc = 1.87,
            oddP2GameHc = 1.87,
            oddP1StraightSets = fairToOdd(probStraightP1),
            oddOverSets = fairToOdd(probOverSets),
            oddTiebreakYes = fairToOdd(probTbMatch),
            fontesVerificadas = listOfNotNull(
                "ESPN • ${tour.displayName} ($tournamentName)",
                providerName?.let { "Odds: $it" }
            )
        )

        outMatches.putIfAbsent(
            compId,
            DatedTennisMatch(
                kickoffMillis = parsedDate.time,
                isLive = statusState == "in",
                isSingles = isSingles,
                match = rawMatch
            )
        )
    }

    private fun extractPlayerName(competitor: JSONObject): String {
        val athlete = competitor.optJSONObject("athlete")
        if (athlete != null) {
            val full = athlete.optString("displayName", athlete.optString("fullName", athlete.optString("shortName", ""))).trim()
            if (full.isNotBlank()) return full
        }
        val roster = competitor.optJSONObject("roster")
        if (roster != null) {
            val rName = roster.optString("shortDisplayName", roster.optString("displayName", "")).trim()
            if (rName.isNotBlank()) return rName
        }
        val team = competitor.optJSONObject("team")
        if (team != null) {
            val tName = team.optString("displayName", team.optString("shortDisplayName", "")).trim()
            if (tName.isNotBlank()) return tName
        }
        return ""
    }

    private fun extractRank(
        competitor: JSONObject,
        playerName: String,
        rankings: Map<String, Int>
    ): Int? {
        val curated = competitor.optJSONObject("curatedRank")?.optInt("current", 0) ?: 0
        if (curated in 1..2000) return curated
        val rankField = competitor.optInt("rank", 0)
        if (rankField in 1..2000) return rankField
        val athleteId = competitor.optJSONObject("athlete")?.optString("id", "").orEmpty()
        if (athleteId.isNotBlank() && rankings.containsKey("id:$athleteId")) {
            return rankings["id:$athleteId"]
        }
        return rankings[playerName.lowercase(Locale.ROOT)]
    }

    private fun fetchRankingsMap(slug: String): Map<String, Int> {
        val url = "https://site.api.espn.com/apis/site/v2/sports/tennis/$slug/rankings"
        val body = runCatching { httpGet(url, null, null) }.getOrNull() ?: return emptyMap()
        return runCatching { parseRankingsJson(JSONObject(body)) }.getOrDefault(emptyMap())
    }

    private fun parseRankingsJson(root: JSONObject): Map<String, Int> {
        val map = mutableMapOf<String, Int>()
        val rankingsArr = root.optJSONArray("rankings") ?: return map
        for (i in 0 until rankingsArr.length()) {
            val group = rankingsArr.optJSONObject(i) ?: continue
            val ranks = group.optJSONArray("ranks") ?: continue
            for (r in 0 until ranks.length()) {
                val item = ranks.optJSONObject(r) ?: continue
                val current = item.optInt("current", 0)
                if (current <= 0) continue
                val athlete = item.optJSONObject("athlete") ?: continue
                val id = athlete.optString("id", "").trim()
                val name = athlete.optString("displayName", athlete.optString("fullName", "")).trim()
                if (id.isNotBlank()) map["id:$id"] = current
                if (name.isNotBlank()) map[name.lowercase(Locale.ROOT)] = current
            }
        }
        return map
    }

    private fun estimateServeHoldProbs(
        tourCode: String,
        surface: TennisSurface,
        rank1: Int?,
        rank2: Int?
    ): Pair<Double, Double> {
        val baseHold = if (tourCode.equals("WTA", ignoreCase = true)) {
            surface.wtaBaseHold
        } else {
            surface.atpBaseHold
        }
        val r1 = (rank1 ?: 65).coerceIn(1, 500)
        val r2 = (rank2 ?: 65).coerceIn(1, 500)

        // Log-rank Elo difference maps smoothly to serve-hold edge
        val logDiff = ln(r2.toDouble()) - ln(r1.toDouble())
        val holdDelta = (logDiff * 0.032).coerceIn(-0.11, 0.11)
        val h1 = (baseHold + holdDelta).coerceIn(0.48, 0.92)
        val h2 = (baseHold - holdDelta).coerceIn(0.48, 0.92)
        return h1 to h2
    }

    internal fun inferSurface(tournamentName: String): TennisSurface {
        val lower = tournamentName.lowercase(Locale.ROOT)
        return when {
            lower.contains("roland garros") || lower.contains("french open") ||
                lower.contains("monte-carlo") || lower.contains("monte carlo") ||
                lower.contains("madrid") || lower.contains("roma") || lower.contains("rome") ||
                lower.contains("barcelona") || lower.contains("rio") || lower.contains("buenos aires") ||
                lower.contains("estoril") || lower.contains("munich") || lower.contains("hamburg") ||
                lower.contains("bastad") || lower.contains("kitzbuhel") || lower.contains("clay") -> TennisSurface.CLAY

            lower.contains("wimbledon") || lower.contains("queen") || lower.contains("halle") ||
                lower.contains("eastbourne") || lower.contains("mallorca") ||
                lower.contains("s-hertogenbosch") || lower.contains("grass") -> TennisSurface.GRASS

            lower.contains("finals") || lower.contains("davis cup") || lower.contains("billie jean") ||
                lower.contains("paris") || lower.contains("vienna") || lower.contains("basel") ||
                lower.contains("rotterdam") || lower.contains("marseille") || lower.contains("metz") ||
                lower.contains("stockholm") || lower.contains("antwerp") || lower.contains("indoor") -> TennisSurface.INDOOR

            else -> TennisSurface.HARD
        }
    }

    private fun isGrandSlam(tournamentName: String): Boolean {
        val lower = tournamentName.lowercase(Locale.ROOT)
        return lower.contains("australian open") ||
            lower.contains("roland garros") ||
            lower.contains("french open") ||
            lower.contains("wimbledon") ||
            lower.contains("us open")
    }

    private fun parseAmericanOrDecimal(obj: JSONObject?): Double? {
        if (obj == null) return null
        val dec = obj.optDouble("decimal", Double.NaN)
        if (!dec.isNaN() && dec > 1.02) return round2(dec)
        val ml = obj.optDouble("moneyLine", Double.NaN)
        if (!ml.isNaN() && abs(ml) >= 100.0) {
            val raw = if (ml > 0.0) 1.0 + (ml / 100.0) else 1.0 + (100.0 / abs(ml))
            return round2(raw.coerceIn(1.04, 25.0))
        }
        return null
    }

    private fun isoToCompactDate(isoDate: String): String? {
        val cleaned = isoDate.trim().replace("-", "")
        return if (cleaned.length == 8 && cleaned.all { it.isDigit() }) cleaned else null
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

    private fun round2(v: Double): Double = round(v * 100.0) / 100.0
    private fun round3(v: Double): Double = round(v * 1000.0) / 1000.0
}
