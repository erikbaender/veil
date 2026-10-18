plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    android {
        namespace = "app.veil.core"
        compileSdk = 37
        minSdk = 31
        compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
        withHostTest { }
    }
    // iOS targets come later: iosArm64(), iosSimulatorArm64() — keep commonMain free of JVM/Android APIs.

    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.coroutines.core)
            api(libs.kotlinx.serialization.json)
            api(libs.kotlinx.datetime)
            implementation(libs.androidx.datastore.preferences.core)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
        androidMain.dependencies {
            implementation(libs.kotlinx.coroutines.android)
        }
    }
}
