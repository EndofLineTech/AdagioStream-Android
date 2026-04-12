import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

val localProps = rootProject.file("local.properties").takeIf { it.exists() }?.let { file ->
    Properties().apply { file.inputStream().use { load(it) } }
}

android {
    namespace = "com.adagiostream.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.adagiostream.android"
        minSdk = 26
        targetSdk = 35
        versionCode = 86
        versionName = "1.0(86)"
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    signingConfigs {
        create("release") {
            storeFile = (System.getenv("KEYSTORE_FILE") ?: localProps?.getProperty("release.storeFile"))?.let { file(it) }
            storePassword = System.getenv("KEYSTORE_PASSWORD") ?: localProps?.getProperty("release.storePassword") ?: ""
            keyAlias = System.getenv("KEY_ALIAS") ?: localProps?.getProperty("release.keyAlias") ?: ""
            keyPassword = System.getenv("KEY_PASSWORD") ?: localProps?.getProperty("release.keyPassword") ?: ""
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            ndk {
                debugSymbolLevel = "FULL"
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }

    applicationVariants.all {
        outputs.all {
            val output = this as com.android.build.gradle.internal.api.BaseVariantOutputImpl
            val safeVersion = versionName?.replace("(", "-")?.replace(")", "") ?: "unknown"
            output.outputFileName = "AdagioStream-${safeVersion}-${buildType.name}.apk"
        }
    }
}

dependencies {
    // Compose
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    debugImplementation(libs.compose.ui.tooling)

    // AndroidX
    implementation(libs.core.ktx)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.activity.compose)
    implementation(libs.navigation.compose)

    // Media3
    implementation(libs.media3.session)
    implementation(libs.media3.common)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    // libVLC
    implementation(libs.libvlc.all)

    // Coil
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)

    // Security
    implementation(libs.security.crypto)

    // OkHttp
    implementation(libs.okhttp)

    // Kotlin Serialization
    implementation(libs.kotlinx.serialization.json)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)

    // Reorderable
    implementation(libs.reorderable)

    // Testing
    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.robolectric)
}
