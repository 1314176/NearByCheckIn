// @Time: 2026/07/20 18:36
// @Author: MrWu
// @File: GlowRadarOverlayController.kt
// @Software: Android Studio


package com.wu.nearbycheckin.platform.overlay


import android.annotation.SuppressLint
import android.app.Service
import android.graphics.PixelFormat
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager

import androidx.compose.ui.platform.ComposeView
import androidx.core.content.edit
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

import com.wu.nearbycheckin.presentation.components.GlowRadarOverlay


/**
 * 封装系统悬浮窗的显示、拖动、位置持久化和生命周期。
 */
class GlowRadarOverlayController(
    private val service: Service,
    private val onVisibilityChanged: (Boolean) -> Unit
) {

    private val windowManager =
        service.getSystemService(WindowManager::class.java)
    private val preferences = service.getSharedPreferences(
        OVERLAY_PREFERENCES_NAME,
        Service.MODE_PRIVATE
    )
    private var floatingView: ComposeView? = null
    private var lifecycleOwner: OverlayLifecycleOwner? = null

    var isVisible: Boolean = preferences.getBoolean(
        OVERLAY_VISIBLE_KEY,
        true
    )
        private set

    // 恢复用户保存的显示状态。
    fun restore() {
        if (isVisible) {
            show()
        }
        onVisibilityChanged(isVisible)
    }

    // 在显示和隐藏之间切换并保存用户选择。
    fun toggle() {
        setVisible(!isVisible)
    }

    // 释放悬浮窗视图和 Compose 生命周期资源。
    fun destroy() {
        hide()
        lifecycleOwner?.stop()
        lifecycleOwner = null
    }

    // 更新并持久化悬浮窗显示状态。
    private fun setVisible(visible: Boolean) {
        isVisible = visible
        preferences.edit {
            putBoolean(OVERLAY_VISIBLE_KEY, visible)
        }
        if (visible) {
            show()
        } else {
            hide()
        }
        onVisibilityChanged(visible)
    }

    // 创建 Compose 悬浮窗并恢复上次位置。
    private fun show() {
        if (floatingView != null) {
            return
        }

        val layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = preferences.getInt(
                OVERLAY_POSITION_X_KEY,
                DEFAULT_OVERLAY_POSITION_X
            )
            y = preferences.getInt(
                OVERLAY_POSITION_Y_KEY,
                DEFAULT_OVERLAY_POSITION_Y
            )
        }
        val owner = OverlayLifecycleOwner().apply {
            start()
        }
        lifecycleOwner = owner

        val radarView = ComposeView(service).apply {
            setViewTreeLifecycleOwner(owner)
            setViewTreeViewModelStoreOwner(owner)
            setViewTreeSavedStateRegistryOwner(owner)
            setContent {
                GlowRadarOverlay(
                    onClose = {
                        setVisible(false)
                    }
                )
            }
        }
        installDragListener(radarView, layoutParams)
        windowManager.addView(radarView, layoutParams)
        floatingView = radarView
        Log.d(TAG, "雷达悬浮窗已经显示")
    }

    // 从 WindowManager 移除悬浮窗并销毁当前生命周期。
    private fun hide() {
        floatingView?.let { view ->
            windowManager.removeView(view)
        }
        floatingView = null
        lifecycleOwner?.stop()
        lifecycleOwner = null
        Log.d(TAG, "雷达悬浮窗已经隐藏，后台服务继续运行")
    }

    // 安装悬浮窗拖动监听并限制视图不越出屏幕。
    @SuppressLint("ClickableViewAccessibility")
    private fun installDragListener(
        radarView: ComposeView,
        layoutParams: WindowManager.LayoutParams
    ) {
        var initialWindowX = 0
        var initialWindowY = 0
        var initialTouchX = 0F
        var initialTouchY = 0F
        var hasMoved = false

        radarView.isClickable = true
        radarView.setOnTouchListener { touchedView, motionEvent ->
            when (motionEvent.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    initialWindowX = layoutParams.x
                    initialWindowY = layoutParams.y
                    initialTouchX = motionEvent.rawX
                    initialTouchY = motionEvent.rawY
                    hasMoved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val movedX =
                        (motionEvent.rawX - initialTouchX).toInt()
                    val movedY =
                        (motionEvent.rawY - initialTouchY).toInt()
                    if (
                        kotlin.math.abs(movedX) >
                        DRAG_THRESHOLD_PIXELS ||
                        kotlin.math.abs(movedY) >
                        DRAG_THRESHOLD_PIXELS
                    ) {
                        hasMoved = true
                    }
                    layoutParams.x = (initialWindowX + movedX)
                        .coerceIn(
                            minimumValue = 0,
                            maximumValue = maximumX(touchedView)
                        )
                    layoutParams.y = (initialWindowY + movedY)
                        .coerceIn(
                            minimumValue = 0,
                            maximumValue = maximumY(touchedView)
                        )
                    windowManager.updateViewLayout(
                        touchedView,
                        layoutParams
                    )
                    true
                }
                MotionEvent.ACTION_UP -> {
                    savePosition(layoutParams.x, layoutParams.y)
                    if (!hasMoved) {
                        touchedView.performClick()
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    savePosition(layoutParams.x, layoutParams.y)
                    true
                }
                else -> false
            }
        }
    }

    // 计算悬浮窗横向可移动的最大像素位置。
    private fun maximumX(view: View): Int {
        return (
                service.resources.displayMetrics.widthPixels -
                        measuredWidth(view)
                ).coerceAtLeast(0)
    }

    // 计算悬浮窗纵向可移动的最大像素位置。
    private fun maximumY(view: View): Int {
        return (
                service.resources.displayMetrics.heightPixels -
                        measuredHeight(view)
                ).coerceAtLeast(0)
    }

    // 返回可靠的悬浮窗像素宽度。
    private fun measuredWidth(view: View): Int {
        return view.width.takeIf { width ->
            width > 0
        } ?: fallbackSizePixels()
    }

    // 返回可靠的悬浮窗像素高度。
    private fun measuredHeight(view: View): Int {
        return view.height.takeIf { height ->
            height > 0
        } ?: fallbackSizePixels()
    }

    // 将默认 dp 尺寸转换为像素。
    private fun fallbackSizePixels(): Int {
        return (
                OVERLAY_FALLBACK_SIZE_DP *
                        service.resources.displayMetrics.density
                ).toInt()
    }

    // 保存悬浮窗当前位置。
    private fun savePosition(positionX: Int, positionY: Int) {
        preferences.edit {
            putInt(OVERLAY_POSITION_X_KEY, positionX)
            putInt(OVERLAY_POSITION_Y_KEY, positionY)
        }
    }

    private companion object {
        const val TAG = "GlowRadarOverlay"
        const val OVERLAY_PREFERENCES_NAME = "overlay_preferences"
        const val OVERLAY_VISIBLE_KEY = "overlay_visible"
        const val OVERLAY_POSITION_X_KEY = "overlay_position_x"
        const val OVERLAY_POSITION_Y_KEY = "overlay_position_y"
        const val DEFAULT_OVERLAY_POSITION_X = 24
        const val DEFAULT_OVERLAY_POSITION_Y = 180
        const val OVERLAY_FALLBACK_SIZE_DP = 180
        const val DRAG_THRESHOLD_PIXELS = 8
    }
}

/**
 * 为 Service 中的 ComposeView 提供完整生命周期。
 */
private class OverlayLifecycleOwner :
    LifecycleOwner,
    ViewModelStoreOwner,
    SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val store = ViewModelStore()
    private val controller = SavedStateRegistryController.create(this)

    init {
        controller.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(
            Lifecycle.Event.ON_CREATE
        )
    }

    override val lifecycle: Lifecycle
        get() = lifecycleRegistry
    override val viewModelStore: ViewModelStore
        get() = store
    override val savedStateRegistry: SavedStateRegistry
        get() = controller.savedStateRegistry

    // 激活悬浮 Compose 生命周期。
    fun start() {
        lifecycleRegistry.handleLifecycleEvent(
            Lifecycle.Event.ON_START
        )
        lifecycleRegistry.handleLifecycleEvent(
            Lifecycle.Event.ON_RESUME
        )
    }

    // 销毁悬浮 Compose 生命周期与 ViewModelStore。
    fun stop() {
        lifecycleRegistry.handleLifecycleEvent(
            Lifecycle.Event.ON_PAUSE
        )
        lifecycleRegistry.handleLifecycleEvent(
            Lifecycle.Event.ON_STOP
        )
        lifecycleRegistry.handleLifecycleEvent(
            Lifecycle.Event.ON_DESTROY
        )
        store.clear()
    }
}
