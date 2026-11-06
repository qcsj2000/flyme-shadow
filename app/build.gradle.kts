plugins {
    id("com.android.application")
}

android {
    namespace = "com.flyme.shadow"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.flyme.shadow"
        minSdk = 29
        targetSdk = 35
        versionCode = 5
        versionName = "1.1.3"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // Xposed API 102：只能 compileOnly，不能打进 APK
    compileOnly("io.github.libxposed:api:102.0.0")
    // 模块 App 与框架通信（RemotePreferences）
    implementation("io.github.libxposed:service:102.0.0")

    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
}
