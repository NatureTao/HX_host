package com.example.hrhostclone.core

// ==================== 数据类 ====================

data class ColorDetectionResult(
    val offsetX: Int = 0,
    val offsetY: Int = 0,
    val localTargetX: Int = 0,
    val localTargetY: Int = 0,
    val boxLeft: Int = 0,
    val boxTop: Int = 0,
    val boxWidth: Int = 0,
    val boxHeight: Int = 0,
    val area: Int = 0,
    val distanceToCenter: Float = 0f,
    val score: Float = 0f,
    val fillRatio: Float = 0f,
    val detectMethod: String = "OpenCV"
)

/** 单个颜色预设 */
data class ColorPreset(
    val name: String,
    val hMin: Int, val sMin: Int, val vMin: Int,
    val hMax: Int, val sMax: Int, val vMax: Int
)

// ==================== 全局状态 ====================

object ColorModeState {

    // ==================== 颜色预设 ====================

    /** 已保存的颜色预设列表 */
    @Volatile var colorPresets: List<ColorPreset> = listOf(
        ColorPreset("OW 粉紫色", 140, 60, 150, 170, 255, 255),
    )

    /** 当前激活的颜色预设索引 */
    @Volatile var activePresetIndex: Int = 0

    /** 当前使用的 HSV 范围（从激活预设读取，也可手动覆盖） */
    @Volatile var currentHMin: Int = 140
    @Volatile var currentSMin: Int = 60
    @Volatile var currentVMin: Int = 150
    @Volatile var currentHMax: Int = 170
    @Volatile var currentSMax: Int = 255
    @Volatile var currentVMax: Int = 255

    // ==================== 检测状态 ====================

    /** 当前检测方法：body / health_bar / none */
    @Volatile var detectMethod: String = "none"

    /** 最新检测结果（包含瞄准点、边界框等） */
    @Volatile var colorDetectionResult: ColorDetectionResult? = null

    /** 找色功能是否激活 */
    @Volatile var isColorAimActive = false

    /** 当前帧是否检测到目标 */
    @Volatile var isDetected = false

    /** 单次检测耗时（毫秒） */
    @Volatile var detectionTimeMs: Long = 0

    /** 检测帧率（FPS） */
    @Volatile var detectionFps: Int = 0

    // ==================== 显示控制 ====================

    /** 是否显示边界框 */
    @Volatile var showBoundingBox: Boolean = true

    /** 是否显示二值化掩码 */
    @Volatile var showMask: Boolean = false

    /** 是否显示预览画面 */
    @Volatile var showPreview: Boolean = true

    // ==================== 自动瞄准 ====================

    /** 自动瞄准总开关 */
    @Volatile var isAutoAimActive: Boolean = false

    /** 锁定队友（开启后不过滤队友图标） */
    @Volatile var lockTeammates: Boolean = false

    /** 瞄准模式：unibot（平滑移动） 或 quick（快速移动） */
    @Volatile var aimMode: String = "unibot"

    // ==================== 触发按键 ====================

    /**
     * 触发的鼠标按键掩码
     * 1 = 左键, 2 = 右键, 4 = 中键, 8 = 上侧键, 16 = 下侧键
     */
    @Volatile var triggerButtonMask: Int = 1

    /** 触发按键索引：0=左键, 1=右键, 2=中键, 3=上侧, 4=下侧 */
    @Volatile var triggerButtonIndex: Int = 0

    /** 触发按键当前是否被按下（由后端按键监控更新） */
    @Volatile var isTriggerPressed: Boolean = false

    // ==================== 移动速度 ====================

    /** 移动速度档位（1-10，越大越快） */
    @Volatile var aimSpeed: Float = 1.5f

    // ==================== 移动算法参数 ====================

    /**
     * 重力系数：鼠标被目标吸引的力度
     * 范围 5-20，越大移动越快、跟枪越紧
     */
    @Volatile var windGravity: Float = 10.0f

    /**
     * 风力系数：鼠标轨迹的随机弯曲程度
     * 范围 1-10，越大轨迹越弯曲、越像人手
     */
    @Volatile var windWind: Float = 6.0f

    /**
     * 最小风力：保证远距离时轨迹也有弯曲
     * 范围 0-5，避免远距离时轨迹太直
     */
    @Volatile var windMinWind: Float = 1.0f

    /**
     * 最大风力：限制随机扰动的上限
     * 范围 2-15，防止轨迹过于抖动
     */
    @Volatile var windMaxWind: Float = 8.0f


    // ==================== 死区 ====================

    /**
     * 死区半径（像素）
     * 目标在此范围内时不移动，防止准星在目标身上乱晃
     * 范围 0-30，越大越稳定但可能无法精确瞄准
     */
    @Volatile var deadZoneRadius: Float = 9f

    // ==================== 瞄准高度 ====================

    /**
     * 瞄准高度百分比（0-100，从顶部往下）
     * 0 = 头顶, 30 = 脖子（默认）, 50 = 身体中心, 90 = 脚部
     */
    @Volatile var aimHeightPercent: Float = 30f

    // ==================== 形态学参数 ====================

    /** 膨胀核大小（1-5，默认 2） */
    @Volatile var dilateSizeValue: Float = 2f

    /** 膨胀迭代次数（1-4，默认 2） */
    @Volatile var dilateIterationsValue: Int = 2

    /** 闭运算核大小（1-10，默认 5） */
    @Volatile var closeSizeValue: Float = 5f

    // ==================== 轮廓过滤参数 ====================

    /** 最小轮廓面积（10-500，默认 40） */
    @Volatile var minContourArea: Float = 100f

    /** 最大轮廓面积（1000-100000，默认 50000） */
    @Volatile var maxContourArea: Float = 50000f

    /** 最小轮廓宽度（3-30，默认 8） */
    @Volatile var minWidthValue: Int = 8

    /** 最小轮廓高度（5-50，默认 15） */
    @Volatile var minHeightValue: Int = 15

    /** 宽高比下限（0.1-1.0，默认 0.3） */
    @Volatile var minAspectRatio: Float = 0.3f

    /** 宽高比上限（1.0-5.0，默认 2.0） */
    @Volatile var maxAspectRatio: Float = 2.0f

    /** 填充率下限（0.1-0.9，默认 0.2） */
    @Volatile var minFillRatio: Float = 0.2f
}