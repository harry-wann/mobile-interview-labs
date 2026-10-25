// practice／solution 底下每一區共用的設定。module 的 build.gradle.kts 只要寫：
//
//   plugins { id("lab-section") }
//
// 原始碼：這個資料夾底下的每個子資料夾（kotlin03-coroutines/、section/…）都是原始碼資料夾，
//        不用照 src/main/java/<package> 的深層結構。test/ 是 JVM 單元測試。
// 權限、Activity 等設定：放在這個資料夾的 AndroidManifest.xml（有需要再建立），會自動合併進 App。
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val kind = project.name.substringBefore('-')       // practice / solution
val section = project.name.substringAfter('-')     // kotlin / platform / …

android {
    namespace = "tw.harry.labs.$kind.$section"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
    }

    buildFeatures {
        compose = true
    }

    sourceSets["main"].apply {
        val skip = setOf("build", "test", ".gradle", ".kotlin", "res")
        java.setSrcDirs(
            projectDir.listFiles()!!
                .filter { it.isDirectory && it.name !in skip }
                .sortedBy { it.name },
        )
        if (file("AndroidManifest.xml").isFile) manifest.srcFile("AndroidManifest.xml")
        if (file("res").isDirectory) res.setSrcDirs(listOf("res"))
    }
    sourceSets["test"].java.setSrcDirs(listOf("test"))

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        // JVM 單元測試裡呼叫到 android.util.Log 這類 API 時回傳預設值，不要直接丟例外
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    "api"(project(":labkit"))
    "implementation"(platform("androidx.compose:compose-bom:${Versions.composeBom}"))
    "implementation"("androidx.compose.ui:ui")
    "implementation"("androidx.compose.material3:material3")
    "implementation"("androidx.lifecycle:lifecycle-runtime-compose:${Versions.lifecycle}")
    "implementation"("androidx.lifecycle:lifecycle-viewmodel-compose:${Versions.lifecycle}")
    "implementation"("org.jetbrains.kotlinx:kotlinx-coroutines-android:${Versions.coroutines}")

    "testImplementation"("junit:junit:${Versions.junit}")
    "testImplementation"("org.jetbrains.kotlinx:kotlinx-coroutines-test:${Versions.coroutines}")
}
