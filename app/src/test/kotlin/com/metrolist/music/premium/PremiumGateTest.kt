package com.metrolist.music.premium

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private fun mockApi(
    body: String,
    check: (String) -> Unit = {},
): HttpClient = HttpClient(MockEngine) {
    engine {
        addHandler { request ->
            check(request.url.toString())
            respond(
                ByteReadChannel(body),
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
    }
}

class PremiumGateTest {
    @Test
    fun emailValidation() {
        assertTrue(isValidPremiumEmail("nama@gmail.com"))
        assertEquals("nama@gmail.com", normalizePremiumEmail(" Nama@Gmail.COM "))
        assertFalse(isValidPremiumEmail(""))
        assertFalse(isValidPremiumEmail("bukan-email"))
    }

    @Test
    fun premiumNeverGetsAds() {
        assertFalse(AdGate.shouldShowAd(true, 99, 3))
        assertFalse(AdGate.shouldShowAd(false, 2, 3))
        assertTrue(AdGate.shouldShowAd(false, 3, 3))
        assertEquals(3, AdGate.nextCounter(2))
    }

    @Test
    fun decideAfterSongCountsAndInserts() {
        // 2 already finished, this 3rd finishes -> counter hits 3 -> insert ad.
        assertEquals(AdDecision.INSERT_AD to 3, AdGate.decideAfterSong(false, 2, 3, casting = false))
        // Only 1 finished before -> count to 2, no ad yet.
        assertEquals(AdDecision.COUNT_ONLY to 2, AdGate.decideAfterSong(false, 1, 3, casting = false))
        // Premium never inserts; casting never inserts.
        assertEquals(AdDecision.COUNT_ONLY to 99, AdGate.decideAfterSong(true, 98, 3, casting = false))
        assertEquals(AdDecision.COUNT_ONLY to 4, AdGate.decideAfterSong(false, 3, 3, casting = true))
    }

    @Test
    fun adMediaIdDetection() {        assertTrue(AdGate.isAdMediaId("beatplay-ad"))
        assertFalse(AdGate.isAdMediaId("some-song-id"))
        assertFalse(AdGate.isAdMediaId(null))
        // Counter stays untouched while an ad is on screen (reset happens after).
        assertTrue(AdGate.shouldShowAd(false, 3, 3))
        assertEquals(4, AdGate.nextCounter(3))
    }

    @Test
    fun photoMimeValidation() {
        val jpg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)
        assertEquals("image/jpeg", photoMimeFor("bukti.jpg", jpg))
        assertEquals("image/png", photoMimeFor("bukti.png", png))
        assertNull(photoMimeFor("bukti.png", jpg))
        assertNull(photoMimeFor("bukti.gif", jpg))
        assertNull(photoMimeFor("bukti.jpg", byteArrayOf()))
    }

    @Test
    fun refreshParsesPremiumAndExpiry() = runBlocking {
        val api = PremiumApi(
            mockApi("""{"email":"nama@gmail.com","premium":true,"expiry":1893456000}""") { url ->
                assert(url.contains("email=nama%40gmail.com"))
            },
        )
        val (premium, expiry) = api.refresh("http://x", "Nama@Gmail.com")
        assertTrue(premium)
        assertEquals(1893456000L, expiry)
    }

    @Test
    fun shopParsesPlansAndManual() = runBlocking {
        val api = PremiumApi(
            mockApi("""{"plans":[{"id":"monthly","title":"Premium 1 bulan","price":20000,"days":30}],"provider":"manual","manual":{"account_name":"RzkyAds via DANA","instructions":"Bayar pas.","has_image":false}}"""),
        )
        val shop = api.fetchShop("http://x")
        assertEquals("manual", shop.provider)
        assertEquals(1, shop.plans.size)
        assertEquals(20000, shop.plans[0].price)
        assertEquals("RzkyAds via DANA", shop.manual?.accountName)
    }
}
