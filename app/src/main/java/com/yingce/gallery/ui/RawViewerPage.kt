package com.yingce.gallery.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.yingce.gallery.data.Photo
import com.yingce.gallery.raw.RawImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * RAW 查看页。
 *
 * 画质口径：整幅按原分辨率解码（不用 half_size、不做解码期缩放），
 * 只在最后一步按屏幕尺寸缩放显示——这是"显示适配"，不是"降采样预览"。
 *
 * 内存保护：像素数超过 [FULL_DECODE_LIMIT] 时先显示内嵌预览并明确标注，
 * 不静默降级成模糊图。
 */
@Composable
fun RawViewerPage(
    photo: Photo,
    downsample: Boolean = false,
    onLoaded: (Int, Int) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var bitmap by remember(photo.id) { mutableStateOf<Bitmap?>(null) }
    var note by remember(photo.id) { mutableStateOf("正在解码 RAW…") }
    var badge by remember(photo.id) { mutableStateOf<String?>(null) }

    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val targetWidthPx = with(density) { configuration.screenWidthDp.dp.roundToPx() }

    LaunchedEffect(photo.id, downsample) {
        val result = withContext(Dispatchers.IO) {
            val file = PhotoSource.fileOf(context, photo)
                ?: return@withContext Triple<Bitmap?, String, String?>(
                    null, "副本不存在（仅引用模式可能已丢失源文件）", null,
                )
            decodeRaw(file, targetWidthPx, downsample)
        }
        bitmap = result.first
        note = result.second
        badge = result.third
        result.first?.let { onLoaded(it.width, it.height) }
    }

    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val bmp = bitmap
        if (bmp != null) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = photo.displayName,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text(note, color = Color.White, style = MaterialTheme.typography.bodyMedium)
        }

        badge?.let { text ->
            Text(
                text = text,
                color = Color.White,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 56.dp, end = 12.dp)
                    .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(10.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    }
}

private const val FULL_DECODE_LIMIT = 64_000_000L // 6400 万像素以内都做原分辨率解码

private fun decodeRaw(file: File, targetWidthPx: Int, downsample: Boolean): Triple<Bitmap?, String, String?> {
    val img = RawImage.open(file.absolutePath)
        ?: return Triple(null, "LibRaw 无法打开该文件", null)

    return img.use { r ->
        val pixels = r.width.toLong() * r.height.toLong()
        // 内存保护：IntArray(w*h) + Bitmap(w*h*4) 要同时存在，超过可用堆的 55% 就先用内嵌预览，
        // 避免大尺寸 RAW（如部分 OPPO/哈苏 DNG）直接 OOM 闪退。
        val needBytes = if (downsample) pixels * 2 else pixels * 4 * 2
        val maxHeap = Runtime.getRuntime().maxMemory()
        if (needBytes > (maxHeap * 0.55).toLong()) {
            val thumb = r.thumbnail()
            val bmp = thumb?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
                ?.let { com.yingce.gallery.raw.RawOrientation.applyToBitmap(it, r.flip) }
            return@use if (bmp != null) {
                Triple(bmp, "内存不足，已回退内嵌预览", "内嵌预览 · ${bmp.width}×${bmp.height}")
            } else {
                Triple(null, "图像过大（${r.width}×${r.height}），可用内存不足", null)
            }
        }
        if (pixels > FULL_DECODE_LIMIT) {
            // 超大图：先给内嵌预览，明确标注，不做静默模糊
            val thumb = r.thumbnail()
            val bmp = thumb?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
            if (bmp != null) {
                Triple(bmp, "内嵌预览", "内嵌预览 · ${r.width}×${r.height}")
            } else {
                Triple(null, "图像过大（${r.width}×${r.height}），分块解码尚未接入", null)
            }
        } else {
            val argbRaw = try {
                r.decodeRegion(0, 0, r.width, r.height, 8, halfSize = downsample)
            } catch (t: Throwable) {
                // 解码器内部的任何异常（含 OOM）都不能让查看器崩溃
                return@use Triple(null, "解码失败：${t.javaClass.simpleName}", null)
            }
            // LibRaw 不自动旋转，必须按 sizes.flip 自己纠正（否则横拍会显示成竖的）
            val ow = if (downsample) (r.width + 1) / 2 else r.width
            val oh = if (downsample) (r.height + 1) / 2 else r.height
            val oriented = if (argbRaw != null) {
                com.yingce.gallery.raw.RawOrientation.apply(argbRaw, ow, oh, r.flip)
            } else null
            val argb = oriented?.pixels
            if (argb == null) {
                val thumb = r.thumbnail()
                val bmp = thumb?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
                    ?.let { com.yingce.gallery.raw.RawOrientation.applyToBitmap(it, r.flip) }
                Triple(bmp, "全解失败，已回退内嵌预览", if (bmp != null) "内嵌预览 · ${bmp.width}×${bmp.height}" else null)
            } else {
                // 不再做 createScaledBitmap：Compose 的 ContentScale.Fit 在 GPU 上缩放，
                // 少一次全尺寸位图拷贝（这是之前卡顿的主要来源之一）。
                val full = Bitmap.createBitmap(oriented!!.pixels, oriented.width, oriented.height, Bitmap.Config.ARGB_8888)
                Triple(full, if (downsample) "RAW 预览（半尺寸，面板已展开）" else "RAW 原分辨率解码", "原生 · ${oriented.width}×${oriented.height}")
            }
        }
    }
}
