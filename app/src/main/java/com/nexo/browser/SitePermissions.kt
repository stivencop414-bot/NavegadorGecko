package com.nexo.browser

import android.Manifest
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.net.Uri
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSession.PermissionDelegate
import org.mozilla.geckoview.GeckoSession.PermissionDelegate.ContentPermission
import org.mozilla.geckoview.GeckoSession.PermissionDelegate.MediaSource

/**
 * Permisos que piden los sitios. Sin este delegado GeckoView rechazaba
 * todo: ubicación, cámara y micrófono (videollamadas), contenido protegido
 * (DRM) y almacenamiento persistente.
 *
 * Cada solicitud se pregunta al usuario mostrando el dominio real; Gecko
 * recuerda la respuesta para ese sitio. Las notificaciones web se rechazan
 * porque Nexo aún no las muestra.
 */
object SitePermissions : PermissionDelegate {
    private val pending = mutableSetOf<Request>()
    private class Request(val session: GeckoSession, val deny: () -> Unit) {
        private val tab = TabManager.tabForSession(session)
        private val epoch = tab?.navigationEpoch
        private val url = tab?.url
        var dialog: AlertDialog? = null
        private var answered = false
        fun current(): Boolean = !answered && tab != null && TabManager.activeSession() === session &&
            TabManager.tabForSession(session) === tab && tab.navigationEpoch == epoch && tab.url == url
        fun finish(allow: Boolean, grant: () -> Unit = {}) {
            if (answered) return
            val accepted = allow && current()
            answered = true
            pending.remove(this)
            if (accepted) grant() else deny()
            dialog?.dismiss(); dialog = null
        }
    }
    fun cancel(session: GeckoSession) { pending.filter { it.session === session }.toList().forEach { it.finish(false) } }
    fun cancelAll() { pending.toList().forEach { it.finish(false) } }
    private fun request(session: GeckoSession, deny: () -> Unit): Request? {
        val value = Request(session, deny)
        if (!value.current()) { value.finish(false); return null }
        pending += value
        return value
    }

    private val locationPermissions = setOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION
    )

    private fun host(uri: String?): String =
        runCatching { Uri.parse(uri.orEmpty()).host }.getOrNull()
            ?.removePrefix("www.")
            ?.takeIf { it.isNotBlank() }
            ?: "Este sitio"

    override fun onAndroidPermissionsRequest(
        session: GeckoSession,
        permissions: Array<out String>?,
        callback: PermissionDelegate.Callback
    ) {
        val guard = request(session) { callback.reject() } ?: return
        val requested = permissions?.filterNotNull().orEmpty()
        val host = BrowserHosts.current()
        if (requested.isEmpty()) {
            guard.finish(true) { callback.grant() }
            return
        }
        if (host == null) {
            guard.finish(false)
            return
        }

        val activity = host.hostActivity
        val missing = requested.filter {
            activity.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            guard.finish(true) { callback.grant() }
            return
        }

        host.requestAndroidPermissions(missing.toTypedArray()) {
            val granted = requested.filter {
                activity.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
            }
            // La ubicación aproximada basta para la geolocalización web.
            val ok = granted.size == requested.size ||
                (requested.all { it in locationPermissions } && granted.isNotEmpty())
            if (ok) guard.finish(true) { callback.grant() } else guard.finish(false)
        }
    }

    override fun onContentPermissionRequest(
        session: GeckoSession,
        perm: ContentPermission
    ): GeckoResult<Int>? {
        val description = when (perm.permission) {
            PermissionDelegate.PERMISSION_GEOLOCATION -> "conocer tu ubicación"
            PermissionDelegate.PERMISSION_MEDIA_KEY_SYSTEM_ACCESS ->
                "reproducir contenido protegido (DRM), como películas o música de pago"
            PermissionDelegate.PERMISSION_XR -> "usar realidad virtual o aumentada"
            PermissionDelegate.PERMISSION_STORAGE_ACCESS ->
                "usar sus cookies mientras visitas ${host(perm.thirdPartyOrigin)}"
            PermissionDelegate.PERMISSION_LOCAL_DEVICE_ACCESS,
            PermissionDelegate.PERMISSION_LOCAL_NETWORK_ACCESS ->
                "conectarse a dispositivos de tu red local"

            PermissionDelegate.PERMISSION_PERSISTENT_STORAGE ->
                return GeckoResult.fromValue(
                    if (perm.privateMode) ContentPermission.VALUE_DENY else ContentPermission.VALUE_ALLOW
                )

            // Nexo no muestra notificaciones web: no prometer algo que no funciona.
            PermissionDelegate.PERMISSION_DESKTOP_NOTIFICATION ->
                return GeckoResult.fromValue(ContentPermission.VALUE_DENY)

            PermissionDelegate.PERMISSION_AUTOPLAY_AUDIBLE ->
                return GeckoResult.fromValue(
                    if (BrowserPrefs.autoplay(AppContext.get()) == BrowserPrefs.AUTOPLAY_ALLOW) ContentPermission.VALUE_ALLOW
                    else ContentPermission.VALUE_DENY
                )
            PermissionDelegate.PERMISSION_AUTOPLAY_INAUDIBLE ->
                return GeckoResult.fromValue(
                    if (BrowserPrefs.autoplay(AppContext.get()) == BrowserPrefs.AUTOPLAY_BLOCK_ALL) ContentPermission.VALUE_DENY
                    else ContentPermission.VALUE_ALLOW
                )

            // Rastreo y demás: comportamiento por defecto de Gecko.
            else -> return GeckoResult.fromValue(ContentPermission.VALUE_PROMPT)
        }

        val activity = BrowserHosts.current()?.hostActivity
            ?: return GeckoResult.fromValue(ContentPermission.VALUE_DENY)

        val result = GeckoResult<Int>()
        val guard = request(session) { result.complete(ContentPermission.VALUE_DENY) } ?: return result
        fun answer(value: Int) = guard.finish(value == ContentPermission.VALUE_ALLOW) { result.complete(value) }

        runCatching {
            guard.dialog = AlertDialog.Builder(activity)
                .setTitle("Permiso del sitio")
                .setMessage("${host(perm.uri)} quiere $description.")
                .setNegativeButton("Bloquear") { _, _ -> answer(ContentPermission.VALUE_DENY) }
                .setPositiveButton("Permitir") { _, _ -> answer(ContentPermission.VALUE_ALLOW) }
                .setOnDismissListener { answer(ContentPermission.VALUE_DENY) }
                .show()
        }.onFailure { answer(ContentPermission.VALUE_DENY) }

        return result
    }

    override fun onMediaPermissionRequest(
        session: GeckoSession,
        uri: String,
        video: Array<out MediaSource>?,
        audio: Array<out MediaSource>?,
        callback: PermissionDelegate.MediaCallback
    ) {
        val activity = BrowserHosts.current()?.hostActivity
        // Compartir pantalla no está soportado: solo cámara y micrófono.
        val camera = video?.firstOrNull { it.source == MediaSource.SOURCE_CAMERA }
        val microphone = audio?.firstOrNull { it.source == MediaSource.SOURCE_MICROPHONE }
        if (activity == null || (camera == null && microphone == null)) {
            callback.reject()
            return
        }

        val what = when {
            camera != null && microphone != null -> "usar tu cámara y tu micrófono"
            camera != null -> "usar tu cámara"
            else -> "usar tu micrófono"
        }

        val guard = request(session) { callback.reject() } ?: return
        runCatching {
            guard.dialog = AlertDialog.Builder(activity)
                .setTitle("Permiso del sitio")
                .setMessage("${host(uri)} quiere $what.")
                .setNegativeButton("Bloquear") { _, _ ->
                    guard.finish(false)
                }
                .setPositiveButton("Permitir") { _, _ ->
                    guard.finish(true) { callback.grant(camera, microphone) }
                }
                .setOnDismissListener { guard.finish(false) }
                .show()
        }.onFailure { guard.finish(false) }
    }
}
