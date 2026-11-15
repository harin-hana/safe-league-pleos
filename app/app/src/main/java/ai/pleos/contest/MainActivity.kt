package ai.pleos.contest

import ai.pleos.contest.league.Bonus
import ai.pleos.contest.league.Engine
import ai.pleos.contest.league.Engine.Phase
import ai.pleos.contest.league.Score
import ai.pleos.contest.league.Verdict
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

// 세이프 리그 화면. M14 장면 1(출발), 3a 3b(도착 결과), 4(다음 날 카드). 주행 중에는 앱이 뒤로 가고 아무것도 띄우지 않는다.
// 데모 모드에서 설정의 「심사용 판정 보기」를 켜면 주행 중에 그 화면이 뜬다(기본 꺼짐). 판정은 늘 logcat LEAGUE_JUDGE에 한 줄씩 남는다.
// 상태는 Engine(프로세스 싱글턴)에 있어 야간 전환으로 다시 만들어져도 그대로다.
class MainActivity : Activity() {

    private val main = Handler(Looper.getMainLooper())
    private lateinit var tone: Tone
    private val refresh = { render() }
    // 0.3초마다: 판정 보기의 「지금 받는 신호」 값을 고쳐 쓰고, 화면 열쇠가 바뀌었으면 다시 그린다
    // (급제동 같은 판정은 속도 틱에서 나와 화면을 깨우지 않는다. 열쇠에 시각이 있어 분이 바뀔 때도 여기서 잡힌다)
    private var live: (() -> Unit)? = null
    private val pulse = object : Runnable { override fun run() { live?.invoke(); render(); main.postDelayed(this, 300) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Engine.start(this)
        handleIntent(intent)
        startForegroundService(Intent(this, ArrivalService::class.java))
        if (checkSelfPermission("android.car.permission.CAR_SPEED") == PackageManager.PERMISSION_GRANTED) Engine.connectAaos(this)
        else requestPermissions(arrayOf("android.car.permission.CAR_SPEED"), 1)
        render()
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); handleIntent(intent); render() }

    // 촬영 준비용 adb: am start -n …/.MainActivity --ez demo true, --es input 도착 (데모 입력과 같음)
    private fun handleIntent(i: Intent) {
        if (i.hasExtra("demo")) { Engine.prefs.demo = i.getBooleanExtra("demo", false); Engine.resetForDemo() }
        when (val s = i.getStringExtra("input")) {
            null -> {}
            "참가" -> Engine.join(); "쉬기" -> Engine.rest(); "내역" -> { Engine.detail = !Engine.detail; Engine.notifyUi() }
            "들러요" -> Engine.stopBy(); "다음에" -> Engine.later(); "다음날" -> Engine.demoInput("다음 날")   // adb는 띄어쓰기를 못 넘김
            else -> Engine.demoInput(s)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) Engine.connectAaos(this)
    }

    // 보이는 동안(onStart~onStop) 갱신한다. 깜빡이를 켜면 시스템이 측방 카메라 창을 앱 위에 띄워 이 화면이 일시 정지가 되는데,
    // onResume~onPause에 묶으면 그동안 판정 보기가 멈춘다(2026-11-14 에뮬레이터 실측)
    override fun onStart() { super.onStart(); Engine.addListener(refresh); main.post(pulse); render() }
    override fun onStop() { Engine.removeListener(refresh); main.removeCallbacks(pulse); super.onStop() }

    private var lastKey = ""
    private fun render() {
        val night = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        // 화면에 보이는 상태가 안 바뀌었으면 다시 그리지 않는다(에뮬레이터 렌더가 느려 터치를 잃는다)
        val key = listOf(Engine.phase, Engine.gear, Engine.beltWarning, Engine.board?.myRank, Engine.detail, Engine.demoNote, Engine.demoOpen, Engine.demo, Engine.prefs.judgeView,
            Engine.waypointAddedMin, Engine.prize?.stamps, Engine.prizeChoice, SystemClock.elapsedRealtime() < Engine.ambientFlashUntil, night, Engine.wiper,
            LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm")), Engine.dest?.name,
            Engine.judge?.verdicts?.size, Engine.judge?.verdicts?.lastOrNull()).joinToString("|")
        if (key == lastKey) return
        lastKey = key
        live = null
        tone = Tone(day = !night, rain = night && Engine.wiper)
        // 주행 중(또는 도착 뒤 P단 전)에는 화면이 없다. 화면을 닫아 내비가 앞에 오게 하고, 결과는 P단에서 서비스가 다시 올린다.
        // 데모 모드에서 설정의 「심사용 판정 보기」를 켰을 때만 닫지 않고 그 화면을 그린다(기본 꺼짐. 11/14 사용자 방향: 앱 화면은 실제 쓰는 모습만)
        if (Engine.phase == Phase.DRIVING || Engine.phase == Phase.ARRIVED) {
            if (!(Engine.demo && Engine.prefs.judgeView)) { finish(); return }
            setContentView(judgeView())
            return
        }
        val root = FrameLayout(this).apply { setBackgroundColor(tone.g0) }
        // M19b 틀(장면 1, 4): 자격 확인 + 카드를 한 덩어리로 세로 가운데, 글자 줄 왼쪽 끝 96 dp. 도착(3a, 3b)은 M14 그대로. 머리줄은 판정 보기와 같은 높이 89
        val wide = Engine.phase != Phase.RESULT
        val col = column { addView(bar(), lp(MATCH, ki(89f))) }
        val stage = if (wide) column { gravity = Gravity.CENTER_VERTICAL; pad(31f, 0f, 31f, 31f) } else column(16f) { pad(24f, 16f, 24f, 24f) }
        when (Engine.phase) {
            Phase.IDLE, Phase.ARMED -> { stage.addView(qual(), lp(MATCH)); if (Engine.phase == Phase.IDLE) stage.addView(departSheet(), lp(MATCH)) }
            Phase.RESULT -> { stage.addView(band(), lp(MATCH, 0, 1f)); stage.addView(if (Engine.detail) detailSheet() else resultSheet(), lp(MATCH)) }
            Phase.PRIZE -> { stage.addView(qual(), lp(MATCH)); stage.addView(prizeSheet(), lp(MATCH)) }
            else -> {}
        }
        col.addView(stage, lp(MATCH, 0, 1f))
        root.addView(col, FrameLayout.LayoutParams(MATCH, MATCH))
        // 앰비언트 한 번 반짝(아래 가장자리 띠). 에뮬레이터는 실제 앰비언트가 안 되어 데모 입력
        if (SystemClock.elapsedRealtime() < Engine.ambientFlashUntil)
            root.addView(View(this).apply { background = android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(Color.TRANSPARENT, tone.amb, Color.TRANSPARENT)) }, FrameLayout.LayoutParams(MATCH, ki(5f), Gravity.BOTTOM))
        if (Engine.demo && Engine.demoOpen) root.addView(demoPanel(), FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM or Gravity.START).apply { setMargins(ki(24f), 0, 0, ki(10f)) })
        setContentView(root)
    }

    // ---------- 상단 바 ----------
    // M26b 안 C(사용자 확정 11/14): 판정 보기 머리줄과 같은 규칙. 왼쪽 화면 이름, 오른쪽 시각(제목 높이의 먹색 숫자)과 요일, 기어, 단계 꼬리표
    private fun bar(): View {
        val now = LocalDateTime.now()
        val stage = when (Engine.phase) { Phase.IDLE, Phase.PRIZE -> "출발 전"; Phase.ARMED -> "리그 중"; Phase.RESULT -> "도착"; else -> "" }
        // 시각과 요일은 글자 밑선을 맞춘다(row는 세로 가운데 맞춤이라 따로 만든다)
        val time = LinearLayout(this).apply {
            addView(num(now.format(DateTimeFormatter.ofPattern("HH:mm")), 44f, tone.navT1, Typeface.DEFAULT))
            addView(text(now.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.KOREAN), 23f, tone.navT2).apply { minimumHeight = 0 }, lp().apply { marginStart = ki(10f) })
        }
        val right = row(15f) {
            addView(time, lp().apply { marginEnd = ki(12f) })
            if (gearLetter().isNotEmpty()) addView(chip("${gearLetter()}단", tone.navT2, tone.barPill))
            // 데모 모드에서는 단계 꼬리표를 길게 누르면 「데모 입력」 줄이 열린다(평소 화면에는 데모 표시가 없다)
            addView(chip(stage, tone.navT2, tone.barPill).apply { if (Engine.demo) setOnLongClickListener { Engine.demoOpen = !Engine.demoOpen; render(); true } })
        }
        return FrameLayout(this).apply {
            pad(74f, 0f, 74f, 0f)
            addView(text("세이프 리그", 35f, tone.navT1, MEDIUM).apply { setOnClickListener { if (Engine.gear != VehicleGear.GEAR_DRIVE) startActivity(Intent(this@MainActivity, SettingsActivity::class.java)) } },
                FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER_VERTICAL or Gravity.START))
            addView(right, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER_VERTICAL or Gravity.END))
        }
    }
    // 머리줄 꼬리표(메인, 판정 보기 공통): 글자 23, 높이 45, 둥근 바탕
    private fun chip(s: String, fg: Int, bg: Int) = text(s, 23f, fg).apply { gravity = Gravity.CENTER; background = rounded(bg, k(999f)); pad(18f, 0f, 18f, 0f); minHeight = ki(45f) }

    private fun gearLetter() = when (Engine.gear) { VehicleGear.GEAR_PARK -> "P"; VehicleGear.GEAR_DRIVE -> "D"; VehicleGear.GEAR_REVERSE -> "R"; VehicleGear.GEAR_NEUTRAL -> "N"; else -> "" }

    // ---------- 위쪽: 출발 조건(출발, 다음 날) ----------
    private fun qual(): View {
        val demo = Engine.demo
        val d = Engine.dest
        val slot = when (Engine.slot) { Engine.Slot.AM -> "아침"; Engine.Slot.PM -> "저녁"; else -> "리그 밖" }
        val items = listOf(
            "목적지" to if (demo) "회사" else (d?.name ?: "없음"),
            "예상 소요" to if (demo) "35분" else d?.let { "${it.remainTime / 60}분" } ?: "없음",
            "거리" to if (demo) "15 km" else d?.let { String.format("%.0f km", it.remainDistance / 1000.0) } ?: "없음",
            "시간대" to slot,
            "같은 길" to "${Engine.board?.total ?: 7}명",
        )
        // 칸별 회색 그림(M19b 00:30판: 핀, 시계, 경로, 해 뜨는 모양, 사람). 벡터 드로어블은 목업 SVG 그대로
        val icons = listOf(R.drawable.ck_pin, R.drawable.ck_clock, R.drawable.ck_route, R.drawable.ck_sunrise, R.drawable.ck_people)
        val checks = row {
            items.forEachIndexed { i, (name, value) ->
                addView(column {
                    pad(if (i == 0) 0f else 32f, 4f, 32f, 4f)
                    if (i > 0) background = object : android.graphics.drawable.ColorDrawable() { override fun draw(c: android.graphics.Canvas) { c.drawRect(0f, 0f, 1f, bounds.height().toFloat(), android.graphics.Paint().apply { color = tone.navHair }) } }
                    addView(row(10f) {
                        addView(android.widget.ImageView(context).apply { setImageResource(icons[i]); setColorFilter(tone.navT2) }, lp(ki(24.6f), ki(24.6f)))
                        addView(text(name, 20f, tone.navT2))
                    })
                    addView(num(value, 56f, tone.navT1).apply { maxLines = 1 }, lp().apply { topMargin = ki(10f) })
                }, lp(0, WRAP, 1f))
            }
        }
        return column {
            pad(43f, 0f, 43f, 37f)   // M19b .qual: 글자 줄 96 dp, 카드 위 48 dp
            addView(row(12f) { addView(CheckDot(context, tone, tone.ac, tone.acInk), lp(ki(34f), ki(34f))); addView(text("출발 조건을 모두 갖췄어요", 25f, tone.navT1, MEDIUM)) })
            addView(checks, lp(MATCH).apply { topMargin = ki(24f) })
            addView(text("회사 300 m 안에서 P단에 두면 결과가 나와요.\n${limitMin()}분 안에 도착해야 순위에 들어가요.", 25f, tone.navT2).apply { lineHeight = ki(25f * 1.4f) }, lp().apply { topMargin = ki(22f) })
        }
    }

    // 도착 기준 = 예상 소요의 120%(Engine.eligible과 같은 식). 데모 35분 → 42분
    private fun limitMin(): Int { val etaS = if (Engine.demo) 35 * 60 else (Engine.dest?.remainTime ?: 0); return etaS * 12 / 10 / 60 }

    // ---------- 위쪽: 경로 띠(도착) ----------
    private fun band(): View {
        val r = Engine.result; val j = Engine.judge
        val durMin = if (Engine.demo) 26 else ((j?.nowMs ?: 0L) / 60000).toInt()
        val total = (j?.nowMs ?: 1L).coerceAtLeast(1L).toFloat()
        val marks = Engine.cueMarks.map { (label, t) -> label to t / total }
        val dots = if (Engine.detail) (j?.penalties?.map { it.tMs / total } ?: emptyList()) else emptyList()
        val left = column {
            addView(num("도착", 56f, tone.navT1))
            addView(text("${if (Engine.demo) "회사" else Engine.dest?.name ?: "목적지"}, ${Score.fmtKm(r?.distanceKm ?: 0.0)} km, ${durMin}분", 20f, tone.navT2))
        }
        val cap = if (Engine.detail) "점 ${count(j?.penalties?.size ?: 0)}가 감점이 난 자리예요" else if (Engine.demo || Engine.dest != null) "${limitMin()}분 안에 도착해 순위에 들었어요" else "도착"
        return column {
            pad(36f, 46f, 36f, 0f)
            addView(row(56f) {
                addView(left, lp(ki(340f), WRAP))
                addView(Track(context, tone, marks, dots), lp(0, ki(96f), 1f))
            }, lp(MATCH))
            addView(text(cap, 25f, tone.navT2), lp().apply { topMargin = ki(20f); marginStart = ki(396f) })
        }
    }

    // ---------- 시트 공통 ----------
    private fun sheet(init: LinearLayout.() -> Unit) = column(28f) {
        background = rounded(tone.g1, k(24f)); pad(36f, 36f, 36f, 32f); init()
    }
    // M14 .app 그리드: 두 칸 모두 위에서 시작. 오른쪽 칸의 padding-top은 바깥 여백으로(쿠폰처럼 배경 있는 칸의 안쪽 여백을 덮지 않게)
    private fun twoCols(left: View, right: View, rightTop: Float = 42f) = row(64f) {
        gravity = Gravity.TOP
        addView(left, lp(0, WRAP, 1f)); addView(right, lp(0, WRAP, 1f).apply { topMargin = ki(rightTop) })
    }
    // M14 .hero .big: line-height .86. 안드로이드 글꼴은 숫자 위아래 여백이 더 커서 상자를 줄이고 위로 당긴다(아래 여백은 숫자엔 비어 있음)
    // M14에서 숫자 윗선은 상자 위에서 .155em, 상자 아래 = 기준선. 글꼴 치수를 재서 맞춘다(상자 아래는 기준선 + .02em, 위는 margin-top -6 + 남는 여백만큼 당김)
    private fun heroNum(s: String, px: Float) = num(s, px, tone.ac).apply {
        gravity = Gravity.TOP
        val em = paint.textSize; val base = -paint.fontMetrics.ascent
        val b = android.graphics.Rect().also { paint.getTextBounds("9", 0, 1, it) }
        layoutParams = lp(WRAP, (base + .02f * em).toInt()).apply { topMargin = -(k(6f) + base + b.top - .155f * em).toInt() }
    }
    // CSS line-height 1.45 줄 높이(한 줄 행)
    private fun View.lineBox(px: Float, padV: Float) { minimumHeight = ki(px * 1.45f + padV * 2) }
    private fun eyebrow(s: String) = text(s, 20f, tone.t2, MEDIUM).apply { letterSpacing = .08f }
    private fun h3(s: String) = text(s, 43f, tone.t1, MEDIUM).apply { lineHeight = ki(43f * 1.2f) }
    private fun body(s: String) = text(s, 25f, tone.t2).apply { lineHeight = ki(25f * 1.45f) }
    private fun hairline() = View(this).apply { setBackgroundColor(tone.hair) }
    // 위쪽 1px 선(순위표, 내역 줄, 받을 것 목록)
    private fun topLine(color: Int) = object : android.graphics.drawable.ColorDrawable() { override fun draw(c: android.graphics.Canvas) { c.drawRect(0f, 0f, bounds.width().toFloat(), 1f, android.graphics.Paint().apply { this.color = color }) } }
    // M19b 카드(장면 1, 4): 안쪽 여백 56 dp, 모서리 32 dp. 글자 줄이 96 dp에서 시작
    private fun card(gapPx: Float = 0f, init: LinearLayout.() -> Unit) = column(gapPx) { background = rounded(tone.g1, k(24.6f)); pad(43f, 43f, 43f, 43f); init() }

    // ---------- 장면 1: 출발 ----------
    private fun departSheet(): View {
        // 「함께 달리는 사람」은 앞 두 명 + 외 n명(26 dp에서 한 줄). 머리말 「오늘 아침 리그」는 위쪽 「시간대 아침」과 겹쳐 지움(M20 35번)
        val others = Engine.board?.rows?.filter { !it.me }?.map { it.name } ?: ai.pleos.contest.league.Ghost.names
        val names = others.take(2).joinToString(", ") + if (others.size > 2) " 외 ${others.size - 2}명" else ""
        val left = column(20f) {
            addView(h3("가장 안전하게 도착한\n순서로 겨뤄요"))
            addView(body("집 근처에서 회사 근처로 가는 분들끼리예요"), lp().apply { topMargin = -ki(8f) })
            addView(text("함께 달리는 사람: $names", 20f, tone.t2))
        }
        val belt = !Engine.beltWarning
        val right = column(20f) {
            addView(row(20f) {
                addView(seat("운전석", true), lp(ki(84f), WRAP)); addView(seat("동승석", belt), lp(ki(84f), WRAP))
            })
            addView(body(if (belt) "두 분 모두 벨트를 맸어요.\n결과에 +1이 더해져요." else "동승석 벨트 아직"))
        }
        val low = row(64f) {
            addView(body("운전에 방해되지 않게 주행 중에는 비워 둬요."), lp(0, WRAP, 1f))
            addView(row(16f) {
                addView(button("이번엔 쉬기", false, tone) { Engine.rest() }, lp(0, ki(80f), 1f))
                addView(button("참가하고 출발", true, tone) { Engine.join() }, lp(0, ki(80f), 1f))
            }, lp(0, WRAP, 1f))
        }
        return card(28f) { addView(twoCols(left, right), lp(MATCH)); addView(low, lp(MATCH)) }
    }

    private fun seat(label: String, on: Boolean) = column(8f) {
        gravity = Gravity.CENTER_HORIZONTAL
        addView(Seat(context, tone, on), lp(ki(84f), ki(72f))); addView(text(label, 20f, tone.t2))
    }

    // ---------- 장면 3a: 도착 결과, 순위 ----------
    private fun resultSheet(): View {
        val r = Engine.result ?: return sheet {}
        val b = Engine.board
        val left = column(14f) {
            clipChildren = false
            addView(eyebrow(resultTitle()))
            addView(heroRow(r), lp(MATCH))
            addView(ladder(r), lp(MATCH))
            b?.morning?.let { (rank, s) -> addView(text("오늘 아침 리그는 ${Score.fmt(s)}${Score.ro(s)} ${rank}위였어요.\n아침과 저녁은 따로 겨뤄요.", 20f, tone.t2)) }
        }
        val right = rankList()
        val low = row(64f) {
            addView(prizeLine(b?.league != false, long = true), lp(0, WRAP, 1f))
            addView(row {
                addView(text("내역 보기", 25f, tone.t1), lp(0, WRAP, 1f))
                addView(text("감점 ${r.penaltyCount}건, 가산 ${r.bonus.values.count { it > 0 }}건 ⌄", 20f, tone.t2))
                setOnClickListener { Engine.detail = true; render() }
            }, lp(0, WRAP, 1f))
        }
        return sheet {
            addView(twoCols(left, right, 35f), lp(MATCH))
            addView(hairline(), lp(MATCH, 1))
            addView(low, lp(MATCH).apply { topMargin = -ki(12f) })
        }
    }

    // 큰 점수 + 순위 한 줄 + 어제 비교(3a, 3b 같음. M29 안 가: 펼쳐도 점수가 줄지 않게)
    // M32 안 A(사용자 확정 11/15): 머리말에 같은 길 인원, 점수 옆에 바로 위 사람(1위면 2위)과의 차이. 값은 순위표 그대로
    private fun resultTitle(): String {
        val y = if (Engine.slot == Engine.Slot.PM) "저녁" else "아침"
        return Engine.board?.takeIf { it.league }?.let { "같은 길 ${it.total}명의 오늘 $y 리그" } ?: "오늘 $y 리그 결과"
    }
    private fun rivalLine(b: ai.pleos.contest.league.Board): String? {
        val i = b.rows.indexOfFirst { it.me }
        if (i < 0 || b.rows.size < 2) return null
        val other = b.rows[if (i == 0) 1 else i - 1]
        val d = Score.round1(abs(other.score - b.rows[i].score))
        val who = "${other.rank}위 ${other.name}"
        return when { d == 0.0 -> "$who${wa(other.name)} 같은 점수예요"; i == 0 -> "${who}보다 ${Score.fmt(d)} 앞서요"; else -> "$who${wa(other.name)} ${Score.fmt(d)} 차이예요" }
    }
    // 이름 끝 받침으로 와/과(숫자는 읽는 소리: 0 1 3 6 7 8은 받침 있음)
    private fun wa(s: String): String {
        val c = s.lastOrNull() ?: return "와"
        val final = if (c in '가'..'힣') (c - '가') % 28 != 0 else c in "013678"
        return if (final) "과" else "와"
    }

    private fun heroRow(r: Score.Result) = row(22f) {
        val b = Engine.board
        gravity = Gravity.BOTTOM; clipChildren = false   // 큰 숫자 상자를 위로 당긴 만큼 잘리지 않게
        addView(heroNum(Score.fmt(r.score), 128f))
        addView(column {
            pad(0f, 0f, 0f, 10f)
            addView(text(b?.let { "${it.total}명 중 ${it.myRank}위" } ?: "순위 확인 중", 26f, tone.t1, MEDIUM))
            b?.takeIf { it.league }?.let { rivalLine(it) }?.let { addView(text(it, 20f, tone.t1)) }
            b?.ghostDiff?.let { d -> val y = if (Engine.slot == Engine.Slot.PM) "저녁" else "아침"; addView(text(when {
                d > 0 -> "어제 ${y}보다 ${Score.fmt(d)} 올랐어요"; d < 0 -> "어제 ${y}보다 ${Score.fmt(-d)} 내렸어요"; else -> "어제 ${y}과 같은 점수예요" }, 20f, tone.t2)) }
        })
    }
    // 순위표: 위 3줄 + 흐린 한 줄(M14 .rows.rank). 3a는 오른쪽, 3b는 점수 아래(M29 안 가)
    private fun rankList() = column {
        val b = Engine.board
        if (b == null) addView(text("순위 확인 중", 18f, tone.t3)) else {
            val top = b.rows.filter { it.rank <= 3 || it.me }
            top.forEach { addView(rankRow(it), lp(MATCH)) }
            val rest = b.rows.filter { it !in top }
            if (rest.isNotEmpty()) addView(rankDim(rest.first().name + if (rest.size > 1) " 외 ${rest.size - 1}명" else "", "${rest.first().rank}~${rest.last().rank}위"), lp(MATCH))
        }
    }

    // 점수 아래 칸(M29 안 A, 사용자 확정 11/14): 받은 가산을 내역 줄과 같은 이름, 점수로. 다음 목표는 흐리게.
    // 점수와 무관한 고비(콤보 5, 10, 감점 0 km)와 금색 강조는 뺐다. 칸은 카드와 함께 한 번에 나타나고 효과음은 한 번
    private fun ladder(r: Score.Result): View {
        val plus = r.lines.filter { it.plus }
        // ponytail: 가산 상한(10) 넘는 날은 칸 합이 내역 합보다 클 수 있다. 6개 다 받아야 생겨 그대로 둠
        val shown = if (plus.size <= 3) plus else plus.take(2) + Score.Line("외 ${plus.size - 2}건", plus.drop(2).sumOf { it.value }, plus = true)
        val goal = when {
            r.bonus[Bonus.B2] != 1 -> Score.Line("감점 0 도착", Score.BONUS_WEIGHT.getValue(Bonus.B2))
            r.bonus[Bonus.B3] != 1 -> Score.Line(Score.BONUS_LABEL.getValue(Bonus.B3), Score.BONUS_WEIGHT.getValue(Bonus.B3))
            else -> null
        }
        val earnedW = maxOf(shown.size, 1).toFloat()   // 제목이 그 칸들 위에 걸치게 칸 수만큼 비율을 준다
        return column(6f) {
            addView(row(8f) {
                addView(text("이번에 더해진 점수", 20f, tone.t2), lp(0, WRAP, earnedW))
                if (goal != null) addView(text("다음 목표", 20f, tone.t2), lp(0, WRAP, 1f))
            }, lp(MATCH))
            addView(row(8f) {
                if (shown.isEmpty()) addView(text("아직 없어요", 20f, tone.t3), lp(0, WRAP, 1f))
                shown.forEach { addView(plusChip(it), lp(0, MATCH, 1f)) }
                goal?.let { addView(plusChip(it).apply { alpha = .35f }, lp(0, MATCH, 1f)) }
            }, lp(MATCH))
            alpha = 0f
            animate().alpha(1f).setStartDelay(300).setDuration(300).withStartAction { Engine.ladderTick() }.start()
        }
    }
    // 가산 칸: 위에 +점수, 아래에 내역과 같은 이름(두 줄까지, 낱말 단위로 줄바꿈)
    private fun plusChip(l: Score.Line) = column(4f) {
        gravity = Gravity.CENTER_VERTICAL; minimumHeight = ki(84f); pad(14f, 10f, 14f, 10f)
        background = rounded(tone.g2, k(10f))
        addView(num("+" + Score.fmt(l.value), 26f, tone.t1, Typeface.DEFAULT))
        addView(text(l.label, 20f, tone.t2).apply {
            lineHeight = ki(20f * 1.3f)
            if (android.os.Build.VERSION.SDK_INT >= 33) lineBreakWordStyle = android.graphics.text.LineBreakConfig.LINE_BREAK_WORD_STYLE_PHRASE
        })
    }

    private fun rankRow(r: ai.pleos.contest.league.Row) = row {
        pad(0f, 8f, 0f, 8f); lineBox(25f, 8f)
        background = topLine(tone.hair)
        addView(num("${r.rank}", 25f, if (r.me) tone.ac else tone.t3, Typeface.DEFAULT), lp(ki(30f), WRAP))
        addView(text(if (r.me) "나" else r.name, 25f, if (r.me) tone.ac else tone.t1), lp(0, WRAP, 1f))
        addView(num(String.format(Locale.US, "%.1f", r.score), 25f, if (r.me) tone.t1 else tone.t2, Typeface.DEFAULT))   // 순위표는 M14처럼 100.0
    }
    private fun rankDim(names: String, right: String) = row {
        pad(0f, 8f, 0f, 8f); lineBox(20f, 8f)
        addView(text(names, 20f, tone.t3), lp(0, WRAP, 1f)); addView(num(right, 20f, tone.t3, Typeface.DEFAULT))
    }
    private fun prizeLine(league: Boolean, long: Boolean) = column {
        if (league) {
            addView(text("이번 주 상위 3명은 도장을 하나 받아요", 20f, tone.t1, MEDIUM))   // 허브 확정 11/07: 한 문장만(3a, 3b 같음)
        } else addView(text("고스트 리그. 같은 길 3명이 모이면 도장이 열립니다", 20f, tone.t2))
    }

    // ---------- 장면 3b: 내역 펼침 ----------
    private fun detailSheet(): View {
        val r = Engine.result ?: return sheet {}
        val b = Engine.board
        // M29 안 가(사용자 확정 11/14): 점수는 3a와 같은 크기, 그 아래 순위표. 펼쳐도 순위가 사라지지 않고 아래가 비지 않게
        val left = column(14f) {
            clipChildren = false
            addView(eyebrow(resultTitle()))
            addView(heroRow(r), lp(MATCH))
            addView(rankList(), lp(MATCH))
        }
        // M14 .col.r gap 10은 「내역」 머리와 줄 묶음 사이에만. 줄끼리는 붙어 있다
        val rows = column {
            r.lines.forEach { l ->
                addView(lineRow(l.label, (if (l.plus) "+" else "") + Score.fmt(l.value), if (l.plus) tone.t1 else tone.t2, tone.hair), lp(MATCH))
            }
            val formula = "100 − ${Score.fmt(r.converted)}${if (r.bonusSum > 0) " + ${Score.fmt(r.bonusSum)}" else ""}"
            addView(lineRow(if (r.bonusSum > 0) "감점은 빼고 가산은 더해요" else "감점은 빼요", formula, tone.ac, tone.t3), lp(MATCH))
        }
        val right = column(10f) {
            addView(row { pad(0f, 0f, 0f, 4f); lineBox(25f, 0f); minimumHeight += ki(4f)
                addView(text("내역", 25f, tone.t1), lp(0, WRAP, 1f)); addView(text("접기 ⌃", 20f, tone.t2)); setOnClickListener { Engine.detail = false; render() } })
            addView(rows, lp(MATCH))
        }
        return sheet {
            addView(twoCols(left, right, 0f), lp(MATCH))
            addView(hairline(), lp(MATCH, 1))
            addView(prizeLine(b?.league != false, long = false), lp(MATCH).apply { topMargin = -ki(12f) })
        }
    }

    // 「점 세 개」처럼 작은 수는 우리말로
    private fun count(n: Int) = listOf("한", "두", "세", "네", "다섯", "여섯", "일곱", "여덟", "아홉").getOrNull(n - 1)?.let { "$it 개" } ?: "${n}개"

    private fun lineRow(label: String, value: String, valueColor: Int, lineColor: Int, valuePx: Float = 25f) = row {
        pad(0f, 9f, 0f, 9f); lineBox(valuePx, 9f)
        background = topLine(lineColor)
        addView(text(label, 25f, tone.t1), lp(0, WRAP, 1f)); addView(num(value, valuePx, valueColor, Typeface.DEFAULT))
    }

    // ---------- 장면 4: 다음 날 카드(M19b 안 C, 도장 세 개) ----------
    // 네 줄: 지난주 순위, 요일 근거, 도장 3칸 + 받을 것(셋이 차면 고름, 아니면 상태 한 줄), 다음 행동 + 단추
    private fun prizeSheet(): View {
        val p = Engine.prize ?: return card {}
        val full = p.stamps >= 3
        val stamps = row(12f) { repeat(3) { i -> addView(Stamp(context, tone, i, i < p.stamps), lp(ki(55f), ki(55f))) } }
        val third = row(31f) {
            if (full) { gravity = Gravity.TOP; addView(stamps); addView(choiceList(p), lp(0, WRAP, 1f)) }
            else { addView(stamps); addView(body("도장 세 개가 되면 안전 점검이나 아메리카노 중 하나를 골라요.").apply { setTextColor(tone.t1) }, lp(0, WRAP, 1f)) }
        }
        val added = Engine.waypointAddedMin
        val actText = if (added != null) "경유지 추가, 도착 +${added}분" else if (full) "고른 곳을 내비에 더할까요?" else "다음 주에 한 번 더 3위 안에 들면 채워져요."
        val act = row(31f) {
            addView(body(actText), lp(0, WRAP, 1f))
            addView(row(15f) {
                addView(button("다음에", false, tone) { Engine.later() }, lp(ki(261f), ki(80f)))
                if (full) addView(button("들러요", true, tone) { Engine.stopBy() }, lp(ki(261f), ki(80f)))
            })
        }
        return card {
            addView(h3("지난주 리그 ${p.rank}위로 마쳤어요"))
            addView(body("가장 안전했던 ${p.days} 세 번으로 정해졌어요."), lp().apply { topMargin = ki(9f) })
            addView(hairline(), lp(MATCH, 1).apply { topMargin = ki(31f) }); addView(third, lp(MATCH).apply { topMargin = ki(31f) })
            addView(hairline(), lp(MATCH, 1).apply { topMargin = ki(31f) }); addView(act, lp(MATCH).apply { topMargin = ki(31f) })
        }
    }

    // 받을 것 목록(M19b .list .it): 고른 줄은 금색 체크 + 밝은 글자, 안 고른 줄은 선으로만 그린 흐린 체크. 누르면 바뀜
    private fun choiceList(p: ai.pleos.contest.league.Prize) = column {
        p.choices.forEachIndexed { i, c ->
            val mine = i == Engine.prizeChoice
            addView(row(12f) {
                pad(0f, if (i == 0) 0f else 15f, 0f, if (i == p.choices.lastIndex) 0f else 15f)
                if (i > 0) background = topLine(tone.hair)
                addView(if (mine) CheckDot(context, tone, tone.ac, tone.acInk) else CheckDot(context, tone, tone.t3, tone.t3, ring = true), lp(ki(34f), ki(34f)))
                addView(text(c.name, 25f, if (mine) tone.t1 else tone.t2), lp(0, WRAP, 1f))
                addView(text("${Score.fmtKm(c.km)} km, 들르면 ${c.extraMin}분 더", 20f, tone.t2), lp().apply { marginEnd = ki(18f) })
                setOnClickListener { if (Engine.waypointAddedMin == null) { Engine.prizeChoice = i; Engine.notifyUi() } }   // 경유지를 더한 뒤에는 바꾸지 않음
            }, lp(MATCH))
        }
    }

    // ---------- 심사용 판정 보기(데모 모드, 주행 중) ----------
    // 목업 「판정보기_차선변경」「판정보기_급제동」(앱 칸 1688×1210 px 그대로, 아래 값은 그 px ÷ 1.3).
    // 판정은 Judge가 하고, 여기서는 판정 순간의 기록(Verdict)과 지금 값만 읽어 그린다. 문구는 Score.conditions
    private fun judgeView(): View {
        val vs = Engine.judge?.verdicts?.toList() ?: emptyList()
        val last = vs.lastOrNull()

        // 「데모 모드」 꼬리표를 누르면 「데모 입력」 줄이 열린다(닫혀 있을 때는 목업처럼 아무것도 얹지 않는다)
        val head = FrameLayout(this).apply {
            pad(74f, 0f, 74f, 0f)
            addView(row(22f) {
                addView(text("심사용 판정 보기", 35f, tone.navT1, MEDIUM))
                addView(chip("데모 모드", tone.tagInk, tone.tagBg).apply { setOnClickListener { Engine.demoOpen = !Engine.demoOpen; render() } })
            }, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER_VERTICAL or Gravity.START))
            addView(row(15f) {
                addView(chip("${gearLetter()}단", tone.navT2, tone.barPill))
                addView(chip(if (Engine.phase == Phase.ARRIVED) "도착" else "주행 중", tone.navT2, tone.barPill))
            }, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER_VERTICAL or Gravity.END))
        }

        // 왼쪽 칸: 방금 판정, 조건과 그때 받은 값, 지금 받는 신호
        val left = column {
            addView(row { minimumHeight = ki(40f)
                addView(text("방금 판정", 20f, tone.t2), lp(0, WRAP, 1f))
                if (last != null) addView(text("출발 후 ${Score.clock(last.tMs)}", 16f, tone.t3))
            }, lp(MATCH))
            addView(hairline(), lp(MATCH, 1))
            if (last == null) addView(text("아직 판정이 없어요", 31f, tone.t2).apply { minimumHeight = ki(158f) })
            else {
                addView(row { minimumHeight = ki(158f)
                    addView(text(Score.verdictLabel(last), 45f, tone.t1, MEDIUM, lines = 1), lp(0, WRAP, 1f))
                    addView(pointsView(last, 85f, 45f, MEDIUM, true))
                }, lp(MATCH))
                val conds = Score.conditions(last, Engine.judge?.cfg?.jamWindowSec ?: 60)
                if (conds.isNotEmpty()) addView(text("이 판정이 난 조건과 그때 받은 값", 20f, tone.t2).apply { minimumHeight = ki(54f) })
                conds.forEach { c ->
                    val fg = if (c.mark == Score.Mark.MET) tone.t2 else tone.t1
                    val valueFg = when (c.mark) { Score.Mark.MET -> tone.t2; Score.Mark.MISS -> tone.t1; Score.Mark.GAIN -> tone.ac }
                    addView(row { background = topLine(tone.hair)
                        addView(text(c.label, 29f, fg, lines = 1), lp(0, WRAP, 1f))
                        addView(mixed(c.value, 31f, 36f, valueFg))
                        addView(CondMark(context, valueFg, c.mark != Score.Mark.MISS), lp(ki(24f), ki(24f)).apply { marginStart = ki(24f); marginEnd = ki(4f) })
                    }, lp(MATCH, ki(85f)))
                }
            }
            addView(View(context), lp(MATCH, 0, 1f))
            addView(hairline(), lp(MATCH, 1))
            addView(text("지금 받는 신호", 18f, tone.t2), lp().apply { topMargin = ki(18f) })
            val names = listOf("속도" to "PERF_VEHICLE_SPEED", "핸들" to "SteeringWheelAngle", "깜빡이" to "TurnSignalState", "브레이크" to "BrakePedalStatus")
            val values = names.map { mixed("", 26f, 32f, tone.t1) }
            addView(row { gravity = Gravity.TOP
                names.forEachIndexed { i, (name, sdk) ->
                    addView(column {
                        addView(text(name, 18f, tone.t2))
                        addView(values[i], lp(WRAP, ki(42f)).apply { topMargin = ki(4f) })   // 높이를 고정해 아래 신호 이름 줄이 네 칸 같은 높이에 온다
                        addView(text(sdk, 13f, tone.t3), lp().apply { topMargin = ki(8f) })
                    }, if (i < 3) lp(ki(178f), WRAP) else lp(0, WRAP, 1f))
                }
            }, lp(MATCH).apply { topMargin = ki(12f) })
            live = {
                val th = Engine.steering * Engine.prefs.steeringSign   // 양수 = 왼쪽
                val now = listOf("${Engine.speedKmh.roundToInt()} km/h", if (abs(th) < .5f) "0°" else "${if (th > 0) "왼쪽" else "오른쪽"} ${abs(th).roundToInt()}°",
                    when (Engine.turnSignal) { -1 -> "왼쪽"; 1 -> "오른쪽"; else -> "꺼짐" }, if (Engine.brake) "밟음" else "밟지 않음")
                values.forEachIndexed { i, tv -> if (tv.text.toString() != now[i]) tv.text = numSpan(now[i], 32f) }
            }
            live?.invoke()
        }

        // 오른쪽 칸: 판정 기록(아홉 줄까지, 새 줄만 밝게), 지금까지 감점과 가산
        val right = column {
            addView(row { minimumHeight = ki(40f); addView(text("판정 기록", 20f, tone.t2), lp(0, WRAP, 1f)); addView(text("출발 후", 16f, tone.t3)) }, lp(MATCH))
            addView(hairline(), lp(MATCH, 1))
            addView(column {
                vs.takeLast(9).forEachIndexed { i, v ->
                    addView(row { if (i > 0) background = topLine(tone.hair)
                        addView(num(Score.clock(v.tMs), 20f, tone.t3, Typeface.DEFAULT), lp(ki(66f), WRAP))
                        addView(text(Score.verdictLabel(v), 21.5f, if (v === last) tone.t1 else tone.t2, lines = 1), lp(0, WRAP, 1f))
                        addView(pointsView(v, 30f, 20f, Typeface.DEFAULT, v === last))
                    }, lp(MATCH, ki(68f)))
                }
            }, lp(MATCH, 0, 1f))
            addView(row { background = topLine(tone.hair); minimumHeight = ki(71f)
                addView(text("지금까지", 21f, tone.t2), lp(0, WRAP, 1f))
                addView(mixed("감점 ${Score.fmt(-vs.sumOf { minOf(it.points, 0.0) })}", 20f, 32f, tone.t2, Typeface.DEFAULT, tone.t1))
                addView(mixed("가산 +${Score.fmt(vs.sumOf { maxOf(it.points, 0.0) })}", 20f, 32f, tone.t2, Typeface.DEFAULT, tone.ac), lp().apply { marginStart = ki(33f) })
            }, lp(MATCH))
        }

        val card = row {
            background = rounded(tone.g1, k(34f)); pad(43f, 37f, 43f, 35f)
            addView(left, lp(0, MATCH, 1f))
            addView(hairline(), lp(1, MATCH).apply { marginStart = ki(40f); marginEnd = ki(40f) })
            addView(right, lp(ki(377f), MATCH))
        }
        return FrameLayout(this).apply {
            setBackgroundColor(tone.g0)
            addView(column {
                addView(head, lp(MATCH, ki(89f)))
                addView(card, lp(MATCH, 0, 1f).apply { setMargins(ki(31f), 0, ki(31f), ki(31f)) })
            }, FrameLayout.LayoutParams(MATCH, MATCH))
            if (Engine.demoOpen) addView(demoPanel(), FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM or Gravity.START).apply { setMargins(ki(24f), 0, 0, ki(10f)) })
        }
    }

    // 점수 칸: 감점은 흰색 −2, 가산은 금색 +1, 깜빡이 켠 차선 변경은 「콤보 n」(새 줄만 금색, 숫자도 한글과 같은 글꼴)
    private fun pointsView(v: Verdict, numPx: Float, comboPx: Float, comboTf: Typeface, newest: Boolean): View = when {
        v.pen == null && v.bonus == null -> text("콤보 ${v.combo}", comboPx, if (newest) tone.ac else tone.t2, comboTf, lines = 1)
        else -> num(Score.points(v.points), numPx, if (v.points > 0) tone.ac else tone.t1)
    }

    // 한글과 단위는 IBM Plex, 숫자는 Barlow로 한 줄에 쓴다(목업 값 표기 「60 km/h」「왼쪽 8°」). numPx = 숫자 크기, numColor = 숫자만 다른 색
    private fun mixed(s: String, px: Float, numPx: Float, color: Int, tf: Typeface = MEDIUM, numColor: Int = color) =
        text("", px, color, tf, lines = 1).apply { text = numSpan(s, numPx, numColor.takeIf { it != color }) }
    private fun numSpan(s: String, numPx: Float, numColor: Int? = null): CharSequence = android.text.SpannableString(s).apply {
        Regex("[0-9.+−°]+").findAll(s).forEach { m ->
            val (a, b) = m.range.first to m.range.last + 1
            setSpan(android.text.style.TypefaceSpan(font(Typeface.DEFAULT, numeric = true)), a, b, 0)
            setSpan(android.text.style.AbsoluteSizeSpan(ki(numPx)), a, b, 0)
            if (numColor != null) setSpan(android.text.style.ForegroundColorSpan(numColor), a, b, 0)
        }
    }

    // ---------- 데모 입력 ----------
    private fun demoPanel(): View = row(8f) {
        val note = Engine.demoNote
        addView(text(if (note != null) "데모 입력: $note" else "데모 입력", 15f, tone.ac).apply {
            background = rounded(tone.acDim, k(999f)); pad(12f, 6f, 12f, 6f)
            setOnClickListener { Engine.demoOpen = !Engine.demoOpen; render() }
        })
        if (Engine.demoOpen) listOf("도착", "앰비언트", "주의 경고", "카메라", "주의 구간", "다음 날", "처음부터").forEach { w ->
            addView(text(w, 15f, tone.navT2).apply {
                background = rounded(tone.barPill, k(999f)); pad(12f, 6f, 12f, 6f)
                setOnClickListener { if (w == "처음부터") Engine.resetForDemo() else Engine.demoInput(w); render() }
            })
        }
    }
}
