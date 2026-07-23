// @Time: 2026/07/20 13:00
// @Author: MrWu
// @File: NearbyCheckInApplication.kt
// @Software: Codex

package com.wu.nearbycheckin.app

import android.app.Application

/**
 * 持有整个进程唯一的手动依赖注入容器。
 */
class NearbyCheckInApplication : Application() {

    val appContainer: AppContainer by lazy {
        AppContainer(applicationContext)
    }
}
