package com.yingce.gallery.ui

import android.net.Uri
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.yingce.gallery.hdr.HdrSupport
import kotlinx.coroutines.delay
import java.io.File

private val SPEEDS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)

/**
 * 视频播放页（Media3 ExoPlayer）。
 *
 * 播放器本身不强行指定硬解/软解：由平台按能力选择，避免"强行硬解导致偏色或崩溃"。
 * HDR 判断只做"描述 + 标注"，不做强制转换；设备不支持的类型交给平台色调映射。
 */
@Composable
fun VideoPlayerPage(
    model: Any,
    isFullscreen: Boolean,
    onToggleFullscreen: () -> Unit,
    onStatus: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    val player = remember {
        ExoPlayer.Builder(context).build().apply {
            playWhenReady = true
        }
    }
    DisposableEffect(Unit) {
        onDispose { player.release() }
    }

    var isPlaying by remember { mutableStateOf(true) }
    var positionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var speedIndex by remember { mutableStateOf(SPEEDS.indexOf(1f)) }
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }
    var controlBarVisible by remember { mutableStateOf(true) }

    LaunchedEffect(model) {
        val item = MediaItem.fromUri(mediaUri(context, model))
        player.setMediaItem(item)
        player.prepare()
        onStatus("准备播放…")
    }

    // 轨道信息 → HDR / 色域判定（每 800ms 取一次，直到拿到视频轨）
    LaunchedEffect(Unit) {
        var lastKey = ""
        while (true) {
            runCatching {
                val device = HdrSupport.probe(context)
                val tracks = player.currentTracks
                for (group in tracks.groups) {
                    for (i in 0 until group.length) {
                        val format = group.getTrackFormat(i)
                        if (!group.isTrackSupported(i)) continue
                        if (format.width <= 0 || format.height <= 0) continue
                        val report = HdrSupport.describeTrack(format, device)
                        val key = "${report.width}x${report.height}:${report.transfer}:${report.decision}"
                        if (key != lastKey) {
                            lastKey = key
                            onStatus(
                                "${report.width}×${report.height} · ${report.mimeType ?: "-"} · " +
                                    "${report.primaries}/${report.transfer} · ${report.decision}"
                            )
                            android.util.Log.i(
                                "YingCeHdr",
                                "track=${format.sampleMimeType} ${format.width}x${format.height} " +
                                    "primaries=${report.primaries} transfer=${report.transfer} " +
                                    "range=${report.range} bitdepth=${report.bitDepth} " +
                                    "hdr=${report.isHdr} supports=${report.deviceSupports} -> ${report.decision}",
                            )
                        }
                    }
                }
            }
            delay(800)
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            if (!dragging) {
                positionMs = player.currentPosition.coerceAtLeast(0L)
                durationMs = player.duration.let { if (it > 0) it else 0L }
                isPlaying = player.isPlaying
            }
            delay(300)
        }
    }

    Box(modifier = modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    this.player = player
                    useController = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        // 控制条
        if (controlBarVisible) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.55f))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = formatTime(if (dragging) dragValue.toLong() else positionMs),
                        color = Color.White,
                        style = MaterialTheme.typography.labelSmall,
                    )
                    Slider(
                        value = if (dragging) dragValue else positionMs.toFloat(),
                        onValueChange = {
                            dragging = true
                            dragValue = it
                        },
                        onValueChangeFinished = {
                            player.seekTo(dragValue.toLong())
                            positionMs = dragValue.toLong()
                            dragging = false
                        },
                        valueRange = 0f..(durationMs.coerceAtLeast(1L)).toFloat(),
                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                    )
                    Text(
                        text = formatTime(durationMs),
                        color = Color.White,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row {
                        TextButton(onClick = {
                            if (player.isPlaying) player.pause() else player.play()
                            isPlaying = player.isPlaying
                        }) {
                            Text(if (isPlaying) "暂停" else "播放", color = Color.White, style = MaterialTheme.typography.labelMedium)
                        }
                        TextButton(onClick = {
                            speedIndex = (speedIndex + 1) % SPEEDS.size
                            player.setPlaybackSpeed(SPEEDS[speedIndex])
                        }) {
                            Text("${SPEEDS[speedIndex]}×", color = Color.White, style = MaterialTheme.typography.labelMedium)
                        }
                        TextButton(onClick = { player.seekTo((positionMs - 10_000).coerceAtLeast(0L)) }) {
                            Text("« 10s", color = Color.White, style = MaterialTheme.typography.labelMedium)
                        }
                        TextButton(onClick = { player.seekTo(positionMs + 10_000) }) {
                            Text("10s »", color = Color.White, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                    TextButton(onClick = onToggleFullscreen) {
                        Text(if (isFullscreen) "退出全屏" else "全屏", color = Color.White, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        } else {
            TextButton(
                onClick = { controlBarVisible = true },
                modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp),
            ) { Text("显示控件", color = Color.White) }
        }
    }
}

private fun mediaUri(context: android.content.Context, model: Any): Uri = when (model) {
    is Uri -> model
    is File -> Uri.fromFile(model)
    is String -> Uri.parse(model)
    else -> Uri.EMPTY
}

private fun formatTime(ms: Long): String {
    if (ms <= 0) return "00:00"
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) String.format(java.util.Locale.US, "%d:%02d:%02d", h, m, s)
    else String.format(java.util.Locale.US, "%02d:%02d", m, s)
}
