package com.yingce.gallery.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.crossfade
import com.yingce.gallery.data.Photo

/**
 * 作品网格。支持选择模式（长按进入），选中项显示高亮与勾选角标。
 *
 * 性能注意：单元格内容刻意保持轻量（无阴影、无动画），
 * 2000 项滚动时决定帧时间的是图片解码与列表复用，而不是这里的装饰。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GalleryGrid(
    photos: List<Photo>,
    selectionMode: Boolean,
    selectedIds: Set<Long>,
    onOpen: (Int) -> Unit,
    onToggleSelect: (Long) -> Unit,
    onLongPress: (Long) -> Unit,
    gridState: androidx.compose.foundation.lazy.grid.LazyGridState = rememberLazyGridState(),
    /** 顶部为悬浮玻璃栏预留的高度 */
    topInset: androidx.compose.ui.unit.Dp = 0.dp,
) {
    val context = LocalContext.current

    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        state = gridState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = topInset + 8.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        itemsIndexed(items = photos, key = { _, photo -> photo.id }) { index, photo ->
            val selected = photo.id in selectedIds
            Column(
                modifier = Modifier.combinedClickable(
                    onClick = {
                        if (selectionMode) onToggleSelect(photo.id) else onOpen(index)
                    },
                    onLongClick = { onLongPress(photo.id) },
                ),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    if (com.yingce.gallery.data.MediaTypes.isVideoByName(photo.displayName)) {
                        VideoThumbnail(
                            model = PhotoSource.model(context, photo),
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        // TIFF / JXL / EXR 走专门路径，其余仍交给 Coil（含缓存）
                        SmartThumbnail(
                            model = PhotoSource.model(context, photo),
                            displayName = photo.displayName,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    if (selected) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.28f)),
                        )
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(6.dp)
                                .size(20.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(MaterialTheme.colorScheme.primary),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("✓", color = Color.White, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    if (photo.gpsLat != null) {
                        Text(
                            text = "📍",
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.align(Alignment.BottomStart).padding(4.dp),
                        )
                    }
                }
                // 界面只显示 display_name，绝不显示 stored_name 的 (1)(2) 后缀
                Text(
                    text = photo.displayName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().padding(top = 3.dp),
                )
            }
        }
    }
}
