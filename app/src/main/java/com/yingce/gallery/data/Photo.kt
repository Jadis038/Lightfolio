package com.yingce.gallery.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 一条已导入的作品。
 *
 * 字段职责（严禁混用）：
 * - [displayName] 原始文件名，界面任何位置只显示它，**不带 (1)(2) 后缀**。
 * - [storedName]  复制模式下真实落盘名（可能带后缀）；仅引用模式为 null。
 * - [sourceUri]   源文件的 content:// URI，两种模式都记录，用于"已导入"定位与仅引用读取。
 */
@Entity(
    tableName = "photos",
    indices = [
        Index(value = ["stored_name"]),
        Index(value = ["imported_at"]),
        Index(value = ["taken_at"]),
        Index(value = ["fingerprint"]),
        Index(value = ["camera_model"]),
        Index(value = ["source_uri"]),
    ],
)
data class Photo(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,

    @ColumnInfo(name = "display_name")
    val displayName: String,

    @ColumnInfo(name = "stored_name")
    val storedName: String? = null,

    @ColumnInfo(name = "size_bytes")
    val sizeBytes: Long,

    @ColumnInfo(name = "mime_type")
    val mimeType: String? = null,

    @ColumnInfo(name = "imported_at")
    val importedAt: Long,

    /** 来源：MEDIASTORE（相册）/ SAF（文件管理） */
    @ColumnInfo(name = "source_kind")
    val sourceKind: String = SourceKind.MEDIASTORE.name,

    /** 源 content URI（字符串） */
    @ColumnInfo(name = "source_uri")
    val sourceUri: String? = null,

    /** 导入方式：COPY（复制到 App 目录）/ REFERENCE（仅引用） */
    @ColumnInfo(name = "import_mode")
    val importMode: String = ImportMode.COPY.name,

    /** 拍摄时间（毫秒），排序用：EXIF DateTimeOriginal 优先，缺失回退文件时间 */
    @ColumnInfo(name = "taken_at")
    val takenAt: Long = 0L,

    /** takenAt 的来源：EXIF / FILE，用于界面说明，不用于排序 */
    @ColumnInfo(name = "taken_at_source")
    val takenAtSource: String = TakenAtSource.FILE.name,

    @ColumnInfo(name = "camera_make")
    val cameraMake: String? = null,

    @ColumnInfo(name = "camera_model")
    val cameraModel: String? = null,

    @ColumnInfo(name = "gps_lat")
    val gpsLat: Double? = null,

    @ColumnInfo(name = "gps_lon")
    val gpsLon: Double? = null,

    /** 去重指纹：(size, 首 64KB SHA-256, 尾 64KB SHA-256) 的复合串 */
    @ColumnInfo(name = "fingerprint")
    val fingerprint: String? = null,

    /** OK / MISSING（仅引用模式下源被删或权限丢失） */
    @ColumnInfo(name = "state")
    val state: String = PhotoState.OK.name,
) {
    val isReference: Boolean get() = importMode == ImportMode.REFERENCE.name
}

enum class SourceKind { MEDIASTORE, SAF }

enum class ImportMode { COPY, REFERENCE }

enum class TakenAtSource { EXIF, FILE }

enum class PhotoState { OK, MISSING }
