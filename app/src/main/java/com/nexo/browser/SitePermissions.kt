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
        val requested = permissions?.filterNotNull().orEmpty()
        val host = BrowserHosts.current()
        if (requested.isEmpty()) {
            callback.grant()
            return
        }
        if (host == null) {
            callback.reject()
            return
        }

        val activity = host.hostActivity
        val missing = requested.filter {
            activity.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            callback.grant()
            return
        }

        host.requestAndroidPermissions(missing.toTypedArray()) {
            val granted = requested.filter {
                activity.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
            }
            // La ubicación aproximada basta para la geolocalización web.
            val ok = granted.size == requested.size ||
                (requested.all { it in locationPermissions } && granted.isNotEmpty())
            if (ok) callback.grant() else callback.reject()
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
        var answered = false
        fun answer(value: Int) {
            if (answered) return
            answered = true
            result.complete(value)
        }

        runCatching {
            AlertDialog.Builder(activity)
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

        var answered = false
        runCatching {
            AlertDialog.Builder(activity)
                .setTitle("Permiso del sitio")
                .setMessage("${host(uri)} quiere $what.")
                .setNegativeButton("Bloquear") { _, _ ->
                    answered = true
                    callback.reject()
                }
                .setPositiveButton("Permitir") { _, _ ->
                    answered = true
                    callback.grant(camera, microphone)
                }
                .setOnDismissListener { if (!answered) callback.reject() }
                .show()
        }.onFailure { callback.reject() }
    }
}
