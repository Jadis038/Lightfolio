package com.yingce.gallery.data

import android.content.Context
import android.net.Uri
import android.os.StatFs
import android.provider.MediaStore
import android.provider.OpenableColumns
import com.yingce.gallery.exif.ExifReader
import com.yingce.gallery.exif.PhotoExif
import com.yingce.gallery.util.Naming
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * 导入器：支持"复制到 App 目录"与"仅引用不复制"，带指纹去重、空间检查、可取消、进度回调。
 *
 * 三条铁律：
 * 1. 源文件只读：只 openInputStream，绝不写回、改名、删除源。
 * 2. 复制模式先写 .part 再改名，失败不留半截文件。
 * 3. 去重优先按源 URI，其次按 (size + 首尾 64KB) 指纹；命中即报"已导入"，不生成副本。
 */
class Importer(
    private val context: Context,
    private val dao: PhotoDao,
) {

    fun photoDir(): File = File(context.filesDir, "photos").apply { if (!exists()) mkdirs() }

    private sealed interface Outcome {
        data object Imported : Outcome
        data object Duplicate : Outcome
        data class Failed(val reason: String) : Outcome
    }

    suspend fun import(
        items: List<ImportItem>,
        mode: ImportMode,
        onProgress: (ImportProgress) -> Unit = {},
        isCancelled: () -> Boolean = { false },
    ): ImportResult = withContext(Dispatchers.IO) {
        val works = items.filter { MediaTypes.isWork(it.displayName, it.mimeType) }
        val filteredCount = items.size - works.size
        if (works.isEmpty()) return@withContext ImportResult(filtered = filteredCount)

        // 空间检查：只统计复制模式；预留 32MB 余量给 Room 与临时文件
        if (mode == ImportMode.COPY) {
            val need = works.sumOf { it.sizeBytes }
            val available = runCatching { StatFs(context.filesDir.absolutePath).availableBytes }.getOrDefault(0L)
            val margin = 32L * 1024 * 1024
            if (available > 0 && need + margin > available) {
                return@withContext ImportResult(
                    filtered = filteredCount,
                    spaceShortfallBytes = need + margin - available,
                )
            }
        }

        var imported = 0
        var duplicates = 0
        var failed = 0
        var bytesDone = 0L
        var cancelled = false
        val errors = mutableListOf<String>()
        val bytesTotal = works.sumOf { it.sizeBytes }

        for ((index, item) in works.withIndex()) {
            if (isCancelled()) {
                cancelled = true
                break
            }
            when (val outcome = runCatching { importOne(item, mode) }.getOrElse { Outcome.Failed(it.message ?: it.javaClass.simpleName) }) {
                is Outcome.Imported -> imported++
                is Outcome.Duplicate -> duplicates++
                is Outcome.Failed -> {
                    failed++
                    if (errors.size < 10) errors += "${item.displayName}: ${outcome.reason}"
                }
            }
            bytesDone += item.sizeBytes
            onProgress(
                ImportProgress(
                    done = index + 1,
                    total = works.size,
                    bytesDone = bytesDone,
                    bytesTotal = bytesTotal,
                    currentName = item.displayName,
                )
            )
        }

        ImportResult(
            imported = imported,
            duplicates = duplicates,
            filtered = filteredCount,
            failed = failed,
            cancelled = cancelled,
            errors = errors,
        )
    }

    private suspend fun importOne(item: ImportItem, mode: ImportMode): Outcome {
        // 1) 同一源 URI 已经导入过
        if (dao.findBySourceUri(item.uri.toString()) != null) return Outcome.Duplicate

        val dir = photoDir()
        val staging = File(
            if (mode == ImportMode.COPY) dir else context.cacheDir,
            ".import_${System.nanoTime()}.part",
        )

        val tap = HashTap()
        try {
            // MediaStore 默认会脱敏位置（把 GPS 抹成 0）。持有 ACCESS_MEDIA_LOCATION 时
            // 必须显式 setRequireOriginal 才能拿到未脱敏的原图数据。
            // 关键：没授权时 setRequireOriginal 会在打开流时抛 SecurityException 导致整条导入失败，
            // 所以必须先检查权限，没授权就用普通 URI（能导入，只是没有 GPS）。
            val canReadOriginal = item.sourceKind == SourceKind.MEDIASTORE &&
                android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q &&
                androidx.core.content.ContextCompat.checkSelfPermission(
                    context,
                    android.Manifest.permission.ACCESS_MEDIA_LOCATION,
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            val readUri = if (canReadOriginal) {
                runCatching { MediaStore.setRequireOriginal(item.uri) }.getOrDefault(item.uri)
            } else item.uri
            context.contentResolver.openInputStream(readUri)?.use { input ->
                staging.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n <= 0) break
                        tap.write(buffer, 0, n)
                        output.write(buffer, 0, n)
                    }
                }
            } ?: return Outcome.Failed("无法读取源文件")
        } catch (io: IOException) {
            staging.delete()
            return Outcome.Failed("读取失败: ${io.message}")
        }

        if (tap.totalBytes <= 0L) {
            staging.delete()
            return Outcome.Failed("源文件为空")
        }

        // 2) 指纹去重（同内容不同路径）
        val fingerprint = tap.fingerprint()
        if (dao.findByFingerprint(fingerprint) != null) {
            staging.delete()
            return Outcome.Duplicate
        }

        // 3) 解析 EXIF（在临时副本上做，避免二次读取源）
        val exif: PhotoExif = runCatching { ExifReader.read(staging) }.getOrDefault(
            PhotoExif(fileName = item.displayName, formatLabel = "", sizeBytes = staging.length())
        )

        val takenAt = exif.takenAtEpochMs ?: item.fileTime.takeIf { it > 0 } ?: System.currentTimeMillis()
        val takenSource = if (exif.takenAtEpochMs != null) TakenAtSource.EXIF else TakenAtSource.FILE

        val storedName: String?
        if (mode == ImportMode.COPY) {
            val resolved = Naming.resolve(item.displayName) { candidate -> File(dir, candidate).exists() }
            val target = File(dir, resolved)
            if (!staging.renameTo(target)) {
                staging.delete()
                return Outcome.Failed("落盘失败: $resolved")
            }
            storedName = resolved
        } else {
            storedName = null
            staging.delete()
        }

        dao.insert(
            Photo(
                displayName = item.displayName,
                storedName = storedName,
                sizeBytes = if (mode == ImportMode.COPY) File(dir, storedName!!).length() else tap.totalBytes,
                mimeType = item.mimeType,
                importedAt = System.currentTimeMillis(),
                sourceKind = item.sourceKind.name,
                sourceUri = item.uri.toString(),
                importMode = mode.name,
                takenAt = takenAt,
                takenAtSource = takenSource.name,
                cameraMake = exif.make,
                cameraModel = exif.cameraText.takeIf { it != "该文件未记录" },
                gpsLat = exif.gpsLat,
                gpsLon = exif.gpsLon,
                fingerprint = fingerprint,
                state = PhotoState.OK.name,
            )
        )
        return Outcome.Imported
    }
}

/** 从 content URI 读出导入所需的元数据。 */
object UriMeta {

    data class Basic(
        val displayName: String,
        val sizeBytes: Long,
        val mimeType: String?,
        val fileTime: Long,
    )

    fun query(context: Context, uri: Uri): Basic? {
        var name: String? = null
        var size = 0L
        var lastModified = 0L

        runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(
                    OpenableColumns.DISPLAY_NAME,
                    OpenableColumns.SIZE,
                    android.provider.MediaStore.MediaColumns.DATE_MODIFIED,
                ),
                null, null, null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIdx >= 0) name = cursor.getString(nameIdx)
                    val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) size = cursor.getLong(sizeIdx)
                    val modIdx = cursor.getColumnIndex(android.provider.MediaStore.MediaColumns.DATE_MODIFIED)
                    if (modIdx >= 0 && !cursor.isNull(modIdx)) lastModified = cursor.getLong(modIdx) * 1000L
                }
            }
        }

        val resolvedName = name?.takeIf { it.isNotBlank() }
            ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { !it.isNullOrBlank() }
            ?: return null

        return Basic(
            displayName = resolvedName,
            sizeBytes = if (size > 0) size else 0L,
            mimeType = runCatching { context.contentResolver.getType(uri) }.getOrNull(),
            fileTime = lastModified,
        )
    }

    fun toItem(context: Context, uri: Uri, kind: SourceKind): ImportItem? {
        val meta = query(context, uri) ?: return null
        return ImportItem(
            uri = uri,
            displayName = meta.displayName,
            sizeBytes = meta.sizeBytes,
            mimeType = meta.mimeType,
            fileTime = meta.fileTime,
            sourceKind = kind,
        )
    }
}
