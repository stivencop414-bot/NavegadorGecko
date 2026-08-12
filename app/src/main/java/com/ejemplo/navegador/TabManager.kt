package com.ejemplo.navegador

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
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
    }

    private val tabs = CopyOnWriteArrayList<BrowserTab>()
    private var appContext: Context? = null
    private var listener: Listener? = null
    private var initialized = false
    private var activeId: String? = null
    private val activationHistory = java.util.ArrayDeque<String>()

    fun initialize(context: Context) {
        appContext = context.applicationContext
        AppContext.initialize(context)
        ExtensionManager.initialize(context)

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
            old?.session?.setActive(false)

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
        session.setActive(true)

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
        tabs.remove(removed)

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
        tab.url = url
        tab.lastUsed = System.currentTimeMillis()

        ensureSession(tab).loadUri(url)
        listener?.onTabChanged(tab)
        persist()
    }

    fun reload() {
        activeSession()?.reload()
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

        tab.session?.reload()
        listener?.onTabChanged(tab)
        persist()
    }

    fun suspendForBackground() {
        tabs.forEach { it.session?.setActive(false) }
    }

    fun resumeActive() {
        activeSession()?.setActive(true)
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
        val tracking = BrowserPrefs.trackingProtection(requireContext())
        tabs.forEach {
            it.session?.settings?.setUseTrackingProtection(tracking)
        }
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
            .suspendMediaWhenInactive(true)
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

        if (loadUrl) session.loadUri(tab.url)
        return session
    }

    private fun configureDelegates(tab: BrowserTab, session: GeckoSession) {
        session.setContentDelegate(
            object : GeckoSession.ContentDelegate {
                override fun onTitleChange(session: GeckoSession, title: String?) {
                    if (!title.isNullOrBlank()) {
                        tab.title = title
                        persist()
                        listener?.onTabChanged(tab)
                    }
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

        session.setNavigationDelegate(
            object : GeckoSession.NavigationDelegate {
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
                override fun onPageStart(session: GeckoSession, url: String) {
                    tab.url = url
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

    private fun trimSessions() {
        val maxLive = BrowserPrefs.maxLiveTabs(requireContext())
        val background = tabs
            .filter { it.id != activeId && it.session != null }
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
                            ),
                            title = o.optString("title", "Pestaña"),
                            isPrivate = false,
                            desktopMode = o.optBoolean("desktopMode", false),
                            lastUsed = o.optLong(
                                "lastUsed",
                                System.currentTimeMillis()
                            )
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
