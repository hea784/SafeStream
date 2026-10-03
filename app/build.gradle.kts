import java.io.File
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// 签名凭据从仓库外的 properties 文件读取，绝不提交进版本控制。
// 路径可用环境变量 SAFESTREAM_SIGNING_PROPS 覆盖。
val signingPropsFile = File(
    System.getenv("SAFESTREAM_SIGNING_PROPS")
        ?: "D:\\dev-tools\\secrets\\safestream\\credentials.properties",
)
val signingProps = Properties().apply {
    if (signingPropsFile.exists()) {
        signingPropsFile.inputStream().use { load(it) }
    }
}
val hasSigning = signingProps.getProperty("storeFile") != null

android {
    namespace = "com.heasafe.safestream"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.heasafe.safestream"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        resourceConfigurations += listOf("zh", "en")
        // 默认关闭：SSRF 防护。debug 构建会覆盖为 true 作为本地测试接缝。
        buildConfigField("boolean", "ALLOW_PRIVATE_HOSTS", "false")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // 找不到凭据时回落到 debug 签名：至少产出可安装的包，
            // 而不是装不上的 unsigned 包。
            signingConfig = if (hasSigning) {
                signingConfigs.create("release") {
                    storeFile = file(signingProps.getProperty("storeFile"))
                    storePassword = signingProps.getProperty("storePassword")
                    keyAlias = signingProps.getProperty("keyAlias")
                    keyPassword = signingProps.getProperty("keyPassword")
                }
            } else {
                signingConfigs.getByName("debug")
            }
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            // 测试接缝：自动化测试需要用 adb reverse + 本地 HTTP 服务跑通
            // "沙箱 -> 发现 -> 播放" 链路，而本地地址默认被 UrlGuard 的 SSRF 防护拒绝。
            // release 里恒为 false，SSRF 防护不受影响。
            buildConfigField("boolean", "ALLOW_PRIVATE_HOSTS", "true")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.swiperefreshlayout)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.webkit)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.ui)
    implementation(libs.media3.session)

    testImplementation(libs.junit)
}
