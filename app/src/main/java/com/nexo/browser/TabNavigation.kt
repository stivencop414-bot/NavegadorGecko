package com.nexo.browser

import java.net.URI

/** A history snapshot is usable only for the URL it actually represents. */
object TabNavigation {
    fun canRestore(currentUrl: String, snapshotUrl: String?): Boolean =
        snapshotUrl != null && currentUrl == snapshotUrl

    fun needsWindowConfirmation(opener: String, target: String): Boolean = runCatching {
        val a = URI(opener); val b = URI(target)
        fun port(u: URI) = if (u.port >= 0) u.port else if (u.scheme.equals("https", true)) 443 else 80
        a.host == null || b.host == null || !a.scheme.equals(b.scheme, true) ||
            !a.host.equals(b.host, true) || port(a) != port(b)
    }.getOrDefault(true)
}
