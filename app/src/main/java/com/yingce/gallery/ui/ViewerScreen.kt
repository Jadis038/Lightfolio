package com.yingce.gallery.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateIntOffsetAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.yingce.gallery.data.Photo
import java.io.File

private val RAW_EXTS = setOf(
    "dng", "cr2", "cr3", "nef", "arw", "raf", "orf", "rw2", "pef", "srw",
    "x3f", "3fr", "mos", "mef", "dcr", "mrw", "erf", "sr2", "srf", "raw", "nrw",
)

internal fun isPlatformOnlyFormat(name: String): Boolean {
    val ext = name.substringAfterLast('.', "").lowercase()
    return ext == "jxl" || ext == "exr"
}

internal fun isTiffName(name: String): Boolean {
    val ext = name.substringAfterLast('.', "").lowercase()
    return ext == "tif" || ext == "tiff"
}

internal fun isRawName(name: String): Boolean {
    val ext = name.substringAfterLast('.', "").lowercase()
    return ext in RAW_EXTS
}

@Composable
fun ViewerScreen(
    photos: List<Photo>,
    startIndex: Int,
    onClose: () -> Unit,
    onDelete: (Photo) -> Unit,
) {
    val context = LocalContext.current
    val pagerState = rememberPagerState(initialPage = startIndex.coerceIn(0, photos.lastIndex)) {
        photos.size
    }

    var expanded by remember { mutableStateOf(false) }
    var videoFullscreen by remember { mutableStateOf(false) }
    var videoStatus by remember { mutableStateOf("") }
    var dragAccum by remember { mutableFloatStateOf(0f) }
    var pendingDelete by remember { mutableStateOf<Photo?>(null) }

    val screenHeightDp = LocalConfiguration.current.screenHeightDp
    val panelHeight = (screenHeightDp * 0.62f).dp
    // 拖动条加高：露出更多可拖区域，减少误触退出
    val handleHeight = 104.dp
    val density = LocalDensity.current
    val hiddenPx = with(density) { (panelHeight - handleHeight).roundToPx() }
    val offset by animateIntOffsetAsState(
        targetValue = IntOffset(0, if (expanded) 0 else hiddenPx),
        animationSpec = spring(dampingRatio = 0.78f, stiffness = Spring.StiffnessMediumLow),
        label = "infoSheet",
    )

    BackHandler(enabled = true) {
        when {
            pendingDelete != null -> pendingDelete = null
            expanded -> expanded = false
            else -> onClose()
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            val photo = photos[page]
            if (com.yingce.gallery.data.MediaTypes.isVideoByName(photo.displayName)) {
                VideoPlayerPage(
                    model = PhotoSource.model(context, photo),
                    isFullscreen = videoFullscreen,
                    onToggleFullscreen = { videoFullscreen = !videoFullscreen },
                    onStatus = { videoStatus = it },
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                // 非视频：统一包一层缩放容器（双指放大 / 双击 100%）
                var intrinsicWidth by remember(photo.id) { mutableStateOf<Int?>(null) }
                Zoomable(intrinsicWidth = intrinsicWidth) {
                    if (isTiffName(photo.displayName)) {
                        TiffViewerPage(
                            photo = photo,
                            onLoaded = { w, _ -> intrinsicWidth = w },
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else if (isRawName(photo.displayName)) {
                        // RAW 走原生解码（LibRaw），不交给 Coil
                        RawViewerPage(
                            photo = photo,
                            downsample = expanded,
                            onLoaded = { w, _ -> intrinsicWidth = w },
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else if (isPlatformOnlyFormat(photo.displayName)) {
                        // JXL / EXR：Coil 解不了，交给平台 ImageDecoder（部分 ROM 支持 JXL）
                        PlatformImageViewerPage(
                            photo = photo,
                            onLoaded = { w, _ -> intrinsicWidth = w },
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        AsyncImage(
                            model = PhotoSource.model(context, photo),
                            contentDescription = photo.displayName,
                            contentScale = ContentScale.Fit,
                            onSuccess = { s -> intrinsicWidth = s.result.image.width },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }

        // HDR/色域状态（视频页显示，便于现场判断有没有偏色）
        if (videoStatus.isNotBlank() && !videoFullscreen) {
            Text(
                text = videoStatus,
                color = Color(0xFFB0BEC5),
                style = MaterialTheme.typography.labelSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(top = 56.dp, start = 12.dp, end = 60.dp)
                    .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }

        // 顶部条：文件名 + 删除 + 关闭
        if (!videoFullscreen) Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(if (isRawName(photos[pagerState.currentPage].displayName)) Color.Black else Color.Black.copy(alpha = 0.45f))
                .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = photos[pagerState.currentPage].displayName,
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { pendingDelete = photos[pagerState.currentPage] }) {
                Text("删除", color = Color(0xFFFFB4AB))
            }
            TextButton(onClick = onClose) {
                Text("关闭", color = Color.White)
            }
        }

        // 底部可上滑展开的信息面板
        val current = photos[pagerState.currentPage]
        // RAW 预览时去掉面板的半透明效果：不透明底不参与与下层图像的混合，
        // 减少一帧内的混合开销（RAW 解码已经很重，这里再省一点）。
        val currentIsRaw = isRawName(current.displayName)
        if (!videoFullscreen) Surface(
            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
            color = if (currentIsRaw) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(panelHeight)
                .offset { offset },
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // 拖拽把手：上滑展开 / 下滑收起
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(handleHeight)
                        .pointerInput(expanded) {
                            detectVerticalDragGestures(
                                onDragStart = { dragAccum = 0f },
                                onDragEnd = {
                                    if (dragAccum < -60f) expanded = true
                                    else if (dragAccum > 60f) expanded = false
                                    dragAccum = 0f
                                },
                                onVerticalDrag = { _, delta -> dragAccum += delta },
                            )
                        }
                        .padding(horizontal = 20.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Column {
                        Box(
                            modifier = Modifier
                                .align(Alignment.CenterHorizontally)
                                .width(42.dp)
                                .height(4.dp)
                                .background(
                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                    RoundedCornerShape(2.dp),
                                ),
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = if (expanded) "下滑收起" else "上滑查看信息",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                DetailContent(
                    photo = current,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }

    pendingDelete?.let { photo ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("从影·册中删除？") },
            text = {
                Text(
                    "只会删除 App 内的副本与记录，不会删除手机里的原文件。\n\n" +
                        "文件：${photo.displayName}"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    onDelete(photo)
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            },
        )
    }
}
