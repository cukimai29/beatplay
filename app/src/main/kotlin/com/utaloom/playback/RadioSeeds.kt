package com.utaloom.playback

import com.utaloom.data.Song
import com.utaloom.innertube.models.WatchEndpoint

/**
 * Endpoints for BeatPlay's automatic (radio style) playback.
 *
 * A bare `videoId` watch endpoint only returns the currently playing song in
 * YouTube Music's watch-next panel, so automatic queues must always be seeded
 * with the song's own radio (`RDAMVM<videoId>`). That radio is generated from
 * the seed song, so the automatic queue stays inside the song's genre instead of
 * drifting into unrelated recommendations.
 */
object RadioSeeds {
    /** Same params YouTube Music sends when the user taps "Start radio". */
    const val PARAMS = "wAEB"

    fun radioId(songId: String) = "RDAMVM$songId"

    fun song(songId: String): WatchEndpoint =
        WatchEndpoint(videoId = songId, playlistId = radioId(songId), params = PARAMS)

    /**
     * Queue order for an automatic radio: the requested song first, then more songs by the
     * same artist (the closest genre match), then the rest of the song's radio mix.
     */
    fun queue(seed: Song, radioSongs: List<Song>): List<Song> {
        val rest = radioSongs.filter { it.id != seed.id }.distinctBy { it.id }
        val artists = seed.artists.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
        if (artists.isEmpty()) return listOf(seed) + rest
        val sameArtist = rest.filter { song -> song.artists.any { it.trim().lowercase() in artists } }
        val sameArtistIds = sameArtist.mapTo(HashSet()) { it.id }
        return listOf(seed) + sameArtist + rest.filter { it.id !in sameArtistIds }
    }

    /** Reorders a radio batch the same way [queue] does, without the seed song itself. */
    fun order(seed: Song, radioSongs: List<Song>): List<Song> = queue(seed, radioSongs).drop(1)
}
