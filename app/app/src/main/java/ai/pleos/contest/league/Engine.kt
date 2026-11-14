package ai.pleos.contest.league

import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.WaypointIndex
import ai.pleos.playground.navi.data.CameraAlert
import ai.pleos.playground.navi.data.CautionPoint
import ai.pleos.playground.navi.data.CurrentLocationInfo
import ai.pleos.playground.navi.data.DestinationArrivedInfo
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.data.RequestWaypointInfo
import ai.pleos.playground.navi.data.RouteStartInfo
import ai.pleos.playground.navi.data.TBTInfo
import ai.pleos.playground.navi.data.WaypointChangedInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.area.WindowArea
import ai.pleos.playground.vehicle.api.model.telltale.Telltales
import ai.pleos.playground.vehicle.constant.area.TurnSignalValue
import ai.pleos.playground.vehicle.constant.area.WindowValue
import ai.pleos.playground.vehicle.constant.control.LightState
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import ai.pleos.playground.vehicle.constant.control.WiperState
import ai.pleos.playground.vehicle.constant.telltale.TelltaleState
import ai.pleos.playground.vehicle.constant.telltale.TelltaleType
import ai.pleos.playground.vehicle.listener.BrakePedalStatusListener
import ai.pleos.playground.vehicle.listener.DistanceDrivenListener
import ai.pleos.playground.vehicle.listener.GearSelectionListener
import ai.pleos.playground.vehicle.listener.LightStateListener
import ai.pleos.playground.vehicle.listener.SteeringWheelAngleListener
import ai.pleos.playground.vehicle.listener.TelltaleInfoListener
import ai.pleos.playground.vehicle.listener.TurnSignalStateListener
import ai.pleos.playground.vehicle.listener.WiperStateListener
import android.car.Car
import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.ComponentCallbacks
import android.content.Context
import android.content.res.Configuration
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter

// 세이프 리그 두뇌. 프로세스에 하나(object)라 야간 전환으로 Activity가 다시 만들어져도 상태를 잃지 않는다.
// 신호 → 판정 모듈(Judge) → 연출(소리, 앰비언트) → 도착 P단 결과. 모든 콜백은 메인 스레드로 모아 한 줄로 처리한다.
object Engine {
    const val TAG = "LEAGUE"
    enum class Phase { IDLE, ARMED, DRIVING, ARRIVED, RESULT, PRIZE }
    enum class Slot { AM, PM, NONE }

    private val main = Handler(Looper.getMainLooper())
    private var started = false
    lateinit var prefs: Prefs; private set
    private lateinit var sounds: Sounds
    private var vehicle: Vehicle? = null
    private var navi: NaviHelper? = null
    private var car: Car? = null
    private var props: CarPropertyManager? = null

    // 상태
    var phase = Phase.IDLE; private set
    var judge: Judge? = null; private set
    var gear: VehicleGear? = null; private set
    var night = false; private set
    var wiper = false; private set
    var beltWarning = false; private set
    var lowBeam = false; private set
    var tailLamp = false; private set
    var speedKmh = 0f; private set
    var brake = false; private set
    var steering = 0f; private set
    var turnSignal = 0; private set
    var dest: DestinationInfo? = null; private set
    var routeStart: RouteStartInfo? = null; private set
    var location: CurrentLocationInfo? = null; private set
    var result: Score.Result? = null; private set
    var board: Board? = null; private set
    var prize: Prize? = null; private set
    var detail = false                       // 결과 화면 「내역 보기」 펼침
    var waypointAddedMin: Int? = null        // 「들러요」 뒤 내비가 돌려준 도착 지연(분)
    var prizeChoice = 1                      // 다음 날 카드에서 고른 받을 것(기본 둘째, 아메리카노. 허브 11/07)
    var ambientFlashUntil = 0L; private set
    var demoNote: String? = null; private set   // 화면 구석 「데모 입력: 도착」
    var demoOpen = false                         // 데모 입력 버튼 줄 펼침
    val cueMarks = mutableListOf<Pair<String, Long>>()   // 경로 띠 눈금(콤보 고비, 주행 시작 기준 ms)
    fun ladderTick() { if (prefs.sound) sounds.ladder() }
    var aaosOk = false; private set
    var lastError: String? = null; private set
    private var tripStart: LocalDateTime? = null
    private var tripStartMs = 0L
    private var beltSoundDone = false
    private var ambientFailLogged = false
    private var lastSpeedCbMs = 0L
    private val listeners = mutableSetOf<() -> Unit>()
    var bringToFront: (() -> Unit)? = null

    val demo get() = prefs.demo
    // 데모는 출발부터 도착, 결과까지 아침 출근 하나(집 → 회사). 한 번의 주행 안에서 아침과 저녁이 섞이지 않게(2026-11-14)
    val slot: Slot get() = if (demo) Slot.AM else LocalTime.now().hour.let { if (it in 6..9) Slot.AM else if (it in 17..20) Slot.PM else Slot.NONE }

    fun addListener(l: () -> Unit) { listeners += l }
    fun removeListener(l: () -> Unit) { listeners -= l }
    fun notifyUi() { logVerdicts(); listeners.toList().forEach { it() } }

    // 판정이 날 때마다 logcat에 한 줄(태그 LEAGUE_JUDGE). 화면에 판정 보기가 없어도 영상 해설이 앱의 실제 기록에서 옮겨 적을 수 있게.
    // 콤보는 판정 보기처럼 한 줄을 고쳐 쓰므로 콤보가 오를 때마다 새 줄이 남는다
    const val TAG_JUDGE = "LEAGUE_JUDGE"
    private val loggedVerdicts = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Verdict, Boolean>())
    private fun logVerdicts() {
        val j = judge ?: return
        j.verdicts.forEach { if (loggedVerdicts.add(it)) Log.i(TAG_JUDGE, Score.logLine(it, j.cfg.jamWindowSec)) }
    }
    private fun post(f: () -> Unit) { main.post { f(); notifyUi() } }

    // ---------- 시작 ----------

    fun start(ctx: Context) {
        if (started) return
        started = true
        val app = ctx.applicationContext
        prefs = Prefs(app)
        LeagueClient.baseUrl = prefs.serverUrl
        sounds = Sounds(app)
        night = app.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        app.registerComponentCallbacks(object : ComponentCallbacks {
            override fun onConfigurationChanged(c: Configuration) = post { night = c.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES; judge?.onNight(night) }
            override fun onLowMemory() {}
        })
        connectVehicle(app)
        connectNavi(app)
        main.post(heartbeat)
        Log.i(TAG, "엔진 시작. 데모 ${prefs.demo}, 핸들 부호 ${prefs.steeringSign}, 서버 '${prefs.serverUrl}'")
    }

    private fun connectVehicle(app: Context) {
        val v = Vehicle(app).also { vehicle = it }
        runCatching { v.initialize() }.onFailure { err("Vehicle $it"); return }
        val regs = listOf<() -> Unit>(
            { v.getGear().registerGearSelection(object : GearSelectionListener {
                override fun onSelected(value: VehicleGear?) = post { onGear(value) }
                override fun onFailed(e: Exception) = err("기어 $e") }) },
            { v.getBrake().registerBrakePedalStatus(object : BrakePedalStatusListener {
                override fun onBrakePedalStatusUpdated(value: Boolean?) = post { brake = value == true; judge?.onBrake(brake) }
                override fun onFailed(e: Exception) = err("브레이크 $e") }) },
            { v.getSteeringWheel().registerSteeringWheelAngle(object : SteeringWheelAngleListener {
                override fun onSteeringWheelAngleUpdated(value: Float?) = post { steering = value ?: 0f; judge?.onSteering(steering) }
                override fun onFailed(e: Exception) = err("핸들 $e") }) },
            { v.getTurnSignal().registerTurnSignalState(object : TurnSignalStateListener {
                override fun onTurnSignalStateUpdated(value: TurnSignalValue?) = post {
                    turnSignal = when (value) { TurnSignalValue.LEFT -> -1; TurnSignalValue.RIGHT -> 1; else -> 0 }; judge?.onTurnSignal(turnSignal) }
                override fun onFailed(e: Exception) = err("지시등 $e") }) },
            { v.getLight().registerLowBeamLightState(object : LightStateListener {
                override fun onLightStateUpdated(value: LightState?) = post { lowBeam = value == LightState.ON; judge?.onLowBeam(lowBeam) }
                override fun onFailed(e: Exception) = err("하향등 $e") }) },
            { v.getLight().registerTailLampLightState(object : LightStateListener {
                override fun onLightStateUpdated(value: LightState?) = post { tailLamp = value == LightState.ON; judge?.onTailLamp(tailLamp) }
                override fun onFailed(e: Exception) = err("미등 $e") }) },
            { v.getWiper().registerWiperState(WindowArea(WindowValue.FRONT_WINDSHIELD), object : WiperStateListener {
                override fun onWiperStateUpdated(value: WiperState?) = post { wiper = value == WiperState.ON; judge?.onWiper(wiper) }
                override fun onFailed(e: Exception) = err("와이퍼 $e") }) },
            { v.getSafety().registerTelltales(object : TelltaleInfoListener {
                override fun onTelltaleInfoUpdated(value: Telltales?) = post { onTelltales(value) }
                override fun onFailed(e: Exception) = err("경고등 $e") }) },
            { v.getOdometer().registerTripCurrentDrivingDistance(object : DistanceDrivenListener {
                override fun onDistanceDrivenUpdated(value: Float?) = post { if (value != null && !demo) judge?.onOdometerKm(value / 1000f) }
                override fun onFailed(e: Exception) = err("주행 거리 $e") }) },
        )
        regs.forEach { runCatching(it).onFailure { e -> err("등록 $e") } }
        // 현재 값 한 번 읽기
        runCatching {
            v.getGear().getCurrentGearSelectionState({ post { onGear(it) } }, {})
            v.getSteeringWheel().getCurrentSteeringWheelAngle({ post { steering = it ?: 0f } }, {})
            v.getLight().getCurrentLowBeamLightState({ post { lowBeam = it == LightState.ON } }, {})
            v.getLight().getCurrentTailLampLightState({ post { tailLamp = it == LightState.ON } }, {})
            v.getSafety().getTelltalesInfo({ post { onTelltales(it) } }, {})
            v.getWiper().getCurrentWiperState(WindowArea(WindowValue.FRONT_WINDSHIELD), { post { wiper = it == WiperState.ON } }, {})
        }
    }

    private var attentionWasOn = false
    private fun onTelltales(t: Telltales?) {
        val on = t?.telltales?.filter { it.state != TelltaleState.OFF }?.map { it.type }?.toSet() ?: emptySet()
        val belt = TelltaleType.SEAT_BELT in on
        if (beltWarning && !belt && (phase == Phase.IDLE || phase == Phase.ARMED) && gear == VehicleGear.GEAR_PARK) beltAllInSound()
        beltWarning = belt; judge?.onBeltWarning(belt)
        val att = TelltaleType.DRIVER_ATTENTION in on
        if (att && !attentionWasOn) judge?.onDriverAttention()
        attentionWasOn = att
    }

    private fun beltAllInSound() { if (!beltSoundDone) { beltSoundDone = true; if (prefs.sound) sounds.beltAllIn() } }

    // AAOS 속도(10Hz 시계). 1차 실측 car_service 크래시가 있어 전부 try로 감싸고, 실패해도 Pleos 신호는 그대로 쓴다.
    fun connectAaos(ctx: Context) {
        if (aaosOk) return
        runCatching {
            car = Car.createCar(ctx.applicationContext)
            props = car!!.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager
            aaosOk = props!!.registerCallback(propCallback, VehiclePropertyIds.PERF_VEHICLE_SPEED, CarPropertyManager.SENSOR_RATE_FAST)
            Log.i(TAG, "AAOS 속도 등록 $aaosOk")
        }.onFailure { aaosOk = false; err("AAOS $it") }
        // 시동 신호는 앱까지 오지 않는다(2026-11-12 실측: Vehicle SDK 2.3.2에 시동 리스너 없음, AAOS IGNITION_STATE 속성 없음,
        // Virtual Console power-system은 VHAL 로그까지만). 그래서 시동 = 부팅으로 본다(ArrivalService, BootReceiver)
    }

    private val propCallback = object : CarPropertyManager.CarPropertyEventCallback {
        override fun onChangeEvent(v: CarPropertyValue<*>) {
            if (v.propertyId == VehiclePropertyIds.PERF_VEHICLE_SPEED) main.post { lastSpeedCbMs = SystemClock.elapsedRealtime(); tick((v.value as Float) * 3.6f) }
        }
        override fun onErrorEvent(propertyId: Int, areaId: Int) = err("AAOS 속성 오류 $propertyId")
    }

    // 속도 콜백이 10Hz로 안 오면 마지막 값으로 틱을 채운다(에뮬레이터는 값이 바뀔 때만 옴)
    private val heartbeat = object : Runnable {
        override fun run() {
            if (SystemClock.elapsedRealtime() - lastSpeedCbMs > 150) tick(speedKmh)
            main.postDelayed(this, 100)
        }
    }

    private fun tick(kmh: Float) {
        speedKmh = kmh
        val j = judge ?: return
        if (phase != Phase.ARMED && phase != Phase.DRIVING) return
        j.tick(kmh, SystemClock.elapsedRealtime() - tripStartMs)
        logVerdicts()   // 급제동 같은 판정은 속도 틱에서 난다
    }

    private fun connectNavi(app: Context) {
        val n = NaviHelper(app).also { navi = it }
        n.addListener(naviListener)
        runCatching { n.initialize({ n.getDestinationInfo() }, { err("Navi 연결 실패 $it") }) }.onFailure { err("Navi $it") }
    }

    private val naviListener = object : NaviHelperEventListener {
        override fun onDestinationInfo(info: DestinationInfo) = post { dest = info.takeIf { it.name.isNotBlank() && it.remainDistance > 0 } }
        override fun onRoutingCompleted() { navi?.getDestinationInfo() }
        override fun onDestinationChanged() { navi?.getDestinationInfo() }
        override fun onRouteStarted(info: RouteStartInfo) = post { routeStart = info }
        override fun onRouteCancelled() = post { dest = null }
        override fun onDestinationArrived(info: DestinationArrivedInfo) = post { Log.i(TAG, "도착 콜백 $info"); arrive("내비") }
        override fun onCurrentLocationInfo(info: CurrentLocationInfo) = post { location = info }
        override fun onCurrentRoadSpeedLimit(limit: Int) = post { judge?.onSpeedLimit(limit) }
        override fun onTBTInfo(list: List<TBTInfo>) = post { judge?.onTbtDistance(list.firstOrNull()?.distance ?: Double.MAX_VALUE) }
        override fun onCameraAlert(list: List<CameraAlert>) = post {
            val j = judge ?: return@post
            list.firstOrNull { it.type.name == "SPEED_CAMERA_SECTION_END" && it.isOverSectionAvrSpeed }?.let { j.onSectionOverSpeed() }
            j.onCamera(list.filter { it.type.name.startsWith("SPEED_CAMERA") }.map { Triple(it.id, it.speedLimit, it.remainDistance) })
        }
        override fun onCautionPointInfo(list: List<CautionPoint>) = post {
            val j = judge ?: return@post
            if (list.any { it.type.name == "SCHOOL_ZONE" && it.remainDistance <= 30 }) j.onSchoolZone()
            j.onCurveCaution(list.any { it.type.name.contains("CURVE") && it.remainDistance in 0..300 })
        }
        override fun onWaypointChanged(info: WaypointChangedInfo) = post {
            val before = dest?.remainTime ?: 0
            waypointAddedMin = maxOf(0, (info.routeStartInfo.duration - before) / 60)
            Log.i(TAG, "경유지 ${info.cmdType} ${info.routeStartInfo.name} ${info.routeStartInfo.duration}s (전 ${before}s)")
            navi?.getDestinationInfo()
        }
        override fun onError(code: NaviErrorCode) = err("Navi $code")
    }

    // ---------- 흐름 ----------

    private fun onGear(g: VehicleGear?) {
        val prev = gear; gear = g
        Log.i(TAG, "기어 $prev → $g, 단계 $phase")
        when {
            g == VehicleGear.GEAR_DRIVE && phase == Phase.ARMED -> { phase = Phase.DRIVING; Log.i(TAG, "주행 시작, 화면 없음") }
            g == VehicleGear.GEAR_DRIVE && phase == Phase.IDLE && prefs.autoJoin -> { join(); phase = Phase.DRIVING }
            g == VehicleGear.GEAR_PARK && phase == Phase.ARRIVED -> showResult()
            // 데모는 내비 목적지가 없다. 주행 중 P단에 두면 도착으로 본다(주행 중에는 화면이 비켜 있어 「데모 입력: 도착」을 손으로 누를 자리가 없다. 0.4.1c)
            g == VehicleGear.GEAR_PARK && phase == Phase.DRIVING && demo -> arrive("데모 P단")
        }
    }

    // 출발 카드 「리그 참가」
    fun join() {
        if (phase != Phase.IDLE && phase != Phase.RESULT && phase != Phase.PRIZE) return
        val j = Judge(Config(prefs.steeringSign, if (demo) 10 else 60, prefs.steerRatio, prefs.wheelbase)) { c -> main.post { onCue(c) } }
        j.onBrake(brake); j.onTurnSignal(turnSignal); j.onLowBeam(lowBeam); j.onTailLamp(tailLamp); j.onNight(night); j.onWiper(wiper); j.onBeltWarning(beltWarning)
        j.onSteering(steering)
        j.onDepart()
        judge = j; result = null; board = null; detail = false; waypointAddedMin = null; cueMarks.clear(); loggedVerdicts.clear()
        tripStart = LocalDateTime.now(); tripStartMs = SystemClock.elapsedRealtime()
        phase = Phase.ARMED
        Log.i(TAG, "리그 참가. 벨트 올인 ${j.bonus[Bonus.B1] == 1}")
        notifyUi()
    }

    fun rest() { phase = Phase.IDLE; judge = null; notifyUi() }

    private fun onCue(c: Cue) {
        Log.i(TAG, "고비 $c")
        val t = SystemClock.elapsedRealtime() - tripStartMs
        when (c) {
            is Cue.Combo -> { cueMarks += "콤보 ${c.n}" to t; if (prefs.sound) sounds.combo(c.n); if (prefs.tts) sounds.say("콤보 ${c.n}"); ambientOnce() }
            is Cue.CleanKm -> { cueMarks += "클린 ${c.n}km" to t; if (prefs.sound) sounds.cleanKm(); ambientOnce() }
            Cue.BeltAllIn -> beltAllInSound()
        }
    }

    // 앰비언트 한 번 반짝. 에뮬레이터는 구역 미지원이라 실패 로그 한 번, 화면에는 아래 띠(데모 입력)
    fun ambientOnce() {
        if (!prefs.ambient) return
        ambientFlashUntil = SystemClock.elapsedRealtime() + 1200
        runCatching {
            vehicle?.getLight()?.setAmbientLightBrightnessColor(SeatArea(), 0xF2B24C, { _, _ -> Log.i(TAG, "앰비언트 설정 성공") },
                { if (!ambientFailLogged) { ambientFailLogged = true; Log.i(TAG, "앰비언트 미지원(데모 입력으로): $it") } })
        }
        notifyUi(); main.postDelayed({ notifyUi() }, 1300)
    }

    // 도착(내비 콜백 또는 데모 입력). 결과는 P단에서.
    fun arrive(from: String) {
        if (phase != Phase.DRIVING && phase != Phase.ARMED) return
        phase = Phase.ARRIVED
        Log.i(TAG, "도착($from). P단을 기다림")
        if (gear == VehicleGear.GEAR_PARK) showResult() else notifyUi()
    }

    private fun showResult() {
        val j = judge ?: return
        j.finish()
        val km = if (demo) 15.0 else j.distanceKm
        val r = Score.of(j, km).also { result = it }
        phase = Phase.RESULT; detail = false
        Log.i(TAG, "결과 ${r.score} (감점 ${r.penaltySum} → ${r.converted}, 가산 ${r.bonusSum}) 거리 $km km")
        if (prefs.sound) sounds.result()
        ambientOnce()
        bringToFront?.invoke()
        notifyUi()
        // 서버가 4초 안에 답하지 않으면 고스트 리그로(에뮬레이터는 DNS가 없어 30초 넘게 걸림)
        val ghost = { if (demo) Ghost.demoBoard(r.score) else Ghost.board(r.score) }
        main.postDelayed({ if (board == null) { board = ghost(); Log.i(TAG, "서버 응답 없음, 고스트 리그"); notifyUi() } }, 4000)
        Thread {
            val start = tripStart ?: LocalDateTime.now()
            val durationS = ((SystemClock.elapsedRealtime() - tripStartMs) / 1000).toInt()
            val etaS = if (demo) 35 * 60 else (dest?.remainTime ?: 0)
            // 데모 모드는 시드의 시연 차량 'me'로 올린다(설치마다 바뀌는 id가 데모 리그에 행을 쌓아 「8명 중 3위」가 되던 것. 11/07)
            val b = LeagueClient.upload(if (prefs.demo) "me" else prefs.driverId, prefs.nickname, leagueKey(), start.toLocalDate().toString(),
                start.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME), r, durationS, etaS, eligible(r, durationS, etaS))
                // 서버 데모 시드에는 어제 아침 기록이 없다(어제 저녁만). 데모의 「어제 아침보다」는 앱 시드 값으로 채운다
                ?.let { if (demo && it.ghostDiff == null) it.copy(ghostDiff = Score.round1(r.score - Ghost.yesterdayMe)) else it }
                ?: ghost()
            main.post { if (board == null || b.source == "서버") { board = b; Log.i(TAG, "순위 ${b.myRank}/${b.total} (${b.source})"); notifyUi() } }
        }.start()
    }

    private fun eligible(r: Score.Result, durationS: Int, etaS: Int): Boolean =
        demo || (slot != Slot.NONE && dest != null && r.distanceKm >= 3 && (etaS == 0 || durationS <= etaS * 1.2))

    private fun grid(v: Double, size: Double) = (v / size).toInt()
    fun leagueKey(): String {
        if (demo) return "HOME|WORK|AM"
        val l = location; val d = dest
        val s = if (l == null) "0_0" else "${grid(l.latitude, 0.03)}_${grid(l.longitude, 0.03)}"
        val e = if (d == null) "0_0" else "${grid(d.latitude, 0.01)}_${grid(d.longitude, 0.01)}"
        return "$s|$e|${slot.name}"
    }

    // 다음 날 카드(상금). 데모 입력 「다음 날」 또는 실제로 상금이 있을 때
    fun nextDay() {
        prize = null; phase = Phase.PRIZE; waypointAddedMin = null; prizeChoice = 1; notifyUi()
        main.postDelayed({ if (prize == null) { prize = Ghost.prize; Log.i(TAG, "도장 서버 응답 없음, 시드 값"); notifyUi() } }, 4000)
        Thread {
            val p = LeagueClient.prize(prefs.driverId) ?: return@Thread
            main.post { prize = p; notifyUi() }
        }.start()
    }

    // 「들러요」: 내비 경유지 추가
    fun stopBy() {
        val c = prize?.choices?.getOrNull(prizeChoice) ?: return
        runCatching { navi?.addWaypoint(RequestWaypointInfo(c.lat, c.lng, c.name, WaypointIndex.FIRST, "", "", "", "")) }
            .onSuccess { Log.i(TAG, "들러요 → addWaypoint ${c.name} (${c.lat}, ${c.lng})") }.onFailure { err("경유지 추가 $it") }
        waypointAddedMin = waypointAddedMin ?: c.extraMin   // 내비 응답이 오면 실제 값으로 바뀜
        notifyUi()
    }

    fun later() { phase = Phase.IDLE; notifyUi() }

    // ---------- 데모 입력 ----------

    fun demoInput(what: String) {
        if (!demo) return
        demoNote = what; main.postDelayed({ if (demoNote == what) { demoNote = null; notifyUi() } }, 4000)
        Log.i(TAG, "데모 입력: $what")
        when (what) {
            "도착" -> arrive("데모 입력")
            "앰비언트" -> ambientOnce()
            "주의 경고" -> judge?.onDriverAttention()
            "카메라" -> { judge?.onCamera(listOf(Triple("demo", 60, 300))); main.postDelayed({ judge?.onCamera(emptyList()); notifyUi() }, 3000) }
            "주의 구간" -> { judge?.onSpeedLimit(60); judge?.onSchoolZone() }
            "다음 날" -> nextDay()
        }
        notifyUi()
    }

    fun reload() { LeagueClient.baseUrl = prefs.serverUrl }
    fun resetForDemo() { phase = Phase.IDLE; judge = null; result = null; board = null; prize = null; beltSoundDone = false; notifyUi() }

    private fun err(msg: String) { lastError = msg; Log.w(TAG, msg) }

    fun today(): String = LocalDate.now().toString()
}
