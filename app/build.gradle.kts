import java.util.Properties
import java.io.FileInputStream

//加载 local.properties
val localProperties = Properties()
val localPropertiesFile = rootProject.file("local.properties")
if (localPropertiesFile.exists()) {
    localProperties.load(FileInputStream(localPropertiesFile))
}

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.jetbrains.kotlin.android)
    id("kotlin-kapt")
}

android {
    namespace = "com.lgloog.moodbox"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.lgloog.moodbox"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        val key = localProperties.getProperty("AI_API_KEY") ?: ""
        buildConfigField("String", "AI_API_KEY", "\"$key\"")
    }

    buildFeatures {
        viewBinding = true
        //开启 buildConfig 功能 (Android Gradle Plugin 8.0+ 默认关闭，需手动开启)
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)

    // 1. 网络请求 (OkHttp)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // 2. 数据解析 (Gson)
    implementation("com.google.code.gson:gson:2.10.1")

    // 3. 数据库 (Room)
    val room_version = "2.6.1"
    implementation("androidx.room:room-runtime:$room_version")
    kapt("androidx.room:room-compiler:$room_version") // 这里需要用到第一步加的 id("kotlin-kapt")
    implementation("androidx.room:room-ktx:$room_version")

    implementation("com.github.PhilJay:MPAndroidChart:v3.1.0")

    // ================== 【新增】端侧 AI 情感计算模块 ==================
    // 1. CameraX：Google 官方推荐的相机库 (生命周期感知，代码极简)
    val cameraxVersion = "1.3.1"
    implementation("androidx.camera:camera-core:$cameraxVersion")
    implementation("androidx.camera:camera-camera2:$cameraxVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraxVersion")
    implementation("androidx.camera:camera-view:$cameraxVersion")

    // 2. ML Kit Face Detection：谷歌的人脸检测模型
    // 使用 play-services 版本，依赖手机的 Google 服务，体积小，检测快
    implementation("com.google.android.gms:play-services-mlkit-face-detection:17.1.0")
}