// Solution：Kotlin & Compose。單元是這個資料夾底下的子資料夾。
plugins {
    id("lab-section")
}

// 這一區用到的套件（practice 與 solution 要一樣）
dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-collections-immutable:0.3.8")
    testImplementation("app.cash.turbine:turbine:${Versions.turbine}")
    testImplementation("io.mockk:mockk:${Versions.mockk}")
}
