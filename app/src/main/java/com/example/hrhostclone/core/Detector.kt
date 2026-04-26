package com.example.hrhostclone.core

import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.Log
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.sqrt

object Detector {

    private const val TAG = "Detector"

    // ==================== 常量 ====================

    private const val NAME_GAP_MIN_RATIO = 0.1f
    private const val NAME_GAP_MAX_RATIO = 0.8f
    private const val NAME_HORIZONTAL_OFFSET_RATIO = 0.5f

    // ==================== 候选轮廓数据结构 ====================

    private data class ContourCandidate(
        val rect: Rect,
        val area: Double,
        val fillRatio: Float,
        val aspectRatio: Float,
        val width: Int,
        val height: Int,
        val isDeathIcon: Boolean = false
    )

    // ==================== FPS 统计 ====================

    private val fpsLock = Any()
    private var frameCount = 0
    private var lastFpsTime = System.currentTimeMillis()

    // ==================== 轮廓历史 ====================

    private var lastFullHeight = 60f

    // ==================== 检测入口 ====================

    fun detect(bitmap: Bitmap, screenCenterX: Int, screenCenterY: Int): ColorDetectionResult? {
        return try {
            detectInternal(bitmap)
        } catch (e: Exception) {
            Log.e(TAG, "检测失败: ${e.message}")
            null
        }
    }

    // ==================== 内部检测逻辑 ====================

    private fun detectInternal(bitmap: Bitmap): ColorDetectionResult? {
        val startTime = System.nanoTime()
        val width = bitmap.width
        val height = bitmap.height

        val mask = createMask(bitmap) ?: return null

        val contours = mutableListOf<MatOfPoint>()
        Imgproc.findContours(mask, contours, Mat(), Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
        mask.release()

        if (contours.isEmpty()) {
            ColorModeState.detectMethod = "none"
            updateFps(startTime)
            return null
        }

        val candidates = mutableListOf<ContourCandidate>()

        for (contour in contours) {
            val area = Imgproc.contourArea(contour)
            val rect = Imgproc.boundingRect(contour)
            val w = rect.width
            val h = rect.height
            if (w <= 0 || h <= 0) continue

            val aspectRatio = w.toFloat() / h
            val rectArea = (w * h).coerceAtLeast(1)
            val fillRatio = (area / rectArea).toFloat()

            // 检测濒死角标（粉色菱形+白色十字）
            val isDeathIcon = aspectRatio in 0.7f..1.3f &&
                    w in 25..55 && h in 25..55 &&
                    fillRatio in 0.3f..0.7f &&
                    area.toInt() in 400..2000

            if (isDeathIcon) {
                if (ColorModeState.lockTeammates) {
                    Log.d(TAG, "🩺 濒死角标加入候选: w=$w h=$h")
                    candidates.add(ContourCandidate(rect, area, fillRatio, aspectRatio, w, h, isDeathIcon = true))
                }
                continue
            }

            // 锁队友时过滤敌人轮廓
            if (ColorModeState.lockTeammates) continue

            // 正常敌人过滤
            if (area < ColorModeState.minContourArea || area > ColorModeState.maxContourArea) continue

            val isAvatar = aspectRatio in 0.95f..1.05f && w in 55..65 && h in 55..65
            if (isAvatar) continue

            val isShinFragment = area < 200 && h < 35 && w < 25 && aspectRatio in 0.5f..1.5f
            if (isShinFragment) continue

            val contourCenterX = rect.x + w / 2
            val contourCenterY = rect.y + h / 2
            val isThinLine = (w > h * 3f || h > w * 3f) && minOf(w, h) < 10
            val inLaserZone = contourCenterX > width * 0.4f && contourCenterY > height * 0.3f
            if (isThinLine && inLaserZone && area < 1000) continue

            if (aspectRatio < ColorModeState.minAspectRatio || aspectRatio > ColorModeState.maxAspectRatio) continue
            if (fillRatio < ColorModeState.minFillRatio) continue
            if (w < ColorModeState.minWidthValue || h < ColorModeState.minHeightValue) continue

            candidates.add(ContourCandidate(rect, area, fillRatio, aspectRatio, w, h))
        }

        contours.forEach { it.release() }

        if (candidates.isEmpty()) {
            ColorModeState.detectMethod = "none"
            updateFps(startTime)
            return null
        }

        // 位置关系过滤（只过滤敌人轮廓，濒死角标不参与）
        val enemyCandidates = candidates.filter { !it.isDeathIcon }
        val deathIconCandidates = candidates.filter { it.isDeathIcon }
        val filteredEnemies = filterNameByPosition(enemyCandidates)
        val filtered = filteredEnemies + deathIconCandidates

        if (filtered.isEmpty()) {
            ColorModeState.detectMethod = "none"
            updateFps(startTime)
            return null
        }

        // 选择最佳目标：濒死角标优先级高于敌人
        val centerX = width / 2
        val centerY = height / 2
        var bestResult: ColorDetectionResult? = null
        var bestPriority = Int.MAX_VALUE
        var bestDistance = Float.MAX_VALUE

        for (candidate in filtered) {
            val rect = candidate.rect
            val w = candidate.width
            val h = candidate.height

            val estimatedH = if (!candidate.isDeathIcon && h < lastFullHeight * 0.7f) lastFullHeight else h.toFloat()
            val aimX = rect.x + w / 2
            val bodyRatio = ColorModeState.aimHeightPercent / 100f

            val aimY: Int
            if (candidate.isDeathIcon) {
                aimY = rect.y + h / 2
            } else {
                val isOccluded = h < lastFullHeight * 0.7f && lastFullHeight > 0 && lastFullHeight < h * 3f
                aimY = if (isOccluded) {
                    val normalTop = (rect.y + h) - lastFullHeight
                    (normalTop + lastFullHeight * bodyRatio).toInt()
                } else {
                    rect.y + (estimatedH * bodyRatio).toInt()
                }
            }

            val offsetX = aimX - centerX
            val offsetY = aimY - centerY
            if (abs(offsetX) > width / 2 || abs(offsetY) > height / 2) continue

            val priority = if (candidate.isDeathIcon) 1 else 2
            val distance = sqrt((offsetX * offsetX + offsetY * offsetY).toFloat())

            if (priority < bestPriority || (priority == bestPriority && distance < bestDistance)) {
                bestPriority = priority
                bestDistance = distance
                bestResult = ColorDetectionResult(
                    offsetX = offsetX, offsetY = offsetY,
                    localTargetX = aimX, localTargetY = aimY,
                    boxLeft = rect.x, boxTop = rect.y,
                    boxWidth = w, boxHeight = h,
                    area = candidate.area.toInt(), distanceToCenter = distance,
                    score = 1f, fillRatio = candidate.fillRatio,
                    detectMethod = if (candidate.isDeathIcon) "death_icon" else "body"
                )
                if (!candidate.isDeathIcon && h > lastFullHeight * 0.5f && h < lastFullHeight * 1.5f) {
                    lastFullHeight = lastFullHeight * 0.7f + h * 0.3f
                }
            }
        }

        ColorModeState.detectMethod = bestResult?.detectMethod ?: "none"
        updateFps(startTime)
        return bestResult
    }

    // ==================== 位置关系过滤 ====================

    private fun filterNameByPosition(candidates: List<ContourCandidate>): List<ContourCandidate> {
        if (candidates.size <= 1) return candidates
        val result = mutableListOf<ContourCandidate>()
        val removedIndices = mutableSetOf<Int>()
        for (i in candidates.indices) {
            if (i in removedIndices) continue
            val upper = candidates[i]
            var isNameOfSomeone = false
            for (j in candidates.indices) {
                if (i == j || j in removedIndices) continue
                if (isNameAboveBody(upper, candidates[j])) { isNameOfSomeone = true; break }
            }
            if (!isNameOfSomeone) result.add(upper)
        }
        return result
    }

    private fun isNameAboveBody(upper: ContourCandidate, lower: ContourCandidate): Boolean {
        val bodyHeight = lower.height
        val verticalGap = lower.rect.y - (upper.rect.y + upper.height)
        if (verticalGap !in (bodyHeight * NAME_GAP_MIN_RATIO).toInt()..(bodyHeight * NAME_GAP_MAX_RATIO).toInt()) return false
        val upperCenterX = upper.rect.x + upper.width / 2
        val lowerCenterX = lower.rect.x + lower.width / 2
        if (abs(upperCenterX - lowerCenterX) > (bodyHeight * NAME_HORIZONTAL_OFFSET_RATIO).toInt()) return false
        if (upper.aspectRatio > 2.5f && upper.height < 35) return true
        return upper.width * upper.height < lower.width * lower.height
    }

    // ==================== 公开方法 ====================

    fun generateMask(bitmap: Bitmap): Bitmap? {
        return try {
            val mask = createMask(bitmap) ?: return null
            val small = Mat()
            Imgproc.resize(mask, small, Size((bitmap.width / 2).toDouble(), (bitmap.height / 2).toDouble()), 0.0, 0.0, Imgproc.INTER_NEAREST)
            mask.release()
            val result = Bitmap.createBitmap(bitmap.width / 2, bitmap.height / 2, Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(small, result)
            small.release()
            result
        } catch (e: Exception) { null }
    }

    fun compositeMaskOnFrame(bitmap: Bitmap): Bitmap? {
        return try {
            val mask = createMask(bitmap) ?: return null
            val smallMask = Mat()
            Imgproc.resize(mask, smallMask, Size((bitmap.width / 2).toDouble(), (bitmap.height / 2).toDouble()), 0.0, 0.0, Imgproc.INTER_NEAREST)
            mask.release()
            val maskBmp = Bitmap.createBitmap(bitmap.width / 2, bitmap.height / 2, Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(smallMask, maskBmp)
            smallMask.release()
            val result = bitmap.copy(Bitmap.Config.ARGB_8888, true)
            Canvas(result).drawBitmap(maskBmp, null, android.graphics.Rect(0, 0, bitmap.width, bitmap.height), null)
            maskBmp.recycle()
            result
        } catch (e: Exception) { null }
    }

    // ==================== 核心掩码 ====================

    private fun createMask(bitmap: Bitmap): Mat? {
        val src = Mat()
        Utils.bitmapToMat(bitmap, src)
        if (src.empty()) return null
        val bgr = Mat()
        when (src.channels()) {
            4 -> Imgproc.cvtColor(src, bgr, Imgproc.COLOR_RGBA2BGR)
            3 -> src.copyTo(bgr)
            else -> { src.release(); return null }
        }
        src.release()
        if (bgr.empty()) return null
        val hsv = Mat()
        Imgproc.cvtColor(bgr, hsv, Imgproc.COLOR_BGR2HSV)
        bgr.release()
        if (hsv.empty()) return null
        val lower = Scalar(ColorModeState.currentHMin.toDouble(), ColorModeState.currentSMin.toDouble(), ColorModeState.currentVMin.toDouble())
        val upper = Scalar(ColorModeState.currentHMax.toDouble(), ColorModeState.currentSMax.toDouble(), ColorModeState.currentVMax.toDouble())
        val mask = Mat()
        Core.inRange(hsv, lower, upper, mask)
        hsv.release()
        if (mask.empty()) return null
        val dkSize = ColorModeState.dilateSizeValue.toDouble()
        val dk = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(dkSize, dkSize))
        Imgproc.dilate(mask, mask, dk, Point(-1.0, -1.0), ColorModeState.dilateIterationsValue)
        dk.release()
        val ckSize = ColorModeState.closeSizeValue.toDouble()
        val ck = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(ckSize, ckSize))
        Imgproc.morphologyEx(mask, mask, Imgproc.MORPH_CLOSE, ck)
        ck.release()
        return mask
    }

    // ==================== FPS 统计 ====================

    private fun updateFps(startTime: Long) {
        val elapsed = (System.nanoTime() - startTime) / 1_000_000
        ColorModeState.detectionTimeMs = elapsed
        synchronized(fpsLock) {
            frameCount++
            val now = System.currentTimeMillis()
            if (now - lastFpsTime >= 1000) {
                ColorModeState.detectionFps = frameCount
                frameCount = 0
                lastFpsTime = now
            }
        }
    }
}