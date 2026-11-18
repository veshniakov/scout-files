package dev.scout.files.core.operations

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import dev.scout.files.core.operations.model.FileOperationTask
import dev.scout.files.core.operations.model.FileOperationType
import dev.scout.files.core.operations.model.OperationStatus
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject

@AndroidEntryPoint
class FileOperationForegroundService : Service() {

    @Inject
    lateinit var fileOperationManager: FileOperationManager

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var observerJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PAUSE -> {
                intent.getStringExtra(EXTRA_TASK_ID)?.let { fileOperationManager.pause(it) }
            }
            ACTION_RESUME -> {
                intent.getStringExtra(EXTRA_TASK_ID)?.let { fileOperationManager.resume(it) }
            }
            ACTION_CANCEL -> {
                intent.getStringExtra(EXTRA_TASK_ID)?.let { fileOperationManager.cancel(it) }
            }
        }
        startObservingTasks()
        return START_NOT_STICKY
    }

    private fun startObservingTasks() {
        if (observerJob != null) return

        observerJob = serviceScope.launch {
            fileOperationManager.tasks.collectLatest { tasks ->
                val activeTask = tasks.firstOrNull {
                    it.status == OperationStatus.RUNNING ||
                        it.status == OperationStatus.PAUSED ||
                        it.status == OperationStatus.WAITING_CONFLICT ||
                        it.status == OperationStatus.WAITING_PASSWORD ||
                        it.status == OperationStatus.QUEUED
                }

                if (activeTask != null) {
                    val notification = buildNotification(activeTask)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        startForeground(
                            NOTIFICATION_ID,
                            notification,
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                        )
                    } else {
                        startForeground(NOTIFICATION_ID, notification)
                    }
                } else {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
    }

    private fun buildNotification(task: FileOperationTask): Notification {
        val title = when (task.type) {
            FileOperationType.COPY -> getString(R.string.notification_title_copy)
            FileOperationType.MOVE -> getString(R.string.notification_title_move)
            FileOperationType.DELETE -> getString(R.string.notification_title_delete)
            FileOperationType.COMPRESS -> getString(R.string.notification_title_compress)
            FileOperationType.EXTRACT -> getString(R.string.notification_title_extract)
        }

        val speedText = formatSpeed(task.speedBytesPerSec)
        val fileText = task.currentFileName ?: ""
        val contentText = when {
            task.status == OperationStatus.PAUSED -> "${getString(R.string.notification_status_paused)} • $fileText"
            task.status == OperationStatus.WAITING_CONFLICT -> getString(R.string.notification_status_waiting_conflict)
            task.status == OperationStatus.WAITING_PASSWORD -> getString(R.string.notification_status_waiting_password)
            fileText.isNotEmpty() && speedText.isNotEmpty() -> "$fileText • $speedText"
            fileText.isNotEmpty() -> fileText
            else -> speedText
        }

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(contentText)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        if (task.totalBytes > 0) {
            val progressPercent = ((task.processedBytes.toDouble() / task.totalBytes.toDouble()) * 100).toInt()
            builder.setProgress(100, progressPercent.coerceIn(0, 100), false)
        } else if (task.totalFiles > 0) {
            builder.setProgress(task.totalFiles, task.processedFiles.coerceIn(0, task.totalFiles), false)
        } else {
            builder.setProgress(0, 0, true)
        }

        // Action buttons
        if (task.status == OperationStatus.PAUSED) {
            val resumeIntent = Intent(this, FileOperationForegroundService::class.java).apply {
                action = ACTION_RESUME
                putExtra(EXTRA_TASK_ID, task.id)
            }
            val resumePending = PendingIntent.getService(
                this,
                101,
                resumeIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(android.R.drawable.ic_media_play, getString(R.string.notification_action_resume), resumePending)
        } else {
            val pauseIntent = Intent(this, FileOperationForegroundService::class.java).apply {
                action = ACTION_PAUSE
                putExtra(EXTRA_TASK_ID, task.id)
            }
            val pausePending = PendingIntent.getService(
                this,
                102,
                pauseIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(android.R.drawable.ic_media_pause, getString(R.string.notification_action_pause), pausePending)
        }

        val cancelIntent = Intent(this, FileOperationForegroundService::class.java).apply {
            action = ACTION_CANCEL
            putExtra(EXTRA_TASK_ID, task.id)
        }
        val cancelPending = PendingIntent.getService(
            this,
            103,
            cancelIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.notification_action_cancel), cancelPending)

        return builder.build()
    }

    private fun formatSpeed(bytesPerSec: Long): String {
        if (bytesPerSec <= 0) return ""
        val kb = bytesPerSec / 1024.0
        val mb = kb / 1024.0
        return if (mb >= 1.0) {
            String.format(Locale.US, "%.1f MB/s", mb)
        } else {
            String.format(Locale.US, "%.0f KB/s", kb)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_desc)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        const val CHANNEL_ID = "scout_file_operations"
        const val NOTIFICATION_ID = 2001

        const val ACTION_START = "dev.scout.files.action.START_OPERATIONS"
        const val ACTION_PAUSE = "dev.scout.files.action.PAUSE_OPERATION"
        const val ACTION_RESUME = "dev.scout.files.action.RESUME_OPERATION"
        const val ACTION_CANCEL = "dev.scout.files.action.CANCEL_OPERATION"
        const val EXTRA_TASK_ID = "extra_task_id"

        fun start(context: Context) {
            val intent = Intent(context, FileOperationForegroundService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
