// Solution：資深進階。單元是這個資料夾底下的子資料夾。
plugins {
    id("lab-section")
}

// 這一區用到的套件（practice 與 solution 要一樣）
dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    testImplementation("app.cash.turbine:turbine:${Versions.turbine}")
}
