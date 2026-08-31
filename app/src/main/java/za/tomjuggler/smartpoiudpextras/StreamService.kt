package za.tomjuggler.smartpoiudpextras

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder

/**
 * Foreground service held while the bridge is streaming. Research-backed
 * mitigation for Android hotspot UDP stalls: Doze/App-Standby suspend network
 * activity and IGNORE wake locks, so a PARTIAL_WAKE_LOCK alone does not keep
 * the relay threads alive with the screen off (phone in pocket during poi
 * spinning). A foreground service keeps the process in the "network no
 * restrictions" bucket and exempt from App-Standby idle classification.
 */
class StreamService : Service() {

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "SmartPoi streaming",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Keeps the LED stream alive with the screen off" }
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, buildNotification())
        return START_STICKY
    }

    private fun buildNotification(): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("SmartPoi streaming")
            .setContentText("Relaying LED stream to your POIs")
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private companion object {
        const val CHANNEL_ID = "smartpoi_stream"
        const val NOTIF_ID = 1001
    }
}
