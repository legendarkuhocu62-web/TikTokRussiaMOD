plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "ru.cobrowser.app"
    compileSdk = 34

    // Постоянный ключ подписи: каждая сборка подписана одним и тем же ключом,
    // поэтому новая версия ставится поверх старой без удаления приложения.
    signingConfigs {
        create("cobrowser") {
            storeFile = file("cobrowser.keystore")
            storeType = "pkcs12"
            storePassword = "cobrowser123"
            keyAlias = "cobrowser"
            keyPassword = "cobrowser123"
        }
    }

    defaultConfig {
        applicationId = "ru.cobrowser.app"
        minSdk = 24
        targetSdk = 34
        // номер версии растёт с каждой сборкой на GitHub, чтобы установщик видел обновление
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "1." + (System.getenv("GITHUB_RUN_NUMBER") ?: "0")
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("cobrowser")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("cobrowser")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.webkit:webkit:1.8.0")
    implementation("androidx.media3:media3-exoplayer:1.2.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.2.1")
    implementation("androidx.media3:media3-exoplayer-dash:1.2.1")
}
