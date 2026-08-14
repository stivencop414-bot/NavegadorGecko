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
import android.os.Handler
import android.os.IBinder
import android.os.Looper

class MediaPlaybackService : Service() {
    override fun onCreate() {
        super.onCreate()
        createChannel()
        instance = this
        startRequested = false

        /*
         * IMPORTANTE:
         * Android exige que cualquier Service iniciado mediante
         * startForegroundService() se promocione a foreground casi
         * inmediatamente. No esperamos a consultar de nuevo el estado
         * de YouTube porque puede cambiar durante Home/PiP.
         */
        promoteToForeground(
            buildBootstrapNotification()
        )
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {
        when (intent?.action) {
            ACTION_PAUSE ->
                BrowserMediaController.pauseCurrent()

            ACTION_STOP ->
                BrowserMediaController.stopCurrent()
        }

        refreshFromController()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        if (instance === this) {
            instance = null
        }
        startRequested = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun refreshFromController() {
        val state =
            BrowserMediaController.snapshotForService()

        if (
            state == null ||
            !BrowserPrefs.backgroundMedia(this)
        ) {
            stopForeground(Service.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        promoteToForeground(
            buildNotification(state)
        )
    }

    private fun promoteToForeground(
        notification: Notification
    ) {
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } else {
            startForeground(
                NOTIFICATION_ID,
                notification
            )
        }
    }

    private fun buildBootstrapNotification(): Notification =
        Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_nexo_logo)
            .setContentTitle("Nexo Browser")
            .setContentText("Preparando reproducción multimedia…")
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

    private fun buildNotification(
        state: BrowserMediaController.Snapshot
    ): Notification {
        val open = PendingIntent.getActivity(
            this,
            200,
            Intent(this, MainActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
            },
            PendingIntent.FLAG_UPDATE_CURRENT or
                PendingIntent.FLAG_IMMUTABLE
        )

        val pause = PendingIntent.getService(
            this,
            201,
            Intent(
                this,
                MediaPlaybackService::class.java
            ).setAction(ACTION_PAUSE),
            PendingIntent.FLAG_UPDATE_CURRENT or
                PendingIntent.FLAG_IMMUTABLE
        )

        val stop = PendingIntent.getService(
            this,
            202,
            Intent(
                this,
                MediaPlaybackService::class.java
            ).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or
                PendingIntent.FLAG_IMMUTABLE
        )

        val statusText =
            if (state.playing) {
                "Reproduciendo desde Nexo Browser"
            } else {
                "Manteniendo reproducción en segundo plano"
            }

        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_nexo_logo)
            .setContentTitle(state.title)
            .setContentText(statusText)
            .setContentIntent(open)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(
                Notification.Action.Builder(
                    android.R.drawable.ic_media_pause,
                    "Pausar",
                    pause
                ).build()
            )
            .addAction(
                Notification.Action.Builder(
                    android.R.drawable.ic_menu_close_clear_cancel,
                    "Detener",
                    stop
                ).build()
            )
            .setStyle(
                Notification.MediaStyle()
                    .setShowActionsInCompactView(0, 1)
            )
            .build()
    }

    private fun createChannel() {
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Reproducción multimedia",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description =
                        "Controles de audio y video de Nexo Browser"
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

        private val main = Handler(Looper.getMainLooper())

        @Volatile
        private var instance: MediaPlaybackService? = null

        @Volatile
        private var startRequested = false

        fun sync(context: Context) {
            val app = context.applicationContext
            val state =
                BrowserMediaController.snapshotForService()

            val enabled =
                BrowserPrefs.backgroundMedia(app)

            val current = instance

            if (state == null || !enabled) {
                if (current != null) {
                    main.post {
                        if (instance === current) {
                            current.refreshFromController()
                        }
                    }
                } else if (!startRequested) {
                    runCatching {
                        app.stopService(
                            Intent(
                                app,
                                MediaPlaybackService::class.java
                            )
                        )
                    }
                }
                return
            }

            if (current != null) {
                main.post {
                    if (instance === current) {
                        current.refreshFromController()
                    }
                }
                return
            }

            if (startRequested) {
                return
            }

            val intent = Intent(
                app,
                MediaPlaybackService::class.java
            ).setAction(ACTION_REFRESH)

            startRequested = true

            runCatching {
                if (Build.VERSION.SDK_INT >= 26) {
                    app.startForegroundService(intent)
                } else {
                    app.startService(intent)
                }
            }.onFailure { error ->
                startRequested = false
                android.util.Log.w(
                    "NexoMedia",
                    "No se pudo iniciar servicio multimedia",
                    error
                )
            }

            /*
             * Si el sistema rechazara silenciosamente la creación,
             * permitir un nuevo intento más adelante.
             */
            main.postDelayed(
                {
                    if (instance == null) {
                        startRequested = false
                    }
                },
                6_000L
            )
        }

        fun stop(context: Context) {
            val app = context.applicationContext
            val current = instance

            if (current != null) {
                main.post {
                    if (instance === current) {
                        current.stopForeground(
                            Service.STOP_FOREGROUND_REMOVE
                        )
                        current.stopSelf()
                    }
                }
            } else if (!startRequested) {
                runCatching {
                    app.stopService(
                        Intent(
                            app,
                            MediaPlaybackService::class.java
                        )
                    )
                }
            }
        }
    }
}
