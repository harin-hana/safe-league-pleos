package ai.pleos.contest.league

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// RULES 2절 점수 다섯 층. 숫자는 규칙 문서 그대로.
object Score {
    val PEN_WEIGHT = mapOf(Pen.D1 to 1.0, Pen.D2 to 1.0, Pen.D3 to 1.0, Pen.D4 to 2.0, Pen.D5 to 1.0, Pen.D6 to 3.0, Pen.D7 to 5.0, Pen.D8 to 3.0)
    const val SECTION_WEIGHT = 3.0
    const val BONUS_CAP = 10.0
    val BONUS_WEIGHT = mapOf(Bonus.B1 to 1.0, Bonus.B2 to 5.0, Bonus.B3 to 1.0, Bonus.B4 to 1.0, Bonus.B5 to 1.0, Bonus.B6 to 1.0)

    // 화면 내역 한 줄(M14 3b 문구)
    val PEN_LABEL = mapOf(
        Pen.D1 to "급제동", Pen.D2 to "급가속", Pen.D3 to "급핸들", Pen.D4 to "깜빡이 없는 차선 변경",
        Pen.D5 to "과속", Pen.D6 to "벨트 미착용 주행", Pen.D7 to "주의 경고", Pen.D8 to "스텔스 주행",
    )
    val BONUS_LABEL = mapOf(
        Bonus.B1 to "출발 전 모두 벨트", Bonus.B2 to "클린 주행", Bonus.B3 to "깜빡이 콤보 20",
        Bonus.B4 to "보호구역 감속", Bonus.B5 to "미리 감속", Bonus.B6 to "빗길 전조등",
    )

    data class Line(val label: String, val value: Double, val plus: Boolean = false)

    data class Result(
        val distanceKm: Double,
        val penaltySum: Double,     // 보정 반영
        val converted: Double,      // 감점 합 × 10 ÷ km, 소수 첫째 자리
        val bonusSum: Double,
        val score: Double,          // 0~110, 소수 첫째 자리
        val lines: List<Line>,      // 내역
        val penaltyCount: Int,
        val cleanKm: Double,
        val comboMax: Int,
        val penaltyCounts: Map<Pen, Int>,
        val jamCounts: Map<Pen, Int>,
        val bonus: Map<Bonus, Int>,
    )

    fun of(j: Judge, distanceKm: Double = j.distanceKm): Result {
        val lines = mutableListOf<Line>()
        var sum = 0.0
        for (pen in Pen.values()) {
            val evs = j.penalties.filter { it.pen == pen }
            val plain = evs.filter { !it.jam && !it.section }
            val jam = evs.filter { it.jam }
            val section = evs.filter { it.section }
            val w = PEN_WEIGHT.getValue(pen)
            if (plain.isNotEmpty()) { val v = plain.size * w; sum += v; lines += Line("${PEN_LABEL[pen]} ${plain.size}회", v) }
            if (jam.isNotEmpty()) { val v = jam.size * w * 0.5; sum += v; lines += Line("${PEN_LABEL[pen]} ${jam.size}회, 정체 구간이라 절반", v) }
            if (section.isNotEmpty()) { val v = section.size * SECTION_WEIGHT; sum += v; lines += Line("구간단속 평균 초과 ${section.size}회", v) }
        }
        val km = max(distanceKm, 0.1)
        val converted = round1(sum * 10 / km)
        lines += Line("감점 ${fmt(sum)}${eul(sum)} 달린 거리에 맞추면", converted)
        var bonus = 0.0
        for (b in Bonus.values()) {
            val n = j.bonus[b] ?: 0
            if (n > 0) { val v = n * BONUS_WEIGHT.getValue(b); bonus += v; lines += Line(BONUS_LABEL.getValue(b) + if (n > 1) " ${n}곳" else "", v, plus = true) }
        }
        bonus = min(bonus, BONUS_CAP)
        val score = round1(min(110.0, max(0.0, 100.0 - converted + bonus)))
        return Result(
            distanceKm, sum, converted, bonus, score, lines, j.penalties.size, j.cleanKm, j.comboMax,
            Pen.values().associateWith { p -> j.penalties.count { it.pen == p } },
            Pen.values().associateWith { p -> j.penalties.count { it.pen == p && it.jam } },
            j.bonus.toMap(),
        )
    }

    fun round1(v: Double) = (v * 10).roundToInt() / 10.0
    fun fmtKm(km: Double) = String.format("%.1f", km)
    fun fmt(v: Double) = if (v == v.toLong().toDouble()) v.toLong().toString() else String.format("%.1f", v)
    // 숫자 뒤 조사. 마지막 자리를 읽었을 때 받침이 ㅇ ㅁ ㄱ(0 3 6)이면 「으로」, 받침이 있으면(0 1 3 6 7 8) 「을」
    fun ro(v: Double) = if (fmt(v).last() in "036") "으로" else "로"
    fun eul(v: Double) = if (fmt(v).last() in "013678") "을" else "를"
}
