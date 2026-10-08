plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.compose.compiler)
}

android {
    namespace = "com.example.scalerelay"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.scalerelay"
        // 前台服务 + GATT 的连接管理需要 API 31+ 的 BLUETOOTH_SCAN/CONNECT 模型；
        // 测试机是 API 35/36。不为了更低版本引入 legacy 蓝牙权限分支。
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"
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
    buildFeatures {
        compose = true
        aidl = false
        buildConfig = true
        shaders = false
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)

    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // Health Connect：版本与 WeightDiary（已在本机真机验证写入成功）一致
    implementation(libs.androidx.health.connect.client)

    // AES-CCM，用于 CMTP 解密
    implementation(libs.bouncycastle)

    testImplementation(libs.junit)
}
