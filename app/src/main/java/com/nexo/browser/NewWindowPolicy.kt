package com.nexo.browser

/** One short-lived permission per user request; redirects cannot create more windows. */
class NewWindowPolicy {
    private var pending: String? = null
    private var allowedAt = Long.MIN_VALUE
    private var lastOpenedAt: Long? = null

    fun authorize(uri: String, hasGesture: Boolean, now: Long): Boolean {
        pending = null
        if (!hasGesture || lastOpenedAt?.let { now - it < 750L } == true) return false
        pending = uri
        allowedAt = now
        return true
    }

    fun cancel() { pending = null }

    fun consume(uri: String, now: Long): Boolean {
        val expected = pending ?: return false
        pending = null
        if (now - allowedAt !in 0..2500L || (uri.isNotBlank() && uri != expected)) return false
        lastOpenedAt = now
        return true
    }
}
