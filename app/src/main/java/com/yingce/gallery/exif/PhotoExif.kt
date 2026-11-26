package com.yingce.gallery.exif

import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

/** 读不到的字段统一用这个字符串，禁止用占位假数据（如 0、--）。 */
const val NOT_RECORDED = "该文件未记录"

data class PhotoExif(
    val fileName: String,
    val formatLabel: String,
    val sizeBytes: Long,
    val make: String? = null,
    val model: String? = null,
    val lens: String? = null,
    val takenAt: String? = null,
    /** DateTimeOriginal 解析出的毫秒时间戳（用于排序），解析失败为 null */
    val takenAtEpochMs: Long? = null,
    val gpsLat: Double? = null,
    val gpsLon: Double? = null,
    val width: Int? = null,
    val height: Int? = null,
    val aperture: Double? = null,
    val exposureSeconds: Double? = null,
    val iso: Int? = null,
    val focalLengthMm: Double? = null,
    val focalLength35Mm: Int? = null,
    val whiteBalance: Int? = null,
    val exposureProgram: Int? = null,
    val exposureBiasEv: Double? = null,
    val meteringMode: Int? = null,
    /** 其余所有 EXIF 标签（名称, 值），用于详情页"其他信息" */
    val extraTags: List<Pair<String, String>> = emptyList(),
) {
    val megapixels: Double?
        get() = if (width != null && height != null && width > 0 && height > 0) {
            width.toDouble() * height / 1_000_000.0
        } else null

    // ---- 展示格式化：null 一律回落到 NOT_RECORDED ----

    val apertureText: String get() = aperture?.let { "f/" + trimNum(it) } ?: NOT_RECORDED

    val shutterText: String
        get() {
            val t = exposureSeconds ?: return NOT_RECORDED
            if (t <= 0.0) return NOT_RECORDED
            if (t >= 1.0) return trimNum(t) + " s"
            val denom = Math.round(1.0 / t)
            if (denom <= 1) return trimNum(t) + " s"
            // 只有"1/N 秒"能把原值还原回来（误差 <2%）时才用分数形式，
            // 否则 0.3s 会被显示成 1/3s（实际 0.333s，误差 11%），那就是失真。
            val back = 1.0 / denom
            val err = Math.abs(back - t) / t
            return if (err < 0.02) "1/$denom s" else trimNum(t) + " s"
        }

    val isoText: String get() = iso?.toString() ?: NOT_RECORDED

    val focalText: String get() = focalLengthMm?.let { trimNum(it) + " mm" } ?: NOT_RECORDED

    val focal35Text: String get() = focalLength35Mm?.let { "$it mm" } ?: NOT_RECORDED

    val whiteBalanceText: String
        get() = when (whiteBalance) {
            0 -> "自动"
            1 -> "手动"
            null -> NOT_RECORDED
            else -> NOT_RECORDED
        }

    val exposureProgramText: String
        get() = when (exposureProgram) {
            0 -> "未定义"
            1 -> "手动"
            2 -> "程序自动"
            3 -> "光圈优先"
            4 -> "快门优先"
            5 -> "创意程序（景深优先）"
            6 -> "运动程序（快门优先）"
            7 -> "人像模式"
            8 -> "风景模式"
            null -> NOT_RECORDED
            else -> NOT_RECORDED
        }

    val exposureBiasText: String
        get() = exposureBiasEv?.let { v ->
            val sign = if (v > 0) "+" else ""
            sign + trimTo2(v) + " EV"
        } ?: NOT_RECORDED

    val meteringModeText: String
        get() = when (meteringMode) {
            0 -> "未知"
            1 -> "平均测光"
            2 -> "中央重点平均"
            3 -> "点测光"
            4 -> "多点测光"
            5 -> "评价测光（分区）"
            6 -> "部分测光"
            255 -> "其他"
            null -> NOT_RECORDED
            else -> NOT_RECORDED
        }

    val resolutionText: String
        get() = if (width != null && height != null) "$width × $height" else NOT_RECORDED

    val megapixelText: String
        get() = megapixels?.let { String.format(Locale.US, "%.2f MP", it) } ?: NOT_RECORDED

    val cameraText: String
        get() {
            val m = make?.trim().orEmpty()
            val mo = model?.trim().orEmpty()
            // 机型名里通常已含品牌词（如 Model="NIKON D750"、Make="NIKON CORPORATION"），
            // 这种情况只显示 Model，否则才把品牌拼在前面。
            val brand = m.split(' ').firstOrNull()?.lowercase(Locale.US)
            val modelHasBrand = !brand.isNullOrBlank() && mo.lowercase(Locale.US).contains(brand)
            return when {
                mo.isEmpty() && m.isEmpty() -> NOT_RECORDED
                mo.isEmpty() -> m
                m.isEmpty() -> mo
                modelHasBrand -> mo
                else -> "$m $mo"
            }
        }

    val lensText: String get() = lens?.trim()?.ifBlank { null } ?: NOT_RECORDED
    val takenAtText: String get() = takenAt ?: NOT_RECORDED
    val sizeText: String get() = humanSize(sizeBytes)

    companion object {
        fun trimNum(v: Double): String {
            val r = Math.round(v * 10.0) / 10.0
            return if (r == Math.floor(r) && !r.isInfinite()) r.toLong().toString()
            else String.format(Locale.US, "%.1f", r)
        }

        /** 保留最多两位小数，去掉末尾多余的 0：0.3333 -> "0.33"，2.0 -> "2"。 */
        fun trimTo2(v: Double): String {
            val r = Math.round(v * 100.0) / 100.0
            if (r == Math.floor(r) && !r.isInfinite()) return r.toLong().toString()
            return String.format(Locale.US, "%.2f", r).trimEnd('0').trimEnd('.')
        }

        fun roundTo2(v: Double): Double = Math.round(v * 100.0) / 100.0

        fun humanSize(bytes: Long): String = when {
            bytes >= 1024L * 1024 * 1024 -> String.format(Locale.US, "%.2f GB", bytes / 1024.0 / 1024 / 1024)
            bytes >= 1024L * 1024 -> String.format(Locale.US, "%.2f MB", bytes / 1024.0 / 1024)
            bytes >= 1024L -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
            else -> "$bytes B"
        }
    }
}

object ExifReader {

    // IFD0
    private const val TAG_MAKE = 0x010F
    private const val TAG_MODEL = 0x0110
    private const val TAG_DATETIME = 0x0132
    private const val TAG_IMAGE_WIDTH = 0x0100
    private const val TAG_IMAGE_HEIGHT = 0x0101

    // ExifIFD
    private const val TAG_EXPOSURE_TIME = 0x829A
    private const val TAG_FNUMBER = 0x829D
    private const val TAG_APERTURE_VALUE = 0x9202
    private const val TAG_EXPOSURE_PROGRAM = 0x8822
    private const val TAG_ISO = 0x8827
    private const val TAG_METERING_MODE = 0x9207
    // 注意：白平衡是 0xA403；0x9208 是 LightSource（曾经写错导致白平衡永远读不到）
    private const val TAG_WHITE_BALANCE = 0xA403
    private const val TAG_EXPOSURE_BIAS = 0x9204
    private const val TAG_FOCAL_LENGTH = 0x920A
    private const val TAG_FOCAL_35 = 0xA405
    private const val TAG_LENS_MODEL = 0xA434
    private const val TAG_LENS_SPEC = 0xA432
    private const val TAG_DATETIME_ORIGINAL = 0x9003
    private const val TAG_DATETIME_DIGITIZED = 0x9004
    private const val TAG_EXIF_WIDTH = 0xA002
    private const val TAG_EXIF_HEIGHT = 0xA003

    private val dateIn = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US)
    private val dateOut = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    /** 已经在上方分区展示过的标签，不再重复列在"其他信息"里。 */
    private val SHOWN_TAGS = setOf(
        TAG_MAKE, TAG_MODEL, TAG_LENS_MODEL, TAG_DATETIME, TAG_DATETIME_ORIGINAL,
        TAG_DATETIME_DIGITIZED, TAG_FNUMBER, TAG_APERTURE_VALUE, TAG_EXPOSURE_TIME,
        TAG_ISO, TAG_FOCAL_LENGTH, TAG_FOCAL_35, TAG_WHITE_BALANCE, TAG_EXPOSURE_PROGRAM,
        TAG_EXPOSURE_BIAS, TAG_METERING_MODE, TAG_IMAGE_WIDTH, TAG_IMAGE_HEIGHT,
        TAG_EXIF_WIDTH, TAG_EXIF_HEIGHT, TAG_LENS_SPEC,
    )

    /** 常见标签名（查不到就用 Tag 0xXXXX 兜底，不编造）。 */
    private val TAG_NAMES = mapOf(
        0x010E to "ImageDescription", 0x010F to "Make", 0x0110 to "Model",
        0x0112 to "Orientation", 0x011A to "XResolution", 0x011B to "YResolution",
        0x0128 to "ResolutionUnit", 0x0131 to "Software", 0x0132 to "ModifyDate",
        0x013B to "Artist", 0x8298 to "Copyright", 0x829A to "ExposureTime",
        0x829D to "FNumber", 0x8822 to "ExposureProgram", 0x8827 to "ISO",
        0x9003 to "DateTimeOriginal", 0x9004 to "CreateDate", 0x9201 to "ShutterSpeedValue",
        0x9202 to "ApertureValue", 0x9204 to "ExposureCompensation", 0x9207 to "MeteringMode",
        0x9209 to "Flash", 0x920A to "FocalLength", 0x927C to "MakerNote",
        0x9286 to "UserComment", 0x9290 to "SubSecTime", 0x9291 to "SubSecTimeOriginal",
        0x9292 to "SubSecTimeDigitized", 0xA000 to "FlashpixVersion", 0xA001 to "ColorSpace",
        0xA002 to "ExifImageWidth", 0xA003 to "ExifImageHeight", 0xA402 to "ExposureMode",
        0xA403 to "WhiteBalance", 0xA405 to "FocalLengthIn35mmFormat",
        0xA406 to "SceneCaptureType", 0xA408 to "Contrast", 0xA409 to "Saturation",
        0xA40A to "Sharpness", 0xA420 to "ImageUniqueID", 0xA430 to "OwnerName",
        0xA431 to "SerialNumber", 0xA432 to "LensInfo", 0xA434 to "LensModel",
        0xA435 to "LensSerialNumber", 0xA460 to "CompositeImage",
        0x0001 to "GPSLatitudeRef", 0x0002 to "GPSLatitude", 0x0003 to "GPSLongitudeRef",
        0x0004 to "GPSLongitude", 0x0005 to "GPSAltitudeRef", 0x0006 to "GPSAltitude",
    )

    /** 把 IFD0 / ExifIFD / GPS / 扩展 IFD / SubIFD 里所有剩余标签收集成可读文本。 */
    private fun collectExtraTags(tiff: TiffReader): List<Pair<String, String>> {
        val out = LinkedHashMap<Int, String>()

        fun describe(e: IfdEntry): String? {
            val size = e.count * when (e.type) {
                1, 2, 6, 7 -> 1L
                3, 8 -> 2L
                4, 9, 11 -> 4L
                5, 10, 12 -> 8L
                else -> 1L
            }
            // 二进制大块只报大小，避免把界面撑爆
            if (e.tag == 0x927C || e.tag == 0x0201 || e.tag == 0x0202) {
                return "（二进制数据，$size 字节）"
            }
            val ascii = tiff.ascii(e)
            if (ascii != null) {
                val cleaned = ascii.trim().trim('\u0000').trim()
                if (cleaned.isNotEmpty() && cleaned.all { it.code in 32..0x2FFF || it == '\n' }) {
                    return cleaned.take(200)
                }
            }
            val d = tiff.firstDouble(e)
            if (d != null) {
                return if (Math.abs(d - Math.round(d)) < 1e-9) Math.round(d).toString() else String.format(Locale.US, "%.4f", d)
            }
            val l = tiff.firstLong(e)
            if (l != null) return l.toString()
            return null
        }

        fun addAll(map: Map<Int, IfdEntry>) {
            for ((tag, e) in map) {
                if (tag in SHOWN_TAGS) continue
                if (tag in out) continue
                val v = describe(e) ?: continue
                out[tag] = v
            }
        }

        addAll(tiff.ifd0)
        addAll(tiff.exif)
        addAll(tiff.gps)
        tiff.ifdChain.forEach { addAll(it) }
        tiff.subIfds.forEach { addAll(it) }

        return out.entries
            .sortedBy { it.key }
            .map { (tag, value) ->
                val name = TAG_NAMES[tag] ?: String.format(Locale.US, "Tag 0x%04X", tag)
                name to value
            }
    }

    fun read(file: File): PhotoExif {
        val base = PhotoExif(
            fileName = file.name,
            formatLabel = formatLabel(file),
            sizeBytes = file.length(),
        )
        // JPEG/PNG 的像素尺寸不在 EXIF 里，先按文件头取一份兜底
        val rasterDims = RasterSize.read(file)
        val located = ExifLocator.open(file)
            ?: return base.copy(width = rasterDims?.first, height = rasterDims?.second)
        return located.use { loc ->
            val tiff = TiffReader(loc.raf, loc.base)
            if (!tiff.parse()) return@use base

            // CR3（ISO BMFF）：CMT1 只是 IFD0，真正的 ExifIFD 在 CMT2 盒里（ExifTool -v3 已证实）。
            if (tiff.exif.isEmpty()) {
                val cmt2 = ExifLocator.findBoxPayload(loc.raf, "CMT2", loc.base + 8, loc.base + 512 * 1024)
                if (cmt2 != null) {
                    val exifBlock = TiffReader(loc.raf, cmt2)
                    if (exifBlock.parse()) {
                        // CMT2 的 IFD0 就是 ExifIFD 内容，直接合并（attachIfd 会按基址差重定位）
                        tiff.attachIfd(exifBlock)
                    }
                }
            }

            // 标签不一定都在 ExifIFD：有的机型把参数直接写在 IFD0，有的在 IFD 链或 SubIFD
            fun entry(tag: Int): IfdEntry? = tiff.exif[tag]
                ?: tiff.ifd0[tag]
                ?: tiff.ifdChain.firstNotNullOfOrNull { it[tag] }
                ?: tiff.subIfds.firstNotNullOfOrNull { it[tag] }

            fun longTag(tag: Int) = tiff.firstLong(entry(tag))
            fun doubleTag(tag: Int) = tiff.firstDouble(entry(tag))
            fun asciiTag(tag: Int) = tiff.ascii(entry(tag))

            // 尺寸选择：与 ExifTool 的 ImageWidth/ImageHeight 语义对齐——
            // 对 RAW 取"含 padding 的完整 RAW 尺寸"，因此取 IFD0 / SubIFD 中面积最大者，
            // ExifIFD 的 PixelXDimension 仅在其它来源都没有时兜底（它常是预览尺寸）。
            fun pairOf(w: Long?, h: Long?): Pair<Int, Int>? =
                if (w != null && h != null && w > 0 && h > 0 && w < 200_000 && h < 200_000) w.toInt() to h.toInt() else null

            val exifDims = pairOf(tiff.firstLong(tiff.exif[TAG_EXIF_WIDTH]), tiff.firstLong(tiff.exif[TAG_EXIF_HEIGHT]))
            val ifdDims = pairOf(tiff.firstLong(tiff.ifd0[TAG_IMAGE_WIDTH]), tiff.firstLong(tiff.ifd0[TAG_IMAGE_HEIGHT]))
            val subDims = tiff.subIfds
                .mapNotNull { m -> pairOf(tiff.firstLong(m[TAG_IMAGE_WIDTH]), tiff.firstLong(m[TAG_IMAGE_HEIGHT])) }
                .maxByOrNull { it.first.toLong() * it.second.toLong() }
            val bestDims = listOfNotNull(ifdDims, subDims).maxByOrNull { it.first.toLong() * it.second.toLong() }
                ?: exifDims

            val rawDate = asciiTag(TAG_DATETIME_ORIGINAL)
                ?: asciiTag(TAG_DATETIME_DIGITIZED)
                ?: asciiTag(TAG_DATETIME)

            base.copy(
                make = asciiTag(TAG_MAKE)?.let(::cleanText),
                model = asciiTag(TAG_MODEL)?.let(::cleanText),
                lens = (asciiTag(TAG_LENS_MODEL)?.let(::cleanText))
                    ?: lensFromSpec(tiff, entry(TAG_LENS_SPEC)),
                takenAt = formatDate(rawDate),
                takenAtEpochMs = parseEpoch(rawDate),
                gpsLat = gpsCoordinate(tiff, isLatitude = true),
                gpsLon = gpsCoordinate(tiff, isLatitude = false),
                width = (bestDims ?: rasterDims)?.first,
                height = (bestDims ?: rasterDims)?.second,
                // 合理性校验：损坏或异常的 EXIF 一律判"未记录"，绝不把垃圾值显示给用户
                aperture = (doubleTag(TAG_FNUMBER)
                    ?: apexToFNumber(doubleTag(TAG_APERTURE_VALUE)))?.takeIf { it in 0.7..64.0 },
                exposureSeconds = doubleTag(TAG_EXPOSURE_TIME)?.takeIf { it > 0.0 && it <= 3600.0 },
                iso = longTag(TAG_ISO)?.toInt()?.takeIf { it in 1..1_000_000 },
                focalLengthMm = doubleTag(TAG_FOCAL_LENGTH)?.takeIf { it in 1.0..3000.0 },
                focalLength35Mm = longTag(TAG_FOCAL_35)?.toInt()?.takeIf { it in 1..5000 },
                whiteBalance = longTag(TAG_WHITE_BALANCE)?.toInt()?.takeIf { it in 0..1 },
                exposureProgram = longTag(TAG_EXPOSURE_PROGRAM)?.toInt()?.takeIf { it in 0..8 },
                exposureBiasEv = doubleTag(TAG_EXPOSURE_BIAS)?.takeIf { it in -10.0..10.0 },
                meteringMode = longTag(TAG_METERING_MODE)?.toInt()
                    ?.takeIf { it in 0..6 || it == 255 },
                extraTags = collectExtraTags(tiff),
            )
        }
    }

    private fun apexToFNumber(apex: Double?): Double? =
        if (apex == null) null else Math.pow(2.0, apex / 2.0)

    /** ASCII 字段清理：去掉控制字符与不可打印字节，避免把二进制垃圾当机型名显示。 */
    private fun cleanText(raw: String?): String? {
        if (raw == null) return null
        val cleaned = raw.filter { it.code in 32..126 || it.code > 127 }.trim()
        if (cleaned.isEmpty()) return null
        if (cleaned.length > 64) return null
        // 至少一半是可读字符，否则视为损坏
        val printable = cleaned.count { it.code in 32..126 }
        return if (printable * 2 >= cleaned.length) cleaned else null
    }

    private fun lensFromSpec(tiff: TiffReader, e: IfdEntry?): String? {
        val r = tiff.rationals(e) ?: return null
        if (r.size < 4) return null
        val f1 = r[0].first / r[0].second
        val f2 = r[1].first / r[1].second
        val a1 = r[2].first / r[2].second
        val a2 = r[3].first / r[3].second
        // 合理性校验：镜头焦距 1–3000mm、光圈 0.7–64，超出即视为损坏数据
        if (f1 !in 1.0..3000.0 || f2 !in 1.0..3000.0) return null
        if (a1 !in 0.7..64.0 || a2 !in 0.7..64.0) return null
        val focal = if (Math.abs(f1 - f2) < 0.01) "${PhotoExif.trimNum(f1)}mm"
        else "${PhotoExif.trimNum(f1)}-${PhotoExif.trimNum(f2)}mm"
        val ap = if (Math.abs(a1 - a2) < 0.01) "f/${PhotoExif.trimNum(a1)}"
        else "f/${PhotoExif.trimNum(a1)}-${PhotoExif.trimNum(a2)}"
        return "$focal $ap"
    }

    private fun formatDate(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        return try {
            dateOut.format(dateIn.parse(raw.trim())!!)
        } catch (_: Exception) {
            raw.trim().ifBlank { null }
        }
    }

    /** "2021:05:15 13:02:02" -> 毫秒时间戳；解析失败返回 null（不要伪造 0）。 */
    private fun parseEpoch(raw: String?): Long? {
        if (raw.isNullOrBlank()) return null
        return runCatching { dateIn.parse(raw.trim())?.time }.getOrNull()
    }

    /**
     * GPS 坐标：ExifIFD 下的 GPS IFD，纬度 0x0001(半球)/0x0002(度分秒)，
     * 经度 0x0003/0x0004。度分秒是 3 个 RATIONAL。
     */
    private fun gpsCoordinate(tiff: TiffReader, isLatitude: Boolean): Double? {
        val refTag = if (isLatitude) 0x0001 else 0x0003
        val valueTag = if (isLatitude) 0x0002 else 0x0004
        val ref = tiff.ascii(tiff.gps[refTag])?.trim()?.uppercase() ?: return null
        val parts = tiff.rationals(tiff.gps[valueTag]) ?: return null
        if (parts.size < 3) return null
        val deg = parts[0].first / parts[0].second
        val min = parts[1].first / parts[1].second
        val sec = parts[2].first / parts[2].second
        val value = deg + min / 60.0 + sec / 3600.0
        if (value.isNaN() || value > 180.0) return null
        return if (ref == "S" || ref == "W") -value else value
    }

    private fun formatLabel(file: File): String {
        val ext = file.extension.uppercase(Locale.US)
        return when (ext) {
            "JPG", "JPEG" -> "JPEG"
            "PNG" -> "PNG"
            "HEIC" -> "HEIC"
            "HEIF" -> "HEIF"
            "AVIF" -> "AVIF"
            "TIF", "TIFF" -> "TIFF"
            "DNG" -> "DNG（Adobe Raw）"
            "CR2" -> "CR2（Canon Raw v2）"
            "CR3" -> "CR3（Canon Raw v3）"
            "NEF" -> "NEF（Nikon Raw）"
            "ARW" -> "ARW（Sony Raw）"
            "RAF" -> "RAF（Fujifilm Raw）"
            "ORF" -> "ORF（Olympus Raw）"
            "RW2" -> "RW2（Panasonic Raw）"
            "PEF" -> "PEF（Pentax Raw）"
            "SRW" -> "SRW（Samsung Raw）"
            "X3F" -> "X3F（Sigma Foveon）"
            "3FR" -> "3FR（Hasselblad Raw）"
            "MOS" -> "MOS（Leaf Raw）"
            "MEF" -> "MEF（Mamiya Raw）"
            "DCR" -> "DCR（Kodak Raw）"
            "MRW" -> "MRW（Minolta Raw）"
            "ERF" -> "ERF（Epson Raw）"
            else -> ext.ifBlank { "未知" }
        }
    }
}
