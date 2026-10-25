import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

/**
 * Date-based version: year.month.release-in-that-month (e.g. 2026.10.0). Veil follows a website
 * that changes under it, so what matters is how recent a build is. The Android version code is
 * the same date as a number (2026.10.0 -> 20261000), so it always increases.
 */
val veilVersion = "2026.10.1"
val veilVersionCode = veilVersion.split('.').map(String::toInt).let { (year, month, release) ->
    require(month in 1..12 && release in 0..99) { "version must be year.month.release" }
    year * 10_000 + month * 100 + release
}

/**
 * Release signing key, kept outside the repository (default ~/.config/veil/signing.properties,
 * or the path in VEIL_SIGNING). Updates only install over a build signed with the same key.
 */
val releaseSigning = Properties().apply {
    val file = file(System.getenv("VEIL_SIGNING") ?: "${System.getProperty("user.home")}/.config/veil/signing.properties")
    if (file.isFile) file.inputStream().use(::load)
}

android {
    namespace = "app.veil.android"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.veil.client"
        minSdk = 31
        targetSdk = 37
        versionCode = veilVersionCode
        versionName = veilVersion
    }

    signingConfigs {
        if (releaseSigning.isNotEmpty()) {
            create("release") {
                storeFile = file(releaseSigning.getProperty("storeFile"))
                storePassword = releaseSigning.getProperty("storePassword")
                keyAlias = releaseSigning.getProperty("keyAlias")
                keyPassword = releaseSigning.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Without the release key (e.g. a fresh checkout) builds are signed with the debug key;
            // those can't update an installed release.
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/versions/9/OSGI-INF/MANIFEST.MF", "DebugProbesKt.bin")
    }

    dependenciesInfo {
        // No dependency metadata blob in the APK (it's encrypted for Google Play only).
        includeInApk = false
        includeInBundle = false
    }
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    implementation(project(":ui"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.work.runtime)
    implementation(libs.kotlinx.coroutines.android)
}
