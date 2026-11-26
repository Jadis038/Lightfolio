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
import com.yingce.gallery.data.Photo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 平台 ImageDecoder 查看页（JXL / EXR 这类 Coil 认不出的格式）。
 *
 * 部分厂商 ROM（如 ColorOS）在系统解码器里加了 JPEG XL 支持，这时能直接显示；
 * 没有支持的系统会明确提示，而不是黑屏无反应。
 */
@Composable
fun PlatformImageViewerPage(photo: Photo, onLoaded: (Int, Int) -> Unit = { _, _ -> }, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var bitmap by remember(photo.id) { mutableStateOf<Bitmap?>(null) }
    var note by remember(photo.id) { mutableStateOf("正在解码 ${photo.displayName.substringAfterLast('.')}…") }

    LaunchedEffect(photo.id) {
        val result = withContext(Dispatchers.IO) {
            runCatching {
                val source = when (val m = PhotoSource.model(context, photo)) {
                    is java.io.File -> ImageDecoder.createSource(m)
                    is android.net.Uri -> ImageDecoder.createSource(context.contentResolver, m)
                    else -> return@withContext null to "无法定位文件"
                }
                ImageDecoder.decodeBitmap(source) { d, _, _ ->
                    d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                }
            }.fold(
                onSuccess = { bmp -> bmp to "平台解码成功" },
                onFailure = { t ->
                    null to "本机系统解码器不支持该格式（${t.javaClass.simpleName}）"
                },
            )
        }
        bitmap = result.first
        note = result.second
        result.first?.let { onLoaded(it.width, it.height) }
    }

    Box(modifier = modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        val bmp = bitmap
        if (bmp != null) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = photo.displayName,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text(note, color = Color.White, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(24.dp))
        }
    }
}
