package com.yingce.gallery.ui

import android.content.Context
import android.net.Uri
import com.yingce.gallery.data.ImportMode
import com.yingce.gallery.data.Photo
import com.yingce.gallery.exif.ExifReader
import com.yingce.gallery.exif.PhotoExif
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** 统一解析一张作品"该从哪里读像素/元数据"。 */
object PhotoSource {

    fun fileOf(context: Context, photo: Photo): File? =
        photo.storedName?.let { File(context.filesDir, "photos/$it") }

    /** Coil 的 model：复制模式给 File，仅引用模式给 content Uri。 */
    fun model(context: Context, photo: Photo): Any =
        if (photo.importMode == ImportMode.REFERENCE.name) {
            photo.sourceUri?.let(Uri::parse) ?: Uri.EMPTY
        } else {
            fileOf(context, photo) ?: Uri.EMPTY
        }

    /** 仅引用模式下源是否还可读（被删/权限丢失会失败）。 */
    fun isReachable(context: Context, photo: Photo): Boolean {
        if (photo.importMode == ImportMode.REFERENCE.name) {
            val uri = photo.sourceUri?.let(Uri::parse) ?: return false
            return runCatching { context.contentResolver.openInputStream(uri)?.close(); true }
                .getOrDefault(false)
        }
        return fileOf(context, photo)?.exists() == true
    }
}

/** 详情页用：复制模式直接读本地文件，仅引用模式先落一份临时副本再解析。 */
object PhotoExifLoader {

    suspend fun load(context: Context, photo: Photo): PhotoExif = withContext(Dispatchers.IO) {
        val file = PhotoSource.fileOf(context, photo)
        if (file != null && file.exists()) {
            return@withContext ExifReader.read(file)
        }
        val uri = photo.sourceUri?.let(Uri::parse)
        if (uri != null && photo.importMode == ImportMode.REFERENCE.name) {
            val temp = File(context.cacheDir, ".detail_${photo.id}.tmp")
            try {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    temp.outputStream().use { output -> input.copyTo(output, 64 * 1024) }
                }
                return@withContext ExifReader.read(temp)
            } catch (_: Exception) {
                // 落到下面的兜底
            } finally {
                temp.delete()
            }
        }
        PhotoExif(fileName = photo.displayName, formatLabel = "", sizeBytes = photo.sizeBytes)
    }
}
