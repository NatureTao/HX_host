package com.example.hrhostclone.backend

import com.example.hrhostclone.core.ColorModeState
import kotlinx.coroutines.*
import kotlin.math.*

object AimMoveController {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var moveJob: Job? = null


    fun start() {
        if (moveJob?.isActive == true) return
        moveJob = scope.launch {
            while (isActive) {
                if (ColorModeState.isAutoAimActive
                    && ColorModeState.isTriggerPressed
                    && ColorModeState.isDetected
                ) {
                    val result = ColorModeState.colorDetectionResult
                    if (result != null) {
                        val offsetX = result.offsetX.toFloat()
                        val offsetY = result.offsetY.toFloat()

                        val deadZone = ColorModeState.deadZoneRadius
                        val distToTarget = sqrt(offsetX * offsetX + offsetY * offsetY)

                        // 死区内不移动
                        if (distToTarget < deadZone) {
                            delay(4)
                            continue
                        }

                        // 两阶段移动
                        val ratio = if (distToTarget > deadZone * 3f) {
                            // 远距离：快速拉过去
                            0.8f
                        } else {
                            // 近距离：减速微调，避免冲过头
                            0.15f
                        }

                        val speed = ColorModeState.aimSpeed / 2.0f
                        val dx = (offsetX * speed * ratio).roundToInt()
                        val dy = (offsetY * speed * ratio).roundToInt()

                        if (dx != 0 || dy != 0) {
                            RuntimeBridge.sendMove(dx, dy)
                        }
                    }
                }
                delay(8) // 降低频率，减少抖动
            }
        }
    }

    fun stop() {
        moveJob?.cancel()
        moveJob = null
    }


}