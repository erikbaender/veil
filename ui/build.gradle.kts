plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose.multiplatform)
}

kotlin {
    android {
        namespace = "app.veil.ui"
        compileSdk = 37
        minSdk = 31
        compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
        androidResources { enable = true }
        withHostTest { }
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":core"))
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.ui)
            implementation(libs.compose.material3)
            implementation(libs.compose.material.icons.core)
            implementation(libs.compose.components.resources)
            implementation(libs.compose.ui.tooling.preview)
            implementation(libs.androidx.lifecycle.viewmodel.compose)
            implementation(libs.androidx.lifecycle.runtime.compose)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
        androidMain.dependencies {
            implementation(libs.androidx.activity.compose)
            implementation(libs.androidx.webkit)
        }
    }
}

dependencies {
    "androidRuntimeClasspath"(libs.androidx.compose.ui.tooling)
}

compose.resources {
    packageOfResClass = "app.veil.ui.res"
    publicResClass = false
}
