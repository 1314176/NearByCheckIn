// @Time: 2026/07/20 18:32
// @Author: MrWu
// @File: CyberBackground.kt
// @Software: Android Studio


package com.wu.nearbycheckin.presentation.components


import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp


/**
 * 赛博朋克风格的高科技 KV 背景组件。
 * 包含深色背景、动态扫描网格以及极光蓝/翡翠绿的霓虹氛围渲染。
 */
@Composable
fun CyberpunkKVBackground(modifier: Modifier = Modifier) {
    val infiniteTransition = rememberInfiniteTransition(label = "CyberBackground")

    // 扫描线的垂直位移
    val scanY by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(4000, easing = LinearEasing),
        ),
        label = "ScanLine"
    )

    // 氛围灯的呼吸效果
    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 0.7f,
        animationSpec = infiniteRepeatable(
            animation = tween(3000, easing = LinearOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "GlowAlpha"
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF0A0C10)) // 极深灰蓝色背景
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val width = size.width
            val height = size.height

            // 1. 绘制背景径向渐变（氛围灯）
            drawRect(
                brush = Brush.radialGradient(
                    colors = listOf(
                        RadarColors.NeonBlue.copy(alpha = 0.15f * glowAlpha),
                        Color.Transparent
                    ),
                    center = Offset(width * 0.8f, height * 0.2f),
                    radius = width
                )
            )
            drawRect(
                brush = Brush.radialGradient(
                    colors = listOf(
                        RadarColors.Emerald.copy(alpha = 0.1f * glowAlpha),
                        Color.Transparent
                    ),
                    center = Offset(width * 0.2f, height * 0.8f),
                    radius = width
                )
            )

            // 2. 绘制透视网格
            val gridSize = 40.dp.toPx()
            val gridColor = RadarColors.NeonBlue.copy(alpha = 0.05f)

            // 垂直线
            for (x in 0..(width / gridSize).toInt()) {
                drawLine(
                    color = gridColor,
                    start = Offset(x * gridSize, 0f),
                    end = Offset(x * gridSize, height),
                    strokeWidth = 1f
                )
            }
            // 水平线
            for (y in 0..(height / gridSize).toInt()) {
                drawLine(
                    color = gridColor,
                    start = Offset(0f, y * gridSize),
                    end = Offset(width, y * gridSize),
                    strokeWidth = 1f
                )
            }

            // 3. 绘制动态扫描线
            val scanLineY = scanY * height
            drawLine(
                brush = Brush.horizontalGradient(
                    colors = listOf(
                        Color.Transparent,
                        RadarColors.NeonBlue.copy(alpha = 0.3f),
                        Color.Transparent
                    )
                ),
                start = Offset(0f, scanLineY),
                end = Offset(width, scanLineY),
                strokeWidth = 2.dp.toPx()
            )

            // 扫描线的微弱拖尾
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        RadarColors.NeonBlue.copy(alpha = 0.05f),
                        Color.Transparent
                    ),
                    startY = scanLineY - 100.dp.toPx(),
                    endY = scanLineY
                ),
                topLeft = Offset(0f, scanLineY - 100.dp.toPx()),
                size = Size(width, 100.dp.toPx())
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
fun CyberpunkKVBackgroundPreview() {
    CyberpunkKVBackground()
}
