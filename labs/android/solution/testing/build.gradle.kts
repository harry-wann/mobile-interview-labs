// Solution：AI 自動化測試。單元是這個資料夾底下的子資料夾。
plugins {
    id("lab-section")
}

// 這一區用到的套件（practice 與 solution 要一樣）
dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation("app.cash.turbine:turbine:${Versions.turbine}")
    testImplementation("io.mockk:mockk:${Versions.mockk}")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation(platform("androidx.compose:compose-bom:${Versions.composeBom}"))
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

android {
    // Robolectric 在 JVM 上跑 Compose 畫面測試時需要
    testOptions.unitTests.isIncludeAndroidResources = true
}
