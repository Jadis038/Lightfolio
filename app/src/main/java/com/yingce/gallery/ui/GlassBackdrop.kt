package com.yingce.gallery.ui

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap

/**
 * 毛玻璃背景。
 *
 * 早期实现用 PixelCopy 抓屏，必须先把玻璃隐藏 2–3 帧，否则会把自己的内容拍进背景 —— 
 * 结果就是**每次滚动停止都闪一下**。现在改成：
 *
 *   用 Compose 的 GraphicsLayer 把"内容层"离屏录一张位图（不包含任何悬浮玻璃），
 *   再缩小/放大做模糊。整个过程不需要隐藏任何 UI，因此没有闪烁。
 */
object GlassBackdrop {

    /** 已模糊好的背景位图（内容层尺寸）。 */
    var bitmap by mutableStateOf<ImageBitmap?>(null)
        private set

    /**
     * 内容层在窗口中的原点。
     * 背景位图的坐标原点是"内容层左上角"，而 GlassBox 拿到的是"窗口坐标"，
     * 两者必须相减，否则模糊内容会整体偏移（看起来像"糊的是别处"）。
     */
    var contentOrigin by mutableStateOf(androidx.compose.ui.geometry.Offset.Zero)

    /** 把内容层位图缩小再放大，得到柔和模糊；同时丢弃旧图释放内存。 */
    fun update(source: ImageBitmap?) {
        if (source == null) return
        val src = runCatching { source.asAndroidBitmap() }.getOrNull() ?: return
        if (src.width <= 0 || src.height <= 0) return

        // 多次降采样再升采样：单次 1/8 会明显块状（用户反馈"停留时分辨率太低"），
        // 走 1/2 → 1/4 → 1/8 再逐级放大，过渡更平滑
        fun scaleTo(bmp: Bitmap, w: Int, h: Int): Bitmap? =
            runCatching { Bitmap.createScaledBitmap(bmp, w.coerceAtLeast(1), h.coerceAtLeast(1), true) }.getOrNull()

        val q2 = scaleTo(src, src.width / 2, src.height / 2) ?: return
        val q4 = scaleTo(q2, src.width / 4, src.height / 4) ?: run { q2.recycle(); return }
        q2.recycle()
        val q8 = scaleTo(q4, src.width / 8, src.height / 8) ?: run { q4.recycle(); return }
        q4.recycle()
        val back4 = scaleTo(q8, src.width / 4, src.height / 4) ?: run { q8.recycle(); return }
        q8.recycle()
        val back2 = scaleTo(back4, src.width / 2, src.height / 2) ?: run { back4.recycle(); return }
        back4.recycle()
        val blurred = scaleTo(back2, src.width, src.height) ?: run { back2.recycle(); return }
        back2.recycle()

        bitmap = blurred.asImageBitmap()
    }

    fun clear() {
        bitmap = null
    }
}

/** 供内容层使用：把自身绘制录进一个离屏图层。 */
@Composable
fun rememberContentLayer() = androidx.compose.ui.graphics.rememberGraphicsLayer()
