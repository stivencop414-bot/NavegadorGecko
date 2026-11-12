package com.nexo.browser
import java.net.URI
object DnsProfile {
    fun valid(value:String):Boolean = runCatching { val u=URI(value.trim());u.scheme=="https" && !u.host.isNullOrBlank() && u.userInfo==null && u.query==null && u.fragment==null }.getOrDefault(false)
}
