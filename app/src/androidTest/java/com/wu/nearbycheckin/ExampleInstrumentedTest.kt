package com.wu.nearbycheckin

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4

import org.junit.Test
import org.junit.runner.RunWith

import org.junit.Assert.*

/**
 * 在 Android 设备上执行的仪器测试。
 *
 * 参考 [测试文档](http://d.android.com/tools/testing)。
 */
@RunWith(AndroidJUnit4::class)
class ExampleInstrumentedTest {
    // 验证测试环境获取到的应用包名是否符合预期。
    @Test
    fun useAppContext() {
        // 获取被测试应用的上下文。
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("com.wu.nearbycheckin", appContext.packageName)
    }
}
