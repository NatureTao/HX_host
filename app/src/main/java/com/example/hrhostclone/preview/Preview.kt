package com.example.hrhostclone.preview

import android.content.Context
import android.graphics.*
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import com.example.hrhostclone.backend.ReceiverEngine
import com.example.hrhostclone.core.ColorDetectionResult
import com.example.hrhostclone.core.ColorModeState
import com.example.hrhostclone.core.Detector
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicReference

class PreviewView(context: Context) : SurfaceView(context), SurfaceHolder.Callback {

    private val renderScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val workScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var renderJob: Job? = null
    private var workJob: Job? = null

    private val latestResult = AtomicReference<ColorDetectionResult?>(null)

    /**
     * 双缓冲：两个 Bitmap 槽位，工作线程和渲染线程各占一个。
     * 工作原理：
     * - 工作线程往 [0] 写入最新的成品图
     * - 渲染线程读取 [1] 进行绘制
     * - 交换时机：渲染线程完成绘制后，从 [0] 复制到 [1]
     */
    private val bufferSlots = arrayOfNulls<Bitmap?>(2)
    private var currentWriteIndex = 0

    /** 渲染线程使用的 Bitmap，只在这个线程里访问 */
    @Volatile
    private var renderBmp: Bitmap? = null

    private val imagePaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val boxPaint = Paint().apply {
        color = Color.rgb(0, 255, 0)
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    private val pointPaint = Paint().apply {
        color = Color.RED
        style = Paint.Style.FILL
    }
    private val linePaint = Paint().apply {
        color = Color.rgb(255, 255, 0)
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
    }

    init {
        holder.addCallback(this)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        // 工作线程：只生产 Bitmap
        workJob = workScope.launch {
            while (isActive) {
                val frame = ReceiverEngine.peekFrame()
                if (frame != null) {
                    val t1 = System.currentTimeMillis()
                    val bmp = runCatching {
                        BitmapFactory.decodeByteArray(frame, 0, frame.size)
                    }.getOrNull()
                    if (bmp != null) {
                        val t2 = System.currentTimeMillis()
                        val result = Detector.detect(bmp, bmp.width / 2, bmp.height / 2)
                        latestResult.set(result)
                        ColorModeState.colorDetectionResult = result
                        ColorModeState.isDetected = result != null

                        val output = if (ColorModeState.showMask && ColorModeState.showPreview) {
                            Detector.compositeMaskOnFrame(bmp)
                        } else if (ColorModeState.showPreview) {
                            bmp.copy(Bitmap.Config.ARGB_8888, true)
                        } else {
                            null
                        }

                        // 写入当前槽位，覆盖旧数据
                        synchronized(bufferSlots) {
                            val old = bufferSlots[currentWriteIndex]
                            bufferSlots[currentWriteIndex] = output
                            currentWriteIndex = (currentWriteIndex + 1) % 2
                            old?.recycle()
                        }

                        bmp.recycle()
//                        val t3 = System.currentTimeMillis()
//                        if (output != null) {
//                            Log.d("Preview", "解码:${t2 - t1}ms 检测+合成:${t3 - t2}ms 总:${t3 - t1}ms")
//                        }
                    }
                }
                delay(16)
            }
        }

        // 渲染线程：只从缓冲区读取最新的 Bitmap
        renderJob = renderScope.launch {
            while (isActive) {
                val canvas = runCatching { holder.lockCanvas() }.getOrNull()
                if (canvas != null) {
                    try {
                        canvas.drawColor(Color.BLACK)

                        // 从缓冲区获取最新的待显示 Bitmap
                        val newBmp: Bitmap? = synchronized(bufferSlots) {
                            val idx = (currentWriteIndex + 1) % 2
                            bufferSlots[idx]
                        }

                        // 如果拿到了新帧，替换旧帧
                        if (newBmp != null) {
                            val old = renderBmp
                            renderBmp = newBmp.copy(Bitmap.Config.ARGB_8888, true)
                            old?.recycle()
                            synchronized(bufferSlots) {
                                bufferSlots[(currentWriteIndex + 1) % 2]?.recycle()
                                bufferSlots[(currentWriteIndex + 1) % 2] = null
                            }
                        }

                        if (renderBmp != null && !renderBmp!!.isRecycled && ColorModeState.showPreview) {
                            canvas.drawBitmap(renderBmp!!, null, Rect(0, 0, width, height), imagePaint)
                        }

                        val result = latestResult.get()
                        if (ColorModeState.showBoundingBox && result != null && renderBmp != null && !renderBmp!!.isRecycled) {
                            drawBoundingBoxRender(canvas, renderBmp!!, result)
                        }
                    } finally {
                        holder.unlockCanvasAndPost(canvas)
                    }
                }
                delay(16)
            }
        }
    }

    private fun drawBoundingBoxRender(canvas: Canvas, bmp: Bitmap, result: ColorDetectionResult) {
        val scaleX = this.width / bmp.width.toFloat()
        val scaleY = this.height / bmp.height.toFloat()
        val left = result.boxLeft * scaleX
        val top = result.boxTop * scaleY
        val right = (result.boxLeft + result.boxWidth) * scaleX
        val bottom = (result.boxTop + result.boxHeight) * scaleY
        val targetX = result.localTargetX * scaleX
        val targetY = result.localTargetY * scaleY
        val centerX = this.width / 2f
        val centerY = this.height / 2f
        canvas.drawRect(left, top, right, bottom, boxPaint)
        canvas.drawCircle(targetX, targetY, 5f, pointPaint)
        canvas.drawLine(centerX, centerY, targetX, targetY, linePaint)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        renderJob?.cancel()
        workJob?.cancel()
        renderScope.cancel()
        workScope.cancel()

        renderBmp?.recycle()
        renderBmp = null
        synchronized(bufferSlots) {
            bufferSlots[0]?.recycle()
            bufferSlots[1]?.recycle()
            bufferSlots[0] = null
            bufferSlots[1] = null
        }
    }
}