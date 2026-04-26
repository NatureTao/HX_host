package com.example.hrhostclone

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.hrhostclone.core.ColorModeState
import kotlin.math.roundToInt

@Composable
fun SettingScreen(
    currentThemeMode: AppThemeMode = AppThemeMode.Bilibili,
    onThemeChange: (AppThemeMode) -> Unit = {}
) {
    val extra = HXExtraTheme.colors

    // ==================== 形态学参数 ====================
    var dilateSize by remember { mutableFloatStateOf(ColorModeState.dilateSizeValue) }
    var dilateIterations by remember { mutableIntStateOf(ColorModeState.dilateIterationsValue) }
    var closeSize by remember { mutableFloatStateOf(ColorModeState.closeSizeValue) }

    // ==================== 轮廓过滤 ====================
    var minArea by remember { mutableFloatStateOf(ColorModeState.minContourArea) }
    var maxArea by remember { mutableFloatStateOf(ColorModeState.maxContourArea) }
    var minWidth by remember { mutableIntStateOf(ColorModeState.minWidthValue) }
    var minHeight by remember { mutableIntStateOf(ColorModeState.minHeightValue) }
    var minAspect by remember { mutableFloatStateOf(ColorModeState.minAspectRatio) }
    var maxAspect by remember { mutableFloatStateOf(ColorModeState.maxAspectRatio) }
    var minFill by remember { mutableFloatStateOf(ColorModeState.minFillRatio) }

    // ==================== 瞄准高度（滑块 0-100） ====================
    var aimHeight by remember { mutableFloatStateOf(ColorModeState.aimHeightPercent) }

    // ==================== 主题弹窗 ====================
    var showThemeDialog by remember { mutableStateOf(false) }

    // ==================== 同步到全局状态 ====================
    LaunchedEffect(dilateSize) { ColorModeState.dilateSizeValue = dilateSize }
    LaunchedEffect(dilateIterations) { ColorModeState.dilateIterationsValue = dilateIterations }
    LaunchedEffect(closeSize) { ColorModeState.closeSizeValue = closeSize }
    LaunchedEffect(minArea) { ColorModeState.minContourArea = minArea }
    LaunchedEffect(maxArea) { ColorModeState.maxContourArea = maxArea }
    LaunchedEffect(minWidth) { ColorModeState.minWidthValue = minWidth }
    LaunchedEffect(minHeight) { ColorModeState.minHeightValue = minHeight }
    LaunchedEffect(minAspect) { ColorModeState.minAspectRatio = minAspect }
    LaunchedEffect(maxAspect) { ColorModeState.maxAspectRatio = maxAspect }
    LaunchedEffect(minFill) { ColorModeState.minFillRatio = minFill }
    LaunchedEffect(aimHeight) { ColorModeState.aimHeightPercent = aimHeight }
    LaunchedEffect(ColorModeState.aimHeightPercent) { aimHeight = ColorModeState.aimHeightPercent }

    // ==================== 主题选择弹窗 ====================
    if (showThemeDialog) {
        AlertDialog(
            onDismissRequest = { showThemeDialog = false },
            title = { Text("选择主题", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    AppThemeMode.entries.forEach { mode ->
                        val isSelected = mode == currentThemeMode
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onThemeChange(mode)
                                    showThemeDialog = false
                                },
                            shape = RoundedCornerShape(10.dp),
                            color = if (isSelected) extra.bilibiliBlue.copy(alpha = 0.1f)
                            else Color.Transparent,
                            border = if (isSelected)
                                BorderStroke(1.5.dp, extra.bilibiliBlue)
                            else null
                        ) {
                            Row(
                                Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Text(mode.label, fontSize = 14.sp, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal, color = extra.primaryText)
                                if (isSelected) Text("✓", color = extra.bilibiliBlue, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showThemeDialog = false }) { Text("关闭") } }
        )
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("设置", fontSize = 32.sp, fontWeight = FontWeight.Bold, color = extra.primaryText)

        // ==================== 形态学参数 ====================
        Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = extra.panelBackground), shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("形态学参数", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = extra.primaryText)
                SettingSlider("膨胀核大小", dilateSize, { dilateSize = it }, 1f..5f, extra)
                SettingSlider("膨胀迭代次数", dilateIterations.toFloat(), { dilateIterations = it.roundToInt() }, 1f..4f, extra)
                SettingSlider("闭运算核大小", closeSize, { closeSize = it }, 1f..10f, extra)
            }
        }

        // ==================== 轮廓过滤 ====================
        Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = extra.panelBackground), shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("轮廓过滤", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = extra.primaryText)
                SettingSlider("最小面积", minArea, { minArea = it }, 10f..500f, extra)
                SettingSlider("最大面积", maxArea, { maxArea = it }, 1000f..100000f, extra)
                SettingSlider("最小宽度", minWidth.toFloat(), { minWidth = it.roundToInt() }, 3f..30f, extra)
                SettingSlider("最小高度", minHeight.toFloat(), { minHeight = it.roundToInt() }, 5f..50f, extra)
                SettingSlider("宽高比下限", minAspect, { minAspect = it }, 0.1f..1.0f, extra)
                SettingSlider("宽高比上限", maxAspect, { maxAspect = it }, 1.0f..5.0f, extra)
                SettingSlider("填充率下限", minFill, { minFill = it }, 0.1f..0.9f, extra)
            }
        }
        // ==================== 主题选择 ====================
        Card(
            modifier = Modifier.fillMaxWidth().clickable { showThemeDialog = true },
            colors = CardDefaults.cardColors(containerColor = extra.panelBackground),
            shape = RoundedCornerShape(16.dp)
        ) {
            Row(
                Modifier.fillMaxWidth().padding(14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("主题选择", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = extra.primaryText)
                    Text(currentThemeMode.label, fontSize = 12.sp, color = extra.mutedText)
                }
            }
        }

        // ==================== 关于 ====================
        Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = extra.panelBackground), shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.fillMaxWidth().padding(14.dp)) {
                Text("关于", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = extra.primaryText)
                Text("HX HOST 仅学习交流切勿用于实战", fontSize = 12.sp, color = extra.mutedText)
            }
        }
    }
}

@Composable
private fun SettingSlider(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    range: ClosedFloatingPointRange<Float>,
    extra: HXExtraColors
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, fontSize = 12.sp, color = extra.secondaryText)
            Text("%.1f".format(value), fontSize = 12.sp, color = extra.mutedText)
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = range,
            modifier = Modifier.fillMaxWidth(),
            colors = SliderDefaults.colors(
                thumbColor = extra.bilibiliBlue,
                activeTrackColor = extra.bilibiliBlue,
                inactiveTrackColor = extra.border
            )
        )
    }
}