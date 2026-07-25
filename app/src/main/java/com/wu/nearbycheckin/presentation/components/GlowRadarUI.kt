// @Time: 2026/07/20 18:32
// @Author: MrWu
// @File: GlowRadarUI.kt
// @Software: Android Studio


package com.wu.nearbycheckin.presentation.components


import kotlin.math.cos
import kotlin.math.sin

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp


// 雷达自定义色彩常量配置
object RadarColors {
    val GlassBg = Color(0xDD0A0C10)
    val NeonBlue = Color(0xFF00E5FF)
    val Emerald = Color(0xFF00E676)
    val BorderBlue = Color(0x3300E5FF)
    val TextGray = Color(0xFF94A3B8)
}

// 绘制极光蓝赛博朋克风格的手绘设置齿轮图标
@Composable
fun CyberSettingsIcon(modifier: Modifier = Modifier, tint: Color = Color.White) {
    Canvas(modifier = modifier.size(18.dp)) {
        val radius = size.minDimension / 2
        drawCircle(color = tint, radius = radius * 0.4f, style = Stroke(width = 3f))
        for (i in 0 until 8) {
            val angle = (i * 45) * (Math.PI / 180)
            val startX = (center.x + radius * 0.4f * cos(angle)).toFloat()
            val startY = (center.y + radius * 0.4f * sin(angle)).toFloat()
            val endX = (center.x + radius * 0.75f * cos(angle)).toFloat()
            val endY = (center.y + radius * 0.75f * sin(angle)).toFloat()
            drawLine(color = tint, start = Offset(startX, startY), end = Offset(endX, endY), strokeWidth = 4f)
        }
    }
}

// 绘制未来感雷达定位三角指针图标
@Composable
fun CyberLocationIcon(modifier: Modifier = Modifier, tint: Color = RadarColors.NeonBlue) {
    Canvas(modifier = modifier.size(14.dp)) {
        val width = size.width
        val height = size.height
        val path = Path().apply {
            moveTo(width / 2f, 0f)
            lineTo(width, height * 0.8f)
            lineTo(width / 2f, height * 0.55f)
            lineTo(0f, height * 0.8f)
            close()
        }
        drawPath(path = path, color = tint)
    }
}

// 绘制不断旋转的极光蓝雷达扫描动效
@Composable
fun RadarScanner(modifier: Modifier = Modifier) {
    val infiniteTransition = rememberInfiniteTransition(label = "Radar")
    val angle by infiniteTransition.animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(3000, easing = LinearEasing)), label = "Rotation"
    )

    Canvas(modifier = modifier.size(100.dp)) {
        val center = Offset(size.width / 2, size.height / 2)
        val radius = size.minDimension / 2

        for (i in 1..3) {
            drawCircle(
                color = RadarColors.NeonBlue.copy(alpha = 0.1f * i),
                radius = radius * (i / 3f),
                style = Stroke(width = 1.dp.toPx())
            )
        }

        drawArc(
            brush = Brush.sweepGradient(
                0f to Color.Transparent,
                1f to RadarColors.NeonBlue,
                center = center
            ),
            startAngle = angle - 60f,
            sweepAngle = 60f,
            useCenter = true,
            alpha = 0.5f
        )
    }
}

// 自动打卡成功日志条目布局
@Composable
fun LogItem(name: String, time: String, distance: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(modifier = Modifier.size(6.dp).background(RadarColors.Emerald, CircleShape))
        Spacer(modifier = Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = name, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Text(text = "$time | 距离: $distance m", color = RadarColors.TextGray, fontSize = 10.sp)
        }
        CyberLocationIcon(modifier = Modifier.size(14.dp), tint = RadarColors.NeonBlue)
    }
}

// 展开状态的完整控制面板仪表盘布局
@Composable
fun MainDashboard(
    onMinimize: () -> Unit,
    onClose: () -> Unit
) {
    val logs = listOf("万象城购物中心", "中环高架路段", "徐家汇地铁站")

    Box(
        modifier = Modifier
            .width(300.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(RadarColors.GlassBg)
            .border(1.dp, RadarColors.BorderBlue, RoundedCornerShape(24.dp))
            .padding(16.dp)
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadarScanner(Modifier.size(40.dp))
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("微光雷达", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Text("巡航打卡模式：运行中", color = RadarColors.Emerald, fontSize = 11.sp)
                }
                CyberSettingsIcon(
                    modifier = Modifier.size(18.dp).clickable { },
                    tint = Color.White
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp), color = Color.White.copy(alpha = 0.1f))

            Text("实时检索日志", color = RadarColors.TextGray, fontSize = 11.sp, modifier = Modifier.padding(bottom = 8.dp))
            LazyColumn(modifier = Modifier.heightIn(max = 120.dp)) {
                items(logs) { log ->
                    LogItem(log, "14:20", "15")
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onMinimize,
                    modifier = Modifier.weight(1f).height(40.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = RadarColors.NeonBlue.copy(alpha = 0.2f)),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("收起", color = RadarColors.NeonBlue, fontSize = 12.sp)
                }

                Button(
                    onClick = onClose,
                    modifier = Modifier.weight(1f).height(40.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = 0.08f)),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("隐藏", color = Color.White, fontSize = 12.sp)
                }
            }
        }
    }
}

// 收起状态下的微型边缘悬浮胶囊布局
@Composable
fun MinimizedCapsule(onExpand: () -> Unit) {
    val infiniteTransition = rememberInfiniteTransition(label = "Glow")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.4f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1000), repeatMode = RepeatMode.Reverse), label = "Alpha"
    )

    Row(
        modifier = Modifier
            .wrapContentWidth()
            .height(40.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(RadarColors.GlassBg)
            .border(1.dp, RadarColors.NeonBlue.copy(alpha = alpha), RoundedCornerShape(20.dp))
            .clickable { onExpand() }
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(modifier = Modifier.size(8.dp).background(RadarColors.Emerald, CircleShape))
        Spacer(modifier = Modifier.width(8.dp))
        Text("巡航中...", color = Color.White, fontSize = 12.sp)
    }
}

// 悬浮窗的顶级Compose聚合容器入口
@Composable
fun GlowRadarOverlay(
    onClose: () -> Unit = {}
) {
    var isExpanded by remember { mutableStateOf(false) }

    Box(modifier = Modifier.padding(16.dp)) {
        if (isExpanded) {
            MainDashboard(
                onMinimize = {
                    isExpanded = false
                },
                onClose = onClose
            )
        } else {
            MinimizedCapsule(onExpand = { isExpanded = true })
        }
    }
}
