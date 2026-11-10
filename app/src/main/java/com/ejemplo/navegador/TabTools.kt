package com.ejemplo.navegador

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.text.InputType
import android.view.ViewGroup
import android.widget.*

/** Direct address-bar controls; every control states its actual scope. */
object TabTools {
    fun show(activity: Activity) {
        val tab = TabManager.activeTab() ?: return
        lateinit var dialog: AlertDialog
        val box = LinearLayout(activity).apply { orientation=LinearLayout.VERTICAL; setPadding(28,12,28,16) }
        fun label(value: String) { box.addView(TextView(activity).apply { text=value; setPadding(0,16,0,8); ThemeManager.styleText(activity,this,true) }) }
        fun field(hint: String, value: String = "", number: Boolean = false) = EditText(activity).apply {
            this.hint=hint; setText(value); setSingleLine(true); ThemeManager.styleEdit(activity,this)
            if(number) inputType=InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
            box.addView(this,LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,48*(activity.resources.displayMetrics.density).toInt().coerceAtLeast(1)))
        }
        fun button(text: String, action: () -> Unit) { box.addView(Button(activity).apply { this.text=text; ThemeManager.styleButton(activity,this);setOnClickListener { dialog.dismiss(); action() } }) }
        val current=tab.networkProfile ?: TabNetworkProfile()
        label("Red web de esta pestaña · ${tab.networkState}")
        label("Grupo: ${tab.groupName ?: "Sin grupo"}. Para mover una pestaña, mantén pulsada su miniatura.")
        button("Abrir grupos de pestañas") { TabSwitcher.show(activity) { privateMode -> TabManager.createTab(BrowserPrefs.homePage(activity),privateMode,true) } }
        val types=listOf("Conexión del sistema","SOCKS5 (DNS remoto)","Proxy HTTP","Tor mediante Orbot (SOCKS5)")
        val type=Spinner(activity).apply { adapter=ThemeManager.spinnerAdapter(activity,types); ThemeManager.styleSpinner(activity,this); box.addView(this) }
        type.setSelection(when(current.type){"socks"->1;"http"->2;else->0})
        val host=field("Host del servidor",current.host)
        val port=field("Puerto",if(current.port>0)current.port.toString() else "",true)
        label("Retardo añadido al inicio de cada solicitud (0–2000 ms)")
        val latency=field("Retardo",current.latency.toString(),true)
        val simulate=CheckBox(activity).apply { text="Simular geolocalización para esta página";isChecked=current.latitude!=null;box.addView(this) }
        val lat=field("Latitud",current.latitude?.toString().orEmpty(),true)
        val lon=field("Longitud",current.longitude?.toString().orEmpty(),true)
        type.onItemSelectedListener=object:AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent:AdapterView<*>?)=Unit
            override fun onItemSelected(parent:AdapterView<*>?,view:android.view.View?,position:Int,id:Long) {
                host.isEnabled=position!=0;port.isEnabled=position!=0
                if(position==3) { host.setText("127.0.0.1");port.setText("9050") }
            }
        }
        label("Configura tu servidor; Orbot debe estar iniciado y su puerto debe coincidir. La ruta cubre tráfico web HTTP(S), no crea una VPN. Mientras haya proxy, WebRTC y precargas se desactivan en todo Nexo y se bloquean solicitudes de fondo sin pestaña. Buzón, IA y listas usan conexiones propias. La ubicación simulada no cambia tu IP. Aplicar recarga esta pestaña.")
        button("Ver IP de salida (api.ipify.org)") { TabManager.navigate("https://api.ipify.org/?format=json") }
        button("DNS del navegador (afecta a todas las pestañas)") { dns(activity) }
        button("Crear identidad Burner + correo temporal") { BurnerMailbox.open(activity) }
        tab.burnerId?.let { id ->
            label("Burner: contenedor aislado. Canvas/WebGL varían de forma experimental; no garantiza anonimato. Las pestañas hijas comparten esta identidad. El buzón no se incluye en copias ni restauraciones.")
            button("Abrir buzón Burner") { BurnerMailbox.show(activity,id) }
            button("Destruir esta identidad Burner") {
                AlertDialog.Builder(activity).setTitle("¿Cerrar y borrar esta identidad?")
                    .setMessage("Se cerrarán todas sus pestañas. Los archivos descargados y las copias que hayas hecho fuera de Nexo permanecen.")
                    .setNegativeButton("Cancelar",null).setPositiveButton("Destruir") { _, _ ->
                        TabManager.allTabs().filter { it.burnerId == id }.map { it.id }.forEach { TabManager.closeTab(it) }
                    }.show()
            }
        }
        dialog=AlertDialog.Builder(activity).setTitle("Herramientas de pestaña").setView(ScrollView(activity).apply { addView(box) })
            .setNegativeButton("Cerrar",null).setPositiveButton("Aplicar y recargar",null).create()
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            if(TabManager.activeTab()?.id != tab.id) { dialog.dismiss(); return@setOnClickListener }
            try {
                val routeType=when(type.selectedItemPosition){1,3->"socks";2->"http";else->"direct"}
                val profile=TabNetworkProfile(routeType,host.text.toString().trim(),if(routeType=="direct")0 else port.text.toString().toInt(),latency.text.toString().toInt(),
                    if(simulate.isChecked)lat.text.toString().toDouble() else null,if(simulate.isChecked)lon.text.toString().toDouble() else null).validated()
                val empty=profile.type=="direct" && profile.latency==0 && profile.latitude==null
                TabManager.applyNetwork(tab.id,if(empty)null else profile);dialog.dismiss()
            } catch(_:Exception) { host.error="Revisa servidor, puerto, retardo y coordenadas." }
        } };dialog.show()
    }
    private fun dns(activity:Activity) {
        val box=LinearLayout(activity).apply { orientation=LinearLayout.VERTICAL;setPadding(32,16,32,16) }
        val names=listOf("Sistema","Cloudflare","Google","Quad9","DoH personalizado / Pi-hole con pasarela DoH")
        val values=listOf(BrowserPrefs.DNS_SYSTEM,BrowserPrefs.DNS_CLOUDFLARE,BrowserPrefs.DNS_GOOGLE,BrowserPrefs.DNS_QUAD9,BrowserPrefs.DNS_CUSTOM)
        val provider=Spinner(activity).apply { adapter=ThemeManager.spinnerAdapter(activity,names);setSelection(values.indexOf(BrowserPrefs.dnsProvider(activity)).coerceAtLeast(0));box.addView(this) }
        val uri=EditText(activity).apply { hint="https://servidor/dns-query";setText(BrowserPrefs.dnsCustom(activity));setSingleLine(true);ThemeManager.styleEdit(activity,this);box.addView(this) }
        val strict=CheckBox(activity).apply { text="DNS estricto: bloquear si falla DoH";isChecked=BrowserPrefs.dnsStrict(activity);box.addView(this) }
        box.addView(TextView(activity).apply { text="Este ajuste es global. Un Pi-hole UDP local necesita una pasarela HTTPS DoH. SOCKS5 usa el DNS del proxy para esa pestaña.";ThemeManager.styleText(activity,this,true) })
        val dialog=AlertDialog.Builder(activity).setTitle("DNS de todo Nexo").setView(box).setNegativeButton("Cancelar",null).setPositiveButton("Guardar",null).create()
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val selected=values[provider.selectedItemPosition]
            if(selected==BrowserPrefs.DNS_CUSTOM && !DnsProfile.valid(uri.text.toString())) { uri.error="Usa HTTPS, sin credenciales ni parámetros.";return@setOnClickListener }
            BrowserPrefs.setDnsCustom(activity,uri.text.toString().trim());BrowserPrefs.setDnsStrict(activity,strict.isChecked);BrowserPrefs.setDnsProvider(activity,selected)
            GeckoRuntimeHolder.applyRuntimePrefs(activity);dialog.dismiss()
        } };dialog.show()
    }
}
