package com.utaloom.data

import com.utaloom.lyrics.Romanization
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsTest {
    @Test
    fun automixIsOnByDefault() {
        val defaults = Settings()
        assertTrue("Automix must blend by default", defaults.gaplessPlayback)
        assertTrue("The default blend must be a real crossfade", defaults.crossfadeSeconds > 0)
        // Still a sane value for slower machines and for the settings chooser.
        assertTrue(defaults.crossfadeSeconds <= 12)
    }

    @Test
    fun freshInstallsLandOnTheOneFiveEightDefaults() {
        val defaults = Settings()
        // A fresh install must skip the historical migrations that used to turn
        // these off, so the schema marker starts at the current default set.
        assertEquals(4, defaults.settingsSchema)
        assertEquals("HIGH", defaults.audioQuality)
        assertTrue(defaults.normalizeVolume)
        assertTrue(defaults.smartShuffle)
        assertTrue(defaults.gaplessPlayback)
        assertEquals(2, defaults.crossfadeSeconds)
    }

    @Test
    fun romanizeLanguageListDefaultsAndParsing() {
        val supported = Romanization.SUPPORTED_LANGUAGES

        // Blank preference enables every supported language (the default).
        assertEquals(supported, Settings().lyricsRomanizeLanguages)

        // Unknown entries are ignored and the canonical order is preserved.
        val mixed = Settings(lyricsRomanizeList = "Korean:false,NotALanguage:true,Japanese:true")
        assertEquals(supported - "Korean", mixed.lyricsRomanizeLanguages)

        // A fully disabled list yields no languages, and garbage falls back to all.
        assertEquals(emptyList<String>(), Settings(lyricsRomanizeList = supported.joinToString(",") { "$it:false" }).lyricsRomanizeLanguages)
        assertEquals(supported, Settings(lyricsRomanizeList = "garbage").lyricsRomanizeLanguages)

        // The new fields survive a JSON round-trip like the rest of the settings.
        val original = Settings(
            lyricsRomanize = true,
            lyricsRomanizeList = "Japanese:true,Korean:false",
            lyricsRomanizeAsMain = true,
            lyricsRomanizeCyrillicByLine = true,
        )
        val parsed = Json.decodeFromString(Settings.serializer(), Json.encodeToString(Settings.serializer(), original))
        assertEquals(original, parsed)
        // Like Android, a language missing from the saved list stays enabled (default true).
        assertEquals(supported - "Korean", parsed.lyricsRomanizeLanguages)
    }

    @Test
    fun onlyExactLegacyDefaultIsMigrated() {
        val legacy = "wss://metrolist.caliph.dev/ws"
        val current = "wss://utaloom.caliph.dev/ws"
        val missing = Json.decodeFromString(Settings.serializer(), "{}")
        assertEquals(current, missing.ltServerUrl)
        assertSame(missing, missing.normalizeLegacyLtServerUrl())

        val retained = Settings(
            darkMode = "dark",
            volume = 37,
            cookie = "SAPISID=test; legacy=$legacy",
            accountName = legacy,
            proxy = "https://proxy.example/?target=$legacy",
            ltUsername = "listener",
            lyricsOrder = listOf("LRCLib", "LyricsPlus"),
            minimizeToTray = false,
        )
        listOf(
            legacy to current,
            current to current,
            " \t$legacy\n" to current,
            " \t$current\n" to " \t$current\n",
            " wss://custom.example/ws " to " wss://custom.example/ws ",
            "$legacy/custom" to "$legacy/custom",
            "$legacy?room=custom" to "$legacy?room=custom",
            "wss://custom.example/ws?fallback=$legacy" to "wss://custom.example/ws?fallback=$legacy",
        ).forEach { (url, expected) ->
            val persisted = Json.encodeToString(Settings.serializer(), retained.copy(ltServerUrl = url))
            val parsed = Json.decodeFromString(Settings.serializer(), persisted)
            val normalized = parsed.normalizeLegacyLtServerUrl()
            assertEquals("All other settings must be retained for $url", retained.copy(ltServerUrl = expected), normalized)
            if (url == expected) assertSame(parsed, normalized)
            assertSame(normalized, normalized.normalizeLegacyLtServerUrl())
        }
    }

    @Test
    fun legacyPremiumServerDefaultMigratesToHttpsWithoutOverwritingCustomUrls() {
        val oldDefault = "http://52.141.21.195:8080"
        val newDefault = "https://premium.adminbeat.my.id"

        assertEquals(newDefault, Settings().premiumServerUrl)
        assertEquals(newDefault, Settings(premiumServerUrl = oldDefault, settingsSchema = 3).migrate().premiumServerUrl)
        assertEquals(newDefault, Settings(premiumServerUrl = "$oldDefault/", settingsSchema = 3).migrate().premiumServerUrl)
        assertEquals(
            "https://custom.example/premium",
            Settings(premiumServerUrl = "https://custom.example/premium", settingsSchema = 3).migrate().premiumServerUrl,
        )
    }

    @Test
    fun legacyVolumeAndBlurDefaultsAreResetOnce() {
        // Schema 0 installations use the historical defaults, so the old migration
        // must still run for them even though fresh installs start at schema 4.
        val legacy = Settings(normalizeVolume = true, backgroundBlurEnabled = true, settingsSchema = 0)
        val migrated = legacy.migrate()
        assertFalse(migrated.normalizeVolume)
        assertFalse(migrated.backgroundBlurEnabled)
        assertEquals(4, migrated.settingsSchema)

        // A user who explicitly turns either option back on keeps that choice on later launches.
        val optedInAgain = migrated.copy(normalizeVolume = true, backgroundBlurEnabled = true)
        assertSame(optedInAgain, optedInAgain.migrate())
    }

    @Test
    fun automixSwitchedOffIsTurnedOnOnce() {
        // An install that saved Automix off keeps hearing a silent gap between tracks until the
        // user finds the setting. The upgrade switches it on exactly once.
        val off = Settings(gaplessPlayback = false, crossfadeSeconds = 0, settingsSchema = 1)
        val migrated = off.migrate()
        assertTrue(migrated.gaplessPlayback)
        assertTrue("The upgrade must pick a real blend, not a silent switch", migrated.crossfadeSeconds > 0)
        assertEquals(4, migrated.settingsSchema)

        // A user who switches Automix back off keeps that choice on later launches.
        val offAgain = migrated.copy(gaplessPlayback = false, crossfadeSeconds = 0)
        assertSame(offAgain, offAgain.migrate())

        // An install that already blends keeps the length it chose, including pure gapless.
        val seamless = Settings(gaplessPlayback = true, crossfadeSeconds = 0, settingsSchema = 1)
        val migratedSeamless = seamless.migrate()
        assertEquals(0, migratedSeamless.crossfadeSeconds)
        assertEquals(4, migratedSeamless.settingsSchema)

        val blended = Settings(gaplessPlayback = true, crossfadeSeconds = 8, settingsSchema = 1)
        assertEquals(8, blended.migrate().crossfadeSeconds)
    }
}
