package com.yingce.gallery

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yingce.gallery.exif.ExifReader
import com.yingce.gallery.geo.AdminGeoIndex
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * 诊断：为什么详情页显示"无位置记录"。
 * 分别验证 EXIF 解析、离线行政区解析、以及资产拷贝是否正常。
 */
@RunWith(AndroidJUnit4::class)
class LocationDiagnosticTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun diagnose() {
        val sb = StringBuilder()
        sb.appendLine("=== 1) assets/admin_cn.bin 是否存在 ===")
        val listed = runCatching { context.assets.list("")?.toList().orEmpty() }.getOrDefault(emptyList())
        sb.appendLine("assets 列表: $listed")
        val assetLen = runCatching { context.assets.openFd("admin_cn.bin").use { it.length } }
            .getOrElse { runCatching { context.assets.open("admin_cn.bin").use { it.available().toLong() } }.getOrElse { -1L } }
        sb.appendLine("asset length = $assetLen")
        val copy = File(context.filesDir, "admin_cn.bin")
        sb.appendLine("filesDir 副本: exists=${copy.exists()} len=${if (copy.exists()) copy.length() else -1}")

        sb.appendLine()
        sb.appendLine("=== 2) 离线行政区解析 ===")
        val idxResult = runCatching { AdminGeoIndex.get(context) }
        sb.appendLine("AdminGeoIndex.get -> ${idxResult.exceptionOrNull()?.let { "异常: ${it.javaClass.simpleName}: ${it.message}" } ?: "OK"}")
        idxResult.getOrNull()?.let { idx ->
            sb.appendLine("isReady=${idx.isReady}")
            for ((name, lat, lon) in listOf(
                Triple("北京", 39.9087, 116.3975),
                Triple("上海", 31.2304, 121.4737),
                Triple("广州", 23.1291, 113.2644),
            )) {
                val p = runCatching { idx.resolve(lat, lon) }
                sb.appendLine("$name -> ${p.getOrNull()?.let { "${it.province}/${it.city}/${it.county}" } ?: "null (${p.exceptionOrNull()?.message})"}")
            }
        }

        sb.appendLine()
        sb.appendLine("=== 3) photos 目录与 EXIF ===")
        val dir = File(context.filesDir, "photos")
        val files = dir.listFiles()?.sortedBy { it.name }?.take(3).orEmpty()
        sb.appendLine("photos 文件数=${dir.listFiles()?.size ?: 0}，抽样=${files.map { it.name }}")
        for (f in files) {
            val e = runCatching { ExifReader.read(f) }
            e.onSuccess {
                sb.appendLine("${f.name}: camera=${it.cameraText} taken=${it.takenAtText} gpsLat=${it.gpsLat} gpsLon=${it.gpsLon} format=${it.formatLabel}")
            }.onFailure {
                sb.appendLine("${f.name}: 解析异常 ${it.javaClass.simpleName}: ${it.message}")
            }
        }

        android.util.Log.i("YingCeLoc", "\n$sb")
        println(sb)
    }
}
