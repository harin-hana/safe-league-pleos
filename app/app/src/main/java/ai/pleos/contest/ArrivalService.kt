package ai.pleos.contest

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import ai.pleos.playground.vehicle.listener.GearSelectionListener
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log

// P단 전면 표시 테스트: 포그라운드 서비스가 기어 P를 받으면 ① startActivity로 화면을 띄우고 ② 알림(탭하면 화면)도 올린다.
// 어느 경로가 되는지 logcat LEAGUE에 남는다.
class ArrivalService : Service() {

    companion object { const val EXTRA_FROM_P = "fromP"; const val CH = "league" }

    private lateinit var vehicle: Vehicle
    private val gearListener = object : GearSelectionListener {
        override fun onSelected(value: VehicleGear?) {
            Log.i(LeagueDiagActivity.TAG, "서비스 기어 = $value")
            if (value == VehicleGear.GEAR_PARK) onPark()
        }
        override fun onFailed(e: Exception) { Log.w(LeagueDiagActivity.TAG, "서비스 기어 오류 $e") }
    }

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH, "세이프 리그", NotificationManager.IMPORTANCE_HIGH))
        startForeground(1, note("리그 진행 중", "P단에 놓으면 결과를 보여 줍니다", false), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        vehicle = Vehicle(applicationContext)
        runCatching { vehicle.initialize(); vehicle.getGear().registerGearSelection(gearListener) }
            .onFailure { Log.w(LeagueDiagActivity.TAG, "서비스 Vehicle $it") }
    }

    private fun screenIntent() = Intent(this, LeagueDiagActivity::class.java)
        .putExtra(EXTRA_FROM_P, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)

    private fun onPark() {
        // ① 백그라운드에서 직접 띄우기 (Android 10 이후 제한 대상)
        val r = runCatching { startActivity(screenIntent()) }
        Log.i(LeagueDiagActivity.TAG, "P단 → startActivity ${if (r.isSuccess) "호출 성공(화면이 떴는지는 눈으로)" else "실패 ${r.exceptionOrNull()}"}")
        // ② 알림 경로 (탭하면 화면, fullScreenIntent 포함)
        getSystemService(NotificationManager::class.java).notify(2, note("리그 결과 도착", "탭하면 결과 화면", true))
        Log.i(LeagueDiagActivity.TAG, "P단 → 알림 2 게시")
    }

    private fun note(title: String, body: String, tap: Boolean): Notification {
        val pi = PendingIntent.getActivity(this, 0, screenIntent(), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val b = Notification.Builder(this, CH).setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle(title).setContentText(body)
        if (tap) b.setContentIntent(pi).setFullScreenIntent(pi, true).setAutoCancel(true)
        return b.build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() { runCatching { vehicle.getGear().unregisterGearSelection(gearListener); vehicle.release() }; super.onDestroy() }
}
