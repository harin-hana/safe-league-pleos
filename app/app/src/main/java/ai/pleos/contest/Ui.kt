package ai.pleos.contest

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.widget.TextView

// 3벌 제네시스 실내(M08 .s3): 갈색 기운 먹 바탕, 가죽 베이지 글자, 코퍼는 선에만. 초록 · 민트 없음.
object Pal {
    val bg = Color.parseColor("#15120F")
    val surf = Color.argb(235, 33, 28, 23)      // rgba(33,28,23,.92)
    val copper = Color.parseColor("#C08A6C")
    val onPill = Color.parseColor("#E6D9C4")
    private fun t(a: Float) = Color.argb((a * 255).toInt(), 230, 217, 196)
    val t92 = t(.92f); val t60 = t(.6f); val t40 = t(.4f); val t24 = t(.24f)
}

fun Context.dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)
fun Context.dpi(v: Float) = dp(v).toInt()

// 글꼴 단계(M06): 32/600 사실, 20/400 근거, 16/400 꼬리표, 버튼 20/600
fun Context.label(text: CharSequence, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
    this.text = text
    setTextSize(TypedValue.COMPLEX_UNIT_DIP, size)
    setTextColor(color)
    letterSpacing = -0.02f
    if (bold) typeface = Typeface.DEFAULT_BOLD
}

fun Context.pill(text: String, on: Boolean = true, small: Boolean = false) =
    label(text, if (small) 16f else 20f, if (on) Pal.onPill else Pal.t40, bold = on).apply {
        gravity = Gravity.CENTER
        val h = if (small) 40f else 60f
        minHeight = dpi(h)
        setPadding(dpi(if (small) 20f else 28f), 0, dpi(if (small) 20f else 28f), 0)
        if (on) background = GradientDrawable().apply {
            cornerRadius = dp(h / 2); setStroke(dpi(1.5f), Pal.copper); setColor(Color.TRANSPARENT)
        }
    }
