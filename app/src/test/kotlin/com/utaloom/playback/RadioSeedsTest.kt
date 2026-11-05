package com.utaloom.playback

import com.utaloom.data.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Automatic playback must be seeded from the searched song's own YouTube Music radio,
 * otherwise the automatic queue drifts away from the song's genre.
 */
class RadioSeedsTest {

    private fun song(id: String, title: String = "Song $id", artist: String = "Artist") =
        Song(id = id, title = title, artists = listOf(artist), thumbnail = "http://x/$id.jpg")

    @Test
    fun songEndpointUsesTheSongRadio() {
        val endpoint = RadioSeeds.song("abc123")
        assertEquals("abc123", endpoint.videoId)
        assertEquals("RDAMVMabc123", endpoint.playlistId)
        assertEquals("wAEB", endpoint.params)
        assertNull(endpoint.playlistSetVideoId)
        assertNull(endpoint.index)
    }

    @Test
    fun radioIdKeepsTheRdamvmPrefix() {
        assertEquals("RDAMVMxyz", RadioSeeds.radioId("xyz"))
    }

    @Test
    fun queueStartsWithTheSearchedSong() {
        val seed = song("seed", "Bojo Galak", "Via Vallen")
        val radio = listOf(seed, song("a", "Sayang", "Via Vallen"), song("b", "Kelingan Mantan", "NDX"))
        val queue = RadioSeeds.queue(seed, radio)
        assertEquals(seed, queue.first())
        assertEquals(listOf("seed", "a", "b"), queue.map { it.id })
    }

    @Test
    fun queueDropsDuplicatesAndTheSeedFromTheRadioSongs() {
        val seed = song("seed")
        val radio = listOf(
            song("seed"),
            song("a"),
            song("a"),
            song("b"),
        )
        assertEquals(listOf("seed", "a", "b"), RadioSeeds.queue(seed, radio).map { it.id })
    }

    @Test
    fun queuePutsTheSearchedArtistFirstSoTheGenreMatches() {
        val seed = song("seed", "Bojo Galak", "Via Vallen")
        val radio = listOf(
            song("b", "Kelingan Mantan", "NDX"),
            song("a", "Sayang", "Via Vallen"),
            song("c", "Los Dol", "Denny Caknan"),
            song("d", "Selow", "Via Vallen"),
        )
        assertEquals(listOf("seed", "a", "d", "b", "c"), RadioSeeds.queue(seed, radio).map { it.id })
    }

    @Test
    fun queueMatchesArtistsCaseInsensitivelyAndIgnoresBlankNames() {
        val seed = song("seed", "Bojo Galak", "via vallen")
        val radio = listOf(
            song("a", "Sayang", " VIA VALLEN "),
            song("b", "Kelingan Mantan", "NDX"),
        )
        assertEquals(listOf("seed", "a", "b"), RadioSeeds.queue(seed, radio).map { it.id })
    }

    @Test
    fun queueKeepsRadioOrderWhenNoArtistSharesTheSeed() {
        val seed = song("seed", "Bojo Galak", "Via Vallen")
        val radio = (1..10).map { song("r$it", "Track $it", "Artist $it") }
        val queue = RadioSeeds.queue(seed, radio)
        assertEquals(listOf("seed") + (1..10).map { "r$it" }, queue.map { it.id })
    }

    @Test
    fun orderReusesTheQueueOrderWithoutTheSeed() {
        val seed = song("seed", "Bojo Galak", "Via Vallen")
        val radio = listOf(song("b", "Kelingan Mantan", "NDX"), song("a", "Sayang", "Via Vallen"))
        assertEquals(listOf("a", "b"), RadioSeeds.order(seed, radio).map { it.id })
    }

    @Test
    fun queueWithAnEmptyRadioStillPlaysTheSeed() {
        val seed = song("seed")
        assertEquals(listOf(seed), RadioSeeds.queue(seed, emptyList()))
    }
}
