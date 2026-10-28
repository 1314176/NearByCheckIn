import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val localProperties = Properties().apply {
    val localPropertiesFile = rootProject.file("local.properties")

    if (localPropertiesFile.exists()) {
        localPropertiesFile.inputStream().use(::load)
    }
}

val baiduMapAk = localProperties.getProperty("BAIDU_MAP_AK").orEmpty()
val amapMapAk = localProperties.getProperty("AMAP_MAP_AK").orEmpty()

android {
    namespace = "com.wu.nearbycheckin"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.wu.nearbycheckin"
        manifestPlaceholders["BAIDU_MAP_AK"] = baiduMapAk
        manifestPlaceholders["AMAP_MAP_AK"] = amapMapAk
        minSdk = 26
        targetSdk = 37
        // 版本从3.0.0起，每完成一轮代码编辑后递增，并同步主界面底部版本文字。
        versionCode = 30022
        versionName = "3.0.22"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }

    packaging {
        jniLibs {
            // 第三方原生库无法 strip 时显式保留调试符号，避免构建阶段反复提示。
            keepDebugSymbols += listOf("**/*.so")
        }
    }
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:model"))
    implementation(project(":core:domain"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // 百度地图基础组件
    implementation(libs.baidumap)

    // 百度地图地点检索组件
    implementation(libs.baidusearch)

    // 百度地图坐标转换和距离计算组件
    implementation(libs.baiduutil)
}
