package ai.pleos.contest

import android.content.Context

// 카드를 띄울지 정하는 규칙. 안드로이드에 기대지 않아 단위 시험으로 바로 돌린다(RulesTest).

enum class Criterion(val label: String, val reason: String) {
    VALUE("가성비", "가성비 기준이에요"),
    RATING("평점", "평점 기준이에요"),
    REGULAR("단골", "단골 기준이에요"),
}

data class Place(val name: String, val lat: Double, val lng: Double, val detourMin: Int)

// 가짜 매장 데이터(카페 실시간 API 없음). 에뮬레이터 기본 위치(성남 시흥동) → 서울역 길 위.
object Places {
    val office = Place("서울역", 37.5547, 126.9707, 0)
    val cafes = mapOf(
        Criterion.VALUE to Place("모퉁이 커피", 37.4846, 127.0342, 3),
        Criterion.RATING to Place("온길 커피", 37.4979, 127.0276, 2),
        Criterion.REGULAR to Place("늘봄 로스터스", 37.5044, 127.0049, 4),
    )
    val charger = Place("양재 충전소", 37.4689, 127.0381, 12)
}

data class Settings(
    val coffeeOn: Boolean = true,
    val criterion: Criterion = Criterion.RATING,
    val dailyCap: Boolean = true,       // 하루 2번
    val chargeCounts: Boolean = false,  // 미정: 충전 카드도 하루 2번에 셀지. 기본은 안 셈
    val socLimit: Int = 20,             // 20 / 25 / 30
    val marginKm: Int = 30,             // 여유 거리. 주행 가능 거리 신호가 ○일 때만 씀
    val demo: Boolean = false,
)

data class Memo(
    val day: String = "",
    val shown: Int = 0,               // 오늘 띄운 카드 수(하루 2번에 셈)
    val coffeeHiddenDay: String = "", // [다음에] 누른 날
    val week: String = "",
    val laterCount: Int = 0,          // 이번 주 커피 [다음에] 수, 3이면 그 주 쉼
    val chargeStage: Int = 0,         // 마지막으로 카드를 띄운 경고 단계
)

data class Card(val kind: Kind, val fact: String, val strong: String, val tag: String, val reason: String, val yes: String, val place: Place) {
    enum class Kind { COFFEE, CHARGE }
}

object Rules {
    const val DAILY_MAX = 2
    const val WEEK_REST_AFTER = 3
    val COMMUTE_HOURS = 7 until 9

    fun rollover(m: Memo, day: String, week: String): Memo {
        var r = m
        if (r.day != day) r = r.copy(day = day, shown = 0)
        if (r.week != week) r = r.copy(week = week, laterCount = 0)
        return r
    }

    fun capLeft(s: Settings, m: Memo) = !s.dailyCap || m.shown < DAILY_MAX

    fun coffeeDue(s: Settings, m: Memo, hour: Int, hasRoute: Boolean): Boolean =
        s.coffeeOn && (hasRoute || s.demo) && (s.demo || hour in COMMUTE_HOURS) &&
            m.coffeeHiddenDay != m.day && m.laterCount < WEEK_REST_AFTER && capLeft(s, m)

    // 주행 가능 거리가 목적지까지 거리 + 여유 거리보다 짧은가(목적지가 있을 때만)
    fun rangeShort(s: Settings, rangeKm: Double?, needKm: Double?) =
        rangeKm != null && needKm != null && rangeKm < needKm + s.marginKm

    // 경고 단계. 1 = 내가 정한 기준 아래(미리 한 번): 배터리가 기준 아래거나 여유 거리가 모자람.
    // ponytail: 2단계(차 경고등이 켜지면 가장 가까운 곳)는 경고등에 「배터리 부족」 종류가 없어 아직 안 넣음.
    fun chargeStage(s: Settings, soc: Int?, rangeKm: Double? = null, needKm: Double? = null): Int =
        if ((soc != null && soc < s.socLimit) || rangeShort(s, rangeKm, needKm)) 1 else 0

    // 같은 단계에서는 한 번만. 기준 위로 올라가면(충전) 다음 하강 때 다시.
    fun chargeDue(s: Settings, m: Memo, stage: Int): Boolean =
        stage > m.chargeStage && (!s.chargeCounts || capLeft(s, m))

    fun afterStage(m: Memo, stage: Int): Memo = if (stage < m.chargeStage) m.copy(chargeStage = stage) else m

    fun shownCard(s: Settings, m: Memo, c: Card, stage: Int = 0): Memo {
        val counts = c.kind == Card.Kind.COFFEE || s.chargeCounts
        val r = if (counts) m.copy(shown = m.shown + 1) else m
        return if (c.kind == Card.Kind.CHARGE) r.copy(chargeStage = stage) else r
    }

    fun later(m: Memo, c: Card): Memo =
        if (c.kind == Card.Kind.COFFEE) m.copy(coffeeHiddenDay = m.day, laterCount = m.laterCount + 1) else m

    fun coffeeCard(s: Settings): Card {
        val p = Places.cafes.getValue(s.criterion)
        return Card(Card.Kind.COFFEE, "${p.name}, 가는 길이에요", p.name, "도착 +${p.detourMin}분", s.criterion.reason, "들러요", p)
    }

    fun chargeCard(s: Settings, soc: Int?, byRange: Boolean): Card {
        val p = Places.charger
        val head = soc?.let { "배터리 $it%" } ?: "배터리"
        val reason = if (byRange) "여유가 ${s.marginKm}km 아래면 알리기로 하셨어요" else "${s.socLimit}% 아래면 알리기로 하셨어요"
        return Card(Card.Kind.CHARGE, "$head, 도착 전 충전소 1곳", head, "도착 +${p.detourMin}분", reason, "충전해요", p)
    }
}

// SharedPreferences 저장. 앱을 다시 켜도 설정과 오늘 띄운 횟수가 남는다.
class Store(ctx: Context) {
    private val p = ctx.getSharedPreferences("pleos_commerce", Context.MODE_PRIVATE)

    var settings: Settings
        get() = Settings(
            coffeeOn = p.getBoolean("coffeeOn", true),
            criterion = Criterion.valueOf(p.getString("criterion", Criterion.RATING.name)!!),
            dailyCap = p.getBoolean("dailyCap", true),
            chargeCounts = p.getBoolean("chargeCounts", false),
            socLimit = p.getInt("socLimit", 20),
            marginKm = p.getInt("marginKm", 30),
            demo = p.getBoolean("demo", false),
        )
        set(s) = p.edit().putBoolean("coffeeOn", s.coffeeOn).putString("criterion", s.criterion.name)
            .putBoolean("dailyCap", s.dailyCap).putBoolean("chargeCounts", s.chargeCounts)
            .putInt("socLimit", s.socLimit).putInt("marginKm", s.marginKm).putBoolean("demo", s.demo).apply()

    var memo: Memo
        get() = Memo(
            p.getString("day", "")!!, p.getInt("shown", 0), p.getString("coffeeHiddenDay", "")!!,
            p.getString("week", "")!!, p.getInt("laterCount", 0), p.getInt("chargeStage", 0),
        )
        set(m) = p.edit().putString("day", m.day).putInt("shown", m.shown).putString("coffeeHiddenDay", m.coffeeHiddenDay)
            .putString("week", m.week).putInt("laterCount", m.laterCount).putInt("chargeStage", m.chargeStage).apply()
}
