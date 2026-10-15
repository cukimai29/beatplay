package com.utaloom.premium

import com.utaloom.data.Stores
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

private val EMAIL_RE = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")

fun isValidEmail(email: String) = EMAIL_RE.matches(email.trim())

fun normalizeEmail(email: String) = email.trim().lowercase()

const val DEVMODE_FILE = ".devmode"

/** True bila ada file .devmode di dir data (atau env BEATPLAY_DEV=1). Gerbang opsi developer. */
fun isDevMode(dir: java.io.File): Boolean =
    java.io.File(dir, DEVMODE_FILE).isFile || System.getenv("BEATPLAY_DEV") == "1"

fun isDevMode(): Boolean =
    runCatching { isDevMode(com.utaloom.data.Paths.dir) }.getOrDefault(false)

/** Payload JSON untuk POST /api/orders/proof (fungsi murni agar bisa di-unit-test). */
const val PROOF_PHOTO_MAX_BYTES_CLIENT: Long = 4L * 1024 * 1024

/** Tentukan mime foto dari magic bytes + ekstensi. Null bila bukan JPG/PNG asli. */
fun photoMimeFor(fileName: String, bytes: ByteArray): String? {
    if (bytes.isEmpty() || bytes.size > PROOF_PHOTO_MAX_BYTES_CLIENT) return null
    val ext = fileName.substringAfterLast('.', "").lowercase()
    val jpeg = bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()
    val png = bytes.size >= 4 && bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() &&
        bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte()
    if (jpeg && ext in setOf("jpg", "jpeg")) return "image/jpeg"
    if (png && ext == "png") return "image/png"
    return null
}

fun proofPayload(merchantRef: String, senderName: String, note: String, photoBase64: String? = null, photoMime: String? = null): String {
    fun esc(s: String, max: Int) = s.replace("\\", "\\\\").replace("\"", "\\\"").take(max)
    val base = "{\"merchant_ref\":\"" + esc(merchantRef, 64) + "\",\"sender_name\":\"" +
        esc(senderName, 100) + "\",\"note\":\"" + esc(note, 300) + "\""
    return if (photoBase64 != null && photoMime != null) {
        base + ",\"photo_base64\":\"" + photoBase64 + "\",\"photo_mime\":\"" + esc(photoMime, 32) + "\"}"
    } else base + "}"
}

/** Pure ad-gating rules (unit-tested, no I/O). */
object AdGate {
    /** True when an ad must play before the next track. */
    fun shouldShowAd(isPremium: Boolean, songsSinceAd: Int, adEveryNSongs: Int): Boolean {
        if (isPremium) return false
        val every = adEveryNSongs.coerceIn(1, 100)
        return songsSinceAd >= every
    }

    /** Counter value after one song finished (call only when no ad was shown). */
    fun nextCounter(songsSinceAd: Int): Int = (songsSinceAd + 1).coerceAtLeast(0)
}

object PremiumRepository {
    private val http: HttpClient by lazy {
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
    }

    fun checkUrl(serverUrl: String, email: String): String {
        val base = serverUrl.trim().trimEnd('/')
        return "$base/api/premium?email=" + URLEncoder.encode(normalizeEmail(email), Charsets.UTF_8)
    }

    /** Returns true when the server says this gmail is premium. Never throws. */
    suspend fun refresh(email: String, serverUrl: String = Stores.settings.value.premiumServerUrl): Result<Boolean> =
        withContext(Dispatchers.IO) {
            runCatching {
                require(isValidEmail(email)) { "Email tidak valid" }
                val req = HttpRequest.newBuilder(URI(checkUrl(serverUrl, email)))
                    .timeout(Duration.ofSeconds(8)).GET().build()
                val res = http.send(req, HttpResponse.BodyHandlers.ofString())
                check(res.statusCode() == 200) { "Server premium HTTP ${res.statusCode()}" }
                val root = Json.parseToJsonElement(res.body()).jsonObject
                val premium = root["premium"]?.jsonPrimitive?.content == "true"
                val expiry = root["expiry"]?.jsonPrimitive?.longOrNull
                premium to expiry
            }.onSuccess { (premium, expiry) ->
                Stores.settings.update {
                    it.copy(
                        premiumEmail = normalizeEmail(email),
                        isPremium = premium,
                        premiumExpiry = expiry,
                        premiumLastChecked = System.currentTimeMillis(),
                        // Reset ad counter on status change so free->premium stops ads immediately.
                        songsSinceAd = if (premium) 0 else it.songsSinceAd,
                    )
                }
            }.map { it.first }
        }

    suspend fun fetchPlans(serverUrl: String = Stores.settings.value.premiumServerUrl): Result<List<Plan>> =
        fetchShop(serverUrl).map { it.plans }

    suspend fun fetchShop(serverUrl: String = Stores.settings.value.premiumServerUrl): Result<ShopInfo> =
        withContext(Dispatchers.IO) {
            runCatching {
                val base = serverUrl.trim().trimEnd('/')
                val req = HttpRequest.newBuilder(URI("$base/api/plans"))
                    .timeout(Duration.ofSeconds(8)).GET().build()
                val res = http.send(req, HttpResponse.BodyHandlers.ofString())
                check(res.statusCode() == 200) { "Server premium HTTP ${res.statusCode()}" }
                val root = Json.parseToJsonElement(res.body()).jsonObject
                val plans = root["plans"]!!.jsonArray.map { e ->
                    val o = e.jsonObject
                    Plan(o["id"]!!.jsonPrimitive.content, o["title"]!!.jsonPrimitive.content,
                        o["price"]!!.jsonPrimitive.int, o["days"]!!.jsonPrimitive.int)
                }
                val provider = root["provider"]?.jsonPrimitive?.content ?: "mock"
                val m = root["manual"]?.jsonObject
                val manual = m?.let {
                    ManualQris(it["account_name"]?.jsonPrimitive?.content.orEmpty(),
                        it["instructions"]?.jsonPrimitive?.content.orEmpty(),
                        it["has_image"]?.jsonPrimitive?.content == "true")
                }
                ShopInfo(plans, provider, manual, base)
            }
        }

    suspend fun submitProof(merchantRef: String, senderName: String, note: String = "", photoFile: java.io.File? = null, serverUrl: String = Stores.settings.value.premiumServerUrl): Result<OrderInfo> =
        withContext(Dispatchers.IO) {
            runCatching {
                require(senderName.isNotBlank()) { "Nama pengirim wajib diisi" }
                val base = serverUrl.trim().trimEnd('/')
                val photoBytes = photoFile?.takeIf { it.isFile }?.readBytes()
                val photoMime = photoBytes?.let { photoMimeFor(photoFile!!.name, it) }
                check(photoFile == null || (photoBytes != null && photoMime != null)) { "Foto tidak valid (JPG/PNG maks 4MB)" }
                val photoB64 = photoBytes?.let { java.util.Base64.getEncoder().encodeToString(it) }
                val payload = proofPayload(merchantRef, senderName, note, photoB64, photoMime)
                val req = HttpRequest.newBuilder(URI("$base/api/orders/proof"))
                    .timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload)).build()
                val res = http.send(req, HttpResponse.BodyHandlers.ofString())
                check(res.statusCode() == 200) { "Gagal kirim bukti: ${res.body().take(200)}" }
                parseOrder(Json.parseToJsonElement(res.body()).jsonObject["order"]!!.jsonObject)
            }
        }

    suspend fun createOrder(email: String, planId: String, serverUrl: String = Stores.settings.value.premiumServerUrl): Result<OrderInfo> =
        withContext(Dispatchers.IO) {
            runCatching {
                require(isValidEmail(email)) { "Email tidak valid" }
                val base = serverUrl.trim().trimEnd('/')
                val payload = """{"email":"${normalizeEmail(email)}","plan_id":"$planId"}"""
                val req = HttpRequest.newBuilder(URI("$base/api/orders/create"))
                    .timeout(Duration.ofSeconds(20))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload)).build()
                val res = http.send(req, HttpResponse.BodyHandlers.ofString())
                check(res.statusCode() == 200) { "Gagal buat order: ${res.body().take(200)}" }
                parseOrder(Json.parseToJsonElement(res.body()).jsonObject["order"]!!.jsonObject)
            }
        }

    suspend fun orderStatus(merchantRef: String, serverUrl: String = Stores.settings.value.premiumServerUrl): Result<OrderStatus> =
        withContext(Dispatchers.IO) {
            runCatching {
                val base = serverUrl.trim().trimEnd('/')
                val req = HttpRequest.newBuilder(URI("$base/api/orders/status?merchant_ref=" + URLEncoder.encode(merchantRef, Charsets.UTF_8)))
                    .timeout(Duration.ofSeconds(10)).GET().build()
                val res = http.send(req, HttpResponse.BodyHandlers.ofString())
                check(res.statusCode() == 200) { "Order tidak ditemukan" }
                val root = Json.parseToJsonElement(res.body()).jsonObject
                val order = parseOrder(root["order"]!!.jsonObject)
                val premium = root["premium"]?.jsonPrimitive?.content == "true"
                val expiry = root["expiry"]?.jsonPrimitive?.longOrNull
                if (premium) {
                    Stores.settings.update {
                        it.copy(premiumEmail = order.email, isPremium = true, premiumExpiry = expiry,
                            premiumLastChecked = System.currentTimeMillis(), songsSinceAd = 0)
                    }
                }
                OrderStatus(order, premium, expiry)
            }
        }

    private fun parseOrder(o: kotlinx.serialization.json.JsonObject) = OrderInfo(
        merchantRef = o["merchant_ref"]!!.jsonPrimitive.content,
        email = o["email"]?.jsonPrimitive?.content.orEmpty(),
        planId = o["plan_id"]?.jsonPrimitive?.content.orEmpty(),
        amount = o["amount"]?.jsonPrimitive?.int ?: 0,
        status = o["status"]?.jsonPrimitive?.content ?: "UNKNOWN",
        checkoutUrl = o["checkout_url"]?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.jsonPrimitive?.content,
        qrString = o["qr_string"]?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.jsonPrimitive?.content,
        qrUrl = o["qr_url"]?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.jsonPrimitive?.content,
        expiredAt = o["expired_at"]?.jsonPrimitive?.longOrNull,
        proofSender = o["proof"]?.jsonObject?.get("sender")?.jsonPrimitive?.content,
    )
}

data class Plan(val id: String, val title: String, val price: Int, val days: Int)

data class ManualQris(val accountName: String, val instructions: String, val hasImage: Boolean)

data class ShopInfo(val plans: List<Plan>, val provider: String, val manual: ManualQris?, val baseUrl: String)

data class OrderInfo(
    val merchantRef: String,
    val email: String,
    val planId: String,
    val amount: Int,
    val status: String,
    val checkoutUrl: String? = null,
    val qrString: String? = null,
    val qrUrl: String? = null,
    val expiredAt: Long? = null,
    val proofSender: String? = null,
)

data class OrderStatus(val order: OrderInfo, val premium: Boolean, val expiry: Long?)

/** In-memory ad playback state for the Compose overlay. */
object AdManager {
    private val _showing = MutableStateFlow(false)
    val showing: StateFlow<Boolean> = _showing.asStateFlow()
    private val _secondsLeft = MutableStateFlow(0)
    val secondsLeft: StateFlow<Int> = _secondsLeft.asStateFlow()
    private val _text = MutableStateFlow("BeatPlay Premium - dengarkan tanpa jeda iklan.")
    val text: StateFlow<String> = _text.asStateFlow()
    private val _videoPath = MutableStateFlow<String?>(null)
    /** Local video file to render in the overlay, null = text-only fallback. */
    val videoPath: StateFlow<String?> = _videoPath.asStateFlow()

    fun isShowing(): Boolean = _showing.value
    fun setVideoAd(path: String?) {
        _videoPath.value = path
    }

    /** End the countdown immediately (e.g. video finished early). */
    fun finishNow() {
        _secondsLeft.value = 0
    }

    /** Called by Player when a track finishes and an ad is due. Blocks ~adSeconds. */
    suspend fun showAdBlocking(seconds: Int, text: String) {
        _text.value = text
        _secondsLeft.value = seconds.coerceIn(1, 120)
        _showing.value = true
        try {
            while (_secondsLeft.value > 0) {
                kotlinx.coroutines.delay(1000)
                _secondsLeft.value = _secondsLeft.value - 1
            }
        } finally {
            _showing.value = false
            _videoPath.value = null
            Stores.settings.update { it.copy(songsSinceAd = 0) }
        }
    }

    fun dismissForPreview() {
        _showing.value = false
        _videoPath.value = null
    }
}
