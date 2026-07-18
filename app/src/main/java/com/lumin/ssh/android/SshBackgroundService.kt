package com.lumin.ssh.android

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

class SshBackgroundService : Service() {
    private val localizedContext: Context
        get() = applicationContext.withAppLanguage(LocalStore(applicationContext).loadAppLanguage())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val sessionId = intent?.getStringExtra(EXTRA_SESSION_ID)
        when (intent?.action) {
            ACTION_STOP -> {
                if (sessionId != null) {
                    BackgroundSshSessions.close(sessionId)
                    getSystemService(NotificationManager::class.java).cancel(notificationId(sessionId))
                    rebalanceForeground()
                }
                return START_NOT_STICKY
            }
            ACTION_RELEASE -> {
                if (sessionId != null) {
                    getSystemService(NotificationManager::class.java).cancel(notificationId(sessionId))
                    rebalanceForeground()
                }
                return START_NOT_STICKY
            }
            else -> {
                val entry = sessionId?.let { BackgroundSshSessions.get(it) } ?: return START_NOT_STICKY
                val notification = buildNotification(entry)
                startForeground(notificationId(sessionId), notification)
                refreshAllNotifications()
            }
        }
        return START_STICKY
    }

    private fun refreshAllNotifications() {
        val manager = getSystemService(NotificationManager::class.java)
        BackgroundSshSessions.all().forEach { entry ->
            manager.notify(notificationId(entry.sessionId), buildNotification(entry))
        }
    }

    private fun rebalanceForeground() {
        val next = BackgroundSshSessions.first()
        if (next == null) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        } else {
            startForeground(notificationId(next.sessionId), buildNotification(next))
            refreshAllNotifications()
        }
    }

    private fun buildNotification(entry: BackgroundSshSessions.Entry): android.app.Notification {
        val text = localizedContext
        return NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.stat_sys_upload_done)
        .setContentTitle(entry.conn.name.ifBlank { entry.conn.host })
        .setContentText(text.getString(R.string.background_session_running, entry.conn.username, entry.conn.host))
        .setOngoing(true)
        .setContentIntent(
            PendingIntent.getActivity(
                this,
                entry.sessionId.hashCode(),
                Intent(this, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    putExtra(EXTRA_SESSION_ID, entry.sessionId)
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        )
        .addAction(
            android.R.drawable.ic_menu_close_clear_cancel,
            text.getString(R.string.disconnect),
            PendingIntent.getService(
                this,
                entry.sessionId.hashCode() + 1,
                Intent(this, SshBackgroundService::class.java).apply {
                    action = ACTION_STOP
                    putExtra(EXTRA_SESSION_ID, entry.sessionId)
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        )
        .build()
    }

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, localizedContext.getString(R.string.background_ssh_sessions), NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    companion object {
        private const val CHANNEL_ID = "ssh_background"
        const val EXTRA_SESSION_ID = "ssh_session_id"
        private const val ACTION_STOP = "com.lumin.ssh.android.STOP_BACKGROUND_SSH"
        private const val ACTION_RELEASE = "com.lumin.ssh.android.RELEASE_BACKGROUND_SSH"

        fun start(context: Context, sessionId: String) {
            val intent = Intent(context, SshBackgroundService::class.java).putExtra(EXTRA_SESSION_ID, sessionId)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent) else context.startService(intent)
        }

        fun stop(context: Context, sessionId: String) {
            context.getSystemService(NotificationManager::class.java).cancel(notificationId(sessionId))
        }

        fun release(context: Context, sessionId: String) {
            val intent = Intent(context, SshBackgroundService::class.java).apply {
                action = ACTION_RELEASE
                putExtra(EXTRA_SESSION_ID, sessionId)
            }
            context.startService(intent)
        }

        private fun notificationId(sessionId: String) = 1000 + sessionId.hashCode().let { if (it == Int.MIN_VALUE) 0 else kotlin.math.abs(it) % 100000 }
    }
}
