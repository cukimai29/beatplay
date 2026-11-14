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
    fun legacyPremiumServerUrlDetectionOnlyMatchesOldRawIp() {
        assertTrue(isLegacyPremiumServerUrl("http://52.141.21.195:8080"))
        assertTrue(isLegacyPremiumServerUrl("https://52.141.21.195/api"))
        assertFalse(isLegacyPremiumServerUrl("https://premium.adminbeat.my.id"))
        assertFalse(isLegacyPremiumServerUrl("https://52.141.21.195.example.com"))
        assertFalse(isLegacyPremiumServerUrl("https://example.com/52.141.21.195"))
    }

    @Test
    fun premiumNeverGetsAds() {
        // Premium never starts a break, no matter how long the session runs.
        assertFalse(
            AdGate.shouldStartBreak(
                isPremium = true, elapsedMs = 60 * 60_000L, nextAtMs = 7 * 60_000L,
                adShowing = false, casting = false, playing = true,
            ),
        )
    }

    @Test
    fun breakStartsOnlyWhenDueAndEligible() {
        val next = 9 * 60_000L
        assertFalse(
            AdGate.shouldStartBreak(false, next - 1, next, adShowing = false, casting = false, playing = true),
        )
        assertTrue(
            AdGate.shouldStartBreak(false, next, next, adShowing = false, casting = false, playing = true),
        )
        // Never stack breaks or interrupt casting/paused playback.
        assertFalse(
            AdGate.shouldStartBreak(false, next * 2, next, adShowing = true, casting = false, playing = true),
        )
        assertFalse(
            AdGate.shouldStartBreak(false, next * 2, next, adShowing = false, casting = true, playing = true),
        )
        assertFalse(
            AdGate.shouldStartBreak(false, next * 2, next, adShowing = false, casting = false, playing = false),
        )
    }

    @Test
    fun adIntervalIsRandomBetweenSevenAndTwelveMinutes() {
        val random = kotlin.random.Random(42)
        repeat(200) {
            val ms = randomAdIntervalMs(random)
            assertTrue(ms >= 7 * 60_000L)
            assertTrue(ms <= 12 * 60_000L)
            assertEquals(0L, ms % 60_000L)
        }
        // The window must actually vary, not always return one fixed value.
        val values = (1..50).map { randomAdIntervalMs(random) }.toSet()
        assertTrue(values.size > 1)
    }

    @Test
    fun adMediaIdDetection() {
        assertTrue(AdGate.isAdMediaId("beatplay-ad"))
        assertFalse(AdGate.isAdMediaId("some-song-id"))
        assertFalse(AdGate.isAdMediaId(null))
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
