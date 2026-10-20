/**
 * BeatPlay Premium DataStore access (suspend helpers for service/UI).
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.premium

import android.content.Context
import com.metrolist.music.constants.AdEveryNSongsKey
import com.metrolist.music.constants.AdSecondsKey
import com.metrolist.music.constants.DEFAULT_AD_EVERY_N_SONGS
import com.metrolist.music.constants.DEFAULT_AD_SECONDS
import com.metrolist.music.constants.DEFAULT_PREMIUM_SERVER_URL
import com.metrolist.music.constants.IsPremiumKey
import com.metrolist.music.constants.PremiumDevModeKey
import com.metrolist.music.constants.PremiumEmailKey
import com.metrolist.music.constants.PremiumExpiryKey
import com.metrolist.music.constants.PremiumServerUrlKey
import com.metrolist.music.constants.SongsSinceAdKey
import com.metrolist.music.utils.dataStore
import com.metrolist.music.utils.safeDataStoreEdit
import kotlinx.coroutines.flow.first

data class PremiumSnapshot(
    val isPremium: Boolean = false,
    val email: String? = null,
    val expiry: Long? = null,
    val serverUrl: String = DEFAULT_PREMIUM_SERVER_URL,
    val songsSinceAd: Int = 0,
    val adEveryNSongs: Int = DEFAULT_AD_EVERY_N_SONGS,
    val adSeconds: Int = DEFAULT_AD_SECONDS,
    val devMode: Boolean = false,
)

suspend fun Context.readPremium(): PremiumSnapshot {
    val prefs = dataStore.data.first()
    return PremiumSnapshot(
        isPremium = prefs[IsPremiumKey] == true,
        email = prefs[PremiumEmailKey],
        expiry = prefs[PremiumExpiryKey],
        serverUrl = prefs[PremiumServerUrlKey] ?: DEFAULT_PREMIUM_SERVER_URL,
        songsSinceAd = prefs[SongsSinceAdKey] ?: 0,
        adEveryNSongs = prefs[AdEveryNSongsKey] ?: DEFAULT_AD_EVERY_N_SONGS,
        adSeconds = prefs[AdSecondsKey] ?: DEFAULT_AD_SECONDS,
        devMode = prefs[PremiumDevModeKey] == true,
    )
}

/** Returns false when the write failed (disk unavailable). */
suspend fun Context.writePremium(update: PremiumSnapshot): Boolean =
    safeDataStoreEdit {
        it[IsPremiumKey] = update.isPremium
        if (update.email != null) it[PremiumEmailKey] = update.email else it.remove(PremiumEmailKey)
        if (update.expiry != null) it[PremiumExpiryKey] = update.expiry else it.remove(PremiumExpiryKey)
        it[PremiumServerUrlKey] = update.serverUrl
        it[SongsSinceAdKey] = update.songsSinceAd
        it[AdEveryNSongsKey] = update.adEveryNSongs
        it[AdSecondsKey] = update.adSeconds
        it[PremiumDevModeKey] = update.devMode
    }

suspend fun Context.recordSongPlayedForAds() {
    val snap = readPremium()
    safeDataStoreEdit { it[SongsSinceAdKey] = AdGate.nextCounter(snap.songsSinceAd) }
}

suspend fun Context.setSongsSinceAd(count: Int) {
    safeDataStoreEdit { it[SongsSinceAdKey] = count.coerceAtLeast(0) }
}

suspend fun Context.resetAdCounter() {
    safeDataStoreEdit { it[SongsSinceAdKey] = 0 }
}
