package com.wu.nearbycheckin.platform.accessibility

import com.wu.nearbycheckin.core.model.MapProvider
import com.wu.nearbycheckin.platform.maps.AccessibilityClickResult
import com.wu.nearbycheckin.platform.maps.MapUiAction
import com.wu.nearbycheckin.platform.maps.MapUiPage

enum class MapUiSuccessCleanupDisposition {
    FINISH_SUCCESS,
    CONTINUE_COMMAND,
    FAIL_COMMAND
}

object MapUiSuccessCleanupPolicy {

    // 判断当前动作是否是百度成功页的最佳努力清理。
    fun shouldExecute(
        provider: MapProvider,
        page: MapUiPage,
        action: MapUiAction?
    ): Boolean {
        return provider == MapProvider.BAIDU &&
                page == MapUiPage.SUCCESS &&
                action == MapUiAction.CLICK_DONE
    }

    // 把成功页清理结果追加到原 Profile 证据。
    fun appendEvidence(
        profileEvidence: String,
        clickResult: AccessibilityClickResult
    ): String {
        val cleanup = if (clickResult.success) {
            "cleanup=success"
        } else {
            "cleanup=failed"
        }
        return "$profileEvidence|$cleanup|" +
                "cleanupResult=${clickResult.message}"
    }

    // 根据当前命令是否已提交打卡决定清理后的命令去向。
    fun dispositionAfter(
        checkInSubmitted: Boolean,
        clickResult: AccessibilityClickResult
    ): MapUiSuccessCleanupDisposition {
        return when {
            checkInSubmitted ->
                MapUiSuccessCleanupDisposition.FINISH_SUCCESS
            clickResult.success ->
                MapUiSuccessCleanupDisposition.CONTINUE_COMMAND
            else ->
                MapUiSuccessCleanupDisposition.FAIL_COMMAND
        }
    }
}
