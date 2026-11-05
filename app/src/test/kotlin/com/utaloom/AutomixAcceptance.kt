package com.utaloom

import com.utaloom.data.Song
import com.utaloom.data.Stores
import com.utaloom.platform.NativeLibs
import com.utaloom.playback.Player
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Live acceptance: one track must hand over to the next without the listener waiting for the
 * next stream to start. Uses real YouTube audio through libVLC, so the Gradle task isolates it.
 */
fun main() = runBlocking {
    val first = Song("dQw4w9WgXcQ", "Never Gonna Give You Up", listOf("Rick Astley"), duration = 213)
    val second = Song("9bZkp7q19f0", "Gangnam Style", listOf("PSY"), duration = 252)
    try {
        check(NativeLibs.discoverVlc() == null && Player.init()) { "Native VLC unavailable" }
        Player.setVolume(0)
        val settings = Stores.settings.value
        check(settings.gaplessPlayback) { "Automix must be on by default" }
        check(settings.crossfadeSeconds > 0) { "Automix must blend, not switch silently" }
        println("Automix defaults: ${settings.crossfadeSeconds}s blend, gapless ${settings.gaplessPlayback}")

        Player.playQueue(listOf(first, second))
        withTimeout(120_000) {
            while (Player.state.value.positionMs < 1000) {
                Player.state.value.error?.let { error(it) }
                delay(100)
            }
        }
        // Jump close to the end so the blend is reached without waiting for the whole track.
        val length = Player.state.value.durationMs
        check(length > 8000) { "Stream length unavailable: $length" }
        Player.seekTo(length - 4000)
        withTimeout(90_000) {
            while (Player.state.value.current?.id != second.id) {
                Player.state.value.error?.let { error(it) }
                delay(50)
            }
        }
        val handed = Player.state.value
        check(handed.current?.id == second.id) { "Automix must move on to the next track" }
        check(handed.isPlaying || handed.isBuffering) { "The next track must keep playing" }
        withTimeout(60_000) {
            while (Player.state.value.positionMs < 1000) {
                Player.state.value.error?.let { error(it) }
                delay(50)
            }
        }
        check(Player.state.value.durationMs > 0) { "The incoming track never reported its length" }
        println("Automix handover: first -> second, next track playing from its own stream: OK")

        // The same queue with Automix off must still play through, just the plain way.
        Stores.settings.update { it.copy(gaplessPlayback = false, crossfadeSeconds = 0) }
        Player.playQueue(listOf(first, second))
        withTimeout(120_000) {
            while (Player.state.value.positionMs < 1000) {
                Player.state.value.error?.let { error(it) }
                delay(100)
            }
        }
        val plainLength = Player.state.value.durationMs
        Player.seekTo(plainLength - 4000)
        withTimeout(90_000) {
            while (Player.state.value.current?.id != second.id) {
                Player.state.value.error?.let { error(it) }
                delay(50)
            }
        }
        withTimeout(60_000) {
            while (Player.state.value.positionMs < 1000) delay(50)
        }
        println("Automix off: plain switch still reaches the next track: OK")
        Stores.settings.update { it.copy(gaplessPlayback = settings.gaplessPlayback, crossfadeSeconds = settings.crossfadeSeconds) }
    } finally {
        Player.stop()
        Player.release()
        Stores.flushAll()
    }
}
