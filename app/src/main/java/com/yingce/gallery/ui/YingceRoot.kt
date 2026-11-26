package com.yingce.gallery.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yingce.gallery.data.AppDatabase
import com.yingce.gallery.data.ImportItem
import com.yingce.gallery.data.ImportMode
import com.yingce.gallery.data.ImportProgress
import com.yingce.gallery.data.ImportResult
import com.yingce.gallery.data.Importer
import com.yingce.gallery.data.Photo
import com.yingce.gallery.data.SourceKind
import com.yingce.gallery.data.UriMeta
import com.yingce.gallery.exif.PhotoExif
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun YingceRoot() {
    val context = LocalContext.current
    val dao = remember { AppDatabase.get(context).photoDao() }
    val scope = rememberCoroutineScope()

// ---------- 筛选 ----------
    var filter by remember { mutableStateOf(GalleryFilter()) }
    var showFilter by remember { mutableStateOf(false) }
    // 0 = 总相册，1 = 相册
    var tab by remember { mutableIntStateOf(0) }
    var showAbout by remember { mutableStateOf(false) }
    // 更新检查结果（null = 无更新/未检查）
    var updateInfo by remember { mutableStateOf<com.yingce.gallery.data.UpdateChecker.UpdateInfo?>(null) }
    var showNewAlbum by remember { mutableStateOf(false) }
    var albumNames by remember { mutableStateOf(AlbumStore.load(context)) }
    var openAlbum by remember { mutableStateOf<String?>(null) }
    var albumPickFor by remember { mutableStateOf<String?>(null) }
    var albumViewerIndex by remember { mutableStateOf<Int?>(null) }
    // 相册成员/显示开关变化时自增，触发主网格重新计算
    var albumVersion by remember { mutableIntStateOf(0) }
    var cameraModels by remember { mutableStateOf(listOf<String>()) }

    LaunchedEffect(Unit) {
        dao.observeCameraModels().collect { cameraModels = it }
    }

    val range = remember(filter.time) { filter.range(System.currentTimeMillis()) }
    val photos by dao
        .observeFiltered(filter.cameraModel, filter.place, range.first, range.second)
        .collectAsStateWithLifecycle(initialValue = emptyList<Photo>())

    // ---------- 选择模式 ----------
    var selectionMode by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf<Set<Long>>(emptySet()) }

    // ---------- 导入 ----------
    var showPicker by remember { mutableStateOf(false) }
    var pendingItems by remember { mutableStateOf<List<ImportItem>?>(null) }
    var importMode by remember { mutableStateOf(ImportMode.COPY) }
    var importing by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<ImportProgress?>(null) }
    var cancelRequested by remember { mutableStateOf(false) }
    var statusLine by remember { mutableStateOf<String?>(null) }
    androidx.compose.runtime.LaunchedEffect(statusLine) {
        if (statusLine != null) {
            kotlinx.coroutines.delay(2500)
            statusLine = null
        }
    }
    var importJob by remember { mutableStateOf<Job?>(null) }

    // ---------- 删除 ----------
    var pendingDelete by remember { mutableStateOf<List<Photo>?>(null) }

    // ---------- 大图 ----------
    var openIndex by remember { mutableStateOf<Int?>(null) }

    fun summarize(result: ImportResult): String {
        if (result.spaceInsufficient) {
            return "存储空间不足：还差 ${PhotoExif.humanSize(result.spaceShortfallBytes)}，本次未导入任何文件"
        }
        val parts = mutableListOf<String>()
        if (result.imported > 0) parts += "导入 ${result.imported} 项"
        if (result.duplicates > 0) parts += "已导入 ${result.duplicates} 项（未生成副本）"
        if (result.filtered > 0) parts += "过滤非图片 ${result.filtered} 项"
        if (result.failed > 0) parts += "失败 ${result.failed} 项"
        if (result.cancelled) parts += "已取消"
        if (parts.isEmpty()) parts += "没有可导入的内容"
        return parts.joinToString("，")
    }

    fun startImport(items: List<ImportItem>, mode: ImportMode) {
        if (items.isEmpty()) return
        // 如果是从某个相册里发起的导入，导入完成后把新照片id并入该相册
        val albumTarget = openAlbum
        val idBeforeImport = photos.maxOfOrNull { it.id } ?: 0L
        importing = true
        cancelRequested = false
        progress = ImportProgress(0, items.size, 0, items.sumOf { it.sizeBytes }, "")
        importJob = scope.launch {
            val result = Importer(context, dao).import(
                items = items,
                mode = mode,
                onProgress = { progress = it },
                isCancelled = { cancelRequested },
            )
            importing = false
            importJob = null
            progress = null
            statusLine = summarize(result)
            if (albumTarget != null && result.imported > 0) {
                runCatching {
                    val all = dao.observeFiltered(null, -1, 0, Long.MAX_VALUE).first()
                    val newIds = all.filter { it.id > idBeforeImport }.map { it.id }.toSet()
                    if (newIds.isNotEmpty()) {
                        AlbumMembers.add(context, albumTarget, newIds)
                        albumVersion++
                    }
                }
            }
        }
    }

    fun deletePhotos(items: List<Photo>) {
        scope.launch {
            withContext(Dispatchers.IO) {
                items.forEach { photo ->
        // 只删 App 私有目录里的副本；仅引用模式本来就没有副本
                    PhotoSource.fileOf(context, photo)?.delete()
                }
                dao.deleteByIds(items.map { it.id })
            }
            selectedIds = emptySet()
            selectionMode = false
        statusLine = "已删除 ${items.size} 项（原始文件未改动）"
        }
    }

    // SAF：可持久化的多选（重启后仍能访问）
    val safLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNullOrEmpty()) return@rememberLauncherForActivityResult
        val items = uris.mapNotNull { uri ->
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            UriMeta.toItem(context, uri, SourceKind.SAF)
        }
        if (items.isEmpty()) {
            statusLine = "选中的文件里没有可导入的图片"
        } else {
            pendingItems = items
        }
    }

    // 总相册过滤：被「不显示在总相册」的相册独占的照片，不进入主网格；
    // 如果同一张照片也属于某个可见相册，则仍然显示（可见优先）。
    val mainPhotos = remember(photos, albumNames, albumVersion) {
        val hidden = HashSet<Long>()
        val visible = HashSet<Long>()
        albumNames.forEach { name ->
            val ids = AlbumMembers.members(context, name)
            if (AlbumMembers.isVisible(context, name)) visible.addAll(ids) else hidden.addAll(ids)
        }
        hidden.removeAll(visible)
        if (hidden.isEmpty()) photos else photos.filter { it.id !in hidden }
    }

    // 滚动时自动关掉玻璃的 RenderEffect 模糊，停下再恢复：
    // 这样既保留"液态玻璃"观感，又不让模糊拖累滚动帧率（本轮硬要求）。
    val gridScrollState = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
    val isScrolling by androidx.compose.runtime.remember {
        androidx.compose.runtime.derivedStateOf { gridScrollState.isScrollInProgress }
    }
    // 玻璃背景采样已整体移除：
    // 1) 顶栏已改为纯色面板，2) 底栏按用户要求改为"全程透明"，
    // 没有任何控件再用背景模糊 —— 采样循环（含每帧离屏录制）也一并删除，
    // 这正是上一版滚动掉帧的来源。
    YingceGlass.backdropEnabled = false
    // 启动时检查更新：开关打开才联网
    AutoUpdateCheck(enabled = com.yingce.gallery.data.UpdatePrefs.isAutoCheckEnabled(context)) { info ->
        updateInfo = info
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(modifier = Modifier.fillMaxSize().systemBarsPadding()) {
            Column(modifier = Modifier.fillMaxSize()) {
                if (selectionMode) {
                    SelectionBar(
                        count = selectedIds.size,
                        onSelectAll = { selectedIds = photos.map { it.id }.toSet() },
                        onDelete = {
                            pendingDelete = photos.filter { it.id in selectedIds }
                        },
                        onCancel = {
                            selectionMode = false
                            selectedIds = emptySet()
                        },
                    )
                } else {
                    Header(
                        count = mainPhotos.size,
                        filterActive = filter.isActive,
                        importing = importing,
                        tab = tab,
                        albumCount = albumNames.size,
                        onFilter = { showFilter = true },
                        onPickGallery = { showPicker = true },
                        onPickFile = { safLauncher.launch(arrayOf("*/*")) },
                        onNewAlbum = { showNewAlbum = true },
                        onAbout = { showAbout = true },
                    )
                }

                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    when {
                        tab == 1 -> AlbumsScreen(
                            albumNames = albumNames,
                            totalCount = photos.size,
                            onNewAlbum = { showNewAlbum = true },
                            onOpenAll = { tab = 0 },
                            onOpenAlbum = { openAlbum = it },
                        )
                        mainPhotos.isEmpty() && filter.isActive -> NoMatchState(
                            onClearFilter = { filter = GalleryFilter() },
                        )
                        mainPhotos.isEmpty() -> EmptyState(
                            onPickGallery = { showPicker = true },
                            enabled = !importing,
                        )
                        else -> GalleryGrid(
                            gridState = gridScrollState,
                            photos = mainPhotos,
                            selectionMode = selectionMode,
                            selectedIds = selectedIds,
                            onOpen = { index -> if (!selectionMode) openIndex = index },
                            onToggleSelect = { id ->
                                selectedIds = if (id in selectedIds) selectedIds - id else selectedIds + id
                            },
                            onLongPress = { id ->
                                selectionMode = true
                                selectedIds = selectedIds + id
                            },
                        )
                    }
                }

                // 底部留白只留一点：让照片能滚到玻璃条下面，透出模糊效果\n                Spacer(modifier = Modifier.height(16.dp))

                statusLine?.let { line ->
                    Text(
                        text = line,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }

            if (!selectionMode && !importing) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .navigationBarsPadding()
                        .padding(bottom = 16.dp),
                    contentAlignment = androidx.compose.ui.Alignment.BottomCenter,
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        TabPill("总相册", selected = tab == 0) { tab = 0 }
                        TabPill("相册", selected = tab == 1) { tab = 1 }
                    }
                }
            }

            androidx.compose.animation.AnimatedVisibility(
                visible = importing,
                enter = androidx.compose.animation.scaleIn(
                    initialScale = 0.92f,
                    animationSpec = YingceMotion.expressive(),
                ) + androidx.compose.animation.fadeIn(animationSpec = YingceMotion.snappy()),
                exit = androidx.compose.animation.scaleOut(
                    targetScale = 0.96f,
                    animationSpec = YingceMotion.snappy(),
                ) + androidx.compose.animation.fadeOut(animationSpec = YingceMotion.snappy()),
            ) {
                ImportOverlay(
                    progress = progress,
                    onCancel = {
                    // 只置标志，让导入器在下一步安全点自行收尾。
                    // 直接 cancel 协程会在当前文件写入中途被取消，进度浮层也会卡住不消失。
                        cancelRequested = true
                    },
                )
            }


            val index = openIndex
            if (index != null && index in photos.indices) {
                ViewerScreen(
                    photos = mainPhotos,
                    startIndex = index,
                    onClose = { openIndex = null },
                    onDelete = { photo ->
                        openIndex = null
                        pendingDelete = listOf(photo)
                    },
                )
            }

            pendingItems?.let { items ->
                ImportModeDialog(
                    count = items.size,
                    mode = importMode,
                    onModeChange = { importMode = it },
                    onConfirm = {
                        val chosen = importMode
                        pendingItems = null
                        startImport(items, chosen)
                    },
                    onDismiss = { pendingItems = null },
                )
            }

            openAlbum?.let { albumName ->
                val memberIds = AlbumMembers.members(context, albumName)
                AlbumDetailScreen(
                    albumName = albumName,
                    photos = photos.filter { it.id in memberIds },
                    visibleInMain = AlbumMembers.isVisible(context, albumName),
                    onBack = { openAlbum = null },
                    onImportGallery = { showPicker = true },
                    onImportFile = { safLauncher.launch(arrayOf("*/*")) },
                    onImportFromAll = { albumPickFor = albumName },
                    onShare = { shareAlbum(context, albumName, memberIds.size) },
                    onToggleVisible = {
                        val now = AlbumMembers.isVisible(context, albumName)
                        AlbumMembers.setVisible(context, albumName, !now)
                        albumVersion++
                        statusLine = if (!now) "该相册将在总相册中显示" else "该相册已从总相册隐藏"
                    },
                    onOpenPhoto = { idx -> albumViewerIndex = idx },
                    onFilter = { showFilter = true },
                    onDeletePhotos = { items -> pendingDelete = items },
                )
            }

                    // 相册内大图：与总相册大图完全同一个 ViewerScreen
            val albumForViewer = openAlbum
            val viewerIndex = albumViewerIndex
            if (albumForViewer != null && viewerIndex != null) {
                val albumPhotos = photos.filter { it.id in AlbumMembers.members(context, albumForViewer) }
                if (albumPhotos.isNotEmpty() && viewerIndex in albumPhotos.indices) {
                    ViewerScreen(
                        photos = albumPhotos,
                        startIndex = viewerIndex,
                        onClose = { albumViewerIndex = null },
                        onDelete = { photo ->
                            albumViewerIndex = null
                            pendingDelete = listOf(photo)
                        },
                    )
                }
            }

            albumPickFor?.let { albumName ->
                AlbumPickerScreen(
                    photos = photos,
                    albumName = albumName,
                    onConfirm = { ids ->
                        AlbumMembers.add(context, albumName, ids)
                        albumVersion++
                        albumPickFor = null
                        statusLine = "已加入 ${ids.size} 张"
                    },
                    onCancel = { albumPickFor = null },
                )
            }

            if (showPicker) {
                MediaPickerScreen(
                    onClose = { showPicker = false },
                    onConfirm = { items ->
                        showPicker = false
                        pendingItems = items
                    },
                )
            }
            updateInfo?.let { info ->
                UpdateDialog(info = info, onDismiss = { updateInfo = null })
            }

            if (showAbout) {
                AboutScreen(
                    onBack = { showAbout = false },
                    onShowUpdate = { info ->
                        showAbout = false
                        updateInfo = info
                    },
                )
            }

            if (showNewAlbum) {
                NewAlbumDialog(
                    onConfirm = { name ->
                        albumNames = AlbumStore.add(context, name)
                        showNewAlbum = false
                        tab = 1
                    },
                    onDismiss = { showNewAlbum = false },
                )
            }

            if (showFilter) {
                FilterDialog(
                    filter = filter,
                    cameraModels = cameraModels,
                    onChange = { filter = it },
                    onDismiss = { showFilter = false },
                )
            }

            pendingDelete?.let { items ->
                DeleteConfirmDialog(
                    items = items,
                    onConfirm = {
                        pendingDelete = null
                        deletePhotos(items)
                    },
                    onDismiss = { pendingDelete = null },
                )
            }
        }
    }
}

@Composable
private fun Header(
    count: Int,
    filterActive: Boolean,
    importing: Boolean,
    tab: Int,
    albumCount: Int,
    onFilter: () -> Unit,
    onPickGallery: () -> Unit,
    onPickFile: () -> Unit,
    onNewAlbum: () -> Unit,
    onAbout: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    // 顶栏：普通纯色面板（不做玻璃效果）。
    // 背景滚动采样在顶栏上必然出现"滑动残留旧画面"，用户已要求彻底移除。
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(30.dp))
            .background(MaterialTheme.colorScheme.background.copy(alpha = 0.96f))
            .border(
                0.8.dp,
                if (isSystemInDarkTheme()) {
                    Color.White.copy(alpha = 0.10f)
                } else {
                    Color.White.copy(alpha = 0.55f)
                },
                RoundedCornerShape(30.dp),
            ),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    Column {
                        Text("影·册", style = MaterialTheme.typography.headlineMedium)
                        Text(
                            text = when {
                        tab == 0 && count == 0 -> "作品库为空"
                        tab == 0 -> "$count 张作品"
                        albumCount == 0 -> "还没有相册"
                        else -> "$albumCount 个相册"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                            .border(0.8.dp, Color.White.copy(alpha = 0.25f), CircleShape)
                            .clickable(onClick = onAbout),
                    ) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("i", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    GlassIconButton(if (filterActive) "筛选•" else "筛选", useBackdrop = false) { if (!importing) onFilter() }
                    Box {
                        GlassIconButton("导入", useBackdrop = false) { if (!importing) menuOpen = true }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                        text = { Text("从相册选择（MediaStore）") },
                                onClick = { menuOpen = false; onPickGallery() },
                            )
                            DropdownMenuItem(
                        text = { Text("从文件管理选择（SAF）") },
                                onClick = { menuOpen = false; onPickFile() },
                            )
                        }
                    }
                }
            }
            // 相册页：第二行放"新建相册"，避免把标题挤成竖排
            if (tab == 1) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 2.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextPillButton("新建相册", useBackdrop = false) { if (!importing) onNewAlbum() }
                }
            }
        }
    }
}

@Composable
internal fun SelectionBar(
    count: Int,
    onSelectAll: () -> Unit,
    onDelete: () -> Unit,
    onCancel: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
        text = "已选 $count 项",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onSelectAll) { Text("全选") }
        TextButton(onClick = onDelete, enabled = count > 0) {
            Text("删除", color = MaterialTheme.colorScheme.error)
        }
        TextButton(onClick = onCancel) { Text("取消") }
    }
}

@Composable
private fun EmptyState(onPickGallery: () -> Unit, enabled: Boolean) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("还没有作品", style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(
        text = "可以从相册或文件管理导入。默认复制到 App 私有目录，原文件不会被修改或删除。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(20.dp))
        Button(onClick = onPickGallery, enabled = enabled) { Text("从相册导入") }
    }
}

@Composable
private fun NoMatchState(onClearFilter: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("当前筛选没有匹配的作品", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(12.dp))
        TextButton(onClick = onClearFilter) { Text("清除筛选") }
    }
}

@Composable
private fun ImportOverlay(progress: ImportProgress?, onCancel: () -> Unit) {
    val p = progress
    val fraction = if (p != null && p.bytesTotal > 0) {
        (p.bytesDone.toFloat() / p.bytesTotal.toFloat()).coerceIn(0f, 1f)
    } else if (p != null && p.total > 0) {
        p.done.toFloat() / p.total.toFloat()
    } else 0f

    Box(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.32f)),
        contentAlignment = Alignment.Center,
    ) {
        Surface(shape = RoundedCornerShape(16.dp), tonalElevation = 3.dp) {
            Column(
                modifier = Modifier.width(280.dp).padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
        Text("正在导入…", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(8.dp))
                Text(
        text = p?.let { "${it.done}/${it.total}" } ?: "准备中",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (p?.currentName?.isNotBlank() == true) {
                    Text(
                        text = p.currentName,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
                Spacer(Modifier.height(12.dp))
                Box(
                    modifier = Modifier.fillMaxWidth().height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(fraction)
                            .fillMaxHeight()
                            .background(MaterialTheme.colorScheme.primary),
                    )
                }
                Spacer(Modifier.height(12.dp))
                TextButton(onClick = onCancel) { Text("取消导入") }
            }
        }
    }
}

@Composable
private fun ImportModeDialog(
    count: Int,
    mode: ImportMode,
    onModeChange: (ImportMode) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
            title = { Text("导入 $count 个文件") },
        text = {
            Column {
                ModeOption(
            title = "复制到 App 目录",
                    subtitle = "导入后即使原文件被删除也能查看；占用额外空间",
                    selected = mode == ImportMode.COPY,
                ) { onModeChange(ImportMode.COPY) }
                Spacer(Modifier.height(8.dp))
                ModeOption(
                    title = "仅引用不复制",
                    subtitle = "不占额外空间；删除原文件后将无法查看",
                    selected = mode == ImportMode.REFERENCE,
                ) { onModeChange(ImportMode.REFERENCE) }
            }
        },
            confirmButton = { TextButton(onClick = onConfirm) { Text("开始导入") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun ModeOption(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.bodyMedium)
                if (selected) {
                    Spacer(Modifier.width(6.dp))
        Text("✓", color = MaterialTheme.colorScheme.primary)
                }
            }
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DeleteConfirmDialog(
    items: List<Photo>,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val referenced = items.count { it.isReference }
    AlertDialog(
        onDismissRequest = onDismiss,
            title = { Text(if (items.size == 1) "从影·册中删除该项？" else "从影·册中删除 ${items.size} 项？") },
        text = {
            Text(
                buildString {
            append("只会删除 App 内的副本与记录，不会删除手机里的原文件。")
                    if (referenced > 0) {
            append("\n\n其中 $referenced 项是「仅引用」模式，只会移除记录。")
                    }
                }
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("删除", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/**
 * 底部浮动玻璃条（ColorOS 相册风格）。
 *
 * 只用静态 chrome：玻璃效果不进列表，滚动时没有任何额外渲染成本。
 */
@Composable
private fun BottomGlassBar(
    filterActive: Boolean,
    onPickGallery: () -> Unit,
    onPickFile: () -> Unit,
    onFilter: () -> Unit,
) {
    var importMenuOpen by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp, vertical = 14.dp),
        contentAlignment = androidx.compose.ui.Alignment.BottomCenter,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            YingceGlass.GlassBox(
                modifier = Modifier.weight(1f).height(54.dp)
                    .clickable { importMenuOpen = true },
            ) {
                Text(
                    text = "导入",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.align(androidx.compose.ui.Alignment.Center),
                )
            }
            YingceGlass.GlassBox(
                modifier = Modifier.weight(1f).height(54.dp)
                    .clickable { onFilter() },
            ) {
                Text(
        text = if (filterActive) "筛选 •" else "筛选",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.align(androidx.compose.ui.Alignment.Center),
                )
            }
        }

        DropdownMenu(expanded = importMenuOpen, onDismissRequest = { importMenuOpen = false }) {
            DropdownMenuItem(
        text = { Text("从相册导入") },
                onClick = { importMenuOpen = false; onPickGallery() },
            )
            DropdownMenuItem(
        text = { Text("从文件导入（任意格式）") },
                onClick = { importMenuOpen = false; onPickFile() },
            )
        }
    }
}
