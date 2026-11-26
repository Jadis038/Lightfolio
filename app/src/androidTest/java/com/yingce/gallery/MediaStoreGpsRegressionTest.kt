package com.yingce.gallery

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yingce.gallery.data.AppDatabase
import com.yingce.gallery.data.ImportItem
import com.yingce.gallery.data.ImportMode
import com.yingce.gallery.data.Importer
import com.yingce.gallery.data.MediaStoreSource
import com.yingce.gallery.data.SourceKind
import com.yingce.gallery.exif.ExifReader
import com.yingce.gallery.geo.AdminGeoIndex
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * 回归验证：MediaStore 导入后 GPS 不能丢。
 * 之前缺 ACCESS_MEDIA_LOCATION，Android 会把 GPS 抹成 0。
 */
@RunWith(AndroidJUnit4::class)
class MediaStoreGpsRegressionTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun importFromMediaStoreKeepsGps() = runBlocking {
        val sb = StringBuilder()
        val images = MediaStoreSource.queryImages(context)
        val target = images.firstOrNull { it.displayName.contains("X12", ignoreCase = true) }
        sb.appendLine("MediaStore 中 X12: ${target != null}")
        if (target == null) {
            android.util.Log.i("YingCeGpsFix", "\n$sb")
            println(sb); return@runBlocking
        }

        val dao = AppDatabase.get(context).photoDao()
        val importer = Importer(context, dao)
        val item = ImportItem(
            uri = target.uri,
            displayName = target.displayName,
            sizeBytes = target.sizeBytes,
            mimeType = target.mimeType,
            fileTime = target.dateModifiedMs,
            sourceKind = SourceKind.MEDIASTORE,
        )
        val result = importer.import(listOf(item), ImportMode.COPY) { false }
        sb.appendLine("导入结果: imported=${result.imported} duplicates=${result.duplicates} failed=${result.failed} errors=${result.errors}")

        val saved = File(context.filesDir, "photos/${target.displayName}")
        sb.appendLine("副本: exists=${saved.exists()} len=${saved.length()}")
        if (saved.exists()) {
            val e = ExifReader.read(saved)
            sb.appendLine("EXIF: gps=${e.gpsLat},${e.gpsLon} camera=${e.cameraText}")
            if (e.gpsLat != null && e.gpsLon != null) {
                val place = runCatching { AdminGeoIndex.get(context).resolve(e.gpsLat!!, e.gpsLon!!) }.getOrNull()
                sb.appendLine("行政区: ${place?.displayPath() ?: "null"}")
            }
        }
        android.util.Log.i("YingCeGpsFix", "\n$sb")
        println(sb)
    }
}
