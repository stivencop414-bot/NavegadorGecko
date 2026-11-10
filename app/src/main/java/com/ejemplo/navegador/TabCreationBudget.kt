package com.ejemplo.navegador

/** Caps bursts from extensions without retaining URLs or browsing history. */
class TabCreationBudget {
    private val created = java.util.ArrayDeque<Long>()
    fun allow(now: Long): Boolean {
        while (created.isNotEmpty() && now - created.first() >= 60000L) created.removeFirst()
        if (created.size >= 20 || created.count { now - it < 10000L } >= 4) return false
        created.addLast(now)
        return true
    }
}
