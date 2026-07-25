package com.wu.nearbycheckin.platform.maps

internal object BoundedAncestorFinder {

    // 在当前节点及最多六层父节点中返回首个匹配项。
    fun <T : Any> findFirst(
        startNode: T,
        parentOf: (T) -> T?,
        matches: (T) -> Boolean
    ): T? {
        var currentNode: T? = startNode
        var parentLevel = 0

        while (
            currentNode != null &&
            parentLevel <= MAX_PARENT_DEPTH
        ) {
            if (matches(currentNode)) {
                return currentNode
            }

            currentNode = parentOf(currentNode)
            parentLevel += 1
        }

        return null
    }

    private const val MAX_PARENT_DEPTH = 6
}
