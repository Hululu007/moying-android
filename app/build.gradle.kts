plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.moying.app"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.moying.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 98
        versionName = "1.9.49"
        testInstrumentationRunner = "com.moying.app.SourceDeletionInstrumentation"
    }
    buildFeatures { compose = true }
    buildTypes {
        getByName("release") {
            isDebuggable = false
            isMinifyEnabled = false
            // 本地分发沿用已有证书，覆盖安装时保留用户片源和观看记录。
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    // 保留已有 Ijk 原始资源，但当前播放器使用稳定的平台 API，不打包未使用的库。
    packaging { jniLibs.excludes += "**/libijk*.so" }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    // 稳定 BOM 将 UI / Foundation / Runtime 固定在 1.7.6。
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.runtime:runtime")
    implementation("androidx.compose.ui:ui-tooling-preview")
    // 窗口尺寸类：按窗口宽度/高度断点分支，不依赖设备型号。
    implementation("androidx.window:window-core:1.5.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
