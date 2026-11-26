package com.yingce.gallery.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.yingce.gallery.data.Photo
import com.yingce.gallery.exif.NOT_RECORDED
import com.yingce.gallery.exif.PhotoExif
import com.yingce.gallery.geo.AdminGeoIndex
import com.yingce.gallery.geo.AdminPlace
import com.yingce.gallery.geo.GeoText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 详情面板。分区顺序：拍摄参数 > 机型 > 分辨率 > 位置。
 * 名称一律用 display_name（不含 (1)(2) 后缀）。
 */
@Composable
fun DetailContent(photo: Photo, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var exif by remember(photo.id) { mutableStateOf<PhotoExif?>(null) }
    var place by remember(photo.id) { mutableStateOf<AdminPlace?>(null) }
    var geoChecked by remember(photo.id) { mutableStateOf(false) }
    val isVideo = com.yingce.gallery.data.MediaTypes.isVideoByName(photo.displayName)
    var videoInfo by remember(photo.id) { mutableStateOf<List<Pair<String, String>>>(emptyList()) }

    LaunchedEffect(photo.id) {
        if (isVideo) {
            // 视频没有 EXIF，改读容器/音视频轨信息
            videoInfo = withContext(Dispatchers.IO) {
                VideoInfo.load(context, PhotoSource.model(context, photo))
            }
            exif = PhotoExif(fileName = photo.displayName, formatLabel = "视频", sizeBytes = photo.sizeBytes)
            geoChecked = true
            return@LaunchedEffect
        }
        val e = PhotoExifLoader.load(context, photo)
        exif = e
        val lat = e.gpsLat
        val lon = e.gpsLon
        if (lat != null && lon != null) {
            // 纯本地解析，不联网、不转圈
            place = withContext(Dispatchers.IO) {
                runCatching { AdminGeoIndex.get(context).resolve(lat, lon) }.getOrNull()
            }
        }
        geoChecked = true
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 8.dp),
    ) {
        Text(
            text = photo.displayName,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(bottom = 2.dp),
        )
        Text(
            text = "文件名（原始名，不含落盘后缀）",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 12.dp),
        )

        val e = exif
        if (e == null) {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.width(18.dp).height(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
                Text("读取 EXIF…", style = MaterialTheme.typography.bodySmall)
            }
            return@Column
        }

        if (isVideo) {
            SectionTitle("视频信息")
            if (videoInfo.isEmpty()) {
                InfoRow("读取结果", "系统没有返回该视频的元数据")
            } else {
                videoInfo.forEach { (k, v) -> InfoRow(k, v) }
            }
            SectionTitle("文件")
            InfoRow("文件名", photo.displayName)
            InfoRow("格式", "视频")
            InfoRow("文件大小", e.sizeText)
            Spacer(Modifier.height(24.dp))
            return@Column
        }

        SectionTitle("拍摄参数")
        InfoRow("光圈", e.apertureText)
        InfoRow("快门速度", e.shutterText)
        InfoRow("ISO", e.isoText)
        InfoRow("等效焦距", e.focal35Text)
        InfoRow("实际焦距", e.focalText)
        InfoRow("白平衡", e.whiteBalanceText)
        InfoRow("拍摄模式", e.exposureProgramText)
        InfoRow("曝光补偿", e.exposureBiasText)
        InfoRow("测光模式", e.meteringModeText)

        SectionTitle("机型")
        InfoRow("机型", e.cameraText)
        InfoRow("镜头型号", e.lensText)
        InfoRow("拍摄时间", e.takenAtText)

        SectionTitle("分辨率")
        InfoRow("尺寸", e.resolutionText)
        InfoRow("像素", e.megapixelText)
        InfoRow("文件格式", e.formatLabel)
        InfoRow("文件大小", e.sizeText)

        SectionTitle("位置")
        val lat = e.gpsLat
        val lon = e.gpsLon
        if (lat == null || lon == null) {
            InfoRow("经纬度", NOT_RECORDED)
            InfoRow("行政区", "无 GPS 信息，无法解析")
        } else {
            InfoRow("经纬度", GeoText.dms(lat, lon))
            InfoRow("十进制度", GeoText.decimal(lat, lon))
            if (!geoChecked) {
                InfoRow("行政区", "解析中…")
            } else {
                val p = place
                InfoRow("行政区", p?.displayPath()?.ifBlank { null } ?: "未匹配到行政区（可能位于境外或边界数据缺口）")
                InfoRow("数据来源", "内置离线行政区划库 · 数据更新 2026-11-25")
            }
        }

        if (e.extraTags.isNotEmpty()) {
            SectionTitle("其他信息")
            e.extraTags.forEach { (k, v) -> InfoRow(k, v) }
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 18.dp, bottom = 6.dp),
    )
}

@Composable
private fun InfoRow(label: String, value: String) {
    val missing = value == NOT_RECORDED
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.Start,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(96.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontStyle = if (missing) FontStyle.Italic else FontStyle.Normal,
            color = if (missing) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
    }
}
