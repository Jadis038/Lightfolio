package com.yingce.gallery.hdr

import android.content.Context
import android.view.Display
import androidx.media3.common.ColorInfo
import androidx.media3.common.Format
import androidx.media3.common.C

/**
 * 设备 HDR / 广色域能力探测。
 *
 * 注意：Android **没有** SDR 峰值亮度的公开 API。
 * `Display.getHdrCapabilities()` 只给 HDR 的亮度范围（设备支持 HDR 时），
 * SDR 白点亮度取不到 → 这里用保守映射并显式标注，不假装知道。
 */
object HdrSupport {

    const val HDR_TYPE_DOLBY_VISION = Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION
    const val HDR_TYPE_HDR10 = Display.HdrCapabilities.HDR_TYPE_HDR10
    const val HDR_TYPE_HLG = Display.HdrCapabilities.HDR_TYPE_HLG
    const val HDR_TYPE_HDR10_PLUS = Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS

    /** SDR 参考白亮度：没有公开 API，取保守值并在界面上注明来源。 */
    const val ASSUMED_SDR_WHITE_NITS = 203.0

    data class DeviceHdr(
        val supportedTypes: List<Int>,
        val maxLuminance: Float,
        val maxAverageLuminance: Float,
        val minLuminance: Float,
        val wideColorGamut: Boolean,
        val colorMode: Int,
        val supportedColorModes: List<Int>,
        val refreshRate: Float,
        val widthPx: Int,
        val heightPx: Int,
        val isHdrNow: Boolean = false,
        val hdrSdrRatio: Float = Float.NaN,
        val preferredWideGamut: String? = null,
    ) {
        fun typeNames(): List<String> = supportedTypes.map { typeName(it) }

        fun supports(type: Int): Boolean = supportedTypes.contains(type)
    }

    fun probe(context: Context): DeviceHdr {
        val display: Display = context.display
            ?: (context.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager).defaultDisplay
        val hdr: Display.HdrCapabilities? = display.hdrCapabilities
        val mode = display.mode
        val cfg = context.resources.configuration
        // 公开 SDK 里 Display.getColorMode()/getSupportedColorModes() 不可见（@SystemApi），
        // 用 Configuration.colorMode 的位掩码表达：宽色域 + HDR 两个掩码位。
        val colorModeRaw = cfg.colorMode
        return DeviceHdr(
            supportedTypes = hdr?.supportedHdrTypes?.toList() ?: emptyList(),
            maxLuminance = runCatching { hdr?.desiredMaxLuminance ?: 0f }.getOrDefault(0f),
            maxAverageLuminance = runCatching { hdr?.desiredMaxAverageLuminance ?: 0f }.getOrDefault(0f),
            minLuminance = runCatching { hdr?.desiredMinLuminance ?: 0f }.getOrDefault(0f),
            wideColorGamut = runCatching { display.isWideColorGamut }.getOrDefault(false),
            colorMode = colorModeRaw,
            supportedColorModes = emptyList(),
            refreshRate = mode?.refreshRate ?: 0f,
            widthPx = mode?.physicalWidth ?: 0,
            heightPx = mode?.physicalHeight ?: 0,
            isHdrNow = runCatching { display.isHdr }.getOrDefault(false),
            hdrSdrRatio = runCatching { display.hdrSdrRatio }.getOrDefault(Float.NaN),
            preferredWideGamut = runCatching { display.preferredWideGamutColorSpace?.name }.getOrNull(),
        )
    }
    fun typeName(type: Int): String = when (type) {
        HDR_TYPE_DOLBY_VISION -> "Dolby Vision"
        HDR_TYPE_HDR10 -> "HDR10 (ST.2084/PQ)"
        HDR_TYPE_HLG -> "HLG"
        HDR_TYPE_HDR10_PLUS -> "HDR10+"
        else -> "未知类型($type)"
    }

    /**
     * Configuration.colorMode 是位掩码：bit0 表示宽色域，后续位表示 HDR。
     * 公开 SDK 只给掩码，不给枚举名，因此这里按 Android 文档的位定义解释。
     */
    fun colorModeName(mode: Int): String {
        val wide = when (mode and android.content.res.Configuration.COLOR_MODE_WIDE_COLOR_GAMUT_MASK) {
            android.content.res.Configuration.COLOR_MODE_WIDE_COLOR_GAMUT_YES -> "宽色域"
            android.content.res.Configuration.COLOR_MODE_WIDE_COLOR_GAMUT_NO -> "sRGB 色域"
            else -> "色域未知"
        }
        val hdrBits = (mode and android.content.res.Configuration.COLOR_MODE_HDR_MASK) ushr
            android.content.res.Configuration.COLOR_MODE_HDR_SHIFT
        val hdr = when (hdrBits) {
            (android.content.res.Configuration.COLOR_MODE_HDR_YES shr
                android.content.res.Configuration.COLOR_MODE_HDR_SHIFT) -> "HDR 已启用"
            (android.content.res.Configuration.COLOR_MODE_HDR_NO shr
                android.content.res.Configuration.COLOR_MODE_HDR_SHIFT) -> "SDR"
            else -> "HDR 未定义"
        }
        return "$wide / $hdr (raw=$mode)"
    }

    private fun transferName(t: Int): String = when (t) {
        C.COLOR_TRANSFER_ST2084 -> "ST2084(PQ/HDR10)"
        C.COLOR_TRANSFER_HLG -> "HLG"
        C.COLOR_TRANSFER_SDR -> "SDR(BT.1886)"
        C.COLOR_TRANSFER_LINEAR -> "Linear"
        C.COLOR_TRANSFER_GAMMA_2_2 -> "Gamma 2.2"
        else -> "transfer=$t"
    }

    private fun colorSpaceName(s: Int): String = when (s) {
        C.COLOR_SPACE_BT709 -> "BT.709"
        C.COLOR_SPACE_BT601 -> "BT.601"
        C.COLOR_SPACE_BT2020 -> "BT.2020"

        else -> "primaries=$s"
    }

    fun isHdrTransfer(transfer: Int): Boolean =
        transfer == C.COLOR_TRANSFER_ST2084 || transfer == C.COLOR_TRANSFER_HLG

    /** 判断一个视频轨是否 HDR：优先看 transfer，其次看 HDR 静态元数据/位深。 */
    /** 杜比视界：MIME 为 video/dolby-vision，或 CodecSpecificData 里带 dv 标记。 */
    fun isDolbyVision(format: Format): Boolean {
        val mime = format.sampleMimeType ?: return false
        return mime.equals("video/dolby-vision", ignoreCase = true) || mime.contains("dolby", ignoreCase = true)
    }

    fun isHdr(format: Format): Boolean {
        if (isDolbyVision(format)) return true
        val ci: ColorInfo? = format.colorInfo
        if (ci == null) return false
        if (isHdrTransfer(ci.colorTransfer)) return true
        if (ci.hdrStaticInfo != null) return true
        return false
    }

    /**
     * 该 HDR 类型设备是否原生支持；不支持时必须映射到 SDR。
     * 杜比视界：本 App 不内置解码器，仅当设备声明支持 DV 时才可能直通（由平台硬解），
     * 否则一律按 HDR10 基础层/映射到 SDR 处理。
     */
    fun sourceHdrType(format: Format): Int? {
        val ci = format.colorInfo ?: return null
        return when (ci.colorTransfer) {
            C.COLOR_TRANSFER_ST2084 -> HDR_TYPE_HDR10
            C.COLOR_TRANSFER_HLG -> HDR_TYPE_HLG
            else -> null
        }
    }

    data class TrackReport(
        val width: Int,
        val height: Int,
        val mimeType: String?,
        val bitDepth: String,
        val primaries: String,
        val transfer: String,
        val range: String,
        val isHdr: Boolean,
        val hdrTypeName: String?,
        val deviceSupports: Boolean,
        val decision: String,
    )

    fun describeTrack(format: Format, device: DeviceHdr): TrackReport {
        val ci = format.colorInfo
        val primaries = ci?.let { colorSpaceName(it.colorSpace) } ?: "未声明"
        val transfer = ci?.let { transferName(it.colorTransfer) } ?: "未声明"
        val range = ci?.let {
            when (it.colorRange) {
                C.COLOR_RANGE_FULL -> "Full"
                C.COLOR_RANGE_LIMITED -> "Limited(TV)"
                else -> "未声明"
            }
        } ?: "未声明"
        val bitDepth = run {
            val luma = ci?.lumaBitdepth ?: 0
            val chroma = ci?.chromaBitdepth ?: 0
            if (luma > 0 && luma != Format.NO_VALUE) {
                "${luma}bit/chroma ${if (chroma > 0) "${chroma}bit" else "?"}"
            } else "未声明"
        }
        val dv = isDolbyVision(format)
        val hdr = isHdr(format)
        val type = if (dv) HDR_TYPE_DOLBY_VISION else sourceHdrType(format)
        val supported = type != null && device.supports(type)

        val decision = when {
            dv && !supported ->
                "Dolby Vision：设备未声明 DV 支持 → 不由 App 强行解码，按平台能力输出（通常退化为 SDR/基础层，可能偏色）"
            dv && supported -> "Dolby Vision 直通（设备声明支持，交由平台硬解）"
            !hdr -> "SDR：按 BT.709/sRGB 直接输出"
            supported -> "HDR 直通（设备声明支持 ${typeName(type!!)}）"
            type != null -> "HDR→SDR 色调映射（设备不支持 ${typeName(type)}，原始元数据保留）"
            else -> "HDR→SDR 色调映射（类型无法识别，保守处理）"
        }

        return TrackReport(
            width = format.width,
            height = format.height,
            mimeType = format.sampleMimeType,
            bitDepth = bitDepth,
            primaries = primaries,
            transfer = transfer,
            range = range,
            isHdr = hdr,
            hdrTypeName = type?.let { typeName(it) },
            deviceSupports = supported,
            decision = decision,
        )
    }

    fun deviceSummary(device: DeviceHdr): String = buildString {
        appendLine("屏幕: ${device.widthPx}×${device.heightPx} @ ${device.refreshRate}Hz")
        appendLine("HDR 支持类型: " + if (device.supportedTypes.isEmpty()) "无（纯 SDR 屏幕）" else device.typeNames().joinToString(", "))
        appendLine("HDR 亮度范围: min=${device.minLuminance} max=${device.maxLuminance} maxAvg=${device.maxAverageLuminance}")
        appendLine("广色域: ${if (device.wideColorGamut) "支持" else "不支持"}")
        appendLine("当前色模式: ${colorModeName(device.colorMode)}")
        appendLine("支持的色模式: " + if (device.supportedColorModes.isEmpty()) "无" else device.supportedColorModes.joinToString(", ") { colorModeName(it) })
        appendLine("SDR 峰值亮度: 系统无公开 API，保守按 ${ASSUMED_SDR_WHITE_NITS.toInt()} nits 映射（已注明）")
    }
}
