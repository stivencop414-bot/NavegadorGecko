package com.ejemplo.navegador

import org.mozilla.geckoview.GeckoSession
import java.util.UUID

data class BrowserTab(
    val id: String = UUID.randomUUID().toString(),
    var url: String,
    var title: String = "Nueva pestaña",
    val isPrivate: Boolean = false,
    var desktopMode: Boolean = false,
    var canGoBack: Boolean = false,
    var canGoForward: Boolean = false,
    var lastUsed: Long = System.currentTimeMillis(),
    var isLoading: Boolean = false,
    var sessionState: String? = null,
    var session: GeckoSession? = null
)
