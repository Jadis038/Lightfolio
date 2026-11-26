package com.yingce.gallery.ui

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import java.io.File

/**
 * 自测的取样器：把目标目录里的文件变成"可被本地路径读取的 File"。
 *
 * 为什么需要它：Android 11+ 即使持有 READ_MEDIA_IMAGES 也不能 listFiles() 任意目录，
 * 只能通过 MediaStore 看到媒体项。而 libraw 需要真实文件路径，所以：
 *   1) 先用 File 直读（若目录可列且可读，例如 App 自己的外部目录）
 *   2) 否则用 MediaStore 按 RELATIVE_PATH 枚举，把内容拷到 cache 再探测
 */
object SelfTestFiles {

    fun collect(context: Context, dirPath: String): List<File> {
        val direct = File(dirPath).listFiles()
            ?.filter { it.isFile && !it.name.startsWith(".") }
            ?.sortedBy { it.name }
        if (!direct.isNullOrEmpty()) return direct

        val relPath = "Pictures/yingce-test"
        val cacheDir = File(context.cacheDir, "yingce_probe").apply { mkdirs() }
        cacheDir.listFiles()?.forEach { it.delete() }

        val out = ArrayList<File>()
        val collection = MediaStore.Files.getContentUri("external")
        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
        )
        val selection = "${MediaStore.Files.FileColumns.RELATIVE_PATH} LIKE ?"
        val args = arrayOf("$relPath%")

        runCatching {
            context.contentResolver.query(collection, projection, selection, args, "_display_name ASC")
                ?.use { cursor ->
                    val idIdx = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
                    val nameIdx = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(idIdx)
                        val name = cursor.getString(nameIdx) ?: continue
                        val uri = android.content.ContentUris.withAppendedId(collection, id)
                        val dst = File(cacheDir, name)
                        runCatching {
                            context.contentResolver.openInputStream(uri)?.use { input ->
                                dst.outputStream().use { output -> input.copyTo(output, 1 shl 20) }
                            }
                        }
                        if (dst.exists() && dst.length() > 0) out += dst
                    }
                }
        }
        return out.sortedBy { it.name }
    }
}
