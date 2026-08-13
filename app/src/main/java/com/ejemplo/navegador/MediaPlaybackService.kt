package com.ejemplo.navegador

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

class MediaPlaybackService : Service() {
    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PAUSE -> BrowserMediaController.pauseCurrent()
            ACTION_STOP -> BrowserMediaController.stopCurrent()
        }

        val state = BrowserMediaController.snapshot()
        if (state == null || !state.playing || !BrowserPrefs.backgroundMedia(this)) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        val notification = buildNotification(state)
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(state: BrowserMediaController.Snapshot): Notification {
        val open = PendingIntent.getActivity(
            this, 200,
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val pause = PendingIntent.getService(
            this, 201,
            Intent(this, MediaPlaybackService::class.java).setAction(ACTION_PAUSE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 202,
            Intent(this, MediaPlaybackService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_nexo_logo)
            .setContentTitle(state.title)
            .setContentText("Reproduciendo desde Nexo Browser")
            .setContentIntent(open)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(android.R.drawable.ic_media_pause, "Pausar", pause).build())
            .addAction(Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel, "Detener", stop).build())
            .setStyle(Notification.MediaStyle().setShowActionsInCompactView(0, 1))
            .build()
    }

    private fun createChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Reproducción multimedia",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Controles de audio y video de Nexo Browser"
                setShowBadge(false)
            }
        )
    }

    companion object {
        private const val CHANNEL_ID = "nexo_media_playback"
        private const val NOTIFICATION_ID = 1907
        private const val ACTION_REFRESH = "nexo.media.REFRESH"
        private const val ACTION_PAUSE = "nexo.media.PAUSE"
        private const val ACTION_STOP = "nexo.media.STOP"

        fun sync(context: Context) {
            val state = BrowserMediaController.snapshot()
            if (state == null || !state.playing || !BrowserPrefs.backgroundMedia(context)) {
                stop(context)
                return
            }
            val intent = Intent(context, MediaPlaybackService::class.java).setAction(ACTION_REFRESH)
            runCatching {
                if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent)
                else context.startService(intent)
            }.onFailure { error ->
                android.util.Log.w("NexoMedia", "No se pudo iniciar servicio multimedia", error)
            }
        }

        fun stop(context: Context) {
            runCatching {
                context.stopService(Intent(context, MediaPlaybackService::class.java))
            }
        }
    }
}
