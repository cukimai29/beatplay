package com.utaloom

import com.utaloom.data.Song
import com.utaloom.data.Stores
import com.utaloom.platform.NativeLibs
import com.utaloom.playback.Player
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.collect
import java.io.File
import javax.sound.sampled.AudioFileFormat
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem

/**
 * Live acceptance for Automix, using local audio so the check is fast and never depends on
 * what YouTube will serve the machine that runs it. Uses libVLC, so the Gradle task isolates it.
 *
 * Two tones stand in for two tracks. The checks are about the handover: it must start inside
 * the blend window (not at the end of the track), the incoming track must play from its own
 * stream, and replacing the queue mid-blend must not disturb the engines.
 */
fun main() = runBlocking {
    val defaults = Stores.settings.value
    val dir = File(System.getProperty("java.io.tmpdir"), "beatplay-automix").apply { mkdirs() }
    val first = tone(dir, "automix-first.wav", 6_000, 392.0)
    val second = tone(dir, "automix-second.wav", 6_000, 587.0)
    val a = Song("automixfirst01", "Tone A", listOf("Utaloom"), duration = 6)
    val b = Song("automixsecond1", "Tone B", listOf("Utaloom"), duration = 6)
    val crossfadeSeconds = 3
    var lastOutgoingPosition = -1L
    var handoverAt = -1L
    var watcher: kotlinx.coroutines.Job? = null
    try {
        check(NativeLibs.discoverVlc() == null && Player.init()) { "Native VLC unavailable" }
        watcher = launch(start = CoroutineStart.UNDISPATCHED) {
            Player.state.collect { state ->
                if (state.current?.id == a.id) {
                    lastOutgoingPosition = state.positionMs
                } else if (state.current?.id == b.id && handoverAt < 0 && lastOutgoingPosition >= 0) {
                    handoverAt = lastOutgoingPosition
                }
            }
        }
        // Keep the native audio path unmuted: this acceptance is intended to exercise real
        // VLC playback during the overlap, not only queue/state changes.
        Player.setVolume(35)
        check(!Player.state.value.muted && Player.state.value.volume > 0) { "Automix acceptance must not run muted" }
        Stores.library.update { it.copy(downloaded = mapOf(a.id to first.absolutePath, b.id to second.absolutePath)) }
        Stores.settings.update { it.copy(gaplessPlayback = true, crossfadeSeconds = crossfadeSeconds) }

        // 1. The handover must start inside the blend window, not when the track runs out.
        Player.playQueue(listOf(a, b))
        awaitPlaying()
        val length = Player.state.value.durationMs
        check(length > 4_000) { "Tone A never reported its length: $length" }
        awaitTrack(b)
        val window = crossfadeSeconds * 1000L
        check(handoverAt >= length - window - 500L) {
            "Automix handed over at ${handoverAt}ms of ${length}ms, before its ${window}ms blend window"
        }
        check(Player.state.value.isPlaying || Player.state.value.isBuffering) { "The incoming track must keep playing" }
        awaitPlaying()
        check(Player.state.value.durationMs > 4_000) { "The incoming track never reported its own length" }
        println("Automix: handed over at ${handoverAt}ms of ${length}ms (blend window ${window}ms), Tone B playing: OK")

        // 2. With Automix off the switch happens the plain way, when the track has ended.
        Stores.settings.update { it.copy(gaplessPlayback = false, crossfadeSeconds = 0) }
        Player.playQueue(listOf(a, b))
        awaitPlaying()
        awaitTrack(b)
        val plainAt = Player.state.value.positionMs
        check(plainAt < 1_500) { "Without Automix the next track should start from the beginning, not ${plainAt}ms in" }
        awaitPlaying()
        println("Automix off: plain switch, next track from the start (${plainAt}ms): OK")

        // 3. Replacing the queue while a blend is still running must not disturb the engines.
        Stores.settings.update { it.copy(gaplessPlayback = true, crossfadeSeconds = crossfadeSeconds) }
        Player.playQueue(listOf(a, b))
        awaitPlaying()
        awaitTrack(b)
        Player.playQueue(listOf(a, b))
        awaitPlaying()
        withTimeout(60_000) { while (Player.state.value.current?.id != b.id) delay(100) }
        awaitPlaying()
        check(Player.state.value.durationMs > 4_000) { "The queue swap during a blend lost the track" }
        println("Queue swapped mid-blend: playback continued: OK")
    } finally {
        watcher?.cancel()
        Player.stop()
        Player.release()
        Stores.settings.update {
            it.copy(volume = defaults.volume, gaplessPlayback = defaults.gaplessPlayback, crossfadeSeconds = defaults.crossfadeSeconds)
        }
        Stores.flushAll()
        runCatching { first.delete(); second.delete() }
    }
}

/** A short mono tone, so the check needs no network and no codec beyond PCM. */
private fun tone(dir: File, name: String, ms: Int, hz: Double): File {
    val rate = 8_000f
    val format = AudioFormat(rate, 8, 1, true, false)
    val samples = (rate * ms / 1000).toInt()
    val bytes = ByteArray(samples) { i -> (Math.sin(2 * Math.PI * hz * i / rate) * 90).toInt().toByte() }
    val file = dir.resolve(name)
    AudioSystem.write(AudioInputStream(bytes.inputStream(), format, samples.toLong()), AudioFileFormat.Type.WAVE, file)
    return file
}

private suspend fun awaitPlaying() {
    withTimeout(90_000) {
        while (Player.state.value.positionMs < 500) {
            Player.state.value.error?.let { error(it) }
            delay(100)
        }
    }
}

private suspend fun awaitTrack(song: Song) {
    withTimeout(90_000) {
        while (Player.state.value.current?.id != song.id) {
            Player.state.value.error?.let { error(it) }
            delay(50)
        }
    }
}
