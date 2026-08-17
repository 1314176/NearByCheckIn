// @Time: 2026/07/20 18:30
// @Author: MrWu
// @File: MainScreen.kt
// @Software: Android Studio


package com.wu.nearbycheckin.presentation.main


import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

import com.wu.nearbycheckin.presentation.components.RadarColors

import com.wu.nearbycheckin.core.domain.usecase.RecordQueryScope
import com.wu.nearbycheckin.core.model.AccessibilityDiagnostics
import com.wu.nearbycheckin.core.model.AutomationLogEntry
import com.wu.nearbycheckin.core.model.AutomationLogLevel
import com.wu.nearbycheckin.core.model.CheckInRecord
import com.wu.nearbycheckin.core.model.MapProvider
import com.wu.nearbycheckin.ui.theme.NearbyCheckInTheme


private val checkInTimeFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("HH:mm:ss")
private val logTimeFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("MM-dd HH:mm:ss")

/**
 * 纯渲染主界面状态并把交互转换为 MainUiEvent。
 * 已重构为卡片化 Dashboard 风格，提升操作逻辑感。
 */
@Composable
fun MainScreen(
    state: MainUiState,
    onEvent: (MainUiEvent) -> Unit,
    modifier: Modifier = Modifier
) {
    PrivacyDialog(state, onEvent)
    RecordsDialog(state, onEvent)
    RuntimeLogsDialog(state, onEvent)
    ClearRecordsDialog(state, onEvent)
    ClearBaiduCompletionDialog(state, onEvent)

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(32.dp))

        Text(
            text = "NearByCheckIn",
            style = MaterialTheme.typography.headlineSmall,
            color = RadarColors.NeonBlue,
            letterSpacing = 4.sp,
            fontWeight = FontWeight.Black
        )
        Text(
            text = "附近打卡自动化系统",
            style = MaterialTheme.typography.labelSmall,
            color = RadarColors.TextGray
        )

        Spacer(modifier = Modifier.height(32.dp))

        // 1. 核心状态卡片
        StatusSummaryCard(state)

        // 2. 当前事务监控 (仅在运行时或有事务时显示)
        if (state.isCruiseRunning || state.activePlaceName.isNotBlank()) {
            Spacer(modifier = Modifier.height(16.dp))
            ActiveTaskCard(state, onEvent)
        }

        Spacer(modifier = Modifier.height(24.dp))

        // 核心控制按钮
        MainActionButton(state, onEvent)

        Spacer(modifier = Modifier.height(24.dp))

        // 3. 控制面板 (初始化与工具)
        ControlPanelCard(onEvent)

        Spacer(modifier = Modifier.height(16.dp))

        // 4. 数据管理
        DataManagementCard(onEvent)

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "当前软件版本：v3.0.2     auther：Mr.Wu",
            modifier = Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
            color = RadarColors.TextGray,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(48.dp))
    }
}

@Composable
private fun StatusSummaryCard(state: MainUiState) {
    CyberCard(title = "运行状态概览") {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = if (state.isCruiseRunning) "RUNNING" else "STOPPED",
                    style = MaterialTheme.typography.titleLarge,
                    color = if (state.isCruiseRunning) RadarColors.Emerald else Color.White,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = if (state.isCruiseRunning) "自动化检索中..." else "待机中",
                    style = MaterialTheme.typography.bodySmall,
                    color = RadarColors.TextGray
                )
            }

            // 地图安装指示器
            Row {
                MapIndicator(label = "百度", installed = state.baiduInstalled)
                Spacer(modifier = Modifier.width(8.dp))
                MapIndicator(label = "高德", installed = state.amapInstalled)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        HorizontalDivider(color = RadarColors.BorderBlue)
        Spacer(modifier = Modifier.height(12.dp))

        DiagnosticsStatusText(state.diagnostics)
    }
}

@Composable
private fun ActiveTaskCard(state: MainUiState, onEvent: (MainUiEvent) -> Unit) {
    CyberCard(title = "实时任务监控") {
        if (state.activePlaceName.isNotBlank()) {
            Text(
                text = state.activePlaceName,
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White
            )
            Text(
                text = "当前状态: " +
                        state.baiduProviderState.ifBlank {
                            state.transactionStatus
                        },
                style = MaterialTheme.typography.bodySmall,
                color = RadarColors.NeonBlue
            )

            Spacer(modifier = Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                TaskStatusChip("百度", state.baiduProviderState)
                Spacer(modifier = Modifier.width(8.dp))
                TaskStatusChip("高德", state.amapStatus)
                Spacer(modifier = Modifier.width(8.dp))
                TaskStatusChip("队列", "${state.queueSize}")
            }
        } else {
            Text(
                text = "等待目标分发...",
                style = MaterialTheme.typography.bodyMedium,
                color = RadarColors.TextGray
            )
        }

        if (state.automationStatus.isNotBlank()) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "系统提示: ${state.automationStatus}",
                style = MaterialTheme.typography.labelSmall,
                color = RadarColors.TextGray
            )
        }

        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "百度今日确认成功 ${state.baiduDailyVerifiedSuccessCount} | " +
                    "累计确认 ${state.baiduTotalVerifiedSuccessCount}",
            style = MaterialTheme.typography.labelSmall,
            color = RadarColors.NeonBlue
        )
        if (state.baiduObservedCheckInCount > 0) {
            Text(
                text = "百度界面最近统计 ${state.baiduObservedCheckInCount} 个地点",
                style = MaterialTheme.typography.labelSmall,
                color = RadarColors.TextGray
            )
        }
        if (state.baiduLastVerifiedSuccessEvidence.isNotBlank()) {
            Text(
                text = "最近成功证据: " +
                        state.baiduLastVerifiedSuccessEvidence.take(160),
                style = MaterialTheme.typography.labelSmall,
                color = RadarColors.TextGray
            )
        }
        if (state.baiduDailyQuotaBlocked) {
            Text(
                text = "百度今日额度已用尽，将于 " +
                        formatLogTime(state.baiduDailyQuotaResetAtMillis) +
                        " 自动恢复",
                style = MaterialTheme.typography.labelSmall,
                color = Color.Yellow
            )
        }

        if (state.pluginPage.isNotBlank()) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "百度候选 ${state.baiduCandidateCount} | " +
                        "永久完成 ${state.baiduCompletedCount}",
                style = MaterialTheme.typography.labelSmall,
                color = RadarColors.NeonBlue
            )
            Text(
                text = "插件 ${state.pluginPage} / ${state.pluginAction}",
                style = MaterialTheme.typography.labelSmall,
                color = RadarColors.TextGray
            )
            if (state.pluginEvidence.isNotBlank()) {
                Text(
                    text = "证据: ${state.pluginEvidence.take(160)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = RadarColors.TextGray
                )
            }
        }

        if (
            (
                    state.transactionStatus == "BLOCKED" ||
                            state.baiduProviderState == "BLOCKED"
                    ) &&
            !state.baiduDailyQuotaBlocked
        ) {
            Spacer(modifier = Modifier.height(12.dp))
            Button(
                onClick = { onEvent(MainUiEvent.ResumeBlockedTransaction) },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text("人工处理完成，继续事务")
            }
        }
    }
}

@Composable
private fun ControlPanelCard(onEvent: (MainUiEvent) -> Unit) {
    var menuExpanded by remember { mutableStateOf(false) }

    CyberCard(title = "控制与初始化") {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { onEvent(MainUiEvent.InitializeBothMaps) },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = RadarColors.BorderBlue,
                    contentColor = Color.White
                ),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text("一键链式授权", style = MaterialTheme.typography.labelSmall)
            }

            Box(modifier = Modifier.weight(1f)) {
                Button(
                    onClick = { menuExpanded = true },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.Transparent,
                        contentColor = Color.White
                    ),
                    border = BorderStroke(1.dp, RadarColors.BorderBlue),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("单独授权", style = MaterialTheme.typography.labelSmall)
                }

                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                    modifier = Modifier.background(RadarColors.GlassBg)
                ) {
                    DropdownMenuItem(
                        text = { Text("仅初始化百度", color = Color.White) },
                        onClick = {
                            menuExpanded = false
                            onEvent(MainUiEvent.InitializeBaiduMap)
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("仅初始化高德", color = Color.White) },
                        onClick = {
                            menuExpanded = false
                            onEvent(MainUiEvent.InitializeAmap)
                        }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SecondaryActionButton("检测地图安装", Modifier.weight(1f)) {
                onEvent(MainUiEvent.RefreshMapInstallation)
            }
            SecondaryActionButton("打开诊断设置", Modifier.weight(1f)) {
                onEvent(MainUiEvent.OpenAccessibilitySettings)
            }
        }
    }
}

@Composable
private fun DataManagementCard(onEvent: (MainUiEvent) -> Unit) {
    CyberCard(title = "数据与记录") {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SecondaryActionButton("今日记录", Modifier.weight(1f)) {
                onEvent(MainUiEvent.QueryRecords(RecordQueryScope.TODAY))
            }
            SecondaryActionButton("查看全部", Modifier.weight(1f)) {
                onEvent(MainUiEvent.QueryRecords(RecordQueryScope.ALL))
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SecondaryActionButton("百度记录", Modifier.weight(1f)) {
                onEvent(
                    MainUiEvent.QueryProviderRecords(MapProvider.BAIDU)
                )
            }
            SecondaryActionButton("高德记录", Modifier.weight(1f)) {
                onEvent(
                    MainUiEvent.QueryProviderRecords(MapProvider.AMAP)
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        SecondaryActionButton(
            text = "运行日志",
            modifier = Modifier.fillMaxWidth()
        ) {
            onEvent(MainUiEvent.ShowRuntimeLogs)
        }
        Spacer(modifier = Modifier.height(8.dp))
        TextButton(
            onClick = {
                onEvent(MainUiEvent.RequestClearBaiduCompletion)
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                "清空百度完成键",
                color = Color.Yellow.copy(alpha = 0.8f),
                style = MaterialTheme.typography.labelSmall
            )
        }
        TextButton(
            onClick = { onEvent(MainUiEvent.RequestClearAll) },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("清空本地记录与事务去重", color = Color.Red.copy(alpha = 0.7f), style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun MainActionButton(state: MainUiState, onEvent: (MainUiEvent) -> Unit) {
    val isRunning = state.isCruiseRunning
    Button(
        onClick = {
            onEvent(if (isRunning) MainUiEvent.StopCruise else MainUiEvent.StartCruise)
        },
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (isRunning) Color(0xFFFF5252) else RadarColors.NeonBlue
        ),
        shape = RoundedCornerShape(28.dp),
        elevation = ButtonDefaults.buttonElevation(defaultElevation = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = if (isRunning) Icons.Default.PlayArrow else Icons.Default.PlayArrow,
                contentDescription = null,
                tint = if (isRunning) Color.White else Color.Black
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = if (isRunning) "停止自动化任务" else "开始自动化打卡",
                style = MaterialTheme.typography.titleMedium,
                color = if (isRunning) Color.White else Color.Black,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
fun CyberCard(
    modifier: Modifier = Modifier,
    title: String? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = RadarColors.GlassBg.copy(alpha = 0.7f)
        ),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, RadarColors.BorderBlue)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            if (title != null) {
                Text(
                    text = title.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = RadarColors.NeonBlue,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
                Spacer(modifier = Modifier.height(12.dp))
            }
            content()
        }
    }
}

@Composable
private fun MapIndicator(label: String, installed: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(
                    if (installed) RadarColors.Emerald else Color.Gray,
                    RoundedCornerShape(4.dp)
                )
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(text = label, style = MaterialTheme.typography.labelSmall, color = Color.White)
    }
}

@Composable
private fun TaskStatusChip(label: String, status: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = label, style = MaterialTheme.typography.labelSmall, color = RadarColors.TextGray)
        Text(
            text = status.ifBlank { "WAIT" },
            style = MaterialTheme.typography.bodySmall,
            color = Color.White,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun SecondaryActionButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        modifier = modifier,
        colors = ButtonDefaults.textButtonColors(contentColor = Color.White),
        border = BorderStroke(0.5.dp, RadarColors.BorderBlue.copy(alpha = 0.5f)),
        shape = RoundedCornerShape(8.dp)
    ) {
        Text(text = text, style = MaterialTheme.typography.labelSmall)
    }
}

/**
 * 渲染 Repository 快照中的无障碍诊断。
 */
@Composable
private fun DiagnosticsStatusText(
    diagnostics: AccessibilityDiagnostics
) {
    if (diagnostics.lastUpdatedMillis <= 0L) {
        Text(
            text = "诊断系统：尚未收到窗口状态",
            style = MaterialTheme.typography.bodySmall,
            color = RadarColors.TextGray
        )
        return
    }

    val stale = System.currentTimeMillis() -
            diagnostics.lastUpdatedMillis >
            DIAGNOSTICS_STALE_MILLIS

    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "系统就绪：",
                style = MaterialTheme.typography.bodySmall,
                color = RadarColors.TextGray
            )
            Text(
                text = if (diagnostics.baiduReady && diagnostics.amapReady) "完全就绪" else "部分就绪",
                style = MaterialTheme.typography.bodySmall,
                color = if (diagnostics.baiduReady && diagnostics.amapReady) RadarColors.Emerald else Color.Yellow,
                fontWeight = FontWeight.Bold
            )
            if (stale) {
                Spacer(modifier = Modifier.width(8.dp))
                Text(text = "[已过期]", style = MaterialTheme.typography.labelSmall, color = Color.Red)
            }
        }
        Text(
            text = "节点: ${diagnostics.targetNodeCount} | 更新: ${formatCheckInTime(diagnostics.lastUpdatedMillis)}",
            style = MaterialTheme.typography.labelSmall,
            color = RadarColors.TextGray
        )
    }
}

/**
 * 根据状态按需显示隐私确认对话框。
 */
@Composable
private fun PrivacyDialog(
    state: MainUiState,
    onEvent: (MainUiEvent) -> Unit
) {
    if (!state.showPrivacyDialog) {
        return
    }

    AlertDialog(
        onDismissRequest = {
            onEvent(MainUiEvent.DismissPrivacy)
        },
        title = {
            Text(text = "隐私提示")
        },
        text = {
            Text(
                text = "附近打卡功能需要使用百度地图SDK，" +
                        "并将当前坐标通过网络用于查询周边地点。" +
                        "是否同意并继续使用该功能？"
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onEvent(MainUiEvent.AcceptPrivacy)
                }
            ) {
                Text(text = "同意并继续")
            }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    onEvent(MainUiEvent.DismissPrivacy)
                }
            ) {
                Text(text = "暂不同意")
            }
        }
    )
}

/**
 * 根据状态显示本地记录查询结果。
 */
@Composable
private fun RecordsDialog(
    state: MainUiState,
    onEvent: (MainUiEvent) -> Unit
) {
    if (!state.showRecordsDialog) {
        return
    }

    AlertDialog(
        onDismissRequest = {
            onEvent(MainUiEvent.DismissRecords)
        },
        title = {
            Text(text = state.recordsDialogTitle)
        },
        text = {
            RecordsContent(state.displayedRecords)
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onEvent(MainUiEvent.DismissRecords)
                }
            ) {
                Text(text = "关闭")
            }
        }
    )
}

/**
 * 渲染记录对话框中的滚动内容。
 */
@Composable
private fun RecordsContent(records: List<CheckInRecord>) {
    if (records.isEmpty()) {
        Text(text = "暂无本地自动打卡记录。")
        return
    }

    Column(
        modifier = Modifier
            .heightIn(max = 360.dp)
            .verticalScroll(rememberScrollState())
    ) {
        records.forEachIndexed { index, record ->
            Text(
                text = "${index + 1}. ${record.poiName}",
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "时间：${formatCheckInTime(
                    record.checkInTimeMillis
                )}",
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = "距离：${record.distanceMeters}米",
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = "停留：${record.stayDurationMillis / 1_000L}秒",
                style = MaterialTheme.typography.bodyMedium
            )
            if (record.address.isNotBlank()) {
                Text(
                    text = "地址：${record.address}",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            record.provider?.let { provider ->
                Text(
                    text = "Provider：${provider.name}",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            if (record.evidenceSummary.isNotBlank()) {
                Text(
                    text = "证据：${record.evidenceSummary}",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            if (index < records.lastIndex) {
                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(12.dp))
            }
        }
    }
}

/**
 * 根据状态显示应用内持久化运行日志。
 */
@Composable
private fun RuntimeLogsDialog(
    state: MainUiState,
    onEvent: (MainUiEvent) -> Unit
) {
    if (!state.showLogsDialog) {
        return
    }

    AlertDialog(
        onDismissRequest = {
            onEvent(MainUiEvent.DismissRuntimeLogs)
        },
        title = {
            Text(text = "运行日志")
        },
        text = {
            RuntimeLogsContent(state.displayedLogs)
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onEvent(MainUiEvent.DismissRuntimeLogs)
                }
            ) {
                Text(text = "关闭")
            }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    onEvent(MainUiEvent.ClearRuntimeLogs)
                }
            ) {
                Text(text = "清空日志")
            }
        }
    )
}

/**
 * 渲染按时间倒序排列的结构化运行日志。
 */
@Composable
private fun RuntimeLogsContent(logs: List<AutomationLogEntry>) {
    Column {
        Text(text = "界面显示最近300条；完整日志按25 MB分段，不自动覆盖或删除，仅手动清空时同步删除。")
        Spacer(modifier = Modifier.height(16.dp))
        if (logs.isEmpty()) {
            Text(text = "暂无运行日志。")
        } else {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                logs.forEachIndexed { index, entry ->
                    val levelText = when (entry.level) {
                        AutomationLogLevel.INFO -> "信息"
                        AutomationLogLevel.WARNING -> "警告"
                        AutomationLogLevel.ERROR -> "错误"
                    }
                    val levelColor = when (entry.level) {
                        AutomationLogLevel.INFO -> RadarColors.NeonBlue
                        AutomationLogLevel.WARNING -> Color.Yellow
                        AutomationLogLevel.ERROR -> Color.Red
                    }

                    Text(
                        text = "${formatLogTime(entry.timestampMillis)} [$levelText] ${entry.source}",
                        style = MaterialTheme.typography.labelSmall,
                        color = levelColor
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = entry.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White
                    )
                    if (index < logs.lastIndex) {
                        Spacer(modifier = Modifier.height(10.dp))
                        HorizontalDivider()
                        Spacer(modifier = Modifier.height(10.dp))
                    }
                }
            }
        }
    }
}

/**
 * 根据状态显示全量清理二次确认对话框。
 */
@Composable
private fun ClearRecordsDialog(
    state: MainUiState,
    onEvent: (MainUiEvent) -> Unit
) {
    if (!state.showClearRecordsDialog) {
        return
    }

    AlertDialog(
        onDismissRequest = {
            onEvent(MainUiEvent.DismissClearAll)
        },
        title = {
            Text(text = "清空本地记录")
        },
        text = {
            Text(
                text = "此操作会清空本地打卡记录、双地图事务记录和永久去重集合，之后相同地点会重新进入候选队列。"
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onEvent(MainUiEvent.ConfirmClearAll)
                }
            ) {
                Text(text = "确认清空")
            }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    onEvent(MainUiEvent.DismissClearAll)
                }
            ) {
                Text(text = "取消")
            }
        }
    )
}

/**
 * 根据状态显示百度完成键清理二次确认对话框。
 */
@Composable
private fun ClearBaiduCompletionDialog(
    state: MainUiState,
    onEvent: (MainUiEvent) -> Unit
) {
    if (!state.showClearBaiduCompletionDialog) {
        return
    }
    AlertDialog(
        onDismissRequest = {
            onEvent(MainUiEvent.DismissClearBaiduCompletion)
        },
        title = { Text(text = "清空百度完成键") },
        text = {
            Text(
                text = "此操作保留历史记录，但允许百度已完成地点重新进入候选队列。"
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onEvent(MainUiEvent.ConfirmClearBaiduCompletion)
                }
            ) {
                Text(text = "确认清空")
            }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    onEvent(MainUiEvent.DismissClearBaiduCompletion)
                }
            ) {
                Text(text = "取消")
            }
        }
    )
}

/**
 * 将记录时间戳格式化为设备本地时分秒。
 */
private fun formatCheckInTime(checkInTimeMillis: Long): String {
    return Instant.ofEpochMilli(checkInTimeMillis)
        .atZone(ZoneId.systemDefault())
        .format(checkInTimeFormatter)
}

/**
 * 将日志时间戳格式化为设备本地月日和时分秒。
 */
private fun formatLogTime(timestampMillis: Long): String {
    return Instant.ofEpochMilli(timestampMillis)
        .atZone(ZoneId.systemDefault())
        .format(logTimeFormatter)
}

/**
 * 在 Android Studio 设计预览中渲染主界面。
 */
@Preview(showBackground = true)
@Composable
private fun MainScreenPreview() {
    NearbyCheckInTheme {
        MainScreen(
            state = MainUiState(
                baiduInstalled = true,
                amapInstalled = true,
                automationStatus = "等待开始"
            ),
            onEvent = {}
        )
    }
}

private const val DIAGNOSTICS_STALE_MILLIS = 45_000L
