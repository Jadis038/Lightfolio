package com.yingce.gallery.ui

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import java.io.File

/**
 * 视频的附加信息（时长/编码/码率/帧率/旋转/音轨等）。
 * 全部来自 MediaMetadataRetriever，读不到的就返回空，不编造。
 */
object VideoInfo {

    fun load(context: Context, model: Any): List<Pair<String, String>> {
        val retriever = MediaMetadataRetriever()
        return try {
            when (model) {
                is Uri -> retriever.setDataSource(context, model)
                is File -> retriever.setDataSource(model.absolutePath)
                is String -> retriever.setDataSource(model)
                else -> return emptyList()
            }
            val out = ArrayList<Pair<String, String>>()
            fun add(label: String, key: Int, format: (String) -> String = { it }) {
                val v = retriever.extractMetadata(key)
                if (!v.isNullOrBlank()) out += label to format(v)
            }

            add("时长", MediaMetadataRetriever.METADATA_KEY_DURATION) { ms ->
                val total = (ms.toLongOrNull() ?: 0L) / 1000
                String.format(java.util.Locale.US, "%d:%02d:%02d", total / 3600, (total % 3600) / 60, total % 60)
            }
            add("容器", MediaMetadataRetriever.METADATA_KEY_MIMETYPE)
            add("分辨率", MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH) { w ->
                val h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT) ?: "?"
                "$w×$h"
            }
            add("码率", MediaMetadataRetriever.METADATA_KEY_BITRATE) { b ->
                val kbps = (b.toLongOrNull() ?: 0L) / 1000
                if (kbps >= 1000) String.format(java.util.Locale.US, "%.1f Mbps", kbps / 1000.0) else "$kbps kbps"
            }
            add("帧率", MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE) { f ->
                val d = f.toDoubleOrNull() ?: 0.0
                if (d > 0) String.format(java.util.Locale.US, "%.2f fps", d) else "未记录"
            }
            add("旋转", MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION) { "$it°" }
            add("色彩标准", MediaMetadataRetriever.METADATA_KEY_COLOR_STANDARD) { "${it}（标准编号）" }
            add("色彩传递", MediaMetadataRetriever.METADATA_KEY_COLOR_TRANSFER) { "${it}（传递函数编号）" }
            add("色彩范围", MediaMetadataRetriever.METADATA_KEY_COLOR_RANGE) { "${it}（范围编号）" }
            add("拍摄时间", MediaMetadataRetriever.METADATA_KEY_DATE)
            add("音轨", MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) { if (it == "yes") "有" else "无" }
            add("帧数", MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)

            // 位置（视频也可能带 GPS）
            val lat = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_LOCATION)
            if (!lat.isNullOrBlank()) out += "位置元数据" to lat
            out
        } catch (_: Throwable) {
            emptyList()
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun codecName(code: String): String = when (code.uppercase()) {
        "AVC", "H264" -> "H.264 / AVC"
        "HEVC", "H265" -> "H.265 / HEVC"
        "AV1" -> "AV1"
        "VP9" -> "VP9"
        "VP8" -> "VP8"
        "MPEG4" -> "MPEG-4"
        else -> code
    }
}
