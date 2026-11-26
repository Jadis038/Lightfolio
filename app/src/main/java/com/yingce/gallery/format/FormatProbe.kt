package com.yingce.gallery.format

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.ImageFormat
import android.media.MediaCodecList
import android.os.Build
import com.yingce.gallery.raw.RawImage
import java.io.File
import java.io.RandomAccessFile

enum class DecodeState { NATIVE, DEGRADED, LIMITED }

/** 一个文件的探测结论。 */
data class FormatReport(
    val fileName: String,
    val sizeBytes: Long,
    val container: String,
    val codec: String,
    val bitDepth: String,
    val colorSpace: String,
    val hdr: String,
    val resolution: String,
    val state: DecodeState,
    val elapsedMs: Long,
    val reason: String,
) {
    fun toLine(): String = buildString {
        append(fileName).append('\t')
        append(container).append('\t')
        append(codec).append('\t')
        append(bitDepth).append('\t')
        append(colorSpace).append('\t')
        append(hdr).append('\t')
        append(resolution).append('\t')
        append(state.name).append('\t')
        append(elapsedMs).append("ms\t")
        append(reason)
    }
}

/**
 * 逐文件探测：容器识别 → 解码尝试 → 三态判定。
 *
 * 原则：
 * - 每个文件独立 try/catch，失败只影响这一行，绝不整页崩溃。
 * - 结论只有三种（NATIVE / DEGRADED / LIMITED），不允许"未知"。
 */
object FormatProbe {

    private val RAW_EXT = setOf(
        "dng", "cr2", "cr3", "nef", "arw", "raf", "orf", "rw2", "pef", "srw",
        "x3f", "3fr", "mos", "mef", "dcr", "mrw", "erf", "sr2", "srf", "raw", "nrw",
    )

    fun probe(context: Context, file: File): FormatReport {
        val started = System.nanoTime()
        val ext = file.extension.lowercase()
        val head = readHead(file, 32)
        val container = sniffContainer(head, ext)

        return try {
            when {
                ext in RAW_EXT || container.startsWith("RAW") -> probeRaw(file, ext, container, started)
                container == "JPEG" || container == "PNG" -> probePlatform(context, file, ext, container, started)
                container.startsWith("HEIF") || container == "AVIF" -> probePlatform(context, file, ext, container, started)
                container == "TIFF" -> probeTiff(file, ext, container, started)
                else -> probePlatform(context, file, ext, container, started)
            }
        } catch (t: Throwable) {
            FormatReport(
                fileName = file.name,
                sizeBytes = file.length(),
                container = container,
                codec = "-",
                bitDepth = "-",
                colorSpace = "-",
                hdr = "-",
                resolution = "-",
                state = DecodeState.LIMITED,
                elapsedMs = (System.nanoTime() - started) / 1_000_000,
                reason = "探测异常: ${t.javaClass.simpleName}: ${t.message ?: ""}",
            )
        }
    }

    // ---------- RAW ----------
    private fun probeRaw(file: File, ext: String, container: String, started: Long): FormatReport {
        val img = RawImage.open(file.absolutePath)
            ?: return FormatReport(
                fileName = file.name, sizeBytes = file.length(), container = container,
                codec = "LibRaw", bitDepth = "-", colorSpace = "-", hdr = "-",
                resolution = "-", state = DecodeState.LIMITED,
                elapsedMs = (System.nanoTime() - started) / 1_000_000,
                reason = "LibRaw 无法打开（不支持的容器或损坏）",
            )

        return img.use { r ->
            val res = "${r.width}×${r.height}（raw ${r.rawWidth}×${r.rawHeight}）"
            val bps = if (r.maximum > 1023) "≥12bit" else "≤10bit"

            // 试解中心 256×256 一块，验证真实可解码（不是只看能不能打开）
            val tw = minOf(256, r.width)
            val th = minOf(256, r.height)
            val ox = ((r.width - tw) / 2).coerceAtLeast(0)
            val oy = ((r.height - th) / 2).coerceAtLeast(0)
            val pixels = runCatching { r.decodeRegion(ox, oy, tw, th, 8) }.getOrNull()

            val elapsed = (System.nanoTime() - started) / 1_000_000
            if (pixels != null && pixels.isNotEmpty()) {
                FormatReport(
                    fileName = file.name, sizeBytes = file.length(), container = container,
                    codec = "LibRaw 0.22.3 / AHD", bitDepth = "$bps（$bps 输出）",
                    colorSpace = "sRGB（相机白平衡）", hdr = if (r.maximum > 1023) "高光可恢复" else "无",
                    resolution = res, state = DecodeState.NATIVE, elapsedMs = elapsed,
                    reason = "全解成功，${r.colors} 通道",
                )
            } else if (r.hasThumbnail) {
                FormatReport(
                    fileName = file.name, sizeBytes = file.length(), container = container,
                    codec = "LibRaw（仅内嵌预览）", bitDepth = bps,
                    colorSpace = "sRGB", hdr = "-", resolution = res,
                    state = DecodeState.DEGRADED, elapsedMs = elapsed,
                    reason = "全解失败（${r.thumbBytes}B 内嵌预览可用）",
                )
            } else {
                FormatReport(
                    fileName = file.name, sizeBytes = file.length(), container = container,
                    codec = "LibRaw（不可解）", bitDepth = bps, colorSpace = "-", hdr = "-",
                    resolution = res, state = DecodeState.LIMITED, elapsedMs = elapsed,
                    reason = "全解失败且无内嵌预览",
                )
            }
        }
    }

    // ---------- 平台解码（JPEG/PNG/HEIF/AVIF） ----------
    private fun probePlatform(
        context: Context,
        file: File,
        ext: String,
        container: String,
        started: Long,
    ): FormatReport {
        // 先只读文件头拿真实尺寸与色域（不分配像素内存）
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching { android.graphics.BitmapFactory.decodeFile(file.absolutePath, bounds) }
        val realW = bounds.outWidth
        val realH = bounds.outHeight
        val colorSpace = bounds.outColorSpace?.name ?: "-"
        val wideGamut = runCatching { bounds.outColorSpace?.isWideGamut == true }.getOrDefault(false)
        val size = if (realW > 0 && realH > 0) "${realW}×${realH}" else "-"

        // 真正解码一次，但限制到 1024 长边：只验证"这个格式能不能解"，不追求像素
        var bitmap: Bitmap? = null
        try {
            bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { d, info, _ ->
                val longest = maxOf(info.size.width, info.size.height)
                if (longest > 1024) {
                    val scale = 1024.0 / longest
                    d.setTargetSize((info.size.width * scale).toInt(), (info.size.height * scale).toInt())
                }
                d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } catch (t: Throwable) {
            bitmap = null
        }

        val elapsed = (System.nanoTime() - started) / 1_000_000
        val codecName = "$container/平台解码器"
        val hdr = when {
            wideGamut -> "宽色域(P3/2020)"
            realW * realH >= 8_000_000 -> "高像素"
            else -> "SDR"
        }

        return if (bitmap != null) {
            FormatReport(
                fileName = file.name, sizeBytes = file.length(), container = container,
                codec = codecName, bitDepth = "8bit(平台解码)",
                colorSpace = colorSpace, hdr = hdr, resolution = size,
                state = DecodeState.NATIVE, elapsedMs = elapsed, reason = "平台解码成功",
            )
        } else {
            FormatReport(
                fileName = file.name, sizeBytes = file.length(), container = container,
                codec = codecName, bitDepth = "-", colorSpace = colorSpace, hdr = hdr,
                resolution = size, state = DecodeState.LIMITED, elapsedMs = elapsed,
                reason = "本机没有该格式的解码器（HEVC/AV1 硬解缺失或格式不支持）",
            )
        }
    }

    // ---------- TIFF ----------
    private fun probeTiff(file: File, ext: String, container: String, started: Long): FormatReport {
        // 平台没有 TIFF 解码器；先看 TIFF 里是否带可用的内嵌 JPEG 预览
        val embedded = TiffProbe.findLargestEmbeddedJpeg(file)
        val elapsed = (System.nanoTime() - started) / 1_000_000
        return if (embedded != null) {
            FormatReport(
                fileName = file.name, sizeBytes = file.length(), container = container,
                codec = "TIFF（内嵌 JPEG 预览）", bitDepth = "-",
                colorSpace = "sRGB", hdr = "-",
                resolution = embedded.width?.let { "${it}×${embedded.height}" } ?: "-",
                state = DecodeState.DEGRADED, elapsedMs = elapsed,
                reason = "TIFF 解码器未内置，已提取 ${embedded.length}B 内嵌预览",
            )
        } else {
            FormatReport(
                fileName = file.name, sizeBytes = file.length(), container = container,
                codec = "TIFF", bitDepth = "-", colorSpace = "-", hdr = "-",
                resolution = "-", state = DecodeState.LIMITED, elapsedMs = elapsed,
                reason = "TIFF 解码器未内置，且无可提取的内嵌预览",
            )
        }
    }

    // ---------- 容器嗅探 ----------
    private fun readHead(file: File, n: Int): ByteArray {
        return runCatching {
            RandomAccessFile(file, "r").use { raf ->
                val b = ByteArray(n)
                val r = raf.read(b)
                if (r <= 0) ByteArray(0) else b.copyOf(r)
            }
        }.getOrDefault(ByteArray(0))
    }

    private fun sniffContainer(head: ByteArray, ext: String): String {
        if (head.size >= 3 && head[0] == 0xFF.toByte() && head[1] == 0xD8.toByte() && head[2] == 0xFF.toByte()) return "JPEG"
        if (head.size >= 8 && head[0] == 0x89.toByte() && head[1] == 'P'.code.toByte()) return "PNG"
        if (head.size >= 4) {
            val ascii4 = String(head, 0, 4, Charsets.US_ASCII)
            if (ascii4 == "II*\u0000" || ascii4 == "MM\u0000*") {
                // TIFF 家族：RAW 的 TIFF 与普通 TIFF 用扩展名区分
                return if (ext in RAW_EXT) "RAW(TIFF)" else "TIFF"
            }
            if (ascii4 == "IIRO" || ascii4 == "IIU\u0000") return "RAW(TIFF变体)"
            if (ascii4.startsWith("FUJI")) return "RAW(RAF)"
            if (ascii4 == "FOVb") return "RAW(X3F)"
        }
        if (head.size >= 12) {
            val brand = String(head, 8, 4, Charsets.US_ASCII)
            if (brand == "crx ") return "RAW(CR3)"
            if (brand.startsWith("heic") || brand.startsWith("heix") || brand.startsWith("mif1")) return "HEIF"
            if (brand.startsWith("avif") || brand.startsWith("avis")) return "AVIF"
        }
        if (head.size >= 12 && String(head, 0, 4, Charsets.US_ASCII) == "\u0000MRM") return "RAW(MRW)"
        return ext.uppercase().ifBlank { "UNKNOWN" }
    }

    fun hevcDecoderAvailable(): Boolean = runCatching {
        MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.any { info ->
            !info.isEncoder && info.supportedTypes.any { it.equals("video/hevc", true) }
        }
    }.getOrDefault(false)

    fun av1DecoderAvailable(): Boolean = runCatching {
        MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.any { info ->
            !info.isEncoder && info.supportedTypes.any { it.equals("video/av01", true) }
        }
    }.getOrDefault(false)
}
