package com.example.hrhostclone

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.hrhostclone.backend.DeviceTestActions
import com.example.hrhostclone.backend.InputBackend
import com.example.hrhostclone.backend.KMBOX_NET_DEFAULT_MONITOR_PORT
import com.example.hrhostclone.backend.KMBOX_NET_DEFAULT_PORT
import com.example.hrhostclone.backend.KmboxNetConfig
import com.example.hrhostclone.backend.KmboxNetRuntime
import com.example.hrhostclone.backend.MakcuLinkRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * 控制页面
 * 负责 MAKCU USB / KMBOX NET 的设备连接、按键监控、测试工具
 */
@Composable
fun FacilityScreen(
    selectedInputBackend: InputBackend,
    kmboxIp: String,
    kmboxPort: String,
    kmboxUuid: String,
    kmboxMonitorPort: String,
    onSelectedInputBackendChange: (InputBackend) -> Unit,
    onKmboxIpChange: (String) -> Unit,
    onKmboxPortChange: (String) -> Unit,
    onKmboxUuidChange: (String) -> Unit,
    onKmboxMonitorPortChange: (String) -> Unit
) {
    val extra = HXExtraTheme.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val usbManager = remember(context) {
        context.getSystemService(Context.USB_SERVICE) as? UsbManager
    }

    // 订阅连接状态
    val makcuState by MakcuLinkRuntime.state.collectAsState()
    val kmboxState by KmboxNetRuntime.state.collectAsState()
    val linkState = if (selectedInputBackend == InputBackend.MakcuUsb) makcuState else kmboxState

    // 鼠标按键标签和掩码
    val mouseLabels = listOf("左键", "右键", "中键", "上侧", "下侧")
    val mouseMasks = listOf(0x01, 0x02, 0x04, 0x08, 0x10)

    // 测试工具
    var drawDelayMs by remember { mutableFloatStateOf(4f) }
    LaunchedEffect(drawDelayMs) {
        DeviceTestActions.drawStepDelayMs = drawDelayMs.roundToInt().coerceIn(2, 22).toLong()
    }

    // USB 设备列表
    var usbDevices by remember { mutableStateOf(listOf<UsbDevice>()) }

    fun refreshUsbDevices(): List<UsbDevice> {
        val latest = runCatching {
            usbManager?.deviceList?.values?.toList().orEmpty().sortedBy { it.deviceId }
        }.getOrDefault(emptyList())
        usbDevices = latest
        return latest
    }

    LaunchedEffect(Unit) { refreshUsbDevices() }

    Column(
        Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // ==================== 页面标题 ====================
        Text("控制", fontSize = 32.sp, fontWeight = FontWeight.Bold, color = extra.primaryText)

        // ==================== 输入后端选择 ====================
        Text("输入后端", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = extra.secondaryText)

        Surface(
            color = Color(0xFFE4E4E4),
            shape = RoundedCornerShape(22.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(Modifier.fillMaxWidth()) {
                InputBackend.entries.forEachIndexed { index, backend ->
                    val isSelected = selectedInputBackend == backend
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(22.dp))
                            .background(if (isSelected) extra.bilibiliBlue else Color.Transparent)
                            .clickable { onSelectedInputBackendChange(backend) }
                            .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            (if (isSelected) "✓ " else "") + backend.label,
                            color = if (isSelected) Color.White else extra.secondaryText,
                            fontSize = 13.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                        )
                    }
                }
            }
        }

        // ==================== 连接状态卡片 ====================
        Card(
            colors = CardDefaults.cardColors(containerColor = extra.panelBackground),
            shape = RoundedCornerShape(16.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 状态指示灯
                Text("●", color = if (linkState.connected) extra.success else extra.border, fontSize = 14.sp)
                Spacer(Modifier.width(10.dp))

                // 状态文字
                Text(
                    if (linkState.connected) "已连接" else "未连接",
                    fontWeight = FontWeight.SemiBold,
                    color = extra.primaryText,
                    fontSize = 16.sp
                )

                Spacer(Modifier.weight(1f))

                // ========== 连接/断开按钮（互斥显示） ==========
                if (linkState.connected) {
                    // 已连接 → 显示断开按钮
                    TextButton(onClick = {
                        when (selectedInputBackend) {
                            InputBackend.MakcuUsb -> MakcuLinkRuntime.disconnect("已断开")
                            InputBackend.KmboxNet -> KmboxNetRuntime.disconnect("已断开")
                        }
                    }) {
                        Icon(
                            Icons.Default.LinkOff,
                            contentDescription = null,
                            tint = extra.error,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text("断开", color = extra.error, fontSize = 13.sp)
                    }
                } else {
                    // 未连接 → 显示连接按钮
                    Button(
                        onClick = {
                            when (selectedInputBackend) {
                                InputBackend.MakcuUsb -> {
                                    // MAKCU：尝试连接选中的设备
                                    val device = usbDevices.firstOrNull()
                                    if (device != null) {
                                        scope.launch {
                                            withContext(Dispatchers.IO) {
                                                MakcuLinkRuntime.connect(usbManager!!, device)
                                            }
                                        }
                                    }
                                }
                                InputBackend.KmboxNet -> {
                                    // KMBOX：用配置信息连接
                                    scope.launch {
                                        KmboxNetRuntime.connect(
                                            KmboxNetConfig(
                                                ip = kmboxIp.trim(),
                                                port = kmboxPort.toIntOrNull()
                                                    ?: KMBOX_NET_DEFAULT_PORT,
                                                uuid = kmboxUuid.trim(),
                                                monitorPort = kmboxMonitorPort.toIntOrNull()
                                                    ?: KMBOX_NET_DEFAULT_MONITOR_PORT
                                            )
                                        )
                                    }
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = extra.bilibiliPink
                        ),
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp)
                    ) {
                        Text("连接", color = Color.White, fontSize = 14.sp)
                    }
                }
            }
        }

        // ==================== KMBOX 配置表单（仅选中时显示） ====================
        if (selectedInputBackend == InputBackend.KmboxNet && !linkState.connected) {
            Card(
                colors = CardDefaults.cardColors(containerColor = extra.panelBackground),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        "KMBOX NET 配置",
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        color = extra.primaryText
                    )

                    OutlinedTextField(
                        value = kmboxIp,
                        onValueChange = onKmboxIpChange,
                        label = { Text("设备 IP") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = kmboxPort,
                            onValueChange = onKmboxPortChange,
                            label = { Text("控制端口") },
                            modifier = Modifier.weight(1f),
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = kmboxMonitorPort,
                            onValueChange = onKmboxMonitorPortChange,
                            label = { Text("监听端口") },
                            modifier = Modifier.weight(1f),
                            singleLine = true
                        )
                    }
                    OutlinedTextField(
                        value = kmboxUuid,
                        onValueChange = onKmboxUuidChange,
                        label = { Text("UUID (8位十六进制)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }
            }
        }

        // ==================== MAKCU USB 设备列表（仅选中时显示） ====================
        if (selectedInputBackend == InputBackend.MakcuUsb && !linkState.connected) {
            // 诊断信息
            Card(
                colors = CardDefaults.cardColors(containerColor = extra.panelBackground),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("USB 诊断", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = extra.primaryText)
                        TextButton(
                            onClick = { refreshUsbDevices() },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Icon(
                                Icons.Default.Refresh,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = extra.bilibiliBlue
                            )
                            Spacer(Modifier.width(4.dp))
                            Text("刷新", fontSize = 11.sp, color = extra.bilibiliBlue)
                        }
                    }
                    Text("✓ USB Host: 支持", fontSize = 12.sp, color = extra.primaryText)
                    Text("检测到设备: ${usbDevices.size} 个", fontSize = 12.sp, color = extra.secondaryText)
                }
            }

            // 设备列表
            if (usbDevices.isNotEmpty()) {
                Text("点击设备进行连接", fontSize = 12.sp, color = extra.secondaryText)
                usbDevices.forEach { device ->
                    Card(
                        colors = CardDefaults.cardColors(containerColor = extra.panelBackground),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                scope.launch {
                                    withContext(Dispatchers.IO) {
                                        MakcuLinkRuntime.connect(usbManager!!, device)
                                    }
                                }
                            }
                    ) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    device.productName ?: "USB Device",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = extra.primaryText
                                )
                                Text(
                                    "VID: ${device.vendorId.toString(16).uppercase().padStart(4, '0')} | " +
                                            "PID: ${device.productId.toString(16).uppercase().padStart(4, '0')}",
                                    fontSize = 11.sp,
                                    color = extra.secondaryText
                                )
                            }
                        }
                    }
                }
            }
        }

        // ==================== 按键状态显示（连接后显示） ====================
        if (linkState.connected) {
            Card(
                colors = CardDefaults.cardColors(containerColor = extra.panelBackground),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("按键状态", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = extra.primaryText)

                    // 5 个指示灯
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        mouseLabels.forEachIndexed { index, label ->
                            val mask = mouseMasks[index]
                            val isPressed = (linkState.buttonMask and mask) != 0
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Surface(
                                    shape = CircleShape,
                                    color = if (isPressed) extra.success.copy(alpha = 0.2f)
                                    else Color(0xFFF5F5F5),
                                    border = BorderStroke(
                                        1.dp,
                                        if (isPressed) extra.success else extra.border
                                    ),
                                    modifier = Modifier.size(36.dp)
                                ) {}
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    label,
                                    fontSize = 11.sp,
                                    color = if (isPressed) extra.success else extra.secondaryText,
                                    fontWeight = if (isPressed) FontWeight.Bold else FontWeight.Normal
                                )
                            }
                        }
                    }

                    // 按下的按键名称
                    val pressedNames = mouseLabels
                        .mapIndexedNotNull { index, label ->
                            label.takeIf { (linkState.buttonMask and mouseMasks[index]) != 0 }
                        }
                        .joinToString("、")

                    Text(
                        text = if (pressedNames.isBlank()) "未检测到按键按下" else "检测到按下: $pressedNames",
                        fontSize = 12.sp,
                        color = extra.secondaryText
                    )

                    // RAW 值和映射值
                    Text(
                        text = "RAW: 0x${(linkState.rawButtonMask and 0xFFFF).toString(16).uppercase()}  " +
                                "映射: 0x${(linkState.buttonMask and 0x1F).toString(16).uppercase()}",
                        fontSize = 11.sp,
                        color = extra.mutedText
                    )
                }
            }

            // ==================== 测试工具 ====================
            Card(
                colors = CardDefaults.cardColors(containerColor = extra.panelBackground),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("测试工具", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = extra.primaryText)

                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    DeviceTestActions.drawSquare(selectedInputBackend)
                                }
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, extra.border)
                        ) {
                            Text("画正方形", fontSize = 13.sp, color = extra.primaryText)
                        }
                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    DeviceTestActions.drawCircle(selectedInputBackend)
                                }
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, extra.border)
                        ) {
                            Text("画圆形", fontSize = 13.sp, color = extra.primaryText)
                        }
                    }

                    // 轨迹速度
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("轨迹速度", fontSize = 13.sp, color = extra.secondaryText)
                        Text(
                            "${drawDelayMs.roundToInt()} ms/步",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = extra.primaryText
                        )
                    }
                    Slider(
                        value = drawDelayMs,
                        onValueChange = { drawDelayMs = it },
                        valueRange = 2f..22f,
                        colors = SliderDefaults.colors(
                            thumbColor = extra.bilibiliBlue,
                            activeTrackColor = extra.bilibiliBlue,
                            inactiveTrackColor = extra.border
                        )
                    )
                }
            }
        }

        // ==================== 状态日志 ====================
        if (linkState.status.isNotBlank()) {
            Card(
                colors = CardDefaults.cardColors(containerColor = extra.panelBackground),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(14.dp)
                ) {
                    Text("状态日志", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = extra.primaryText)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        linkState.status,
                        fontSize = 12.sp,
                        color = if (linkState.status.contains("失败") || linkState.status.contains("异常"))
                            extra.error else extra.success
                    )
                    if (linkState.debugInfo.isNotBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            linkState.debugInfo,
                            fontSize = 10.sp,
                            color = extra.mutedText
                        )
                    }
                }
            }
        }
    }
}