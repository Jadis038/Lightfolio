package com.yingce.gallery

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yingce.gallery.data.AppDatabase
import com.yingce.gallery.data.ImportMode
import com.yingce.gallery.data.Photo
import com.yingce.gallery.data.PhotoDao
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** 造 2000 条作品，用于滚动性能与筛选实测；不参与产品逻辑。 */
@RunWith(AndroidJUnit4::class)
class SeedLargeLibraryTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var dao: PhotoDao

    @Before
    fun setUp() {
        runBlocking { dao = AppDatabase.get(context).photoDao() }
    }

    @Test
    fun seed2000() = runBlocking {
        dao.deleteAll()
        val dir = File(context.filesDir, "photos").apply { mkdirs() }
        // 用一张已存在的图做模板
        val template = File(dir, "YingCe_M3_01.jpg")
        assertTrue("需要一张模板图", template.exists())

        val now = System.currentTimeMillis()
        val day = 24L * 60 * 60 * 1000
        val models = listOf("Canon EOS R5", "NIKON D750", "SONY ILCE-7RM2", "FUJIFILM X-T10", "Hasselblad H3D")

        for (i in 1..2000) {
            val name = "seed_%04d.jpg".format(i)
            val target = File(dir, name)
            if (!target.exists()) template.copyTo(target, overwrite = true)
            dao.insert(
                Photo(
                    displayName = name,
                    storedName = name,
                    sizeBytes = target.length(),
                    mimeType = "image/jpeg",
                    importedAt = now - i * 1000L,
                    importMode = ImportMode.COPY.name,
                    takenAt = now - i * day / 10,
                    cameraModel = models[i % models.size],
                    gpsLat = if (i % 3 == 0) 39.9 else null,
                    gpsLon = if (i % 3 == 0) 116.4 else null,
                )
            )
        }
        val count = dao.count()
        assertEquals(2000, count)

        // 顺带验证筛选/排序在 2000 条数据上的行为
        val all = dao.observeFiltered(null, -1, 0L, Long.MAX_VALUE).first()
        assertEquals(2000, all.size)
        assertTrue("应按拍摄时间倒序", all.zipWithNext().all { (a, b) -> a.takenAt >= b.takenAt })

        val canon = dao.observeFiltered("Canon EOS R5", -1, 0L, Long.MAX_VALUE).first()
        assertEquals(400, canon.size)
        assertTrue(canon.all { it.cameraModel == "Canon EOS R5" })

        val withGps = dao.observeFiltered(null, 1, 0L, Long.MAX_VALUE).first()
        assertTrue("有位置信息的应约 1/3", withGps.size in 600..700)

        val modelsOut = dao.observeCameraModels().first()
        assertEquals(5, modelsOut.size)
    }
}
