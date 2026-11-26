package com.yingce.gallery.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.crossfade
import com.yingce.gallery.data.Photo

/** 相册内容页：空态给导入入口，底部左分享、右显示开关。 */
@Composable
fun AlbumDetailScreen(
    albumName: String,
    photos: List<Photo>,
    visibleInMain: Boolean,
    onBack: () -> Unit,
    onImportGallery: () -> Unit,
    onImportFile: () -> Unit,
    onImportFromAll: () -> Unit,
    onShare: () -> Unit,
    onToggleVisible: () -> Unit,
    onOpenPhoto: (Int) -> Unit = {},
    onFilter: () -> Unit = {},
    onDeletePhotos: (List<Photo>) -> Unit = {},
) {
    val context = LocalContext.current
    // 顶部导入与中间导入各自一个菜单，互不干扰
    var topImportMenu by remember { mutableStateOf(false) }
    var centerImportMenu by remember { mutableStateOf(false) }
    // 与总相册一致的多选：长按进入、点击切换、全选、删除
    var selectionMode by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    // 删除完成后（照片列表变化）自动退出选择模式，并丢弃已不存在的 id
    androidx.compose.runtime.LaunchedEffect(photos.map { it.id }) {
        if (selectedIds.isNotEmpty()) {
            val alive = selectedIds.filter { id -> photos.any { it.id == id } }.toSet()
            if (alive.isEmpty()) {
                selectionMode = false
                selectedIds = emptySet()
            } else if (alive.size != selectedIds.size) {
                selectedIds = alive
            }
        }
    }
    androidx.activity.compose.BackHandler(enabled = true) {
        if (selectionMode) {
            selectionMode = false
            selectedIds = emptySet()
        } else {
            onBack()
        }
    }

    // 必须有不透明底，否则会与下层相册列表叠在一起
    androidx.compose.material3.Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            if (selectionMode) {
                // 选择模式：与总相册同一个 SelectionBar 组件
                SelectionBar(
                    count = selectedIds.size,
                    onSelectAll = { selectedIds = photos.map { it.id }.toSet() },
                    onDelete = {
                        // 与总相册一致：先弹确认，确认后才真正删除；取消则保留选择
                        val targets = photos.filter { it.id in selectedIds }
                        if (targets.isNotEmpty()) onDeletePhotos(targets)
                    },
                    onCancel = {
                        selectionMode = false
                        selectedIds = emptySet()
                    },
                )
            } else {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButtonCompact("←") { onBack() }
                Spacer(Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(albumName, style = MaterialTheme.typography.headlineMedium)
                    Text(
                        if (photos.isEmpty()) "空相册" else "${photos.size} 张",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // 相册内保留筛选与导入（导入含三种来源）
                GlassIconButton("筛选") { onFilter() }
                Spacer(Modifier.width(8.dp))
                Box {
                    GlassIconButton("导入") { topImportMenu = true }
                    DropdownMenu(expanded = topImportMenu, onDismissRequest = { topImportMenu = false }) {
                        DropdownMenuItem(text = { Text("从相册导入（MediaStore）") }, onClick = { topImportMenu = false; onImportGallery() })
                        DropdownMenuItem(text = { Text("从文件管理导入（SAF）") }, onClick = { topImportMenu = false; onImportFile() })
                        DropdownMenuItem(text = { Text("从总相册导入") }, onClick = { topImportMenu = false; onImportFromAll() })
                    }
                }
            }
            }

            if (photos.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("相册里还没有图片", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(14.dp))
                        Box {
                            YingceGlass.GlassBox(
                                modifier = Modifier.width(180.dp).height(52.dp).clickable { centerImportMenu = true },
                                corner = 26.dp,
                                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f),
                            ) {
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Text("导入", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                }
                            }
                            DropdownMenu(expanded = centerImportMenu, onDismissRequest = { centerImportMenu = false }) {
                                DropdownMenuItem(text = { Text("从相册导入（MediaStore）") }, onClick = { centerImportMenu = false; onImportGallery() })
                                DropdownMenuItem(text = { Text("从文件管理导入（SAF）") }, onClick = { centerImportMenu = false; onImportFile() })
                                DropdownMenuItem(text = { Text("从总相册导入") }, onClick = { centerImportMenu = false; onImportFromAll() })
                            }
                        }
                    }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    itemsIndexed(photos, key = { _, p -> p.id }) { index, p ->
                        val selected = p.id in selectedIds
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(112.dp)
                                .clip(androidx.compose.foundation.shape.RoundedCornerShape(10.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .combinedClickable(
                                    onClick = {
                                        if (selectionMode) {
                                            selectedIds = if (selected) selectedIds - p.id else selectedIds + p.id
                                        } else {
                                            onOpenPhoto(index)
                                        }
                                    },
                                    onLongClick = {
                                        selectionMode = true
                                        selectedIds = selectedIds + p.id
                                    },
                                ),
                        ) {
                            AsyncImage(
                                model = coil3.request.ImageRequest.Builder(context)
                                    .data(PhotoSource.model(context, p))
                                    .size(360)
                                    .crossfade(false)
                                    .build(),
                                contentDescription = p.displayName,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
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
                        }
                    }
                }
            }
        }

        // 左下角：圆形分享按钮（图标，无文字）—— 选择模式下隐藏，避免与选择栏冲突
        if (!selectionMode) {
            YingceGlass.GlassBox(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 20.dp, bottom = 28.dp)
                    .width(96.dp)
                    .height(48.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onShare),
                corner = 24.dp,
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("分享", style = MaterialTheme.typography.bodyMedium)
                }
            }
            // 右下角：圆形显示开关（在总相册中是否显示）
            CircleAction(
                text = if (visibleInMain) "◉" else "○",
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 20.dp, bottom = 28.dp),
                onClick = onToggleVisible,
            )
        }
    }
    }
}

@Composable
private fun CircleAction(text: String, modifier: Modifier, onClick: () -> Unit) {
    YingceGlass.GlassBox(
        modifier = modifier.size(56.dp).clip(CircleShape).clickable(onClick = onClick),
        corner = 28.dp,
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(text, style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun TextButtonCompact(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier.size(44.dp).clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = MaterialTheme.typography.titleMedium)
    }
}

/** 从总相册挑图加入相册。 */
@Composable
fun AlbumPickerScreen(
    photos: List<Photo>,
    albumName: String,
    onConfirm: (Set<Long>) -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    var selected by remember { mutableStateOf(setOf<Long>()) }
    androidx.activity.compose.BackHandler(enabled = true) { onCancel() }

    androidx.compose.material3.Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
    Column(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            TextButtonCompact("取消", onCancel)
            // 确认按钮居中，文案固定为"导入"
            YingceGlass.GlassBox(
                modifier = Modifier
                    .align(Alignment.Center)
                    .width(120.dp)
                    .height(44.dp)
                    .clickable { onConfirm(selected) },
                corner = 22.dp,
                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("导入", style = MaterialTheme.typography.bodyMedium)
                }
            }
            Text(
                text = if (selected.isEmpty()) "" else "已选 ${selected.size}",
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.align(Alignment.CenterEnd),
            )
        }
        Text(
            text = "加入「$albumName」",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        )
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(photos, key = { it.id }) { p ->
                val on = p.id in selected
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(112.dp)
                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable { selected = if (on) selected - p.id else selected + p.id },
                ) {
                    AsyncImage(
                        model = coil3.request.ImageRequest.Builder(context)
                            .data(PhotoSource.model(context, p)).size(360).crossfade(false).build(),
                        contentDescription = p.displayName,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                    if (on) {
                        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)))
                        Box(
                            modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(20.dp)
                                .clip(CircleShape).background(MaterialTheme.colorScheme.primary),
                            contentAlignment = Alignment.Center,
                        ) { Text("✓", style = MaterialTheme.typography.labelSmall, color = Color.White) }
                    }
                }
            }
        }
    }
    }
}

/** 分享：把相册信息与首张图片一起发出去（ACTION_SEND）。 */
fun shareAlbum(context: Context, albumName: String, count: Int) {
    val text = "影·册 · 相册「$albumName」（$count 张）"
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    runCatching { context.startActivity(Intent.createChooser(intent, "分享相册")) }
}

/** 相册成员与显示开关的持久化。 */
object AlbumMembers {
    private const val PREF = "yingce_album_members"

    fun members(context: Context, album: String): Set<Long> =
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getString("m_$album", "")
            ?.split(',')
            ?.mapNotNull { it.toLongOrNull() }
            ?.toSet()
            ?: emptySet()

    fun add(context: Context, album: String, ids: Set<Long>) {
        val merged = members(context, album) + ids
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putString("m_$album", merged.joinToString(","))
            .apply()
    }

    fun isVisible(context: Context, album: String): Boolean =
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getBoolean("v_$album", true)

    fun setVisible(context: Context, album: String, visible: Boolean) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putBoolean("v_$album", visible)
            .apply()
    }
}
