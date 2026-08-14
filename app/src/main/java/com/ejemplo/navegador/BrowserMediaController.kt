package com.ejemplo.navegador

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import org.mozilla.geckoview.MediaSession

object BrowserMediaController {
    interface Listener {
        fun onBrowserMediaStateChanged()
    }

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
        var present: Boolean = false,
        var playing: Boolean = false,
        var width: Int = 0,
        var height: Int = 0
    )

    private val entries = linkedMapOf<String, Entry>()
    private val videos = mutableMapOf<String, VideoState>()
    private val recentPlayback = mutableMapOf<String, Long>()

    /*
     * Ventana de protección para Home/PiP.
     * Durante esta ventana una pausa transitoria emitida por el sitio
     * no debe apagar GeckoSession ni el foreground service.
     */
    private val playbackHoldUntil = mutableMapOf<String, Long>()

    private val main = Handler(Looper.getMainLooper())

    private var listener: Listener? = null
    private var currentTabId: String? = null

    private fun now(): Long = SystemClock.elapsedRealtime()

    private fun markRecentlyPlaying(tabId: String) {
        recentPlayback[tabId] = now()
    }

    private fun hasActiveHold(tabId: String): Boolean {
        val until = playbackHoldUntil[tabId] ?: return false
        if (now() >= until) {
            playbackHoldUntil.remove(tabId)
            return false
        }
        return true
    }

    private fun cleanupExpiredHold(tabId: String) {
        if (hasActiveHold(tabId)) return

        playbackHoldUntil.remove(tabId)

        val entry = entries[tabId]
        if (
            entry != null &&
            !entry.playing &&
            videos[tabId]?.playing != true &&
            !entry.mediaSession.isActive
        ) {
            entries.remove(tabId)
            if (currentTabId == tabId) {
                currentTabId = null
            }
        }

        notifyChanged()
    }

    fun holdPlayback(
        tabId: String,
        durationMs: Long = 10_000L
    ) {
        val duration = durationMs.coerceAtLeast(1_000L)
        val until = now() + duration
        val previous = playbackHoldUntil[tabId] ?: 0L
        playbackHoldUntil[tabId] = maxOf(previous, until)

        main.postDelayed(
            { cleanupExpiredHold(tabId) },
            duration + 100L
        )

        notifyChanged()
    }

    fun clearPlaybackHold(tabId: String) {
        if (playbackHoldUntil.remove(tabId) != null) {
            notifyChanged()
        }
    }

    fun isPlaybackHoldActive(tabId: String): Boolean =
        hasActiveHold(tabId)

    fun attach(value: Listener?) {
        listener = value
        listener?.onBrowserMediaStateChanged()
    }

    fun onActivated(
        tab: BrowserTab,
        media: MediaSession
    ) {
        val previous = entries[tab.id]

        entries[tab.id] = Entry(
            mediaSession = media,
            title = tab.title.ifBlank {
                previous?.title ?: "Multimedia en Nexo"
            },
            playing = previous?.playing == true
        )

        currentTabId = tab.id
        notifyChanged()
    }

    fun onDeactivated(
        tabId: String,
        media: MediaSession
    ) {
        val entry = entries[tabId]

        if (entry?.mediaSession === media) {
            if (hasActiveHold(tabId)) {
                entry.playing = false
            } else {
                entries.remove(tabId)
                if (currentTabId == tabId) {
                    currentTabId = null
                }
            }
        }

        notifyChanged()
    }

    fun onPlay(
        tab: BrowserTab,
        media: MediaSession
    ) {
        val entry = entries[tab.id]

        if (entry == null || entry.mediaSession !== media) {
            entries[tab.id] = Entry(
                mediaSession = media,
                title = tab.title.ifBlank {
                    "Multimedia en Nexo"
                },
                playing = true
            )
        } else {
            if (tab.title.isNotBlank()) {
                entry.title = tab.title
            }
            entry.playing = true
        }

        currentTabId = tab.id
        markRecentlyPlaying(tab.id)
        notifyChanged()
    }

    fun onPause(
        tabId: String,
        media: MediaSession
    ) {
        entries[tabId]
            ?.takeIf { it.mediaSession === media }
            ?.playing = false

        notifyChanged()
    }

    fun onStop(
        tabId: String,
        media: MediaSession
    ) {
        val entry = entries[tabId]

        if (entry?.mediaSession === media) {
            if (hasActiveHold(tabId)) {
                entry.playing = false
            } else {
                entries.remove(tabId)
                recentPlayback.remove(tabId)
                if (currentTabId == tabId) {
                    currentTabId = null
                }
            }
        }

        notifyChanged()
    }

    fun updateMetadata(
        tabId: String,
        media: MediaSession,
        title: String?
    ) {
        val entry = entries[tabId] ?: return
        if (entry.mediaSession !== media) return

        if (!title.isNullOrBlank()) {
            entry.title = title
        }

        notifyChanged()
    }

    fun updateTabTitle(
        tabId: String,
        title: String
    ) {
        val entry = entries[tabId] ?: return

        if (title.isNotBlank()) {
            entry.title = title
        }

        if (entry.playing || hasActiveHold(tabId)) {
            notifyChanged()
        }
    }

    fun onVideoState(
        tabId: String,
        present: Boolean,
        playing: Boolean,
        width: Int,
        height: Int
    ) {
        videos[tabId] = VideoState(
            present = present,
            playing = playing,
            width = width.coerceAtLeast(0),
            height = height.coerceAtLeast(0)
        )

        if (playing) {
            markRecentlyPlaying(tabId)
        }

        listener?.onBrowserMediaStateChanged()
    }

    fun resetVideo(tabId: String) {
        videos.remove(tabId)
        listener?.onBrowserMediaStateChanged()
    }

    fun removeTab(tabId: String) {
        entries.remove(tabId)
        videos.remove(tabId)
        recentPlayback.remove(tabId)
        playbackHoldUntil.remove(tabId)

        if (currentTabId == tabId) {
            currentTabId = null
        }

        notifyChanged()
    }

    fun isPlaying(tabId: String): Boolean =
        entries[tabId]?.playing == true

    fun isAnyPlaying(): Boolean =
        entries.values.any { it.playing }

    fun isVideoPresent(tabId: String): Boolean =
        videos[tabId]?.present == true

    fun isVideoPlaying(tabId: String): Boolean =
        videos[tabId]?.playing == true

    fun isPlaybackActive(tabId: String): Boolean =
        isPlaying(tabId) || isVideoPlaying(tabId)

    fun wasRecentlyPlaying(
        tabId: String,
        windowMs: Long = 6_000L
    ): Boolean {
        val last = recentPlayback[tabId] ?: return false
        return now() - last <= windowMs.coerceAtLeast(0L)
    }

    fun shouldKeepAlive(
        tabId: String,
        windowMs: Long = 6_000L
    ): Boolean =
        isPlaybackActive(tabId) ||
            hasActiveHold(tabId) ||
            wasRecentlyPlaying(tabId, windowMs)

    fun videoAspect(tabId: String): Pair<Int, Int> {
        val state = videos[tabId]
        return if (
            (state?.width ?: 0) > 0 &&
            (state?.height ?: 0) > 0
        ) {
            state!!.width to state.height
        } else {
            16 to 9
        }
    }

    /*
     * Solo reanuda automáticamente cuando existe una transición
     * explícitamente protegida por MainActivity.
     * Esto evita reactivar un contenido que el usuario pausó a mano.
     */
    fun resumeIfRecent(
        tabId: String,
        @Suppress("UNUSED_PARAMETER") windowMs: Long = 6_000L
    ): Boolean {
        if (!hasActiveHold(tabId)) {
            return false
        }

        val media = entries[tabId]?.mediaSession ?: return false

        main.post {
            runCatching {
                if (media.isActive) {
                    media.play()
                }
            }
        }

        return true
    }

    private fun snapshotFor(
        allowHold: Boolean
    ): Snapshot? {
        fun usable(id: String): Boolean =
            isPlaybackActive(id) ||
                (allowHold && hasActiveHold(id))

        val preferredId =
            currentTabId?.takeIf { id ->
                entries[id] != null && usable(id)
            }

        val selectedId =
            preferredId
                ?: entries.keys.lastOrNull { usable(it) }
                ?: return null

        val entry = entries[selectedId] ?: return null
        currentTabId = selectedId

        return Snapshot(
            tabId = selectedId,
            title = entry.title.ifBlank {
                "Multimedia en Nexo"
            },
            playing = isPlaybackActive(selectedId)
        )
    }

    fun snapshot(): Snapshot? =
        snapshotFor(allowHold = false)

    fun snapshotForService(): Snapshot? =
        snapshotFor(allowHold = true)

    fun pauseCurrent() {
        val id = currentTabId ?: entries.keys.lastOrNull() ?: return
        val entry = entries[id] ?: return

        playbackHoldUntil.remove(id)
        recentPlayback.remove(id)
        entry.playing = false
        notifyChanged()

        main.post {
            runCatching {
                entry.mediaSession.pause()
            }
        }
    }

    fun stopCurrent() {
        val id = currentTabId ?: entries.keys.lastOrNull() ?: return
        val entry = entries[id] ?: return

        playbackHoldUntil.remove(id)
        recentPlayback.remove(id)
        entry.playing = false
        notifyChanged()

        main.post {
            runCatching {
                entry.mediaSession.stop()
            }
        }
    }

    private fun notifyChanged() {
        listener?.onBrowserMediaStateChanged()

        val context = runCatching {
            AppContext.get()
        }.getOrNull() ?: return

        MediaPlaybackService.sync(context)
    }
}
