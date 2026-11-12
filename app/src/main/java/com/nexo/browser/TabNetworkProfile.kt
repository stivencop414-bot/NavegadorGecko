package com.nexo.browser

import org.json.JSONObject

/** No passwords: endpoint settings are per tab; DNS over SOCKS is always remote. */
data class TabNetworkProfile(val type: String = "direct", val host: String = "", val port: Int = 0,
    val latency: Int = 0, val latitude: Double? = null, val longitude: Double? = null) {
    fun validated(): TabNetworkProfile {
        require(type in setOf("direct","socks","http"))
        require(latency in 0..2000)
        if(type != "direct") require(host.isNotBlank() && host.length <= 253 && host.matches(Regex("[a-zA-Z0-9.\\[\\]:-]+")) && port in 1..65535)
        if(latitude != null || longitude != null) require(latitude != null && longitude != null && latitude.isFinite() && longitude.isFinite() && latitude in -90.0..90.0 && longitude in -180.0..180.0)
        return this
    }
    fun json() = JSONObject().put("type",type).put("host",host).put("port",port).put("latency",latency).apply {
        if(latitude != null) put("geo",JSONObject().put("lat",latitude).put("lon",longitude))
    }
    companion object {
        fun fromJson(json: JSONObject?): TabNetworkProfile? = if(json == null) null else runCatching {
            TabNetworkProfile(json.optString("type","direct"),json.optString("host"),json.optInt("port"),json.optInt("latency"),
                json.optJSONObject("geo")?.getDouble("lat"),json.optJSONObject("geo")?.getDouble("lon")).validated()
        }.getOrElse { TabNetworkProfile(type="invalid") }
    }
}
