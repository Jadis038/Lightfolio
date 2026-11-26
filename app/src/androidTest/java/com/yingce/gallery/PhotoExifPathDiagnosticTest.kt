package com.yingce.gallery

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yingce.gallery.data.AppDatabase
import com.yingce.gallery.data.Photo
import com.yingce.gallery.exif.ExifReader
import com.yingce.gallery.ui.PhotoExifLoader
import com.yingce.gallery.raw.RawImage
import com.yingce.gallery.raw.RawOrientation
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * 诊断 1：走**详情页真实的加载路径**（PhotoExifLoader），而不是直接调 ExifReader，
 * 看 GPS 是否丢失——这决定了问题在解析器还是在 UI 取值。
 *
 * 诊断 2：把 iPhone DNG 全解 + 旋转后存成 PNG，用于肉眼确认方向是否正确。
 */
@RunWith(AndroidJUnit4::class)
class PhotoExifPathDiagnosticTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun detailPanelPathKeepsGps() = runBlocking {
        val db = AppDatabase.get(context)
        val photos = db.photoDao().observeFiltered(null, -1, 0, Long.MAX_VALUE).let {
            // 直接用一次查询拿几行
            db.photoDao().findByFingerprint("none") // 触发实例
            db.photoDao()
        }
        val rows: List<Photo> = runCatching {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                db.openHelper.readableDatabase.query("SELECT * FROM photos LIMIT 3").use { c ->
                    val out = ArrayList<Photo>()
                    while (c.moveToNext()) {
                        out.add(
                            Photo(
                                id = c.getLong(c.getColumnIndexOrThrow("id")),
                                displayName = c.getString(c.getColumnIndexOrThrow("display_name")),
                                storedName = c.getString(c.getColumnIndexOrThrow("stored_name")),
                                sizeBytes = c.getLong(c.getColumnIndexOrThrow("size_bytes")),
                                mimeType = null,
                                importedAt = 0,
                                importMode = c.getString(c.getColumnIndexOrThrow("import_mode")),
                                takenAt = 0,
                            ),
                        )
                    }
                    out
                }
            }
        }.getOrElse { emptyList() }

        val sb = StringBuilder()
        sb.appendLine("数据库行数=${rows.size}")
        for (p in rows.take(2)) {
            val direct = runCatching {
                val f = File(context.filesDir, "photos/${p.storedName}")
                ExifReader.read(f)
            }.getOrNull()
            val viaLoader = runCatching { PhotoExifLoader.load(context, p) }.getOrNull()
            sb.appendLine("${p.displayName}: direct gps=${direct?.gpsLat},${direct?.gpsLon} | loader gps=${viaLoader?.gpsLat},${viaLoader?.gpsLon} camera=${viaLoader?.cameraText}")
        }
        android.util.Log.i("YingCeExifPath", "\n$sb")
        println(sb)
    }

    @Test
    fun dumpRotatedIphoneDng() {
        val f = File("/sdcard/Android/data/com.yingce.gallery.debug/files/selftest-in/Apple_iPhone_12_Pro.dng")
        if (!f.exists()) return
        val img = RawImage.open(f.absolutePath) ?: return
        img.use { r ->
            // 缩小解码范围以便快速导出：取左上 800x600 块，仍为 1:1 像素
            val w = minOf(800, r.width)
            val h = minOf(600, r.height)
            val px = r.decodeRegion(0, 0, w, h, 8) ?: return
            val oriented = RawOrientation.apply(px, w, h, r.flip)
            val bmp = Bitmap.createBitmap(oriented.pixels, oriented.width, oriented.height, Bitmap.Config.ARGB_8888)
            val out = File(context.getExternalFilesDir(null), "raw_rot_${r.flip}.png")
            FileOutputStream(out).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            android.util.Log.i("YingCeExifPath", "导出 ${out.absolutePath} flip=${r.flip} ${oriented.width}x${oriented.height}")
            bmp.recycle()
        }
    }
}
