package com.example.hrhostclone

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.hrhostclone.backend.StreamStats
import com.example.hrhostclone.core.ColorModeState
import com.example.hrhostclone.core.ColorPreset
import com.example.hrhostclone.preview.PreviewView
import java.net.Inet4Address
import java.net.NetworkInterface
import kotlin.math.roundToInt
import android.widget.Toast
import androidx.compose.material.icons.filled.MoreVert
import org.json.JSONObject

@Composable
fun MonitoringScreen(
    running: Boolean,
    stats: StreamStats,
    isUdp: Boolean,
    activePort: Int,
    onToggleRun: (Boolean) -> Unit,
    onProtocolChange: (Boolean) -> Unit,
    onExportConfig: () -> String = { "" },
    onSaveConfig: () -> Unit = {},
    onImportConfig: (JSONObject) -> Unit = {}
) {
    val extra = HXExtraTheme.colors
    val context = LocalContext.current
    val localIp = remember { getLocalIpAddress() }

    // ==================== 控制按钮状态 ====================
    var showBoundingBox by remember { mutableStateOf(true) }
    var showMask by remember { mutableStateOf(false) }
    var showPreview by remember { mutableStateOf(true) }

    LaunchedEffect(showBoundingBox) { ColorModeState.showBoundingBox = showBoundingBox }
    LaunchedEffect(showMask) { ColorModeState.showMask = showMask }
    LaunchedEffect(showPreview) { ColorModeState.showPreview = showPreview }

    // ==================== 自动瞄准状态（从 ColorModeState 初始化） ====================
    var autoAimEnabled by remember { mutableStateOf(ColorModeState.isAutoAimActive) }
    var aimSpeed by remember { mutableFloatStateOf(ColorModeState.aimSpeed) }
    var triggerButton by remember { mutableIntStateOf(ColorModeState.triggerButtonIndex) }
    var aimMode by remember { mutableIntStateOf(0) }
    var aimHeightPercent by remember { mutableFloatStateOf(ColorModeState.aimHeightPercent) }

    // WindMouse 参数
    var windGravity by remember { mutableFloatStateOf(ColorModeState.windGravity) }
    var windWind by remember { mutableFloatStateOf(ColorModeState.windWind) }
    var windMinWind by remember { mutableFloatStateOf(ColorModeState.windMinWind) }
    var windMaxWind by remember { mutableFloatStateOf(ColorModeState.windMaxWind) }
    var deadZoneRadius by remember { mutableFloatStateOf(ColorModeState.deadZoneRadius) }

    // ==================== 颜色预设状态 ====================
    var showPresetList by remember { mutableStateOf(false) }
    var showPresetDialog by remember { mutableStateOf(false) }
    var editingPreset by remember { mutableStateOf<ColorPreset?>(null) }
    var presetList by remember { mutableStateOf(ColorModeState.colorPresets) }
    var activePreset by remember { mutableIntStateOf(ColorModeState.activePresetIndex) }

    // ==================== 双向同步到 ColorModeState ====================
    // UI → State
    LaunchedEffect(autoAimEnabled) { ColorModeState.isAutoAimActive = autoAimEnabled }
    LaunchedEffect(aimSpeed) { ColorModeState.aimSpeed = aimSpeed }
    LaunchedEffect(aimMode) { ColorModeState.aimMode = if (aimMode == 1) "quick" else "unibot" }
    LaunchedEffect(triggerButton) {
        ColorModeState.triggerButtonIndex = triggerButton
        ColorModeState.triggerButtonMask = when (triggerButton) {
            0 -> 1; 1 -> 2; 2 -> 4; 3 -> 8; 4 -> 16; else -> 1
        }
    }
    LaunchedEffect(windGravity) { ColorModeState.windGravity = windGravity }
    LaunchedEffect(windWind) { ColorModeState.windWind = windWind }
    LaunchedEffect(windMinWind) { ColorModeState.windMinWind = windMinWind }
    LaunchedEffect(windMaxWind) { ColorModeState.windMaxWind = windMaxWind }
    LaunchedEffect(deadZoneRadius) { ColorModeState.deadZoneRadius = deadZoneRadius }
    LaunchedEffect(aimHeightPercent) { ColorModeState.aimHeightPercent = aimHeightPercent }


    // State → UI（从持久化恢复时同步回 UI）
    LaunchedEffect(ColorModeState.isAutoAimActive) { autoAimEnabled = ColorModeState.isAutoAimActive }
    LaunchedEffect(ColorModeState.aimSpeed) { aimSpeed = ColorModeState.aimSpeed }
    LaunchedEffect(ColorModeState.triggerButtonIndex) { triggerButton = ColorModeState.triggerButtonIndex }
    LaunchedEffect(ColorModeState.windGravity) { windGravity = ColorModeState.windGravity }
    LaunchedEffect(ColorModeState.windWind) { windWind = ColorModeState.windWind }
    LaunchedEffect(ColorModeState.windMinWind) { windMinWind = ColorModeState.windMinWind }
    LaunchedEffect(ColorModeState.windMaxWind) { windMaxWind = ColorModeState.windMaxWind }
    LaunchedEffect(ColorModeState.deadZoneRadius) { deadZoneRadius = ColorModeState.deadZoneRadius }
    LaunchedEffect(ColorModeState.aimHeightPercent) { aimHeightPercent = ColorModeState.aimHeightPercent }


    LaunchedEffect(activePreset) {
        ColorModeState.activePresetIndex = activePreset
        presetList.getOrNull(activePreset)?.let { preset ->
            ColorModeState.currentHMin = preset.hMin
            ColorModeState.currentSMin = preset.sMin
            ColorModeState.currentVMin = preset.vMin
            ColorModeState.currentHMax = preset.hMax
            ColorModeState.currentSMax = preset.sMax
            ColorModeState.currentVMax = preset.vMax
        }
    }

    Column(Modifier.fillMaxSize()) {
        // ==================== 固定区域：头部 + 预览 + 按钮 ====================
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 头部标题 + 配置菜单
            var showConfigMenu by remember { mutableStateOf(false) }
            var showImportDialog by remember { mutableStateOf(false) }
            var importJsonText by remember { mutableStateOf("") }

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("HX HOST", fontSize = 36.sp, fontWeight = FontWeight.Bold, color = extra.primaryText)

                Box {
                    IconButton(onClick = { showConfigMenu = true }) {
                        Icon(
                            Icons.Filled.MoreVert,
                            contentDescription = "配置",
                            tint = extra.secondaryText
                        )
                    }
                    DropdownMenu(
                        expanded = showConfigMenu,
                        onDismissRequest = { showConfigMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("导出配置") },
                            onClick = {
                                showConfigMenu = false
                                // 从 MainApp 传入的导出函数获取 JSON
                                val configJson = onExportConfig()
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("HX Config", configJson))
                                Toast.makeText(context, "配置已复制到剪贴板", Toast.LENGTH_SHORT).show()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("导入配置") },
                            onClick = {
                                showConfigMenu = false
                                // 从剪贴板读取
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                val clip = clipboard.primaryClip
                                if (clip != null && clip.itemCount > 0) {
                                    importJsonText = clip.getItemAt(0).text?.toString() ?: ""
                                }
                                showImportDialog = true
                            }
                        )
                    }
                }
            }

// 导入配置弹窗
            if (showImportDialog) {
                AlertDialog(
                    onDismissRequest = { showImportDialog = false },
                    title = { Text("导入配置", fontWeight = FontWeight.Bold) },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("粘贴配置 JSON 后点击导入", fontSize = 12.sp, color = extra.mutedText)
                            OutlinedTextField(
                                value = importJsonText,
                                onValueChange = { importJsonText = it },
                                label = { Text("配置 JSON") },
                                modifier = Modifier.fillMaxWidth().height(200.dp),
                                maxLines = 20
                            )
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            if (importJsonText.isNotBlank()) {
                                try {
                                    val json = JSONObject(importJsonText)
                                    onImportConfig(json)
                                    Toast.makeText(context, "导入成功", Toast.LENGTH_SHORT).show()
                                    showImportDialog = false
                                } catch (e: Exception) {
                                    Toast.makeText(context, "JSON 格式错误", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }) { Text("导入") }
                    },
                    dismissButton = {
                        TextButton(onClick = { showImportDialog = false }) { Text("取消") }
                    }
                )
            }

            // 状态卡片
            Card(
                colors = CardDefaults.cardColors(containerColor = extra.panelBackground),
                shape = RoundedCornerShape(16.dp)
            ) {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.CheckCircle, null, tint = if (running) extra.success else extra.border, modifier = Modifier.size(30.dp))
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(if (running) "工作中" else "待机中", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = extra.primaryText)
                            Surface(color = extra.success, shape = RoundedCornerShape(6.dp)) {
                                Text("OpenCV", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                            }
                        }
                        Text("版本: 1.0.0", color = extra.secondaryText, fontSize = 12.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            Text("接收: ${stats.receiveFps} FPS", color = extra.secondaryText, fontSize = 12.sp)
                            Text("延迟: ${stats.latencyMs}ms", color = extra.secondaryText, fontSize = 12.sp)
                            Text("找色: ${if (running) ColorModeState.detectionFps else 0} FPS", color = extra.secondaryText, fontSize = 12.sp)
                        }
                    }
                }
            }

            // 预览画面 + 控制按钮
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    Modifier.weight(1f).height(172.dp).clip(RoundedCornerShape(14.dp)).background(Color.Black)
                ) {
                    if (running) {
                        AndroidView(factory = { PreviewView(it) }, modifier = Modifier.fillMaxSize())
                    } else {
                        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("已停止", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                            Text("等待启动", color = Color(0xFFCCCCCC), fontSize = 14.sp)
                        }
                    }
                }

                Column(
                    Modifier.weight(1f).height(172.dp),
                    verticalArrangement = Arrangement.Bottom,
                    horizontalAlignment = Alignment.End
                ) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MonitorToolButton("◻", "标记", showBoundingBox, Modifier.weight(1f)) { showBoundingBox = !showBoundingBox }
                        MonitorToolButton("T", "二值", showMask, Modifier.weight(1f)) { showMask = !showMask; if (showMask) showPreview = true }
                        MonitorToolButton("✕", "关闭", !showPreview, Modifier.weight(1f)) { showPreview = !showPreview }
                    }
                    Spacer(Modifier.height(8.dp))
                    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
                        Text(presetList.getOrNull(activePreset)?.name ?: "未选择颜色", fontWeight = FontWeight.Bold, color = extra.secondaryText, fontSize = 16.sp)
                        Text(if (ColorModeState.isDetected) "OK" else "WAIT", color = if (ColorModeState.isDetected) extra.success else extra.mutedText, fontSize = 11.sp)
                    }
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = { onToggleRun(!running) },
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = if (running) Color(0xFFC8102E) else extra.bilibiliPink, contentColor = Color.White)
                    ) {
                        Text(if (running) "停止" else "启动找色", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }

        // ==================== 可滚动区域 ====================
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp).padding(bottom = 10.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ==================== 颜色预设 ====================
            Card(colors = CardDefaults.cardColors(containerColor = extra.panelBackground), shape = RoundedCornerShape(16.dp)) {
                Row(Modifier.fillMaxWidth().clickable { showPresetList = true }.padding(14.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        val active = presetList.getOrNull(activePreset)
                        Surface(modifier = Modifier.size(36.dp), shape = RoundedCornerShape(8.dp), color = if (active != null) Color.hsv((active.hMin + active.hMax) / 2f * 2f, (active.sMin + active.sMax) / 2f / 255f, (active.vMin + active.vMax) / 2f / 255f) else Color.Magenta) {}
                        Column {
                            Text(active?.name ?: "未选择", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = extra.primaryText)
                            if (active != null) Text("H:${active.hMin}-${active.hMax}  S:${active.sMin}-${active.sMax}  V:${active.vMin}-${active.vMax}", fontSize = 11.sp, color = extra.mutedText)
                        }
                    }
                    Text("切换 ›", fontSize = 12.sp, color = extra.mutedText)
                }
            }

            // ==================== 预设列表弹窗 ====================
            if (showPresetList) {
                AlertDialog(
                    onDismissRequest = { showPresetList = false },
                    title = {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("颜色预设", fontWeight = FontWeight.Bold)
                            TextButton(onClick = { editingPreset = ColorPreset("", 140, 60, 150, 170, 255, 255); showPresetDialog = true }) { Text("+ 新建", color = extra.bilibiliBlue, fontSize = 12.sp) }
                        }
                    },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            presetList.forEachIndexed { index, preset ->
                                val isActive = index == activePreset
                                Surface(
                                    modifier = Modifier.fillMaxWidth().clickable { activePreset = index; showPresetList = false },
                                    shape = RoundedCornerShape(10.dp),
                                    color = if (isActive) extra.bilibiliBlue.copy(alpha = 0.1f) else Color.Transparent,
                                    border = BorderStroke(1.dp, if (isActive) extra.bilibiliBlue else extra.border)
                                ) {
                                    Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                            Surface(modifier = Modifier.size(28.dp), shape = RoundedCornerShape(6.dp), color = Color.hsv((preset.hMin + preset.hMax) / 2f * 2f, (preset.sMin + preset.sMax) / 2f / 255f, (preset.vMin + preset.vMax) / 2f / 255f)) {}
                                            Column {
                                                Text(preset.name.ifBlank { "未命名" }, fontSize = 13.sp, fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal, color = extra.primaryText)
                                                Text("H:${preset.hMin}-${preset.hMax}  S:${preset.sMin}-${preset.sMax}  V:${preset.vMin}-${preset.vMax}", fontSize = 10.sp, color = extra.mutedText)
                                            }
                                        }
                                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                            if (isActive) Text("✓", color = extra.bilibiliBlue, fontWeight = FontWeight.Bold)
                                            TextButton(onClick = { editingPreset = preset; showPresetDialog = true }) { Text("编辑", fontSize = 10.sp, color = extra.mutedText) }
                                            TextButton(onClick = {
                                                val newList = presetList.toMutableList(); newList.removeAt(index); presetList = newList
                                                ColorModeState.colorPresets = newList; if (activePreset >= newList.size) activePreset = (newList.size - 1).coerceAtLeast(0)
                                            }) { Text("删除", fontSize = 10.sp, color = extra.error) }
                                        }
                                    }
                                }
                            }
                        }
                    },
                    confirmButton = { TextButton(onClick = { showPresetList = false }) { Text("关闭") } }
                )
            }

            // ==================== 颜色编辑弹窗 ====================
            if (showPresetDialog && editingPreset != null) {
                var editName by remember(editingPreset) { mutableStateOf(editingPreset!!.name) }
                var editHMin by remember(editingPreset) { mutableIntStateOf(editingPreset!!.hMin) }
                var editSMin by remember(editingPreset) { mutableIntStateOf(editingPreset!!.sMin) }
                var editVMin by remember(editingPreset) { mutableIntStateOf(editingPreset!!.vMin) }
                var editHMax by remember(editingPreset) { mutableIntStateOf(editingPreset!!.hMax) }
                var editSMax by remember(editingPreset) { mutableIntStateOf(editingPreset!!.sMax) }
                var editVMax by remember(editingPreset) { mutableIntStateOf(editingPreset!!.vMax) }

                AlertDialog(
                    onDismissRequest = { showPresetDialog = false },
                    title = { Text(if (editingPreset!!.name.isBlank()) "新建预设" else "编辑预设", fontWeight = FontWeight.Bold) },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(value = editName, onValueChange = { editName = it }, label = { Text("名称") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                            Box(Modifier.fillMaxWidth().height(40.dp).clip(RoundedCornerShape(8.dp)).background(Color.hsv((editHMin + editHMax) / 2f * 2f, (editSMin + editSMax) / 2f / 255f, (editVMin + editVMax) / 2f / 255f)))
                            Text("最小值", fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = extra.primaryText)
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Column(Modifier.weight(1f)) { Text("H: $editHMin", fontSize = 10.sp, color = extra.mutedText); Slider(value = editHMin.toFloat(), onValueChange = { editHMin = it.toInt() }, valueRange = 0f..180f, colors = SliderDefaults.colors(thumbColor = extra.bilibiliBlue, activeTrackColor = extra.bilibiliBlue, inactiveTrackColor = extra.border)) }
                                Column(Modifier.weight(1f)) { Text("S: $editSMin", fontSize = 10.sp, color = extra.mutedText); Slider(value = editSMin.toFloat(), onValueChange = { editSMin = it.toInt() }, valueRange = 0f..255f, colors = SliderDefaults.colors(thumbColor = extra.bilibiliBlue, activeTrackColor = extra.bilibiliBlue, inactiveTrackColor = extra.border)) }
                                Column(Modifier.weight(1f)) { Text("V: $editVMin", fontSize = 10.sp, color = extra.mutedText); Slider(value = editVMin.toFloat(), onValueChange = { editVMin = it.toInt() }, valueRange = 0f..255f, colors = SliderDefaults.colors(thumbColor = extra.bilibiliBlue, activeTrackColor = extra.bilibiliBlue, inactiveTrackColor = extra.border)) }
                            }
                            Text("最大值", fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = extra.primaryText)
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Column(Modifier.weight(1f)) { Text("H: $editHMax", fontSize = 10.sp, color = extra.mutedText); Slider(value = editHMax.toFloat(), onValueChange = { editHMax = it.toInt() }, valueRange = 0f..180f, colors = SliderDefaults.colors(thumbColor = extra.bilibiliBlue, activeTrackColor = extra.bilibiliBlue, inactiveTrackColor = extra.border)) }
                                Column(Modifier.weight(1f)) { Text("S: $editSMax", fontSize = 10.sp, color = extra.mutedText); Slider(value = editSMax.toFloat(), onValueChange = { editSMax = it.toInt() }, valueRange = 0f..255f, colors = SliderDefaults.colors(thumbColor = extra.bilibiliBlue, activeTrackColor = extra.bilibiliBlue, inactiveTrackColor = extra.border)) }
                                Column(Modifier.weight(1f)) { Text("V: $editVMax", fontSize = 10.sp, color = extra.mutedText); Slider(value = editVMax.toFloat(), onValueChange = { editVMax = it.toInt() }, valueRange = 0f..255f, colors = SliderDefaults.colors(thumbColor = extra.bilibiliBlue, activeTrackColor = extra.bilibiliBlue, inactiveTrackColor = extra.border)) }
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            val newPreset = ColorPreset(name = editName.ifBlank { "预设" }, hMin = editHMin, sMin = editSMin, vMin = editVMin, hMax = editHMax, sMax = editSMax, vMax = editVMax)
                            val list = presetList.toMutableList()
                            val existingIndex = list.indexOfFirst { it.name == editingPreset!!.name && editingPreset!!.name.isNotBlank() }
                            if (existingIndex >= 0) { list[existingIndex] = newPreset } else { list.add(newPreset); activePreset = list.size - 1 }
                            presetList = list; ColorModeState.colorPresets = list; showPresetDialog = false
                        }) { Text("保存") }
                    },
                    dismissButton = { TextButton(onClick = { showPresetDialog = false }) { Text("取消") } }
                )
            }

            // ==================== 自动瞄准设置 ====================
            Card(colors = CardDefaults.cardColors(containerColor = extra.panelBackground), shape = RoundedCornerShape(16.dp)) {
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("自动瞄准", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = extra.primaryText)
                        Switch(checked = autoAimEnabled, onCheckedChange = { autoAimEnabled = it }, colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = extra.success, uncheckedThumbColor = Color.White, uncheckedTrackColor = extra.border))
                    }

                    Text("触发按键", fontSize = 14.sp, color = extra.primaryText)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("左键", "右键", "中键", "上侧", "下侧").forEachIndexed { index, label ->
                            val isSelected = triggerButton == index
                            Surface(
                                modifier = Modifier.weight(1f).clickable { triggerButton = index }, shape = RoundedCornerShape(8.dp),
                                color = if (isSelected) extra.bilibiliBlue else Color.Transparent, border = BorderStroke(1.dp, if (isSelected) extra.bilibiliBlue else extra.border)
                            ) { Text(label, modifier = Modifier.padding(vertical = 8.dp), textAlign = TextAlign.Center, fontSize = 11.sp, color = if (isSelected) Color.White else extra.secondaryText, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal) }
                        }
                    }
                    // 锁队友开关
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("锁队友", fontSize = 14.sp, color = extra.primaryText)
                        var lockTeammates by remember { mutableStateOf(ColorModeState.lockTeammates) }
                        LaunchedEffect(lockTeammates) { ColorModeState.lockTeammates = lockTeammates }
                        LaunchedEffect(ColorModeState.lockTeammates) { lockTeammates = ColorModeState.lockTeammates }
                        Switch(
                            checked = lockTeammates,
                            onCheckedChange = { lockTeammates = it },
                            colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = extra.bilibiliBlue, uncheckedThumbColor = Color.White, uncheckedTrackColor = extra.border)
                        )
                    }

                    HorizontalDivider(color = extra.border)

                    // 滑块 range 改为 0.1f..10f，步进不需要额外改，Slider 默认支持浮点
                    Text("移动速度", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = extra.primaryText)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("慢", fontSize = 11.sp, color = extra.mutedText)
                        Slider(
                            value = aimSpeed,
                            onValueChange = { aimSpeed = it },
                            valueRange = 0.1f..2f,
                            modifier = Modifier.weight(1f),
                            colors = SliderDefaults.colors(thumbColor = extra.bilibiliBlue, activeTrackColor = extra.bilibiliBlue, inactiveTrackColor = extra.border)
                        )
                        Text("快", fontSize = 11.sp, color = extra.mutedText)
                    }
                    Text("%.1f".format(aimSpeed), fontSize = 12.sp, color = extra.mutedText, modifier = Modifier.align(Alignment.End))

                    Text("死区半径", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = extra.primaryText)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("小", fontSize = 11.sp, color = extra.mutedText)
                        Slider(value = deadZoneRadius, onValueChange = { deadZoneRadius = it }, valueRange = 0f..30f, modifier = Modifier.weight(1f), colors = SliderDefaults.colors(thumbColor = extra.bilibiliBlue, activeTrackColor = extra.bilibiliBlue, inactiveTrackColor = extra.border))
                        Text("大", fontSize = 11.sp, color = extra.mutedText)
                    }
                    Text("${deadZoneRadius.roundToInt()} px", fontSize = 12.sp, color = extra.mutedText, modifier = Modifier.align(Alignment.End))

                    Text("重力 (跟枪力度)", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = extra.primaryText)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("弱", fontSize = 11.sp, color = extra.mutedText)
                        Slider(value = windGravity, onValueChange = { windGravity = it }, valueRange = 5f..20f, modifier = Modifier.weight(1f), colors = SliderDefaults.colors(thumbColor = extra.bilibiliBlue, activeTrackColor = extra.bilibiliBlue, inactiveTrackColor = extra.border))
                        Text("强", fontSize = 11.sp, color = extra.mutedText)
                    }
                    Text("%.1f".format(windGravity), fontSize = 12.sp, color = extra.mutedText, modifier = Modifier.align(Alignment.End))

                    Text("风力 (轨迹弯曲)", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = extra.primaryText)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("直", fontSize = 11.sp, color = extra.mutedText)
                        Slider(value = windWind, onValueChange = { windWind = it }, valueRange = 1f..10f, modifier = Modifier.weight(1f), colors = SliderDefaults.colors(thumbColor = extra.bilibiliBlue, activeTrackColor = extra.bilibiliBlue, inactiveTrackColor = extra.border))
                        Text("弯", fontSize = 11.sp, color = extra.mutedText)
                    }
                    Text("%.1f".format(windWind), fontSize = 12.sp, color = extra.mutedText, modifier = Modifier.align(Alignment.End))

                    // 瞄准高度
                    Text("瞄准高度", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = extra.primaryText)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("头", fontSize = 11.sp, color = extra.mutedText)
                        Slider(value = aimHeightPercent, onValueChange = { aimHeightPercent = it }, valueRange = 10f..95f, modifier = Modifier.weight(1f), colors = SliderDefaults.colors(thumbColor = extra.bilibiliBlue, activeTrackColor = extra.bilibiliBlue, inactiveTrackColor = extra.border))
                        Text("脚", fontSize = 11.sp, color = extra.mutedText)
                    }
                    Text(when { aimHeightPercent < 22f -> "头部 (${"%.1f".format(aimHeightPercent)}%)"; aimHeightPercent < 40f -> "上半身 (${"%.1f".format(aimHeightPercent)}%)"; aimHeightPercent < 70f -> "身体 (${"%.1f".format(aimHeightPercent)}%)"; else -> "脚部 (${"%.1f".format(aimHeightPercent)}%)" }, fontSize = 12.sp, color = extra.bilibiliBlue, fontWeight = FontWeight.SemiBold, modifier = Modifier.align(Alignment.End))
                }
            }

            // ==================== 传输协议 ====================
            Text("传输协议", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = extra.primaryText)
            Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(26.dp), color = if (running) Color(0xFFE0E0E0) else Color(0xFFE7E7E7)) {
                Row(Modifier.fillMaxWidth().padding(3.dp)) {
                    ProtocolSegmentButton("UDP", isUdp, !running, extra, Modifier.weight(1f)) { onProtocolChange(true) }
                    ProtocolSegmentButton("TCP", !isUdp, !running, extra, Modifier.weight(1f)) { onProtocolChange(false) }
                }
            }

            // ==================== 画面传输配置 ====================
            Card(colors = CardDefaults.cardColors(containerColor = extra.panelBackground), shape = RoundedCornerShape(16.dp)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("画面传输配置", color = extra.mutedText, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Text("请在电脑端运行 HX Streamer 推流器", color = extra.secondaryText, fontSize = 12.sp)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) { Text("本机 IP", color = extra.primaryText, fontSize = 16.sp); Text(localIp, color = extra.secondaryText, fontWeight = FontWeight.Bold, fontSize = 28.sp) }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) { Text("监听端口", color = extra.primaryText, fontSize = 16.sp); Text(activePort.toString(), color = extra.secondaryText, fontWeight = FontWeight.Bold, fontSize = 28.sp) }
                }
            }
        }
    }
}

// ==================== 辅助组件 ====================

@Composable
private fun MonitorToolButton(icon: String, label: String, active: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(modifier = modifier.height(52.dp).clip(RoundedCornerShape(10.dp)).background(if (active) Color(0xFF7F7F7F) else Color(0xFFE1E1E1)).clickable(onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text(icon, fontSize = 18.sp, color = if (active) Color.White else Color(0xFF6A6A6A))
        Text(label, fontSize = 9.sp, color = if (active) Color.White else Color(0xFF6A6A6A))
    }
}

@Composable
private fun ProtocolSegmentButton(text: String, selected: Boolean, enabled: Boolean, extra: HXExtraColors, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(modifier = modifier.clip(RoundedCornerShape(22.dp)).background(when { !enabled && selected -> Color(0xFFBDBDBD); selected -> Color(0xFF858585); else -> Color.Transparent }).clickable(enabled = enabled, onClick = onClick).padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
        Text(text, color = when { !enabled && selected -> Color(0xFFF5F5F5); !enabled -> Color(0xFF9A9A9A); selected -> Color.White; else -> extra.secondaryText }, fontSize = 16.sp, fontWeight = FontWeight.Bold)
    }
}

private fun getLocalIpAddress(): String {
    return try {
        val interfaces = NetworkInterface.getNetworkInterfaces()
        while (interfaces.hasMoreElements()) {
            val nif = interfaces.nextElement()
            if (!nif.isUp || nif.isLoopback) continue
            val addrs = nif.inetAddresses
            while (addrs.hasMoreElements()) { val addr = addrs.nextElement(); if (!addr.isLoopbackAddress && addr is Inet4Address) return addr.hostAddress ?: "" }
        }
        "No IP"
    } catch (_: Exception) { "No IP" }
}

