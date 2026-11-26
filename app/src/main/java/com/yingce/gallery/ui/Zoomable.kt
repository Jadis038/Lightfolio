package com.yingce.gallery.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

/**
 * 大图缩放容器。
 *
 * 关键：**不能吃掉单指横向滑动**，否则查看器里就没法左右翻页了。
 * 因此手势处理是"按需消费"：
 *  - 单指 + 当前未放大 → 完全不消费，让 HorizontalPager 去翻页
 *  - 双指（缩放中）或已经放大 → 才消费事件，用于缩放与拖动
 *
 * 交互：
 *  - 双指放大/缩小；松手保持当前状态
 *  - 双击：适应屏幕 ↔ 100%（图片像素与屏幕像素 1:1，按机型密度实时计算）
 */
@Composable
fun Zoomable(
    /** 图片原始像素宽度；未知时传 null，双击退化为 2.5 倍 */
    intrinsicWidth: Int?,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val screenWidthPx = with(density) { configuration.screenWidthDp.dp.toPx().toInt() }
    val oneToOne = if (intrinsicWidth != null && intrinsicWidth > 0 && screenWidthPx > 0) {
        (intrinsicWidth.toFloat() / screenWidthPx).coerceIn(1f, 10f)
    } else 2.5f

    Box(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    var zooming = false
                    var lastEventTime = 0L
                    var lastTapUp = 0L
                    do {
                        val event = awaitPointerEvent()
                        val pressedCount = event.changes.count { it.pressed }
                        if (pressedCount >= 2) zooming = true

                        // 只在"双指"或"已放大"时才接管手势，否则放行给翻页
                        val takeOver = zooming || scale > 1.02f
                        if (takeOver) {
                            val zoomChange = event.calculateZoom()
                            val panChange = event.calculatePan()
                            if (zoomChange != 1f) {
                                val next = (scale * zoomChange).coerceIn(1f, 10f)
                                scale = next
                                if (next <= 1.02f) offset = Offset.Zero
                                zooming = true
                            }
                            if (panChange != Offset.Zero && scale > 1.02f) {
                                offset += panChange
                            }
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }

                        // 双击检测：两次快速抬起且位移很小
                        val upNow = event.changes.all { !it.pressed }
                        if (upNow) {
                            val now = event.changes.first().uptimeMillis
                            if (now - lastTapUp < 300 && pressedCount <= 1) {
                                if (scale > 1.05f) {
                                    scale = 1f
                                    offset = Offset.Zero
                                } else {
                                    scale = oneToOne
                                }
                                lastTapUp = 0L
                            } else {
                                lastTapUp = now
                            }
                        }
                        lastEventTime = event.changes.first().uptimeMillis
                    } while (event.changes.any { it.pressed })
                }
            }
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = offset.x
                translationY = offset.y
            },
        content = content,
    )
}
