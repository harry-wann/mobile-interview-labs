// LabKit：每個演練 module 共用的畫面、log 主控台與小工具
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "tw.harry.labs.kit"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    api(platform("androidx.compose:compose-bom:${Versions.composeBom}"))
    api("androidx.compose.ui:ui")
    api("androidx.compose.material3:material3")
    api("androidx.activity:activity-compose:${Versions.activityCompose}")
    api("androidx.lifecycle:lifecycle-runtime-compose:${Versions.lifecycle}")
    api("androidx.lifecycle:lifecycle-viewmodel-compose:${Versions.lifecycle}")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-android:${Versions.coroutines}")
}
