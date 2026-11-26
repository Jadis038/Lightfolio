package com.yingce.gallery.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.yingce.gallery.data.DeviceImage
import com.yingce.gallery.data.GalleryAccess
import com.yingce.gallery.data.ImportItem
import com.yingce.gallery.data.MediaStoreSource
import com.yingce.gallery.data.SourceKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 应用内相册选择器（MediaStore 来源）。
 *
 * Android 14+ 用户可能只授予"部分照片"（READ_MEDIA_VISUAL_USER_SELECTED），
 * 此时顶部出现"申请访问全部"入口，点击后再次请求 READ_MEDIA_IMAGES。
 */
@Composable
fun MediaPickerScreen(
    onClose: () -> Unit,
    onConfirm: (List<ImportItem>) -> Unit,
) {
    val context = LocalContext.current
    var fullAccess by remember { mutableStateOf(GalleryAccess.hasFullAccess(context)) }
    var partialAccess by remember { mutableStateOf(GalleryAccess.hasPartialAccess(context)) }
    var images by remember { mutableStateOf<List<DeviceImage>>(emptyList()) }
    // 0=全部（照片+视频） 1=照片 2=视频
    var category by remember { mutableStateOf(0) }
    val shown = remember(images, category) {
        when (category) {
            1 -> images.filter { !com.yingce.gallery.data.MediaTypes.isVideoByName(it.displayName) }
            2 -> images.filter { com.yingce.gallery.data.MediaTypes.isVideoByName(it.displayName) }
            else -> images
        }
    }
    var loading by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Set<Long>>(emptySet()) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        fullAccess = GalleryAccess.hasFullAccess(context)
        partialAccess = GalleryAccess.hasPartialAccess(context)
    }

    val requestAllLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        fullAccess = GalleryAccess.hasFullAccess(context)
        partialAccess = GalleryAccess.hasPartialAccess(context)
    }

    LaunchedEffect(fullAccess, partialAccess) {
        if (GalleryAccess.hasAnyAccess(context)) {
            loading = true
            // M6：图片 + 视频一起列出，按修改时间倒序
            val imgs = withContext(Dispatchers.IO) { MediaStoreSource.queryImages(context) }
            val vids = withContext(Dispatchers.IO) { MediaStoreSource.queryVideos(context) }
            images = (imgs + vids).sortedByDescending { it.dateModifiedMs }
            loading = false
        }
    }

    androidx.activity.compose.BackHandler(enabled = true) { onClose() }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize()) {
            // 顶部栏
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("从相册选择", style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = when {
                            !GalleryAccess.hasAnyAccess(context) -> "尚未授权"
                            partialAccess -> "仅能访问部分照片"
                            else -> "已选 ${selected.size} 张"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = onClose) { Text("关闭") }
            }

            // 部分授权提示 + 申请全部
            if (partialAccess && !fullAccess) {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "系统只允许访问你选中的部分照片，可选择「申请访问全部」以浏览整个相册。",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = {
                            requestAllLauncher.launch(Manifest.permission.READ_MEDIA_IMAGES)
                        }) { Text("申请访问全部") }
                    }
                }
            }

            when {
                !GalleryAccess.hasAnyAccess(context) -> {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text("需要读取相册的权限才能浏览并导入照片", style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(16.dp))
                        Button(onClick = {
                            permissionLauncher.launch(GalleryAccess.requiredPermissions())
                        }) { Text("授权读取相册") }
                    }
                }

                loading -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("正在读取相册…", style = MaterialTheme.typography.bodyMedium)
                    }
                }

                images.isEmpty() -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("相册里没有可导入的图片", style = MaterialTheme.typography.bodyMedium)
                    }
                }

                else -> {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        listOf("全部", "照片", "视频").forEachIndexed { idx, label ->
                            val on = category == idx
                            androidx.compose.material3.Surface(
                                shape = RoundedCornerShape(18.dp),
                                color = if (on) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                                modifier = Modifier
                                    .height(34.dp)
                                    .clickable { category = idx },
                            ) {
                                Box(
                                    modifier = Modifier.padding(horizontal = 16.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        text = label,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (on) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurface,
                                    )
                                }
                            }
                        }
                    }
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(4),
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(8.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        items(shown, key = { it.id }) { image ->
                            val isSelected = image.id in selected
                            Box(
                                modifier = Modifier
                                    .aspectRatio(1f)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                                    .clickable {
                                        selected = if (isSelected) selected - image.id else selected + image.id
                                    },
                            ) {
                                if (com.yingce.gallery.data.MediaTypes.isVideoByName(image.displayName)) {
                                    VideoThumbnail(model = image.uri, modifier = Modifier.fillMaxSize())
                                } else {
                                    AsyncImage(
                                        model = image.uri,
                                        contentDescription = image.displayName,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                }
                                if (isSelected) {
                                    Box(
                                        Modifier.fillMaxSize()
                                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.3f))
                                    )
                                    Box(
                                        modifier = Modifier
                                            .align(Alignment.TopEnd)
                                            .padding(3.dp)
                                            .size(18.dp)
                                            .clip(RoundedCornerShape(9.dp))
                                            .background(MaterialTheme.colorScheme.primary),
                                        contentAlignment = Alignment.Center,
                                    ) { Text("✓", color = Color.White, style = MaterialTheme.typography.labelSmall) }
                                }
                                Text(
                                    text = image.displayName,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color.White,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier
                                        .align(Alignment.BottomStart)
                                        .fillMaxWidth()
                                        .background(Color.Black.copy(alpha = 0.35f))
                                        .padding(horizontal = 3.dp, vertical = 1.dp),
                                )
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "共 ${images.size} 张",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        Button(
                            enabled = selected.isNotEmpty(),
                            onClick = {
                                val items = images.filter { it.id in selected }.map { image ->
                                    ImportItem(
                                        uri = image.uri,
                                        displayName = image.displayName,
                                        sizeBytes = image.sizeBytes,
                                        mimeType = image.mimeType,
                                        // 拍摄时间优先用 MediaStore 的 DATE_TAKEN（秒 -> 毫秒）
                                        fileTime = image.dateTakenMs.takeIf { it > 0 } ?: image.dateModifiedMs,
                                        sourceKind = SourceKind.MEDIASTORE,
                                    )
                                }
                                onConfirm(items)
                            },
                        ) { Text(if (selected.isEmpty()) "导入" else "导入 ${selected.size} 张") }
                    }
                }
            }
        }
    }
}
