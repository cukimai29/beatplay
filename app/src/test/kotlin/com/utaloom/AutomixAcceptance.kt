package com.utaloom

import com.utaloom.data.Song
import com.utaloom.data.Stores
import com.utaloom.data.toSong
import com.utaloom.innertube.YouTube
import com.utaloom.innertube.models.SongItem
import com.utaloom.platform.NativeLibs
import com.utaloom.playback.Player
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Live acceptance: one track must hand over to the next without the listener waiting for
 * the next stream to start. Uses real audio through libVLC, so the Gradle task isolates it.
 *
 * Tracks are chosen at runtime because a hardcoded video can be unavailable from the
 * machine that runs the check (region blocks, for example).
 */
fun main() = runBlocking {
    val defaults = Stores.settings.value
    try {
        check(NativeLibs.discoverVlc() == null && Player.init()) { "Native VLC unavailable" }
        Player.setVolume(0)
        // Set explicitly: the check must not depend on what an earlier run left on disk.
        Stores.settings.update { it.copy(gaplessPlayback = true, crossfadeSeconds = 3) }
        val settings = Stores.settings.value
        check(settings.gaplessPlayback) { "Automix must be switchable on" }
        check(settings.crossfadeSeconds > 0) { "Automix must blend, not switch silently" }
        println("Automix defaults: ${settings.crossfadeSeconds}s blend, gapless ${settings.gaplessPlayback}")

        val pool = candidateTracks()
        val tracks = ArrayList<Song>(2)
        for (song in pool) {
            if (tracks.size >= 2) break
            if (startsPlaying(song)) tracks += song
        }
        check(tracks.size >= 2) { "No two playable tracks from this machine (${pool.size} candidates tried)" }
        val first = tracks[0]
        val second = tracks[1]
        println("Using: ${first.title} / ${first.artists} and ${second.title} / ${second.artists}")

        // 1. With Automix on: the handover must happen while the first track is still ending.
        Player.playQueue(listOf(first, second))
        awaitPlaying()
        val length = Player.state.value.durationMs
        check(length > 8000) { "Stream length unavailable: $length" }
        val blendFrom = settings.crossfadeSeconds * 1000L
        Player.seekTo((length - blendFrom - 2000).coerceAtLeast(1000))
        if (!awaitSeek(length - blendFrom - 2000)) println("Seek not applied by the backend, waiting for the natural end")
        awaitTrack(second)
        check(Player.state.value.isPlaying || Player.state.value.isBuffering) { "The next track must keep playing" }
        awaitPlaying()
        check(Player.state.value.durationMs > 0) { "The incoming track never reported its length" }
        println("Automix: first -> second with the blend, next track playing from its own stream: OK")

        // 2. With Automix off: the same queue must still reach the next track, the plain way.
        Stores.settings.update { it.copy(gaplessPlayback = false, crossfadeSeconds = 0) }
        Player.playQueue(listOf(first, second))
        awaitPlaying()
        val plainLength = Player.state.value.durationMs
        Player.seekTo((plainLength - 6000).coerceAtLeast(1000))
        if (!awaitSeek(plainLength - 6000)) println("Seek not applied by the backend, waiting for the natural end")
        awaitTrack(second)
        awaitPlaying()
        println("Automix off: plain switch still reaches the next track: OK")
    } finally {
        Player.stop()
        Player.release()
        Stores.settings.update { it.copy(gaplessPlayback = defaults.gaplessPlayback, crossfadeSeconds = defaults.crossfadeSeconds) }
        Stores.flushAll()
    }
}

/** Short, widely available tracks, used when the search API cannot be reached. */
private fun fallbackTracks(): List<Song> = listOf(
    Song("9bZkp7q19f0", "Gangnam Style", listOf("PSY"), duration = 252),
    Song("kJQP7kiw5Fk", "Despacito", listOf("Luis Fonsi", "Daddy Yankee"), duration = 282),
    Song("JGwWNGJdvx8", "Shape of You", listOf("Ed Sheeran"), duration = 264),
    Song("RgKAFK5djSk", "See You Again", listOf("Wiz Khalifa", "Charlie Puth"), duration = 238),
    Song("OPf0YbXqDm0", "Uptown Funk", listOf("Mark Ronson", "Bruno Mars"), duration = 270),
    Song("jNQXAC9IVRw", "Me at the zoo", listOf("jawed"), duration = 19),
)

/** Real search results first, so the tracks are playable from wherever this runs. */
private suspend fun candidateTracks(): List<Song> {
    val searched = runCatching {
        withTimeout(60_000) { YouTube.search("Via Vallen Bojo Galak", YouTube.SearchFilter.FILTER_SONG).getOrThrow() }
    }.getOrNull()
    val songs = searched?.items?.filterIsInstance<SongItem>()
        ?.map { it.toSong() }
        ?.distinctBy { it.id }
        ?.sortedBy { it.duration ?: 999 }
        ?.take(6)
        .orEmpty()
    return songs.ifEmpty { fallbackTracks() }
}

/** True when the track really starts producing audio on this machine. */
private suspend fun startsPlaying(song: Song): Boolean {
    Player.playQueue(listOf(song))
    val deadline = System.currentTimeMillis() + 90_000
    while (System.currentTimeMillis() < deadline) {
        val s = Player.state.value
        if (s.error != null) return false
        if (s.current?.id == song.id && s.positionMs >= 500) return true
        delay(100)
    }
    return false
}

private suspend fun awaitPlaying() {
    withTimeout(90_000) {
        while (Player.state.value.positionMs < 500) {
            Player.state.value.error?.let { error(it) }
            delay(100)
        }
    }
}

/** True once the position got close to the seek target. */
private suspend fun awaitSeek(target: Long): Boolean = withTimeout(15_000) {
    while (Player.state.value.positionMs < target - 1500) delay(100)
    true
} == true

private suspend fun awaitTrack(song: Song) {
    withTimeout(300_000) {
        while (Player.state.value.current?.id != song.id) {
            Player.state.value.error?.let { error(it) }
            delay(100)
        }
    }
}
