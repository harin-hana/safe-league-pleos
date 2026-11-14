package ai.pleos.contest.league

import android.content.Context

// 설정 한 곳. 데모 모드, 핸들 부호, 별명, 소리와 빛, 차종, 서버 주소.
class Prefs(ctx: Context) {
    private val p = ctx.getSharedPreferences("safe_league", Context.MODE_PRIVATE)
    private fun b(k: String, d: Boolean) = p.getBoolean(k, d)
    private fun set(k: String, v: Any) = p.edit().apply { when (v) { is Boolean -> putBoolean(k, v); is Float -> putFloat(k, v); is String -> putString(k, v); is Int -> putInt(k, v) } }.apply()

    var demo: Boolean get() = b("demo", false); set(v) = set("demo", v)
    var steeringSign: Float get() = p.getFloat("steeringSign", -1f); set(v) = set("steeringSign", v)   // -1 에뮬레이터(콘솔과 반대), 1 실차
    // 옛 기본값 「아침7시42」는 시드 별명과 겹쳐 남의 이름으로도 보였다. 저장된 값이 그것이면 새 기본값으로
    var nickname: String get() = p.getString("nickname", null)?.takeIf { it != "아침7시42" } ?: "첫차보다먼저"; set(v) = set("nickname", v)
    var autoJoin: Boolean get() = b("autoJoin", false); set(v) = set("autoJoin", v)
    var autoStart: Boolean get() = b("autoStart", true); set(v) = set("autoStart", v)   // 시동 때 출발 카드를 스스로 올림(0.4.0)
    var sound: Boolean get() = b("sound", true); set(v) = set("sound", v)
    var tts: Boolean get() = b("tts", true); set(v) = set("tts", v)
    var ambient: Boolean get() = b("ambient", true); set(v) = set("ambient", v)
    var steerRatio: Float get() = p.getFloat("steerRatio", 14f); set(v) = set("steerRatio", v)
    var wheelbase: Float get() = p.getFloat("wheelbase", 2.9f); set(v) = set("wheelbase", v)
    var serverUrl: String get() = p.getString("serverUrl", LeagueClient.DEFAULT_URL)!!; set(v) = set("serverUrl", v)
    var driverId: String get() = p.getString("driverId", null) ?: java.util.UUID.randomUUID().toString().take(12).also { set("driverId", it) }; private set(v) = set("driverId", v)
}
