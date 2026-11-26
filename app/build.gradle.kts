plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "com.watchparty"
    compileSdk = 34
    defaultConfig { applicationId = "com.watchparty"; minSdk = 26; targetSdk = 34; versionCode = 1; versionName = "1.0" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.getstream:stream-webrtc-android:1.1.1")
}
