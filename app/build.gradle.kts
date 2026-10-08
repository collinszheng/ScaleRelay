import java.util.Properties

// 签名口令放在项目根的 keystore.properties（已 gitignore）。
// 文件不存在时**不做签名** —— 这样别人 clone 下来照样能跑 assembleDebug，
// 只是出不了正式包。这与 WeightDiary 的做法一致。
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}

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
        versionName = "0.1.0"
    }

    signingConfigs {
        if (keystorePropsFile.exists()) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // 口令文件不在时不设 signingConfig，产物是 unsigned 包（无法安装，但能构建）
            if (keystorePropsFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
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

/**
 * 把签名后的正式包拷到项目根的 dist/，并起一个带版本号的英文名。
 *
 * 产物名一律英文：中文文件名在 Windows / adb / 手机文件管理器之间转手会踩编码坑
 * （WeightDiary 上实测过「体重日记-1.0.apk」传成「浣撻噸鏃ヨ-1」）。
 */
tasks.register<Copy>("distRelease") {
    group = "distribution"
    description = "把签名后的正式包拷到 dist/ScaleRelay-<版本>.apk"
    dependsOn("assembleRelease")
    from(layout.buildDirectory.file("outputs/apk/release/app-release.apk"))
    into(rootProject.layout.projectDirectory.dir("dist"))
    // 用字符串重载而不是 rename { } 闭包：闭包会捕获 Gradle 脚本对象，
    // 配置缓存无法序列化，构建会直接失败
    rename("app-release\\.apk", "ScaleRelay-${android.defaultConfig.versionName}.apk")
}