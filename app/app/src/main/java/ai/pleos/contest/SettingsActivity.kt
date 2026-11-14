package ai.pleos.contest

import ai.pleos.contest.league.Engine
import android.app.Activity
import android.content.res.Configuration
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView

// 설정(M14 장면 5): 왼쪽 메뉴, 오른쪽 목록. 데모 모드 스위치와 핸들 부호도 여기.
class SettingsActivity : Activity() {
    private lateinit var tone: Tone

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Engine.start(this)
        render()
    }

    private fun render() {
        val night = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        tone = Tone(day = !night, rain = night && Engine.wiper)
        val p = Engine.prefs
        val root = column { setBackgroundColor(tone.g0) }
        root.addView(FrameLayout(this).apply {
            pad(32f, 0f, 32f, 0f)
            addView(text("세이프 리그", 18f, tone.navT2).apply { setOnClickListener { finish() } }, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER_VERTICAL or Gravity.START))
            addView(pill("설정", tone), FrameLayout.LayoutParams(WRAP, ki(36f), Gravity.CENTER_VERTICAL or Gravity.END))
        }, lp(MATCH, ki(52f)))
        val body = row { setBackgroundColor(tone.nav) }
        val menu = column { pad(0f, 24f, 0f, 0f) }
        listOf("리그", "소리와 빛", "내 차", "데모").forEachIndexed { i, m ->
            menu.addView(text(m, 25f, if (i == 0) tone.navT1 else tone.navT2).apply {
                pad(32f, 18f, 32f, 18f)
                if (i == 0) { background = rounded(tone.g0, k(16f)) }
            }, lp(MATCH).apply { if (i == 0) marginEnd = ki(16f) })
        }
        body.addView(menu, lp(ki(320f), MATCH))
        val list = column { background = rounded(tone.g1, k(24f)); pad(36f, 6f, 36f, 0f) }
        fun toggle(name: String, sub: String, on: Boolean, set: (Boolean) -> Unit) = list.addView(item(name, sub, Toggle(this, tone, on) { set(it); render() }), lp(MATCH))
        toggle("시동 때 자동 실행", "시동을 걸면 출발 안내 화면을 바로 보여 드려요", p.autoStart) { p.autoStart = it }
        toggle("출발 때 리그 자동 참가","출발 안내 화면 없이 바로 참가해요", p.autoJoin) { p.autoJoin = it }
        toggle("콤보 효과음", "콤보 단계에 오를 때마다 한 번 울려요", p.sound) { p.sound = it }
        toggle("음성 한마디", "「콤보 10」처럼 짧게 말해요", p.tts) { p.tts = it }
        toggle("실내 조명 반짝", "콤보 단계에 오를 때마다 한 번 반짝여요", p.ambient) { p.ambient = it }
        toggle("동승자 심판석", "동승석에 앉은 분 화면에만 콤보를 바로 보여 드려요", false) { }
        list.addView(item("차종", "차에 맞게 고르면 급핸들을 더 정확히 가려내요", text("기본값 ›", 20f, tone.t2)), lp(MATCH))
        list.addView(item("핸들 부호", "에뮬레이터는 콘솔과 반대라 뒤집어 읽음", choice(listOf("에뮬레이터" to -1f, "실차" to 1f), p.steeringSign) { p.steeringSign = it; render() }), lp(MATCH))
        list.addView(item("별명", "순위표에 보이는 이름이에요", edit(p.nickname) { p.nickname = it }), lp(MATCH))
        list.addView(item("리그 서버", "비워 두면 고스트 리그", edit(p.serverUrl, wide = true) { p.serverUrl = it; Engine.reload() }), lp(MATCH))
        toggle("데모 모드", "P단에 두면 도착으로 봐요. 단계 꼬리표를 길게 누르면 데모 입력", p.demo) { p.demo = it; Engine.resetForDemo() }
        toggle("심사용 판정 보기", "데모 모드로 주행하는 동안 받은 신호와 판정을 보여 줘요", p.judgeView) { p.judgeView = it }
        list.addView(text("Vehicle SDK 2.3.2, NaviHelper 3.0.5. AAOS 속도 ${if (Engine.aaosOk) "연결" else "미연결"}. ${Engine.lastError ?: ""}", 15f, tone.t3).apply { pad(0f, 14f, 0f, 20f) })
        body.addView(ScrollView(this).apply { addView(list) }, lp(0, WRAP, 1f).apply { setMargins(0, ki(16f), ki(24f), ki(24f)) })
        root.addView(body, lp(MATCH, 0, 1f))
        setContentView(root)
    }

    private fun fmt(v: Float) = if (v == v.toInt().toFloat()) v.toInt().toString() else v.toString()

    private fun item(name: String, sub: String, right: View) = row {
        pad(0f, 15f, 0f, 15f)
        background = object : android.graphics.drawable.ColorDrawable() { override fun draw(c: android.graphics.Canvas) { c.drawRect(0f, bounds.height() - 1f, bounds.width().toFloat(), bounds.height().toFloat(), android.graphics.Paint().apply { color = tone.hair }) } }
        addView(column(2f) { addView(text(name, 25f, tone.t1)); addView(text(sub, 20f, tone.t2)) }, lp(0, WRAP, 1f))
        addView(right)
    }

    private fun choice(opts: List<Pair<String, Float>>, cur: Float, pick: (Float) -> Unit) = row(8f) {
        opts.forEach { (name, v) ->
            addView(text(name, 20f, if (v == cur) tone.acInk else tone.t2).apply {
                background = rounded(if (v == cur) tone.ac else tone.g3, k(999f)); pad(14f, 6f, 14f, 6f); setOnClickListener { pick(v) }
            })
        }
    }

    private fun edit(value: String, wide: Boolean = false, set: (String) -> Unit) = EditText(this).apply {
        setText(value); setTextColor(tone.t1); setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 20f * ZOOM); setSingleLine()
        inputType = InputType.TYPE_CLASS_TEXT; background = rounded(tone.g3, k(10f)); pad(12f, 6f, 12f, 6f)
        minWidth = ki(if (wide) 420f else 200f)
        setOnFocusChangeListener { _, has -> if (!has) set(text.toString().trim()) }
    }
}

// M14 .tg 토글
class Toggle(ctx: android.content.Context, private val tone: Tone, private var on: Boolean, private val set: (Boolean) -> Unit) : View(ctx) {
    private val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    init { setOnClickListener { on = !on; set(on) } }
    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(context.ki(64f), context.ki(36f))
    override fun onDraw(c: android.graphics.Canvas) {
        p.color = if (on) tone.ac else tone.g3
        c.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), height / 2f, height / 2f, p)
        p.color = if (on) tone.acInk else tone.t2
        val r = context.k(14f); val cx = if (on) width - context.k(4f) - r else context.k(4f) + r
        c.drawCircle(cx, height / 2f, r, p)
    }
}
