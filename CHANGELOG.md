# Changelog

## [v1.5.16] - 2026-11-14

* fix(playback): stop sudden volume jumping by turning off volume normalization by default (schema 5 migration)
* fix(playback): pre-set engine volume before playback loads to eliminate initial 100% burst
* fix(together): guard room volume synchronization against redundant updates
* feat(playback): default Automix crossfade blend duration set to 8s
* feat(branding): new neon B app logo across Windows desktop, system tray, website and promo assets

## [v1.5.15] - 2026-11-14

* fix(premium): resolve owner email for brand channels (baaf5c7)
* fix(web): resolve downloads per platform, not global latest release (08f6c72)
* chore(web): rebuild deploy zip with forward-slash entries (1515309)
* fix(web): keep Android releases out of desktop feed (084c9fa)
* chore(web): point Android download to v1.4.0 (826ebd3)
* fix(premium): bind desktop entitlement to signed-in account (9851281)
* feat(premium): BuatQris QRIS gateway + status fallback, default server domain (e26e975)

## [v1.5.14] - 2026-11-09

* fix(playback): use independent Windows audio outputs for Automix (67d0b14)

## [v1.5.12] - 2026-11-09

* fix(playback): wait for Automix stream readiness without blocking (3be1cea)

## [v1.5.11] - 2026-11-09

* fix(playback): wait for the next track to be ready before Automix (7412dee)

## [v1.5.10] - 2026-11-09

* fix(playback): reuse libVLC during Automix handover (b59c093)
* Refresh Android 1.3.2 website package (bc4e4ae)
* Update Android download link to v1.3.2 (9716b79)

## [v1.5.9] - 2026-11-09

* fix(playback): isolate VLC volume controls during Automix (94cc447)
* Update promo poster with centered neon B logo v1.3.1 (1be138d)
* Update Android download link: APK now hosted in BeatPlay repo (android-v1.3.1 tag) (c549cc2)
* Refresh website archive after Android download link update (d7ae92f)
* Update website and logo assets: Android 1.3.1 with centered launcher icons, Windows/Linux v1.5.8 desktop icons (bdaf09b)

## [v1.5.8] - 2026-11-06

* fix(playback): cancel Automix fade on seek/skip
* feat(playback): support YouTube Shorts as regular songs (watch endpoint fallback)
* chore: prepare release v1.5.8

## [v1.5.7] - 2026-11-05

* feat(ads): ad banner on home and player
* chore: prepare release v1.5.7

## [v1.5.6] - 2026-11-04

* fix(desktop): tray double-click to restore window
* chore: prepare release v1.5.6

## [v1.5.5] - 2026-11-04

* fix(playback): memory leak in player controller
* chore: prepare release v1.5.5

## [v1.5.4] - 2026-11-03

* fix(playback): audio stutter on low-end hardware
* chore: prepare release v1.5.4

## [v1.5.3] - 2026-11-02

* fix(playback): disable audio normalization by default
* chore: prepare release v1.5.3

## [v1.5.2] - 2026-11-01

* fix(auth): token refresh reliability
* chore: prepare release v1.5.2

## [v1.5.1] - 2026-10-15

* Rilis patch 1.5.1 (b0fd666)

## [v1.5.0] - 2026-10-10

* Initial v1.5.0 release
