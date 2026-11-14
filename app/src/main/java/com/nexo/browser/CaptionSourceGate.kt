package com.nexo.browser

/** Reserves ownership BEFORE Android's permission activity can pause/resume Nexo. */
class CaptionSourceGate {
    enum class Source { PAGE, ANDROID_PENDING, ANDROID }
    var source = Source.PAGE
        private set
    val acceptsPage get() = source == Source.PAGE
    val pending get() = source == Source.ANDROID_PENDING
    fun requestAndroid() { source = Source.ANDROID_PENDING }
    fun startAndroid(): Boolean {
        if (!pending) return false
        source = Source.ANDROID
        return true
    }
    fun reset() { source = Source.PAGE }
}
