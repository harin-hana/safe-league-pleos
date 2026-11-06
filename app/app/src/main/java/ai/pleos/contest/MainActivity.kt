package ai.pleos.contest

import ai.pleos.contest.league.Bonus
import ai.pleos.contest.league.Engine
import ai.pleos.contest.league.Engine.Phase
import ai.pleos.contest.league.Score
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
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

// 세이프 리그 화면. M14 장면 1(출발), 3a 3b(도착 결과), 4(다음 날 카드). 주행 중에는 앱이 뒤로 가고 아무것도 띄우지 않는다.
// 상태는 Engine(프로세스 싱글턴)에 있어 야간 전환으로 다시 만들어져도 그대로다.
class MainActivity : Activity() {

    private val main = Handler(Looper.getMainLooper())
    private lateinit var tone: Tone
    private val refresh = { render() }
    private val minute = object : Runnable { override fun run() { render(); main.postDelayed(this, 60_000) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Engine.start(this)
        handleIntent(intent)
        startForegroundService(Intent(this, ArrivalService::class.java))
        if (checkSelfPermission("android.car.permission.CAR_SPEED") == PackageManager.PERMISSION_GRANTED) Engine.connectAaos(this)
        else requestPermissions(arrayOf("android.car.permission.CAR_SPEED"), 1)
        render()
    }

    private var userOpened = true   // 서비스가 P단에 띄운 게 아니라 사람이 연 것
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); handleIntent(intent); render() }

    // 촬영 준비용 adb: am start -n …/.MainActivity --ez demo true, --es input 도착 (데모 입력과 같음)
    private fun handleIntent(i: Intent) {
        userOpened = !i.getBooleanExtra(ArrivalService.EXTRA_FROM_P, false)
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

    override fun onResume() { super.onResume(); Engine.addListener(refresh); main.post(minute); render() }
    override fun onPause() { Engine.removeListener(refresh); main.removeCallbacks(minute); super.onPause() }

    private var lastKey = ""
    private fun render() {
        val night = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        // 화면에 보이는 상태가 안 바뀌었으면 다시 그리지 않는다(에뮬레이터 렌더가 느려 터치를 잃는다)
        val key = listOf(Engine.phase, Engine.gear, Engine.beltWarning, Engine.board?.myRank, Engine.detail, Engine.demoNote, Engine.demoOpen, Engine.demo,
            Engine.waypointAddedMin, Engine.prize?.stamps, Engine.prizeChoice, SystemClock.elapsedRealtime() < Engine.ambientFlashUntil, night, Engine.wiper,
            LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm")), Engine.dest?.name).joinToString("|")
        if (key == lastKey) return
        lastKey = key
        tone = Tone(day = !night, rain = night && Engine.wiper)
        // 주행 중(또는 도착 뒤 P단 전)에는 화면이 없다. 화면을 닫아 내비가 앞에 오게 하고, 결과는 P단에서 서비스가 다시 올린다.
        // 데모 모드에서 일부러 앱을 열면 「데모 입력」 줄만 보인다(도착 스위치용)
        if (Engine.phase == Phase.DRIVING || Engine.phase == Phase.ARRIVED) {
            val justStarted = lastKey.startsWith("ARMED|")
            if (!Engine.demo || !userOpened || justStarted) { finish(); return }
            setContentView(FrameLayout(this).apply {
                setBackgroundColor(tone.nav)
                addView(demoPanel(), FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM or Gravity.START).apply { setMargins(ki(24f), 0, 0, ki(10f)) })
            })
            return
        }
        val root = FrameLayout(this).apply { setBackgroundColor(tone.g0) }
        // M19b 틀(장면 1, 4): 머리글 96 dp, 자격 확인 + 카드를 한 덩어리로 세로 가운데, 글자 줄 왼쪽 끝 96 dp. 도착(3a, 3b)은 M14 그대로
        val wide = Engine.phase != Phase.RESULT
        val col = column { addView(bar(wide), lp(MATCH, ki(if (wide) 74f else 52f))) }
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
        if (Engine.demo) root.addView(demoPanel(), FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM or Gravity.START).apply { setMargins(ki(24f), 0, 0, ki(10f)) })
        setContentView(root)
    }

    // ---------- 상단 바 ----------
    private fun bar(wide: Boolean): View {
        val now = LocalDateTime.now()
        val left = row(10f) {
            addView(num(now.format(DateTimeFormatter.ofPattern("HH:mm")), 20f, tone.navT1, MEDIUM))
            addView(text(now.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.KOREAN), 20f, tone.navT2))
            val g = when (Engine.gear) { VehicleGear.GEAR_PARK -> "P"; VehicleGear.GEAR_DRIVE -> "D"; VehicleGear.GEAR_REVERSE -> "R"; VehicleGear.GEAR_NEUTRAL -> "N"; else -> "" }
            addView(num(g, 20f, tone.navT1, MEDIUM).apply { gravity = Gravity.CENTER; background = rounded(tone.barChip, k(8f)) }, lp(ki(37f), ki(37f)).apply { marginStart = ki(4f) })
        }
        val pillText = when (Engine.phase) { Phase.IDLE, Phase.PRIZE -> "출발 전"; Phase.ARMED -> "리그 중"; Phase.RESULT -> "도착"; else -> "" }
        val right = row(10f) {
            addView(text("세이프 리그", 20f, tone.navT2).apply { setOnClickListener { if (Engine.gear != VehicleGear.GEAR_DRIVE) startActivity(Intent(this@MainActivity, SettingsActivity::class.java)) } })
            addView(pill(pillText, tone, accent = Engine.phase == Phase.ARMED), lp(WRAP, ki(37f)))
        }
        return FrameLayout(this).apply {
            val side = if (wide) 74f else 32f
            pad(side, 0f, side, 0f)
            addView(left, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER_VERTICAL or Gravity.START))
            addView(right, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER_VERTICAL or Gravity.END))
        }
    }

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
            addView(text("${if (Engine.demo) "집" else Engine.dest?.name ?: "목적지"}, ${Score.fmtKm(r?.distanceKm ?: 0.0)} km, ${durMin}분", 20f, tone.navT2))
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
        val hero = row(22f) {
            gravity = Gravity.BOTTOM; clipChildren = false   // 큰 숫자 상자를 위로 당긴 만큼 잘리지 않게
            addView(heroNum(Score.fmt(r.score), 128f))
            addView(column {
                pad(0f, 0f, 0f, 10f)
                addView(text(b?.let { "${it.total}명 중 ${it.myRank}위" } ?: "순위 확인 중", 26f, tone.t1, MEDIUM))
                b?.ghostDiff?.let { d -> val y = if (Engine.slot == Engine.Slot.PM) "저녁" else "아침"; addView(text(if (d >= 0) "어제 ${y}보다 ${Score.fmt(d)} 올랐어요" else "어제 ${y}보다 ${Score.fmt(-d)} 내렸어요", 20f, tone.t2)) }
            })
        }
        val left = column(14f) {
            clipChildren = false
            addView(eyebrow("오늘 ${if (Engine.slot == Engine.Slot.PM) "저녁" else "아침"} 리그 결과"))
            addView(hero, lp(MATCH))
            addView(ladder(r), lp(MATCH, ki(108f)))
            b?.morning?.let { (rank, s) -> addView(text("오늘 아침 리그는 ${Score.fmt(s)}${Score.ro(s)} ${rank}위였어요.\n아침과 저녁은 따로 겨뤄요.", 20f, tone.t2)) }
        }
        val right = column {
            if (b == null) addView(text("순위 확인 중", 18f, tone.t3)) else {
                // 위 3줄 + 흐린 한 줄(M14 .rows.rank)
                val top = b.rows.filter { it.rank <= 3 || it.me }
                top.forEach { addView(rankRow(it), lp(MATCH)) }
                val rest = b.rows.filter { it !in top }
                if (rest.isNotEmpty()) addView(rankDim(rest.first().name + if (rest.size > 1) " 외 ${rest.size - 1}명" else "", "${rest.first().rank}~${rest.last().rank}위"), lp(MATCH))
            }
        }
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

    // 콤보 사다리: 닿은 고비가 아래서부터 겹쳐 올라가고, 마지막 가산은 강조, 다음 고비는 흐리게
    private fun ladder(r: Score.Result): View {
        val items = mutableListOf<Pair<String, Boolean>>()
        listOf(5, 10, 20).filter { it <= r.comboMax }.forEach { items += "깜빡이\n콤보 $it" to true }
        // 데모는 실제로 1km 남짓만 달려 클린 km가 안 쌓인다. 데모 고정값(집, 15 km, 26분)처럼 M14 3a 값 5개를 쓴다
        val cleanKm = if (Engine.demo) 5 else r.cleanKm.toInt()
        listOf(5, 10).filter { it <= cleanKm }.forEach { items += "감점 0\n${it} km" to true }
        r.bonus.filter { it.value > 0 && it.key != Bonus.B1 && it.key != Bonus.B2 }.keys.forEach { items += Score.BONUS_LABEL.getValue(it).replace(" ", "\n") to true }
        val next = if (r.bonus[Bonus.B2] == 1) "클린\n위크" else "감점 0\n도착"
        val reached = items.size
        items += next to false
        val heights = listOf(60f, 76f, 92f, 108f)   // M20: 글자 20에 두 줄이 들어가게(계단 간격 16 그대로)
        return row(8f) {
            gravity = Gravity.BOTTOM
            clipChildren = false
            items.forEachIndexed { i, (label, ok) ->
                val last = ok && i == reached - 1 && r.bonusSum > 0
                val h = if (!ok) 60f else heights[minOf(i, 3)]
                // 낮은 칸(60)은 두 줄 글자가 칸보다 커서 M14처럼 위아래로 넘쳐 보이게 한다(칸이 글자를 자르지 않게)
                val tv = text(label, 20f, if (last) tone.acInk else tone.t2, if (last) MEDIUM else Typeface.DEFAULT).apply {
                    gravity = Gravity.CENTER; lineHeight = ki(20f * 1.3f); pad(4f, 0f, 4f, 0f)
                }
                addView(FrameLayout(context).apply {
                    clipChildren = false; background = rounded(if (last) tone.ac else tone.g2, k(10f))
                    addView(tv, FrameLayout.LayoutParams(MATCH, ki(108f), Gravity.CENTER))   // 칸보다 크게 재고 가운데에(WRAP이면 칸 높이로 잘림)
                    if (!ok) alpha = .35f
                    else { alpha = 0f; animate().alpha(1f).setStartDelay(220L * i).setDuration(260).withStartAction { if (i > 0) Engine.ladderTick() }.start() }
                }, lp(0, ki(h), 1f))
            }
        }
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
        val left = column(10f) {
            clipChildren = false
            addView(eyebrow("오늘 ${if (Engine.slot == Engine.Slot.PM) "저녁" else "아침"} 리그 결과"))
            addView(row(22f) {
                gravity = Gravity.BOTTOM; clipChildren = false
                addView(heroNum(Score.fmt(r.score), 72f))
                addView(text(b?.let { "${it.total}명 중 ${it.myRank}위" } ?: "순위 확인 중", 26f, tone.t1, MEDIUM).apply { pad(0f, 0f, 0f, 4f) })
            })
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

    // 받을 것 목록(M19b .list .it): 고른 줄은 금색 체크 + 밝은 글자, 누르면 바뀜
    private fun choiceList(p: ai.pleos.contest.league.Prize) = column {
        p.choices.forEachIndexed { i, c ->
            val mine = i == Engine.prizeChoice
            addView(row(12f) {
                pad(0f, if (i == 0) 0f else 15f, 0f, if (i == p.choices.lastIndex) 0f else 15f)
                if (i > 0) background = topLine(tone.hair)
                if (mine) addView(CheckDot(context, tone, tone.ac, tone.acInk), lp(ki(34f), ki(34f))) else addView(View(context), lp(ki(34f), ki(34f)))
                addView(text(c.name, 25f, if (mine) tone.t1 else tone.t2), lp(0, WRAP, 1f))
                addView(text("${Score.fmtKm(c.km)} km, 들르면 ${c.extraMin}분 더", 20f, tone.t2), lp().apply { marginEnd = ki(18f) })
                setOnClickListener { Engine.prizeChoice = i; Engine.notifyUi() }
            }, lp(MATCH))
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
