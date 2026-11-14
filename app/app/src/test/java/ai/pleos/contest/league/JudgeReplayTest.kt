package ai.pleos.contest.league

import org.junit.Assert.assertEquals
import org.junit.Test

// 대본 v2 장면 2 신호 로그를 재생하면 RULES 2-7 예시와 같은 결과(99.7)가 나와야 한다.
class JudgeReplayTest {

    // 핸들 값은 앱이 받는 값(콘솔과 부호 반대). 깜빡이 -1 = 왼쪽.
    private val scene2 = buildString {
        appendLine("0 steer 0"); appendLine("0 belt 0"); appendLine("0 depart")
        appendLine("0 night 1"); appendLine("0 wiper 1"); appendLine("0 lowbeam 1"); appendLine("0 tail 1"); appendLine("0 speed 60")
        for (i in 0 until 10) {   // 깜빡이 켜고 차선 변경 10번
            val s = 5000 + 4000L * i
            appendLine("$s signal -1"); appendLine("${s + 1000} steer -8"); appendLine("${s + 2000} steer 0"); appendLine("${s + 3000} signal 0")
        }
        appendLine("50000 steer -8"); appendLine("51000 steer 0")                         // 깜빡이 없는 차선 변경(D4)
        appendLine("56000 brake 1"); appendLine("56100 speed 50"); appendLine("56900 brake 0")  // 급제동(D1)
        appendLine("57000 speed 15"); appendLine("67000 speed 25")                           // 정체 10초(데모 창)
        appendLine("68000 brake 1"); appendLine("68100 speed 15"); appendLine("68900 brake 0")  // 정체 급제동(0.5)
    }

    @Test fun scene2ReplayGives99_7() {
        val cues = mutableListOf<Cue>()
        val j = Replay.run(scene2, Judge(Config(steeringSign = -1f, jamWindowSec = 10)) { cues += it }, 70000)

        assertEquals(listOf(Pen.D1, Pen.D1, Pen.D4), j.penalties.map { it.pen }.sorted())
        assertEquals(listOf(false, true), j.penalties.filter { it.pen == Pen.D1 }.map { it.jam })
        assertEquals(10, j.comboMax)
        assertEquals(listOf(Cue.BeltAllIn, Cue.Combo(5), Cue.Combo(10)), cues)
        assertEquals(mapOf(Bonus.B1 to 1, Bonus.B6 to 1), j.bonus)

        val r = Score.of(j, distanceKm = 15.0)
        assertEquals(3.5, r.penaltySum, 1e-9)
        assertEquals(2.3, r.converted, 1e-9)
        assertEquals(2.0, r.bonusSum, 1e-9)
        assertEquals(99.7, r.score, 1e-9)
        assertEquals(listOf("급제동 1회", "급제동 1회, 정체 구간이라 절반", "깜빡이 없는 차선 변경 1회", "감점 3.5를 달린 거리에 맞추면", "출발 전 모두 벨트", "빗길 전조등"),
            r.lines.map { it.label })
        r.lines.forEach { l -> assert(!l.label.contains('·') && !l.label.contains('—') && !l.label.contains("점수")) { l.label } }
    }

    // 심사용 판정 보기: 판정 한 건마다 그 순간 받은 값이 남고, 콤보는 한 줄로 고쳐 쓰이며, 합계가 결과 내역과 같다
    @Test fun verdictsKeepTheValuesAtThatMoment() {
        val j = Replay.run(scene2, Judge(Config(steeringSign = -1f, jamWindowSec = 10)), 70000)
        val v = j.verdicts
        assertEquals(listOf("벨트 올인", "빗길 전조등", "깜빡이 켜고 차선 변경", "깜빡이 없는 차선 변경", "급제동", "막히는 길 급제동"), v.map { Score.verdictLabel(it) })
        assertEquals(listOf("+1", "+1", "+0", "−2", "−1", "−0.5"), v.map { Score.points(it.points) })
        assertEquals(listOf("0:00", "0:00", "0:42", "0:50", "0:56", "1:08"), v.map { Score.clock(it.tMs) })   // 핸들 판정 시각은 직전 속도 틱(0.1초 전)

        // logcat 한 줄(LEAGUE_JUDGE)은 판정 보기와 같은 내용
        assertEquals("0:50 | 깜빡이 없는 차선 변경 | −2 | 속도 40 km/h 이상=60 km/h(MET) ; 핸들을 5° 넘게 꺾었다 되돌림=왼쪽 8°(MET) ; 꺾은 쪽 깜빡이=꺼짐(MISS)", Score.logLine(v[3], 10))
        assertEquals("0:42 | 깜빡이 켜고 차선 변경 | 콤보 10 | 속도 40 km/h 이상=60 km/h(MET) ; 핸들을 5° 넘게 꺾었다 되돌림=왼쪽 8°(MET) ; 꺾은 쪽 깜빡이=왼쪽 켜짐(GAIN)", Score.logLine(v[2], 10))

        val combo = v[2]   // 열 번 가운데 마지막 것만 남는다
        assertEquals(10, combo.combo)
        assertEquals(listOf("60 km/h", "왼쪽 8°", "왼쪽 켜짐"), Score.conditions(combo, 10).map { it.value })
        assertEquals(Score.Mark.GAIN, Score.conditions(combo, 10).last().mark)

        val d4 = Score.conditions(v[3], 10)
        assertEquals(listOf("속도 40 km/h 이상", "핸들을 5° 넘게 꺾었다 되돌림", "꺾은 쪽 깜빡이"), d4.map { it.label })
        assertEquals(listOf("60 km/h", "왼쪽 8°", "꺼짐"), d4.map { it.value })
        assertEquals(listOf(Score.Mark.MET, Score.Mark.MET, Score.Mark.MISS), d4.map { it.mark })

        assertEquals(listOf("밟음", "60 → 50 km/h"), Score.conditions(v[4], 10).map { it.value })
        val jam = Score.conditions(v[5], 10)
        assertEquals(listOf("밟음", "25 → 15 km/h", "16 km/h"), jam.map { it.value })
        assertEquals("직전 10초 평균 속도 20 km/h 이하", jam.last().label)

        assertEquals(listOf("꺼짐"), Score.conditions(v[0], 10).map { it.value })
        assertEquals(listOf("와이퍼 켜짐", "하향등 켜짐"), Score.conditions(v[1], 10).map { it.label })

        val r = Score.of(j, distanceKm = 15.0)
        assertEquals(r.penaltySum, -v.sumOf { minOf(it.points, 0.0) }, 1e-9)
        assertEquals(r.bonusSum, v.sumOf { maxOf(it.points, 0.0) }, 1e-9)
        v.forEach { x -> (Score.conditions(x, 10).flatMap { listOf(it.label, it.value) } + Score.verdictLabel(x)).forEach { s -> assert('·' !in s && '—' !in s) { s } } }
    }

    // 80 km/h 이상에서는 꺾임 기준이 3°라 조건 문장도 3°로 나온다. 규칙에 없는 조건 줄은 비어 있어도 된다(이름과 점수만)
    @Test fun verdictSentenceFollowsTheRule() {
        val log = listOf("0 speed 90", "0 steer 0", "1000 steer -4", "2000 steer 0", "3000 attention").joinToString("\n")
        val v = Replay.run(log, Judge(Config(steeringSign = -1f)), 5000).verdicts
        assertEquals(listOf("깜빡이 없는 차선 변경", "주의 경고"), v.map { Score.verdictLabel(it) })
        assertEquals(listOf("핸들을 3° 넘게 꺾었다 되돌림" to "왼쪽 4°", "꺾은 쪽 깜빡이" to "꺼짐"), Score.conditions(v[0], 60).drop(1).map { it.label to it.value })
        assertEquals(emptyList<Score.Cond>(), Score.conditions(v[1], 60))
        assertEquals("−5", Score.points(v[1].points))
    }

    @Test fun sameLogSameResultTwice() {
        val a = Score.of(Replay.run(scene2, Judge(Config(jamWindowSec = 10)), 70000), 15.0)
        val b = Score.of(Replay.run(scene2, Judge(Config(jamWindowSec = 10)), 70000), 15.0)
        assertEquals(a, b)
    }

    // 데모는 아침 리그 하나. 대본 결과 99.7이면 7명 중 2위, 「오늘 아침 리그는 …」 줄 없음, 어제 아침보다 0.4
    @Test fun demoBoardIsMorningLeagueSecondOfSeven() {
        val b = Ghost.demoBoard(99.7)
        assertEquals(2 to 7, b.myRank to b.total)
        assertEquals(null, b.morning)
        assertEquals(0.4, b.ghostDiff!!, 1e-9)
    }
}
