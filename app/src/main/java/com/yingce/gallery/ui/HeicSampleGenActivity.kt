package com.yingce.gallery.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.heifwriter.HeifWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 生成一张 HEIC 样片（debug 用），用于验证 HEIC 的 EXIF 提取。
 *
 * 流程：本页生成 HEIC → 拉到电脑 → exiftool 注入 GPS/机型 → 推回设备 → 跑诊断。
 */
class HeicSampleGenActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            YingceTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { Screen() }
            }
        }
    }
}

@Composable
private fun Screen() {
    val context = LocalContext.current
    var lines by remember { mutableStateOf(listOf("生成中…")) }
    LaunchedEffect(Unit) {
        val out = withContext(Dispatchers.IO) {
            val dir = context.getExternalFilesDir(null) ?: context.filesDir
            val target = File(dir, "sample_no_exif.heic")
            runCatching {
                val srcFile = File(context.filesDir, "photos/YingCe_M3_01.jpg")
                val bmp = if (srcFile.exists()) {
                    BitmapFactory.decodeFile(srcFile.absolutePath)
                } else {
                    Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888).apply {
                        eraseColor(android.graphics.Color.rgb(40, 90, 160))
                    }
                }
                val scaled = Bitmap.createScaledBitmap(bmp, 640, 480, true)
                writeHeic(scaled, target)
                scaled.recycle()
                if (bmp !== scaled) bmp.recycle()
            }.fold(
                onSuccess = { listOf("已生成: ${target.absolutePath}", "${target.length() / 1024} KB") },
                onFailure = { listOf("失败: ${it.javaClass.simpleName}: ${it.message}") },
            )
        }
        lines = out
        android.util.Log.i("YingCeHeicGen", out.joinToString("\n"))
    }
    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Text("HEIC 样片生成", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        lines.forEach { Text(it, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace) }
    }
}

private fun writeHeic(bmp: Bitmap, target: File) {
    if (target.exists()) target.delete()
    val w = bmp.width
    val h = bmp.height
    val argb = IntArray(w * h)
    bmp.getPixels(argb, 0, w, 0, 0, w, h)

    val y = ByteArray(w * h)
    val u = ByteArray(w * h / 4)
    val v = ByteArray(w * h / 4)
    for (j in 0 until h) {
        for (i in 0 until w) {
            val c = argb[j * w + i]
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            y[j * w + i] = (((66 * r + 129 * g + 25 * b + 128) shr 8) + 16).coerceIn(0, 255).toByte()
        }
    }
    for (j in 0 until h / 2) {
        for (i in 0 until w / 2) {
            var ru = 0
            var rv = 0
            for (dj in 0..1) {
                for (di in 0..1) {
                    val c = argb[(j * 2 + dj) * w + (i * 2 + di)]
                    val r = (c shr 16) and 0xFF
                    val g = (c shr 8) and 0xFF
                    val b = c and 0xFF
                    ru += ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                    rv += ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
                }
            }
            u[j * (w / 2) + i] = (ru / 4).coerceIn(0, 255).toByte()
            v[j * (w / 2) + i] = (rv / 4).coerceIn(0, 255).toByte()
        }
    }
    // NV12：Y 平面 + 交错 UV
    val plane = ByteArray(w * h * 3 / 2)
    System.arraycopy(y, 0, plane, 0, y.size)
    var k = y.size
    for (i in u.indices) {
        plane[k++] = u[i]
        plane[k++] = v[i]
    }

    val writer = HeifWriter.Builder(target.absolutePath, w, h, HeifWriter.INPUT_MODE_BUFFER)
        .setQuality(90)
        .build()
    writer.start()
    writer.addYuvBuffer(android.media.MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar, plane)
    writer.stop(0L)
}
