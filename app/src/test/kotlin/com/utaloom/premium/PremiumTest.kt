package com.utaloom.premium

import com.utaloom.data.Settings
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.junit.Assert.*
import org.junit.Test
class PremiumTest {
    @Test
    fun emailValidation() {
        assertTrue(isValidEmail("nama@gmail.com"))
        assertTrue(isValidEmail(" Nama@Gmail.COM "))
        assertEquals("nama@gmail.com", normalizeEmail(" Nama@Gmail.COM "))
        assertFalse(isValidEmail(""))
        assertFalse(isValidEmail("bukan-email"))
        assertFalse(isValidEmail("a@b"))
    }

    @Test
    fun premiumNeverGetsAds() {
        assertFalse(AdGate.shouldShowAd(isPremium = true, songsSinceAd = 99, adEveryNSongs = 3))
        assertFalse(AdGate.shouldShowAd(isPremium = false, songsSinceAd = 2, adEveryNSongs = 3))
        assertTrue(AdGate.shouldShowAd(isPremium = false, songsSinceAd = 3, adEveryNSongs = 3))
        assertTrue(AdGate.shouldShowAd(isPremium = false, songsSinceAd = 10, adEveryNSongs = 3))
    }

    @Test
    fun counterIncrements() {
        assertEquals(1, AdGate.nextCounter(0))
        assertEquals(3, AdGate.nextCounter(2))
    }

    @Test
    fun checkUrlEncodesEmail() {
        val url = PremiumRepository.checkUrl("http://127.0.0.1:8080/", "Nama@Gmail.com ")
        assertEquals("http://127.0.0.1:8080/api/premium?email=nama%40gmail.com", url)
    }

    @Test
    fun premiumFieldsSurviveJsonRoundTrip() {
        val original = Settings(premiumEmail = "a@gmail.com", isPremium = true, songsSinceAd = 2, adEveryNSongs = 3)
        val parsed = Json.decodeFromString(Settings.serializer(), Json.encodeToString(Settings.serializer(), original))
        assertEquals(original, parsed)
        // Defaults keep old settings files working: free user, ad every 3 songs.
        val legacy = Json.decodeFromString(Settings.serializer(), "{}")
        assertFalse(legacy.isPremium)
        assertEquals(3, legacy.adEveryNSongs)
        assertEquals(15, legacy.adSeconds)
    }

    @Test
    fun premiumExpirySurvivesJsonRoundTrip() {
        val original = Settings(premiumEmail = "a@gmail.com", isPremium = true, premiumExpiry = 1893456000L)
        val parsed = Json.decodeFromString(Settings.serializer(), Json.encodeToString(Settings.serializer(), original))
        assertEquals(1893456000L, parsed.premiumExpiry)
        assertTrue(parsed.isPremium)
        // Old files without the field decode to null (lifetime/manual handled by boolean).
        assertNull(Json.decodeFromString(Settings.serializer(), "{}").premiumExpiry)
    }

    @Test
    fun adVideoPathDefaultsAndValidation() {
        assertEquals("", Json.decodeFromString(Settings.serializer(), "{}").adVideoPath)
        val withVideo = Settings(adVideoPath = "D:\\musik\\IMG_1866.MP4")
        val parsed = Json.decodeFromString(Settings.serializer(), Json.encodeToString(Settings.serializer(), withVideo))
        assertEquals("D:\\musik\\IMG_1866.MP4", parsed.adVideoPath)
        // isCandidate: fake temp files with right/wrong extensions.
        val mp4 = java.io.File.createTempFile("adv", ".mp4").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val txt = java.io.File.createTempFile("adv", ".txt").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val missing = java.io.File(mp4.parentFile, "tidak-ada.mp4")
        try {
            assertTrue(com.utaloom.playback.AdVideo.isCandidate(mp4))
            assertFalse(com.utaloom.playback.AdVideo.isCandidate(txt))
            assertFalse(com.utaloom.playback.AdVideo.isCandidate(missing))
            assertFalse(com.utaloom.playback.AdVideo.isCandidate(mp4.parentFile))
        } finally {
            mp4.delete()
            txt.delete()
        }
    }

    @Test
    fun devModeGate() {
        val dir = java.nio.file.Files.createTempDirectory("bpdev").toFile()
        try {
            val saved = System.getenv("BEATPLAY_DEV")
            if (saved == "1") return // env memaksa true, lewati bagian false di mesin ini
            assertFalse(com.utaloom.premium.isDevMode(dir))
            java.io.File(dir, "devmode-tidak-ini").createNewFile()
            assertFalse(com.utaloom.premium.isDevMode(dir))
            java.io.File(dir, com.utaloom.premium.DEVMODE_FILE).createNewFile()
            assertTrue(com.utaloom.premium.isDevMode(dir))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun premiumDevModeDefaultsOff() {        assertFalse(Json.decodeFromString(Settings.serializer(), "{}").premiumDevMode)
        val on = Settings(premiumDevMode = true)
        assertEquals(true, Json.decodeFromString(Settings.serializer(), Json.encodeToString(Settings.serializer(), on)).premiumDevMode)
    }

    @Test
    fun proofPayloadIsValidJson() {
        // Regression: Kirim bukti pernah gagal dengan "body harus JSON" karena
        // satu kutip hilang di builder inline. Fungsi murni ini menjamin valid.
        val raw = proofPayload("BP-261015034928-D2197F", "TEST", "halo")
        val o = Json.parseToJsonElement(raw).jsonObject
        assertEquals("BP-261015034928-D2197F", o["merchant_ref"]!!.jsonPrimitive.content)
        assertEquals("TEST", o["sender_name"]!!.jsonPrimitive.content)
        assertEquals("halo", o["note"]!!.jsonPrimitive.content)
        // Kutip + backslash di-escape, tidak merusak JSON.
        val tricky = Json.parseToJsonElement(proofPayload("R\"1\\", "A\"B", "n\n1")).jsonObject
        assertEquals("R\"1\\", tricky["merchant_ref"]!!.jsonPrimitive.content)
    }

    @Test
    fun proofPhotoMimeValidation() {
        val jpg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0x00)
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D)
        assertEquals("image/jpeg", photoMimeFor("bukti.jpg", jpg))
        assertEquals("image/jpeg", photoMimeFor("BUKTI.JPEG", jpg))
        assertEquals("image/png", photoMimeFor("bukti.png", png))
        assertNull(photoMimeFor("bukti.png", jpg)) // magic tak cocok
        assertNull(photoMimeFor("bukti.gif", jpg)) // ekstensi tak didukung
        assertNull(photoMimeFor("bukti.jpg", byteArrayOf())) // kosong
    }

    @Test
    fun proofPayloadWithPhotoIncludesFields() {
        val raw = proofPayload("BP-1", "TEST", "halo", "aGVsbG8=", "image/jpeg")
        val o = Json.parseToJsonElement(raw).jsonObject
        assertEquals("BP-1", o["merchant_ref"]!!.jsonPrimitive.content)
        assertEquals("aGVsbG8=", o["photo_base64"]!!.jsonPrimitive.content)
        assertEquals("image/jpeg", o["photo_mime"]!!.jsonPrimitive.content)
        // Tanpa foto: field absen (kompatibel server lama/test).
        val plain = Json.parseToJsonElement(proofPayload("BP-1", "T", "")).jsonObject
        assertNull(plain["photo_base64"])
    }

    @Test
    fun shopInfoWithManualParses() {
        // Same shape as server GET /api/plans for provider manual.
        val body = """{"plans":[{"id":"monthly","title":"Premium 1 bulan","price":20000,"days":30}],"tripay_method":"QRIS2","provider":"manual","manual":{"account_name":"RzkyAds via DANA","instructions":"Bayar pas.","has_image":false}}"""
        val root = Json.parseToJsonElement(body).jsonObject
        assertEquals("manual", root["provider"]!!.jsonPrimitive.content)
        val m = root["manual"]!!.jsonObject
        assertEquals("RzkyAds via DANA", m["account_name"]!!.jsonPrimitive.content)
        assertEquals("Bayar pas.", m["instructions"]!!.jsonPrimitive.content)
        assertEquals("false", m["has_image"]!!.jsonPrimitive.content)
        val plans = root["plans"]!!.jsonArray
        assertEquals(1, plans.size)
        assertEquals("monthly", plans[0].jsonObject["id"]!!.jsonPrimitive.content)
    }

    @Test
    fun premiumResponseWithExpiryParses() {
        // Same shape as server GET /api/premium for a paid user.
        val body = """{"email":"a@gmail.com","premium":true,"expiry":1893456000}"""
        val root = Json.parseToJsonElement(body).jsonObject
        assertEquals("true", root["premium"]!!.jsonPrimitive.content)
        assertEquals(1893456000L, root["expiry"]!!.jsonPrimitive.longOrNull)
        // Manual lifetime has no expiry key.
        val lifetime = Json.parseToJsonElement("""{"email":"b@gmail.com","premium":true}""").jsonObject
        assertNull(lifetime["expiry"]?.jsonPrimitive?.longOrNull)
    }
}
