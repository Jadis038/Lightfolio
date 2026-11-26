package com.yingce.gallery.data

import android.net.Uri

/** 一个待导入项（已从 MediaStore / SAF 解析出的元数据）。 */
data class ImportItem(
    val uri: Uri,
    val displayName: String,
    val sizeBytes: Long,
    val mimeType: String?,
    /** 文件时间（毫秒），仅作为拍摄时间缺失时的回退 */
    val fileTime: Long,
    val sourceKind: SourceKind,
)

data class ImportProgress(
    val done: Int,
    val total: Int,
    val bytesDone: Long,
    val bytesTotal: Long,
    val currentName: String,
)

data class ImportResult(
    val imported: Int = 0,
    /** 指纹或源 URI 命中，判定为"已导入" */
    val duplicates: Int = 0,
    /** 非图片（含无法判定的）被过滤 */
    val filtered: Int = 0,
    val failed: Int = 0,
    val cancelled: Boolean = false,
    /** 空间不足时给出缺口字节数（0 表示无问题）；此值非 0 时不会导入任何文件 */
    val spaceShortfallBytes: Long = 0,
    val errors: List<String> = emptyList(),
) {
    val spaceInsufficient: Boolean get() = spaceShortfallBytes > 0
}

/** 作品类型判定：RAW 也算作品。 */
object MediaTypes {

    private val IMAGE_EXTENSIONS = setOf(
        // 常规位图
        "jpg", "jpeg", "png", "webp", "bmp", "gif", "heic", "heif", "avif", "tif", "tiff",
        // RAW（按验收要求：RAW 也算作品）
        "dng", "cr2", "cr3", "nef", "arw", "raf", "orf", "rw2", "pef", "srw",
        "x3f", "3fr", "mos", "mef", "dcr", "mrw", "erf", "sr2", "srf", "raw", "k25", "kdc", "nrw",
    )

    fun extensionOf(name: String): String {
        val i = name.lastIndexOf('.')
        return if (i <= 0 || i == name.length - 1) "" else name.substring(i + 1).lowercase()
    }

    /** M6：视频也进作品库。容器覆盖 MP4 / MOV / MKV / AVI / WebM 等。 */
    private val VIDEO_EXTENSIONS = setOf(
        "mp4", "m4v", "mov", "mkv", "webm", "avi", "3gp", "ts", "mts", "m2ts", "wmv", "flv", "mpg", "mpeg",
    )

    /** 图片（含 RAW）算作品。 */
    fun isImageWork(name: String, mimeType: String?): Boolean {
        val mime = mimeType?.lowercase()
        if (mime != null && mime.startsWith("video/")) return false
        if (mime != null && mime.startsWith("audio/")) return false
        if (mime != null && mime.startsWith("image/")) return true
        // 部分设备对 RAW 给的 mime 是 application/octet-stream 或干脆没有，用扩展名兜底
        return extensionOf(name) in IMAGE_EXTENSIONS
    }

    /** M6：视频判定（MIME 优先，扩展名兜底；AVI 的 MIME 常为 video/x-msvideo 或 octet-stream）。 */
    fun isVideoWork(name: String, mimeType: String?): Boolean {
        val mime = mimeType?.lowercase()
        if (mime != null && mime.startsWith("video/")) return true
        if (mime != null && (mime.startsWith("image/") || mime.startsWith("audio/"))) return false
        return extensionOf(name) in VIDEO_EXTENSIONS
    }

    /** 作品 = 图片或视频。 */
    fun isWork(name: String, mimeType: String?): Boolean =
        isImageWork(name, mimeType) || isVideoWork(name, mimeType)

    fun isVideoByName(name: String): Boolean = extensionOf(name) in VIDEO_EXTENSIONS
}
