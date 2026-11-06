package ai.pleos.contest.league

// 신호 로그 재생. 한 줄 = "ms 종류 값". 종류: speed brake steer signal lowbeam tail night wiper belt limit attention depart
// 속도 틱은 0.1초마다 재생기가 만든다(앱에서는 AAOS 속도 콜백이 이 역할).
object Replay {
    fun run(log: String, judge: Judge, endMs: Long): Judge {
        val events = log.lineSequence().map { it.substringBefore('#').trim() }.filter { it.isNotEmpty() }
            .map { l -> val p = l.split(Regex("\\s+")); Triple(p[0].toLong(), p[1], p.getOrElse(2) { "" }) }
            .sortedBy { it.first }.toMutableList()
        var speed = 0f
        var t = 0L
        while (t <= endMs) {
            while (events.isNotEmpty() && events.first().first <= t) {
                val (_, kind, v) = events.removeAt(0)
                when (kind) {
                    "speed" -> speed = v.toFloat()
                    "brake" -> judge.onBrake(v == "1")
                    "steer" -> judge.onSteering(v.toFloat())
                    "signal" -> judge.onTurnSignal(v.toInt())
                    "lowbeam" -> judge.onLowBeam(v == "1")
                    "tail" -> judge.onTailLamp(v == "1")
                    "night" -> judge.onNight(v == "1")
                    "wiper" -> judge.onWiper(v == "1")
                    "belt" -> judge.onBeltWarning(v == "1")
                    "limit" -> judge.onSpeedLimit(v.toInt())
                    "attention" -> judge.onDriverAttention()
                    "depart" -> judge.onDepart()
                }
            }
            judge.tick(speed, t)
            t += 100
        }
        return judge.finish()
    }
}
