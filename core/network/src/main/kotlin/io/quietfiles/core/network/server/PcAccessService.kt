package io.quietfiles.core.network.server

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import io.quietfiles.core.network.R
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Держит «Доступ с ПК» живым, пока телефон в кармане, и показывает адрес и кнопку «Остановить» (F17).
 * Не экспортируется; останавливается вместе с сервером.
 */
@AndroidEntryPoint
class PcAccessService : LifecycleService() {

    @Inject lateinit var server: PcAccessServer

    private var started = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) {
            server.stop()
            stopSelf()
            return START_NOT_STICKY
        }
        if (!started) {
            started = true
            ensureChannel()
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(server.state.value), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            lifecycleScope.launch {
                server.state.collect { state ->
                    if (state !is PcAccessState.Running) {
                        ServiceCompat.stopForeground(this@PcAccessService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    } else {
                        val nm = NotificationManagerCompat.from(this@PcAccessService)
                        if (nm.areNotificationsEnabled()) runCatching { nm.notify(NOTIFICATION_ID, notification(state)) }
                    }
                }
            }
        }
        // Если система убила процесс, сервер не поднимается сам: пользователь включит его снова.
        return START_NOT_STICKY
    }

    private fun ensureChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.pc_access_channel), NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) },
        )
    }

    private fun notification(state: PcAccessState): Notification {
        val running = state as? PcAccessState.Running
        val stop = PendingIntent.getService(
            this, 0, Intent(this, PcAccessService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val open = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 1, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        val clients = running?.clients?.size ?: 0
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(getString(R.string.pc_access_notification_title))
            .setContentText(
                if (clients > 0) resources.getQuantityString(R.plurals.pc_access_clients, clients, clients) else running?.address.orEmpty(),
            )
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, getString(R.string.pc_access_stop), stop)
            .build()
    }

    companion object {
        private const val CHANNEL = "pc_access"
        private const val NOTIFICATION_ID = 2221
        private const val ACTION_STOP = "io.quietfiles.pcaccess.STOP"
    }
}
