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

    @Test fun sameLogSameResultTwice() {
        val a = Score.of(Replay.run(scene2, Judge(Config(jamWindowSec = 10)), 70000), 15.0)
        val b = Score.of(Replay.run(scene2, Judge(Config(jamWindowSec = 10)), 70000), 15.0)
        assertEquals(a, b)
    }
}
