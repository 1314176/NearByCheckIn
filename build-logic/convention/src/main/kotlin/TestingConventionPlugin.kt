// @Time: 2026/07/26 02:29
// @Author: MrWu
// @File: TestingConventionPlugin.kt
// @Software: Android Studio


package com.wu.nearbycheckin.buildlogic


import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.withType


class TestingConventionPlugin : Plugin<Project> {

    // 统一 JVM 测试依赖、执行引擎和失败日志。
    override fun apply(target: Project) {
        with(target) {
            val libs = extensions
                .getByType<VersionCatalogsExtension>()
                .named("libs")
            dependencies.add(
                "testImplementation",
                libs.findLibrary("junit").get()
            )
            tasks.withType<Test>().configureEach {
                useJUnit()
                testLogging {
                    events("failed", "skipped")
                }
            }
        }
    }
}
