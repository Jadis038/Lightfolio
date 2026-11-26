package com.yingce.gallery.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.yingce.gallery.data.UpdateChecker
import kotlinx.coroutines.launch
import java.io.File

/**
 * 更新弹窗：发现新版本 → 用户确认 → 下载 → 调起系统安装器。
 *
 * 下载与校验都在 IO 线程进行；UI 只显示进度。
 * 整个过程不需要任何第三方网络库（HttpURLConnection 足够）。
 */
@Composable
fun UpdateDialog(
    info: UpdateChecker.UpdateInfo,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var phase by remember { mutableStateOf(Phase.Idle) }
    var downloaded by remember { mutableStateOf(0L) }
    var total by remember { mutableStateOf(0L) }
    var message by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = { if (phase != Phase.Downloading) onDismiss() },
        title = { Text("发现新版本 ${info.versionName}") },
        text = {
            Column {
                if (info.notes.isNotBlank()) {
                    Column(
                        modifier = Modifier
                            .heightIn(max = 220.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        Text(info.notes, style = MaterialTheme.typography.bodySmall)
                    }
                    Spacer(Modifier.height(12.dp))
                }
                if (info.sizeBytes > 0) {
                    Text(
                        "安装包大小：${"%.1f".format(info.sizeBytes / 1024.0 / 1024.0)} MB",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                when (phase) {
                    Phase.Downloading -> {
                        Spacer(Modifier.height(14.dp))
                        val frac = if (total > 0) (downloaded.toFloat() / total).coerceIn(0f, 1f) else 0f
                        LinearProgressIndicator(
                            progress = { frac },
                            modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = if (total > 0) {
                                "${"%.1f".format(downloaded / 1024.0 / 1024.0)} / ${"%.1f".format(total / 1024.0 / 1024.0)} MB"
                            } else {
                                "已下载 ${"%.1f".format(downloaded / 1024.0 / 1024.0)} MB"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Phase.Done -> {
                        Spacer(Modifier.height(10.dp))
                        Text("下载完成，请在系统安装界面继续", style = MaterialTheme.typography.labelSmall)
                    }
                    else -> Unit
                }
                message?.let {
                    Spacer(Modifier.height(10.dp))
                    Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = phase != Phase.Downloading,
                onClick = {
                    message = null
                    phase = Phase.Downloading
                    downloaded = 0
                    total = info.sizeBytes
                    scope.launch {
                        runCatching {
                            // 先确认是否允许安装未知来源应用
                            if (!UpdateChecker.hasInstallPermission(context)) {
                                openUnknownSourcesSettings(context)
                                error("请先允许「安装未知应用」，然后返回重新点击更新")
                            }
                            val file = UpdateChecker.download(context, info) { d, t ->
                                downloaded = d
                                if (t > 0) total = t
                            }
                            phase = Phase.Done
                            installApk(context, file)
                        }.onFailure { t ->
                            phase = Phase.Idle
                            message = t.message ?: "下载失败"
                        }
                    }
                },
            ) {
                Text("立即更新", fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = phase != Phase.Downloading) { Text("稍后") }
        },
    )
}

private enum class Phase { Idle, Downloading, Done }

private fun openUnknownSourcesSettings(context: Context) {
    runCatching {
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}

private fun installApk(context: Context, file: File) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, "application/vnd.android.package-archive")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(intent)
}

/**
 * 启动时的自动检查：只在开关打开时执行；失败静默（不打扰用户）。
 */
@Composable
fun AutoUpdateCheck(enabled: Boolean, onFound: (UpdateChecker.UpdateInfo) -> Unit) {
    val context = LocalContext.current
    LaunchedEffect(enabled) {
        if (!enabled) return@LaunchedEffect
        kotlinx.coroutines.delay(1200) // 让首页先渲染完
        runCatching { UpdateChecker.check(context) }.getOrNull()?.let(onFound)
    }
}

/** 应用信息页里的「检查更新」按钮用：返回结果文本给人看。 */
@Composable
fun ManualUpdateCheckSection(
    onFound: (UpdateChecker.UpdateInfo) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth(),
        ) {
            TextButton(
                enabled = !checking,
                onClick = {
                    checking = true
                    status = "正在检查…"
                    scope.launch {
                        runCatching { UpdateChecker.check(context) }
                            .onSuccess { info ->
                                if (info == null) {
                                    status = "已是最新版本（${UpdateChecker.localVersionName(context)}）"
                                } else {
                                    status = "发现新版本 ${info.versionName}"
                                    onFound(info)
                                }
                            }
                            .onFailure { status = "检查失败：${it.message ?: "网络不可用"}" }
                        checking = false
                    }
                },
            ) { Text("检查更新") }
            var auto by remember { mutableStateOf(UpdatePrefsLocal(context)) }
            TextButton(
                onClick = {
                    auto = !auto
                    com.yingce.gallery.data.UpdatePrefs.setAutoCheckEnabled(context, auto)
                },
            ) { Text(if (auto) "启动自动检查：开" else "启动自动检查：关") }
        }
        status?.let {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(10.dp),
            ) { Text(it, style = MaterialTheme.typography.labelSmall) }
        }
    }
}

private fun UpdatePrefsLocal(context: Context): Boolean =
    com.yingce.gallery.data.UpdatePrefs.isAutoCheckEnabled(context)
