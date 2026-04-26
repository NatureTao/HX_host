package com.example.hrhostclone

import android.content.Context
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Gamepad
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.sp
import com.example.hrhostclone.backend.AimMoveController
import com.example.hrhostclone.backend.InputBackend
import com.example.hrhostclone.backend.InputBackendRuntime
import com.example.hrhostclone.backend.KMBOX_NET_DEFAULT_MONITOR_PORT
import com.example.hrhostclone.backend.KMBOX_NET_DEFAULT_PORT
import com.example.hrhostclone.backend.ReceiverEngine
import com.example.hrhostclone.backend.StreamStats
import com.example.hrhostclone.core.ColorModeState
import com.example.hrhostclone.core.ColorPreset
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.json.JSONObject

// ==================== 应用常量 ====================

private const val APP_NAME = "HX HOST"
private const val APP_VERSION = "1.0.0"
private const val AUTO_CONFIG_PREFS = "hx_host_prefs"
private const val AUTO_CONFIG_KEY = "app_auto_config_json"

// ==================== 导航枚举 ====================

enum class AppTab {
    Monitoring, Facility, Setting
}

// ==================== MainActivity ====================

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (org.opencv.android.OpenCVLoader.initDebug()) {
            Log.i("OpenCV", "OpenCV 加载成功")
        } else {
            Log.e("OpenCV", "OpenCV 初始化失败")
        }
        setContent { MainApp() }
    }
}

// ==================== MainApp ====================

@Composable
fun MainApp() {
    val context = LocalContext.current
    val prefs = remember(context) {
        context.getSharedPreferences(AUTO_CONFIG_PREFS, Context.MODE_PRIVATE)
    }
    var autoConfigLoaded by remember { mutableStateOf(false) }

    // ==================== 导航 ====================
    var currentTab by rememberSaveable { mutableStateOf(AppTab.Monitoring) }

    // ==================== 画面传输 ====================
    var running by rememberSaveable { mutableStateOf(false) }
    var isUdp by rememberSaveable { mutableStateOf(true) }
    var udpPort by rememberSaveable { mutableStateOf("7878") }
    var tcpPort by rememberSaveable { mutableStateOf("7878") }

    // ==================== 输入后端 ====================
    var selectedInputBackend by rememberSaveable { mutableStateOf(InputBackend.MakcuUsb) }
    var kmboxIp by rememberSaveable { mutableStateOf("") }
    var kmboxPort by rememberSaveable { mutableStateOf(KMBOX_NET_DEFAULT_PORT.toString()) }
    var kmboxUuid by rememberSaveable { mutableStateOf("") }
    var kmboxMonitorPort by rememberSaveable { mutableStateOf(KMBOX_NET_DEFAULT_MONITOR_PORT.toString()) }

    // ==================== 主题 ====================
    var appThemeMode by rememberSaveable { mutableStateOf(AppThemeMode.Bilibili) }

    // ==================== 统计信息 ====================
    var stats by remember { mutableStateOf(StreamStats(protocol = if (isUdp) "UDP" else "TCP")) }

    // ==================== 工具函数 ====================

    /** 应用颜色预设到当前 HSV */
    fun applyPreset(preset: ColorPreset) {
        ColorModeState.currentHMin = preset.hMin
        ColorModeState.currentSMin = preset.sMin
        ColorModeState.currentVMin = preset.vMin
        ColorModeState.currentHMax = preset.hMax
        ColorModeState.currentSMax = preset.sMax
        ColorModeState.currentVMax = preset.vMax
    }

    // ==================== 配置持久化 ====================

    /** 构建配置 JSON */
    fun buildConfigJson(): JSONObject = JSONObject().apply {
        put("version", APP_VERSION)
        put("isUdp", isUdp)
        put("udpPort", udpPort.toIntOrNull() ?: 7878)
        put("tcpPort", tcpPort.toIntOrNull() ?: 7878)
        put("inputBackend", selectedInputBackend.label)
        put("kmboxIp", kmboxIp)
        put("kmboxPort", kmboxPort.toIntOrNull()?.coerceIn(1, 65535) ?: KMBOX_NET_DEFAULT_PORT)
        put("kmboxUuid", kmboxUuid.trim())
        put("kmboxMonitorPort", kmboxMonitorPort.toIntOrNull()?.coerceIn(1, 65535) ?: KMBOX_NET_DEFAULT_MONITOR_PORT)
        put("themeMode", appThemeMode.name)

        // 自动瞄准参数
        put("autoAimEnabled", ColorModeState.isAutoAimActive)
        put("triggerButtonIndex", ColorModeState.triggerButtonIndex)
        put("aimSpeed", ColorModeState.aimSpeed.toDouble())
        put("windGravity", ColorModeState.windGravity.toDouble())
        put("windWind", ColorModeState.windWind.toDouble())
        put("windMinWind", ColorModeState.windMinWind.toDouble())
        put("windMaxWind", ColorModeState.windMaxWind.toDouble())
        put("deadZoneRadius", ColorModeState.deadZoneRadius.toDouble())
        put("aimHeight", ColorModeState.aimHeightPercent.toDouble())
        put("lockTeammates", ColorModeState.lockTeammates)


        // 检测参数
        put("dilateSize", ColorModeState.dilateSizeValue.toDouble())
        put("dilateIterations", ColorModeState.dilateIterationsValue)
        put("closeSize", ColorModeState.closeSizeValue.toDouble())
        put("minArea", ColorModeState.minContourArea.toDouble())
        put("maxArea", ColorModeState.maxContourArea.toDouble())
        put("minWidth", ColorModeState.minWidthValue)
        put("minHeight", ColorModeState.minHeightValue)
        put("minAspect", ColorModeState.minAspectRatio.toDouble())
        put("maxAspect", ColorModeState.maxAspectRatio.toDouble())
        put("minFill", ColorModeState.minFillRatio.toDouble())

        // 颜色预设
        val presetsArray = org.json.JSONArray()
        ColorModeState.colorPresets.forEach { preset ->
            val obj = org.json.JSONObject()
            obj.put("name", preset.name)
            obj.put("hMin", preset.hMin); obj.put("sMin", preset.sMin); obj.put("vMin", preset.vMin)
            obj.put("hMax", preset.hMax); obj.put("sMax", preset.sMax); obj.put("vMax", preset.vMax)
            presetsArray.put(obj)
        }
        put("colorPresets", presetsArray)
        put("activePresetIndex", ColorModeState.activePresetIndex)
    }

    /** 立即同步保存配置到磁盘（防杀进程丢数据） */
    fun saveConfigNow() {
        val json = buildConfigJson().toString()
        prefs.edit().putString(AUTO_CONFIG_KEY, json).commit()
        Log.d("Config", "配置已保存")
    }

    /** 从 JSON 恢复配置 */
    fun applyConfig(root: JSONObject) {
        isUdp = root.optBoolean("isUdp", true)
        udpPort = root.optInt("udpPort", 7878).coerceIn(1, 65535).toString()
        tcpPort = root.optInt("tcpPort", 7878).coerceIn(1, 65535).toString()
        val backendLabel = root.optString("inputBackend", InputBackend.MakcuUsb.label)
        selectedInputBackend = InputBackend.entries.find { it.label == backendLabel } ?: InputBackend.MakcuUsb
        kmboxIp = root.optString("kmboxIp", "").trim()
        kmboxPort = root.optInt("kmboxPort", KMBOX_NET_DEFAULT_PORT).coerceIn(1, 65535).toString()
        kmboxUuid = root.optString("kmboxUuid", "").trim()
        kmboxMonitorPort = root.optInt("kmboxMonitorPort", KMBOX_NET_DEFAULT_MONITOR_PORT).coerceIn(1, 65535).toString()
        appThemeMode = runCatching {
            AppThemeMode.valueOf(root.optString("themeMode", AppThemeMode.Bilibili.name))
        }.getOrDefault(AppThemeMode.Bilibili)

        // 自动瞄准参数
        ColorModeState.isAutoAimActive = root.optBoolean("autoAimEnabled", false)
        ColorModeState.triggerButtonIndex = root.optInt("triggerButtonIndex", 0)
        ColorModeState.triggerButtonMask = when (ColorModeState.triggerButtonIndex) {
            0 -> 1; 1 -> 2; 2 -> 4; 3 -> 8; 4 -> 16; else -> 1
        }
        ColorModeState.aimSpeed = root.optDouble("aimSpeed", 4.0).toFloat()
        ColorModeState.windGravity = root.optDouble("windGravity", 10.0).toFloat()
        ColorModeState.windWind = root.optDouble("windWind", 5.0).toFloat()
        ColorModeState.windMinWind = root.optDouble("windMinWind", 1.0).toFloat()
        ColorModeState.windMaxWind = root.optDouble("windMaxWind", 5.0).toFloat()
        ColorModeState.deadZoneRadius = root.optDouble("deadZoneRadius", 9.0).toFloat()
        ColorModeState.aimHeightPercent = root.optDouble("aimHeight", 30.0).toFloat()
        ColorModeState.lockTeammates = root.optBoolean("lockTeammates", false)


        // 检测参数
        ColorModeState.dilateSizeValue = root.optDouble("dilateSize", 2.0).toFloat()
        ColorModeState.dilateIterationsValue = root.optInt("dilateIterations", 2)
        ColorModeState.closeSizeValue = root.optDouble("closeSize", 5.0).toFloat()
        ColorModeState.minContourArea = root.optDouble("minArea", 40.0).toFloat()
        ColorModeState.maxContourArea = root.optDouble("maxArea", 50000.0).toFloat()
        ColorModeState.minWidthValue = root.optInt("minWidth", 8)
        ColorModeState.minHeightValue = root.optInt("minHeight", 15)
        ColorModeState.minAspectRatio = root.optDouble("minAspect", 0.3).toFloat()
        ColorModeState.maxAspectRatio = root.optDouble("maxAspect", 2.0).toFloat()
        ColorModeState.minFillRatio = root.optDouble("minFill", 0.2).toFloat()

        // 颜色预设
        val presetsList = mutableListOf<ColorPreset>()
        root.optJSONArray("colorPresets")?.let { arr ->
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                presetsList.add(
                    ColorPreset(
                        name = obj.optString("name", "预设$i"),
                        hMin = obj.optInt("hMin", 140), sMin = obj.optInt("sMin", 60), vMin = obj.optInt("vMin", 150),
                        hMax = obj.optInt("hMax", 170), sMax = obj.optInt("sMax", 255), vMax = obj.optInt("vMax", 255)
                    )
                )
            }
        }
        if (presetsList.isNotEmpty()) ColorModeState.colorPresets = presetsList
        ColorModeState.activePresetIndex = root.optInt("activePresetIndex", 0)
        ColorModeState.colorPresets.getOrNull(ColorModeState.activePresetIndex)?.let { applyPreset(it) }
        Log.d("Config", "配置已加载: aimSpeed=${ColorModeState.aimSpeed}, aimHeight=${ColorModeState.aimHeightPercent}")
    }

    // 启动时加载配置
    LaunchedEffect(Unit) {
        val cached = prefs.getString(AUTO_CONFIG_KEY, null)
        if (!cached.isNullOrBlank()) {
            runCatching { JSONObject(cached) }.getOrNull()?.let { applyConfig(it) }
        }
        autoConfigLoaded = true
    }

    // 定时自动保存（每 3 秒检查一次，变化时同步写入磁盘）
    LaunchedEffect(autoConfigLoaded) {
        if (!autoConfigLoaded) return@LaunchedEffect
        delay(1000) // 等 UI 初始化完成，避免默认值覆盖已保存配置
        var lastJson = ""
        while (isActive) {
            val currentJson = buildConfigJson().toString()
            if (currentJson != lastJson) {
                prefs.edit().putString(AUTO_CONFIG_KEY, currentJson).commit()
                lastJson = currentJson
                Log.d("Config", "定时保存完成")
            }
            delay(3000)
        }
    }

    // 计算监听端口
    val activePort = if (isUdp) udpPort.toIntOrNull()?.coerceIn(1, 65535) ?: 7878
    else tcpPort.toIntOrNull()?.coerceIn(1, 65535) ?: 7878

    // 同步输入后端选择
    LaunchedEffect(selectedInputBackend) { InputBackendRuntime.selectedBackend = selectedInputBackend }

    // 控制视频流接收
    LaunchedEffect(running, isUdp, activePort) {
        if (running) ReceiverEngine.start(activePort, isUdp) else ReceiverEngine.stop()
    }

    // 定期更新统计信息
    LaunchedEffect(Unit) {
        while (isActive) { stats = ReceiverEngine.snapshot(); delay(250) }
    }

    // 启动移动控制器
    LaunchedEffect(Unit) { AimMoveController.start() }

    // 组件销毁时停止并保存
    DisposableEffect(Unit) {
        onDispose {
            saveConfigNow()
            AimMoveController.stop()
            ReceiverEngine.stop()
        }
    }

    // ==================== UI 布局 ====================
    AppTheme(themeMode = appThemeMode) {
        val extra = HXExtraTheme.colors
        Scaffold(
            containerColor = extra.pageBackground,
            bottomBar = {
                NavigationBar(containerColor = extra.navBarBackground) {
                    NavigationBarItem(selected = currentTab == AppTab.Monitoring, onClick = { currentTab = AppTab.Monitoring }, icon = { Icon(Icons.Outlined.Visibility, "监控") }, label = { Text("监控", fontSize = 10.sp) })
                    NavigationBarItem(selected = currentTab == AppTab.Facility, onClick = { currentTab = AppTab.Facility }, icon = { Icon(Icons.Outlined.Gamepad, "控制") }, label = { Text("控制", fontSize = 10.sp) })
                    NavigationBarItem(selected = currentTab == AppTab.Setting, onClick = { currentTab = AppTab.Setting }, icon = { Icon(Icons.Outlined.Settings, "设置") }, label = { Text("设置", fontSize = 10.sp) })
                }
            }
        ) { innerPadding ->
            Box(Modifier.fillMaxSize().padding(innerPadding).background(extra.pageBackground)) {
                when (currentTab) {
                    AppTab.Monitoring -> MonitoringScreen(
                        running = running, stats = stats, isUdp = isUdp, activePort = activePort,
                        onToggleRun = { running = it }, onProtocolChange = { isUdp = it },
                        onExportConfig = { buildConfigJson().toString() },
                        onSaveConfig = { saveConfigNow() },
                        onImportConfig = { json -> applyConfig(json); saveConfigNow() }
                    )
                    AppTab.Facility -> FacilityScreen(
                        selectedInputBackend = selectedInputBackend, kmboxIp = kmboxIp, kmboxPort = kmboxPort, kmboxUuid = kmboxUuid, kmboxMonitorPort = kmboxMonitorPort,
                        onSelectedInputBackendChange = { selectedInputBackend = it }, onKmboxIpChange = { kmboxIp = it.trim() }, onKmboxPortChange = { kmboxPort = it.filter(Char::isDigit) },
                        onKmboxUuidChange = { kmboxUuid = it.uppercase().filter { c -> c in '0'..'9' || c in 'A'..'F' }.take(8) }, onKmboxMonitorPortChange = { kmboxMonitorPort = it.filter(Char::isDigit) }
                    )
                    AppTab.Setting -> SettingScreen(currentThemeMode = appThemeMode, onThemeChange = { mode -> appThemeMode = mode })
                }
            }
        }
    }
}