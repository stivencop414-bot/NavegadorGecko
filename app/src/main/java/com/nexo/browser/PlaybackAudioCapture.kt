package com.nexo.browser

import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.*
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.*
import org.mozilla.geckoview.GeckoSession

/** Explicit audio-only MediaProjection consent. Never uses a microphone AudioSource. */
object PlaybackAudioCapture {
    const val REQUEST = 9844
    private data class Request(val session: GeckoSession, val token: Any, val at: Long = SystemClock.uptimeMillis())
    private var pending: Request? = null
    internal data class Grant(val session: GeckoSession, val token: Any, val data: Intent)
    private var grant: Grant? = null
    internal var service: PlaybackCaptureService? = null
    fun awaitingConsent(): Boolean = pending != null
    /** [reason]: la página no pudo dar su audio; se explica antes de proponer la captura de Android. */
    fun request(activity: MainActivity, reason: String? = null) {
        if (Build.VERSION.SDK_INT < 29) {
            ToastMessage.show(activity, "La captura de reproducción requiere Android 10 o posterior."); return
        }
        val session = TabManager.activeSession()
        val token = session?.let { LiveCaptions.captureToken(it) }
        if (session == null || token == null) {
            ToastMessage.show(activity, "Activa primero CC en la pestaña del video y vuelve a intentarlo."); return
        }
        if (LiveCaptions.usingDeviceAudio(session)) { ToastMessage.show(activity,"La captura de Android ya está activa. Apaga CC para detenerla."); return }
        if (BrowserPrefs.deepgramKey(activity).isBlank()) {
            ToastMessage.show(activity, "Configura Deepgram para reconocer el audio. Google o IA solo traducen texto."); return
        }
        // Una solicitud anterior sin respuesta (diálogo cerrado por Android) no bloquea para siempre.
        pending?.let { if (SystemClock.uptimeMillis() - it.at < 60_000L) return else pending = null }
        AlertDialog.Builder(activity).setTitle(if (reason != null) "¿Escuchar el video con Android?" else "Capturar el audio de Nexo")
            .setMessage((reason?.let { "No se pudo tomar el audio desde la página: $it\n\nAndroid sí puede escucharlo directamente. " } ?: "") +
                "Android pedirá permiso de captura y de grabación de audio. Nexo capturará solo su propia reproducción, no el micrófono ni otras apps. Puede incluir otras pestañas de Nexo que estén sonando: páusalas antes. Enviará ese audio a Deepgram para transcribirlo. Se detiene al apagar CC, cambiar de página/pestaña o salir de Nexo (salvo PiP). DRM y algunos reproductores pueden impedirlo.")
            .setNegativeButton(if (reason != null) "Ahora no" else "Cancelar", null)
            .setPositiveButton(if (reason != null) "Usar captura de Android" else "Continuar") { _, _ ->
                if (!LiveCaptions.matchesCapture(session, token) || TabManager.activeSession() !== session) {
                    ToastMessage.show(activity, "Los subtítulos de esta pestaña cambiaron. Vuelve a elegir la captura de Android desde CC."); return@setPositiveButton
                }
                pending = Request(session, token)
                activity.requestAndroidPermissions(arrayOf(Manifest.permission.RECORD_AUDIO)) { allowed ->
                    if (pending?.token !== token) return@requestAndroidPermissions
                    if (activity.isFinishing || activity.isDestroyed) { pending = null; return@requestAndroidPermissions }
                    if (!allowed) {
                        pending = null
                        ToastMessage.show(activity, "Sin el permiso de audio Android no deja capturar la reproducción. Actívalo en Ajustes de Android › Apps › Nexo › Permisos.")
                        return@requestAndroidPermissions
                    }
                    if (!LiveCaptions.matchesCapture(session, token) || TabManager.activeSession() !== session) {
                        pending = null
                        ToastMessage.show(activity, "Los subtítulos de esta pestaña cambiaron. Vuelve a elegir la captura de Android desde CC.")
                        return@requestAndroidPermissions
                    }
                    try {
                        val manager = activity.getSystemService(MediaProjectionManager::class.java)
                        // Android 14+: pantalla completa directamente. Con "Una sola app" el usuario
                        // podía elegir otra app y la captura quedaba sin el audio de Nexo.
                        val intent = if (Build.VERSION.SDK_INT >= 34)
                            manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
                        else manager.createScreenCaptureIntent()
                        activity.startActivityForResult(intent, REQUEST)
                    } catch (_: Exception) { pending = null; ToastMessage.show(activity,"Android no pudo abrir la autorización de captura.") }
                }
            }.show()
    }
    fun result(activity: Activity, resultCode: Int, data: Intent?) {
        val request = pending; pending = null
        if (request == null) return
        if (resultCode != Activity.RESULT_OK || data == null) {
            ToastMessage.show(activity, "Captura de Android cancelada."); return
        }
        if (!LiveCaptions.matchesCapture(request.session, request.token) || TabManager.activeSession() !== request.session) {
            ToastMessage.show(activity, "Los subtítulos de esta pestaña cambiaron mientras Android pedía permiso. Vuelve a intentarlo desde CC."); return
        }
        grant = Grant(request.session, request.token, data)
        try { activity.startForegroundService(Intent(activity, PlaybackCaptureService::class.java)) }
        catch (_: Exception) { grant = null; ToastMessage.show(activity,"Android no permitió iniciar la captura. Vuelve a intentarlo con Nexo visible.") }
    }
    internal fun takeGrant(): Grant? = grant.also { grant = null }
    fun cancel(session: GeckoSession) {
        if (pending?.session === session) pending = null
        if (grant?.session === session) grant = null
        service?.takeIf { it.session === session }?.finishCapture()
    }
}

private object ToastMessage {
    fun show(c: Context, text: String) = android.widget.Toast.makeText(c,text,android.widget.Toast.LENGTH_LONG).show()
}

/** Foreground lifetime is bound to one caption stream, not to a saved permission token. */
class PlaybackCaptureService : Service() {
    internal var session: GeckoSession? = null
        private set
    private var projection: MediaProjection? = null
    private var recorder: AudioRecord? = null
    @Volatile private var running = false
    private var closing = false
    private val main = Handler(Looper.getMainLooper())
    // Si el video está sonando (lo actualiza el hilo principal cada segundo).
    @Volatile private var mediaPlaying = true
    private val playbackWatch = object : Runnable {
        override fun run() {
            val current = session ?: return
            mediaPlaying = TabManager.tabForSession(current)?.let { BrowserMediaController.isPlaying(it.id) } ?: true
            if (running) main.postDelayed(this, 1000L)
        }
    }
    private val callback = object : MediaProjection.Callback() {
        override fun onStop() { fail("Android detuvo la autorización de captura. Puedes volver a activarla desde CC.") }
    }
    override fun onBind(intent: Intent?) = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "stop") {
            session?.let { LiveCaptions.stop(it) }
            finishCapture()
            return START_NOT_STICKY
        }
        val grant = PlaybackAudioCapture.takeGrant()
        // Arrancado con startForegroundService: Android cierra la app si el servicio se detiene
        // sin haber pasado a primer plano. Se promueve antes de cualquier comprobación.
        if (!running && !promote()) {
            grant?.let { LiveCaptions.onPageError(this, it.session, "android_capture", "Android no permitió iniciar la captura. Vuelve a intentarlo con Nexo visible.") }
            stopSelf()
            return START_NOT_STICKY
        }
        if (Build.VERSION.SDK_INT < 29 || grant == null || running || !LiveCaptions.matchesCapture(grant.session,grant.token) || TabManager.activeSession() !== grant.session) {
            if (!running) {
                if (grant != null && LiveCaptions.isActive(grant.session))
                    LiveCaptions.onPageError(this, grant.session, "android_capture", "La pestaña cambió mientras Android iniciaba la captura. Vuelve a activarla desde CC.")
                stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
            }
            return START_NOT_STICKY
        }
        if (closing) { LiveCaptions.onPageError(this,grant.session,"android_capture","La captura anterior se está cerrando. Vuelve a autorizar desde CC."); stopSelf(); return START_NOT_STICKY }
        PlaybackAudioCapture.service = this
        session = grant.session
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            fail("Android no permitió capturar audio. Autoriza el permiso desde CC.")
            return START_NOT_STICKY
        }
        try {
            val consent = getSystemService(MediaProjectionManager::class.java).getMediaProjection(Activity.RESULT_OK,grant.data) ?: error("Projection denied")
            projection = consent
            consent.registerCallback(callback, main)
            val config = AudioPlaybackCaptureConfiguration.Builder(consent)
                .addMatchingUid(Process.myUid())
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN).build()
            val format = AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(PlaybackPcm.SAMPLE_RATE).setChannelMask(AudioFormat.CHANNEL_IN_MONO).build()
            val min = AudioRecord.getMinBufferSize(PlaybackPcm.SAMPLE_RATE,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT)
            check(min > 0)
            val audio = AudioRecord.Builder().setAudioFormat(format).setBufferSizeInBytes(maxOf(min*2,16000))
                .setAudioPlaybackCaptureConfig(config).build()
            recorder = audio
            check(audio.state == AudioRecord.STATE_INITIALIZED)
            audio.startRecording()
            check(audio.recordingState == AudioRecord.RECORDSTATE_RECORDING)
            check(LiveCaptions.startDeviceAudio(grant.session,grant.token))
            running = true
            main.post(playbackWatch)
            Thread({
                val buffer = ByteArray(8000) // 250 ms, mono PCM16 at 16 kHz
                val gain = PlaybackPcm.Gain()
                var signal = false
                var playingSilenceMs = 0L
                var quietReported = false
                // Diagnóstico en pantalla: pico del último segundo (antes de la ganancia).
                var levelPeak = 0
                var levelBytes = 0
                try {
                    while (running) {
                        val size = audio.read(buffer,0,buffer.size,AudioRecord.READ_BLOCKING)
                        if (size < 0) error("AudioRecord read failed")
                        if (size == 0) continue
                        val bytes = buffer.copyOf(size)
                        levelPeak = maxOf(levelPeak, PlaybackPcm.peak(bytes))
                        levelBytes += size
                        if (levelBytes >= PlaybackPcm.SAMPLE_RATE * 2) {
                            val peak = levelPeak; val playing = mediaPlaying
                            levelPeak = 0; levelBytes = 0
                            main.post { if (running && LiveCaptions.matchesCapture(grant.session,grant.token)) LiveCaptions.onDeviceLevel(grant.session, peak, playing) }
                        }
                        val heard = PlaybackPcm.hasSignal(bytes)
                        signal = signal || heard
                        if (heard) gain.apply(bytes)
                        main.post {
                            if (running && LiveCaptions.matchesCapture(grant.session,grant.token)) LiveCaptions.onDeviceAudio(grant.session,bytes)
                        }
                        if (signal) continue
                        // El silencio solo cuenta con el video sonando: en pausa no se da por perdido.
                        if (mediaPlaying) playingSilenceMs += size * 1000L / (PlaybackPcm.SAMPLE_RATE * 2)
                        if (!quietReported && playingSilenceMs > 6000) {
                            quietReported = true
                            main.post { if (running) LiveCaptions.onStatus(grant.session,"device_quiet") }
                        }
                        if (playingSilenceMs > 25000) {
                            main.post { if (running) fail("Android solo recibió silencio aunque el video suena (nivel 0 %). El video puede estar silenciado, protegido (DRM) o sonar por una vía que Android no deja capturar. Prueba a adelantarlo unos segundos, otro servidor o los subtítulos instantáneos de Android.") }; break
                        }
                    }
                } catch (_: Exception) { main.post { if(running) fail("Android no pudo seguir capturando el audio del reproductor.") } }
            },"NexoPlaybackCapture").start()
        } catch (_: Exception) { fail("No se pudo iniciar la captura de reproducción. Comprueba permisos y vuelve a autorizar con Nexo visible.") }
        return START_NOT_STICKY
    }
    /** Notificación de captura con "Detener"; false si Android no deja pasar a primer plano. */
    private fun promote(): Boolean = runCatching {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("caption-capture", "Captura para subtítulos", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(this, 0, Intent(this,PlaybackCaptureService::class.java).setAction("stop"), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = Notification.Builder(this,"caption-capture")
            .setSmallIcon(R.drawable.ic_stat_nexo).setContentTitle("Nexo · captura de audio activa")
            .setContentText("Solo reproducción de Nexo · transcripción con Deepgram")
            .setOngoing(true).addAction(Notification.Action.Builder(null,"Detener",stop).build()).build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(947,notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        else startForeground(947,notification)
    }.isSuccess

    private fun fail(message: String) {
        if (closing) return // ignore a revocation already queued before explicit stop
        val current = session
        finishCapture()
        if (current != null) LiveCaptions.onPageError(this,current,"android_capture",message)
    }
    internal fun finishCapture() {
        if (closing) return
        closing = true; running = false
        main.removeCallbacks(playbackWatch)
        val audio = recorder; recorder = null
        runCatching { audio?.stop() }; runCatching { audio?.release() }
        val consent = projection; projection = null
        runCatching { consent?.unregisterCallback(callback) }; runCatching { consent?.stop() }
        if (PlaybackAudioCapture.service === this) PlaybackAudioCapture.service = null
        stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
    }
    override fun onDestroy() { finishCapture(); super.onDestroy() }
}
