package com.nexo.browser

import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.WebExtension

/** A local extension page binds the native session before any target network request. */
object TabRouting {
    const val ID = "nexo_network@com.ejemplo.navegador"
    private val main = Handler(Looper.getMainLooper())
    private var extension: GeckoResult<WebExtension>? = null
    private val pending = java.util.WeakHashMap<GeckoSession, Boolean>()
    private val failed = java.util.WeakHashMap<GeckoSession, Boolean>()
    private val timeouts = java.util.WeakHashMap<GeckoSession, Runnable>()
    private val awaiting = java.util.WeakHashMap<GeckoSession, Boolean>()
    private val bootstrapUrls = java.util.WeakHashMap<GeckoSession, String>()
    private val purge = java.util.WeakHashMap<GeckoSession, Boolean>()
    fun initialize() {
        if(extension != null) return
        val result=GeckoResult<WebExtension>();extension=result
        val controller=GeckoRuntimeHolder.get(AppContext.get()).webExtensionController
        controller.ensureBuiltIn("resource://android/assets/extensions/nexo_network/",ID).accept({ installed ->
            if(installed == null) { extension=null;result.completeExceptionally(IllegalStateException("No se pudo cargar la red interna.")) }
            else if(installed.metaData.allowedInPrivateBrowsing) result.complete(installed)
            // Los componentes integrados suelen estar ya permitidos en privado; si Gecko
            // rechaza el cambio se sigue con el componente tal cual (el arranque de la
            // pestaña lo comprobará y, si falla, actúa el tiempo límite).
            else controller.setAllowedInPrivateBrowsing(installed,true).accept(
                { allowed -> result.complete(allowed ?: installed) },
                { error -> android.util.Log.w("NexoNetwork", "Permiso en privado", error); result.complete(installed) })
        },{ error -> extension=null;result.completeExceptionally(error ?: IllegalStateException("No se pudo instalar el componente de red.")) })
    }

    /**
     * Burner sin proxy ni simulaciones: el aislamiento lo da Gecko (contexto
     * propio y modo privado). Si el componente interno falla, solo se pierde la
     * variación experimental de huella; la pestaña no se bloquea.
     */
    private fun routeOptional(tab: BrowserTab): Boolean {
        val profile = tab.networkProfile
        return tab.burnerId != null &&
            (profile == null || (profile.type == "direct" && profile.latency == 0 && profile.latitude == null))
    }
    fun blocked(session: GeckoSession) = failed.containsKey(session)
    fun preparing(session: GeckoSession) = pending.containsKey(session) || failed.containsKey(session)
    fun synthetic(session: GeckoSession) = preparing(session) || awaiting.containsKey(session)
    fun reserve(session: GeckoSession) { pending[session] = true; awaiting[session] = true }
    fun bootstrap(session: GeckoSession, url: String) = !failed.containsKey(session) && bootstrapUrls[session] == url
    fun targetStarted(session: GeckoSession) { awaiting.remove(session) }
    fun targetFinished(session: GeckoSession) { if(purge.remove(session) == true) session.purgeHistory() }
    fun forget(session: GeckoSession) { timeouts.remove(session)?.let { main.removeCallbacks(it) }; failed.remove(session); pending.remove(session); awaiting.remove(session); bootstrapUrls.remove(session); purge.remove(session) }
    fun prepare(tab: BrowserTab, session: GeckoSession, preserveHistory: Boolean = false, ready: () -> Unit) {
        if(tab.networkProfile == null && tab.burnerId == null) { ready(); return }
        initialize(); reserve(session); tab.canGoBack=false;tab.canGoForward=false; purge[session] = !preserveHistory; tab.networkState = "Preparando ruta"
        fun fail() {
            if(pending.remove(session) != null && tab.session === session) {
                timeouts.remove(session)?.let { main.removeCallbacks(it) }
                if (routeOptional(tab)) {
                    awaiting[session] = true
                    tab.networkState = "Burner aislado"
                    TabManager.notifyMessage("Burner abierto con cookies y datos aislados. La variación de huella no se pudo activar.")
                    ready()
                    return
                }
                failed[session] = true
                tab.networkState = "Ruta bloqueada"; tab.isLoading = false
                TabManager.notifyMessage("No se pudo preparar la red. No se abrió la página por conexión directa. Revisa la ruta de la pestaña.")
            }
        }
        val timeout=Runnable { if(pending.containsKey(session)) fail() }
        // Un Burner sin proxy no espera tanto: su página abre aunque falle el componente.
        timeouts[session]=timeout;main.postDelayed(timeout,if (routeOptional(tab)) 5000L else 15000L)
        extension?.accept({ ext ->
            if(ext == null || !pending.containsKey(session) || tab.session !== session) return@accept
            val bootstrap = ext.metaData.baseUrl + "bootstrap.html"
            bootstrapUrls[session] = bootstrap
            session.webExtensionController.setMessageDelegate(ext,object : WebExtension.MessageDelegate {
                override fun onMessage(app: String, message: Any, sender: WebExtension.MessageSender): GeckoResult<Any>? {
                    if(app != "nexo_network" || sender.webExtension.id != ID || sender.session !== session || !sender.isTopLevel() || sender.url != bootstrap || !pending.containsKey(session)) return null
                    val data = message as? JSONObject ?: return null
                    if(data.optString("phase") == "profile") return GeckoResult.fromValue((tab.networkProfile ?: TabNetworkProfile()).json()
                        .put("burner",tab.burnerId != null).put("seed",tab.burnerSeed.toLong() and 0xffffffffL))
                    if(data.optString("phase") == "ready") {
                        if(data.optBoolean("ok")) main.post {
                            if(pending.remove(session) != null && tab.session === session) {
                                timeouts.remove(session)?.let { main.removeCallbacks(it) }
                                tab.networkState = "Configuración aplicada"; ready()
                            }
                        } else fail()
                        return GeckoResult.fromValue(JSONObject().put("ok",true))
                    }
                    return null
                }
            },"nexo_network")
            session.loadUri(bootstrap)
        },{ fail() })
    }
}
