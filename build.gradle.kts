// 顶层构建文件，用于配置所有子项目或模块共享的构建选项。
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
