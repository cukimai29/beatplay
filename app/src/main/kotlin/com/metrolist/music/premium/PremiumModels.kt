/**
 * BeatPlay Premium models (mirrors the desktop app + premium server API).
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.premium

data class Plan(
    val id: String,
    val title: String,
    val price: Int,
    val days: Int,
)

data class ManualQris(
    val accountName: String,
    val instructions: String,
    val hasImage: Boolean,
)

data class ShopInfo(
    val plans: List<Plan>,
    val provider: String,
    val manual: ManualQris?,
    val baseUrl: String,
)

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

data class OrderStatus(
    val order: OrderInfo,
    val premium: Boolean,
    val expiry: Long?,
)
