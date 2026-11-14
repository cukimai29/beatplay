/**
 * BeatPlay Premium pure rules (no Android deps, unit-tested).
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.premium

private val EMAIL_RE = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")

fun isValidPremiumEmail(email: String) = EMAIL_RE.matches(email.trim())

fun normalizePremiumEmail(email: String) = email.trim().lowercase()

/** Host of the old raw-IP premium server; installs still pointing at it are migrated. */
const val LEGACY_PREMIUM_SERVER_HOST = "52.141.21.195"

/** True when a saved premium server URL still points at the old raw-IP address. */
fun isLegacyPremiumServerUrl(url: String): Boolean {
    val host = url.trim().trimEnd('/').substringAfter("://").substringBefore('/').substringBefore(':')
    return host.equals(LEGACY_PREMIUM_SERVER_HOST, ignoreCase = true)
}

/** Pure ad-gating rules. */
object AdGate {
    /** True when an ad must play before the next track. */
    fun shouldShowAd(
        isPremium: Boolean,
        songsSinceAd: Int,
        adEveryNSongs: Int,
    ): Boolean {
        if (isPremium) return false
        val every = adEveryNSongs.coerceIn(1, 100)
        return songsSinceAd >= every
    }

    /** Counter value after one song finished (call only when no ad was shown). */
    fun nextCounter(songsSinceAd: Int): Int = (songsSinceAd + 1).coerceAtLeast(0)

    /** Media id of the inserted video-ad queue item. */
    const val AD_MEDIA_ID = "beatplay-ad"

    fun isAdMediaId(id: String?): Boolean = id == AD_MEDIA_ID

    /** What to do when one song just finished naturally. Returns (action, newCounter). */
    fun decideAfterSong(
        isPremium: Boolean,
        songsSinceAd: Int,
        adEveryNSongs: Int,
        casting: Boolean,
    ): Pair<AdDecision, Int> {
        val newCount = nextCounter(songsSinceAd)
        val action =
            if (!casting && shouldShowAd(isPremium, newCount, adEveryNSongs)) AdDecision.INSERT_AD
            else AdDecision.COUNT_ONLY
        return action to newCount
    }
}

enum class AdDecision {
    COUNT_ONLY,
    INSERT_AD,
}

const val PROOF_PHOTO_MAX_BYTES: Long = 4L * 1024 * 1024

/** Detect proof-photo mime from magic bytes + extension. Null when not a real JPG/PNG. */
fun photoMimeFor(
    fileName: String,
    bytes: ByteArray,
): String? {
    if (bytes.isEmpty() || bytes.size > PROOF_PHOTO_MAX_BYTES) return null
    val ext = fileName.substringAfterLast('.', "").lowercase()
    val jpeg = bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()
    val png = bytes.size >= 4 && bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() &&
        bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte()
    if (jpeg && ext in setOf("jpg", "jpeg")) return "image/jpeg"
    if (png && ext == "png") return "image/png"
    return null
}
