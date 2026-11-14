package com.nexo.browser

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.WebResponse
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

object TabManager {
    private const val PRIVATE_LIVE_LIMIT = 8
    interface Listener {
        fun onActiveTabChanged(tab: BrowserTab, session: GeckoSession)
        fun onTabChanged(tab: BrowserTab)
        fun onProgress(tab: BrowserTab, progress: Int, loading: Boolean)
        fun onTabCountChanged(count: Int)
        fun onMessage(message: String)
        fun onContextElement(element: GeckoSession.ContentDelegate.ContextElement)

        /** Un sitio pidió (o dejó) pantalla completa, p. ej. el botón de YouTube. */
        fun onFullScreenChanged(tab: BrowserTab, fullScreen: Boolean) = Unit
    }

    private val tabs = CopyOnWriteArrayList<BrowserTab>()
    private var appContext: Context? = null
    private var listener: Listener? = null
    private var initialized = false
    private var activeId: String? = null
    private val activationHistory = java.util.ArrayDeque<String>()
    private val crashTimes = mutableMapOf<String, java.util.ArrayDeque<Long>>()

    fun initialize(context: Context) {
        appContext = context.applicationContext
        AppContext.initialize(context)
        ExtensionManager.initialize(context)
        TranslatorManager.initialize(context)
        TabRouting.initialize()

        if (!initialized) {
            initialized = true
            restoreOrCreate()
        }

        notifyActive()
    }

    fun attach(newListener: Listener?) {
        listener = newListener
        if (newListener != null && initialized) {
            notifyActive()
            newListener.onTabCountChanged(tabs.size)
        }
    }

    fun allTabs(): List<BrowserTab> = tabs.toList()

    fun activeTab(): BrowserTab? =
        tabs.firstOrNull { it.id == activeId } ?: tabs.firstOrNull()

    fun activeSession(): GeckoSession? = activeTab()?.session

    fun liveSessions(): List<GeckoSession> =
        tabs.mapNotNull { it.session }

    fun tabForSession(session: GeckoSession): BrowserTab? =
        tabs.firstOrNull { it.session === session }

    private fun isSafeAppNavigationUrl(url: String): Boolean =
        UrlSafety.isAppNavigable(url)

    private fun normalizeMobileUrl(
        url: String,
        desktopMode: Boolean
    ): String {
        if (desktopMode) return url

        val parsed =
            runCatching { Uri.parse(url) }
                .getOrNull()
                ?: return url

        val host =
            parsed.host
                ?.lowercase()
                ?: return url

        val youtube =
            host == "youtube.com" ||
                host == "www.youtube.com" ||
                host == "m.youtube.com"

        if (!youtube) return url

        val builder =
            parsed.buildUpon()
                .authority("m.youtube.com")
                .clearQuery()

        parsed.queryParameterNames
            .forEach { name ->
                parsed.getQueryParameters(name)
                    .forEach { value ->
                        val desktopFlag =
                            name.equals(
                                "app",
                                ignoreCase = true
                            ) &&
                            value.equals(
                                "desktop",
                                ignoreCase = true
                            )

                        if (!desktopFlag) {
                            builder.appendQueryParameter(
                                name,
                                value
                            )
                        }
                    }
            }

        return builder.build().toString()
    }

    fun createTab(
        url: String,
        isPrivate: Boolean = false,
        activate: Boolean = true,
        groupName: String? = null,
        burnerId: String? = null,
        networkProfile: TabNetworkProfile? = null
    ): BrowserTab {
        val initialUrl =
            if (isSafeAppNavigationUrl(url)) {
                url
            } else {
                listener?.onMessage("Nexo bloqueó un esquema de URL no permitido.")
                BrowserPrefs.homePage(requireContext())
            }

        val tab = BrowserTab(
            url = initialUrl,
            title = if (isPrivate) "Pestaña privada" else "Nueva pestaña",
            isPrivate = isPrivate || burnerId != null,
            desktopMode = appContext?.let { BrowserPrefs.desktopDefault(it) } == true,
            groupName = TabGroupRules.name(groupName),
            burnerId = burnerId,
            networkProfile = networkProfile
        )

        tabs += tab
        listener?.onTabCountChanged(tabs.size)
        persist()

        if (activate) switchTo(tab.id)
        return tab
    }

    /** Pestaña normal traída de la app anterior: se añade al final, sin abrirla hasta que se use. */
    fun importTab(url: String, title: String, groupName: String?, desktopMode: Boolean) {
        if (!UrlSafety.isWebUrl(url)) return
        tabs += BrowserTab(
            url = url,
            title = title.ifBlank { url },
            groupName = TabGroupRules.name(groupName),
            desktopMode = desktopMode
        )
        listener?.onTabCountChanged(tabs.size)
        persist()
    }

    fun applyNetwork(id: String, profile: TabNetworkProfile?) {
        val tab = tabs.firstOrNull { it.id == id } ?: return
        tab.networkProfile = profile?.validated(); tab.networkState = "Preparando ruta"
        tab.session?.let { closeSession(it) }; tab.session = null
        BrowserMediaController.removeTab(tab.id)
        if(tab.id == activeId) switchTo(tab.id,loadUrl=true,rememberPrevious=false)
        persist()
    }

    /** Coloca [id] junto a [targetId] (antes o después): reordenar arrastrando en el selector. */
    fun moveTab(id: String, targetId: String, after: Boolean) {
        if (id == targetId) return
        val tab = tabs.firstOrNull { it.id == id } ?: return
        if (tabs.none { it.id == targetId }) return
        tabs.remove(tab)
        val index = tabs.indexOfFirst { it.id == targetId }
        tabs.add(if (after) index + 1 else index, tab)
        persist()
    }

    fun moveToGroup(id: String, name: String?) {
        tabs.firstOrNull { it.id == id }?.let {
            it.groupName = TabGroupRules.name(name); listener?.onTabChanged(it); persist()
        }
    }

    fun renameGroup(old: String, name: String?, privateMode: Boolean) {
        tabs.filter { it.isPrivate == privateMode && it.groupName == old }.forEach { moveToGroup(it.id, name) }
    }

    fun openOrSelectTab(url: String) {
        val privateMode = activeTab()?.isPrivate == true
        val existing = tabs.firstOrNull { it.url == url && it.isPrivate == privateMode }
        if (existing != null) switchTo(existing.id) else createTab(url, privateMode, true)
    }

    fun createSessionForExtension(
        url: String,
        activate: Boolean
    ): GeckoSession {
        val tab = BrowserTab(
            id = UUID.randomUUID().toString(),
            url = if (UrlSafety.isExtensionTabUrl(url)) url else "about:blank",
            title = "Extensión",
            groupName = activeTab()?.groupName,
            burnerId = activeTab()?.burnerId,
            burnerSeed = activeTab()?.burnerSeed ?: java.security.SecureRandom().nextInt(),
            networkProfile = activeTab()?.networkProfile,
            isPrivate = activeTab()?.isPrivate ?: false
        )

        tabs += tab
        listener?.onTabCountChanged(tabs.size)

        val session = ensureSession(tab, loadUrl = false)

        if (activate) {
            switchTo(tab.id, loadUrl = false)
        } else {
            session.setActive(false)

            ExtensionManager.setTabActive(
                session,
                false
            )
        }

        persist()
        return session
    }

    fun switchTo(
        id: String,
        loadUrl: Boolean = true,
        rememberPrevious: Boolean = true
    ) {
        val target = tabs.firstOrNull { it.id == id } ?: return
        val old = activeTab()

        if (old?.id != target.id) {
            // No dejar una pestaña en segundo plano en pantalla completa.
            if (old?.isFullScreen == true) {
                old.isFullScreen = false
                runCatching { old.session?.exitFullScreen() }
            }

            old?.session?.let { session ->
                SitePermissions.cancel(session)
                val backgroundEnabled = BrowserPrefs.backgroundMedia(requireContext())
                val keepMediaAlive =
                    backgroundEnabled &&
                        BrowserMediaController.shouldKeepAlive(old.id, 8_000L)

                session.settings.setSuspendMediaWhenInactive(!backgroundEnabled)
                session.setFocused(false)
                ExtensionManager.setTabActive(session, false)
                session.setPriorityHint(
                    if (keepMediaAlive) GeckoSession.PRIORITY_HIGH
                    else GeckoSession.PRIORITY_DEFAULT
                )
                session.setActive(keepMediaAlive)
            }

            if (rememberPrevious && old != null) {
                activationHistory.remove(old.id)
                activationHistory.addLast(old.id)

                while (activationHistory.size > 30) {
                    activationHistory.removeFirst()
                }
            }
        }

        activeId = target.id
        target.lastUsed = System.currentTimeMillis()

        val session = ensureSession(target, loadUrl)
        session.setPriorityHint(GeckoSession.PRIORITY_HIGH)
        session.setActive(true)
        session.setFocused(true)

        ExtensionManager.setTabActive(
            session,
            true
        )

        listener?.onActiveTabChanged(target, session)
        listener?.onTabChanged(target)
        listener?.onTabCountChanged(tabs.size)

        trimSessions()
        persist()
    }

    fun activateBySession(session: GeckoSession) {
        tabForSession(session)?.let { switchTo(it.id, loadUrl = false) }
    }

    fun closeBySession(session: GeckoSession) {
        tabForSession(session)?.let { closeTab(it.id) }
    }

    /** Pestaña cerrada que se puede reabrir (Deshacer en el selector o desde el menú). */
    class ClosedTab(
        val id: String,
        val url: String,
        val title: String,
        val isPrivate: Boolean,
        val groupName: String?,
        val desktopMode: Boolean,
        val sessionState: String?,
        val networkProfile: TabNetworkProfile?,
        val index: Int,
        val wasActive: Boolean,
        val preview: android.graphics.Bitmap?,
        val closedAt: Long = android.os.SystemClock.elapsedRealtime()
    )

    private val recentlyClosed = java.util.ArrayDeque<ClosedTab>()
    private const val MAX_CLOSED = 10
    /** Las privadas solo se pueden reabrir durante unos segundos (para deshacer). */
    private const val PRIVATE_UNDO_MS = 15_000L

    private fun pruneClosed() {
        val now = android.os.SystemClock.elapsedRealtime()
        recentlyClosed.removeIf { it.isPrivate && now - it.closedAt > PRIVATE_UNDO_MS }
        while (recentlyClosed.size > MAX_CLOSED) recentlyClosed.removeFirst()
    }

    fun lastClosed(): ClosedTab? {
        pruneClosed()
        return recentlyClosed.peekLast()
    }

    /** Reabre la última pestaña cerrada (o [id]) con su historial cuando se conservó. */
    fun reopenClosed(id: String? = null, activate: Boolean = true): BrowserTab? {
        pruneClosed()
        val entry = (if (id == null) recentlyClosed.peekLast() else recentlyClosed.lastOrNull { it.id == id }) ?: return null
        recentlyClosed.remove(entry)
        val tab = BrowserTab(
            id = entry.id.takeIf { candidate -> tabs.none { it.id == candidate } } ?: UUID.randomUUID().toString(),
            url = entry.url,
            title = entry.title,
            isPrivate = entry.isPrivate,
            groupName = entry.groupName,
            desktopMode = entry.desktopMode,
            networkProfile = entry.networkProfile,
            sessionState = entry.sessionState
        )
        tabs.add(entry.index.coerceIn(0, tabs.size), tab)
        entry.preview?.takeIf { !entry.isPrivate }?.let { TabPreviewStore.restore(requireContext(), tab.id, it) }
        listener?.onTabCountChanged(tabs.size)
        persist()
        if (activate || entry.wasActive) switchTo(tab.id)
        return tab
    }

    fun closeTab(id: String) {
        val index = tabs.indexOfFirst { it.id == id }
        if (index < 0) return

        val removed = tabs[index]
        val wasActive = removed.id == activeId
        // Burner no se reabre: al cerrar su última pestaña se borran sus datos.
        if (removed.burnerId == null && !BrowserPrefs.isNexoHome(removed.url) && UrlSafety.isAppNavigable(removed.url)) {
            recentlyClosed.addLast(ClosedTab(
                removed.id, removed.url, removed.title, removed.isPrivate, removed.groupName,
                removed.desktopMode, removed.sessionState.takeUnless { removed.isPrivate },
                removed.networkProfile?.takeIf { it.type != "invalid" }, index, wasActive,
                if (removed.isPrivate) null else TabPreviewStore.cached(removed.id)
            ))
            pruneClosed()
        }
        activationHistory.remove(id)
        crashTimes.remove(id)

        removed.session?.let { session ->
            runCatching {
                ExtensionManager.setTabActive(
                    session,
                    false
                )
            }
            closeSession(session)
        }
        removed.session = null
        BrowserMediaController.removeTab(removed.id)
        tabs.remove(removed)
        removed.burnerId?.let { id ->
            if (tabs.none { it.burnerId == id }) {
                GeckoRuntimeHolder.get(requireContext()).storageController.clearDataForSessionContext(id)
                BurnerMailbox.close(id)
            }
        }
        if (!removed.isPrivate) TabPreviewStore.remove(requireContext(), removed.id)

        if (tabs.isEmpty()) {
            val c = requireContext()
            createTab(BrowserPrefs.homePage(c), false, true)
            return
        }

        if (wasActive) {
            // Volver a la pestaña usada justo antes (p. ej. la que abrió esta
            // ventana); si no hay, a la vecina.
            val previous = activationHistory.lastOrNull { id -> tabs.any { it.id == id } }
            val next = previous?.let { id -> tabs.firstOrNull { it.id == id } }
                ?: tabs[index.coerceAtMost(tabs.lastIndex)]
            activationHistory.remove(next.id)
            switchTo(next.id, rememberPrevious = false)
        } else {
            listener?.onTabCountChanged(tabs.size)
            persist()
        }
    }

    fun navigate(url: String) {
        val tab = activeTab() ?: return

        if (!isSafeAppNavigationUrl(url)) {
            listener?.onMessage("Nexo bloqueó un esquema de URL no permitido.")
            return
        }

        val target =
            normalizeMobileUrl(
                url,
                tab.desktopMode
            )

        tab.url = target
        tab.navigationEpoch++
        tab.lastUsed = System.currentTimeMillis()

        if(tab.networkProfile == null && tab.burnerId == null) GeckoRuntimeHolder.speculativeConnect(requireContext(),target)
        // An explicit navigation must never restore an older history entry first.
        tab.sessionState = null
        val session = tab.session
        when {
            session == null -> ensureSession(tab) // opens and loads exactly once, after routing
            TabRouting.blocked(session) -> applyNetwork(tab.id, tab.networkProfile)
            TabRouting.preparing(session) -> Unit // the pending route loads the latest tab.url
            else -> session.loadUri(target)
        }
        listener?.onTabChanged(tab)
        persist()
    }

    /** [bypassCache]: recarga completa, sin usar la caché (pulsación larga en Recargar). */
    fun reload(bypassCache: Boolean = false) {
        val tab = activeTab() ?: return
        if (tab.session == null) {
            tab.sessionState = null
            ensureSession(tab)
        } else if(tab.session?.let { TabRouting.synthetic(it) } == true) {
            applyNetwork(tab.id,tab.networkProfile)
        } else if (bypassCache) {
            tab.session?.reload(GeckoSession.LOAD_FLAGS_BYPASS_CACHE)
        } else tab.session?.reload()
    }

    /** Pestaña vecina del mismo modo (normal o privado), para el gesto de la barra. */
    fun adjacentTabId(offset: Int): String? {
        val current = activeTab() ?: return null
        val same = tabs.filter { it.isPrivate == current.isPrivate }
        val index = same.indexOfFirst { it.id == current.id }
        return same.getOrNull(index + offset)?.id
    }

    fun notifyMessage(message: String) {
        listener?.onMessage(message)
    }

    /** Vuelve a dibujar la pestaña activa (p. ej. cuando su buzón Burner ya tiene dirección). */
    fun refreshActive() {
        activeTab()?.let { listener?.onTabChanged(it) }
    }

    fun canGoBackOrPrevious(): Boolean =
        activeTab()?.canGoBack == true ||
            activationHistory.any { id ->
                id != activeId && tabs.any { it.id == id }
            }

    fun goBackOrPrevious() {
        if (activeTab()?.canGoBack == true) {
            activeSession()?.goBack()
            return
        }

        while (activationHistory.isNotEmpty()) {
            val previousId = activationHistory.removeLast()

            if (
                previousId != activeId &&
                tabs.any { it.id == previousId }
            ) {
                switchTo(
                    previousId,
                    loadUrl = true,
                    rememberPrevious = false
                )
                return
            }
        }
    }

    /** Sale de la pantalla completa de la pestaña activa. Devuelve true si lo estaba. */
    fun exitFullScreen(): Boolean {
        val tab = activeTab()?.takeIf { it.isFullScreen } ?: return false
        tab.isFullScreen = false
        runCatching { tab.session?.exitFullScreen() }
        listener?.onFullScreenChanged(tab, false)
        return true
    }

    fun goBack() {
        activeSession()?.goBack()
    }

    fun goForward() {
        activeSession()?.goForward()
    }

    fun setDesktopMode(enabled: Boolean) {
        val tab = activeTab() ?: return
        tab.desktopMode = enabled

        tab.session?.settings?.apply {
            setUserAgentMode(
                if (enabled) GeckoSessionSettings.USER_AGENT_MODE_DESKTOP
                else GeckoSessionSettings.USER_AGENT_MODE_MOBILE
            )
            setViewportMode(
                if (enabled) GeckoSessionSettings.VIEWPORT_MODE_DESKTOP
                else GeckoSessionSettings.VIEWPORT_MODE_MOBILE
            )
        }

        val target =
            normalizeMobileUrl(
                tab.url,
                enabled
            )

        tab.url = target

        if (target.isNotBlank()) {
            tab.session?.loadUri(target)
        }

        listener?.onTabChanged(tab)
        persist()
    }

    fun prepareForPictureInPicture() {
        val tab =
            activeTab()
                ?: return

        tab.session?.let {
            session ->

            /*
             * PiP sigue siendo contenido visible.
             * Nunca suspender multimedia durante esta transición.
             */
            session.settings
                .setSuspendMediaWhenInactive(
                    false
                )

            session.setPriorityHint(
                GeckoSession.PRIORITY_HIGH
            )

            session.setActive(true)

            ExtensionManager.setTabActive(
                session,
                true
            )
        }



    }

    fun prepareForBackground() {
        val tab =
            activeTab()
                ?: return

        val context =
            requireContext()

        val backgroundEnabled =
            BrowserPrefs.backgroundMedia(
                context
            )

        val keepMediaAlive =
            backgroundEnabled &&
            BrowserMediaController
                .shouldKeepAlive(
                    tab.id,
                    8_000L
                )

        tab.session?.let {
            session ->

            session.flushSessionState()
            session.setFocused(false)

            /*
             * Si el usuario habilitó reproducción en segundo
             * plano no permitimos que Gecko suspenda media.
             */
            session.settings
                .setSuspendMediaWhenInactive(
                    !backgroundEnabled
                )

            ExtensionManager.setTabActive(
                session,
                false
            )

            session.setPriorityHint(
                if (keepMediaAlive) {
                    GeckoSession.PRIORITY_HIGH
                } else {
                    GeckoSession.PRIORITY_DEFAULT
                }
            )

            session.setActive(
                keepMediaAlive
            )
        }

        if (keepMediaAlive) {
            MediaPlaybackService.sync(
                context
            )
        }

        persistNow()
    }

    fun resumeActive() {
        activeSession()?.let {
            session ->

            session.setPriorityHint(
                GeckoSession.PRIORITY_HIGH
            )

            session.setActive(true)
            session.setFocused(true)

            ExtensionManager.setTabActive(
                session,
                true
            )
        }
    }

    fun closeAllSessionsKeepingState() {
        persistNow()

        tabs.forEach { tab ->
            tab.session?.let { closeSession(it) }
            tab.session = null
        }
    }

    private fun closeSession(session: GeckoSession) {
        SitePermissions.cancel(session)
        // Includes a killed/crashed session: stop capture before waiting for recovery.
        LiveCaptions.stop(session)
        // Intentional closes must never call recovery or open another window.
        session.contentDelegate = null
        session.navigationDelegate = null
        session.setProgressDelegate(null)
        session.setMediaSessionDelegate(null)
        session.setTranslationsSessionDelegate(null)
        TranslatorManager.forget(session)
        TabRouting.forget(session)
        ExtensionManager.forgetSession(session)
        runCatching {
            session.setActive(false)
            session.close()
        }
    }

    fun reapplySettings() {
        val context = requireContext()
        val tracking = BrowserPrefs.trackingProtection(context)
        val suspendMedia = !BrowserPrefs.backgroundMedia(context)
        tabs.forEach {
            it.session?.settings?.setUseTrackingProtection(tracking)
            it.session?.settings?.setSuspendMediaWhenInactive(suspendMedia)
        }
        ExtensionManager.sendBrowserState(context)
        MediaPlaybackService.sync(context)
        trimSessions()
    }

    /** Sesión nueva, con sus delegados, todavía sin abrir. */
    private fun newSession(tab: BrowserTab): GeckoSession {
        val context = requireContext()
        val settings = GeckoSessionSettings.Builder()
            .usePrivateMode(tab.isPrivate)
            .contextId(tab.burnerId)
            .suspendMediaWhenInactive(!BrowserPrefs.backgroundMedia(context))
            .useTrackingProtection(BrowserPrefs.trackingProtection(context))
            .userAgentMode(
                if (tab.desktopMode) GeckoSessionSettings.USER_AGENT_MODE_DESKTOP
                else GeckoSessionSettings.USER_AGENT_MODE_MOBILE
            )
            .viewportMode(
                if (tab.desktopMode) GeckoSessionSettings.VIEWPORT_MODE_DESKTOP
                else GeckoSessionSettings.VIEWPORT_MODE_MOBILE
            )
            .build()

        val session = GeckoSession(settings)
        tab.session = session
        configureDelegates(tab, session)
        return session
    }

    /** Enlaza extensiones cuando la sesión ya está abierta. */
    private fun bindOpenedSession(session: GeckoSession) {
        ExtensionManager.bindSession(session)
    }

    private fun ensureSession(
        tab: BrowserTab,
        loadUrl: Boolean = true
    ): GeckoSession {
        tab.session?.let { return it }

        val context = requireContext()
        val session = newSession(tab)
        if(!loadUrl && (tab.networkProfile != null || tab.burnerId != null)) TabRouting.reserve(session)
        session.open(GeckoRuntimeHolder.get(context))
        bindOpenedSession(session)

        if (loadUrl) {
            val restored =
                if (!tab.isLoading) {
                    tab.sessionState
                        ?.let {
                            runCatching { GeckoSession.SessionState.fromString(it) }.getOrNull()?.takeIf { state ->
                                TabNavigation.canRestore(tab.url, runCatching { state[state.currentIndex].uri }.getOrNull())
                            }
                        }
                } else {
                    null
                }

            val target = tab.url
            val epoch = tab.navigationEpoch
            TabRouting.prepare(tab,session,restored != null) {
                if(restored != null && tab.url == target && tab.navigationEpoch == epoch) session.restoreState(restored) else session.loadUri(tab.url)
            }
        } else if(tab.networkProfile != null || tab.burnerId != null) {
            mainHandler.post {
                if(tab.session === session) TabRouting.prepare(tab,session) { session.loadUri(tab.url) }
            }
        }
        return session
    }

    /**
     * Ventana nueva pedida por la página (target=_blank, window.open).
     * GeckoView exige devolver una sesión SIN abrir y con el mismo modo
     * privado que la que la pidió; Gecko la abre y carga la URL.
     */
    private fun openWindowFrom(opener: BrowserTab, uri: String): GeckoSession {
        val tab = BrowserTab(
            url = uri.takeIf { isSafeAppNavigationUrl(it) } ?: "about:blank",
            title = if (opener.isPrivate) "Pestaña privada" else "Nueva pestaña",
            isPrivate = opener.isPrivate,
            groupName = opener.groupName,
            burnerId = opener.burnerId,
            burnerSeed = opener.burnerSeed,
            networkProfile = opener.networkProfile,
            desktopMode = opener.desktopMode,
            isLoading = true
        )
        // Insertarla junto a la pestaña que la abrió.
        val openerIndex = tabs.indexOfFirst { it.id == opener.id }
        if (openerIndex >= 0) tabs.add(openerIndex + 1, tab) else tabs += tab

        val session = newSession(tab)
        listener?.onTabCountChanged(tabs.size)
        switchTo(tab.id, loadUrl = false)
        if(tab.networkProfile != null || tab.burnerId != null) TabRouting.reserve(session)
        whenOpen(session) {
            bindOpenedSession(it)
            if(tab.networkProfile != null || tab.burnerId != null) {
                val target = uri.ifBlank { "about:blank" }
                TabRouting.prepare(tab,session) { session.loadUri(target) }
            }
        }
        return session
    }

    /** Ejecuta [action] en cuanto GeckoView haya abierto [session]. */
    private fun whenOpen(session: GeckoSession, attempt: Int = 0, action: (GeckoSession) -> Unit) {
        if (session.isOpen) {
            action(session)
            return
        }
        if (attempt >= 40 || tabs.none { it.session === session }) return
        mainHandler.postDelayed({ whenOpen(session, attempt + 1, action) }, 50L)
    }

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    private fun configureDelegates(tab: BrowserTab, session: GeckoSession) {
        val newWindows = NewWindowPolicy()
        var windowPrompt: android.app.AlertDialog? = null
        TranslatorManager.bind(session)
        session.setPromptDelegate(PagePrompts)
        session.setPermissionDelegate(SitePermissions)
        session.setContentDelegate(
            object : GeckoSession.ContentDelegate {
                override fun onCrash(session: GeckoSession) {
                    recoverContentProcess(tab, session, "crash")
                }

                override fun onKill(session: GeckoSession) {
                    recoverContentProcess(tab, session, "kill")
                }

                override fun onFirstContentfulPaint(session: GeckoSession) {
                    // Keep recent crashes across first paint so a crash/reload loop stays bounded.
                    val recent = crashTimes[tab.id]
                    val now = System.currentTimeMillis()
                    while (recent?.isNotEmpty() == true && now - recent.first() > 30000L) recent.removeFirst()
                    if (recent?.isEmpty() == true) crashTimes.remove(tab.id)
                }

                override fun onTitleChange(session: GeckoSession, title: String?) {
                    if (TabRouting.synthetic(session)) return
                    if (!title.isNullOrBlank()) {
                        tab.title = title
                        BrowserMediaController.updateTabTitle(tab.id, title)
                        persist()
                        listener?.onTabChanged(tab)
                    }
                }

                override fun onContextMenu(
                    session: GeckoSession,
                    screenX: Int,
                    screenY: Int,
                    element: GeckoSession.ContentDelegate.ContextElement
                ) {
                    listener?.onContextElement(element)
                }

                override fun onExternalResponse(
                    session: GeckoSession,
                    response: WebResponse
                ) {
                    if (ExtensionManager.isRemoteExtensionPackage(response.uri)) {
                        runCatching { response.body?.close() }
                        if(tab.networkProfile?.type?.let { it != "direct" } == true) {
                            listener?.onMessage("La instalación requiere una conexión independiente. Desactiva la ruta para instalarla.")
                            return
                        }
                        listener?.onMessage("Preparando instalación de extensión…")
                        ExtensionManager.installUrl(
                            requireContext(),
                            response.uri
                        ) { _, message ->
                            listener?.onMessage(message)
                        }
                        return
                    }

                    // Sin proxy ni Burner, un archivo grande puede repartirse en varias conexiones
                    // y reanudarse si se corta (misma sesión: cookies y modo privado).
                    val source = if (tab.networkProfile == null && tab.burnerId == null) {
                        DownloadSource(tab.isPrivate, tab.url.takeIf { UrlSafety.isWebUrl(it) }, BrowserPrefs.parallelDownloads(requireContext()))
                    } else null
                    listener?.onMessage("Descargando…")
                    DownloadStore.saveResponse(
                        requireContext(),
                        response,
                        source
                    ) { _, message ->
                        listener?.onMessage(message)
                    }
                }

                override fun onCloseRequest(session: GeckoSession) {
                    closeTab(tab.id)
                }

                override fun onFullScreen(session: GeckoSession, fullScreen: Boolean) {
                    tab.isFullScreen = fullScreen
                    if (tab.id == activeTab()?.id) {
                        listener?.onFullScreenChanged(tab, fullScreen)
                    }
                }

                // window.focus() desde otra pestaña (p. ej. tras un inicio de sesión).
                override fun onFocusRequest(session: GeckoSession) {
                    // window.focus() is not a user tab selection. Background pages,
                    // including popunders, must not steal the visible GeckoView.
                }
            }
        )

        session.setMediaSessionDelegate(
            object : org.mozilla.geckoview.MediaSession.Delegate {
                override fun onActivated(
                    session: GeckoSession,
                    mediaSession: org.mozilla.geckoview.MediaSession
                ) { BrowserMediaController.onActivated(tab, mediaSession) }

                override fun onDeactivated(
                    session: GeckoSession,
                    mediaSession: org.mozilla.geckoview.MediaSession
                ) { BrowserMediaController.onDeactivated(tab.id, mediaSession) }

                override fun onPlay(
                    session: GeckoSession,
                    mediaSession: org.mozilla.geckoview.MediaSession
                ) {
                    tab.sessionState = null
                    persist()
                    BrowserMediaController.onPlay(
                        tab,
                        mediaSession
                    )
                }

                override fun onPause(
                    session: GeckoSession,
                    mediaSession: org.mozilla.geckoview.MediaSession
                ) { BrowserMediaController.onPause(tab.id, mediaSession) }

                override fun onStop(
                    session: GeckoSession,
                    mediaSession: org.mozilla.geckoview.MediaSession
                ) { BrowserMediaController.onStop(tab.id, mediaSession) }

                override fun onMetadata(
                    session: GeckoSession,
                    mediaSession: org.mozilla.geckoview.MediaSession,
                    meta: org.mozilla.geckoview.MediaSession.Metadata
                ) {
                    BrowserMediaController.updateMetadata(tab.id, mediaSession, meta.title)
                }
            }
        )

        session.setNavigationDelegate(
            object : GeckoSession.NavigationDelegate {
                override fun onLocationChange(
                    session: GeckoSession,
                    url: String?,
                    permissions: MutableList<GeckoSession.PermissionDelegate.ContentPermission>,
                    hasUserGesture: Boolean
                ) {
                    if (tab.session !== session || TabRouting.synthetic(session) || url == null || !isSafeAppNavigationUrl(url)) return
                    if (tab.url != url) {
                        SitePermissions.cancel(session)
                        if (LiveCaptions.usingDeviceAudio(session)) LiveCaptions.stop(session)
                        else PlaybackAudioCapture.cancel(session)
                        tab.sessionState = null
                        tab.navigationEpoch++
                        windowPrompt?.dismiss()
                    }
                    tab.url = url
                    ExtensionManager.sendBrowserState(requireContext(), session)
                    persist()
                    listener?.onTabChanged(tab)
                }

                override fun onLoadRequest(
                    session: GeckoSession,
                    request: GeckoSession.NavigationDelegate.LoadRequest
                ): GeckoResult<AllowOrDeny>? {
                    if (tab.session !== session || tabs.none { it.id == tab.id }) return GeckoResult.deny()
                    if (TabRouting.preparing(session)) return if(TabRouting.bootstrap(session,request.uri)) GeckoResult.allow() else GeckoResult.deny()
                    // tel:, mailto:, intent://, market:… los abre otra app.
                    if (UrlSafety.isExternalAppUrl(request.uri)) {
                        ExternalApps.open(
                            requireContext(),
                            request.uri,
                            request.hasUserGesture,
                            tab.isPrivate
                        ) { fallback -> session.loadUri(fallback) }
                        return GeckoResult.fromValue(AllowOrDeny.DENY)
                    }

                    if (ExtensionManager.isRemoteExtensionPackage(request.uri) && tab.networkProfile?.type?.let { it != "direct" } == true) {
                        listener?.onMessage("La instalación de extensiones usa una conexión independiente. Desactiva la ruta para instalarla.")
                        return GeckoResult.deny()
                    }
                    if (ExtensionManager.isRemoteExtensionPackage(request.uri)) {
                        listener?.onMessage("Abriendo instalador de extensión…")
                        ExtensionManager.installUrl(
                            requireContext(),
                            request.uri
                        ) { _, message ->
                            listener?.onMessage(message)
                        }
                        return GeckoResult.fromValue(AllowOrDeny.DENY)
                    }

                    if (tab.session !== session || tabs.none { it.id == tab.id }) return GeckoResult.deny()
                    if (request.target == GeckoSession.NavigationDelegate.TARGET_WINDOW_NEW) {
                        if (windowPrompt != null) return GeckoResult.deny()
                        val now = android.os.SystemClock.elapsedRealtime()
                        if (!newWindows.authorize(request.uri, request.hasUserGesture && tab.id == activeId, now)) return GeckoResult.deny()
                        // Gesture flags also accompany stolen video taps. Require explicit consent
                        // for cross-origin popups rather than opening advertising tabs automatically.
                        if (TabNavigation.needsWindowConfirmation(tab.url, request.uri)) {
                            val activity = BrowserHosts.current()?.hostActivity ?: return GeckoResult.deny()
                            val epoch = tab.navigationEpoch
                            val decision = GeckoResult<AllowOrDeny>()
                            var answered = false
                            windowPrompt = android.app.AlertDialog.Builder(activity)
                                .setTitle("¿Abrir otra pestaña?")
                                .setMessage("La página quiere abrir ${Uri.parse(request.uri).host ?: "una ventana"}. Puedes cancelar y seguir en el reproductor.")
                                .setNegativeButton("Cancelar", null)
                                .setPositiveButton("Abrir") { _, _ ->
                                    answered = true
                                    val valid = tab.session === session && tab.id == activeId && tab.navigationEpoch == epoch &&
                                        newWindows.authorize(request.uri,true,android.os.SystemClock.elapsedRealtime())
                                    if (!valid) newWindows.cancel()
                                    decision.complete(if(valid) AllowOrDeny.ALLOW else AllowOrDeny.DENY)
                                }.setOnDismissListener {
                                    windowPrompt = null
                                    if (!answered) { newWindows.cancel(); decision.complete(AllowOrDeny.DENY) }
                                }.show()
                            // Continue Gecko's original new-window request after consent so
                            // window.opener/postMessage login flows and private isolation survive.
                            return decision
                        }
                        return GeckoResult.allow()
                    }

                    val target =
                        normalizeMobileUrl(
                            request.uri,
                            tab.desktopMode
                        )

                    // Una ventana nueva se resuelve en onNewSession: no
                    // redirigir aquí o se cargaría en la pestaña actual.
                    if (
                        request.isDirectNavigation && target != request.uri &&
                        request.target != GeckoSession.NavigationDelegate.TARGET_WINDOW_NEW
                    ) {
                        tab.url = target
                        tab.sessionState = null
                        persist()
                        session.loadUri(target)

                        return GeckoResult.fromValue(
                            AllowOrDeny.DENY
                        )
                    }

                    return null
                }

                override fun onNewSession(
                    session: GeckoSession,
                    uri: String
                ): GeckoResult<GeckoSession>? {
                    if (tab.session !== session || tabs.none { it.id == tab.id } || tab.id != activeId ||
                        !newWindows.consume(uri, android.os.SystemClock.elapsedRealtime()) ||
                        (!isSafeAppNavigationUrl(uri) && uri.isNotBlank())) return null
                    return GeckoResult.fromValue(openWindowFrom(tab, uri))
                }

                override fun onCanGoBack(
                    session: GeckoSession,
                    canGoBack: Boolean
                ) {
                    if(TabRouting.synthetic(session)) return
                    tab.canGoBack = canGoBack
                    listener?.onTabChanged(tab)
                }

                override fun onCanGoForward(
                    session: GeckoSession,
                    canGoForward: Boolean
                ) {
                    if(TabRouting.synthetic(session)) return
                    tab.canGoForward = canGoForward
                    listener?.onTabChanged(tab)
                }
            }
        )

        session.setProgressDelegate(
            object : GeckoSession.ProgressDelegate {
                override fun onSessionStateChange(
                    session: GeckoSession,
                    sessionState: GeckoSession.SessionState
                ) {
                    if (
                        tab.session === session && !TabRouting.synthetic(session) && !tab.isPrivate &&
                        TabNavigation.canRestore(tab.url, runCatching { sessionState[sessionState.currentIndex].uri }.getOrNull()) &&
                        !BrowserMediaController.isPlaying(tab.id)
                    ) {
                        tab.sessionState =
                            sessionState.toString()
                        persist()
                    }
                }

                override fun onPageStart(session: GeckoSession, url: String) {
                    if (tab.session !== session) return
                    if (TabRouting.bootstrap(session,url) || TabRouting.preparing(session)) return
                    TabRouting.targetStarted(session)
                    windowPrompt?.dismiss()
                    // Una página nueva no hereda los subtítulos (ni su consumo de Deepgram).
                    if (LiveCaptions.isActive(session)) LiveCaptions.stop(session)
                    tab.navigationEpoch++
                    TranslatorManager.forget(session)
                    PagePrompts.onPageStart(session)
                    SitePermissions.cancel(session)
                    BrowserMediaController.resetVideo(tab.id)
                    tab.url = url
                    ExtensionManager.sendBrowserState(requireContext(), session)
                    tab.isLoading = true
                    tab.lastUsed = System.currentTimeMillis()
                    persist()
                    listener?.onTabChanged(tab)
                    listener?.onProgress(tab, 0, true)
                }

                override fun onProgressChange(
                    session: GeckoSession,
                    progress: Int
                ) {
                    if (tab.session !== session) return
                    if(TabRouting.synthetic(session)) return
                    listener?.onProgress(
                        tab,
                        progress.coerceIn(0, 100),
                        true
                    )
                }

                override fun onPageStop(session: GeckoSession, success: Boolean) {
                    if (tab.session !== session) return
                    if(TabRouting.synthetic(session)) return
                    if(success) TabRouting.targetFinished(session)
                    tab.isLoading = false
                    persist()
                    listener?.onProgress(tab, 100, false)

                    if (success && !tab.isPrivate) {
                        HistoryStore.add(
                            requireContext(),
                            tab.url,
                            tab.title
                        )
                    }
                }
            }
        )
    }


    private fun recoverContentProcess(
        tab: BrowserTab,
        failedSession: GeckoSession,
        reason: String
    ) {
        if (tab.session !== failedSession || tabs.none { it.id == tab.id }) return
        closeSession(failedSession)

        val currentUrl = tab.url

        tab.session = null
        // Un "kill" lo decide Android (no es culpa de la página): conservar el
        // historial y la posición. Tras un fallo real se carga la URL limpia.
        if (reason != "kill") tab.sessionState = null
        if (tab.isFullScreen) {
            tab.isFullScreen = false
            if (tab.id == activeId) listener?.onFullScreenChanged(tab, false)
        }
        tab.isLoading = false

        BrowserMediaController.removeTab(tab.id)

        val now = System.currentTimeMillis()
        val times = crashTimes.getOrPut(tab.id) {
            java.util.ArrayDeque()
        }

        while (
            times.isNotEmpty() &&
            now - times.first() > 30000L
        ) {
            times.removeFirst()
        }

        times.addLast(now)

        // Nunca mandar al usuario a Inicio por un kill/crash.
        tab.url = currentUrl
        persist()

        if (tab.id != activeId) return

        val attempts = times.size

        if (attempts > 3) {
            listener?.onMessage(
                "La página consumió demasiados recursos. " +
                    "Nexo conservó la URL; toca Recargar para intentarlo otra vez."
            )
            return
        }

        // Successful automatic recovery is quiet; repeated failures still explain what to do.
        android.util.Log.i("NexoGecko", "Recuperando proceso web: $reason")

        val delay =
            when (attempts) {
                1 -> 350L
                2 -> 900L
                else -> 1600L
            }

        android.os.Handler(
            android.os.Looper.getMainLooper()
        ).postDelayed(
            {
                if (
                    tab.id == activeId && tabs.any { it.id == tab.id } &&
                    tab.session == null
                ) {
                    runCatching {
                        switchTo(
                            tab.id,
                            loadUrl = true,
                            rememberPrevious = false
                        )
                    }.onFailure { error ->
                        android.util.Log.e(
                            "NexoGecko",
                            "No se pudo recuperar sesión: $reason",
                            error
                        )
                    }
                }
            },
            delay
        )
    }

    private fun trimSessions() {
        val maxLive = BrowserPrefs.maxLiveTabs(requireContext())
        // Las privadas y Burner no guardan estado: descartarlas las "reiniciaría"
        // y cerraría sus sesiones iniciadas. Solo se descartan si son muchas.
        val background = tabs
            .filter {
                it.id != activeId && it.session != null &&
                    !BrowserMediaController.isPlaying(it.id) &&
                    (!it.isPrivate || tabs.count { other -> other.isPrivate && other.session != null } > PRIVATE_LIVE_LIMIT)
            }
            .sortedByDescending { it.lastUsed }

        background
            .drop((maxLive - 1).coerceAtLeast(0))
            .forEach { tab ->
                tab.session?.let { closeSession(it) }
                tab.session = null
            }
    }

    private fun restoreOrCreate() {
        val context = requireContext()

        val saved = TabStore.read(context)
        if (BrowserPrefs.restoreTabs(context)) {
            saved?.let { raw ->
                runCatching {
                    val array = JSONArray(raw)

                    for (i in 0 until array.length()) {
                        val o = array.getJSONObject(i)
                        tabs += BrowserTab(
                            id = o.optString("id").ifBlank {
                                UUID.randomUUID().toString()
                            },
                            url = o.optString(
                                "url",
                                BrowserPrefs.homePage(context)
                            ).let { restoredUrl ->
                                val candidate =
                                    if (restoredUrl.startsWith("resource://android/assets/home/")) {
                                        BrowserPrefs.homePage(context)
                                    } else {
                                        restoredUrl
                                    }

                                if (isSafeAppNavigationUrl(candidate)) {
                                    candidate
                                } else {
                                    BrowserPrefs.homePage(context)
                                }
                            },
                            title = o.optString("title", "Pestaña"),
                            isPrivate = false,
                            groupName = TabGroupRules.name(o.optString("groupName")),
                            networkProfile = if(o.has("network") && !o.isNull("network")) TabNetworkProfile.fromJson(o.optJSONObject("network")) ?: TabNetworkProfile(type="invalid") else null,
                            desktopMode = o.optBoolean("desktopMode", false),
                            lastUsed = o.optLong(
                                "lastUsed",
                                System.currentTimeMillis()
                            ),
                            isLoading =
                                o.optBoolean(
                                    "loading",
                                    false
                                ),
                            sessionState = o.optString("sessionState")
                                .takeIf { it.isNotBlank() && it != "null" }
                        )
                    }

                    activeId = BrowserPrefs.activeTabId(context)
                        ?.takeIf { id -> tabs.any { it.id == id } }
                }
            }
        }

        if (tabs.isEmpty()) {
            val tab = BrowserTab(url = BrowserPrefs.homePage(context))
            tabs += tab
            activeId = tab.id
        }

        if (activeId == null) activeId = tabs.first().id

        activeTab()?.let { ensureSession(it) }
        persist()
    }

    private val persistHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val persistTask = Runnable { persistNow() }

    /*
     * Se llama en cada título, progreso o cambio de estado de sesión. Agrupar
     * las escrituras evita serializar todas las pestañas (con su historial)
     * decenas de veces por segundo en el hilo principal.
     */
    private fun persist() {
        persistHandler.removeCallbacks(persistTask)
        persistHandler.postDelayed(persistTask, 500L)
    }

    private class TabSnapshot(
        val id: String,
        val url: String,
        val title: String,
        val desktopMode: Boolean,
        val lastUsed: Long,
        val loading: Boolean,
        val sessionState: String,
        val groupName: String?,
        val network: JSONObject?
    )

    /** Escritura inmediata: al ir a segundo plano o cerrar sesiones. */
    fun persistNow() {
        persistHandler.removeCallbacks(persistTask)
        val context = appContext ?: return

        // Copia barata en el hilo principal; el JSON se arma en segundo plano.
        val snapshot = tabs.filterNot { it.isPrivate }.map { tab ->
            TabSnapshot(
                tab.id, tab.url, tab.title, tab.desktopMode,
                tab.lastUsed, tab.isLoading, tab.sessionState ?: "", tab.groupName, tab.networkProfile?.json()
            )
        }

        TabStore.write(context) {
            val array = JSONArray()
            snapshot.forEach { tab ->
                array.put(
                    JSONObject().apply {
                        put("id", tab.id)
                        put("url", tab.url)
                        put("title", tab.title)
                        put("desktopMode", tab.desktopMode)
                        put("lastUsed", tab.lastUsed)
                        put("loading", tab.loading)
                        put("sessionState", tab.sessionState)
                        put("groupName", tab.groupName ?: "")
                        put("network", tab.network)
                    }
                )
            }
            array.toString()
        }

        val activeTabId = activeTab()?.takeUnless { it.isPrivate }?.id
        if (BrowserPrefs.activeTabId(context) != activeTabId) {
            BrowserPrefs.setActiveTabId(context, activeTabId)
        }
    }

    /**
     * Android avisa que falta memoria: cerrar las sesiones de pestañas en
     * segundo plano (conservan URL e historial y se restauran al volver).
     * Las privadas se respetan: cerrarlas borraría su contenido.
     */
    /**
     * Solo una presión de memoria real descarta pestañas. TRIM_MEMORY_BACKGROUND
     * llega siempre que la app pasa a segundo plano (en Android 14+ es casi la
     * única señal) y descartar con ella reiniciaba las pestañas sin motivo.
     */
    fun onTrimMemory(level: Int) {
        if (!initialized) return
        TabPreviewStore.trimMemory(level)
        val critical = level == 15 /* RUNNING_CRITICAL */ || level >= 80 /* COMPLETE */
        if (!critical) return
        tabs.filter {
            it.id != activeId && it.session != null && !it.isPrivate &&
                !BrowserMediaController.isPlaying(it.id)
        }
            .sortedByDescending { it.lastUsed }
            .drop(2)
            .forEach { tab ->
                tab.session?.let { closeSession(it) }
                tab.session = null
            }
    }

    private fun notifyActive() {
        val tab = activeTab() ?: return
        val session = ensureSession(tab)
        listener?.onActiveTabChanged(tab, session)
        listener?.onTabChanged(tab)
    }

    private fun requireContext(): Context =
        requireNotNull(appContext) {
            "TabManager.initialize debe ejecutarse primero"
        }
}
