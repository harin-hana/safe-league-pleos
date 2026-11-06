package ai.pleos.contest.league

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

// 리그 순위표 한 장. 서버 응답 또는 고스트 리그(앱 안 시드).
data class Row(val rank: Int, val name: String, val score: Double, val me: Boolean = false)
data class Board(
    val rows: List<Row>, val myRank: Int, val total: Int,
    val league: Boolean,              // 3명 이상(상금 있음). 아니면 고스트 리그
    val morningLine: String?,         // 「오늘 아침 리그 2위 99.7」. 아침 기록이 있을 때만
    val ghostDiff: Double?,           // 어제의 나와 차이(+면 위)
    val source: String,               // "서버" 또는 "고스트"
)
data class Prize(val title: String, val sum: Double, val shop: String, val item: String, val meta: String, val lat: Double, val lng: Double)

// 앱 안 시드(repo_draft/server/seed_demo.sql과 같은 값). 서버가 없거나 막히면 이걸로 고스트 리그.
object Ghost {
    val pm = listOf("아침첫차" to 100.0, "올림픽대로" to 98.8, "깜빡이장인" to 98.1, "비오는날" to 97.4, "월요일만" to 96.7, "아침7시42" to 96.0)
    val morningMe = 2 to 99.7
    const val yesterdayMe = 99.3
    val names get() = pm.map { it.first }

    fun board(myScore: Double, members: List<Pair<String, Double>> = pm): Board {
        val all = (members.map { it.first to it.second } + ("나" to myScore)).sortedByDescending { it.second }
        val rows = all.mapIndexed { i, (n, s) -> Row(i + 1, n, s, me = n == "나") }
        val league = all.size >= 3
        return Board(rows, rows.first { it.me }.rank, rows.size, league,
            "오늘 아침 리그 ${morningMe.first}위 ${Score.fmt(morningMe.second)}", Score.round1(myScore - yesterdayMe), "고스트")
    }

    val prize = Prize("지난주 왕중왕", 298.6, "길 위 카페", "아메리카노 한 잔", "출근길 위 2.3 km 지점. 들르면 도착 +6분. 이번 주 안에", 37.4979, 127.0276)
}

// 리그 서버(Cloudflare Workers + D1). 주행 중 통신 없음. 도착 때 POST /trip 한 번, 다음 출발 카드에서 GET /prize 한 번.
object LeagueClient {
    const val DEFAULT_URL = "https://safe-league.alwaysharin.workers.dev"   // 설정에서 비우면 고스트 리그
    @Volatile var baseUrl = DEFAULT_URL
    const val TIMEOUT = 3000

    fun upload(driverId: String, nickname: String, leagueKey: String, date: String, startedAt: String, r: Score.Result, durationS: Int, etaS: Int, eligible: Boolean): Board? {
        if (baseUrl.isBlank()) return null
        val body = JSONObject().apply {
            put("driver_id", driverId); put("nickname", nickname); put("league_key", leagueKey); put("trip_date", date); put("started_at", startedAt)
            put("distance_km", r.distanceKm); put("duration_s", durationS); put("eta_s", etaS); put("eligible", if (eligible) 1 else 0)
            Pen.values().forEach { put(it.name.lowercase(), r.penaltyCounts[it] ?: 0) }
            put("d1_jam", r.jamCounts[Pen.D1] ?: 0); put("d2_jam", r.jamCounts[Pen.D2] ?: 0)
            Bonus.values().forEach { put(it.name.lowercase(), r.bonus[it] ?: 0) }
            put("penalty_sum", r.penaltySum); put("bonus_sum", r.bonusSum); put("score", r.score)
            put("clean_km", r.cleanKm); put("penalty_count", r.penaltyCount); put("combo_max", r.comboMax)
        }
        return runCatching {
            val j = JSONObject(http("POST", "/trip", body.toString()))
            val rows = j.getJSONArray("rows").let { a -> (0 until a.length()).map { i -> a.getJSONObject(i).let { Row(it.getInt("rank"), it.getString("name"), it.getDouble("score"), it.optBoolean("me")) } } }
            Board(rows, j.getInt("my_rank"), j.getInt("total"), j.getBoolean("league"),
                j.optString("morning_line").takeIf { it.isNotBlank() }, if (j.isNull("ghost_diff")) null else j.getDouble("ghost_diff"), "서버")
        }.onFailure { Log.w(Engine.TAG, "서버 업로드 실패, 고스트 리그로: $it") }.getOrNull()
    }

    fun prize(driverId: String): Prize? {
        if (baseUrl.isBlank()) return null
        return runCatching {
            val j = JSONObject(http("GET", "/prize/$driverId"))
            if (!j.optBoolean("has")) return null
            Prize(j.getString("title"), j.getDouble("sum"), j.getString("shop"), j.getString("item"), j.getString("meta"), j.getDouble("lat"), j.getDouble("lng"))
        }.onFailure { Log.w(Engine.TAG, "상금 조회 실패: $it") }.getOrNull()
    }

    fun count(leagueKey: String, date: String): Int? {
        if (baseUrl.isBlank()) return null
        return runCatching { JSONObject(http("GET", "/league/$leagueKey/$date")).getJSONArray("rows").length() }.getOrNull()
    }

    private fun http(method: String, path: String, body: String? = null): String {
        val c = URL(baseUrl.trimEnd('/') + path).openConnection() as HttpURLConnection
        c.requestMethod = method; c.connectTimeout = TIMEOUT; c.readTimeout = TIMEOUT
        if (body != null) { c.doOutput = true; c.setRequestProperty("Content-Type", "application/json"); c.outputStream.use { it.write(body.toByteArray()) } }
        if (c.responseCode !in 200..299) throw RuntimeException("HTTP ${c.responseCode}")
        return c.inputStream.bufferedReader().readText()
    }
}
