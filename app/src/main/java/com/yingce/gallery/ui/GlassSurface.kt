package com.yingce.gallery.ui

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * 液态玻璃面板（真·磨砂背景）。
 *
 * 效果来源（与系统级毛玻璃一致）：
 *  1. 背景层：`GlassBackdrop` 抓到的整窗画面（已模糊），按控件在窗口中的位置偏移绘制 → 真正"透出背后照片"
 *  2. 色调层：极淡的白/黑，控制压暗或提亮
 *  3. 折射层：AGSL 斜向高光 + 边缘高光（API 33+），预烘焙成位图，每帧只做位图绘制
 *  4. 边缘：0.8dp 极淡描边
 *
 * 性能：抓帧只在滚动停止后做一次；滚动期间背景层用纯色调兜底（`glassEffectsEnabled=false`）。
 */
object YingceGlass {

    private const val REFRACTION_AGSL = """
        uniform float2 uSize;
        uniform float uStrength;

        half4 main(float2 coord) {
            float2 uv = coord / uSize;
            float diagonal = smoothstep(0.0, 1.0, (uv.x + uv.y) * 0.5);
            float band = 1.0 - abs(diagonal - 0.32) * 3.2;
            band = clamp(band, 0.0, 1.0);
            float edgeX = smoothstep(0.0, 0.10, min(uv.x, 1.0 - uv.x));
            float edgeY = smoothstep(0.0, 0.16, min(uv.y, 1.0 - uv.y));
            float rim = 1.0 - (edgeX * edgeY);
            float sheen = band * 0.55 + rim * 0.30;
            half3 tint = half3(1.0, 0.985, 0.965);
            return half4(tint * sheen * uStrength, sheen * uStrength * 0.85);
        }
    """

    val supportsAgsL: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    /** 滚动中关闭玻璃效果（不做背景采样、不画折射），保住帧率。 */
    var glassEffectsEnabled by mutableStateOf(true)

    /**
     * 背景采样总开关。
     * 只有"总相册"（背后是照片）才值得采样；相册页背后没有照片，
     * 若继续用上一页的旧位图，就会在每张卡片里糊出"残留"。
     */
    var backdropEnabled by mutableStateOf(true)

    @Composable
    fun GlassBox(
        modifier: Modifier = Modifier,
        corner: Dp = 24.dp,
        tint: Color = defaultTint(),
        blurRadius: Dp = 12.dp,
        refraction: Boolean = true,
        /** 是否使用真实背景采样（关掉则退化为纯色半透明） */
        useBackdrop: Boolean = true,
        content: @Composable BoxScope.() -> Unit = {},
    ) {
        val shape = RoundedCornerShape(corner)

        var posInWindow by remember { mutableStateOf(Offset.Zero) }
        val backdrop: ImageBitmap? = if (useBackdrop && glassEffectsEnabled && backdropEnabled) GlassBackdrop.bitmap else null
        val useAgsL = glassEffectsEnabled && supportsAgsL && refraction

        Box(
            modifier = modifier

                .onGloballyPositioned { posInWindow = it.positionInWindow() }
                .clip(shape)
                .drawWithCache {
                    val bd = backdrop
                    // 注意坐标系数：posInWindow 是窗口坐标，位图原点是内容层左上角
                    val offsetX = -(posInWindow.x - GlassBackdrop.contentOrigin.x).roundToInt()
                    val offsetY = -(posInWindow.y - GlassBackdrop.contentOrigin.y).roundToInt()
                    onDrawBehind {
                        if (bd != null) {
                            drawImage(
                                image = bd,
                                dstOffset = IntOffset(offsetX, offsetY),
                            )
                        }
                    }
                }
                // 色调层：极淡，主要让文字有足够对比
                .background(tint)
                .border(
                    width = 0.8.dp,
                    color = if (isSystemInDarkTheme()) {
                        Color.White.copy(alpha = 0.16f)
                    } else {
                        Color.White.copy(alpha = 0.6f)
                    },
                    shape = shape,
                ),
        ) {
            // 折射高光：AGSL 只算一次，烘焙成位图后每帧只做位图绘制
            if (useAgsL) {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .drawWithCache {
                            val w = size.width.toInt().coerceAtLeast(1)
                            val h = size.height.toInt().coerceAtLeast(1)
                            val baked = sheenBitmap(w, h)
                            onDrawBehind {
                                if (baked != null) {
                                    drawContext.canvas.nativeCanvas.drawBitmap(baked, 0f, 0f, null)
                                }
                            }
                        },
                )
            }
            content()
        }
    }

    private var cachedSheen: android.graphics.Bitmap? = null
    private var cachedW = 0
    private var cachedH = 0

    @Synchronized
    private fun sheenBitmap(w: Int, h: Int): android.graphics.Bitmap? {
        val c = cachedSheen
        if (c != null && cachedW == w && cachedH == h) return c
        return runCatching {
            val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bmp)
            val shader = android.graphics.RuntimeShader(REFRACTION_AGSL)
            shader.setFloatUniform("uSize", w.toFloat(), h.toFloat())
            shader.setFloatUniform("uStrength", 1.0f)
            val paint = android.graphics.Paint().apply { isAntiAlias = true; this.shader = shader }
            canvas.drawPaint(paint)
            cachedSheen?.recycle()
            cachedSheen = bmp
            cachedW = w
            cachedH = h
            bmp
        }.getOrNull()
    }

    /**
     * 默认色调：偏不透明的白（浅色）/ 偏不透明的深灰（深色）。
     * 太透明会导致文字压在照片上看不清，这里取 55% / 78% —— 既有磨砂感，又保证可读性。
     */
    @Composable
    private fun defaultTint(): Color = if (isSystemInDarkTheme()) {
        Color(0xC71B1D22)
    } else {
        Color(0x8CFFFFFF)
    }
}
