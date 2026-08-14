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

    private val entries =
        linkedMapOf<String, Entry>()

    private val videos =
        mutableMapOf<String, VideoState>()

    /*
     * Se mantiene separado del estado "playing".
     *
     * YouTube puede emitir una pausa transitoria exactamente
     * durante Home/PiP. No queremos interpretar esa transición
     * como una orden explícita del usuario.
     */
    private val recentPlayback =
        mutableMapOf<String, Long>()

    private val main =
        Handler(Looper.getMainLooper())

    private var listener: Listener? = null
    private var currentTabId: String? = null

    private fun now(): Long =
        SystemClock.elapsedRealtime()

    private fun markRecentlyPlaying(
        tabId: String
    ) {
        recentPlayback[tabId] = now()
    }

    fun attach(value: Listener?) {
        listener = value
        listener?.onBrowserMediaStateChanged()
    }

    fun onActivated(
        tab: BrowserTab,
        media: MediaSession
    ) {
        val previous = entries[tab.id]

        entries[tab.id] =
            Entry(
                mediaSession = media,
                title =
                    tab.title.ifBlank {
                        previous?.title
                            ?: "Multimedia en Nexo"
                    },
                playing =
                    previous?.playing == true
            )

        currentTabId = tab.id
        notifyChanged()
    }

    fun onDeactivated(
        tabId: String,
        media: MediaSession
    ) {
        if (
            entries[tabId]
                ?.mediaSession === media
        ) {
            entries.remove(tabId)

            if (currentTabId == tabId) {
                currentTabId = null
            }
        }

        /*
         * No borrar recentPlayback aquí:
         * Gecko puede desactivar/recrear MediaSession durante
         * la transición de actividad sin que el usuario haya
         * detenido el contenido.
         */
        notifyChanged()
    }

    fun onPlay(
        tab: BrowserTab,
        media: MediaSession
    ) {
        val entry = entries[tab.id]

        if (
            entry == null ||
            entry.mediaSession !== media
        ) {
            entries[tab.id] =
                Entry(
                    mediaSession = media,
                    title =
                        tab.title.ifBlank {
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
            ?.takeIf {
                it.mediaSession === media
            }
            ?.playing = false

        notifyChanged()
    }

    fun onStop(
        tabId: String,
        media: MediaSession
    ) {
        if (
            entries[tabId]
                ?.mediaSession === media
        ) {
            entries.remove(tabId)
        }

        if (currentTabId == tabId) {
            currentTabId = null
        }

        notifyChanged()
    }

    fun updateMetadata(
        tabId: String,
        media: MediaSession,
        title: String?
    ) {
        val entry =
            entries[tabId]
                ?: return

        if (entry.mediaSession !== media) {
            return
        }

        if (!title.isNullOrBlank()) {
            entry.title = title
        }

        notifyChanged()
    }

    fun updateTabTitle(
        tabId: String,
        title: String
    ) {
        val entry =
            entries[tabId]
                ?: return

        if (title.isNotBlank()) {
            entry.title = title
        }

        if (entry.playing) {
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
        videos[tabId] =
            VideoState(
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

        if (currentTabId == tabId) {
            currentTabId = null
        }

        notifyChanged()
    }

    fun isPlaying(tabId: String): Boolean =
        entries[tabId]?.playing == true

    fun isAnyPlaying(): Boolean =
        entries.values.any {
            it.playing
        }

    fun isVideoPresent(tabId: String): Boolean =
        videos[tabId]?.present == true

    fun isVideoPlaying(tabId: String): Boolean =
        videos[tabId]?.playing == true

    fun isPlaybackActive(
        tabId: String
    ): Boolean =
        isPlaying(tabId) ||
            isVideoPlaying(tabId)

    fun wasRecentlyPlaying(
        tabId: String,
        windowMs: Long = 6_000L
    ): Boolean {
        val last =
            recentPlayback[tabId]
                ?: return false

        return now() - last <=
            windowMs.coerceAtLeast(0L)
    }

    fun shouldKeepAlive(
        tabId: String,
        windowMs: Long = 6_000L
    ): Boolean =
        isPlaybackActive(tabId) ||
            wasRecentlyPlaying(
                tabId,
                windowMs
            )

    fun videoAspect(
        tabId: String
    ): Pair<Int, Int> {
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
     * Recuperación nativa.
     *
     * Solo se invoca cuando MainActivity sabe que el contenido
     * estaba reproduciéndose ANTES de la transición.
     * Por eso no fuerza a reproducir un video que el usuario
     * hubiera dejado pausado.
     */
    fun resumeIfRecent(
        tabId: String,
        windowMs: Long = 6_000L
    ): Boolean {
        if (
            !wasRecentlyPlaying(
                tabId,
                windowMs
            )
        ) {
            return false
        }

        val media =
            entries[tabId]
                ?.mediaSession
                ?: return false

        main.post {
            runCatching {
                if (media.isActive) {
                    media.play()
                }
            }
        }

        return true
    }

    fun snapshot(): Snapshot? {
        val preferred =
            currentTabId
                ?.let { id ->
                    entries[id]
                        ?.takeIf {
                            it.playing
                        }
                        ?.let {
                            id to it
                        }
                }

        val selected =
            preferred
                ?: entries.entries
                    .lastOrNull {
                        it.value.playing
                    }
                    ?.let {
                        it.key to it.value
                    }
                ?: return null

        currentTabId = selected.first

        return Snapshot(
            tabId = selected.first,
            title =
                selected.second
                    .title
                    .ifBlank {
                        "Multimedia en Nexo"
                    },
            playing = true
        )
    }

    fun pauseCurrent() {
        val media =
            currentPlayingSession()
                ?: return

        main.post {
            runCatching {
                media.pause()
            }
        }
    }

    fun stopCurrent() {
        val media =
            currentPlayingSession()
                ?: return

        main.post {
            runCatching {
                media.stop()
            }
        }
    }

    private fun currentPlayingSession():
        MediaSession? =
        snapshot()
            ?.tabId
            ?.let {
                entries[it]
                    ?.mediaSession
            }

    private fun notifyChanged() {
        listener
            ?.onBrowserMediaStateChanged()

        val context =
            runCatching {
                AppContext.get()
            }.getOrNull()
                ?: return

        MediaPlaybackService.sync(
            context
        )
    }
}
