package com.ejemplo.navegador

import android.content.Context
import org.mozilla.geckoview.StorageController

/** Borrado de datos de navegación compartido por Ajustes y "Borrar datos al salir". */
object BrowsingData {
    fun clear(context: Context, onDone: (Boolean, String?) -> Unit = { _, _ -> }) {
        val app = context.applicationContext
        TabManager.closeAllSessionsKeepingState()
        HistoryStore.clear(app)
        TabPreviewStore.clear(app)
        GeckoRuntimeHolder.get(app).storageController
            .clearData(StorageController.ClearFlags.ALL)
            .accept({ onDone(true, null) }, { error -> onDone(false, error?.message) })
    }
}
