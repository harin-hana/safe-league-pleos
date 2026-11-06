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
            Engine.waypointAddedMin, Engine.prize?.shop, SystemClock.elapsedRealtime() < Engine.ambientFlashUntil, night, Engine.wiper,
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
        val col = column { addView(bar(), lp(MATCH, ki(52f))) }
        val stage = column(16f) { pad(24f, 16f, 24f, 24f) }
        when (Engine.phase) {
            Phase.IDLE, Phase.ARMED -> { stage.addView(qual(), lp(MATCH, 0, 1f)); if (Engine.phase == Phase.IDLE) stage.addView(departSheet(), lp(MATCH)) }
            Phase.RESULT -> { stage.addView(band(), lp(MATCH, 0, 1f)); stage.addView(if (Engine.detail) detailSheet() else resultSheet(), lp(MATCH)) }
            Phase.PRIZE -> { stage.addView(qual(), lp(MATCH, 0, 1f)); stage.addView(prizeSheet(), lp(MATCH)) }
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
    private fun bar(): View {
        val now = LocalDateTime.now()
        val left = row(10f) {
            addView(num(now.format(DateTimeFormatter.ofPattern("HH:mm")), 20f, tone.navT1, MEDIUM))
            addView(text(now.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.KOREAN), 18f, tone.navT2))
            val g = when (Engine.gear) { VehicleGear.GEAR_PARK -> "P"; VehicleGear.GEAR_DRIVE -> "D"; VehicleGear.GEAR_REVERSE -> "R"; VehicleGear.GEAR_NEUTRAL -> "N"; else -> "" }
            addView(num(g, 17f, tone.navT1, MEDIUM).apply { gravity = Gravity.CENTER; background = rounded(tone.barChip, k(8f)) }, lp(ki(30f), ki(30f)).apply { marginStart = ki(4f) })
        }
        val pillText = when (Engine.phase) { Phase.IDLE -> "리그 대기"; Phase.ARMED -> "리그 중"; Phase.RESULT -> "도착"; Phase.PRIZE -> "리그 대기"; else -> "" }
        val right = row(10f) {
            addView(text("세이프 리그", 18f, tone.navT2).apply { setOnClickListener { if (Engine.gear != VehicleGear.GEAR_DRIVE) startActivity(Intent(this@MainActivity, SettingsActivity::class.java)) } })
            addView(pill(pillText, tone, accent = Engine.phase == Phase.ARMED), lp(WRAP, ki(30f)))
        }
        return FrameLayout(this).apply {
            pad(32f, 0f, 32f, 0f)
            addView(left, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER_VERTICAL or Gravity.START))
            addView(right, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER_VERTICAL or Gravity.END))
        }
    }

    // ---------- 위쪽: 리그 자격 확인(출발, 다음 날) ----------
    private fun qual(): View {
        val demo = Engine.demo
        val d = Engine.dest
        val slot = when (Engine.slot) { Engine.Slot.AM -> "아침"; Engine.Slot.PM -> "저녁"; else -> "리그 밖" }
        val items = listOf(
            "목적지" to if (demo) "회사" else (d?.name ?: "없음"),
            "예상" to if (demo) "35분" else d?.let { "${it.remainTime / 60}분" } ?: "없음",
            "거리" to if (demo) "15 km" else d?.let { String.format("%.0f km", it.remainDistance / 1000.0) } ?: "없음",
            "시간대" to slot,
            "같은 길" to "${Engine.board?.total ?: 7}명",
        )
        val checks = row {
            items.forEachIndexed { i, (name, value) ->
                val ok = value != "없음" && value != "리그 밖"
                addView(column {
                    pad(if (i == 0) 0f else 32f, 4f, 32f, 4f)
                    if (i > 0) background = object : android.graphics.drawable.ColorDrawable() { override fun draw(c: android.graphics.Canvas) { c.drawRect(0f, 0f, 1f, bounds.height().toFloat(), android.graphics.Paint().apply { color = tone.navHair }) } }
                    addView(row(10f) {
                        if (ok) addView(CheckDot(context, tone), lp(ki(24f), ki(24f)))
                        addView(text(name, 20f, tone.navT2))
                    })
                    addView(num(value, 56f, tone.navT1).apply { maxLines = 1 }, lp().apply { topMargin = ki(10f) })
                }, lp(0, WRAP, 1f))
            }
        }
        return column {
            pad(36f, 46f, 36f, 0f)
            addView(text("리그 자격 확인", 15f, tone.navT2, MEDIUM).apply { letterSpacing = .08f })
            addView(checks, lp(MATCH).apply { topMargin = ki(24f) })
        }
    }

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
        val cap = if (Engine.detail) "점 ${j?.penalties?.size ?: 0}개는 감점이 난 자리" else if (Engine.demo) "예상 35분 안에 도착" else Engine.dest?.let { "예상 ${it.remainTime / 60}분 안에 도착" } ?: "도착"
        return column {
            pad(36f, 46f, 36f, 0f)
            addView(row(56f) {
                addView(left, lp(ki(340f), WRAP))
                addView(Track(context, tone, marks, dots), lp(0, ki(96f), 1f))
            }, lp(MATCH))
            addView(text(cap, 20f, tone.navT2), lp().apply { topMargin = ki(20f); marginStart = ki(396f) })
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
    private fun eyebrow(s: String) = text(s, 15f, tone.t2, MEDIUM).apply { letterSpacing = .08f }
    private fun h3(s: String) = text(s, 34f, tone.t1, MEDIUM).apply { lineHeight = ki(34f * 1.25f) }
    private fun body(s: String) = text(s, 20f, tone.t2).apply { lineHeight = ki(20f * 1.45f) }
    private fun hairline() = View(this).apply { setBackgroundColor(tone.hair) }

    // ---------- 장면 1: 출발 ----------
    private fun departSheet(): View {
        val slotName = when (Engine.slot) { Engine.Slot.PM -> "저녁"; else -> "아침" }
        val n = Engine.board?.total ?: 7
        val names = (Engine.board?.rows?.filter { !it.me }?.map { it.name } ?: ai.pleos.contest.league.Ghost.names).joinToString(", ")
        val left = column(20f) {
            addView(eyebrow("오늘 $slotName 리그"))
            addView(h3("같은 길 ${n}명이\n함께 출발합니다"))
            addView(body(if (Engine.demo || Engine.dest == null) "집에서 회사, 아침 06~10시" else "${Engine.dest!!.name}까지, ${if (Engine.slot == Engine.Slot.PM) "저녁 17~21시" else "아침 06~10시"}"), lp().apply { topMargin = -ki(8f) })
            addView(text("함께 달리는 사람: $names", 15f, tone.t3))
        }
        val belt = !Engine.beltWarning
        val right = column(20f) {
            addView(row(20f) {
                addView(seat("운전석", true), lp(ki(84f), WRAP)); addView(seat("동승석", belt), lp(ki(84f), WRAP))
            })
            addView(body(if (belt) "착석 2명 모두 벨트. 벨트 올인" else "동승석 벨트 아직"))
        }
        val low = row(64f) {
            addView(body("주행 중에는 아무것도 보여 주지 않습니다.\n결과는 도착해서 P단에 놓았을 때 한 번."), lp(0, WRAP, 1f))
            addView(row(16f) {
                addView(button("오늘은 쉬기", false, tone) { Engine.rest() }, lp(0, ki(72f), 1f))
                addView(button("리그 참가", true, tone) { Engine.join() }, lp(0, ki(72f), 1f))
            }, lp(0, WRAP, 1f))
        }
        return sheet { addView(twoCols(left, right), lp(MATCH)); addView(low, lp(MATCH)) }
    }

    private fun seat(label: String, on: Boolean) = column(8f) {
        gravity = Gravity.CENTER_HORIZONTAL
        addView(Seat(context, tone, on), lp(ki(84f), ki(72f))); addView(text(label, 15f, tone.t2))
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
                b?.ghostDiff?.let { d -> addView(text(if (d >= 0) "어제의 나보다 ${Score.fmt(d)} 위" else "어제의 나보다 ${Score.fmt(-d)} 아래", 17f, tone.t2)) }
            })
        }
        val left = column(14f) {
            clipChildren = false
            addView(eyebrow("오늘 ${if (Engine.slot == Engine.Slot.PM) "저녁" else "아침"} 리그 결과"))
            addView(hero, lp(MATCH))
            addView(ladder(r), lp(MATCH, ki(84f)))
            b?.morningLine?.let { addView(text("$it. 아침과 저녁은 따로 겨룹니다", 15f, tone.t3)) }
        }
        val right = column {
            if (b == null) addView(text("순위 확인 중", 18f, tone.t3)) else {
                // 위 3줄 + 흐린 한 줄(M14 .rows.rank)
                val top = b.rows.filter { it.rank <= 3 || it.me }
                top.forEach { addView(rankRow(it), lp(MATCH)) }
                val rest = b.rows.filter { it !in top }
                if (rest.isNotEmpty()) addView(rankDim(rest.joinToString(", ") { it.name }, "${rest.first().rank}~${rest.last().rank}위"), lp(MATCH))
            }
        }
        val low = row(64f) {
            addView(prizeLine(b?.league != false, long = true), lp(0, WRAP, 1f))
            addView(row {
                addView(text("내역 보기", 20f, tone.t1), lp(0, WRAP, 1f))
                addView(text("감점 ${r.penaltyCount}건, 가산 ${r.bonus.values.count { it > 0 }}건 ⌄", 17f, tone.t2))
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
        listOf(5, 10).filter { it <= cleanKm }.forEach { items += "클린 1km\n${it}개" to true }
        r.bonus.filter { it.value > 0 && it.key != Bonus.B1 && it.key != Bonus.B2 }.keys.forEach { items += Score.BONUS_LABEL.getValue(it).replace(" ", "\n") to true }
        val next = if (r.bonus[Bonus.B2] == 1) "클린\n위크" else "클린\n주행"
        val reached = items.size
        items += next to false
        val heights = listOf(36f, 52f, 68f, 84f)
        return row(8f) {
            gravity = Gravity.BOTTOM
            clipChildren = false
            items.forEachIndexed { i, (label, ok) ->
                val last = ok && i == reached - 1 && r.bonusSum > 0
                val h = if (!ok) 36f else heights[minOf(i, 3)]
                // 낮은 칸(36)은 두 줄 글자가 칸보다 커서 M14처럼 위아래로 넘쳐 보이게 한다(칸이 글자를 자르지 않게)
                val tv = text(label, 15f, if (last) tone.acInk else tone.t2, if (last) MEDIUM else Typeface.DEFAULT).apply {
                    gravity = Gravity.CENTER; lineHeight = ki(15f * 1.3f); pad(4f, 0f, 4f, 0f)
                }
                addView(FrameLayout(context).apply {
                    clipChildren = false; background = rounded(if (last) tone.ac else tone.g2, k(10f))
                    addView(tv, FrameLayout.LayoutParams(MATCH, ki(84f), Gravity.CENTER))   // 칸보다 크게 재고 가운데에(WRAP이면 칸 높이로 잘림)
                    if (!ok) alpha = .35f
                    else { alpha = 0f; animate().alpha(1f).setStartDelay(220L * i).setDuration(260).withStartAction { if (i > 0) Engine.ladderTick() }.start() }
                }, lp(0, ki(h), 1f))
            }
        }
    }

    private fun rankRow(r: ai.pleos.contest.league.Row) = row {
        pad(0f, 8f, 0f, 8f); lineBox(22f, 8f)
        background = object : android.graphics.drawable.ColorDrawable() { override fun draw(c: android.graphics.Canvas) { c.drawRect(0f, 0f, bounds.width().toFloat(), 1f, android.graphics.Paint().apply { color = tone.hair }) } }
        addView(num("${r.rank}", 18f, if (r.me) tone.ac else tone.t3, Typeface.DEFAULT), lp(ki(26f), WRAP))
        addView(text(if (r.me) "나" else r.name, 18f, if (r.me) tone.ac else tone.t1), lp(0, WRAP, 1f))
        addView(num(String.format(Locale.US, "%.1f", r.score), 22f, if (r.me) tone.t1 else tone.t2, Typeface.DEFAULT))   // 순위표는 M14처럼 100.0
    }
    private fun rankDim(names: String, right: String) = row {
        pad(0f, 8f, 0f, 8f); lineBox(15f, 8f)
        addView(text(names, 15f, tone.t3), lp(0, WRAP, 1f)); addView(num(right, 15f, tone.t3, Typeface.DEFAULT))
    }
    private fun prizeLine(league: Boolean, long: Boolean) = row(8f) {
        if (league) {
            addView(text("이번 주 리그 상금", 17f, tone.t1, MEDIUM))
            addView(text(if (long) "길 위 가게 쿠폰. 월~금 상위 3회 합으로 월요일에" else "길 위 가게 쿠폰", 17f, tone.t2))
        } else addView(text("고스트 리그. 같은 길 3명이 모이면 상금이 열립니다", 17f, tone.t2))
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
                // 「15.0 km로 환산」 → M14 「15 km로 환산」(문구는 Score가 만들어 화면에서만 맞춘다)
                addView(lineRow(l.label.replace(".0 km", " km"), (if (l.plus) "+" else "") + Score.fmt(l.value), if (l.plus) tone.t1 else tone.t2, tone.hair), lp(MATCH))
            }
            val formula = "100 − ${Score.fmt(r.converted)}${if (r.bonusSum > 0) " + ${Score.fmt(r.bonusSum)}" else ""} = ${Score.fmt(r.score)}"
            addView(lineRow("리그 결과", formula, tone.ac, tone.t3, 24f), lp(MATCH))
        }
        val right = column(10f) {
            addView(row { pad(0f, 0f, 0f, 4f); lineBox(20f, 0f); minimumHeight += ki(4f)
                addView(text("내역", 20f, tone.t1), lp(0, WRAP, 1f)); addView(text("접기 ⌃", 17f, tone.t2)); setOnClickListener { Engine.detail = false; render() } })
            addView(rows, lp(MATCH))
        }
        return sheet {
            addView(twoCols(left, right, 0f), lp(MATCH))
            addView(hairline(), lp(MATCH, 1))
            addView(prizeLine(b?.league != false, long = false), lp(MATCH).apply { topMargin = -ki(12f) })
        }
    }

    private fun lineRow(label: String, value: String, valueColor: Int, lineColor: Int, valuePx: Float = 22f) = row {
        pad(0f, 9f, 0f, 9f); lineBox(valuePx, 9f)
        background = object : android.graphics.drawable.ColorDrawable() { override fun draw(c: android.graphics.Canvas) { c.drawRect(0f, 0f, bounds.width().toFloat(), 1f, android.graphics.Paint().apply { color = lineColor }) } }
        addView(text(label, 19f, tone.t1), lp(0, WRAP, 1f)); addView(num(value, valuePx, valueColor, Typeface.DEFAULT))
    }

    // ---------- 장면 4: 다음 날 카드 ----------
    private fun prizeSheet(): View {
        val p = Engine.prize ?: return sheet {}
        val left = column(20f) {
            addView(eyebrow(p.title))
            addView(h3("3위, 상금이\n도착했습니다"))
            addView(body("월~금 상위 3회 합 ${Score.fmt(p.sum)}"), lp().apply { topMargin = -ki(8f) })
        }
        // 왼쪽 강조 띠는 배경 겹으로(띠를 MATCH 높이 뷰로 두면 쿠폰이 시트 전체 높이로 늘어나 위쪽 자격 확인과 버튼을 밀어냄)
        val coupon = column(6f) {
            background = android.graphics.drawable.LayerDrawable(arrayOf(rounded(tone.g2, k(16f)), android.graphics.drawable.ColorDrawable(tone.ac))).apply {
                setLayerWidth(1, ki(6f)); setLayerGravity(1, Gravity.START or Gravity.FILL_VERTICAL)
            }
            clipToOutline = true
            pad(24f, 24f, 24f, 24f)
            addView(text(p.shop, 26f, tone.t1, MEDIUM)); addView(text(p.item, 20f, tone.t1))
            addView(text(p.meta, 17f, tone.t2), lp().apply { topMargin = ki(6f) })
        }
        val added = Engine.waypointAddedMin
        val low = row(64f) {
            addView(body(if (added != null) "경유지 추가, 도착 +${added}분" else "들르면 내비 경유지에 바로 들어갑니다."), lp(0, WRAP, 1f))
            addView(row(16f) {
                addView(button("다음에", false, tone) { Engine.later() }, lp(0, ki(72f), 1f))
                addView(button("들러요", true, tone) { Engine.stopBy() }, lp(0, ki(72f), 1f))
            }, lp(0, WRAP, 1f))
        }
        return sheet { addView(twoCols(left, coupon), lp(MATCH)); addView(low, lp(MATCH)) }
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
