package com.nexo.browser

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView

/**
 * Herramientas de la pestaña actual en una hoja inferior: grupo, ruta de red,
 * pruebas (retardo y ubicación), DNS e identidad Burner. Cada control indica
 * su alcance real (esta pestaña o todo Nexo).
 */
object TabTools {
    private val ROUTES = listOf(
        "direct" to "Sistema",
        "socks" to "SOCKS5",
        "http" to "Proxy HTTP",
        "tor" to "Tor (Orbot)"
    )

    fun show(activity: Activity) {
        val tab = TabManager.activeTab() ?: return
        val p = ThemeManager.palette(activity)
        lateinit var sheet: Dialog
        val current = tab.networkProfile ?: TabNetworkProfile()
        val host = runCatching { android.net.Uri.parse(tab.url).host }.getOrNull()?.removePrefix("www.").orEmpty()
        val body = NexoUi.column(activity)

        if (UrlSafety.isWebUrl(tab.url)) {
            body.addView(NexoUi.switchRow(activity, "Modo nocturno en este sitio",
                "Oscurece las páginas cuando Nexo usa un tema oscuro.", NightMode.allowed(activity, tab)) { enabled ->
                NightMode.setAllowed(activity, tab, enabled)
            })
        }

        // --- Grupo ---------------------------------------------------------
        body.addView(NexoUi.section(activity, "Grupo de esta pestaña"))
        fun groupChips(): View {
            val names = TabManager.allTabs().filter { it.isPrivate == tab.isPrivate }.mapNotNull { it.groupName }.distinct().sorted()
            val chips = mutableListOf<View>()
            chips += NexoUi.chip(activity, "Sin grupo", tab.groupName == null) {
                TabManager.moveToGroup(tab.id, null); sheet.dismiss(); show(activity)
            }
            names.forEach { name ->
                chips += NexoUi.chip(activity, name, tab.groupName == name, TabGroupRules.color(name)) {
                    TabManager.moveToGroup(tab.id, name); sheet.dismiss(); show(activity)
                }
            }
            chips += NexoUi.chip(activity, "+  Nuevo grupo", false) {
                sheet.dismiss()
                NexoUi.askText(activity, "Nuevo grupo", "Nombre del grupo", action = "Crear y mover") { value ->
                    TabManager.moveToGroup(tab.id, value)
                    Toastish.show(activity, "Pestaña movida a «${TabGroupRules.name(value)}».")
                }
            }
            return NexoUi.chipRow(activity, chips)
        }
        body.addView(groupChips())
        body.addView(NexoUi.actionRow(activity, R.drawable.ic_tab_tools, "Ver todos los grupos", "Pestañas agrupadas por color") {
            sheet.dismiss()
            TabSwitcher.show(activity) { privateMode -> TabManager.createTab(BrowserPrefs.homePage(activity), privateMode, true) }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = NexoUi.dp(activity, 6f)
        })

        // --- Ruta de red ---------------------------------------------------
        body.addView(NexoUi.section(activity, "Ruta de red de esta pestaña"))
        var route = when {
            current.type == "socks" && current.host == "127.0.0.1" && current.port == 9050 -> "tor"
            current.type == "socks" -> "socks"
            current.type == "http" -> "http"
            else -> "direct"
        }
        val routeHolder = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val serverCard = NexoUi.card(activity)
        val (hostView, hostField) = NexoUi.field(activity, "Servidor", "proxy.ejemplo.com", current.host)
        val (portView, portField) = NexoUi.field(activity, "Puerto", "1080", if (current.port > 0) current.port.toString() else "", numeric = true)
        serverCard.addView(NexoUi.pair(activity, hostView, 2f, portView, 1f))
        val routeHint = NexoUi.note(activity, "")
        serverCard.addView(routeHint)
        fun renderRoutes() {
            routeHolder.removeAllViews()
            routeHolder.addView(NexoUi.chipRow(activity, ROUTES.map { (value, label) ->
                NexoUi.chip(activity, label, route == value) {
                    route = value
                    if (value == "tor") { hostField.setText("127.0.0.1"); portField.setText("9050") }
                    renderRoutes()
                }
            }))
            serverCard.visibility = if (route == "direct") View.GONE else View.VISIBLE
            hostField.isEnabled = route != "tor"
            portField.isEnabled = route != "tor"
            routeHint.text = when (route) {
                "tor" -> "Orbot debe estar iniciado con su puerto SOCKS en 9050. El DNS se resuelve en Tor."
                "socks" -> "SOCKS5 con DNS remoto: el servidor resuelve los nombres."
                "http" -> "Proxy HTTP para el tráfico web de esta pestaña."
                else -> ""
            }
        }
        renderRoutes()
        body.addView(routeHolder)
        body.addView(serverCard, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = NexoUi.dp(activity, 10f)
        })

        // --- Pruebas -------------------------------------------------------
        body.addView(NexoUi.section(activity, "Simulación para pruebas"))
        val testCard = NexoUi.card(activity)
        var latency = current.latency.coerceIn(0, 2000)
        val latencyLabel = TextView(activity).apply { textSize = 14.5f; setTextColor(p.text) }
        fun renderLatency() { latencyLabel.text = if (latency == 0) "Retardo añadido: ninguno" else "Retardo añadido: $latency ms" }
        renderLatency()
        testCard.addView(latencyLabel)
        testCard.addView(SeekBar(activity).apply {
            max = 40 // pasos de 50 ms
            progress = latency / 50
            progressTintList = android.content.res.ColorStateList.valueOf(p.accent)
            thumbTintList = android.content.res.ColorStateList.valueOf(p.accent)
            contentDescription = "Retardo añadido a cada solicitud"
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, value: Int, fromUser: Boolean) { latency = value * 50; renderLatency() }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, NexoUi.dp(activity, 40f)))
        var simulate = current.latitude != null
        val (latView, latField) = NexoUi.field(activity, "Latitud", "4.7110", current.latitude?.toString().orEmpty(), numeric = true)
        val (lonView, lonField) = NexoUi.field(activity, "Longitud", "-74.0721", current.longitude?.toString().orEmpty(), numeric = true)
        val coordinates = NexoUi.pair(activity, latView, 1f, lonView, 1f).apply { visibility = if (simulate) View.VISIBLE else View.GONE }
        testCard.addView(NexoUi.switchRow(activity, "Simular ubicación", "La página recibe estas coordenadas; tu IP no cambia.", simulate) {
            simulate = it; coordinates.visibility = if (it) View.VISIBLE else View.GONE
        })
        testCard.addView(coordinates)
        body.addView(testCard)

        // --- Aplicar -------------------------------------------------------
        val error = TextView(activity).apply {
            textSize = 12.5f; setTextColor(p.danger); visibility = View.GONE
            setPadding(NexoUi.dp(activity, 4f), NexoUi.dp(activity, 8f), 0, 0)
        }
        body.addView(error)
        body.addView(NexoUi.button(activity, "Aplicar y recargar", primary = true) {
            if (TabManager.activeTab()?.id != tab.id) { sheet.dismiss(); return@button }
            try {
                val type = when (route) { "socks", "tor" -> "socks"; "http" -> "http"; else -> "direct" }
                val profile = TabNetworkProfile(
                    type,
                    if (type == "direct") "" else hostField.text.toString().trim(),
                    if (type == "direct") 0 else portField.text.toString().trim().toInt(),
                    latency,
                    if (simulate) latField.text.toString().trim().toDouble() else null,
                    if (simulate) lonField.text.toString().trim().toDouble() else null
                ).validated()
                val empty = profile.type == "direct" && profile.latency == 0 && profile.latitude == null
                TabManager.applyNetwork(tab.id, if (empty) null else profile)
                sheet.dismiss()
            } catch (_: Exception) {
                error.text = when {
                    route != "direct" && hostField.text.isBlank() -> "Escribe el servidor del proxy."
                    route != "direct" && portField.text.toString().toIntOrNull() !in 1..65535 -> "El puerto debe estar entre 1 y 65535."
                    simulate -> "Revisa las coordenadas: latitud −90 a 90 y longitud −180 a 180."
                    else -> "Revisa los datos de la ruta."
                }
                error.visibility = View.VISIBLE
            }
        }, NexoUi.buttonParams(activity, 14f))
        if (tab.networkProfile != null) {
            body.addView(NexoUi.button(activity, "Quitar ajustes de red") {
                TabManager.applyNetwork(tab.id, null); sheet.dismiss()
            }, NexoUi.buttonParams(activity, 8f))
        }

        // --- Más -----------------------------------------------------------
        body.addView(NexoUi.section(activity, "Más herramientas"))
        body.addView(NexoUi.actionRow(activity, R.drawable.ic_menu_find, "Ver IP de salida", "Consulta api.ipify.org en esta pestaña") {
            sheet.dismiss(); TabManager.navigate("https://api.ipify.org/?format=json")
        })
        body.addView(NexoUi.actionRow(activity, R.drawable.ic_menu_settings, "DNS del navegador", dnsLabel(activity) + " · afecta a todas las pestañas") {
            sheet.dismiss(); dns(activity)
        })
        body.addView(NexoUi.actionRow(activity, R.drawable.ic_menu_private, "Nueva identidad Burner", "Contenedor aislado con correo temporal") {
            sheet.dismiss(); BurnerMailbox.open(activity)
        })
        tab.burnerId?.let { id ->
            body.addView(NexoUi.actionRow(activity, R.drawable.ic_menu_file, "Abrir buzón Burner", "Mensajes del correo temporal") {
                sheet.dismiss(); BurnerMailbox.show(activity, id)
            })
            body.addView(NexoUi.actionRow(activity, R.drawable.ic_menu_close, "Destruir esta identidad", "Cierra sus pestañas y borra sus datos", danger = true) {
                sheet.dismiss()
                AlertDialog.Builder(activity).setTitle("¿Cerrar y borrar esta identidad?")
                    .setMessage("Se cerrarán todas sus pestañas. Los archivos descargados y las copias que hayas hecho fuera de Nexo permanecen.")
                    .setNegativeButton("Cancelar", null).setPositiveButton("Destruir") { _, _ ->
                        TabManager.allTabs().filter { it.burnerId == id }.map { it.id }.forEach { TabManager.closeTab(it) }
                    }.show()
            })
        }
        body.addView(NexoUi.note(activity,
            "La ruta solo afecta al tráfico web de esta pestaña; no es una VPN." +
                if (tab.burnerId != null) " Burner aísla cookies y datos; no garantiza anonimato." else ""
        ))

        val subtitle = listOfNotNull(host.ifBlank { null }, tab.networkState.takeIf { it.isNotBlank() }).joinToString(" · ")
        sheet = NexoSheets.show(activity, NexoSheets.column(activity,
            NexoSheets.header(activity, if (tab.burnerId != null) "Herramientas · Burner" else "Herramientas de pestaña", subtitle), body))
        sheet.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
    }

    private fun dnsLabel(activity: Activity): String = when (BrowserPrefs.dnsProvider(activity)) {
        BrowserPrefs.DNS_CLOUDFLARE -> "Cloudflare"
        BrowserPrefs.DNS_GOOGLE -> "Google"
        BrowserPrefs.DNS_QUAD9 -> "Quad9"
        BrowserPrefs.DNS_CUSTOM -> "DoH personalizado"
        else -> "Sistema"
    }

    private fun dns(activity: Activity) {
        lateinit var sheet: Dialog
        val names = listOf("Sistema", "Cloudflare", "Google", "Quad9", "Personalizado")
        val values = listOf(BrowserPrefs.DNS_SYSTEM, BrowserPrefs.DNS_CLOUDFLARE, BrowserPrefs.DNS_GOOGLE, BrowserPrefs.DNS_QUAD9, BrowserPrefs.DNS_CUSTOM)
        var selected = BrowserPrefs.dnsProvider(activity).takeIf { it in values } ?: BrowserPrefs.DNS_SYSTEM
        var strict = BrowserPrefs.dnsStrict(activity)
        val body = NexoUi.column(activity)
        val chips = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val (customView, uri) = NexoUi.field(activity, "Dirección DoH", "https://servidor/dns-query", BrowserPrefs.dnsCustom(activity))
        fun render() {
            chips.removeAllViews()
            chips.addView(NexoUi.chipRow(activity, names.mapIndexed { index, name ->
                NexoUi.chip(activity, name, values[index] == selected) { selected = values[index]; render() }
            }))
            customView.visibility = if (selected == BrowserPrefs.DNS_CUSTOM) View.VISIBLE else View.GONE
        }
        render()
        body.addView(NexoUi.section(activity, "Proveedor"))
        body.addView(chips)
        body.addView(customView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = NexoUi.dp(activity, 8f)
        })
        body.addView(NexoUi.switchRow(activity, "DNS estricto", "Bloquear la navegación si falla DoH, sin volver al DNS del sistema.", strict) { strict = it })
        body.addView(NexoUi.note(activity, "Afecta a todas las pestañas. Para Pi-hole usa una pasarela DoH (HTTPS)."))
        body.addView(NexoUi.button(activity, "Guardar", primary = true) {
            if (selected == BrowserPrefs.DNS_CUSTOM && !DnsProfile.valid(uri.text.toString().trim())) {
                uri.error = "Usa HTTPS, sin credenciales ni parámetros."; return@button
            }
            BrowserPrefs.setDnsCustom(activity, uri.text.toString().trim())
            BrowserPrefs.setDnsStrict(activity, strict)
            BrowserPrefs.setDnsProvider(activity, selected)
            GeckoRuntimeHolder.applyRuntimePrefs(activity)
            sheet.dismiss()
            Toastish.show(activity, "DNS guardado: " + dnsLabel(activity) + ".")
        }, NexoUi.buttonParams(activity, 14f))
        sheet = NexoSheets.show(activity, NexoSheets.column(activity, NexoSheets.header(activity, "DNS de todo Nexo", "Se aplica a todas las pestañas"), body))
        sheet.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
    }
}

/** Aviso breve sin depender de un contexto concreto. */
object Toastish {
    fun show(activity: Activity, message: String) {
        android.widget.Toast.makeText(activity, message, android.widget.Toast.LENGTH_SHORT).show()
    }
}
