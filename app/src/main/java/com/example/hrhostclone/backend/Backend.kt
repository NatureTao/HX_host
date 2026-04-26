package com.example.hrhostclone.backend

import android.hardware.usb.*
import com.example.hrhostclone.core.ColorModeState
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.ByteArrayOutputStream
import java.net.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.Charset
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

// ==================== 公开枚举 ====================

enum class InputBackend(val label: String) {
    MakcuUsb("MAKCU USB"),
    KmboxNet("KMBOX NET")
}

// ==================== 公开常量 ====================

const val KMBOX_NET_DEFAULT_PORT = 6234
const val KMBOX_NET_DEFAULT_MONITOR_PORT = 6235

// ==================== 公开数据类 ====================

data class KmboxNetConfig(
    val ip: String = "",
    val port: Int = KMBOX_NET_DEFAULT_PORT,
    val uuid: String = "",
    val monitorPort: Int = KMBOX_NET_DEFAULT_MONITOR_PORT
)

data class UsbSendResult(val success: Boolean, val message: String)

data class MakcuLinkState(
    val connected: Boolean = false,
    val deviceId: Int = -1,
    val rawButtonMask: Int = 0,
    val buttonMask: Int = 0,
    val debugInfo: String = "",
    val status: String = "未连接"
)

// ==================== 内部常量 ====================

private const val MAKCU_VENDOR_ID = 0x1A86
private const val MAKCU_PID_CH343 = 0x55D3
private const val MAKCU_SERIAL_BAUD = 4_000_000
private const val MAKCU_FALLBACK_BAUD = 115_200
private const val USB_TEST_COMMAND_TIMEOUT_MS = 2000

private const val KMBOX_NET_CONNECT_TIMEOUT_MS = 700
private const val KMBOX_NET_MONITOR_TIMEOUT_MS = 300
private const val KMBOX_NET_CMD_CONNECT = 0xAF3C2828.toInt()
private const val KMBOX_NET_CMD_MOUSE_MOVE = 0xAEDE7345.toInt()
private const val KMBOX_NET_CMD_MOUSE_LEFT = 0x9823AE8D.toInt()
private const val KMBOX_NET_CMD_MOUSE_RIGHT = 0x238D8212.toInt()
private const val KMBOX_NET_CMD_MOUSE_MIDDLE = 0x97A3AE8D.toInt()
private const val KMBOX_NET_CMD_MONITOR = 0x27388020.toInt()
private const val KMBOX_NET_MONITOR_MAGIC = 0xAA550000.toInt()

private const val MAKCU_USB_READ_TIMEOUT_MS = 24
private const val MAKCU_BUTTON_STREAM_PERIOD_MS = 4

private fun sanitizeRawMouseButtonMask(rawMask: Int): Int = rawMask and 0x1F

private fun normalizeMouseButtonMask(rawMask: Int): Int = rawMask and 0x1F

// ==================== 安全回退配置 ====================
object HoldSafetyConfig {
    @Volatile var enableStuckHoldRecovery = true   // 默认启用
    @Volatile var recoveryTimeoutMs: Long = 3_500L // 3.5秒
}

// ==================== 输入后端运行时 ====================

object InputBackendRuntime {
    @Volatile var selectedBackend: InputBackend = InputBackend.MakcuUsb

    fun currentButtonMask(): Int {
        return when (selectedBackend) {
            InputBackend.MakcuUsb -> MakcuLinkRuntime.state.value.buttonMask
            InputBackend.KmboxNet -> KmboxNetRuntime.state.value.buttonMask
        } and 0x1F
    }

    fun isConnected(): Boolean {
        return when (selectedBackend) {
            InputBackend.MakcuUsb -> MakcuLinkRuntime.state.value.connected
            InputBackend.KmboxNet -> KmboxNetRuntime.state.value.connected
        }
    }
}

private object MakcuButtonsParser {
    fun parse(
        state: MakcuButtonsParseState,
        buffer: ByteArray,
        count: Int,
        options: MakcuButtonsParserOptions
    ): MakcuButtonsParseResult? {
        if (count > 0) {
            state.pendingBytes.write(buffer, 0, count)
        }
        val data = state.pendingBytes.toByteArray()
        if (data.isEmpty()) return null

        // 简单解析：找 "buttons(" 后面的数字
        val text = data.toString(Charsets.ISO_8859_1)
        val pattern = Regex("""buttons?[=:(]?\s*(\d+)""", RegexOption.IGNORE_CASE)
        val match = pattern.find(text)

        if (match != null) {
            val mask = match.groupValues[1].toIntOrNull() ?: return null
            val consumed = match.range.last + 1
            trimPending(state, data, consumed)
            return MakcuButtonsParseResult(mask and 0xFFFF, "text", data.copyOf())
        }

        if (data.size > 64) {
            trimPending(state, data, data.size - 64)
        }
        return null
    }

    private fun trimPending(state: MakcuButtonsParseState, data: ByteArray, consumed: Int) {
        val safe = consumed.coerceIn(0, data.size)
        state.pendingBytes.reset()
        if (safe < data.size) {
            state.pendingBytes.write(data, safe, data.size - safe)
        }
    }
}

private data class MakcuButtonsParseResult(
    val mask: Int,
    val source: String,
    val dataPreview: ByteArray
)

// ==================== MAKCU 状态 ====================

object MakcuLinkRuntime {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sessionRef = AtomicReference<MakcuUsbSession?>(null)
    private var sessionJob: Job? = null
    private val _state = MutableStateFlow(MakcuLinkState())
    val state: StateFlow<MakcuLinkState> = _state.asStateFlow()

    fun session(): MakcuUsbSession? = sessionRef.get()

    fun updateStatus(status: String) {
        _state.value = _state.value.copy(status = status)
    }

    fun connect(usbManager: UsbManager, device: UsbDevice) {
        disconnect()
        _state.value = _state.value.copy(status = "正在连接 MAKCU...", deviceId = device.deviceId)
        sessionJob = scope.launch {
            try {
                val session = openMakcuUsbSession(usbManager, device)
                if (session != null) {
                    sessionRef.set(session)
                    RuntimeBridge.bindSession(session)
                    _state.value = _state.value.copy(connected = true, status = "MAKCU 已连接")

                    // ========== 安全回退：监控循环 ==========
                    var idleReads = 0
                    var lastMaskUpdateMs = System.currentTimeMillis()

                    while (isActive && sessionRef.get() != null) {
                        val stateSnapshot = _state.value
                        val holding = ((stateSnapshot.rawButtonMask or stateSnapshot.buttonMask) and 0xFFFF) != 0

                        var mask = session.readButtonsMask(MAKCU_USB_READ_TIMEOUT_MS)

                        if (mask == null) {
                            idleReads++

                            // ========== 安全回退检查 ==========
                            if (holding &&
                                HoldSafetyConfig.enableStuckHoldRecovery &&
                                System.currentTimeMillis() - lastMaskUpdateMs >= HoldSafetyConfig.recoveryTimeoutMs
                            ) {
                                lastMaskUpdateMs = System.currentTimeMillis()
                                _state.value = _state.value.copy(
                                    rawButtonMask = 0,
                                    buttonMask = 0,
                                    debugInfo = session.debugSummary(),
                                    status = "安全回退：疑似卡住长按，已清零并重启按键流"
                                )
                                // 重启按键流
                                session.sendCommandExact("km.buttons(2,$MAKCU_BUTTON_STREAM_PERIOD_MS)")
                                session.sendCommandExact("buttons(2,$MAKCU_BUTTON_STREAM_PERIOD_MS)")
                                idleReads = 0
                            }

                            delay(if (holding) 2 else 5)
                            continue
                        }

                        idleReads = 0
                        lastMaskUpdateMs = System.currentTimeMillis()

                        val rawMask = sanitizeRawMouseButtonMask(mask)
                        val mappedMask = normalizeMouseButtonMask(rawMask)

                        _state.value = _state.value.copy(
                            rawButtonMask = rawMask,
                            buttonMask = mappedMask,
                            debugInfo = session.debugSummary()
                        )
                        // 更新自动瞄准的触发按键状态
                        ColorModeState.isTriggerPressed =
                            (rawMask and ColorModeState.triggerButtonMask) != 0
                        delay(2)
                    }
                } else {
                    _state.value = _state.value.copy(connected = false, status = "MAKCU 连接失败")
                }
            } catch (e: Exception) {
                _state.value = _state.value.copy(connected = false, status = "连接异常: ${e.message}")
            }
        }
    }

    fun disconnect(reason: String = "已断开") {
        sessionJob?.cancel()
        sessionJob = null
        sessionRef.getAndSet(null)?.close()
        RuntimeBridge.bindSession(null)
        _state.value = MakcuLinkState(status = reason)
    }

}

// ==================== KMBOX 状态 ====================

object KmboxNetRuntime {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sessionRef = AtomicReference<KmboxNetSession?>(null)
    private var sessionJob: Job? = null
    private val _state = MutableStateFlow(MakcuLinkState(status = "未连接"))
    val state: StateFlow<MakcuLinkState> = _state.asStateFlow()

    fun session(): KmboxNetSession? = sessionRef.get()

    fun updateStatus(status: String) {
        _state.value = _state.value.copy(status = status)
    }

    fun connect(config: KmboxNetConfig) {
        disconnect()
        _state.value = MakcuLinkState(status = "正在连接 KMBOX NET...")
        sessionJob = scope.launch {
            var opened: KmboxNetSession? = null
            var lastMaskUpdateMs = System.currentTimeMillis()
            var timeoutCount = 0

            try {
                opened = KmboxNetSession.connect(config)
                sessionRef.set(opened)
                RuntimeBridge.bindKmboxSession(opened)
                opened.startMonitor()

                _state.value = _state.value.copy(
                    connected = true,
                    status = "KMBOX NET 已连接 ${opened.ip}:${opened.port}",
                    debugInfo = opened.debugSummary()
                )

                while (isActive) {
                    val event = opened.readMonitorEvent()

                    if (event == null) {
                        timeoutCount++

                        // 定期更新调试信息
                        if (timeoutCount % 6 == 0) {
                            _state.value = _state.value.copy(debugInfo = opened.debugSummary())
                        }

                        // ========== 安全回退检查 ==========
                        val holding = (_state.value.buttonMask and 0x1F) != 0
                        if (holding &&
                            HoldSafetyConfig.enableStuckHoldRecovery &&
                            System.currentTimeMillis() - lastMaskUpdateMs >= HoldSafetyConfig.recoveryTimeoutMs
                        ) {
                            lastMaskUpdateMs = System.currentTimeMillis()
                            _state.value = _state.value.copy(
                                rawButtonMask = 0,
                                buttonMask = 0,
                                debugInfo = opened.debugSummary(),
                                status = "安全回退：KMBOX 监控超时，已清零按键状态"
                            )
                        }

                        delay(10)
                        continue
                    }

                    timeoutCount = 0
                    lastMaskUpdateMs = System.currentTimeMillis()

                    val rawMask = event.rawButtons and 0x1F

                    _state.value = _state.value.copy(
                        connected = true,
                        rawButtonMask = rawMask,
                        buttonMask = rawMask,
                        debugInfo = opened.debugSummary()
                    )
                    ColorModeState.isTriggerPressed =
                        (rawMask and ColorModeState.triggerButtonMask) != 0
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (t: Throwable) {
                val detail = t.message?.take(120)?.ifBlank { null } ?: t.javaClass.simpleName
                _state.value = _state.value.copy(
                    connected = false,
                    rawButtonMask = 0,
                    buttonMask = 0,
                    debugInfo = "",
                    status = "KMBOX NET 连接失败: $detail"
                )
            } finally {
                val current = opened
                if (current != null && sessionRef.compareAndSet(current, null)) {
                    RuntimeBridge.bindKmboxSession(null)
                    current.close()
                }
                if (_state.value.connected) {
                    _state.value = _state.value.copy(connected = false, rawButtonMask = 0, buttonMask = 0, debugInfo = "")
                }
            }
        }
    }

    fun disconnect(reason: String = "已断开") {
        sessionJob?.cancel()
        sessionJob = null
        sessionRef.getAndSet(null)?.close()
        RuntimeBridge.bindKmboxSession(null)
        _state.value = MakcuLinkState(status = reason)
    }

}

// ==================== 运行时桥接 ====================

object RuntimeBridge {
    private val moveScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val moveQueue = Channel<Pair<Int, Int>>(Channel.CONFLATED)
    private val sessionRef = AtomicReference<MakcuUsbSession?>(null)
    private val kmboxSessionRef = AtomicReference<KmboxNetSession?>(null)

    init {
        moveScope.launch {
            while (isActive) {
                val (dx, dy) = moveQueue.receive()
                if (dx == 0 && dy == 0) continue
                when (InputBackendRuntime.selectedBackend) {
                    InputBackend.KmboxNet -> kmboxSessionRef.get()?.sendMove(dx, dy)
                    InputBackend.MakcuUsb -> sessionRef.get()?.sendMoveSmart(dx, dy)
                }
            }
        }
    }

    fun bindSession(session: MakcuUsbSession?) { sessionRef.set(session) }
    fun bindKmboxSession(session: KmboxNetSession?) { kmboxSessionRef.set(session) }

    fun sendMove(dx: Int, dy: Int) {
        if (dx == 0 && dy == 0) return
        if (!InputBackendRuntime.isConnected()) return
        moveQueue.trySend(dx to dy)
    }

    suspend fun sendMouseButton(buttonMask: Int, pressed: Boolean): UsbSendResult {
        return when (InputBackendRuntime.selectedBackend) {
            InputBackend.KmboxNet -> kmboxSessionRef.get()?.sendMouseButton(buttonMask, pressed)
                ?: UsbSendResult(false, "KMBOX 未连接")
            InputBackend.MakcuUsb -> sessionRef.get()?.sendMouseButton(buttonMask, pressed)
                ?: UsbSendResult(false, "MAKCU 未连接")
        }
    }
}
// ==================== 按键监控 ===========================
private data class MakcuButtonsParseState(
    val pendingBytes: ByteArrayOutputStream = ByteArrayOutputStream(),
    var snapshotMaskCache: Int = 0
)

private data class MakcuButtonsParserOptions(
    val allowBareButtonsToken: Boolean,
    val strictBinaryZeroGuard: Boolean
)

// ==================== MAKCU USB 会话 ====================
class MakcuUsbSession(
    private val connection: UsbDeviceConnection,
    private val outEndpoint: UsbEndpoint,
    private val inEndpoint: UsbEndpoint?,
    val baudRate: Int
) {
    private val writeLock = Mutex()
    private val parseLock = Any()
    private val parserState = MakcuButtonsParseState()

    @Volatile private var debugLastHex: String = "--"
    @Volatile private var debugLastParser: String = "init"
    @Volatile private var debugLastMask: Int = -1
    @Volatile private var debugLastCount: Int = 0

    suspend fun sendMoveSmart(dx: Int, dy: Int): UsbSendResult {
        val moveCmd = "move($dx,$dy)"
        return sendCommand(moveCmd)
    }

    suspend fun sendMouseButton(buttonMask: Int, pressed: Boolean): UsbSendResult {
        val aliases = when (buttonMask and 0x1F) {
            0x01 -> listOf("left", "lbutton")
            0x02 -> listOf("right", "rbutton")
            0x04 -> listOf("middle", "mbutton")
            0x08 -> listOf("side1", "x1", "xbutton1")
            0x10 -> listOf("side2", "x2", "xbutton2")
            else -> return UsbSendResult(false, "不支持的按键")
        }
        val value = if (pressed) 1 else 0
        aliases.forEach { alias ->
            val result = sendCommand("$alias($value)")
            if (result.success) return result
        }
        return UsbSendResult(false, "按键写入失败")
    }

    suspend fun sendCommandExact(cmd: String): UsbSendResult {
        return sendCommand(cmd)
    }

    private suspend fun sendCommand(cmd: String): UsbSendResult {
        return writeLock.withLock {
            try {
                val payload = "$cmd\r\n".toByteArray(Charsets.US_ASCII)
                val written = connection.bulkTransfer(outEndpoint, payload, payload.size, USB_TEST_COMMAND_TIMEOUT_MS)
                if (written == payload.size) UsbSendResult(true, cmd) else UsbSendResult(false, "写入失败")
            } catch (e: Exception) {
                UsbSendResult(false, e.message ?: "未知错误")
            }
        }
    }

    suspend fun readButtonsMask(timeoutMs: Int): Int? {
        val endpoint = inEndpoint ?: return null
        return writeLock.withLock {
            val readBuffer = ByteArray(256)
            val count = connection.bulkTransfer(endpoint, readBuffer, readBuffer.size, timeoutMs)

            synchronized(parseLock) {
                if (count > 0) {
                    updateDebugRx(readBuffer, count)
                } else {
                    debugLastCount = 0
                    if (debugLastParser == "init" || debugLastParser == "none") {
                        debugLastParser = "timeout"
                    }
                }
            }

            if (count <= 0) return@withLock null
            parseButtonsMask(readBuffer, count)
        }
    }

    private fun parseButtonsMask(buffer: ByteArray, count: Int): Int? {
        synchronized(parseLock) {
            val result = MakcuButtonsParser.parse(
                state = parserState,
                buffer = buffer,
                count = count,
                options = MakcuButtonsParserOptions(
                    allowBareButtonsToken = false,
                    strictBinaryZeroGuard = false
                )
            )
            if (result != null) {
                updateDebugParse(result.source, result.mask, result.dataPreview)
                return result.mask
            }
            updateDebugParse("none", null, parserState.pendingBytes.toByteArray())
            return null
        }
    }

    fun debugSummary(): String {
        val maskText = if (debugLastMask >= 0) {
            "0x${(debugLastMask and 0xFFFF).toString(16).uppercase()}"
        } else {
            "--"
        }
        return "USB c=$debugLastCount src=$debugLastParser m=$maskText rx=$debugLastHex"
    }

    fun canReadButtons(): Boolean = inEndpoint != null

    fun close() {
        runCatching { connection.close() }
    }

    private fun updateDebugRx(buffer: ByteArray, count: Int) {
        debugLastCount = count.coerceAtLeast(0)
        if (count > 0) {
            debugLastHex = hexPreview(buffer, count)
        }
    }

    private fun updateDebugParse(source: String, mask: Int?, data: ByteArray) {
        debugLastParser = source
        debugLastMask = mask ?: -1
        debugLastHex = hexPreview(data, data.size)
    }

    private fun hexPreview(buffer: ByteArray, count: Int, maxBytes: Int = 24): String {
        if (count <= 0) return "--"
        val end = count.coerceAtMost(buffer.size)
        val start = (end - maxBytes).coerceAtLeast(0)
        val sb = StringBuilder()
        for (i in start until end) {
            if (sb.isNotEmpty()) sb.append(' ')
            val v = buffer[i].toInt() and 0xFF
            if (v < 0x10) sb.append('0')
            sb.append(v.toString(16).uppercase())
        }
        return sb.toString()
    }
}


private fun openMakcuUsbSession(usbManager: UsbManager, device: UsbDevice): MakcuUsbSession? {
    if (!usbManager.hasPermission(device)) return null

    val connection = usbManager.openDevice(device) ?: return null

    for (i in 0 until device.interfaceCount) {
        val intf = device.getInterface(i)
        if (!connection.claimInterface(intf, true)) continue

        for (j in 0 until intf.endpointCount) {
            val ep = intf.getEndpoint(j)
            if (ep.direction == UsbConstants.USB_DIR_OUT && ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                val inEp = (0 until intf.endpointCount)
                    .map { intf.getEndpoint(it) }
                    .firstOrNull { it.direction == UsbConstants.USB_DIR_IN && it.type == UsbConstants.USB_ENDPOINT_XFER_BULK }
                return MakcuUsbSession(connection, ep, inEp, MAKCU_SERIAL_BAUD)
            }
        }
    }
    connection.close()
    return null
}

// ==================== KMBOX NET 会话 ====================

class KmboxNetSession private constructor(
    private val commandSocket: DatagramSocket,
    private val monitorSocket: DatagramSocket,
    private val targetAddress: InetSocketAddress,
    private val mac: Int,
    private val rand: Int,
    val ip: String,
    val port: Int,
    val uuid: String
) {
    private val sendLock = Any()
    private var indexpts: Int = 0

    @Volatile private var lastCommandLabel: String = "connect"
    @Volatile private var lastMonitorPreview: String = ""
    @Volatile private var lastMonitorAtMs: Long = System.currentTimeMillis()

    companion object {
        fun connect(config: KmboxNetConfig): KmboxNetSession {
            val normalizedIp = config.ip.trim()
            require(normalizedIp.isNotBlank()) { "IP 不能为空" }
            val mac = normalizeKmboxUuid(config.uuid).toLong(16).toInt()

            val commandSocket = DatagramSocket(null).apply {
                soTimeout = KMBOX_NET_CONNECT_TIMEOUT_MS
                connect(InetSocketAddress(normalizedIp, config.port))
            }
            val monitorSocket = DatagramSocket(null).apply {
                soTimeout = KMBOX_NET_MONITOR_TIMEOUT_MS
                reuseAddress = true
                bind(InetSocketAddress(config.monitorPort))
            }

            try {
                val target = InetSocketAddress(normalizedIp, config.port)
                val connectPacket = buildKmboxHeader(mac, 0, 0, KMBOX_NET_CMD_CONNECT)
                commandSocket.send(DatagramPacket(connectPacket, connectPacket.size, target))

                val response = ByteArray(64)
                val reply = DatagramPacket(response, response.size)
                commandSocket.receive(reply)
                val rand = ByteBuffer.wrap(response, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int

                return KmboxNetSession(commandSocket, monitorSocket, target, mac, rand, normalizedIp, config.port, config.uuid)
            } catch (e: Exception) {
                commandSocket.close()
                monitorSocket.close()
                throw e
            }
        }
    }

    fun sendMove(dx: Int, dy: Int): UsbSendResult {
        val payload = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(0).putInt(dx).putInt(dy).putInt(0).array()
        return sendPacket(KMBOX_NET_CMD_MOUSE_MOVE, payload, "move($dx,$dy)")
    }

    fun sendMouseButton(buttonMask: Int, pressed: Boolean): UsbSendResult {
        val cmd = when (buttonMask and 0x1F) {
            0x01 -> KMBOX_NET_CMD_MOUSE_LEFT
            0x02 -> KMBOX_NET_CMD_MOUSE_RIGHT
            0x04 -> KMBOX_NET_CMD_MOUSE_MIDDLE
            else -> return UsbSendResult(false, "不支持的按键")
        }
        val payload = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(if (pressed) 1 else 0).array()
        return sendPacket(cmd, payload, "button(${buttonMask and 0x1F},$pressed)")
    }

    fun startMonitor(): UsbSendResult {
        val monitorRand = KMBOX_NET_MONITOR_MAGIC or (monitorSocket.localPort and 0xFFFF)
        return sendPacket(KMBOX_NET_CMD_MONITOR, byteArrayOf(), "monitor", monitorRand)
    }

    fun readMonitorEvent(timeoutMs: Int = KMBOX_NET_MONITOR_TIMEOUT_MS): KmboxMonitorEvent? {
        monitorSocket.soTimeout = timeoutMs.coerceAtLeast(1)
        val buffer = ByteArray(32)
        val packet = DatagramPacket(buffer, buffer.size)
        return try {
            monitorSocket.receive(packet)
            val data = packet.data.copyOf(packet.length)
            if (data.size < 6) return null

            val buttons = (((data[1].toInt() and 0xFF) shl 8) or (data[0].toInt() and 0xFF))
            val dx = (((data[3].toInt() and 0xFF) shl 8) or (data[2].toInt() and 0xFF)).toShort().toInt()
            val dy = (((data[5].toInt() and 0xFF) shl 8) or (data[4].toInt() and 0xFF)).toShort().toInt()
            val wheel = if (data.size >= 8) {
                (((data[7].toInt() and 0xFF) shl 8) or (data[6].toInt() and 0xFF)).toShort().toInt()
            } else {
                0
            }

            lastMonitorAtMs = System.currentTimeMillis()
            lastMonitorPreview = "btn=0x${((buttons ushr 8) and 0x1F).toString(16).uppercase()} dx=$dx dy=$dy wh=$wheel"

            KmboxMonitorEvent(
                rawButtons = (buttons ushr 8) and 0x1F,
                dx = dx,
                dy = dy,
                wheel = wheel
            )
        } catch (_: SocketTimeoutException) {
            null
        }
    }

    fun debugSummary(): String {
        val ageMs = (System.currentTimeMillis() - lastMonitorAtMs).coerceAtLeast(0L)
        return "KMBOX $ip:$port uuid=$uuid idx=$indexpts cmd=$lastCommandLabel rx=${ageMs}ms ${lastMonitorPreview.ifBlank { "waiting" }}"
    }

    fun close() {
        runCatching { commandSocket.close() }
        runCatching { monitorSocket.close() }
    }

    private fun sendPacket(
        cmd: Int,
        payload: ByteArray,
        label: String,
        customRand: Int? = null
    ): UsbSendResult {
        return synchronized(sendLock) {
            runCatching {
                val packet = buildKmboxPacket(
                    mac = mac,
                    rand = customRand ?: rand,
                    indexpts = indexpts,
                    cmd = cmd,
                    payload = payload
                )
                commandSocket.send(DatagramPacket(packet, packet.size, targetAddress))
                lastCommandLabel = label
                indexpts++
                UsbSendResult(true, label)
            }.getOrElse { t ->
                val detail = t.message?.take(80)?.ifBlank { null } ?: t.javaClass.simpleName
                UsbSendResult(false, detail)
            }
        }
    }
}

// ==================== 监控事件数据类 ====================
data class KmboxMonitorEvent(
    val rawButtons: Int,
    val dx: Int,
    val dy: Int,
    val wheel: Int
)


private fun buildKmboxHeader(mac: Int, rand: Int, indexpts: Int, cmd: Int): ByteArray {
    return ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
        .putInt(mac).putInt(rand).putInt(indexpts).putInt(cmd).array()
}

private fun buildKmboxPacket(mac: Int, rand: Int, indexpts: Int, cmd: Int, payload: ByteArray): ByteArray {
    val header = buildKmboxHeader(mac, rand, indexpts, cmd)
    return header + payload
}

private fun normalizeKmboxUuid(raw: String): String {
    return raw.trim().removePrefix("0x").removePrefix("0X").replace(" ", "").uppercase().take(8)
        .padEnd(8, '0')
}

// ==================== 测试动作执行器 ====================

object DeviceTestActions {

    @Volatile
    var drawStepDelayMs: Long = 4L

    suspend fun drawSquare(backend: InputBackend) {
        val moves = generateSquareMoves()
        executeMoves(backend, moves, "正方形")
    }

    suspend fun drawCircle(backend: InputBackend) {
        val moves = generateCircleMoves()
        executeMoves(backend, moves, "圆形")
    }

    private suspend fun executeMoves(
        backend: InputBackend,
        moves: List<Pair<Int, Int>>,
        patternName: String
    ) {
        var stepIndex = 0
        for ((dx, dy) in moves) {
            stepIndex++

            val result = when (backend) {
                InputBackend.MakcuUsb -> {
                    MakcuLinkRuntime.session()?.sendMoveSmart(dx, dy)
                        ?: UsbSendResult(false, "MAKCU 会话为空")
                }
                InputBackend.KmboxNet -> {
                    KmboxNetRuntime.session()?.sendMove(dx, dy)
                        ?: UsbSendResult(false, "KMBOX 会话为空")
                }
            }

            if (!result.success) {
                val status = "[$patternName] 第 $stepIndex/${moves.size} 步失败: ${result.message}"
                when (backend) {
                    InputBackend.MakcuUsb -> MakcuLinkRuntime.updateStatus(status)
                    InputBackend.KmboxNet -> KmboxNetRuntime.updateStatus(status)
                }
                return
            }

            delay(drawStepDelayMs)
        }

        val successStatus = "[$patternName] 完成 (${moves.size} 步)"
        when (backend) {
            InputBackend.MakcuUsb -> MakcuLinkRuntime.updateStatus(successStatus)
            InputBackend.KmboxNet -> KmboxNetRuntime.updateStatus(successStatus)
        }
    }

    private fun generateSquareMoves(
        side: Int = 240,
        stepsPerEdge: Int = 12
    ): List<Pair<Int, Int>> {
        val step = (side / stepsPerEdge.toFloat()).roundToInt().coerceAtLeast(1)
        return buildList {
            repeat(stepsPerEdge) { add(step to 0) }
            repeat(stepsPerEdge) { add(0 to step) }
            repeat(stepsPerEdge) { add(-step to 0) }
            repeat(stepsPerEdge) { add(0 to -step) }
        }
    }

    private fun generateCircleMoves(
        radius: Int = 120,
        segments: Int = 48
    ): List<Pair<Int, Int>> {
        var prevX = radius
        var prevY = 0
        return buildList {
            for (i in 1..segments) {
                val angle = 2.0 * Math.PI * i / segments
                val x = (radius * cos(angle)).roundToInt()
                val y = (radius * sin(angle)).roundToInt()
                val dx = x - prevX
                val dy = y - prevY
                if (dx != 0 || dy != 0) {
                    add(dx to dy)
                }
                prevX = x
                prevY = y
            }
        }
    }
}