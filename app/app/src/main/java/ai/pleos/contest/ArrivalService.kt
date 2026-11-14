package ai.pleos.contest

import ai.pleos.contest.league.Engine
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.UserManager
import android.provider.Settings
import android.util.Log

// 포그라운드 서비스: 앱이 뒤에 있는 동안 Engine을 살려 두고, 도착 뒤 P단에서 결과 화면을 앞으로 올린다(startActivity + 알림).
// 2026-11-06 실측: 내비 위에서 D → P에 startActivity로 앱이 올라옴.
// 0.4.0: 시동(차량 화면 부팅)에 P단이면 출발 카드도 스스로 올린다.
class ArrivalService : Service() {

    companion object { const val EXTRA_FROM_P = "fromP"; const val EXTRA_BOOT = "boot"; const val CH = "league" }

    private val main = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH, "세이프 리그", NotificationManager.IMPORTANCE_HIGH))
        startForeground(1, note("리그 진행 중", "P단에 놓으면 결과를 보여 줍니다", false), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        Engine.start(this)
        // 화면 없이 깨어났을 때도 속도를 받는다(권한은 처음 화면에서 한 번 받은 것)
        if (checkSelfPermission("android.car.permission.CAR_SPEED") == PackageManager.PERMISSION_GRANTED) Engine.connectAaos(this)
        Engine.bringToFront = {
            front("P단")
            nm.notify(2, note("리그 결과 도착", "탭하면 결과 화면", true))
        }
    }

    // 안드로이드 14는 화면이 없는 앱이 스스로 화면을 올리는 것을 막는다(2026-11-12 실측 BAL_BLOCK).
    // 「다른 앱 위에 표시」 권한이 있으면 풀린다. Pleos Connect에는 그 설정 화면이 없어 adb로 한 번 준다:
    //   adb shell "appops set --user 10 ai.pleos.contest.pleos_commerce SYSTEM_ALERT_WINDOW allow; appops write-settings"
    // (write-settings를 빼면 곧바로 재부팅할 때 권한이 저장되지 않고 사라진다. 실측)
    // 권한이 없으면 알림도 화면에 뜨지 않아 아무 일도 없다. 호출은 막혀도 예외가 나지 않아 권한 여부를 같이 적는다
    private fun front(why: String) {
        val r = runCatching { startActivity(screenIntent()) }
        Log.i(Engine.TAG, "$why → startActivity ${if (r.isSuccess) "호출" else "실패 ${r.exceptionOrNull()}"}, 다른 앱 위에 표시 ${Settings.canDrawOverlays(this)}")
    }

    // 시동(부팅) 뒤 기어 값이 올 때까지 1초마다 본다. P단이고 출발 전일 때만 출발 카드를 올린다.
    // D단이거나 waitS초 안에 P단이 확인되지 않으면 올리지 않는다(주행 중에는 스스로 뜨지 않는다)
    private fun showDepart(waitS: Int) {
        if (!Engine.prefs.autoStart) return
        when {
            Engine.gear == VehicleGear.GEAR_PARK -> if (Engine.phase == Engine.Phase.IDLE) front("부팅, P단")
            Engine.gear == null && waitS > 0 -> main.postDelayed({ showDepart(waitS - 1) }, 1000)
            else -> Log.i(Engine.TAG, "부팅, 기어 ${Engine.gear}라 출발 카드를 올리지 않음")
        }
    }

    private fun screenIntent() = Intent(this, MainActivity::class.java)
        .putExtra(EXTRA_FROM_P, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)

    private fun note(title: String, body: String, tap: Boolean): Notification {
        val pi = PendingIntent.getActivity(this, 0, screenIntent(), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val b = Notification.Builder(this, CH).setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle(title).setContentText(body)
        if (tap) b.setContentIntent(pi).setFullScreenIntent(pi, true).setAutoCancel(true)
        return b.build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.getBooleanExtra(EXTRA_BOOT, false) == true) showDepart(30)
        return START_STICKY
    }
    override fun onBind(intent: Intent?): IBinder? = null
}

// 시동 = 차량 화면 부팅. 서비스를 깨우면 서비스가 P단을 확인한 뒤 출발 카드를 올린다.
// AAOS의 사용자 0은 화면 없는 시스템 사용자라 건너뛴다(운전자는 사용자 10)
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) {
        if (ctx.getSystemService(UserManager::class.java).isSystemUser) return
        Log.i(Engine.TAG, "부팅 방송 ${i.action}")
        ctx.startForegroundService(Intent(ctx, ArrivalService::class.java).putExtra(ArrivalService.EXTRA_BOOT, true))
    }
}
