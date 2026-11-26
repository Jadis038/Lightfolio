package com.yingce.gallery.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import com.yingce.gallery.format.DecodeState
import com.yingce.gallery.format.FormatProbe
import com.yingce.gallery.format.FormatReport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Debug 自测入口：遍历指定目录，逐文件输出探测报告。
 *
 * 启动方式（不联网、不发 Intent 给外部）：
 *   adb shell am start -n com.yingce.gallery.debug/com.yingce.gallery.ui.SelfTestActivity \
 *       --es path /sdcard/Pictures/yingce-test
 */
class SelfTestActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val path = intent?.getStringExtra("path") ?: "/sdcard/Pictures/yingce-test"
        setContent {
            YingceTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    SelfTestScreen(path)
                }
            }
        }
    }
}

@Composable
private fun SelfTestScreen(path: String) {
    val context = LocalContext.current
    var lines by remember { mutableStateOf(listOf<String>()) }
    var done by remember { mutableStateOf(false) }
    var summary by remember { mutableStateOf("") }

    LaunchedEffect(path) {
        val result = withContext(Dispatchers.IO) {
            // 说明：Android 11+ 即使有 READ_MEDIA_IMAGES 也不能 listFiles() 任意目录，
            // 因此优先走 MediaStore 枚举，必要时把内容拷到缓存再探测（libraw 需要真实路径）。
            val files = SelfTestFiles.collect(context, path)
            val reports = files.map { f ->
                val r = FormatProbe.probe(context, f)
                r
            }
            val counts = reports.groupingBy { it.state }.eachCount()
            val header = "file\tcontainer\tcodec\tbitdepth\tcolorspace\thdr\tresolution\tstate\telapsed\treason"
            val body = reports.map { it.toLine() }

            val sb = StringBuilder()
            sb.appendLine("# 影·册 M5 自测报告")
            sb.appendLine()
            sb.appendLine("目录: $path")
            sb.appendLine("文件数: ${files.size}")
            sb.appendLine("NATIVE=${counts[DecodeState.NATIVE] ?: 0}  DEGRADED=${counts[DecodeState.DEGRADED] ?: 0}  LIMITED=${counts[DecodeState.LIMITED] ?: 0}")
            sb.appendLine()
            sb.appendLine(header)
            body.forEach { sb.appendLine(it) }

            val outDir = context.getExternalFilesDir(null) ?: context.filesDir
            val report = File(outDir, "selftest_report.md")
            report.writeText(sb.toString())
            android.util.Log.i("SelfTest", "report=${report.absolutePath}\n${sb}")

            Triple(body, "${counts[DecodeState.NATIVE] ?: 0} 原生 / ${counts[DecodeState.DEGRADED] ?: 0} 降级 / ${counts[DecodeState.LIMITED] ?: 0} 有限 · ${files.size} 个文件", report.absolutePath)
        }
        lines = result.first
        summary = result.second
        android.util.Log.i("SelfTest", "written: ${result.third}")
        done = true
    }

    Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
        Text("格式自测", style = MaterialTheme.typography.titleLarge)
        Text(path, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(8.dp))
        Text(if (done) summary else "正在探测…", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(8.dp))
        Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
            lines.take(60).forEach { l ->
                Text(l, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, maxLines = 2)
            }
            if (lines.size > 60) Text("… 其余 ${lines.size - 60} 行见报告文件", style = MaterialTheme.typography.labelSmall)
        }
    }
}
