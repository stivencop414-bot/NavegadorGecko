package com.nexo.browser

import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.*
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import org.mozilla.geckoview.GeckoSession

/** Explicit audio-only MediaProjection consent. Never uses a microphone AudioSource. */
object PlaybackAudioCapture {
    const val REQUEST = 9844
    private data class Request(val session: GeckoSession, val token: Any, val data: Intent? = null)
    private var pending: Request? = null
    internal data class Grant(val session: GeckoSession, val token: Any, val data: Intent)
    private var grant: Grant? = null
    internal var service: PlaybackCaptureService? = null
    fun awaitingConsent(): Boolean = pending != null
    fun request(activity: MainActivity) {
        if (Build.VERSION.SDK_INT < 29) {
            ToastMessage.show(activity, "La captura de reproducción requiere Android 10 o posterior."); return
        }
        val session = TabManager.activeSession() ?: return
        val token = LiveCaptions.captureToken(session) ?: return
        if (LiveCaptions.usingDeviceAudio(session)) { ToastMessage.show(activity,"La captura de Android ya está activa. Apaga CC para detenerla."); return }
        if (BrowserPrefs.deepgramKey(activity).isBlank()) {
            ToastMessage.show(activity, "Configura Deepgram para reconocer el audio. Google o IA solo traducen texto."); return
        }
        if (pending != null) return
        AlertDialog.Builder(activity).setTitle("Capturar el audio de Nexo")
            .setMessage("Android pedirá permiso de captura y de grabación de audio. Nexo capturará solo su propia reproducción, no el micrófono ni otras apps. Puede incluir otras pestañas de Nexo que estén sonando: páusalas antes. Enviará ese audio a Deepgram para transcribirlo. Se detiene al apagar CC, cambiar de página/pestaña o salir de Nexo (salvo PiP). DRM y algunos reproductores pueden impedirlo.")
            .setNegativeButton("Cancelar", null).setPositiveButton("Continuar") { _, _ ->
                if (!LiveCaptions.matchesCapture(session, token) || TabManager.activeSession() !== session) return@setPositiveButton
                pending = Request(session, token)
                activity.requestAndroidPermissions(arrayOf(Manifest.permission.RECORD_AUDIO)) { allowed ->
                    if (pending?.token !== token) return@requestAndroidPermissions
                    if (!allowed || !LiveCaptions.matchesCapture(session, token) || TabManager.activeSession() !== session || activity.isFinishing || activity.isDestroyed) {
                        pending = null; return@requestAndroidPermissions
                    }
                    try {
                        val manager = activity.getSystemService(MediaProjectionManager::class.java)
                        activity.startActivityForResult(manager.createScreenCaptureIntent(), REQUEST)
                    } catch (_: Exception) { pending = null; ToastMessage.show(activity,"Android no pudo abrir la autorización de captura.") }
                }
            }.show()
    }
    fun result(activity: Activity, resultCode: Int, data: Intent?) {
        val request = pending; pending = null
        if (resultCode != Activity.RESULT_OK || data == null || request == null ||
            !LiveCaptions.matchesCapture(request.session, request.token) || TabManager.activeSession() !== request.session) return
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
        if (Build.VERSION.SDK_INT < 29 || grant == null || running || !LiveCaptions.matchesCapture(grant.session,grant.token) || TabManager.activeSession() !== grant.session) {
            if (!running) stopSelf()
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
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel("caption-capture", "Captura para subtítulos", NotificationManager.IMPORTANCE_LOW))
            val stop = PendingIntent.getService(this, 0, Intent(this,PlaybackCaptureService::class.java).setAction("stop"), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val notification = Notification.Builder(this,"caption-capture")
                .setSmallIcon(R.drawable.ic_nav_reload).setContentTitle("Nexo · captura de audio activa")
                .setContentText("Solo reproducción de Nexo · transcripción con Deepgram")
                .setOngoing(true).addAction(Notification.Action.Builder(null,"Detener",stop).build()).build()
            startForeground(947,notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
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
            Thread({
                val buffer = ByteArray(8000) // 250 ms, mono PCM16 at 16 kHz
                var signal = false
                val started = SystemClock.elapsedRealtime()
                try {
                    while (running) {
                        val size = audio.read(buffer,0,buffer.size,AudioRecord.READ_BLOCKING)
                        if (size < 0) error("AudioRecord read failed")
                        if (size == 0) continue
                        val bytes = buffer.copyOf(size)
                        signal = signal || PlaybackPcm.hasSignal(bytes)
                        main.post {
                            if (running && LiveCaptions.matchesCapture(grant.session,grant.token)) LiveCaptions.onDeviceAudio(grant.session,bytes)
                        }
                        if (!signal && SystemClock.elapsedRealtime()-started > 15000) {
                            main.post { if (running) fail("Android solo entrega silencio. Comprueba que el video suene; el reproductor, su proceso o DRM pueden impedir la captura.") }; break
                        }
                    }
                } catch (_: Exception) { main.post { if(running) fail("Android no pudo seguir capturando el audio del reproductor.") } }
            },"NexoPlaybackCapture").start()
        } catch (_: Exception) { fail("No se pudo iniciar la captura de reproducción. Comprueba permisos y vuelve a autorizar con Nexo visible.") }
        return START_NOT_STICKY
    }
    private fun fail(message: String) {
        if (closing) return // ignore a revocation already queued before explicit stop
        val current = session
        finishCapture()
        if (current != null) LiveCaptions.onPageError(this,current,"android_capture",message)
    }
    internal fun finishCapture() {
        if (closing) return
        closing = true; running = false
        val audio = recorder; recorder = null
        runCatching { audio?.stop() }; runCatching { audio?.release() }
        val consent = projection; projection = null
        runCatching { consent?.unregisterCallback(callback) }; runCatching { consent?.stop() }
        if (PlaybackAudioCapture.service === this) PlaybackAudioCapture.service = null
        stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
    }
    override fun onDestroy() { finishCapture(); super.onDestroy() }
}
