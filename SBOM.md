# Software Bill of Materials (SBOM)

**Application:** AdagioStream Android
**Package:** `com.adagiostream.android`
**Version:** 1.0(113)
**Generated:** 2026-04-16

## Build Toolchain

| Component | Version | License | Source |
|---|---|---|---|
| Android Gradle Plugin (AGP) | 9.1.0 | Apache 2.0 | [maven](https://maven.google.com/web/index.html#com.android.application:com.android.application.gradle.plugin) / [release notes](https://developer.android.com/build/releases/gradle-plugin) |
| Kotlin | 2.3.20 | Apache 2.0 | [GitHub](https://github.com/JetBrains/kotlin/releases) |
| Kotlin Symbol Processing (KSP) | 2.3.6 | Apache 2.0 | [GitHub](https://github.com/google/ksp/releases) |
| Java Target | 17 | GPL v2 + CPE | — |
| Compile SDK | 36 | — | — |
| Min SDK | 26 (Android 8.0) | — | — |
| Target SDK | 35 (Android 15) | — | — |

## Runtime Dependencies

### Jetpack Compose

| Library | Version | License | Source |
|---|---|---|---|
| Compose BOM | 2026.03.00 | Apache 2.0 | [maven](https://maven.google.com/web/index.html#androidx.compose:compose-bom) / [mapping](https://developer.android.com/develop/ui/compose/bom/bom-mapping) |
| compose-ui | (BOM-managed) | Apache 2.0 | [maven](https://maven.google.com/web/index.html#androidx.compose.ui:ui) |
| compose-ui-graphics | (BOM-managed) | Apache 2.0 | [maven](https://maven.google.com/web/index.html#androidx.compose.ui:ui-graphics) |
| compose-material3 | (BOM-managed) | Apache 2.0 | [maven](https://maven.google.com/web/index.html#androidx.compose.material3:material3) |
| compose-material-icons-extended | (BOM-managed) | Apache 2.0 | [maven](https://maven.google.com/web/index.html#androidx.compose.material:material-icons-extended) |

### AndroidX

| Library | Version | License | Source |
|---|---|---|---|
| core-ktx | 1.18.0 | Apache 2.0 | [maven](https://maven.google.com/web/index.html#androidx.core:core-ktx) / [release notes](https://developer.android.com/jetpack/androidx/releases/core) |
| lifecycle-runtime-ktx | 2.10.0 | Apache 2.0 | [maven](https://maven.google.com/web/index.html#androidx.lifecycle:lifecycle-runtime-ktx) / [release notes](https://developer.android.com/jetpack/androidx/releases/lifecycle) |
| lifecycle-runtime-compose | 2.10.0 | Apache 2.0 | [maven](https://maven.google.com/web/index.html#androidx.lifecycle:lifecycle-runtime-compose) |
| lifecycle-viewmodel-compose | 2.10.0 | Apache 2.0 | [maven](https://maven.google.com/web/index.html#androidx.lifecycle:lifecycle-viewmodel-compose) |
| activity-compose | 1.13.0 | Apache 2.0 | [maven](https://maven.google.com/web/index.html#androidx.activity:activity-compose) / [release notes](https://developer.android.com/jetpack/androidx/releases/activity) |
| navigation-compose | 2.9.7 | Apache 2.0 | [maven](https://maven.google.com/web/index.html#androidx.navigation:navigation-compose) / [release notes](https://developer.android.com/jetpack/androidx/releases/navigation) |
| appcompat | 1.7.1 | Apache 2.0 | [maven](https://maven.google.com/web/index.html#androidx.appcompat:appcompat) / [release notes](https://developer.android.com/jetpack/androidx/releases/appcompat) |
| mediarouter | 1.8.1 | Apache 2.0 | [maven](https://maven.google.com/web/index.html#androidx.mediarouter:mediarouter) / [release notes](https://developer.android.com/jetpack/androidx/releases/mediarouter) |

### Media

| Library | Version | License | Source |
|---|---|---|---|
| media3-session | 1.10.0 | Apache 2.0 | [maven](https://maven.google.com/web/index.html#androidx.media3:media3-session) / [release notes](https://developer.android.com/jetpack/androidx/releases/media3) |
| media3-common | 1.10.0 | Apache 2.0 | [maven](https://maven.google.com/web/index.html#androidx.media3:media3-common) |
| libVLC | 4.0.0-eap24 | LGPL 2.1 | [maven central](https://central.sonatype.com/artifact/org.videolan.android/libvlc-all) / [nightlies](https://artifacts.videolan.org/vlc-android/) |

### Dependency Injection

| Library | Version | License | Source |
|---|---|---|---|
| Hilt Android | 2.59.2 | Apache 2.0 | [GitHub](https://github.com/google/dagger/releases) / [maven](https://maven.google.com/web/index.html#com.google.dagger:hilt-android) |
| Hilt Compiler (KSP) | 2.59.2 | Apache 2.0 | [maven](https://maven.google.com/web/index.html#com.google.dagger:hilt-compiler) |
| Hilt Navigation Compose | 1.3.0 | Apache 2.0 | [maven](https://maven.google.com/web/index.html#androidx.hilt:hilt-navigation-compose) |

### Networking

| Library | Version | License | Source |
|---|---|---|---|
| OkHttp | 5.3.0 | Apache 2.0 | [GitHub](https://github.com/square/okhttp/releases) / [maven central](https://central.sonatype.com/artifact/com.squareup.okhttp3/okhttp) |

### Image Loading

| Library | Version | License | Source |
|---|---|---|---|
| Coil Compose | 3.4.0 | Apache 2.0 | [GitHub](https://github.com/coil-kt/coil/releases) / [maven central](https://central.sonatype.com/artifact/io.coil-kt.coil3/coil-compose) |
| Coil Network OkHttp | 3.4.0 | Apache 2.0 | [maven central](https://central.sonatype.com/artifact/io.coil-kt.coil3/coil-network-okhttp) |

### Serialization

| Library | Version | License | Source |
|---|---|---|---|
| kotlinx-serialization-json | 1.11.0 | Apache 2.0 | [GitHub](https://github.com/Kotlin/kotlinx.serialization/releases) / [maven central](https://central.sonatype.com/artifact/org.jetbrains.kotlinx/kotlinx-serialization-json) |

### Coroutines

| Library | Version | License | Source |
|---|---|---|---|
| kotlinx-coroutines-android | 1.10.2 | Apache 2.0 | [GitHub](https://github.com/Kotlin/kotlinx.coroutines/releases) / [maven central](https://central.sonatype.com/artifact/org.jetbrains.kotlinx/kotlinx-coroutines-android) |

### Google Play Services

| Library | Version | License | Source |
|---|---|---|---|
| Cast Framework | 22.3.1 | Android Software Development Kit License | [maven](https://maven.google.com/web/index.html#com.google.android.gms:play-services-cast-framework) / [release notes](https://developers.google.com/cast/docs/release-notes) |

### UI Utilities

| Library | Version | License | Source |
|---|---|---|---|
| Reorderable (sh.calvin) | 3.0.0 | Apache 2.0 | [GitHub](https://github.com/Calvin-LL/Reorderable/releases) |

## Test Dependencies

| Library | Version | License | Source |
|---|---|---|---|
| JUnit | 4.13.2 | EPL 1.0 | [GitHub](https://github.com/junit-team/junit4/releases) / [maven central](https://central.sonatype.com/artifact/junit/junit) |
| MockK | 1.14.9 | Apache 2.0 | [GitHub](https://github.com/mockk/mockk/releases) / [maven central](https://central.sonatype.com/artifact/io.mockk/mockk) |
| kotlinx-coroutines-test | 1.10.2 | Apache 2.0 | [GitHub](https://github.com/Kotlin/kotlinx.coroutines/releases) |
| Turbine | 1.2.1 | Apache 2.0 | [GitHub](https://github.com/cashapp/turbine/releases) / [maven central](https://central.sonatype.com/artifact/app.cash.turbine/turbine) |
| Robolectric | 4.16.1 | MIT | [GitHub](https://github.com/robolectric/robolectric/releases) |

## License Summary

| License | Count |
|---|---|
| Apache 2.0 | 24 |
| LGPL 2.1 | 1 (libVLC) |
| Android SDK License | 1 (Cast Framework) |
| EPL 1.0 | 1 (JUnit) |
| MIT | 1 (Robolectric) |

## Notes

- **libVLC** is licensed under LGPL 2.1. It is dynamically linked, which satisfies LGPL requirements for proprietary applications.
- **Compose BOM** manages versions for all `androidx.compose` artifacts — individual versions are determined by the BOM version.
- Native code is restricted to `arm64-v8a` ABI only.
- Release builds use ProGuard/R8 minification and resource shrinking.
