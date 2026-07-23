// @Time: 2026/06/29 05:06
// @Author: MrWu
// @File: BaiduMapSdkManager.kt
// @Software: Android Studio


@file:Suppress("SpellCheckingInspection")

package com.wu.nearbycheckin.platform.cruise


import android.content.Context
import android.util.Log

import com.baidu.mapapi.CoordType
import com.baidu.mapapi.SDKInitializer

/**
 * 统一管理百度地图SDK的隐私声明和初始化状态。
 */
object BaiduMapSdkManager {

    /**
     * 在用户同意隐私政策后初始化百度地图SDK，并注册鉴权结果监听。
     *
     * @param context 当前应用上下文。
     * @return 成功调用SDK初始化流程时返回true，出现初始化异常时返回false。
     */
    @Synchronized
    fun initializeAfterPrivacyConsent(context: Context): Boolean {
        val applicationContext = context.applicationContext

        return try {
            SDKInitializer.setAgreePrivacy(
                applicationContext,
                true
            )

            SDKInitializer.setCoordType(
                CoordType.BD09LL
            )

            lastInitializeTimeMillis = System.currentTimeMillis()

            if (!authListenerRegistered) {
                SDKInitializer.addAuthResultListener { status, message ->
                    authCompleted = true
                    authPassed = status == AUTH_SUCCESS_STATUS
                    authStatus = status
                    authMessage = message.orEmpty()

                    if (authPassed) {
                        Log.d(
                            TAG,
                            "百度地图鉴权成功：status=$status，message=$authMessage"
                        )
                    } else {
                        Log.e(
                            TAG,
                            "百度地图鉴权失败：status=$status，message=$authMessage"
                        )
                    }

                    notifyAuthStateListeners()
                }

                authListenerRegistered = true
            }

            if (!SDKInitializer.isInitialized()) {
                SDKInitializer.initialize(applicationContext)
            }

            Log.d(
                TAG,
                "百度地图SDK初始化调用完成：" +
                        "initialized=${SDKInitializer.isInitialized()}，" +
                        "https=${SDKInitializer.isHttpsEnable()}"
            )

            true
        } catch (exception: RuntimeException) {
            authCompleted = true
            authPassed = false
            authMessage = exception.message.orEmpty()

            Log.e(
                TAG,
                "百度地图SDK初始化失败",
                exception
            )

            false
        }
    }

    /**
     * 判断百度地图鉴权流程是否已经返回结果。
     */
    fun isAuthCompleted(): Boolean {
        return authCompleted
    }

    /**
     * 判断百度地图AK鉴权是否成功。
     */
    fun isAuthPassed(): Boolean {
        return authPassed
    }

    /**
     * 获取百度地图鉴权状态码和详细说明。
     */
    fun getAuthDescription(): String {
        return "status=$authStatus，message=$authMessage"
    }

    /**
     * 判断是否可以在鉴权回调长时间未返回时先尝试发起百度检索请求。
     */
    fun canTryRequestWithoutAuthResult(): Boolean {
        return !authCompleted &&
                SDKInitializer.isInitialized() &&
                System.currentTimeMillis() - lastInitializeTimeMillis >=
                AUTH_RESULT_WAIT_TIMEOUT_MILLIS
    }

    /**
     * 注册百度地图鉴权状态监听，已完成鉴权时会立即回调当前状态。
     *
     * @param listener 接收鉴权是否通过和状态描述的回调。
     */
    @Synchronized
    fun addAuthStateListener(
        listener: (Boolean, String) -> Unit
    ) {
        authStateListeners.add(listener)

        if (authCompleted) {
            listener(
                authPassed,
                getAuthDescription()
            )
        }
    }

    /**
     * 移除百度地图鉴权状态监听，避免服务销毁后继续持有回调。
     *
     * @param listener 需要移除的鉴权状态回调。
     */
    @Synchronized
    fun removeAuthStateListener(
        listener: (Boolean, String) -> Unit
    ) {
        authStateListeners.remove(listener)
    }

    /**
     * 将最新鉴权结果分发给所有已注册监听。
     */
    @Synchronized
    private fun notifyAuthStateListeners() {
        authStateListeners.forEach { listener ->
            listener(
                authPassed,
                getAuthDescription()
            )
        }
    }

    private const val TAG = "BaiduMapSdkManager"
    private const val AUTH_SUCCESS_STATUS = 0
    @Volatile
    private var authCompleted = false
    @Volatile
    private var authPassed = false
    @Volatile
    private var authStatus = Int.MIN_VALUE
    @Volatile
    private var authMessage = "尚未收到百度地图鉴权结果"
    @Volatile
    private var lastInitializeTimeMillis = 0L
    private val authStateListeners =
        mutableSetOf<(Boolean, String) -> Unit>()
    private var authListenerRegistered = false
    private const val AUTH_RESULT_WAIT_TIMEOUT_MILLIS = 5_000L
}
