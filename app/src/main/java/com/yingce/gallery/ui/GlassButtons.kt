package com.yingce.gallery.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** 圆形液态玻璃按钮（右上角用）。 */
@Composable
fun GlassIconButton(label: String, useBackdrop: Boolean = true, onClick: () -> Unit) {
    YingceGlass.GlassBox(
        modifier = Modifier
            .size(62.dp)
            .clickable(onClick = onClick),
        corner = 31.dp,
        useBackdrop = useBackdrop,
        refraction = useBackdrop,
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
        }
    }
}

/**
 * 底部胶囊选择器。
 *
 * 按用户要求：**保留胶囊形状与背景色，只去掉毛玻璃模糊**。
 * 背景是纯色填充（不采样、不模糊、不做高光），因此滚动时没有任何额外渲染成本。
 */
@Composable
fun TabPill(text: String, selected: Boolean, onClick: () -> Unit) {
    val dark = androidx.compose.foundation.isSystemInDarkTheme()
    // 选中：加深的绿色实底 + 浅色文字；未选中：中性实底 + 常规文字
    val bg = if (selected) {
        if (dark) androidx.compose.ui.graphics.Color(0xFF1FA463) else androidx.compose.ui.graphics.Color(0xFF0E7A48)
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val fg = if (selected) {
        androidx.compose.ui.graphics.Color.White
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    Box(
        modifier = Modifier
            .width(132.dp)
            .height(50.dp)
            .clip(CircleShape)
            .background(bg)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = fg,
        )
    }
}

/**
 * 相册页：列出用户新建的相册 + 一个"全部作品"入口。
 *
 * 说明：本轮只做界面与容器，相册内容（把作品划入相册）尚未实现，数量显示为 0 并标注。
 */
@Composable
fun AlbumsScreen(
    photos: android.graphics.Bitmap? = null,
    albumNames: List<String>,
    totalCount: Int,
    onNewAlbum: () -> Unit,
    onOpenAll: () -> Unit,
    onOpenAlbum: (String) -> Unit = {},
    topInset: androidx.compose.ui.unit.Dp = 0.dp,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = topInset + 8.dp,
            bottom = 8.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Spacer(Modifier.height(8.dp))
            YingceGlass.GlassBox(
                modifier = Modifier.fillMaxWidth().height(88.dp).clickable(onClick = onOpenAll),
            ) {
                Row(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("全部作品", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "$totalCount 张",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text("›", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        if (albumNames.isEmpty()) {
            item {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(top = 40.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("还没有相册", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "点右上角「＋」新建一个",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        } else {
            items(albumNames) { name ->
                YingceGlass.GlassBox(
                    modifier = Modifier.fillMaxWidth().height(76.dp).clickable { onOpenAlbum(name) },
                ) {
                    Row(
                        modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(name, style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.weight(1f))
                        Text(
                            "0 张",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        item { Spacer(Modifier.height(120.dp)) }
    }
}

/** 相册名持久化（SharedPreferences，简单可靠，不引入新表）。 */
object AlbumStore {
    private const val PREF = "yingce_albums"
    private const val KEY = "names"

    fun load(context: Context): List<String> =
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getString(KEY, "")
            ?.split('\n')
            ?.filter { it.isNotBlank() }
            ?: emptyList()

    fun add(context: Context, name: String): List<String> {
        val current = load(context).toMutableList()
        if (name.isNotBlank() && name !in current) current.add(name.trim())
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putString(KEY, current.joinToString("\n"))
            .apply()
        return current
    }
}

/** 新建相册的输入弹窗。 */
@Composable
fun NewAlbumDialog(onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { androidx.compose.runtime.mutableStateOf("") }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新建相册") },
        text = {
            androidx.compose.material3.OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("相册名称") },
                singleLine = true,
            )
        },
        confirmButton = {
            androidx.compose.material3.TextButton(
                onClick = { if (name.isNotBlank()) onConfirm(name) },
                enabled = name.isNotBlank(),
            ) { Text("创建") }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
/** 文字胶囊按钮（如"新建相册"），透明玻璃底。 */
@Composable
fun TextPillButton(label: String, useBackdrop: Boolean = true, onClick: () -> Unit) {
    YingceGlass.GlassBox(
        modifier = Modifier
            .height(44.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        corner = 22.dp,
        tint = androidx.compose.ui.graphics.Color(0x8CFFFFFF),
        useBackdrop = useBackdrop,
        refraction = useBackdrop,
    ) {
        Box(
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
        }
    }
}