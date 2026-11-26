package com.yingce.gallery.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.ContextCompat

/**
 * 相册（MediaStore）来源。
 *
 * 权限现状（Android 14+）：
 * - `READ_MEDIA_IMAGES` 已授予 = 可访问全部图片
 * - 只授予 `READ_MEDIA_VISUAL_USER_SELECTED` = 用户只给了"部分照片"
 *   → 界面必须提供"申请访问全部"入口（本轮需求第 9 条）
 */
object GalleryAccess {

    fun requiredPermissions(): Array<String> = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
            Manifest.permission.ACCESS_MEDIA_LOCATION,
        )
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.ACCESS_MEDIA_LOCATION,
        )
        else -> arrayOf(
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.ACCESS_MEDIA_LOCATION,
        )
    }

    fun hasFullAccess(context: Context): Boolean {
        val perm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        return ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
    }

    fun hasPartialAccess(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return false
        return !hasFullAccess(context) &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
            ) == PackageManager.PERMISSION_GRANTED
    }

    fun hasAnyAccess(context: Context): Boolean = hasFullAccess(context) || hasPartialAccess(context)

    /** Android 10+ 读取未脱敏的原图（含 GPS）需要这个权限。 */
    fun hasLocationAccess(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return true
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_MEDIA_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
    }
}

data class DeviceImage(
    val id: Long,
    val uri: Uri,
    val displayName: String,
    val sizeBytes: Long,
    val mimeType: String?,
    val dateModifiedMs: Long,
    val dateTakenMs: Long,
)

object MediaStoreSource {

    /**
     * 查询设备图片（含 RAW）。RAW 在 MediaStore 里通常 mime 为 image/x-*，
     * 个别设备会给 application/octet-stream，因此同时用扩展名兜底。
     */
    /**
     * M6/M8：视频查询。
     *
     * 第一版用 MediaStore.Files + MEDIA_TYPE 过滤，实测在部分机型（以及 Android 14 的
     * 部分授权状态下）拿不到结果，导致"相册里看不到视频"。这里改成双查询：
     *   1) MediaStore.Video.Media（标准视频集合，最可靠）
     *   2) MediaStore.Files 扫 video/ 的 MIME（兜底 MKV/AVI 等可能没被识别成视频的文件）
     * 去重后按修改时间倒序。
     */
    fun queryVideos(context: Context, limit: Int = 2000): List<DeviceImage> {
        val out = LinkedHashMap<String, DeviceImage>()
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.MIME_TYPE,
            MediaStore.Video.Media.DATE_MODIFIED,
            MediaStore.Video.Media.DATE_TAKEN,
        )

        fun addFrom(uri: android.net.Uri, selection: String?, args: Array<String>?) {
            runCatching {
                context.contentResolver.query(uri, projection, selection, args, "date_modified DESC")
                    ?.use { cursor ->
                        val idIdx = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                        val nameIdx = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                        val sizeIdx = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
                        val mimeIdx = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.MIME_TYPE)
                        val modIdx = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_MODIFIED)
                        val takenIdx = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_TAKEN)
                        while (cursor.moveToNext() && out.size < limit) {
                            val id = cursor.getLong(idIdx)
                            val name = cursor.getString(nameIdx) ?: continue
                            val mime = if (cursor.isNull(mimeIdx)) null else cursor.getString(mimeIdx)
                            if (!MediaTypes.isVideoWork(name, mime)) continue
                            val size = if (cursor.isNull(sizeIdx)) 0L else cursor.getLong(sizeIdx)
                            val mod = if (cursor.isNull(modIdx)) 0L else cursor.getLong(modIdx) * 1000L
                            val taken = if (cursor.isNull(takenIdx)) 0L else cursor.getLong(takenIdx)
                            val itemUri = android.content.ContentUris.withAppendedId(uri, id)
                            // 两个查询集合的 _ID 不同但可能是同一个文件，用 名称+大小 去重
                            out["$name|$size"] = DeviceImage(
                                id = id,
                                uri = itemUri,
                                displayName = name,
                                sizeBytes = size,
                                mimeType = mime,
                                dateModifiedMs = mod,
                                dateTakenMs = taken,
                            )
                        }
                    }
            }
        }

        // 1) 标准视频集合
        addFrom(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, null, null)
        // 2) 兜底：Files 里 MIME 为 video/* 或扩展名命中视频
        addFrom(
            MediaStore.Files.getContentUri("external"),
            "${MediaStore.Files.FileColumns.MIME_TYPE} LIKE 'video/%'",
            null,
        )

        return out.values.sortedByDescending { it.dateModifiedMs }.take(limit)
    }
    fun queryImages(context: Context, limit: Int = 5000): List<DeviceImage> {
        val collection = MediaStore.Files.getContentUri("external")
        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.SIZE,
            MediaStore.Files.FileColumns.MIME_TYPE,
            MediaStore.Files.FileColumns.DATE_MODIFIED,
            MediaStore.Files.FileColumns.DATE_TAKEN,
        )
        val selection = "${MediaStore.Files.FileColumns.MEDIA_TYPE} = ? OR " +
            "${MediaStore.Files.FileColumns.MIME_TYPE} LIKE 'image/%'"
        val args = arrayOf(MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString())

        val out = ArrayList<DeviceImage>(256)
        runCatching {
            context.contentResolver.query(
                collection,
                projection,
                selection,
                args,
                "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC",
            )?.use { cursor ->
                val idIdx = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
                val nameIdx = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
                val sizeIdx = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)
                val mimeIdx = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MIME_TYPE)
                val modIdx = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_MODIFIED)
                val takenIdx = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_TAKEN)

                while (cursor.moveToNext() && out.size < limit) {
                    val id = cursor.getLong(idIdx)
                    val name = cursor.getString(nameIdx) ?: continue
                    val mime = if (cursor.isNull(mimeIdx)) null else cursor.getString(mimeIdx)
                    if (!MediaTypes.isWork(name, mime)) continue
                    val size = if (cursor.isNull(sizeIdx)) 0L else cursor.getLong(sizeIdx)
                    val mod = if (cursor.isNull(modIdx)) 0L else cursor.getLong(modIdx) * 1000L
                    val taken = if (cursor.isNull(takenIdx)) 0L else cursor.getLong(takenIdx)
                    out += DeviceImage(
                        id = id,
                        uri = MediaStore.Files.getContentUri("external", id),
                        displayName = name,
                        sizeBytes = size,
                        mimeType = mime,
                        dateModifiedMs = mod,
                        dateTakenMs = taken,
                    )
                }
            }
        }
        return out
    }
}
