package com.yingce.gallery.ui

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.crossfade
import com.yingce.gallery.format.TiffDecoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * 缩略图。
 *
 * Coil/BitmapFactory 认不出的格式（TIFF、JXL、EXR）走这里：
 *  - TIFF：用内置 TiffDecoder 解到 360px 宽（带内存缓存）
 *  - JXL / EXR：先试平台的 ImageDecoder（部分厂商 ROM 支持 JXL），失败则显示格式标签
 *
 * 其余格式仍然交给 Coil（它已经有内存/磁盘缓存）。
 */
@Composable
fun SmartThumbnail(
    model: Any,
    displayName: String,
    modifier: Modifier = Modifier,
) {
    val ext = displayName.substringAfterLast('.', "").lowercase()
    when {
        com.yingce.gallery.data.MediaTypes.isVideoByName(displayName) -> Unit
        ext in RAW_EXTS -> RawThumb(model, displayName, modifier)
        ext == "tif" || ext == "tiff" -> TiffThumb(model, modifier)
        ext == "jxl" || ext == "exr" -> PlatformThumb(model, displayName, modifier)
        else -> AsyncImage(
            model = coil3.request.ImageRequest.Builder(LocalContext.current)
                .data(model)
                .size(360)
                .crossfade(false)
                .build(),
            contentDescription = displayName,
            contentScale = ContentScale.Crop,
            modifier = modifier,
        )
    }
}

private val RAW_EXTS = setOf(
    "dng", "cr2", "cr3", "nef", "arw", "raf", "orf", "rw2", "pef", "srw",
    "x3f", "3fr", "mos", "mef", "dcr", "mrw", "erf", "sr2", "srf", "raw", "nrw",
)

private val tiffCache = ConcurrentHashMap<String, Bitmap>()
private val rawCache = ConcurrentHashMap<String, Bitmap>()

/** RAW 缩略图：取内嵌预览并按 flip 纠正方向（列表面里也要正着显示）。 */
@Composable
private fun RawThumb(model: Any, displayName: String, modifier: Modifier) {
    var bmp by remember(model) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(model) {
        bmp = withContext(Dispatchers.IO) {
            val file = model as? java.io.File ?: return@withContext null
            val key = file.absolutePath
            rawCache[key]?.let { return@withContext it }
            runCatching {
                com.yingce.gallery.raw.RawImage.open(key)?.use { r ->
                    val bytes = r.thumbnail() ?: return@use null
                    val decoded = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        ?: return@use null
                    val oriented = com.yingce.gallery.raw.RawOrientation.applyToBitmap(decoded, r.flip)
                    val longest = maxOf(oriented.width, oriented.height)
                    val scaled = if (longest > 360) {
                        val s = 360.0 / longest
                        android.graphics.Bitmap.createScaledBitmap(
                            oriented,
                            (oriented.width * s).toInt().coerceAtLeast(1),
                            (oriented.height * s).toInt().coerceAtLeast(1),
                            true,
                        )
                    } else oriented
                    rawCache[key] = scaled
                    scaled
                }
            }.getOrNull()
        }
    }
    Box(modifier.background(Color(0xFF202124))) {
        val image = bmp
        if (image != null) {
            Image(
                bitmap = image.asImageBitmap(),
                contentDescription = displayName,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text("RAW", color = Color(0xFF9AA0A6), style = MaterialTheme.typography.labelSmall, modifier = Modifier.align(Alignment.Center))
        }
    }
}

@Composable
private fun TiffThumb(model: Any, modifier: Modifier) {
    var bmp by remember(model) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(model) {
        bmp = withContext(Dispatchers.IO) {
            val file = model as? java.io.File ?: return@withContext null
            val key = file.absolutePath
            tiffCache[key]?.let { return@withContext it }
            TiffDecoder.decodeScaled(file, 360)?.also { b -> tiffCache[key] = b }
        }
    }
    Box(modifier.background(Color(0xFF202124))) {
        val image = bmp
        if (image != null) {
            Image(
                bitmap = image.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text(
                "TIFF",
                color = Color(0xFF9AA0A6),
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.align(Alignment.Center),
            )
        }
    }
}

@Composable
private fun PlatformThumb(model: Any, displayName: String, modifier: Modifier) {
    val context = LocalContext.current
    var bmp by remember(model) { mutableStateOf<Bitmap?>(null) }
    var failed by remember(model) { mutableStateOf(false) }
    LaunchedEffect(model) {
        bmp = withContext(Dispatchers.IO) {
            runCatching {
                val source = when (model) {
                    is java.io.File -> ImageDecoder.createSource(model)
                    is android.net.Uri -> ImageDecoder.createSource(context.contentResolver, model)
                    else -> return@withContext null
                }
                ImageDecoder.decodeBitmap(source) { d, info, _ ->
                    val longest = maxOf(info.size.width, info.size.height)
                    if (longest > 360) {
                        val s = 360.0 / longest
                        d.setTargetSize((info.size.width * s).toInt(), (info.size.height * s).toInt())
                    }
                    d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                }
            }.getOrNull()
        }
        if (bmp == null) failed = true
    }
    Box(modifier.background(Color(0xFF202124))) {
        val image = bmp
        if (image != null) {
            Image(
                bitmap = image.asImageBitmap(),
                contentDescription = displayName,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else if (failed) {
            Text(
                displayName.substringAfterLast('.', "?").uppercase(),
                color = Color(0xFF9AA0A6),
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.align(Alignment.Center).padding(4.dp),
            )
        }
    }
}
