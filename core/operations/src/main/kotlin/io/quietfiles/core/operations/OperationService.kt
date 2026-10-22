package io.quietfiles.core.operations

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import io.quietfiles.core.common.formatDuration
import io.quietfiles.core.model.OperationSnapshot
import io.quietfiles.core.model.OperationStatus
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Foreground-сервис типа dataSync: держит процесс живым, пока идут операции, и показывает их прогресс.
 * Останавливается сам, когда очередь пуста.
 */
@AndroidEntryPoint
class OperationService : LifecycleService() {

    @Inject lateinit var queue: OperationQueue

    private var started = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_CANCEL_ALL) {
            queue.operations.value.filter { it.status.isActive }.forEach { queue.cancel(it.id) }
        }
        if (!started) {
            started = true
            ensureChannel(this)
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildNotification(queue.operations.value),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
            lifecycleScope.launch {
                queue.operations.collectLatest { ops ->
                    if (ops.none { it.status.isActive }) {
                        ServiceCompat.stopForeground(this@OperationService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    } else {
                        NotificationManagerCompat.from(this@OperationService).let { nm ->
                            if (nm.areNotificationsEnabled()) {
                                runCatching { nm.notify(NOTIFICATION_ID, buildNotification(ops)) }
                            }
                        }
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fstype: Int) {
        // Система ограничивает время dataSync-сервисов; корректно останавливаемся, операции остаются в очереди.
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun buildNotification(ops: List<OperationSnapshot>): Notification {
        val active = ops.filter { it.status.isActive }
        val current = active.firstOrNull { it.status != OperationStatus.QUEUED } ?: active.firstOrNull()
        val launch = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            putExtra(EXTRA_SHOW_OPERATIONS, true)
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val contentIntent = launch?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        val cancelIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, OperationService::class.java).setAction(ACTION_CANCEL_ALL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_operation)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setContentIntent(contentIntent)
            .addAction(0, getString(R.string.op_cancel_all), cancelIntent)
        if (current == null) {
            return builder.setContentTitle(getString(R.string.op_notification_preparing)).build()
        }
        val p = current.progress
        val percent = (p.fraction * 100).toInt()
        val text = when (current.status) {
            OperationStatus.WAITING_FOR_DECISION -> getString(R.string.op_waiting_decision)
            OperationStatus.PAUSED -> getString(R.string.op_paused)
            else -> buildString {
                p.currentName?.let { append(it) }
                p.secondsLeft?.let { if (isNotEmpty()) append(" · "); append(formatDuration(it)) }
            }
        }
        builder.setContentTitle(current.title)
            .setContentText(text)
            .setProgress(100, percent, p.bytesTotal == 0L && p.itemsTotal == 0)
        if (active.size > 1) builder.setSubText(resources.getQuantityString(R.plurals.op_more_queued, active.size - 1, active.size - 1))
        return builder.build()
    }

    companion object {
        const val CHANNEL_ID = "file_operations"
        const val EXTRA_SHOW_OPERATIONS = "io.quietfiles.extra.SHOW_OPERATIONS"
        private const val NOTIFICATION_ID = 1001
        private const val ACTION_CANCEL_ALL = "io.quietfiles.action.CANCEL_ALL_OPERATIONS"

        fun start(context: Context) {
            runCatching { ContextCompat.startForegroundService(context, Intent(context, OperationService::class.java)) }
        }

        fun ensureChannel(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java)
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, context.getString(R.string.op_channel_name), NotificationManager.IMPORTANCE_LOW).apply {
                        description = context.getString(R.string.op_channel_description)
                        setShowBadge(false)
                    },
                )
            }
        }
    }
}
