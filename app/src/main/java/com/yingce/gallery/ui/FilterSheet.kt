package com.yingce.gallery.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import java.util.Calendar

/** 时间筛选：把"最近 N 天/今年/更早"换算成区间。 */
enum class TimeFilter(val label: String) {
    ALL("全部时间"),
    TODAY("今天"),
    WEEK("最近 7 天"),
    MONTH("最近 30 天"),
    YEAR("今年"),
    OLDER("一年以前"),
}

data class GalleryFilter(
    val cameraModel: String? = null,
    /** -1 全部 / 0 无位置信息 / 1 有位置信息 */
    val place: Int = -1,
    val time: TimeFilter = TimeFilter.ALL,
) {
    val isActive: Boolean get() = cameraModel != null || place != -1 || time != TimeFilter.ALL

    fun range(now: Long): Pair<Long, Long> {
        val dayMs = 24L * 60 * 60 * 1000
        return when (time) {
            TimeFilter.ALL -> 0L to Long.MAX_VALUE
            TimeFilter.TODAY -> {
                val cal = Calendar.getInstance().apply {
                    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                }
                cal.timeInMillis to now
            }
            TimeFilter.WEEK -> (now - 7 * dayMs) to now
            TimeFilter.MONTH -> (now - 30 * dayMs) to now
            TimeFilter.YEAR -> {
                val cal = Calendar.getInstance().apply {
                    set(Calendar.DAY_OF_YEAR, 1); set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                }
                cal.timeInMillis to now
            }
            TimeFilter.OLDER -> 0L to (now - 365 * dayMs)
        }
    }
}

@Composable
fun FilterDialog(
    filter: GalleryFilter,
    cameraModels: List<String>,
    onChange: (GalleryFilter) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface) {
            Column(
                modifier = Modifier
                    .heightIn(max = 560.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
            ) {
                Text("筛选", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(12.dp))

                SectionLabel("拍摄机型")
                OptionRow("全部机型", filter.cameraModel == null) {
                    onChange(filter.copy(cameraModel = null))
                }
                cameraModels.forEach { model ->
                    OptionRow(model, filter.cameraModel == model) {
                        onChange(filter.copy(cameraModel = model))
                    }
                }
                if (cameraModels.isEmpty()) {
                    Text(
                        "库里还没有读取到机型信息",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Spacer(Modifier.height(16.dp))
                SectionLabel("拍摄地点")
                OptionRow("全部", filter.place == -1) { onChange(filter.copy(place = -1)) }
                OptionRow("有位置信息", filter.place == 1) { onChange(filter.copy(place = 1)) }
                OptionRow("无位置信息", filter.place == 0) { onChange(filter.copy(place = 0)) }
                Text(
                    "地点的省市解析在 M8 接入离线行政区划，本轮的「地点」按是否有 GPS 信息筛选。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )

                Spacer(Modifier.height(16.dp))
                SectionLabel("拍摄时间")
                TimeFilter.entries.forEach { t ->
                    OptionRow(t.label, filter.time == t) { onChange(filter.copy(time = t)) }
                }

                Spacer(Modifier.height(12.dp))
                Row(modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = { onChange(GalleryFilter()) }) { Text("重置") }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text("完成") }
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(bottom = 4.dp),
    )
}

@Composable
private fun OptionRow(text: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .background(
                if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface
            )
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        if (selected) Text("✓", color = MaterialTheme.colorScheme.primary)
    }
    Box(Modifier.height(2.dp))
}
