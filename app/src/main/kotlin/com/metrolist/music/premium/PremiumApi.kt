/**
 * BeatPlay Premium HTTP API (Ktor + OkHttp engine, kotlinx.serialization parsing).
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.premium

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

class PremiumApi(
    private val client: HttpClient = HttpClient(OkHttp) {
        install(HttpTimeout) {
            connectTimeoutMillis = 5_000
            requestTimeoutMillis = 10_000
            socketTimeoutMillis = 10_000
        }
    },
) {
    fun close() = client.close()

    fun checkUrl(
        serverUrl: String,
        email: String,
    ): String = "${serverUrl.trim().trimEnd('/')}/api/premium?email=${java.net.URLEncoder.encode(normalizePremiumEmail(email), "UTF-8")}"

    /** Returns (premium, expiryEpochSecondsOrNull). Throws on transport/API errors. */
    suspend fun refresh(
        serverUrl: String,
        email: String,
    ): Pair<Boolean, Long?> {
        require(isValidPremiumEmail(email)) { "Email tidak valid" }
        val root = Json.parseToJsonElement(client.get(checkUrl(serverUrl, email)).bodyAsText()).jsonObject
        val premium = root["premium"]?.jsonPrimitive?.booleanOrNull == true
        val expiry = root["expiry"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.longOrNull
        return premium to expiry
    }

    suspend fun fetchShop(serverUrl: String): ShopInfo {
        val base = serverUrl.trim().trimEnd('/')
        val root = Json.parseToJsonElement(client.get("$base/api/plans").bodyAsText()).jsonObject
        val plans = root["plans"]?.jsonArray?.map { e ->
            val o = e.jsonObject
            Plan(o["id"]!!.jsonPrimitive.content, o["title"]!!.jsonPrimitive.content, o["price"]!!.jsonPrimitive.int, o["days"]!!.jsonPrimitive.int)
        }.orEmpty()
        val manual = root["manual"]?.takeIf { it !is JsonNull }?.jsonObject?.let {
            ManualQris(
                it["account_name"]?.jsonPrimitive?.content.orEmpty(),
                it["instructions"]?.jsonPrimitive?.content.orEmpty(),
                it["has_image"]?.jsonPrimitive?.booleanOrNull == true,
            )
        }
        return ShopInfo(plans, root["provider"]?.jsonPrimitive?.content ?: "mock", manual, base)
    }

    suspend fun createOrder(
        serverUrl: String,
        email: String,
        planId: String,
        customerName: String = "",
    ): OrderInfo {
        require(isValidPremiumEmail(email)) { "Email tidak valid" }
        val base = serverUrl.trim().trimEnd('/')
        val payload = "{\"email\":\"${esc(normalizePremiumEmail(email))}\",\"plan_id\":\"${esc(planId)}\"," +
            "\"customer_name\":\"${esc(customerName.ifBlank { normalizePremiumEmail(email).substringBefore('@') })}\"}"
        val res = client.post("$base/api/orders/create") {
            contentType(ContentType.Application.Json)
            setBody(payload)
        }
        val text = res.bodyAsText()
        check(res.status.value == 200) { "Gagal buat order: ${text.take(200)}" }
        return parseOrder(Json.parseToJsonElement(text).jsonObject["order"]!!.jsonObject)
    }

    suspend fun orderStatus(
        serverUrl: String,
        merchantRef: String,
    ): OrderStatus {
        val base = serverUrl.trim().trimEnd('/')
        val res = client.get("$base/api/orders/status?merchant_ref=${java.net.URLEncoder.encode(merchantRef, "UTF-8")}")
        val text = res.bodyAsText()
        check(res.status.value == 200) { "Order tidak ditemukan" }
        val root = Json.parseToJsonElement(text).jsonObject
        val order = parseOrder(root["order"]!!.jsonObject)
        val premium = root["premium"]?.jsonPrimitive?.booleanOrNull == true
        val expiry = root["expiry"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.longOrNull
        return OrderStatus(order, premium, expiry)
    }

    suspend fun submitProof(
        serverUrl: String,
        merchantRef: String,
        senderName: String,
        note: String = "",
        photoBytes: ByteArray? = null,
        photoMime: String? = null,
    ): OrderInfo {
        require(senderName.isNotBlank()) { "Nama pengirim wajib diisi" }
        val base = serverUrl.trim().trimEnd('/')
        val payload = StringBuilder("{\"merchant_ref\":\"${esc(merchantRef)}\",\"sender_name\":\"${esc(senderName.take(100))}\",\"note\":\"${esc(note.take(300))}\"")
        if (photoBytes != null && photoMime != null) {
            payload.append(",\"photo_base64\":\"${android.util.Base64.encodeToString(photoBytes, android.util.Base64.NO_WRAP)}\",\"photo_mime\":\"${esc(photoMime)}\"")
        }
        payload.append("}")
        val res = client.post("$base/api/orders/proof") {
            contentType(ContentType.Application.Json)
            setBody(payload.toString())
        }
        val text = res.bodyAsText()
        check(res.status.value == 200) { "Gagal kirim bukti: ${text.take(200)}" }
        return parseOrder(Json.parseToJsonElement(text).jsonObject["order"]!!.jsonObject)
    }

    private fun parseOrder(o: JsonObject): OrderInfo =
        OrderInfo(
            merchantRef = o["merchant_ref"]!!.jsonPrimitive.content,
            email = o["email"]?.jsonPrimitive?.content.orEmpty(),
            planId = o["plan_id"]?.jsonPrimitive?.content.orEmpty(),
            amount = o["amount"]?.jsonPrimitive?.int ?: 0,
            status = o["status"]?.jsonPrimitive?.content ?: "UNKNOWN",
            checkoutUrl = o["checkout_url"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content,
            qrString = o["qr_string"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content,
            qrUrl = o["qr_url"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content,
            expiredAt = o["expired_at"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.longOrNull,
            proofSender = o["proof"]?.takeIf { it !is JsonNull }?.jsonObject?.get("sender")?.jsonPrimitive?.content,
        )

    private fun esc(s: String): String = s.replace("\\", "\\\\").replace("\"", "\\\"")
}
