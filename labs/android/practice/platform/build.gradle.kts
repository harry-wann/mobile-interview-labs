// Practice：iOS × Android 平台對照。單元是這個資料夾底下的子資料夾。
plugins {
    id("lab-section")
}

// 這一區用到的套件（practice 與 solution 要一樣）
dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("com.google.crypto.tink:tink-android:1.15.0")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("com.squareup.okhttp3:okhttp-tls:4.12.0")
    testImplementation("com.android.tools:r8:8.13.19")
    testImplementation("app.cash.turbine:turbine:${Versions.turbine}")
}
