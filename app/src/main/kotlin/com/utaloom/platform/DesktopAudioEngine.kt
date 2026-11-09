package com.utaloom.playback

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.utaloom.data.Stores
import java.util.concurrent.RejectedExecutionException
import uk.co.caprica.vlcj.factory.MediaPlayerFactory
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter

interface LibVlcCore : Library {
    fun var_SetChecked(obj: Pointer, name: String, type: Int, value: String): Int

    companion object {
        val INSTANCE: LibVlcCore? by lazy {
            com.utaloom.platform.NativeLibs.discoverVlc()
            val dir = com.utaloom.platform.NativeLibs.vlcPath
            if (dir != null) {
                com.sun.jna.NativeLibrary.addSearchPath("vlccore", dir)
                com.sun.jna.NativeLibrary.addSearchPath("libvlccore", dir)
            }
            val fileCandidates = if (dir != null) {
                listOf(
                    java.io.File(dir, "libvlccore.dll"),
                    java.io.File(dir, "libvlccore.dylib"),
                    java.io.File(dir, "libvlccore.so"),
                    java.io.File(dir, "libvlccore.so.9"),
                ).filter { it.exists() }.map { it.absolutePath }
            } else emptyList()

            (fileCandidates + listOf("libvlccore", "vlccore")).firstNotNullOfOrNull { target ->
                runCatching { Native.load(target, LibVlcCore::class.java) }.getOrNull()
            }
        }
    }
}

fun audioEngineOptions(pitch: Int = 0): List<String> {
    val boundedPitch = AudioEffects.sanitizePitch(pitch)
    val filterChain = if (boundedPitch != 0) "scaletempo:scaletempo_pitch" else "scaletempo"
    return buildList {
        add("--no-video")
        add("--network-caching=1500")
        add("--audio-filter=$filterChain")
        add("--audio-replay-gain-mode=none")  // Correct VLC option: stop libVLC altering volume per track
        if (boundedPitch != 0) {
            add("--pitch-shift=$boundedPitch")
        }
        if (System.getenv("UTALOOM_DEBUG") == null) add("--quiet") else add("--verbose=1")
    }
}

/** Each player needs its own libVLC instance so overlapping Automix tracks have independent volume controls. */
fun createAudioEngine(pitch: Int = Stores.settings.value.playbackPitch): AudioEngine {
    val boundedPitch = AudioEffects.sanitizePitch(pitch)
    val owned = MediaPlayerFactory(*audioEngineOptions(boundedPitch).toTypedArray())
    return try {
        AudioEffects.populateFromFactory(owned)
        val player = owned.mediaPlayers().newMediaPlayer()
        DesktopAudioEngine(owned, player, boundedPitch)
    } catch (e: Throwable) {
        owned.release()
        throw e
    }
}

private class DesktopAudioEngine(
    private val factory: MediaPlayerFactory,
    private val player: MediaPlayer,
    private val pitch: Int,
) : AudioEngine {
    private val listeners = mutableMapOf<AudioEngine.Listener, MediaPlayerEventAdapter>()
    private var lastEqEnabled = false

    @Volatile
    private var released = false

    init {
        syncAudioFilter(player, pitch, enabled = false)
    }

    @Synchronized override fun addListener(listener: AudioEngine.Listener) {
        if (listener in listeners) return
        val adapter = object : MediaPlayerEventAdapter() {
            override fun playing(mediaPlayer: MediaPlayer) = listener.playing()
            override fun paused(mediaPlayer: MediaPlayer) = listener.paused()
            override fun stopped(mediaPlayer: MediaPlayer) = listener.stopped()
            override fun buffering(mediaPlayer: MediaPlayer, newCache: Float) = listener.buffering(newCache)
            override fun timeChanged(mediaPlayer: MediaPlayer, newTime: Long) = listener.timeChanged(newTime)
            override fun lengthChanged(mediaPlayer: MediaPlayer, newLength: Long) = listener.lengthChanged(newLength)
            override fun finished(mediaPlayer: MediaPlayer) = listener.finished()
            override fun error(mediaPlayer: MediaPlayer) = listener.error()
        }
        listeners[listener] = adapter
        player.events().addMediaPlayerEventListener(adapter)
    }

    @Synchronized override fun removeListener(listener: AudioEngine.Listener) {
        listeners.remove(listener)?.let { player.events().removeMediaPlayerEventListener(it) }
    }

    override fun submit(task: () -> Unit) {
        // vlcj terminates the media player's executor on release, and Player keeps engines
        // around that can still be handed work afterwards: the outgoing half of a finished
        // blend, or a buffered next track. Rejecting that work must never reach the user as
        // a crash dialog, so a dead engine simply drops what it is asked to do.
        if (released) return
        try {
            player.submit { task() }
        } catch (_: RejectedExecutionException) {
            // Shut down between the check above and the submit: the work no longer matters.
        }
    }
    override fun play() { player.controls().play() }
    override fun setPause(paused: Boolean) { player.controls().setPause(paused) }
    override fun seek(positionMs: Long) { player.controls().setTime(positionMs) }
    override fun stop() { player.controls().stop() }
    override fun setVolume(volume: Int) { player.audio().setVolume(volume) }

    override fun setEqualizer(enabled: Boolean, preamp: Float, bands: List<Float>) {
        lastEqEnabled = enabled
        if (!enabled) {
            player.audio().setEqualizer(null)
            syncAudioFilter(player, pitch, enabled = false)
            return
        }
        val eq = factory.equalizer().newEqualizer()
        eq.setPreamp(AudioEffects.sanitizePreamp(preamp))
        val count = minOf(eq.bandCount(), bands.size)
        for (i in 0 until count) {
            val amp = bands[i]
            if (amp.isFinite()) eq.setAmp(i, AudioEffects.sanitizePreamp(amp))
        }
        player.audio().setEqualizer(eq)
        syncAudioFilter(player, pitch, enabled = true)
    }

    override fun setRate(rate: Float): Boolean {
        val validRate = AudioEffects.sanitizeSpeed(rate)
        return player.controls().setRate(validRate)
    }

    override val rate: Float
        get() = player.status().rate().takeIf { it.isFinite() && it > 0f } ?: 1f

    override fun load(url: String, headers: Map<String, String>, startPaused: Boolean): Boolean {
        // ponytail: VLC supports User-Agent and Referer options, add a custom HTTP layer if other headers are required.
        val options = headers.mapNotNull { (name, value) ->
            when {
                name.equals("User-Agent", ignoreCase = true) -> ":http-user-agent=$value"
                name.equals("Referer", ignoreCase = true) -> ":http-referrer=$value"
                else -> null
            }
        } + listOfNotNull(
            "start-paused".takeIf { startPaused },
            ":http-reconnect".takeIf { url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true) },
        )
        val loaded = player.media().play(url, *options.toTypedArray())
        if (loaded) syncAudioFilter(player, pitch, lastEqEnabled)
        return loaded
    }

    override val time: Long get() = player.status().time()
    override val isSeekable: Boolean get() = player.status().isSeekable

    override fun release() {
        try {
            player.release()
        } finally {
            released = true
            factory.release()
        }
    }
}

internal fun syncAudioFilter(player: MediaPlayer, pitch: Int, enabled: Boolean = false) {
    val bounded = AudioEffects.sanitizePitch(pitch)
    val filterString = when {
        enabled && bounded != 0 -> "equalizer:scaletempo:scaletempo_pitch"
        enabled -> "equalizer:scaletempo"
        bounded != 0 -> "scaletempo:scaletempo_pitch"
        else -> "scaletempo"
    }
    runCatching {
        val ptr = player.mediaPlayerInstance()?.getPointer() ?: return@runCatching
        LibVlcCore.INSTANCE?.var_SetChecked(ptr, "audio-filter", 0x0040, filterString)
    }
}
