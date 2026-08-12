package com.ejemplo.navegador

import android.content.Context

class BrowserViewModel(private val context: Context) {
    fun navigate(input: String) =
        TabManager.navigate(SearchResolver.resolve(context, input))

    fun goHome() =
        TabManager.navigate(BrowserPrefs.homePage(context))

    fun newTab(privateMode: Boolean = false) =
        TabManager.createTab(
            BrowserPrefs.homePage(context),
            privateMode,
            true
        )

    fun closeCurrentTab() {
        TabManager.activeTab()?.let { TabManager.closeTab(it.id) }
    }
}
