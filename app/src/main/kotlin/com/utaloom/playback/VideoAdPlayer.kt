package com.utaloom.playback

import com.utaloom.data.Paths
import com.utaloom.data.Stores
import java.awt.Component
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter
import uk.co.caprica.vlcj.player.component.EmbeddedMediaPlayerComponent

/** Resolves which local video file (if any) should play as the audio-ad visual. */
object AdVideo {
    private val EXTENSIONS = setOf("mp4", "mov", "mkv", "avi", "webm")
    const val MAX_BYTES: Long = 200L * 1024 * 1024

    fun isCandidate(f: File): Boolean =
        f.isFile && f.length() in 1..MAX_BYTES && f.extension.lowercase() in EXTENSIONS

    /** Explicit setting first, then the bundled ad.mp4 extracted to the data dir. */
    fun resolveFile(): File? {
        Stores.settings.value.adVideoPath.takeIf { it.isNotBlank() }?.let { File(it) }
            ?.takeIf(::isCandidate)?.let { return it }
        return runCatching {
            val out = File(Paths.dir, "ad.mp4")
            val res = AdVideo::class.java.getResourceAsStream("/ad.mp4")
            if (res != null) {
                res.use { input ->
                    if (!out.isFile) out.outputStream().use { input.copyTo(it) }
                }
            }
            out.takeIf(::isCandidate)
        }.getOrNull()
    }
}

/**
 * Plays the ad video on its own VLC instance (separate from the music engine).
 * Falls back gracefully: any failure just ends the ad early / keeps the text overlay.
 */
object VideoAdPlayer {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var component: EmbeddedMediaPlayerComponent? = null

    /** AWT component for Compose SwingPanel. Creates the player lazily. Null when unavailable. */
    @Synchronized
    fun surface(): Component? = runCatching {
        releaseLocked()
        EmbeddedMediaPlayerComponent("--quiet", "--network-caching=1500")
            .also { component = it }.videoSurfaceComponent()
    }.getOrNull()

    fun play(path: String, volume: Int, onStarted: (hasVideo: Boolean) -> Unit, onFinished: () -> Unit) {
        val comp = synchronized(this) { component } ?: run { onFinished(); return }
        var done = false
        fun finishOnce() {
            synchronized(this) { if (done) return; done = true }
            onFinished()
        }
        val listener = object : MediaPlayerEventAdapter() {
            override fun playing(mediaPlayer: MediaPlayer) {
                val hasVideo = runCatching {
                    mediaPlayer.media().info().videoTracks().isNotEmpty()
                }.getOrDefault(false)
                onStarted(hasVideo)
            }

            override fun finished(mediaPlayer: MediaPlayer) = finishOnce()
            override fun error(mediaPlayer: MediaPlayer) = finishOnce()
        }
        scope.launch {
            runCatching {
                comp.mediaPlayer().events().addMediaPlayerEventListener(listener)
                comp.mediaPlayer().audio().setVolume(volume.coerceIn(0, 150))
                if (!comp.mediaPlayer().media().play(path)) finishOnce()
            }.onFailure { finishOnce() }
        }
    }

    @Synchronized fun release() = releaseLocked()

    private fun releaseLocked() {
        runCatching { component?.release() }
        component = null
    }
}
