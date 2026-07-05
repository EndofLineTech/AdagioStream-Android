# Adagio Stream

A feature-rich IPTV audio streaming app for Android. Manage multiple streaming providers, browse channels by group, and listen with Android Auto, Chromecast, and background playback support.

## Features

- **Multi-Provider Support** — Connect M3U playlists and Xtream Codes providers simultaneously
- **Android Auto** — Full channel browsing and playback in your car via Media3 `MediaLibraryService`
- **Chromecast** — Stream to any Google Cast-compatible device
- **Time-Shift Buffer** — Seamless audio continuity during phone calls and interruptions with skip-to-live
- **Auto-Reconnect** — Stream automatically resumes when switching between Wi-Fi and mobile networks
- **Favorites** — Mark channels as favorites for quick access
- **Channel Groups** — Organize, enable/disable, and favorite groups with custom sort order
- **EPG** — Electronic program guide integration for both M3U and Xtream Codes
- **SiriusXM Metadata** — Automatic track detection with song title, artist, and artwork via xmplaylist.com
- **Live Sports Scores** — Real-time NFL, MLB, NBA, and NHL scores from ESPN on matched channels
- **Loved Songs** — Save tracks you hear on SiriusXM channels to your library
- **Custom Playlists** — Create and share custom channel playlists
- **Home Screen Widget** — Now-playing widget with play/pause control
- **Share Intent** — Import provider URLs directly from any app's share sheet
- **Privacy First** — Zero analytics, zero tracking, all data stored locally on device
- **GDPR Compliant** — Export your data, delete all data, in-app privacy policy

## Connecting a Navidrome / Subsonic Server

> **Note:** Music library browsing and playback are in progress — this
> release establishes the server connection foundation.

To add a Navidrome (or any Subsonic-compatible) server:

1. In the app, go to **Settings → Accounts** and tap **+**.
2. Select the **Navidrome** tab.
3. Fill in your server details:

   | Field | Example |
   |-------|---------|
   | **Server URL** | `http://192.168.1.10:4533` or `https://music.example.com` |
   | **Username** | your Navidrome username |
   | **Password** | your Navidrome password |
   | **Display Name** (optional) | `My Navidrome` — defaults to the server hostname |

4. Tap **Test Connection**. Once the test passes, **Save** becomes available.

### http vs. https

Both `http://` and `https://` URLs are accepted. Most home Navidrome
instances run on a local IP over plain http (e.g. `http://192.168.1.10:4533`
on port 4533 by default); the app supports that.

When you enter an `http://` URL, the app shows a warning: *"http is
unencrypted. Your token auth is still safe, but the connection isn't private.
Use https if your server supports it."* Authentication uses the Subsonic
token + salt protocol (an MD5 hash of your password and a random salt), so
your password is never transmitted in plaintext even over http. On a trusted
home network, http is generally acceptable; if your server is exposed to the
internet, use https.

### Test Connection results

| Result | What to do |
|--------|------------|
| **Connection successful** | Credentials accepted — tap **Save**. |
| Incorrect username or password | Double-check your Navidrome username and password. |
| Can't reach server — check the URL | Verify the URL and port; confirm the device is on the same network as the server. |
| That doesn't look like a Subsonic/Navidrome server | The URL may point to the wrong service or an incorrect reverse-proxy path. |
| Enter a valid server URL | Enter a full `http://` or `https://` URL including the port if required. |
| Server error (HTTP _n_) | The server returned an unexpected error — try again or check server logs. |
| Unexpected response from the server | The server responded but the app couldn't parse it — verify it is a Navidrome or Subsonic-compatible server. |

## Requirements

- Android 8.0 (API 26) or newer
- Android Studio Ladybug or newer (AGP 9.1+)
- JDK 17

## Setup

This project uses [Gradle](https://gradle.org/) with the Kotlin DSL and [beads](https://github.com/MotWakorb/beads) for issue tracking.

```bash
# Set JAVA_HOME to the JDK bundled with Android Studio
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"

# Set up beads issue tracking
chmod 700 .beads
bd bootstrap
bd import
bd hooks install
```

For release signing, define the following in `local.properties` (or as environment variables `KEYSTORE_FILE`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`):

```properties
release.storeFile=/path/to/release.keystore
release.storePassword=...
release.keyAlias=...
release.keyPassword=...
```

## Building

```bash
# Debug build
./gradlew :app:assembleDebug

# Release build (signed AAB for Play Store)
./gradlew :app:bundleRelease

# Install debug build on a connected device
./gradlew :app:installDebug
```

## Testing

```bash
# Unit tests (JVM + Robolectric)
./gradlew :app:testDebugUnitTest

# Lint
./gradlew :app:lintDebug
```

## Versioning

Bump `versionCode` and `versionName` in `app/build.gradle.kts`, commit, and tag as `v1.0.<versionCode>`:

```bash
git tag v1.0.117
git push && git push origin v1.0.117
```

The tag triggers the CI/CD publish workflow. **Always bump `versionCode` before tagging** — Play Store rejects duplicate version codes.

## Project Structure

```
app/src/main/java/com/adagiostream/android/
├── model/            Data structures (Provider, Channel, AppSettings, etc.)
├── service/          Business logic
│   ├── account/      Provider account management
│   ├── parsing/      M3U and Xtream Codes parsers
│   ├── persistence/  Local storage
│   ├── player/       AudioPlaybackService, VLC wrapper, TimeShiftBuffer, Cast
│   ├── playlist/     Custom playlist manager
│   └── metadata/     ESPN scores, SiriusXM metadata, iTunes artwork
├── ui/
│   ├── navigation/   Navigation graph
│   ├── theme/        Material 3 theme
│   ├── components/   Shared Compose components
│   └── screens/
│       ├── accounts/     Provider add/edit
│       ├── channels/     Channel browsing and search
│       ├── groups/       Group management
│       ├── epg/          Electronic program guide
│       ├── favorites/    Favorite channels
│       ├── loved/        Saved track library
│       ├── m3us/         Custom playlist management
│       ├── nowplaying/   Now playing and mini player
│       ├── settings/     Settings and advanced settings
│       ├── setup/        First-time setup flow
│       ├── licenses/     OSS license attributions
│       └── privacy/      Privacy policy and data controls
├── widget/           Home screen now-playing widget
├── di/               Hilt modules
└── util/             Constants, extensions, helpers
```

## Dependencies

- **[libVLC](https://www.videolan.org/vlc/libvlc.html)** 4.0.0-eap24 — LibVLC-based audio playback (LGPL 2.1)
- **[AndroidX Media3](https://developer.android.com/jetpack/androidx/releases/media3)** 1.10.0 — `MediaLibraryService` for Android Auto and media session
- **[Jetpack Compose](https://developer.android.com/jetpack/compose)** (BOM 2026.03.00) — UI toolkit
- **[Hilt](https://dagger.dev/hilt/)** 2.59.2 — Dependency injection
- **[Google Cast Framework](https://developers.google.com/cast)** 22.3.1 — Chromecast support
- **[OkHttp](https://square.github.io/okhttp/)** 5.3.0 — HTTP client
- **[Coil](https://coil-kt.github.io/coil/)** 3.4.0 — Image loading

See [SBOM.md](SBOM.md) for the complete software bill of materials including versions, licenses, and source URLs.

No third-party analytics, crash reporting, or advertising SDKs.

## Privacy

Adagio Stream does not collect, store, or transmit any personal data.

- No analytics or telemetry
- No advertising
- No device identifiers or tracking
- All data stored locally on device in encrypted SharedPreferences and the app sandbox
- Debug logs automatically redact credentials
- GDPR compliant with data export and deletion controls

## License

Copyright (c) 2026 End of Line Technologies.

Licensed under the **GNU Affero General Public License v3.0** with additional permissions under section 7 covering (1) distribution through Android application stores subject to standard store end-user terms (Google Play, Amazon Appstore, Samsung Galaxy Store, F-Droid, direct APK), and (2) linking with proprietary Android platform components such as Google Play Services / Cast Framework.

See [LICENSE](LICENSE) for the full license text and additional permissions. Downstream recipients may remove the additional permissions per AGPL §7.
