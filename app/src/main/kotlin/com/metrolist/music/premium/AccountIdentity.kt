package com.metrolist.music.premium

import com.metrolist.innertube.models.AccountInfo
import com.metrolist.innertube.models.YouTubeAccount

/**
 * Premium belongs to the signed-in Google account. Brand channels do not expose an address in
 * the account menu ([AccountInfo.email] is null), so the owner address is taken from the
 * channel list byline ("Ganti Youtube channel") instead.
 */
fun resolveAccountEmail(
    account: AccountInfo,
    accounts: List<YouTubeAccount>,
): String? {
    account.email?.let(::emailOrNull)?.let { return it }

    val handle = normalizeChannelHandle(account.channelHandle)
    if (handle != null) {
        accounts.firstNotNullOfOrNull { candidate ->
            if (normalizeChannelHandle(candidate.channelHandle) == handle) {
                emailOrNull(candidate.byline)
            } else {
                null
            }
        }?.let { return it }
    }

    val name = account.name.trim()
    if (name.isNotEmpty()) {
        accounts.firstNotNullOfOrNull { candidate ->
            if (candidate.name.trim().equals(name, ignoreCase = true)) {
                emailOrNull(candidate.byline)
            } else {
                null
            }
        }?.let { return it }
    }

    return accounts.firstOrNull { it.isSelected }?.byline?.let(::emailOrNull)
}

/** "@RzkyyAds" -> "rzkyyads" so handles from different endpoints compare equal. */
fun normalizeChannelHandle(handle: String?): String? {
    val trimmed = handle?.trim()?.removePrefix("@")?.trim().orEmpty()
    return trimmed.lowercase().takeIf { it.isNotEmpty() }
}

private fun emailOrNull(value: String?): String? =
    value?.let(::normalizePremiumEmail)?.takeIf(::isValidPremiumEmail)
