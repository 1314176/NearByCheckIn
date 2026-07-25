package com.wu.nearbycheckin.platform.maps

data class MapUiNodeSnapshot(
    val text: String = "",
    val description: String = "",
    val viewId: String = "",
    val clickable: Boolean = false
) {

    // 返回去除首尾空白后的全部非空标签。
    fun labels(): List<String> {
        return listOf(text, description, viewId)
            .map(String::trim)
            .filter(String::isNotBlank)
    }
}

data class MapUiSnapshot(
    val packageName: String,
    val appVersion: String,
    val nodes: List<MapUiNodeSnapshot>
) {

    // 判断任一节点是否包含指定标签。
    fun hasLabel(label: String): Boolean {
        return nodes.any { node ->
            node.labels().any { value -> value.matchesUiLabel(label) }
        }
    }

    // 判断任一可点击节点是否包含指定标签。
    fun hasClickableLabel(label: String): Boolean {
        return nodes.any { node ->
            node.clickable &&
                    node.labels().any { value ->
                        value.matchesUiLabel(label)
                    }
        }
    }

    // 判断页面是否包含任一指定标签。
    fun hasAnyLabel(labels: List<String>): Boolean {
        return labels.any(::hasLabel)
    }

    // 判断页面是否包含任一可点击标签。
    fun hasAnyClickableLabel(labels: List<String>): Boolean {
        return labels.any(::hasClickableLabel)
    }

    // 判断页面文本、描述或资源 ID 是否包含指定片段。
    fun containsLabelFragment(fragment: String): Boolean {
        return nodes.any { node ->
            node.labels().any { value -> value.contains(fragment) }
        }
    }

    // 判断页面是否包含任一指定片段。
    fun containsAnyLabelFragment(
        fragments: List<String>
    ): Boolean {
        return fragments.any(::containsLabelFragment)
    }

    // 生成用于识别页面是否变化的稳定指纹。
    fun fingerprint(): String {
        return nodes.joinToString(separator = "|") { node ->
            "${node.labels().joinToString("/")}:${node.clickable}"
        }
    }
}

enum class MapUiPage {
    HOME,
    MINE,
    FOOTPRINT,
    CHECK_IN,
    SUCCESS,
    ALREADY_COMPLETED,
    BLOCKED,
    UNKNOWN
}

enum class MapUiAction {
    CLICK_MINE,
    CLICK_FOOTPRINT,
    CLICK_CHECK_IN_POINT,
    CLICK_CHECK_IN,
    CLICK_HOME,
    DISMISS_PROMPT,
    WAIT,
    BLOCK
}

data class MapUiDecision(
    val page: MapUiPage,
    val action: MapUiAction?,
    val evidence: String,
    val reason: String = ""
)

interface ProviderUiProfile {
    val packageName: String
    val supportedAppVersion: String
    val profileVersion: String

    // 根据纯 Kotlin 页面快照返回下一动作或终态。
    fun decide(snapshot: MapUiSnapshot): MapUiDecision
}

// 匹配精确标签或无障碍描述中的按钮后缀。
private fun String.matchesUiLabel(label: String): Boolean {
    return this == label ||
            startsWith("$label,") ||
            startsWith("$label，")
}
