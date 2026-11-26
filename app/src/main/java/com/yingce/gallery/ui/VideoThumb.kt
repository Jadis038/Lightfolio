package com.yingce.gallery.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.media.MediaMetadataRetriever
import android.net.Uri
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 视频封面：用 MediaMetadataRetriever 抽一帧。
 * Coil 不自带视频解码器，直接喂视频 URI 只会失败，所以单独走这条路。
 */
@Composable
fun VideoThumbnail(model: Any, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var bmp by remember(model) { mutableStateOf<Bitmap?>(null) }
    var failed by remember(model) { mutableStateOf(false) }

    LaunchedEffect(model) {
        bmp = null
        failed = false
        val frame = withContext(Dispatchers.IO) { runCatching { extractFrame(context, model) }.getOrNull() }
        if (frame == null) failed = true else bmp = frame
    }

    Box(modifier = modifier.background(androidx.compose.ui.graphics.Color(0xFF202124))) {
        val image = bmp
        if (image != null) {
            Image(
                bitmap = image.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else if (failed) {
            Text(
                text = "无封面",
                style = MaterialTheme.typography.labelSmall,
                color = androidx.compose.ui.graphics.Color(0xFF9AA0A6),
                modifier = Modifier.align(Alignment.Center),
            )
        }
        // 播放角标
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.45f), androidx.compose.foundation.shape.CircleShape)
                .padding(6.dp),
        ) {
            Text("▶", color = androidx.compose.ui.graphics.Color.White, style = MaterialTheme.typography.labelMedium)
        }
    }
}



private fun extractFrame(context: Context, model: Any): Bitmap? {
    val retriever = MediaMetadataRetriever()
    return try {
        when (model) {
            is Uri -> retriever.setDataSource(context, model)
            is java.io.File -> retriever.setDataSource(model.absolutePath)
            is String -> retriever.setDataSource(model)
            else -> return null
        }
        // 优先取 1s 处的帧，避免黑场首帧
        retriever.getFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            ?: retriever.getFrameAtTime()
    } catch (_: Throwable) {
        null
    } finally {
        runCatching { retriever.release() }
    }
}
