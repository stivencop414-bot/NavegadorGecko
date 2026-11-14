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

    /**
     * Solicitud abierta, ligada a su pestaña, al sitio principal y a la
     * navegación en la que se pidió. Si el usuario cambia de página o de
     * pestaña, se cierra (rechazada); un "Permitir" tardío ya no vale.
     */
    private class Pending(val session: GeckoSession, val epoch: Long, val origin: String, val dialog: AlertDialog)
    private val pending = mutableListOf<Pending>()

    private fun topOrigin(session: GeckoSession): String? =
        TabManager.tabForSession(session)?.url?.let { PermissionScope.origin(it) }

    /** La pestaña sigue en la misma página que pidió el permiso y es la visible. */
    private fun stillValid(session: GeckoSession, epoch: Long, origin: String): Boolean {
        val tab = TabManager.tabForSession(session) ?: return false
        return TabManager.activeSession() === session && tab.navigationEpoch == epoch &&
            PermissionScope.origin(tab.url) == origin
    }

    /** Al abandonar la página o la pestaña: cierra (rechaza) sus solicitudes pendientes. */
    fun cancelFor(session: GeckoSession) {
        pending.filter { it.session === session }.forEach { runCatching { it.dialog.dismiss() } }
        pending.removeAll { it.session === session }
    }

    /** Muestra el diálogo registrado; [onAllow]/[onDeny] solo se llaman una vez. */
    private fun ask(activity: android.app.Activity, session: GeckoSession, message: String, onAllow: () -> Unit, onDeny: () -> Unit): Boolean {
        val tab = TabManager.tabForSession(session) ?: return false
        val origin = topOrigin(session) ?: return false
        if (TabManager.activeSession() !== session) return false
        val epoch = tab.navigationEpoch
        var answered = false
        lateinit var entry: Pending
        val dialog = AlertDialog.Builder(activity)
            .setTitle("Permiso del sitio")
            .setMessage(message)
            .setNegativeButton("Bloquear") { _, _ -> if (!answered) { answered = true; onDeny() } }
            .setPositiveButton("Permitir") { _, _ ->
                if (answered) return@setPositiveButton
                answered = true
                if (stillValid(session, epoch, origin)) onAllow() else onDeny()
            }
            .setOnDismissListener { pending.remove(entry); if (!answered) { answered = true; onDeny() } }
            .create()
        entry = Pending(session, epoch, origin, dialog)
        pending += entry
        dialog.show()
        return true
    }

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
        // Solo para la pestaña visible: una pestaña de fondo no abre permisos de Android.
        if (host == null || TabManager.activeSession() !== session) {
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
        val shown = runCatching {
            ask(activity, session, "${host(perm.uri)} quiere $description.",
                onAllow = { result.complete(ContentPermission.VALUE_ALLOW) },
                onDeny = { result.complete(ContentPermission.VALUE_DENY) })
        }.getOrDefault(false)
        if (!shown) result.complete(ContentPermission.VALUE_DENY)
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

        val shown = runCatching {
            ask(activity, session, "${host(uri)} quiere $what.",
                onAllow = { callback.grant(camera, microphone) },
                onDeny = { callback.reject() })
        }.getOrDefault(false)
        if (!shown) callback.reject()
    }
}
