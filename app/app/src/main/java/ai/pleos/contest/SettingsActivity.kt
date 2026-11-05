package ai.pleos.contest

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView

// 한 번 정해 두는 설정. 카드 둘째 줄은 여기 값을 그대로 쓴다.
class SettingsActivity : Activity() {
    private lateinit var store: Store

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(this)
        render()
    }

    private fun render() {
        val s = store.settings
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpi(64f), dpi(48f), dpi(64f), dpi(48f))
        }
        col.addView(label("설정", 32f, Pal.t92, bold = true))
        fun onOff(v: Boolean) = if (v) 0 else 1
        col.row("커피 카드", listOf("켬", "끔"), onOff(s.coffeeOn)) { store.settings = s.copy(coffeeOn = it == 0) }
        col.row("커피 기준", Criterion.values().map { it.label }, s.criterion.ordinal) { store.settings = s.copy(criterion = Criterion.values()[it]) }
        col.row("하루 2번까지", listOf("켬", "끔"), onOff(s.dailyCap)) { store.settings = s.copy(dailyCap = it == 0) }
        col.row("충전 카드도 2번에 셈", listOf("켬", "끔"), onOff(s.chargeCounts)) { store.settings = s.copy(chargeCounts = it == 0) }
        val socs = listOf(20, 25, 30)
        col.row("배터리 기준", socs.map { "$it%" }, socs.indexOf(s.socLimit)) { store.settings = s.copy(socLimit = socs[it]) }
        val margins = listOf(10, 20, 30)
        col.row("여유 거리", margins.map { "${it}km" }, margins.indexOf(s.marginKm)) { store.settings = s.copy(marginKm = margins[it]) }
        if (!MainActivity.RANGE_OK) col.addView(label("주행 가능 거리 신호를 받을 수 있는 차에서 적용돼요", 16f, Pal.t40))
        col.addView(View(this), LinearLayout.LayoutParams(1, dpi(48f)))
        col.row("데모 모드", listOf("켬", "끔"), onOff(s.demo), small = true) {
            // 켤 때마다 오늘 기록을 비워 장면을 처음부터 다시 볼 수 있게 한다
            if (it == 0) store.memo = Memo()
            store.settings = s.copy(demo = it == 0)
        }
        setContentView(ScrollView(this).apply { setBackgroundColor(Pal.bg); addView(col) })
    }

    private fun LinearLayout.row(name: String, opts: List<String>, sel: Int, small: Boolean = false, pick: (Int) -> Unit) {
        val r = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, dpi(if (small) 8f else 16f), 0, 0) }
        r.addView(label(name, if (small) 16f else 20f, if (small) Pal.t40 else Pal.t92),
            LinearLayout.LayoutParams(dpi(320f), LinearLayout.LayoutParams.WRAP_CONTENT))
        opts.forEachIndexed { i, o ->
            r.addView(pill(o, on = i == sel, small = small).apply { setOnClickListener { pick(i); render() } },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginEnd = dpi(8f) })
        }
        addView(r)
    }
}
