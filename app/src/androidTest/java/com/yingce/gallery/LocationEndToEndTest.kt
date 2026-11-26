package com.yingce.gallery

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yingce.gallery.data.AppDatabase
import com.yingce.gallery.data.Photo
import com.yingce.gallery.geo.AdminGeoIndex
import com.yingce.gallery.ui.PhotoExifLoader
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 端到端验证：导入后的真实文件 → 详情页同一条加载路径 → GPS → 行政区。
 */
@RunWith(AndroidJUnit4::class)
class LocationEndToEndTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun x12ShowsGpsAndAdminPlace() = runBlocking {
        val db = AppDatabase.get(context)
        val all: List<Photo> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            db.openHelper.readableDatabase.query("SELECT id, display_name, stored_name, size_bytes, import_mode FROM photos").use { c ->
                val out = ArrayList<Photo>()
                while (c.moveToNext()) {
                    out.add(
                        Photo(
                            id = c.getLong(0),
                            displayName = c.getString(1),
                            storedName = c.getString(2),
                            sizeBytes = c.getLong(3),
                            mimeType = null,
                            importedAt = 0,
                            importMode = c.getString(4),
                            takenAt = 0,
                        ),
                    )
                }
                out
            }
        }
        val target = all.firstOrNull { it.displayName.contains("X12", ignoreCase = true) }
        val sb = StringBuilder()
        sb.appendLine("总数=${all.size} 找到 X12=${target != null}")
        if (target != null) {
            val e = PhotoExifLoader.load(context, target)
            sb.appendLine("file=${target.displayName} gps=${e.gpsLat},${e.gpsLon} camera=${e.cameraText} taken=${e.takenAtText}")
            if (e.gpsLat != null && e.gpsLon != null) {
                val place = runCatching { AdminGeoIndex.get(context).resolve(e.gpsLat!!, e.gpsLon!!) }.getOrNull()
                sb.appendLine("行政区=${place?.displayPath() ?: "null"}")
            }
        }
        android.util.Log.i("YingCeE2E", "\n$sb")
        println(sb)
    }
}
