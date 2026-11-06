package ai.pleos.contest

import ai.pleos.contest.league.Engine
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log

// 포그라운드 서비스: 앱이 뒤에 있는 동안 Engine을 살려 두고, 도착 뒤 P단에서 결과 화면을 앞으로 올린다(startActivity + 알림).
// 2026-11-06 실측: 내비 위에서 D → P에 startActivity로 앱이 올라옴.
class ArrivalService : Service() {

    companion object { const val EXTRA_FROM_P = "fromP"; const val CH = "league" }

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH, "세이프 리그", NotificationManager.IMPORTANCE_HIGH))
        startForeground(1, note("리그 진행 중", "P단에 놓으면 결과를 보여 줍니다", false), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        Engine.start(this)
        Engine.bringToFront = {
            val r = runCatching { startActivity(screenIntent()) }
            Log.i(Engine.TAG, "P단 → startActivity ${if (r.isSuccess) "호출 성공" else "실패 ${r.exceptionOrNull()}"}")
            nm.notify(2, note("리그 결과 도착", "탭하면 결과 화면", true))
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

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY
    override fun onBind(intent: Intent?): IBinder? = null
}
