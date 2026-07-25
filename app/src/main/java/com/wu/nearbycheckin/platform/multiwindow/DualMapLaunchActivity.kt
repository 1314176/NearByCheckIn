// @Time: 2026/07/20 18:32
// @Author: MrWu
// @File: DualMapLaunchActivity.kt
// @Software: Android Studio


@file:Suppress("SpellCheckingInspection")


package com.wu.nearbycheckin.platform.multiwindow


import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log


/**
 * 作为前台中转页启动地图应用，减少无障碍服务后台直接打开外部应用导致的重复确认。
 */
@SuppressLint("CustomSplashScreen")
class DualMapLaunchActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        launchRequestedMap()
        finish()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        launchRequestedMap()
        finish()
    }

    /**
     * 按启动参数打开指定地图应用。
     */
    private fun launchRequestedMap() {
        val targetPackageName = intent.getStringExtra(
            EXTRA_TARGET_PACKAGE_NAME
        ).orEmpty()
        val launchAdjacent = intent.getBooleanExtra(
            EXTRA_LAUNCH_ADJACENT,
            false
        )

        if (targetPackageName.isBlank()) {
            return
        }

        launchMapApplication(
            packageName = targetPackageName,
            launchAdjacent = launchAdjacent
        )
    }

    /**
     * 使用系统启动入口打开指定地图应用。
     */
    private fun launchMapApplication(
        packageName: String,
        launchAdjacent: Boolean
    ): Boolean {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
            ?: Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
                setPackage(packageName)
            }
        launchIntent.addSplitLaunchFlagsIfNeeded(launchAdjacent)

        return try {
            startActivity(launchIntent)
            true
        } catch (exception: RuntimeException) {
            Log.w(
                TAG,
                "地图应用启动失败：$packageName",
                exception
            )
            false
        }
    }

    /**
     * 需要分屏相邻窗口时，将系统分屏启动标记传递给真正的地图应用。
     */
    private fun Intent.addSplitLaunchFlagsIfNeeded(launchAdjacent: Boolean) {
        if (!launchAdjacent) {
            return
        }

        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        addFlags(Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT)
        addFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
    }

    companion object {
        private const val TAG = "DualMapLaunchActivity"
        private const val EXTRA_TARGET_PACKAGE_NAME =
            "com.wu.nearbycheckin.extra.TARGET_PACKAGE_NAME"
        private const val EXTRA_LAUNCH_ADJACENT =
            "com.wu.nearbycheckin.extra.LAUNCH_ADJACENT"

        /**
         * 创建地图启动中转页Intent，分屏场景下可请求进入相邻窗口。
         */
        fun createIntent(
            context: Context,
            targetPackageName: String,
            launchAdjacent: Boolean
        ): Intent {
            return Intent(
                context,
                DualMapLaunchActivity::class.java
            ).apply {
                putExtra(
                    EXTRA_TARGET_PACKAGE_NAME,
                    targetPackageName
                )
                putExtra(
                    EXTRA_LAUNCH_ADJACENT,
                    launchAdjacent
                )
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
            }
        }
    }
}
