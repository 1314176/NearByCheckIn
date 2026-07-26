// @Time: 2026/07/26 04:29
// @Author: MrWu
// @File: BoundedAncestorFinderTest.kt
// @Software: Android Studio


package com.wu.nearbycheckin.platform.maps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BoundedAncestorFinderTest {

    // 验证祖先查找允许命中第六层父节点。
    @Test
    fun findsMatchingAncestorAtSixthParentLevel() {
        val matchingAncestor = TestNode(matches = true)
        val startNode = descendantOf(
            ancestor = matchingAncestor,
            parentLevel = 6
        )

        val result = BoundedAncestorFinder.findFirst(
            startNode = startNode,
            parentOf = TestNode::parent,
            matches = TestNode::matches
        )

        assertEquals(matchingAncestor, result)
    }

    // 验证祖先查找拒绝命中第七层父节点。
    @Test
    fun ignoresMatchingAncestorAtSeventhParentLevel() {
        val matchingAncestor = TestNode(matches = true)
        val startNode = descendantOf(
            ancestor = matchingAncestor,
            parentLevel = 7
        )

        val result = BoundedAncestorFinder.findFirst(
            startNode = startNode,
            parentOf = TestNode::parent,
            matches = TestNode::matches
        )

        assertNull(result)
    }

    // 创建与指定祖先相隔固定父级层数的测试节点。
    private fun descendantOf(
        ancestor: TestNode,
        parentLevel: Int
    ): TestNode {
        var descendant = ancestor
        repeat(parentLevel) {
            descendant = TestNode(parent = descendant)
        }
        return descendant
    }

    private data class TestNode(
        val parent: TestNode? = null,
        val matches: Boolean = false
    )
}
