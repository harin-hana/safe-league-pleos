package ai.pleos.contest

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

// M14 디자인 토큰. 세 톤(주간, 야간, 비 오는 밤). 시트(g1~g3, t1~t3)는 톤과 무관하게 어둡다.
class Tone(val day: Boolean, val rain: Boolean) {
    val g0 = c(if (day) "#e9ecef" else if (rain) "#0f1114" else "#121214")
    val nav = c(if (day) "#e2e6ea" else if (rain) "#121519" else "#151518")
    val g1 = c(if (rain) "#171a1e" else "#1a1a1d")
    val g2 = c(if (rain) "#20242a" else "#242428")
    val g3 = c(if (rain) "#2a2f36" else "#2f2f34")
    val t1 = c("#ece9e3"); val t2 = c(if (day) "#b3afa7" else "#a8a49c"); val t3 = c("#6d6a64")
    val ac = c("#d8c08f"); val acInk = c("#1b1708"); val acDim = Color.argb(36, 216, 192, 143)
    val amb = c("#f2b24c")
    val hair = Color.argb(20, 255, 255, 255)
    val navT1 = if (day) c("#15171a") else t1
    val navT2 = if (day) c("#6b7079") else t2
    val navHair = if (day) Color.argb(31, 21, 23, 26) else hair
    val glow = if (day) Color.argb(18, 21, 23, 26) else Color.argb(20, 236, 233, 227)
    val barChip = if (day) Color.argb(20, 21, 23, 26) else g2
    val barPill = if (day) Color.argb(15, 21, 23, 26) else g2
    // 판정 보기 「데모 모드」 꼬리표. 밝은 바탕에서는 금색 글자가 안 읽혀 같은 금색을 짙게 쓴다(목업 값)
    val tagInk = if (day) c("#7a6230") else ac
    val tagBg = if (day) Color.argb(87, 216, 192, 143) else acDim
    companion object { fun c(s: String) = Color.parseColor(s) }
}

// M14는 1294px 안쪽을 zoom 1.3으로 키운 것. 아래 px 값은 M14 CSS 그대로이고 K()가 1.3배 dp로 바꾼다.
const val ZOOM = 1.3f
fun Context.k(px: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, px * ZOOM, resources.displayMetrics)
fun Context.ki(px: Float) = k(px).toInt()
// 굵기 표시용 열쇠. 실제 글꼴은 아래 font()가 M14 글꼴(res/font, OFL, 허브 결정 11/06)로 바꾼다
val LIGHT: Typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
val MEDIUM: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)

// M14 --f-ui IBM Plex Sans KR 400 500, --f-num Barlow Semi Condensed 300 400. 한 번 읽어 둔다
private var fontCache: Array<Typeface>? = null
private fun Context.fonts() = fontCache ?: arrayOf(
    resources.getFont(R.font.ibm_plex_sans_kr_regular), resources.getFont(R.font.ibm_plex_sans_kr_medium),
    resources.getFont(R.font.barlow_semi_condensed_light), resources.getFont(R.font.barlow_semi_condensed_regular),
).also { fontCache = it }
fun Context.font(tf: Typeface, numeric: Boolean): Typeface = fonts().let { f -> if (numeric) (if (tf === LIGHT) f[2] else f[3]) else (if (tf === MEDIUM) f[1] else f[0]) }

fun Context.text(s: CharSequence, px: Float, color: Int, tf: Typeface = Typeface.DEFAULT, lines: Int = 0, numeric: Boolean = false) = TextView(this).apply {
    text = s; setTextSize(TypedValue.COMPLEX_UNIT_DIP, px * ZOOM); setTextColor(color); typeface = font(tf, numeric)
    letterSpacing = -0.01f; includeFontPadding = false
    if (lines > 0) maxLines = lines
    // M14 body line-height 1.45: 한 줄 글은 그 높이 가운데에(안드로이드 기본 1.17배라 줄이 쌓이면 시트가 짧아짐)
    minimumHeight = ki(px * 1.45f); gravity = Gravity.CENTER_VERTICAL or Gravity.START
}

// 큰 숫자는 M14에서 line-height 1(.ck b, .tbt b) 또는 .86(.hero)이라 1.45 최소 높이를 뺀다
fun Context.num(s: CharSequence, px: Float, color: Int, tf: Typeface = LIGHT) = text(s, px, color, tf, numeric = true).apply { letterSpacing = -0.02f; minimumHeight = 0 }

fun rounded(color: Int, radius: Float, stroke: Int = 0, strokeColor: Int = 0) = GradientDrawable().apply {
    cornerRadius = radius; setColor(color); if (stroke > 0) setStroke(stroke, strokeColor)
}

fun Context.column(gapPx: Float = 0f, init: LinearLayout.() -> Unit = {}) = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    if (gapPx > 0) { showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE; dividerDrawable = GapDrawable(ki(gapPx)) }
    init()
}

fun Context.row(gapPx: Float = 0f, init: LinearLayout.() -> Unit = {}) = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
    if (gapPx > 0) { showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE; dividerDrawable = GapDrawable(ki(gapPx)) }
    init()
}

class GapDrawable(private val size: Int) : android.graphics.drawable.ColorDrawable(Color.TRANSPARENT) {
    override fun getIntrinsicWidth() = size
    override fun getIntrinsicHeight() = size
}

fun lp(w: Int = ViewGroup.LayoutParams.WRAP_CONTENT, h: Int = ViewGroup.LayoutParams.WRAP_CONTENT, weight: Float = 0f) = LinearLayout.LayoutParams(w, h, weight)
const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

fun View.pad(l: Float, t: Float, r: Float, b: Float) = setPadding(context.ki(l), context.ki(t), context.ki(r), context.ki(b))

// M14 .btn: 높이 72, 모서리 16, 글자 22 medium → M20 권장 25(32 dp)
fun Context.button(label: String, primary: Boolean, tone: Tone, onClick: () -> Unit) = text(label, 25f, if (primary) tone.acInk else tone.t1, MEDIUM).apply {
    gravity = Gravity.CENTER; background = rounded(if (primary) tone.ac else tone.g2, k(17f))   // M19b 모서리 22 dp
    setOnClickListener { onClick() }
}

// M14 .pill(상단 바) → M20 권장 글자 20(26 dp), 높이 36
fun Context.pill(label: String, tone: Tone, accent: Boolean = false) = text(label, 20f, if (accent) tone.ac else tone.navT2).apply {
    gravity = Gravity.CENTER; background = rounded(if (accent) tone.acDim else tone.barPill, k(999f)); pad(14f, 0f, 14f, 0f); minHeight = ki(36f)
}

// 체크 동그라미(M14 .ck span i)
// 기본은 상단 바 색(바탕 navT1, 체크 g0). 금색 체크는 (ac, acInk)
class CheckDot(ctx: Context, private val tone: Tone, private val fill: Int = tone.navT1, private val ink: Int = tone.g0) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(c: Canvas) {
        val r = width / 2f
        p.style = Paint.Style.FILL; p.color = fill; c.drawCircle(r, r, r, p)
        p.style = Paint.Style.STROKE; p.color = ink; p.strokeWidth = width * 0.17f; p.strokeCap = Paint.Cap.ROUND; p.strokeJoin = Paint.Join.ROUND
        val path = android.graphics.Path().apply { moveTo(width * .29f, height * .53f); lineTo(width * .45f, height * .69f); lineTo(width * .72f, height * .36f) }
        c.drawPath(path, p)
    }
}

// 판정 보기 조건 줄 끝 표시: 선으로만 그린 체크 또는 ✕(목업 29×21, 23×23)
class CondMark(ctx: Context, private val color: Int, private val ok: Boolean) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        p.color = color; p.strokeWidth = w * .09f
        c.drawPath(android.graphics.Path().apply {
            if (ok) { moveTo(w * .05f, h * .52f); lineTo(w * .36f, h * .83f); lineTo(w * .95f, h * .17f) }
            else { moveTo(w * .14f, h * .14f); lineTo(w * .86f, h * .86f); moveTo(w * .86f, h * .14f); lineTo(w * .14f, h * .86f) }
        }, p)
    }
}

// 도장 칸(M19b .stamps i): 72 dp 원. 찍힌 칸은 금색 바탕에 그림(0 벨트, 1 깜빡이, 2 전조등. SVG 36 기준을 칸의 절반 크기로), 빈 칸은 회색 테두리만
class Stamp(ctx: Context, private val tone: Tone, private val kind: Int, private val on: Boolean) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(c: Canvas) {
        val r = width / 2f
        if (!on) { p.style = Paint.Style.STROKE; p.strokeWidth = context.k(1.5f); p.color = tone.t3; c.drawCircle(r, r, r - p.strokeWidth / 2, p); return }
        p.style = Paint.Style.FILL; p.color = tone.ac; c.drawCircle(r, r, r, p)
        c.save(); c.translate(width / 4f, height / 4f); c.scale(width / 72f, height / 72f)
        p.style = Paint.Style.STROKE; p.color = tone.acInk; p.strokeWidth = 2.4f; p.strokeCap = Paint.Cap.ROUND; p.strokeJoin = Paint.Join.ROUND
        val path = android.graphics.Path()
        when (kind) {
            0 -> { c.drawCircle(18f, 9.5f, 3.5f, p); path.moveTo(10f, 29f); path.lineTo(10f, 24f); path.cubicTo(10f, 19.5f, 13.5f, 17f, 18f, 17f); path.cubicTo(22.5f, 17f, 26f, 19.5f, 26f, 24f); path.lineTo(26f, 29f); path.moveTo(13.5f, 18f); path.lineTo(25f, 28f) }
            1 -> { path.moveTo(8f, 18f); path.lineTo(16f, 10f); path.lineTo(16f, 14.5f); path.lineTo(28f, 14.5f); path.lineTo(28f, 21.5f); path.lineTo(16f, 21.5f); path.lineTo(16f, 26f); path.close() }
            else -> { path.moveTo(21f, 9f); path.cubicTo(29f, 9f, 29f, 27f, 21f, 27f); path.close(); for (y in listOf(12f, 18f, 24f)) { path.moveTo(7f, y); path.lineTo(16f, y) } }
        }
        c.drawPath(path, p); c.restore()
    }
}

// 좌석 칸(M14 .seat). on이면 강조 테두리 + 벨트 사선
class Seat(ctx: Context, private val tone: Tone, private val on: Boolean) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(c: Canvas) {
        val rect = android.graphics.RectF(0f, 0f, width.toFloat(), height.toFloat())
        p.style = Paint.Style.FILL; p.color = if (on) tone.acDim else tone.g3
        c.drawRoundRect(rect, context.k(14f), context.k(14f), p)
        if (on) {
            p.style = Paint.Style.STROKE; p.strokeWidth = context.k(1.5f); p.color = tone.ac
            c.drawRoundRect(rect, context.k(14f), context.k(14f), p)
            p.strokeWidth = context.k(3f); p.strokeCap = Paint.Cap.ROUND
            c.save(); c.clipRect(rect)
            val x = context.k(18f); val y = context.k(10f); val len = context.k(54f)
            c.drawLine(x, y, x + len * Math.cos(Math.toRadians(40.0)).toFloat(), y + len * Math.sin(Math.toRadians(40.0)).toFloat(), p)
            c.restore()
        }
    }
}

// 경로 띠(M14 .track): 선 하나, 출발과 도착 점, 콤보 고비 눈금, 감점 자리 점
class Track(ctx: Context, private val tone: Tone, private val marks: List<Pair<String, Float>>, private val dots: List<Float>) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(c: Canvas) {
        val y = context.k(56f) + 1f
        p.style = Paint.Style.FILL; p.color = tone.t3; c.drawRect(0f, y - 1f, width.toFloat(), y + 1f, p)
        p.textSize = context.k(20f); p.textAlign = Paint.Align.CENTER; p.typeface = context.font(Typeface.DEFAULT, numeric = false)
        marks.forEach { (label, f) ->
            p.color = tone.t2; c.drawRect(width * f - 1f, context.k(44f), width * f + 1f, context.k(70f), p)
            c.drawText(label, width * f, context.k(28f), p)
        }
        dots.forEach { f -> p.color = tone.t2; c.drawCircle(width * f, y, context.k(7f), p) }
        p.color = tone.g0; c.drawCircle(context.k(8f), y, context.k(8f), p)
        p.style = Paint.Style.STROKE; p.strokeWidth = context.k(2f); p.color = tone.t3; c.drawCircle(context.k(8f), y, context.k(7f), p)
        p.style = Paint.Style.FILL; p.color = tone.glow; c.drawCircle(width - context.k(8f), y, context.k(18f), p)
        p.color = tone.navT1; c.drawCircle(width - context.k(8f), y, context.k(8f), p)
    }
}
