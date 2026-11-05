package ai.pleos.contest

import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.RouteOption
import ai.pleos.playground.navi.constants.WaypointIndex
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.data.RequestWaypointInfo
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.data.RouteWithWaypointsRequest
import ai.pleos.playground.navi.data.WaypointChangedInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.listener.EvBatteryStateOfChargeListener
import ai.pleos.playground.vehicle.listener.RangeRemainingListener
import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.SpannableString
import android.text.Spanned
import android.text.style.StyleSpan
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.IsoFields

// 호스트 다크 위 카드 한 장. 조건이 맞을 때만 뜨고, 답은 한 번.
class MainActivity : Activity() {

    companion object {
        // 주행 가능 거리(Odometer.getRangeRemainingDistance)가 에뮬레이터에서 바뀌는 게 확인되면 true
        const val RANGE_OK = true
        const val DEMO_SOC = 18
        const val AUTO_CLOSE_MS = 20_000L
        const val TAG = "PCOM"
    }

    private lateinit var store: Store
    private lateinit var vehicle: Vehicle
    private lateinit var navi: NaviHelper
    private val main = Handler(Looper.getMainLooper())

    private var soc: Int? = null
    private var rangeKm: Double? = null
    private var demoSoc: Int? = null
    private var dest: DestinationInfo? = null
    private var card: Card? = null
    private var resumed = false

    private lateinit var status: TextView
    private lateinit var panel: LinearLayout

    private val socListener = object : EvBatteryStateOfChargeListener {
        override fun onEvBatteryStateOfChargeUpdated(value: Int?) = main.post { soc = value; evaluate() }.let { }
        override fun onFailed(e: Exception) { Log.w(TAG, "SOC $e") }
    }

    private val rangeListener = object : RangeRemainingListener {
        override fun onRangeRemainingChanged(value: Float?) = main.post { rangeKm = value?.div(1000.0); evaluate() }.let { }
        override fun onFailed(e: Exception) { Log.w(TAG, "주행 가능 거리 $e") }
    }

    private val naviListener = object : NaviHelperEventListener {
        override fun onDestinationInfo(info: DestinationInfo) =
            main.post { dest = info.takeIf { it.name.isNotBlank() && it.remainDistance > 0 }; evaluate() }.let { }
        override fun onRouteCancelled() = main.post { dest = null; refreshStatus() }.let { }
        override fun onRouteEnded() = main.post { dest = null; refreshStatus() }.let { }
        override fun onRoutingCompleted() = navi.getDestinationInfo()
        override fun onDestinationChanged() = navi.getDestinationInfo()
        override fun onWaypointChanged(info: WaypointChangedInfo) {
            Log.i(TAG, "경유지 ${info.cmdType} ${info.routeStartInfo.name} ${info.routeStartInfo.duration}s")
        }
        override fun onError(code: NaviErrorCode) { Log.w(TAG, "Navi $code") }
    }

    private val poll = object : Runnable {
        override fun run() {
            runCatching { navi.getDestinationInfo() }
            runCatching { vehicle.getEvBattery().getEvBatteryStateOfCharge({ v -> main.post { soc = v; evaluate() } }, {}) }
            main.postDelayed(this, 3000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(this)
        buildScreen()

        vehicle = Vehicle(applicationContext)
        runCatching {
            vehicle.initialize()
            vehicle.getEvBattery().registerEvBatteryStateOfCharge(socListener)
            if (RANGE_OK) vehicle.getOdometer().registerRangeRemainingListener(rangeListener)
        }.onFailure { Log.w(TAG, "Vehicle $it") }

        navi = NaviHelper(applicationContext)
        navi.addListener(naviListener)
        navi.initialize({ main.post(poll) }, { Log.w(TAG, "Navi 연결 실패 $it") })
        main.postDelayed({ if (!main.hasCallbacks(poll)) main.post(poll) }, 5000)
    }

    private fun buildScreen() {
        val root = FrameLayout(this).apply { setBackgroundColor(Pal.bg) }
        status = label("", 16f, Pal.t24)
        root.addView(status, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply { setMargins(dpi(32f), dpi(24f), 0, 0) })
        val gear = label("설정", 16f, Pal.t40).apply {
            setPadding(dpi(24f), dpi(16f), dpi(24f), dpi(16f))
            setOnClickListener { startActivity(Intent(this@MainActivity, SettingsActivity::class.java)) }
        }
        root.addView(gear, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply { setMargins(0, dpi(8f), dpi(8f), 0) })

        // 안 A 틀: 반투명 패널, 큰 모서리, 테두리 없이 위쪽 코퍼 선 1px
        panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { cornerRadius = dp(24f); setColor(Pal.surf) }
            clipToOutline = true
            visibility = View.GONE
        }
        root.addView(panel, FrameLayout.LayoutParams(dpi(704f), -2, Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, dpi(24f), dpi(96f)) })
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        // 데모를 끄거나 새로 켜면(기록이 비워짐) 넣어 둔 배터리 값을 뺀다
        if (!store.settings.demo || store.memo.day.isEmpty()) demoSoc = null
        evaluate()
    }

    override fun onPause() { resumed = false; super.onPause() }

    private fun refreshStatus() {
        val s = store.settings
        val parts = mutableListOf<String>()
        (demoSoc ?: soc)?.let { parts += "배터리 $it%" }
        rangeKm?.let { parts += "주행 가능 ${it.toInt()}km" }
        dest?.let { parts += "목적지 ${it.name} ${it.remainDistance / 1000}km" }
        if (s.demo) parts += "데모"
        status.text = parts.joinToString("   ")
    }

    private fun evaluate() {
        refreshStatus()
        if (card != null) return
        val s = store.settings
        val now = LocalDate.now()
        var m = Rules.rollover(store.memo, now.toString(), "${now.get(IsoFields.WEEK_BASED_YEAR)}-W${now.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)}")
        val level = demoSoc ?: soc
        val range = rangeKm.takeIf { RANGE_OK }
        val needKm = dest?.remainDistance?.div(1000.0)
        val stage = Rules.chargeStage(s, level, range, needKm)
        val byRange = (level == null || level >= s.socLimit) && Rules.rangeShort(s, range, needKm)
        m = Rules.afterStage(m, stage)
        store.memo = m
        // 한 번에 한 장. 충전이 커피보다 먼저.
        when {
            Rules.chargeDue(s, m, stage) -> show(Rules.chargeCard(s, level, byRange), stage)
            Rules.coffeeDue(s, m, LocalTime.now().hour, dest != null) -> show(Rules.coffeeCard(s))
        }
    }

    private fun show(c: Card, stage: Int = 0) {
        card = c
        store.memo = Rules.shownCard(store.settings, store.memo, c, stage)
        Log.i(TAG, "카드 ${c.kind} ${c.fact}")
        fillPanel(c)
        if (!resumed) runCatching {
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        }.onFailure { Log.w(TAG, "앞으로 못 가져옴 $it") }
        // 답하지 않으면 그냥 사라진다([다음에]로 세지 않음)
        main.postDelayed(autoClose, AUTO_CLOSE_MS)
    }

    private val autoClose = Runnable { close() }

    private fun fillPanel(c: Card) {
        panel.removeAllViews()
        panel.addView(View(this).apply { setBackgroundColor(Pal.copper) }, LinearLayout.LayoutParams(-1, 1))
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dpi(40f), dpi(40f), dpi(40f), dpi(40f)) }

        val factRow = LinearLayout(this).apply { isBaselineAligned = true }
        val fact = SpannableString(c.fact).apply { setSpan(StyleSpan(Typeface.BOLD), 0, c.strong.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
        factRow.addView(label(fact, 32f, Pal.t92).apply { maxLines = 1 })
        factRow.addView(label(c.tag, 16f, Pal.t40).apply { maxLines = 1 },
            LinearLayout.LayoutParams(-2, -2).apply { marginStart = dpi(16f) })
        body.addView(factRow)

        body.addView(label(c.reason, 20f, Pal.t60).apply { maxLines = 1 },
            LinearLayout.LayoutParams(-2, -2).apply { topMargin = dpi(12f) })

        val act = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        act.addView(pill(c.yes).apply { setOnClickListener { yes(c) } })
        act.addView(label("다음에", 20f, Pal.t60).apply {
            setPadding(dpi(32f), dpi(16f), dpi(32f), dpi(16f))
            setOnClickListener { store.memo = Rules.later(store.memo, c); close() }
        })
        body.addView(act, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dpi(28f) })
        panel.addView(body)
        panel.visibility = View.VISIBLE
    }

    private fun yes(c: Card) {
        val p = c.place
        val wp = RequestWaypointInfo(p.lat, p.lng, p.name, WaypointIndex.FIRST, "", "", "", "")
        runCatching {
            if (dest != null) navi.addWaypoint(wp)
            else navi.requestRouteWithWaypoints(RouteWithWaypointsRequest(
                RouteInfo(Places.office.lat, Places.office.lng, null, null, Places.office.name, "", "", "", RouteOption.RECOMMENDED, "", false),
                listOf(wp)))
        }.onFailure { Log.w(TAG, "경유지 추가 실패 $it") }
        close()
    }

    private fun close() {
        main.removeCallbacks(autoClose)
        val closed = card
        card = null
        panel.visibility = View.GONE
        // 데모: 커피 장면이 끝나면 몇 초 뒤 배터리 18%를 넣어 충전 장면으로
        if (store.settings.demo && closed?.kind == Card.Kind.COFFEE && demoSoc == null)
            main.postDelayed({ demoSoc = DEMO_SOC; evaluate() }, 6000)
        else refreshStatus()
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        runCatching {
            vehicle.getEvBattery().unregisterEvBatteryStateOfCharge(socListener)
            vehicle.getOdometer().unregisterRangeRemainingListener(rangeListener)
            vehicle.release()
        }
        runCatching { navi.removeListener(naviListener); navi.release() }
        super.onDestroy()
    }
}
