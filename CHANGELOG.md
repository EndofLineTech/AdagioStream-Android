# Changelog

All notable changes to Adagio Stream for Android are documented in this file.

Format follows [Keep a Changelog](https://keepachangelog.com/en/1.0.0/).

## Releases

Play Store builds are tagged `v1.0.<versionCode>` on the commit that bumps
`versionCode` in `app/build.gradle.kts`. Always bump `versionCode` before
tagging — Play Store rejects duplicate version codes. The CI/CD workflow
publishes the signed AAB automatically when a `v*` tag is pushed.

---

## [Unreleased]

### Added

- **Consolidated Custom M3Us tab** — The My M3Us tab now matches iOS: an
  "M3U Accounts" section lists your M3U provider accounts above your custom
  playlists, with tap-to-edit and delete (with confirmation) right from the
  tab. Custom playlists can now be reordered via **Move Up / Move Down** in
  each playlist's menu. The empty state only shows when you have neither
  playlists nor M3U accounts. Adding new M3U accounts remains in
  Settings → Accounts.

- **Collapsible channel groups** — Channel-list groups (including the
  Favorites section, which renders first) collapse and expand with a tap on
  the header, all collapsed by default. Expanded groups are remembered
  across restarts, searching temporarily opens every matching group, and a
  new overflow menu offers **Expand All** / **Collapse All**. (iOS parity:
  beads_mobilemusic-ll2.)

- **StellarTunerLog metadata source** — A second SiriusXM now-playing
  metadata service alongside xmplaylist.com, selectable in Settings and now
  the default. Ads, promos, and filler cuts are hidden automatically, and
  switching sources re-matches your channels and resumes the one you're
  listening to.
- **Now Playing refresh interval** — Choose how often SiriusXM song info
  refreshes for the tuned channel (10–45 seconds, default 30).
- **Prefer Live Scores** — When a channel is carrying a live game, show the
  score instead of song metadata on Now Playing, the lock screen, and
  Android Auto. On by default; toggle in Settings.

- **Navidrome / Subsonic server support (foundation)** — Add a Navidrome
  (or any Subsonic-compatible) music server as a new account type alongside
  existing M3U and Xtream Codes providers. Enter your server URL, username,
  and password, then tap **Test Connection** to verify the server is
  reachable and your credentials are accepted before saving. Full music
  library browsing and playback are in progress.

### Fixed

- **SiriusXM metadata surviving a bad launch** — If the station catalog
  fetch failed once at app launch (flaky network, captive portal), SiriusXM
  song info stayed dead for the whole session. The fetch now retries with
  backoff and attaches metadata to the playing channel as soon as it
  succeeds.
- Track titles can no longer show the stream file name (e.g. "20998.ts").

---

## [1.0.119] — 2026-05-28

Initial Play Store release series. M3U playlist and Xtream Codes IPTV
providers, Android Auto, Chromecast, time-shift buffer, EPG, SiriusXM
metadata, ESPN live scores, favorites, custom playlists, home screen widget,
share intent, GDPR privacy controls.
