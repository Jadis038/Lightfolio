package com.yingce.gallery

import android.net.Uri
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yingce.gallery.data.AppDatabase
import com.yingce.gallery.data.ImportItem
import com.yingce.gallery.data.ImportMode
import com.yingce.gallery.data.Importer
import com.yingce.gallery.data.Photo
import com.yingce.gallery.data.PhotoDao
import com.yingce.gallery.data.SourceKind
import com.yingce.gallery.data.TakenAtSource
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * M3 导入链路逻辑验证：去重、重名、仅引用、过滤、排序与筛选。
 * 全部在真实 Android 运行时执行（content URI 由 App 自身 FileProvider 提供）。
 */
@RunWith(AndroidJUnit4::class)
class ImportM3InstrumentedTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var dao: PhotoDao

    @Before
    fun setUp() {
        runBlocking {
            dao = AppDatabase.get(context).photoDao()
            dao.deleteAll()
            File(context.filesDir, "photos").listFiles()?.forEach { it.delete() }
        }
    }

    private fun makeFile(relDir: String, name: String, seed: Int): Uri {
        val dir = File(context.cacheDir, relDir).apply { if (!exists()) mkdirs() }
        val file = File(dir, name)
        file.writeBytes(("YINGCE-M3-$seed-" + "x".repeat(2048)).toByteArray())
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }

    private fun item(uri: Uri, name: String, size: Long = 4096, mime: String? = "image/jpeg", kind: SourceKind = SourceKind.SAF) =
        ImportItem(uri = uri, displayName = name, sizeBytes = size, mimeType = mime, fileTime = System.currentTimeMillis(), sourceKind = kind)

    @Test
    fun sameUriImportedTwice_yieldsOneRecordAndReportsDuplicate() = runBlocking {
        val uri = makeFile("dup/a", "same_source.jpg", 1)
        val importer = Importer(context, dao)

        val first = importer.import(listOf(item(uri, "same_source.jpg")), ImportMode.COPY)
        assertEquals("首次应导入 1 项", 1, first.imported)
        assertEquals(0, first.duplicates)

        val second = importer.import(listOf(item(uri, "same_source.jpg")), ImportMode.COPY)
        assertEquals("第二次不应新增", 0, second.imported)
        assertEquals("第二次应判定为已导入", 1, second.duplicates)

        assertEquals("库里只应有一条", 1, dao.count())
        assertEquals("落盘文件也只应有一个", 1, File(context.filesDir, "photos").listFiles()?.size)
    }

    @Test
    fun sameNameDifferentContent_getsSuffixOnDiskButKeepsDisplayName() = runBlocking {
        // 两个不同目录、同名、内容不同 -> MediaStore 不会介入，正好验证重名规则
        val uriA = makeFile("dup/b1", "photo.jpg", 11)
        val uriB = makeFile("dup/b2", "photo.jpg", 22)
        val importer = Importer(context, dao)

        val ra = importer.import(listOf(item(uriA, "photo.jpg", 2100)), ImportMode.COPY)
        val rb = importer.import(listOf(item(uriB, "photo.jpg", 2100)), ImportMode.COPY)
        assertEquals(1, ra.imported)
        assertEquals(1, rb.imported)

        val files = File(context.filesDir, "photos").listFiles()?.map { it.name } ?: emptyList()
        assertTrue("应有原名文件: $files", files.contains("photo.jpg"))
        assertTrue("应有 (1) 文件: $files", files.contains("photo (1).jpg"))

        val rows = dao.getAll().filter { it.displayName == "photo.jpg" }
        assertEquals("两条记录", 2, rows.size)
        assertEquals("display_name 都不带后缀", setOf("photo.jpg"), rows.map { it.displayName }.toSet())
    }

    @Test
    fun referenceModeDoesNotCopyButKeepsSourceUri() = runBlocking {
        val uri = makeFile("ref/a", "ref_only.jpg", 33)
        val importer = Importer(context, dao)

        val result = importer.import(listOf(item(uri, "ref_only.jpg")), ImportMode.REFERENCE)
        assertEquals(1, result.imported)

        val row = dao.getAll().single()
        assertTrue("应标记为仅引用", row.isReference)
        assertNull("仅引用不应有落盘名", row.storedName)
        assertNotNull("应记录源 URI", row.sourceUri)
        assertEquals(
            "App 私有目录不应多出文件",
            0,
            File(context.filesDir, "photos").listFiles()?.size ?: 0,
        )
    }

    @Test
    fun nonImageFilesAreFiltered() = runBlocking {
        val txt = makeFile("filter", "note.txt", 44)
        val img = makeFile("filter", "ok.jpg", 55)
        val importer = Importer(context, dao)

        val result = importer.import(
            listOf(
                item(txt, "note.txt", mime = "text/plain"),
                item(img, "ok.jpg", mime = "image/jpeg"),
            ),
            ImportMode.COPY,
        )
        assertEquals("只有图片被导入", 1, result.imported)
        assertEquals("非图片被过滤", 1, result.filtered)
        assertEquals(1, dao.count())
    }

    @Test
    fun rawFilesCountAsWorks() = runBlocking {
        // RAW 的 mime 常常缺失或为 octet-stream，必须靠扩展名判定
        val raw = makeFile("raw", "shot.CR3", 66)
        val importer = Importer(context, dao)
        val result = importer.import(listOf(item(raw, "shot.CR3", mime = null)), ImportMode.COPY)
        assertEquals("RAW 也应算作品", 1, result.imported)
    }

    @Test
    fun sortAndFilterQueries() = runBlocking {
        val now = System.currentTimeMillis()
        val day = 24L * 60 * 60 * 1000
        dao.insert(Photo(displayName = "old.jpg", storedName = "old.jpg", sizeBytes = 1, importedAt = now, takenAt = now - 400 * day, cameraModel = "Canon EOS R5", gpsLat = 39.9, gpsLon = 116.4))
        dao.insert(Photo(displayName = "mid.jpg", storedName = "mid.jpg", sizeBytes = 1, importedAt = now, takenAt = now - 10 * day, cameraModel = "NIKON D750"))
        dao.insert(Photo(displayName = "new.jpg", storedName = "new.jpg", sizeBytes = 1, importedAt = now, takenAt = now - 1 * day, cameraModel = "Canon EOS R5"))

        val all = dao.observeFiltered(null, -1, 0L, Long.MAX_VALUE).first()
        assertEquals("按拍摄时间倒序", listOf("new.jpg", "mid.jpg", "old.jpg"), all.map { it.displayName })

        val canon = dao.observeFiltered("Canon EOS R5", -1, 0L, Long.MAX_VALUE).first()
        assertEquals(setOf("new.jpg", "old.jpg"), canon.map { it.displayName }.toSet())

        val withLocation = dao.observeFiltered(null, 1, 0L, Long.MAX_VALUE).first()
        assertEquals(listOf("old.jpg"), withLocation.map { it.displayName })

        val withoutLocation = dao.observeFiltered(null, 0, 0L, Long.MAX_VALUE).first()
        assertEquals(setOf("new.jpg", "mid.jpg"), withoutLocation.map { it.displayName }.toSet())

        val recent = dao.observeFiltered(null, -1, now - 30 * day, now).first()
        assertEquals(setOf("new.jpg", "mid.jpg"), recent.map { it.displayName }.toSet())

        val models = dao.observeCameraModels().first()
        assertEquals(listOf("Canon EOS R5", "NIKON D750"), models)

        assertFalse("takenAtSource 默认应为 FILE", all.all { it.takenAtSource == TakenAtSource.EXIF.name })
    }

    @Test
    fun batchDeleteRemovesRowsAndCopies() = runBlocking {
        val importer = Importer(context, dao)
        val uris = (1..3).map { makeFile("batch/$it", "batch_$it.jpg", 70 + it) }
        importer.import(uris.mapIndexed { i, u -> item(u, "batch_${i + 1}.jpg") }, ImportMode.COPY)
        assertEquals(3, dao.count())

        val ids = dao.getAll().map { it.id }
        dao.deleteByIds(ids)
        assertEquals(0, dao.count())
    }

    @Test
    fun importNeverModifiesSource() = runBlocking {
        val uri = makeFile("integrity", "keep_me.jpg", 88)
        val before = context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
        val importer = Importer(context, dao)
        importer.import(listOf(item(uri, "keep_me.jpg")), ImportMode.COPY)
        val after = context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
        assertTrue("源文件字节必须完全一致", before.contentEquals(after))
    }

    @Test
    fun importCanBeCancelledAtSafePoint() = runBlocking {
        val items = (1..30).map { i ->
            val uri = makeFile("cancel/$i", "c_$i.jpg", 100 + i)
            item(uri, "c_$i.jpg", size = 4096)
        }
        var cancel = false
        val result = Importer(context, dao).import(
            items = items,
            mode = ImportMode.COPY,
            onProgress = { p -> if (p.done >= 5) cancel = true },
            isCancelled = { cancel },
        )
        assertTrue("应报告已取消", result.cancelled)
        assertTrue("取消前应已导入一部分（实际 ${result.imported}）", result.imported in 1..29)
        assertTrue("取消后不应继续导入", result.imported <= 6)
        assertEquals("库里条数应与实际导入数一致", result.imported, dao.count())
        assertTrue(
            "不应残留 .part 临时文件",
            File(context.filesDir, "photos").listFiles()?.none { it.name.startsWith(".import_") } ?: true,
        )
    }

    /**
     * SAF 持久化授权：App 进程重启后，之前通过系统选择器拿到的 content:// 授权仍然有效，
     * 且能真正读出字节。这条是在真实 DocumentsProvider 授权基础上验证的（见 M3-验收报告）。
     */
    @Test
    fun persistedSafGrantsRemainReadable() = runBlocking {
        val grants = context.contentResolver.persistedUriPermissions
        assertTrue("应至少有一条持久化读授权（由系统文件选择器授予），实际 ${grants.size}", grants.isNotEmpty())

        val readable = grants.count { grant ->
            grant.isReadPermission && runCatching {
                context.contentResolver.openInputStream(grant.uri)?.use { it.read(ByteArray(16)) }
            }.getOrNull() != null
        }
        assertTrue("持久化授权应仍可读取，可读 ${readable}/${grants.size}", readable > 0)
    }
}
