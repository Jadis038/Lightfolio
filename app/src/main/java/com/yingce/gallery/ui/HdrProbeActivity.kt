package com.yingce.gallery.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.yingce.gallery.hdr.HdrSupport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/**
 * HDR / 色域探测页（debug）。
 *
 * 输出两段内容：
 *  1) 设备 HDR 能力的**原始值**（Display.getHdrCapabilities + 色模式 + 亮度范围）
 *  2) 目录里每个视频文件的轨道级判定（位深/原色/传输函数/范围/HDR 类型/决策）
 *
 * 启动：
 *   adb shell am start -n com.yingce.gallery.debug/com.yingce.gallery.ui.HdrProbeActivity \
 *       --es path /sdcard/Android/data/com.yingce.gallery.debug/files/video-test
 */
class HdrProbeActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val path = intent?.getStringExtra("path") ?: ""
        setContent {
            YingceTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    HdrProbeScreen(path)
                }
            }
        }
    }
}

@Composable
private fun HdrProbeScreen(path: String) {
    val context = LocalContext.current
    var lines by remember { mutableStateOf(listOf<String>()) }
    var done by remember { mutableStateOf(false) }

    LaunchedEffect(path) {
        // ExoPlayer 必须在 main 线程创建与访问，因此整段探测跑在主线程（内部用 delay 让出）
        val out = withContext(Dispatchers.Main) {
            val sb = StringBuilder()
            val device = HdrSupport.probe(context)
            sb.appendLine("# 影·册 M6 · HDR / 色域探测")
            sb.appendLine()
            sb.appendLine("## 设备能力（原始值）")
            sb.appendLine("supportedHdrTypes = ${device.supportedTypes}  ${device.typeNames()}")
            sb.appendLine("maxLuminance=${device.maxLuminance} maxAverageLuminance=${device.maxAverageLuminance} minLuminance=${device.minLuminance}")
            sb.appendLine("isWideColorGamut=${device.wideColorGamut}")
            sb.appendLine("colorMode=${device.colorMode} (${HdrSupport.colorModeName(device.colorMode)})")
            sb.appendLine("supportedColorModes=${device.supportedColorModes} " +
                device.supportedColorModes.joinToString { HdrSupport.colorModeName(it) })
            sb.appendLine("屏幕=${device.widthPx}×${device.heightPx} @ ${device.refreshRate}Hz")
            sb.appendLine("SDR 峰值亮度: 无公开 API，保守按 ${HdrSupport.ASSUMED_SDR_WHITE_NITS.toInt()} nits")
            sb.appendLine()
            sb.appendLine("## 逐文件轨道判定")

            val files = if (path.isBlank()) emptyList() else
                (File(path).listFiles()?.filter { it.isFile }?.sortedBy { it.name } ?: emptyList())

            for (f in files) {
                val line = probeFile(context, f)
                sb.appendLine(line)
            }
            if (files.isEmpty()) sb.appendLine("(目录为空或不可读: $path)")

            val target = File(context.getExternalFilesDir(null) ?: context.filesDir, "hdr_probe.md")
            target.writeText(sb.toString())
            android.util.Log.i("YingCeHdr", "\n$sb")
            sb.toString() to target.absolutePath
        }
        lines = out.first.lines()
        android.util.Log.i("YingCeHdr", "report=${out.second}")
        done = true
    }

    Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
        Text("HDR / 色域探测", style = MaterialTheme.typography.titleLarge)
        Text(if (done) "已生成 hdr_probe.md" else "探测中…", style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(8.dp))
        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
            lines.forEach { l ->
                Text(l, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
            }
        }
    }
}

/** 用 ExoPlayer 只读轨道信息（不渲染、不出声），拿到 Format.colorInfo 后立即释放。 */
private suspend fun probeFile(context: android.content.Context, file: File): String {
    val player = ExoPlayer.Builder(context).build()
    return try {
        player.setMediaItem(MediaItem.fromUri(android.net.Uri.fromFile(file)))
        player.prepare()

        val tracks = withTimeoutOrNull(8_000) {
            while (player.currentTracks.groups.isEmpty()) delay(100)
            player.currentTracks
        }
        if (tracks == null || tracks.groups.isEmpty()) {
            "${file.name}\t无法读取轨道（容器不受支持或文件损坏）"
        } else {
            val device = HdrSupport.probe(context)
            val sb = StringBuilder()
            var reported = false
            for (group in tracks.groups) {
                for (i in 0 until group.length) {
                    val format = group.getTrackFormat(i)
                    if (format.width <= 0 || format.height <= 0) continue
                    val r = HdrSupport.describeTrack(format, device)
                    sb.appendLine()
                    sb.appendLine("${file.name}  ${r.width}×${r.height}  ${r.mimeType ?: "-"}")
                    sb.appendLine("  位深=${r.bitDepth} 原色=${r.primaries} 传输=${r.transfer} 范围=${r.range}")
                    sb.appendLine("  HDR=${r.isHdr} 类型=${r.hdrTypeName ?: "N/A"} 设备支持=${r.deviceSupports}")
                    sb.appendLine("  决策=${r.decision}")
                    reported = true
                }
            }
            if (!reported) "${file.name}\t无视频轨"
            else sb.toString().trimEnd()
        }
    } finally {
        runCatching { player.release() }
    }
}
