package com.example.hrhostclone.backend

import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.net.*
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToInt
import android.util.Log

data class StreamStats(
    val connected: Boolean = false,
    val protocol: String = "UDP",
    val receiveFps: Int = 0,
    val bytesPerSec: Long = 0,
    val latencyMs: Int = 0
)

object ReceiverEngine {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var receiverJob: Job? = null

    @Volatile private var useUdp = true
    @Volatile private var running = false
    @Volatile private var listenPort = 7878
    @Volatile private var connected = false
    @Volatile private var receiveFpsLimit = 240

    private val latestFrame = AtomicReference<ByteArray?>(null)
    private val packetCounter = AtomicLong(0)
    private val byteCounter = AtomicLong(0)
    private val pps = AtomicLong(0)
    private val bps = AtomicLong(0)
    @Volatile private var lastAcceptedFrameMs = 0L
    @Volatile private var avgFrameIntervalMs = 0f
    @Volatile private var lastTickMs = System.currentTimeMillis()

    private var udpSocket: DatagramSocket? = null
    private var tcpServerSocket: ServerSocket? = null
    private var tcpClientSocket: Socket? = null

    private var limitWindowStartMs = System.currentTimeMillis()
    private var limitWindowCount = 0

    fun start(port: Int, isUdp: Boolean) {
        val normalized = port.coerceIn(1, 65535)
        val needRestart = !running || normalized != listenPort || isUdp != useUdp
        listenPort = normalized
        useUdp = isUdp
        running = true
        if (needRestart) restart()
    }

    fun stop() {
        running = false
        connected = false
        receiverJob?.cancel()
        receiverJob = null
        closeSockets()
        latestFrame.set(null)
        packetCounter.set(0)
        byteCounter.set(0)
        pps.set(0)
        bps.set(0)
        resetLatencyStats()
    }

    fun snapshot(): StreamStats {
        tickStats()
        return StreamStats(
            connected = connected,
            protocol = if (useUdp) "UDP" else "TCP",
            receiveFps = pps.get().toInt(),
            bytesPerSec = bps.get(),
            latencyMs = estimateLatencyMs()
        )
    }

    fun peekFrame(): ByteArray? = latestFrame.get()

    private fun restart() {
        receiverJob?.cancel()
        closeSockets()
        resetLatencyStats()
        receiverJob = scope.launch {
            if (useUdp) receiveUdpLoop() else receiveTcpLoop()
        }
    }

    private fun receiveUdpLoop() {
        try {
            val socket = DatagramSocket(null).apply {
                reuseAddress = true
                soTimeout = 500
                bind(InetSocketAddress(listenPort))
            }
            udpSocket = socket
            connected = true
            val buf = ByteArray(65535)

            var lastReceiveTime = System.currentTimeMillis()  // ✅ 新增

            while (running && useUdp) {
                try {
                    val packet = DatagramPacket(buf, buf.size)
                    socket.receive(packet)

                    val now = System.currentTimeMillis()       // ✅ 新增
                    val gap = now - lastReceiveTime            // ✅ 新增
                    lastReceiveTime = now                      // ✅ 新增

                    if (gap > 100) {                           // ✅ 新增：间隔超过 100ms 就打印
                        Log.w("ReceiverEngine", "接收间隔: ${gap}ms, 帧大小: ${packet.length}")
                    }

                    if (packet.length > 0 && shouldAcceptFrame()) {
                        latestFrame.set(packet.data.copyOf(packet.length))
                        packetCounter.incrementAndGet()
                        byteCounter.addAndGet(packet.length.toLong())
                        noteFrameAccepted()
                    }
                    tickStats()
                } catch (_: SocketTimeoutException) {
                    tickStats()
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {
        } finally {
            connected = false
            closeSockets()
        }
    }


    private fun receiveTcpLoop() {
        try {
            val server = ServerSocket().apply {
                reuseAddress = true
                soTimeout = 500
                bind(InetSocketAddress(listenPort))
            }
            tcpServerSocket = server

            while (running && !useUdp) {
                try {
                    connected = false
                    val client = server.accept().apply { soTimeout = 500 }
                    tcpClientSocket = client
                    connected = true
                    readMjpegFrames(client)
                } catch (_: SocketTimeoutException) {
                    tickStats()
                } catch (_: Exception) {
                } finally {
                    connected = false
                    runCatching { tcpClientSocket?.close() }
                    tcpClientSocket = null
                }
            }
        } catch (_: Exception) {
        } finally {
            connected = false
            closeSockets()
        }
    }

    private fun readMjpegFrames(client: Socket) {
        val input = client.getInputStream()
        val readBuf = ByteArray(16 * 1024)
        val cache = ByteArrayOutputStream(128 * 1024)
        val SOI = byteArrayOf(0xFF.toByte(), 0xD8.toByte())
        val EOI = byteArrayOf(0xFF.toByte(), 0xD9.toByte())

        while (running && !useUdp) {
            val n = try {
                input.read(readBuf)
            } catch (_: SocketTimeoutException) {
                tickStats(); continue
            } catch (_: Exception) {
                break
            }
            if (n <= 0) break

            cache.write(readBuf, 0, n)
            val bytes = cache.toByteArray()
            var start = indexOf(bytes, SOI, 0)
            if (start < 0) {
                if (bytes.size > 2 * 1024 * 1024) cache.reset()
                continue
            }

            val end = indexOf(bytes, EOI, start + 2)
            if (end >= 0) {
                val frame = bytes.copyOfRange(start, end + 2)
                if (shouldAcceptFrame()) {
                    latestFrame.set(frame)
                    packetCounter.incrementAndGet()
                    byteCounter.addAndGet(frame.size.toLong())
                    noteFrameAccepted()
                }
                cache.reset()
                tickStats()
            }
        }
    }

    private fun indexOf(data: ByteArray, pattern: ByteArray, from: Int): Int {
        if (pattern.isEmpty() || data.size < pattern.size) return -1
        var i = from.coerceAtLeast(0)
        val last = data.size - pattern.size
        while (i <= last) {
            var ok = true
            for (j in pattern.indices) {
                if (data[i + j] != pattern[j]) { ok = false; break }
            }
            if (ok) return i
            i++
        }
        return -1
    }

    private fun tickStats() {
        val now = System.currentTimeMillis()
        if (now - lastTickMs >= 1000L) {
            pps.set(packetCounter.getAndSet(0))
            bps.set(byteCounter.getAndSet(0))
            lastTickMs = now
        }
    }

    private fun shouldAcceptFrame(): Boolean {
        val now = System.currentTimeMillis()
        if (now - limitWindowStartMs >= 1000L) {
            limitWindowStartMs = now
            limitWindowCount = 0
        }
        if (limitWindowCount >= receiveFpsLimit) return false
        limitWindowCount++
        return true
    }

    private fun noteFrameAccepted() {
        val now = System.currentTimeMillis()
        val prev = lastAcceptedFrameMs
        if (prev > 0L) {
            val interval = (now - prev).coerceAtLeast(1L).toFloat()
            avgFrameIntervalMs = if (avgFrameIntervalMs <= 0f) interval else avgFrameIntervalMs * 0.82f + interval * 0.18f
        }
        lastAcceptedFrameMs = now
    }

    private fun estimateLatencyMs(): Int {
        if (!connected) return 0
        val last = lastAcceptedFrameMs
        if (last <= 0L) return 0
        val frameAge = (System.currentTimeMillis() - last).coerceAtLeast(0L).coerceAtMost(2000L).toInt()
        val interval = avgFrameIntervalMs.roundToInt().coerceAtLeast(0)
        return maxOf(frameAge, interval).coerceAtLeast(1)
    }

    private fun resetLatencyStats() {
        lastAcceptedFrameMs = 0L
        avgFrameIntervalMs = 0f
    }

    private fun closeSockets() {
        runCatching { udpSocket?.close() }
        runCatching { tcpClientSocket?.close() }
        runCatching { tcpServerSocket?.close() }
        udpSocket = null
        tcpClientSocket = null
        tcpServerSocket = null
    }
}