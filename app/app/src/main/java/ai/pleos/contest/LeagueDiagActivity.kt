package ai.pleos.contest

import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.RouteOption
import ai.pleos.playground.navi.data.CameraAlert
import ai.pleos.playground.navi.data.CautionPoint
import ai.pleos.playground.navi.data.DestinationArrivedInfo
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.telltale.Telltales
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.area.TurnSignalValue
import ai.pleos.playground.vehicle.constant.control.CruiseControl
import ai.pleos.playground.vehicle.constant.control.LightState
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import ai.pleos.playground.vehicle.constant.telltale.TelltaleState
import ai.pleos.playground.vehicle.listener.BrakePedalStatusListener
import ai.pleos.playground.vehicle.listener.CruiseControlStateListener
import ai.pleos.playground.vehicle.listener.GearSelectionListener
import ai.pleos.playground.vehicle.listener.LightStateListener
import ai.pleos.playground.vehicle.listener.SeatBeltBuckledStateListener
import ai.pleos.playground.vehicle.listener.SeatBeltNotBuckledWarningListener
import ai.pleos.playground.vehicle.listener.SeatOccupancyListener
import ai.pleos.playground.vehicle.listener.SteeringWheelAngleListener
import ai.pleos.playground.vehicle.listener.TelltaleInfoListener
import ai.pleos.playground.vehicle.listener.TurnSignalStateListener
import android.app.Activity
import android.car.Car
import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

// 세이프 리그 신호 진단 (2026-11-05): 11개 신호 + P단 전면 표시 테스트. 값은 화면과 logcat 태그 LEAGUE에 남긴다.
// 열기: adb shell am start -n ai.pleos.contest.pleos_commerce/ai.pleos.contest.LeagueDiagActivity
class LeagueDiagActivity : Activity() {

    companion object { const val TAG = "LEAGUE" }

    private val values = linkedMapOf(
        "1 브레이크" to "-", "2 핸들 각도" to "-", "3 지시등" to "-", "4 속도(AAOS)" to "-", "5 크루즈" to "-",
        "6 벨트 운전석(착석/체결/경고)" to "-", "6 벨트 동승석(착석/체결/경고)" to "-", "7 경고등" to "-",
        "8 앰비언트" to "-", "9 NIGHT_MODE(AAOS)" to "-", "10 기어(Pleos)" to "-", "10 기어(AAOS)" to "-",
        "11 도착 콜백" to "-", "+ 제한속도/카메라/주의구간" to "-", "+ 하향등/미등" to "-", "목적지" to "-",
        "P단 전면 테스트" to "-", "마지막 오류" to "-"
    )
    private lateinit var text: TextView
    private val main = Handler(Looper.getMainLooper())
    private lateinit var vehicle: Vehicle
    private lateinit var navi: NaviHelper
    private var car: Car? = null
    private var props: CarPropertyManager? = null
    private val driver = SeatArea(SeatValue.ROW_1_LEFT)
    private val passenger = SeatArea(SeatValue.ROW_1_RIGHT)
    private val belt = mutableMapOf("운전석" to arrayOf("-", "-", "-"), "동승석" to arrayOf("-", "-", "-"))

    private fun put(key: String, value: Any?) {
        main.post {
            values[key] = value.toString()
            Log.i(TAG, "$key = $value")
            text.text = values.entries.joinToString("\n") { "${it.key}: ${it.value}" }
        }
    }

    private fun putBelt(seat: String, idx: Int, v: Any?) {
        belt[seat]!![idx] = v.toString()
        put("6 벨트 $seat(착석/체결/경고)", belt[seat]!!.joinToString(" / "))
    }

    private fun seatListeners(seat: String, area: SeatArea) = listOf<() -> Unit>(
        { vehicle.getSeat().registerSeatOccupancy(area, object : SeatOccupancyListener {
            override fun onSeatOccupancyUpdated(value: Boolean?) = putBelt(seat, 0, value)
            override fun onFailed(e: Exception) = putBelt(seat, 0, "오류 $e") }) },
        { vehicle.getSeat().registerSeatBeltBuckledState(area, object : SeatBeltBuckledStateListener {
            override fun onSeatBeltBuckledStateUpdated(value: Boolean?) = putBelt(seat, 1, value)
            override fun onFailed(e: Exception) = putBelt(seat, 1, "오류 $e") }) },
        { vehicle.getSafety().registerSeatBeltNotBuckledWarning(area, object : SeatBeltNotBuckledWarningListener {
            override fun onSeatBeltNotBuckledWarningUpdated(value: Boolean?) = putBelt(seat, 2, value)
            override fun onFailed(e: Exception) = putBelt(seat, 2, "오류 $e") }) },
    )

    private fun onTelltales(t: Telltales) =
        t.telltales.filter { it.state != TelltaleState.OFF }.joinToString { "${it.type}=${it.state}" }.ifEmpty { "없음" }

    private val naviListener = object : NaviHelperEventListener {
        override fun onDestinationInfo(info: DestinationInfo) = put("목적지", "${info.name} / 남은 ${info.remainDistance} m, ${info.remainTime} 초")
        override fun onRoutingCompleted() = navi.getDestinationInfo()
        override fun onDestinationArrived(info: DestinationArrivedInfo) = put("11 도착 콜백", "onDestinationArrived $info")
        override fun onRouteEnded() = put("11 도착 콜백", "onRouteEnded")
        override fun onCurrentRoadSpeedLimit(limit: Int) = put("+ 제한속도/카메라/주의구간", "제한속도 $limit")
        override fun onCameraAlert(list: List<CameraAlert>) = put("+ 제한속도/카메라/주의구간", "카메라 ${list.map { "${it.type}:${it.speedLimit}" }}")
        override fun onCautionPointInfo(list: List<CautionPoint>) = put("+ 제한속도/카메라/주의구간", "주의구간 ${list.map { it.type }}")
        override fun onError(code: NaviErrorCode) = put("마지막 오류", "Navi $code")
    }

    private val propCallback = object : CarPropertyManager.CarPropertyEventCallback {
        override fun onChangeEvent(v: CarPropertyValue<*>) {
            when (v.propertyId) {
                VehiclePropertyIds.PERF_VEHICLE_SPEED -> put("4 속도(AAOS)", "${v.value} m/s (${"%.1f".format((v.value as Float) * 3.6f)} km/h)")
                VehiclePropertyIds.NIGHT_MODE -> put("9 NIGHT_MODE(AAOS)", v.value)
                VehiclePropertyIds.GEAR_SELECTION -> put("10 기어(AAOS)", v.value)
            }
        }
        override fun onErrorEvent(propertyId: Int, areaId: Int) = put("마지막 오류", "AAOS 속성 오류 $propertyId")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        text = TextView(this).apply { textSize = 18f }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(40, 40, 40, 40); addView(text)
            addView(Button(context).apply { text = "목적지 설정: 서울역"; setOnClickListener { setDestination() } })
            addView(Button(context).apply { text = "앰비언트 켜기(운전석, 색 0xFFC08A)"; setOnClickListener { ambient() } })
            addView(Button(context).apply { text = "P단 전면 테스트 시작(서비스). 내비로 나간 뒤 기어 D 다음 P"; setOnClickListener { startP() } })
        }
        setContentView(ScrollView(this).apply { addView(col) })
        if (intent.getBooleanExtra(ArrivalService.EXTRA_FROM_P, false)) put("P단 전면 테스트", "○ 서비스가 띄움 (onCreate)")

        vehicle = Vehicle(applicationContext)
        runCatching { vehicle.initialize() }.onFailure { put("마지막 오류", "Vehicle $it") }
        val regs = listOf<() -> Unit>(
            { vehicle.getBrake().registerBrakePedalStatus(object : BrakePedalStatusListener {
                override fun onBrakePedalStatusUpdated(value: Boolean?) = put("1 브레이크", value)
                override fun onFailed(e: Exception) = put("1 브레이크", "오류 $e") }) },
            { vehicle.getSteeringWheel().registerSteeringWheelAngle(object : SteeringWheelAngleListener {
                override fun onSteeringWheelAngleUpdated(value: Float?) = put("2 핸들 각도", value)
                override fun onFailed(e: Exception) = put("2 핸들 각도", "오류 $e") }) },
            { vehicle.getTurnSignal().registerTurnSignalState(object : TurnSignalStateListener {
                override fun onTurnSignalStateUpdated(value: TurnSignalValue?) = put("3 지시등", value)
                override fun onFailed(e: Exception) = put("3 지시등", "오류 $e") }) },
            { vehicle.getDrivingMode().registerCruiseControlState(object : CruiseControlStateListener {
                override fun onCruiseControlStateUpdated(value: CruiseControl?) = put("5 크루즈", value)
                override fun onFailed(e: Exception) = put("5 크루즈", "오류 $e") }) },
            { vehicle.getSafety().registerTelltales(object : TelltaleInfoListener {
                override fun onTelltaleInfoUpdated(value: Telltales?) = put("7 경고등", value?.let(::onTelltales))
                override fun onFailed(e: Exception) = put("7 경고등", "오류 $e") }) },
            { vehicle.getGear().registerGearSelection(object : GearSelectionListener {
                override fun onSelected(value: VehicleGear?) = put("10 기어(Pleos)", value)
                override fun onFailed(e: Exception) = put("10 기어(Pleos)", "오류 $e") }) },
            { vehicle.getLight().registerLowBeamLightState(object : LightStateListener {
                override fun onLightStateUpdated(value: LightState?) = put("+ 하향등/미등", "하향등 $value")
                override fun onFailed(e: Exception) = put("+ 하향등/미등", "오류 $e") }) },
            { vehicle.getLight().registerTailLampLightState(object : LightStateListener {
                override fun onLightStateUpdated(value: LightState?) = put("+ 하향등/미등", "미등 $value")
                override fun onFailed(e: Exception) = put("+ 하향등/미등", "오류 $e") }) },
        ) + seatListeners("운전석", driver) + seatListeners("동승석", passenger)
        regs.forEach { runCatching(it).onFailure { e -> put("마지막 오류", "등록 $e") } }
        readOnce()

        navi = NaviHelper(applicationContext)
        navi.addListener(naviListener)
        navi.initialize({ navi.getDestinationInfo() }, { put("마지막 오류", "Navi 연결 실패 $it") })

        requestPermissions(arrayOf("android.car.permission.CAR_SPEED"), 1)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(ArrivalService.EXTRA_FROM_P, false)) put("P단 전면 테스트", "○ 서비스가 띄움 (onNewIntent)")
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        put("4 속도(AAOS)", "CAR_SPEED 권한 ${grantResults.firstOrNull()}")
        connectAaos()
    }

    private fun connectAaos() {
        runCatching {
            car = Car.createCar(this)
            props = car!!.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager
            listOf(VehiclePropertyIds.PERF_VEHICLE_SPEED to "4 속도(AAOS)", VehiclePropertyIds.NIGHT_MODE to "9 NIGHT_MODE(AAOS)",
                VehiclePropertyIds.GEAR_SELECTION to "10 기어(AAOS)").forEach { (id, key) ->
                runCatching {
                    val rate = if (id == VehiclePropertyIds.PERF_VEHICLE_SPEED) CarPropertyManager.SENSOR_RATE_UI else CarPropertyManager.SENSOR_RATE_ONCHANGE
                    val ok = props!!.registerCallback(propCallback, id, rate)
                    val now = runCatching { props!!.getProperty<Any>(id, 0).value }.getOrElse { "읽기 오류 $it" }
                    put(key, "등록 $ok, 지금 $now")
                }.onFailure { put(key, "오류 $it") }
            }
        }.onFailure { put("마지막 오류", "AAOS Car $it") }
    }

    private fun readOnce() {
        vehicle.getBrake().getCurrentBrakePedalStatus({ put("1 브레이크", "$it (읽기)") }, { put("1 브레이크", "오류 $it") })
        vehicle.getSteeringWheel().getCurrentSteeringWheelAngle({ put("2 핸들 각도", "$it (읽기)") }, { put("2 핸들 각도", "오류 $it") })
        vehicle.getTurnSignal().getCurrentTurnSignalState({ put("3 지시등", "$it (읽기)") }, { put("3 지시등", "오류 $it") })
        vehicle.getDrivingMode().getCruiseControlState({ put("5 크루즈", "$it (읽기)") }, { put("5 크루즈", "오류 $it") })
        vehicle.getGear().getCurrentGearSelectionState({ put("10 기어(Pleos)", "$it (읽기)") }, { put("10 기어(Pleos)", "오류 $it") })
        vehicle.getSeat().getCurrentSeatOccupancy(driver, { putBelt("운전석", 0, "$it(읽기)") }, { putBelt("운전석", 0, "오류 $it") })
        vehicle.getSeat().getCurrentSeatBeltBuckledState(driver, { putBelt("운전석", 1, "$it(읽기)") }, { putBelt("운전석", 1, "오류 $it") })
        vehicle.getSeat().getCurrentSeatOccupancy(passenger, { putBelt("동승석", 0, "$it(읽기)") }, { putBelt("동승석", 0, "오류 $it") })
        vehicle.getSeat().getCurrentSeatBeltBuckledState(passenger, { putBelt("동승석", 1, "$it(읽기)") }, { putBelt("동승석", 1, "오류 $it") })
        vehicle.getSafety().getTelltalesInfo({ put("7 경고등", onTelltales(it)) }, { put("7 경고등", "오류 $it") })
        vehicle.getLight().getAmbientLightBrightnessColor(driver, { put("8 앰비언트", "지금 색 $it") }, { put("8 앰비언트", "읽기 오류 $it") })
    }

    // 2026-11-06 실측: ROW_1_LEFT(0x1)는 미지원. 구역 후보를 차례로 읽기, 설정해 LEAGUE 로그에 남긴다
    private val ambientAreas = listOf(
        "빈 구역" to SeatArea(),
        "ROW_1_CENTER" to SeatArea(SeatValue.ROW_1_CENTER),
        "1열 전체" to SeatArea(SeatValue.ROW_1_LEFT, SeatValue.ROW_1_CENTER, SeatValue.ROW_1_RIGHT),
        "1, 2열 좌우" to SeatArea(SeatValue.ROW_1_LEFT, SeatValue.ROW_1_RIGHT, SeatValue.ROW_2_LEFT, SeatValue.ROW_2_RIGHT),
        "ROW_1_RIGHT" to SeatArea(SeatValue.ROW_1_RIGHT),
        "ROW_1_LEFT" to SeatArea(SeatValue.ROW_1_LEFT),
    )

    private fun ambient() = ambientAreas.forEach { (name, area) ->
        runCatching {
            vehicle.getLight().getAmbientLightBrightnessColor(area, { put("8 앰비언트 읽기 $name", "$it") }, { put("8 앰비언트 읽기 $name", "오류 $it") })
            vehicle.getLight().setAmbientLightBrightnessColor(area, 0xFFC08A,
                { a, v -> put("8 앰비언트 설정 $name", "○ 설정됨 $a $v") }, { put("8 앰비언트 설정 $name", "오류 $it") })
        }.onFailure { put("8 앰비언트 설정 $name", "예외 $it") }
    }

    private fun setDestination() {
        runCatching {
            navi.requestRoute(RouteInfo(37.5547, 126.9707, null, null, "서울역", "", "", "", RouteOption.RECOMMENDED, "", false))
        }.onFailure { put("마지막 오류", "requestRoute $it") }
    }

    private fun startP() {
        startForegroundService(Intent(this, ArrivalService::class.java))
        put("P단 전면 테스트", "서비스 시작. 내비로 나간 뒤 Virtual Console에서 기어 D 다음 P")
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        runCatching { props?.unregisterCallback(propCallback); car?.disconnect() }
        runCatching { vehicle.release() }
        runCatching { navi.removeListener(naviListener); navi.release() }
        super.onDestroy()
    }
}
