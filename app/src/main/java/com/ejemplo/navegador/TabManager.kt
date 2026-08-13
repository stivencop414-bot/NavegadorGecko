package com.ejemplo.navegador

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
    interface Listener {
        fun onActiveTabChanged(tab: BrowserTab, session: GeckoSession)
        fun onTabChanged(tab: BrowserTab)
        fun onProgress(tab: BrowserTab, progress: Int, loading: Boolean)
        fun onTabCountChanged(count: Int)
        fun onMessage(message: String)
        fun onContextElement(element: GeckoSession.ContentDelegate.ContextElement)
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
        activate: Boolean = true
    ): BrowserTab {
        val tab = BrowserTab(
            url = url,
            title = if (isPrivate) "Pestaña privada" else "Nueva pestaña",
            isPrivate = isPrivate
        )

        tabs += tab
        listener?.onTabCountChanged(tabs.size)
        persist()

        if (activate) switchTo(tab.id)
        return tab
    }

    fun createSessionForExtension(
        url: String,
        activate: Boolean
    ): GeckoSession {
        val tab = BrowserTab(
            id = UUID.randomUUID().toString(),
            url = url,
            title = "Extensión",
            isPrivate = activeTab()?.isPrivate ?: false
        )

        tabs += tab
        listener?.onTabCountChanged(tabs.size)

        val session = ensureSession(tab, loadUrl = false)

        if (activate) {
            switchTo(tab.id, loadUrl = false)
        } else {
            session.setActive(false)
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
            old?.session?.let { session ->
                val keepMediaAlive =
                    BrowserPrefs.backgroundMedia(requireContext()) &&
                    old != null &&
                    BrowserMediaController.isPlaying(old.id)

                session.setFocused(false)
                session.setPriorityHint(
                    if (keepMediaAlive) {
                        GeckoSession.PRIORITY_HIGH
                    } else {
                        GeckoSession.PRIORITY_DEFAULT
                    }
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

    fun closeTab(id: String) {
        val index = tabs.indexOfFirst { it.id == id }
        if (index < 0) return

        val removed = tabs[index]
        val wasActive = removed.id == activeId
        activationHistory.remove(id)

        removed.session?.let { session ->
            runCatching {
                session.setActive(false)
                session.close()
            }
        }
        removed.session = null
        BrowserMediaController.removeTab(removed.id)
        tabs.remove(removed)
        if (!removed.isPrivate) TabPreviewStore.remove(requireContext(), removed.id)

        if (tabs.isEmpty()) {
            val c = requireContext()
            createTab(BrowserPrefs.homePage(c), false, true)
            return
        }

        if (wasActive) {
            val next = tabs[index.coerceAtMost(tabs.lastIndex)]
            switchTo(next.id, rememberPrevious = false)
        } else {
            listener?.onTabCountChanged(tabs.size)
            persist()
        }
    }

    fun navigate(url: String) {
        val tab = activeTab() ?: return
        val target =
            normalizeMobileUrl(
                url,
                tab.desktopMode
            )

        tab.url = target
        tab.lastUsed = System.currentTimeMillis()

        GeckoRuntimeHolder.speculativeConnect(
            requireContext(),
            target
        )
        ensureSession(tab).loadUri(target)
        listener?.onTabChanged(tab)
        persist()
    }

    fun reload() {
        activeSession()?.reload()
    }

    fun notifyMessage(message: String) {
        listener?.onMessage(message)
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

    fun prepareForBackground() {
        val tab = activeTab()

        tab?.session?.let { session ->
            val keepMediaAlive =
                BrowserPrefs.backgroundMedia(requireContext()) &&
                BrowserMediaController.isPlaying(tab.id)

            session.flushSessionState()
            session.setFocused(false)
            session.setPriorityHint(
                if (keepMediaAlive) {
                    GeckoSession.PRIORITY_HIGH
                } else {
                    GeckoSession.PRIORITY_DEFAULT
                }
            )
            session.setActive(keepMediaAlive)

            if (keepMediaAlive) {
                MediaPlaybackService.sync(requireContext())
            }
        }

        persist()
    }

    fun suspendForBackground() {
        prepareForBackground()
    }

    fun resumeActive() {
        activeSession()?.let { session ->
            session.setPriorityHint(GeckoSession.PRIORITY_HIGH)
            session.setActive(true)
            session.setFocused(true)
        }
    }

    fun closeAllSessionsKeepingState() {
        persist()

        tabs.forEach { tab ->
            tab.session?.let { session ->
                runCatching {
                    session.setActive(false)
                    session.close()
                }
            }
            tab.session = null
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

    private fun ensureSession(
        tab: BrowserTab,
        loadUrl: Boolean = true
    ): GeckoSession {
        tab.session?.let { return it }

        val context = requireContext()
        val settings = GeckoSessionSettings.Builder()
            .usePrivateMode(tab.isPrivate)
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
        session.open(GeckoRuntimeHolder.get(context))
        ExtensionManager.bindSession(session)
        TranslatorManager.bindSession(session)

        if (loadUrl) {
            val restored =
                if (!tab.isLoading) {
                    tab.sessionState
                        ?.let {
                            GeckoSession.SessionState.fromString(it)
                        }
                } else {
                    null
                }

            if (restored != null) {
                session.restoreState(restored)
            } else {
                GeckoRuntimeHolder.speculativeConnect(
                    context,
                    tab.url
                )
                session.loadUri(tab.url)
            }
        }
        return session
    }

    private fun configureDelegates(tab: BrowserTab, session: GeckoSession) {
        session.setContentDelegate(
            object : GeckoSession.ContentDelegate {
                override fun onCrash(session: GeckoSession) {
                    recoverContentProcess(tab, session, "crash")
                }

                override fun onKill(session: GeckoSession) {
                    recoverContentProcess(tab, session, "kill")
                }

                override fun onFirstContentfulPaint(session: GeckoSession) {
                    crashTimes.remove(tab.id)
                }

                override fun onTitleChange(session: GeckoSession, title: String?) {
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
                    DownloadStore.saveResponse(
                        requireContext(),
                        response
                    ) { _, message ->
                        listener?.onMessage(message)
                    }
                }

                override fun onCloseRequest(session: GeckoSession) {
                    closeTab(tab.id)
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
                override fun onLoadRequest(
                    session: GeckoSession,
                    request: GeckoSession.NavigationDelegate.LoadRequest
                ): GeckoResult<AllowOrDeny>? {
                    val target =
                        normalizeMobileUrl(
                            request.uri,
                            tab.desktopMode
                        )

                    if (target != request.uri) {
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

                override fun onCanGoBack(
                    session: GeckoSession,
                    canGoBack: Boolean
                ) {
                    tab.canGoBack = canGoBack
                    listener?.onTabChanged(tab)
                }

                override fun onCanGoForward(
                    session: GeckoSession,
                    canGoForward: Boolean
                ) {
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
                        !tab.isPrivate &&
                        !BrowserMediaController.isPlaying(tab.id)
                    ) {
                        tab.sessionState =
                            sessionState.toString()
                        persist()
                    }
                }

                override fun onPageStart(session: GeckoSession, url: String) {
                    BrowserMediaController.resetVideo(tab.id)
                    tab.url = url
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
                    listener?.onProgress(
                        tab,
                        progress.coerceIn(0, 100),
                        true
                    )
                }

                override fun onPageStop(session: GeckoSession, success: Boolean) {
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
        if (tab.session !== failedSession) return

        val currentUrl = tab.url

        tab.session = null
        tab.sessionState = null
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

        listener?.onMessage(
            if (reason == "kill") {
                "Android liberó el proceso web; recuperando esta misma página…"
            } else {
                "Gecko reinició el proceso web; recuperando esta misma página…"
            }
        )

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
                    tab.id == activeId &&
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
        val background = tabs
            .filter {
                it.id != activeId && it.session != null &&
                    !BrowserMediaController.isPlaying(it.id)
            }
            .sortedByDescending { it.lastUsed }

        background
            .drop((maxLive - 1).coerceAtLeast(0))
            .forEach { tab ->
                tab.session?.let { session ->
                    runCatching {
                        session.setActive(false)
                        session.close()
                    }
                }
                tab.session = null
            }
    }

    private fun restoreOrCreate() {
        val context = requireContext()

        if (BrowserPrefs.restoreTabs(context)) {
            BrowserPrefs.tabsJson(context)?.let { raw ->
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
                                if (restoredUrl.startsWith("resource://android/assets/home/")) {
                                    BrowserPrefs.homePage(context)
                                } else restoredUrl
                            },
                            title = o.optString("title", "Pestaña"),
                            isPrivate = false,
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

    private fun persist() {
        val context = appContext ?: return
        val array = JSONArray()

        tabs.filterNot { it.isPrivate }.forEach { tab ->
            array.put(
                JSONObject().apply {
                    put("id", tab.id)
                    put("url", tab.url)
                    put("title", tab.title)
                    put("desktopMode", tab.desktopMode)
                    put("lastUsed", tab.lastUsed)
                    put("loading", tab.isLoading)
                    put("sessionState", tab.sessionState ?: "")
                }
            )
        }

        BrowserPrefs.setTabsJson(context, array.toString())
        BrowserPrefs.setActiveTabId(
            context,
            activeTab()?.takeUnless { it.isPrivate }?.id
        )
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
