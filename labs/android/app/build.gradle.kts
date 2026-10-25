// MobileLabs App：把 practice-* 與 solution-* 所有 module 放進同一個 App，首頁依區列出每個演練。
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "tw.harry.labs"
    compileSdk = 36

    defaultConfig {
        applicationId = "tw.harry.labs"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
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
    implementation(project(":labkit"))
    // 記憶體洩漏偵測（Advanced03Memory 的演練會用到）：debug 版自動啟用，洩漏時跳通知
    debugImplementation("com.squareup.leakcanary:leakcanary-android:2.14")
    // 自動加入所有 practice-* 與 solution-* module
    rootProject.subprojects
        .filter { it.name.startsWith("practice-") || it.name.startsWith("solution-") }
        .forEach { implementation(project(it.path)) }
}
