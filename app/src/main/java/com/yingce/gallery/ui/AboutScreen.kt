package com.yingce.gallery.ui

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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 应用信息页。
 *
 * 文案按用户给定内容原样展示；后续如需补充版本号/许可信息，加在这里。
 */
@Composable
fun AboutScreen(onBack: () -> Unit) {
    // 返回键/侧滑手势先退出本页，不要直接退出应用
    androidx.activity.compose.BackHandler(enabled = true) { onBack() }
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 16.dp),
        ) {
            TextButton(onClick = onBack) { Text("← 返回") }
            Spacer(Modifier.height(12.dp))
            Text("应用信息", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(24.dp))

            YingceGlass.GlassBox(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                Column(modifier = Modifier.padding(20.dp)) {
                    InfoLine("此软件由Галин制作")
                    InfoLine("此软件由DeepSeek v4.1 Flash制作")
                    InfoLine("软件图标来自Ho Wing Own")
                    InfoLine("测试机型：")
                    InfoLine("OPPO Find X10 Pro Max、SAMSUNG Galaxy S24 Ultra")
                    Spacer(Modifier.height(14.dp))
                    InfoLine("已实测支持的RAW：")
                    InfoLine("佳能5D3、尼康Z8、大疆Air3、索尼α6700、OPPO Find X10 Pro Max、三星S24 Ultra")
                    Spacer(Modifier.height(14.dp))
                    InfoLine("已支持的照片格式：")
                    InfoLine("JPEG、PNG、AVIF、HEIC、TIFF（暂不支持LZW压缩的TIFF）")
                }
            }
        }
    }
}

@Composable
private fun InfoLine(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(vertical = 8.dp),
    )
}
