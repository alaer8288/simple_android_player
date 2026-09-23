plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.simpleplayer"
    compileSdk = 35
    // AGP 8.7 默认要求 Build Tools 34.0.0，这里显式使用本地已安装的 35.0.0
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "com.example.simpleplayer"
        minSdk = 29          // Codec2 路径从 Android 10 起可用
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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

// 零第三方依赖：全部使用 android.media.* / android.view.* 系统 API
dependencies {
}
