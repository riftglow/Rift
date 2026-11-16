package light.rift.core.ui

import android.content.Context
import androidx.core.app.NotificationCompat
import light.rift.R
import light.rift.RiftApp
import light.rift.ktx.Logs

/**
 * Progress notification for a running connection test ("3 / 12"), shown
 * on the "connection-test" channel and cancelled when the test finishes.
 * All failures are swallowed and logged — a notification must never crash
 * a test run.
 */
class ConnectionTestNotification(val context: Context, val title: String) {

    private val channelId = "connection-test"
    private val notificationId = 1001

    fun updateNotification(progress: Int, max: Int, finished: Boolean) {
        try {
            if (finished) {
                RiftApp.notification.cancel(notificationId)
                return
            }
            val notification = NotificationCompat.Builder(context, channelId)
                .setSmallIcon(R.drawable.ic_service_active)
                .setContentTitle(title)
                .setOnlyAlertOnce(true)
                .setContentText("$progress / $max")
                .setProgress(max, progress, false)
                .build()
            RiftApp.notification.notify(notificationId, notification)
        } catch (e: Exception) {
            Logs.w(e)
        }
    }

}
