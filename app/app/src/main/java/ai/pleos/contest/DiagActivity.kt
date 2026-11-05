package ai.pleos.contest

import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.RouteOption
import ai.pleos.playground.navi.data.CurrentLocationInfo
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.data.RouteStartInfo
import ai.pleos.playground.navi.data.RouteStateInfo
import ai.pleos.playground.navi.data.TripInfo
import ai.pleos.playground.navi.data.TripRequest
import ai.pleos.playground.navi.data.WaypointChangedInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.WheelArea
import ai.pleos.playground.vehicle.api.model.area.WindowArea
import ai.pleos.playground.vehicle.api.model.telltale.Telltales
import ai.pleos.playground.vehicle.constant.area.WheelValue
import ai.pleos.playground.vehicle.constant.area.WindowValue
import ai.pleos.playground.vehicle.constant.control.WiperState
import ai.pleos.playground.vehicle.constant.telltale.TelltaleState
import ai.pleos.playground.vehicle.listener.EvBatteryStateOfChargeListener
import ai.pleos.playground.vehicle.listener.LowWasherFluidWaringListener
import ai.pleos.playground.vehicle.listener.RangeRemainingListener
import ai.pleos.playground.vehicle.listener.TelltaleInfoListener
import ai.pleos.playground.vehicle.listener.TirePressureListener
import ai.pleos.playground.vehicle.listener.WiperStateListener
import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

// 진단 화면: 신호 값을 글자 그대로 보여 주고 logcat(태그 SIGCHK)에도 남긴다.
class DiagActivity : Activity() {

    private val values = linkedMapOf(
        "EvBattery SOC" to "-", "주행 가능 거리" to "-", "연비" to "-", "도착 시 배터리" to "-",
        "와이퍼" to "-", "워셔액 부족" to "-", "타이어 공기압(앞 왼쪽)" to "-", "켜진 경고등" to "-",
        "목적지" to "-", "마지막 오류" to "-"
    )
    private lateinit var text: TextView
    private val main = Handler(Looper.getMainLooper())
    private lateinit var vehicle: Vehicle
    private lateinit var navi: NaviHelper
    private val front = WindowArea(WindowValue.FRONT_WINDSHIELD)
    private val wheel = WheelArea(WheelValue.LEFT_FRONT)

    private fun put(key: String, value: Any?) {
        main.post {
            values[key] = value.toString()
            Log.i("SIGCHK", "$key = $value")
            text.text = values.entries.joinToString("\n") { "${it.key}: ${it.value}" }
        }
    }

    private val socListener = object : EvBatteryStateOfChargeListener {
        override fun onEvBatteryStateOfChargeUpdated(value: Int?) = put("EvBattery SOC", "$value %")
        override fun onFailed(e: Exception) = put("EvBattery SOC", "오류 $e")
    }
    private val rangeListener = object : RangeRemainingListener {
        override fun onRangeRemainingChanged(value: Float?) = put("주행 가능 거리", "$value (콜백)")
        override fun onFailed(e: Exception) = put("주행 가능 거리", "오류 $e")
    }
    private val wiperListener = object : WiperStateListener {
        override fun onWiperStateUpdated(value: WiperState?) = put("와이퍼", "$value (콜백)")
        override fun onFailed(e: Exception) = put("와이퍼", "오류 $e")
    }
    private val washerListener = object : LowWasherFluidWaringListener {
        override fun onLowWasherFluidWaringUpdated(value: Boolean?) = put("워셔액 부족", "$value (콜백)")
        override fun onFailed(e: Exception) = put("워셔액 부족", "오류 $e")
    }
    private val tireListener = object : TirePressureListener {
        override fun onTirePressureUpdated(value: Float?) = put("타이어 공기압(앞 왼쪽)", "$value (콜백)")
        override fun onFailed(e: Exception) = put("타이어 공기압(앞 왼쪽)", "오류 $e")
    }
    private val telltaleListener = object : TelltaleInfoListener {
        override fun onTelltaleInfoUpdated(value: Telltales?) = put("켜진 경고등", value?.let(::onTelltales) + " (콜백)")
        override fun onFailed(e: Exception) = put("켜진 경고등", "오류 $e")
    }
    private fun onTelltales(t: Telltales) =
        t.telltales.filter { it.state != TelltaleState.OFF }.joinToString { "${it.type}=${it.state}" }.ifEmpty { "없음" }

    private val naviListener = object : NaviHelperEventListener {
        override fun onCurrentLocationInfo(info: CurrentLocationInfo) = Unit
        override fun onDestinationInfo(info: DestinationInfo) =
            put("목적지", "${info.name} / 남은 ${info.remainDistance} m, ${info.remainTime} 초")
        override fun onRouteStateInfo(info: RouteStateInfo) =
            put("도착 시 배터리", "RouteState ${info.remainDestinationChargingInt}")
        override fun onRouteStarted(info: RouteStartInfo) =
            put("도착 시 배터리", "RouteStart ${info.remainDestinationChargingValue}")
        override fun onTripInfo(info: TripInfo) =
            put("도착 시 배터리", "Trip estimatedSocAtDest ${info.estimatedSocAtDest}, ${info.distance} m")
        override fun onWaypointChanged(info: WaypointChangedInfo) =
            put("도착 시 배터리", "경유지 뒤 ${info.routeStartInfo.remainDestinationChargingValue}")
        override fun onRoutingCompleted() { navi.getDestinationInfo(); navi.getRouteStateInfo() }
        override fun onError(code: NaviErrorCode) = put("마지막 오류", "Navi $code")
    }

    private val poll = object : Runnable {
        override fun run() {
            runCatching { readVehicle(); navi.getDestinationInfo(); navi.getRouteStateInfo() }
                .onFailure { put("마지막 오류", it) }
            main.postDelayed(this, 3000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        text = TextView(this).apply { textSize = 20f }
        val route = Button(this).apply { text = "목적지 설정: 서울역"; setOnClickListener { setDestination() } }
        val trip = Button(this).apply { text = "도착 시 배터리 요청(requestTrip)"; setOnClickListener { requestTrip() } }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
            addView(text); addView(route); addView(trip)
        })

        vehicle = Vehicle(applicationContext)
        runCatching { vehicle.initialize() }.onFailure { put("마지막 오류", "Vehicle $it") }
        listOf<() -> Unit>(
            { vehicle.getEvBattery().registerEvBatteryStateOfCharge(socListener) },
            { vehicle.getOdometer().registerRangeRemainingListener(rangeListener) },
            { vehicle.getWiper().registerWiperState(front, wiperListener) },
            { vehicle.getSafety().registerLowWasherFluidWaringState(washerListener) },
            { vehicle.getTire().registerTirePressure(wheel, tireListener) },
            { vehicle.getSafety().registerTelltales(telltaleListener) },
        ).forEach { runCatching(it).onFailure { e -> put("마지막 오류", "등록 $e") } }

        navi = NaviHelper(applicationContext)
        navi.addListener(naviListener)
        navi.initialize({ main.post(poll) }, { put("마지막 오류", "Navi 연결 실패 $it") })
        main.postDelayed({ if (!main.hasCallbacks(poll)) main.post(poll) }, 5000)
    }

    private fun readVehicle() {
        vehicle.getEvBattery().getEvBatteryStateOfCharge({ put("EvBattery SOC", "$it %") }, { put("EvBattery SOC", "오류 $it") })
        vehicle.getOdometer().getRangeRemainingDistance({ put("주행 가능 거리", "$it") }, { put("주행 가능 거리", "오류 $it") })
        vehicle.getFuel().getCurrentFuelEconomy({ put("연비", "current $it") }, { put("연비", "오류 $it") })
        vehicle.getWiper().getCurrentWiperState(front, { put("와이퍼", "$it") }, { put("와이퍼", "오류 $it") })
        vehicle.getSafety().hasLowWasherFluidWarning({ put("워셔액 부족", "$it") }, { put("워셔액 부족", "오류 $it") })
        vehicle.getTire().getTirePressure(wheel, { put("타이어 공기압(앞 왼쪽)", "$it") }, { put("타이어 공기압(앞 왼쪽)", "오류 $it") })
        vehicle.getSafety().getTelltalesInfo({ put("켜진 경고등", onTelltales(it)) }, { put("켜진 경고등", "오류 $it") })
    }

    private fun setDestination() = runCatching {
        navi.requestRoute(RouteInfo(37.5547, 126.9707, null, null, "서울역", "", "", "", RouteOption.RECOMMENDED, "", false))
    }.onFailure { put("마지막 오류", "requestRoute $it") }

    private fun requestTrip() = runCatching {
        navi.requestTrip(TripRequest(37.5547, 126.9707, "", "서울역", ""))
    }.onFailure { put("마지막 오류", "requestTrip $it") }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        runCatching {
            vehicle.getEvBattery().unregisterEvBatteryStateOfCharge(socListener)
            vehicle.getOdometer().unregisterRangeRemainingListener(rangeListener)
            vehicle.getWiper().unregisterWiperState(front, wiperListener)
            vehicle.getSafety().unregisterLowWasherFluidWaringState(washerListener)
            vehicle.getTire().unregisterTirePressure(wheel, tireListener)
            vehicle.getSafety().unregisterTelltales(telltaleListener)
            vehicle.release()
        }
        runCatching { navi.removeListener(naviListener); navi.release() }
        super.onDestroy()
    }
}
