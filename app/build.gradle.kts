plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.example.mobileobs"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.example.mobileobs"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    // USB 採集卡(UVC)直連。若找不到此版本，請到 https://github.com/shiyinghan/UVCAndroid 查最新版號
    implementation("com.herohan:UVCAndroid:1.0.5")
}
