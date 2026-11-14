package ai.pleos.contest.league

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.tan

// 판정 모듈. SAFE_LEAGUE_RULES.md 6절 의사코드 그대로. 안드로이드에 기대지 않아 JVM 시험(JudgeReplayTest)으로 바로 돌린다.
// 시계 = 속도 틱(10Hz). 나머지 신호는 바뀔 때만 들어와 최근 값으로 들고 있는다.

enum class Pen { D1, D2, D3, D4, D5, D6, D7, D8 }
enum class Bonus { B1, B2, B3, B4, B5, B6 }

// 주행 중 연출용 사건(점수 밖). 감점은 사건을 내지 않는다(무음).
sealed class Cue {
    data class Combo(val n: Int) : Cue()        // 깜빡이 콤보 고비 5, 10, 20
    data class CleanKm(val n: Int) : Cue()      // 클린 1km 5개, 10개
    object BeltAllIn : Cue()                     // 출발 전 벨트 올인
}

data class PenaltyEvent(val pen: Pen, val tMs: Long, val km: Double, val jam: Boolean, val section: Boolean = false)

// 심사용 판정 보기에 넘기는 기록: 판정 한 건과 그 순간 받은 값. 규칙과 점수 계산에는 쓰지 않는다.
// pen = 감점, bonus = 가산, 둘 다 없으면 깜빡이 켠 차선 변경(combo = 그때 콤보). 값은 판정 종류에 따라 일부만 채운다
data class Verdict(
    val tMs: Long, val pen: Pen? = null, val bonus: Bonus? = null, val combo: Int = 0, val jam: Boolean = false, val section: Boolean = false,
    val speed: Float = 0f,         // 판정 순간 속도
    val speedBefore: Float = 0f,   // 0.5초 전 속도(급제동)
    val jamAvg: Float = 0f,        // 직전 N초 평균 속도(막히는 길)
    val swing: Float = 0f,         // 최근 4초의 최대 꺾임, 양수 = 왼쪽(차선 변경)
    val swingMin: Float = 0f,      // 그 속도에서의 꺾임 기준(5° 또는 3°)
    val signal: Int = 0,           // 깜빡이. -1 왼쪽, 0 꺼짐, +1 오른쪽. 콤보는 맞은 쪽(방금 끈 2초 안도 켠 것으로 치는 규칙 그대로)
) {
    val points: Double get() = when {
        pen != null -> -(if (section) Score.SECTION_WEIGHT else Score.PEN_WEIGHT.getValue(pen) * if (jam) 0.5 else 1.0)
        bonus != null -> Score.BONUS_WEIGHT.getValue(bonus)
        else -> 0.0
    }
}

class Config(
    var steeringSign: Float = -1f,     // 앱 값 × 부호 = 콘솔 기준. 에뮬레이터는 콘솔과 반대라 -1. 양수 = 왼쪽
    var jamWindowSec: Int = 60,        // 정체 판정 창. 데모 모드 10
    var steerRatio: Float = 14f,
    var wheelbaseM: Float = 2.9f,
)

class Judge(val cfg: Config = Config(), private val cue: (Cue) -> Unit = {}) {

    companion object {
        const val TICK = 0.1
        const val DIFF_TICKS = 5            // 0.5초 창
        const val STEER_WINDOW_MS = 4000L
        const val SIGNAL_RECENT_MS = 2000L
    }

    // 최근 값 보관
    var speedKmh = 0f; private set
    var brake = false; private set
    var turnSignal = 0; private set            // -1 왼쪽(콘솔 양수 각도 쪽), 0 끔, +1 오른쪽
    var lowBeam = false; private set
    var tailLamp = false; private set
    var night = false; private set
    var wiper = false; private set
    var beltWarning = false; private set
    var speedLimit = 0; private set            // 0 = 모름
    var tbtDistanceM = Double.MAX_VALUE; private set
    var curveCaution = false; private set

    // 결과
    val penalties = mutableListOf<PenaltyEvent>()
    val bonus = mutableMapOf<Bonus, Int>()
    val verdicts = mutableListOf<Verdict>()    // 판정 보기용 기록(시간 순). 콤보는 한 줄을 고쳐 쓴다
    var combo = 0; private set
    var comboMax = 0; private set
    var distanceKm = 0.0; private set          // 속도 적분(실차는 Odometer 값으로 덮어씀)
    var cleanKmSinceLast = 0.0; private set
    var ticks = 0L; private set
    var nowMs = 0L; private set

    // 사건 창
    private val speeds = ArrayDeque<Float>()   // 최근 6개(0.5초 전 비교용)
    private val jamRing = FloatArray(600); private var jamIdx = 0; private var jamCount = 0; private var jamSum = 0.0
    private var brakeWin = 0.0; private var brakeCounted = false
    private var accelWin = 0.0; private var accelCounted = false
    private var stealthWin = 0.0; private var stealthCounted = false; private var stealthExtra = false
    private var overWin = 0.0; private var overCounted = 0
    private var beltCounted = false
    private var d3Counted = false
    private var lastSignalDir = 0; private var lastSignalOffMs = Long.MIN_VALUE
    private val steer = ArrayDeque<Pair<Long, Float>>()
    private var cleanMilestone = 0
    private var voidedKm = mutableSetOf<Int>()   // 감점이 난 km 칸(클린 km 계산용)
    private val cameraWatch = mutableMapOf<String, Pair<Int, Boolean>>()  // id → (제한속도, 급제동 없음)
    private var zone: Zone? = null
    private var zonesCounted = 0
    private var wiperLightSeen = false

    private class Zone(val limit: Int, val startKm: Double) { var ok = true }

    // ---------- 신호 입력 ----------

    fun onBrake(on: Boolean) { brake = on }
    fun onTurnSignal(dir: Int) {
        if (dir == 0 && turnSignal != 0) lastSignalOffMs = nowMs
        if (dir != 0) lastSignalDir = dir
        turnSignal = dir
    }
    fun onLowBeam(on: Boolean) { lowBeam = on; checkB6() }
    fun onTailLamp(on: Boolean) { tailLamp = on }
    fun onNight(on: Boolean) { night = on }
    fun onWiper(on: Boolean) { wiper = on; checkB6() }
    fun onSpeedLimit(kmh: Int) {
        if (kmh != speedLimit) zone?.let { closeZone() }
        speedLimit = kmh
    }
    fun onTbtDistance(m: Double) { tbtDistanceM = m }
    fun onCurveCaution(on: Boolean) { curveCaution = on }
    fun onDriverAttention() = penalty(Pen.D7, false)
    fun onBeltWarning(on: Boolean) { beltWarning = on; if (!on) beltCounted = false }

    // 출발 때 한 번: 착석 전원 벨트(= 벨트 경고등 없음)면 B1
    fun onDepart() { if (!beltWarning) { bonus[Bonus.B1] = 1; verdicts.add(0, Verdict(0, bonus = Bonus.B1)); cue(Cue.BeltAllIn) } }

    // 실차: Odometer 거리로 적분값을 덮어씀
    fun onOdometerKm(km: Float) { if (km > 0f) distanceKm = km.toDouble() }

    // 단속 카메라: 300m 전부터 지켜보고, 목록에서 사라지면(통과) 급제동 없이 제한속도 이하였으면 B5
    fun onCamera(list: List<Triple<String, Int, Int>>) {   // (id, 제한속도, 남은 m)
        val ids = list.map { it.first }.toSet()
        for ((id, limit, remain) in list) if (remain in 0..300 && id !in cameraWatch) cameraWatch[id] = limit to true
        for (id in cameraWatch.keys.toList()) if (id !in ids) {
            val (limit, ok) = cameraWatch.remove(id)!!
            if (ok && limit > 0 && speedKmh <= limit && (bonus[Bonus.B5] ?: 0) < 3) { bonus[Bonus.B5] = (bonus[Bonus.B5] ?: 0) + 1; verdicts += Verdict(nowMs, bonus = Bonus.B5, speed = speedKmh) }
        }
    }
    fun onSectionOverSpeed() = penalty(Pen.D5, false, section = true)   // 구간단속 평균 초과 1회 3

    // 주의 구간: 보호구역 통과부터 제한속도가 바뀔 때까지(안 바뀌면 300m) 제한속도 이하면 B4
    fun onSchoolZone() { if (zone == null && speedLimit > 0) zone = Zone(speedLimit, distanceKm) }
    private fun closeZone() {
        val z = zone ?: return
        zone = null
        if (z.ok && zonesCounted < 3) { zonesCounted++; bonus[Bonus.B4] = zonesCounted; verdicts += Verdict(nowMs, bonus = Bonus.B4, speed = speedKmh) }
    }

    private fun checkB6() { if (wiper && lowBeam && !wiperLightSeen) { wiperLightSeen = true; verdicts += Verdict(nowMs, bonus = Bonus.B6) } }

    // ---------- 시계: 속도 틱 ----------

    fun tick(kmh: Float, tMs: Long) {
        nowMs = tMs; ticks++
        speedKmh = kmh
        speeds.addLast(kmh); if (speeds.size > DIFF_TICKS + 1) speeds.removeFirst()
        val v = kmh.toDouble()
        distanceKm += v / 3600.0 * TICK
        cleanKmSinceLast += v / 3600.0 * TICK

        // 정체 = 직전 N초 평균 속도 20 이하
        val n = cfg.jamWindowSec * 10
        if (jamCount >= n) jamSum -= jamRing[jamIdx] else jamCount++
        jamRing[jamIdx] = kmh; jamSum += kmh; jamIdx = (jamIdx + 1) % n
        val jam = jamCount >= n && jamSum / jamCount <= 20.0

        // 6-1 속도 미분
        val a = if (speeds.size > DIFF_TICKS) (v - speeds.first()) / (DIFF_TICKS * TICK) else 0.0
        if (brake && a < -10) brakeWin += TICK else { brakeWin = 0.0; brakeCounted = false }
        if (brakeWin >= 0.5 - 1e-9 && !brakeCounted) { brakeCounted = true; penalty(Pen.D1, jam) }
        val accelLimit = if (v <= 10) 12 else if (v <= 20) 10 else 8
        if (a > accelLimit) accelWin += TICK else { accelWin = 0.0; accelCounted = false }
        if (accelWin >= 1.0 - 1e-9 && !accelCounted) { accelCounted = true; penalty(Pen.D2, jam) }

        // D5 과속: 제한속도 110% 초과 10초마다 1(첫 10초 유예)
        if (speedLimit > 0 && v > speedLimit * 1.1) {
            overWin += TICK
            val due = (overWin / 10.0).toInt() - 1
            while (overCounted < due) { overCounted++; penalty(Pen.D5, false) }
        } else { overWin = 0.0; overCounted = 0 }

        // D6 벨트 미착용 주행: 10km/h 이상에서 벨트 경고(한 경고 구간에 1회)
        if (beltWarning && v >= 10 && !beltCounted) { beltCounted = true; penalty(Pen.D6, false) }

        // 6-3 스텔스
        val noLight = !lowBeam && !tailLamp
        if (night && noLight && v >= 10) stealthWin += TICK else { stealthWin = 0.0; stealthCounted = false; stealthExtra = false }
        if (stealthWin > 30 && !stealthCounted) { stealthCounted = true; penalty(Pen.D8, false) }
        if (stealthWin > 300 && !stealthExtra) { stealthExtra = true; penalty(Pen.D8, false) }

        // 보호구역 감시
        zone?.let { z -> if (v > z.limit) z.ok = false; if (distanceKm - z.startKm >= 0.3) closeZone() }
        // 카메라 감시: 급제동이 나면 그 카메라는 탈락(penalty에서 처리)

        // 클린거리 고비(5km, 10km)
        val km = cleanKmSinceLast.toInt()
        if (km >= 5 && cleanMilestone < 5) { cleanMilestone = 5; cue(Cue.CleanKm(5)) }
        if (km >= 10 && cleanMilestone < 10) { cleanMilestone = 10; cue(Cue.CleanKm(10)) }
    }

    // ---------- 6-2 핸들 ----------

    fun onSteering(rawDeg: Float) {
        val theta = rawDeg * cfg.steeringSign
        val v = speedKmh.toDouble()
        // D3 급핸들: 추정 회전율(도/초) = v × tan(θ/조향비) ÷ 축거, 라디안→도
        val delta = Math.toRadians((theta / cfg.steerRatio).toDouble())
        val yawDegPerSec = Math.toDegrees(v / 3.6 * tan(delta) / cfg.wheelbaseM)
        if (v >= 40 && abs(yawDegPerSec) >= 10) { if (!d3Counted) { d3Counted = true; penalty(Pen.D3, false) } } else d3Counted = false

        // D4, B3 차선 변경 패턴: 최근 4초 버퍼
        steer.addLast(nowMs to theta)
        while (steer.size > 1 && nowMs - steer.elementAt(1).first >= STEER_WINDOW_MS) steer.removeFirst()
        if (v < 40 || tbtDistanceM <= 100 || curveCaution) return
        val base = steer.first().second
        val (lo, hi) = if (v < 80) 5f to 20f else 3f to 12f
        val peak = steer.maxByOrNull { abs(it.second - base) }!!.second
        val swing = peak - base
        if (abs(swing) in lo..hi && abs(theta - base) < 2f) {
            val s = if (swing > 0) -1 else 1          // 콘솔 양수 각도 = 왼쪽 = -1
            val signalMatch = turnSignal == s || (lastSignalDir == s && nowMs - lastSignalOffMs <= SIGNAL_RECENT_MS)
            if (signalMatch) {
                combo++; comboMax = max(comboMax, combo)
                // 판정 보기: 이번 연속의 콤보 줄(마지막 감점 뒤에 있는 것)을 지우고 새 값으로 다시 적는다
                verdicts.indexOfLast { it.bonus == null }.let { if (it >= 0 && verdicts[it].pen == null) verdicts.removeAt(it) }
                verdicts += Verdict(nowMs, combo = combo, speed = speedKmh, swing = swing, swingMin = lo, signal = s)
                if (combo == 5 || combo == 10 || combo == 20) cue(Cue.Combo(combo))
                if (combo >= 20) { if (Bonus.B3 !in bonus) verdicts += Verdict(nowMs, bonus = Bonus.B3); bonus[Bonus.B3] = 1 }
            } else penalty(Pen.D4, false, swing = swing, swingMin = lo)
            steer.clear(); steer.addLast(nowMs to theta)
        }
    }

    // ---------- 감점(무음) ----------

    private fun penalty(pen: Pen, jam: Boolean, section: Boolean = false, swing: Float = 0f, swingMin: Float = 0f) {
        penalties += PenaltyEvent(pen, nowMs, distanceKm, jam, section)
        verdicts += Verdict(nowMs, pen, jam = jam, section = section, speed = speedKmh, speedBefore = speeds.firstOrNull() ?: speedKmh,
            jamAvg = if (jamCount > 0) (jamSum / jamCount).toFloat() else 0f, swing = swing, swingMin = swingMin, signal = turnSignal)
        combo = 0
        cleanKmSinceLast = 0.0; cleanMilestone = 0
        voidedKm += distanceKm.toInt()
        for (k in cameraWatch.keys) cameraWatch[k] = cameraWatch[k]!!.copy(second = false)
    }

    // 도착 때 가산 마감
    fun finish(): Judge {
        if (wiperLightSeen) bonus[Bonus.B6] = 1
        if (penalties.isEmpty()) bonus[Bonus.B2] = 1
        zone?.let { closeZone() }
        return this
    }

    val cleanKm: Double get() = max(0.0, distanceKm - voidedKm.count { it < distanceKm }.toDouble())
}
