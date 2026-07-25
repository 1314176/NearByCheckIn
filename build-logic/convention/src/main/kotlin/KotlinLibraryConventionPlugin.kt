// @Time: 2026/07/26 02:29
// @Author: MrWu
// @File: KotlinLibraryConventionPlugin.kt
// @Software: Android Studio


package com.wu.nearbycheckin.buildlogic


import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension


class KotlinLibraryConventionPlugin : Plugin<Project> {

    // 统一纯 Kotlin 模块使用的插件和 Java 工具链。
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("org.jetbrains.kotlin.jvm")
            extensions.configure<KotlinJvmProjectExtension> {
                jvmToolchain(11)
            }
        }
    }
}
