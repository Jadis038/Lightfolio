package com.yingce.gallery.ui

import android.graphics.Bitmap
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.yingce.gallery.data.Photo
import com.yingce.gallery.format.TiffDecoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** TIFF 查看页：用内置 TiffDecoder 抽样解码到屏幕宽度。 */
@Composable
fun TiffViewerPage(
    photo: Photo,
    onLoaded: (Int, Int) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var bitmap by remember(photo.id) { mutableStateOf<Bitmap?>(null) }
    var note by remember(photo.id) { mutableStateOf("正在解码 TIFF…") }

    val widthDp = LocalConfiguration.current.screenWidthDp
    val density = LocalDensity.current
    val targetPx = with(density) { widthDp.dp.roundToPx() }

    LaunchedEffect(photo.id) {
        val result = withContext(Dispatchers.IO) {
            val file = PhotoSource.fileOf(context, photo)
                ?: return@withContext null to "副本不存在"
            val bmp = TiffDecoder.decodeScaled(file, targetPx)
            if (bmp != null) bmp to "TIFF 解码完成" else null to "TIFF 解码器不支持该文件的压缩/布局（目前支持未压缩/PackBits/Deflate，RGB 或灰度）"
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
            Text(
                text = note,
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(24.dp),
            )
        }
    }
}
