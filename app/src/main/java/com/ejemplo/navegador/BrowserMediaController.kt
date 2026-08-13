package com.ejemplo.navegador

import android.os.Handler
import android.os.Looper
import org.mozilla.geckoview.MediaSession

object BrowserMediaController {
    interface Listener { fun onBrowserMediaStateChanged() }

    data class Snapshot(
        val tabId: String,
        val title: String,
        val playing: Boolean
    )

    private data class Entry(
        val mediaSession: MediaSession,
        var title: String,
        var playing: Boolean = false
    )

    private data class VideoState(
        var playing: Boolean = false,
        var width: Int = 0,
        var height: Int = 0
    )

    private val entries = linkedMapOf<String, Entry>()
    private val videos = mutableMapOf<String, VideoState>()
    private val main = Handler(Looper.getMainLooper())
    private var listener: Listener? = null
    private var currentTabId: String? = null

    fun attach(value: Listener?) {
        listener = value
        listener?.onBrowserMediaStateChanged()
    }

    fun onActivated(tab: BrowserTab, media: MediaSession) {
        entries[tab.id] = Entry(media, tab.title.ifBlank { "Multimedia en Nexo" })
        currentTabId = tab.id
        notifyChanged()
    }

    fun onDeactivated(tabId: String, media: MediaSession) {
        if (entries[tabId]?.mediaSession === media) {
            entries.remove(tabId)
            if (currentTabId == tabId) currentTabId = null
        }
        videos.remove(tabId)
        notifyChanged()
    }

    fun onPlay(tab: BrowserTab, media: MediaSession) {
        val entry = entries[tab.id]
        if (entry == null || entry.mediaSession !== media) {
            entries[tab.id] = Entry(media, tab.title.ifBlank { "Multimedia en Nexo" }, true)
        } else {
            if (tab.title.isNotBlank()) entry.title = tab.title
            entry.playing = true
        }
        currentTabId = tab.id
        notifyChanged()
    }

    fun onPause(tabId: String, media: MediaSession) {
        entries[tabId]?.takeIf { it.mediaSession === media }?.playing = false
        notifyChanged()
    }

    fun onStop(tabId: String, media: MediaSession) {
        if (entries[tabId]?.mediaSession === media) entries.remove(tabId)
        videos.remove(tabId)
        if (currentTabId == tabId) currentTabId = null
        notifyChanged()
    }

    fun updateMetadata(tabId: String, media: MediaSession, title: String?) {
        val entry = entries[tabId] ?: return
        if (entry.mediaSession !== media) return
        if (!title.isNullOrBlank()) entry.title = title
        notifyChanged()
    }

    fun updateTabTitle(tabId: String, title: String) {
        val entry = entries[tabId] ?: return
        if (title.isNotBlank()) entry.title = title
        if (entry.playing) notifyChanged()
    }

    fun onVideoState(tabId: String, playing: Boolean, width: Int, height: Int) {
        videos[tabId] = VideoState(playing, width.coerceAtLeast(0), height.coerceAtLeast(0))
        listener?.onBrowserMediaStateChanged()
    }

    fun resetVideo(tabId: String) {
        videos.remove(tabId)
        listener?.onBrowserMediaStateChanged()
    }

    fun removeTab(tabId: String) {
        entries.remove(tabId)
        videos.remove(tabId)
        if (currentTabId == tabId) currentTabId = null
        notifyChanged()
    }

    fun isPlaying(tabId: String) = entries[tabId]?.playing == true
    fun isAnyPlaying() = entries.values.any { it.playing }
    fun isVideoPlaying(tabId: String) = isPlaying(tabId) && videos[tabId]?.playing == true

    fun videoAspect(tabId: String): Pair<Int, Int> {
        val state = videos[tabId]
        return if ((state?.width ?: 0) > 0 && (state?.height ?: 0) > 0) {
            state!!.width to state.height
        } else 16 to 9
    }

    fun snapshot(): Snapshot? {
        val preferred = currentTabId?.let { id ->
            entries[id]?.takeIf { it.playing }?.let { id to it }
        }
        val selected = preferred ?: entries.entries.lastOrNull { it.value.playing }
            ?.let { it.key to it.value } ?: return null
        currentTabId = selected.first
        return Snapshot(selected.first, selected.second.title.ifBlank { "Multimedia en Nexo" }, true)
    }

    fun pauseCurrent() {
        val media = currentPlayingSession() ?: return
        main.post { runCatching { media.pause() } }
    }

    fun stopCurrent() {
        val media = currentPlayingSession() ?: return
        main.post { runCatching { media.stop() } }
    }

    private fun currentPlayingSession(): MediaSession? =
        snapshot()?.tabId?.let { entries[it]?.mediaSession }

    private fun notifyChanged() {
        listener?.onBrowserMediaStateChanged()
        val context = runCatching { AppContext.get() }.getOrNull() ?: return
        MediaPlaybackService.sync(context)
    }
}
