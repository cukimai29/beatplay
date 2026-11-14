# Changelog

## [v1.4.2] - 2026-11-14

### Changed
- **ads:** Free accounts now get one short ad break at a random 7–12 minute interval of listening time instead of after every 3 songs. The ad is queued after the current song so it never cuts a track in half, and it still shows for Premium-free listening when casting.
- **premium:** The Premium screen shows the estimated minutes until the next break instead of a song counter.

## [v1.4.1] - 2026-11-14

### Fixed
- **premium:** Brand channels (for example "rzkyy ads") report no email, so Premium looked inactive after switching accounts. The app now reads the owner email from the channel list, and older installs rebuild the missing email automatically at startup.
- **premium:** A temporary network failure while reading the account menu no longer clears a Premium status that was already confirmed.

## [v1.4.0] - 2026-11-14

### Changed
- **premium:** Premium is bound to the signed-in YouTube/Google account. The app detects that account email automatically; arbitrary emails can no longer be typed to check or claim a Premium status.
- **premium:** Replaced the raw VPS IP with `https://premium.adminbeat.my.id`; saved URLs using the old IP migrate once automatically.

### Added
- **premium:** Refresh Premium status automatically at startup and whenever the signed-in account changes.

## [v1.3.1] - 2026-11-06

### Changed
- **brand:** Launcher icons repositioned for better vertical centering — the neon B emblem now sits in the middle of the launcher canvas instead of appearing top-heavy
- **version:** Code bumped to 9 for fresh distribution as a minor visual polish update

## [v1.3.0] - 2026-11-06

### Added
- **brand:** New BeatPlay launcher icon (adaptive + monochrome) and in-app logo
- **player:** Spotify Canvas backgrounds with automatic login (`3c464b0`)
- **canvas:** Unit tests for cookie extraction and Canvas URL matching (`e12a965`)

### Changed
- **defaults:** Fresh installs now start on Audio quality High, Volume normalization on and Smart shuffle on; existing installs keep what they already picked
- **brand:** Logo regenerated from the neon "B" emblem: launcher mipmaps, adaptive foreground/monochrome and the in-app logo all come from one source
- **automix:** "Crossfade" is now Automix and ships enabled by default at 2 seconds, with a one-time migration for existing installs
- **playback:** Reduced streaming lag with next-track prefetching and progressive caching (`9fc42ef`)

### Fixed
- **canvas:** Repaired Spotify lookup and first-frame playback (`9a7c019`)
- **auth:** Restored Scaffold layout and settings for Spotify login (`6356ff7`)
- **playback:** Prevented wrong-thread access during look-ahead caching and fixed WebView login (`1f7441b`)
- **player:** Fixed blank WebView layouts and added manual cookie entry (`61a158d`)
- **player:** Fixed blank Spotify login and added a fallback when Canvas is unavailable (`ec7802f`)
- **lyrics:** Synchronized lyric animations and highlighting during cast playback (`37f542f`)

### BeatPlay
- Kept premium subscription and video-ad flow from 1.2.1
- Version code bumped to 8 so the APK updates existing 1.2.1 installs in place

## [v1.2.0] - 2026-10-12

### Added
- **offline:** add download-only mode and download preferences (`3baa05c`)
- **queue:** save displayed queue as a local playlist (`6cdc52c`)
- **lyrics:** add free Google Translate provider on Android (`d4f8c67`)
- **changelog:** AI-generated changelog on release (`057b524`)

## [v1.1.1] - 2026-10-10

### Fixed
- **Import:** Background notifications that are disabled now show an explanation instead of being silently skipped. (c0a42f5)

## [v1.1.0] - 2026-10-10

### Added
- **Playlist import from links** — Add playlists straight from Spotify and YouTube Music URLs, no manual track entry required.
- **Import progress bar** — Link imports now display a progress bar that tracks the import as it runs.
- **Cancellable imports** — Link imports can be cancelled mid-flight if you change your mind or picked the wrong playlist.
- **Background importing** — Playlist imports now run in the background, so you can keep using the app while they finish, with progress reported through a notification.

## [v1.0.2] - 2026-10-10

### Added
- **Lyrics provider settings** — Added a desktop-style lyrics provider settings screen, giving you control over which sources are used when fetching lyrics. (`237263e`)
- **Smart shuffle** — The player now offers smart shuffle, which reorders upcoming tracks to avoid repetition and keep playback varied. (`88019f3`)
- **Launcher shortcuts** — Added launcher shortcuts for quick access to common player actions right from your home screen. (`88019f3`)

### Changed
- **Listen Together URL migration** — Migrated the old `metrolist.caliph.dev` Listen Together endpoint to `utaloom.caliph.dev`. Existing sessions will continue to work against the new host. (`0015cca`)

## [v1.0.1] - 2026-10-09

### Changed
- New app icon: the **Utaloom Woven U** logo replaces the previous Metrolist-derived icon. The artwork is now original to Utaloom, so it no longer carries the licensing and attribution baggage of the borrowed design.

## [v1.0.0] - 2026-10-09

### Added
- **listentogether:** Default to the `utaloom.caliph.dev` server, and ensure v1 + v2 release signing ([a810c37])
