package com.yingce.gallery

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yingce.gallery.data.AppDatabase
import com.yingce.gallery.exif.ExifLocator
import com.yingce.gallery.exif.ExifReader
import com.yingce.gallery.exif.TiffReader
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** 查清楚：为什么同一张图在电脑上能读出 GPS，在设备上读不到。 */
@RunWith(AndroidJUnit4::class)
class LocationDeepDiagnosticTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun inspectImportedCopy() = runBlocking {
        val db = AppDatabase.get(context)
        val sb = StringBuilder()
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            db.openHelper.readableDatabase.query(
                "SELECT display_name, stored_name, size_bytes, import_mode, source_uri FROM photos WHERE display_name LIKE '%X12%' LIMIT 3",
            ).use { c ->
                while (c.moveToNext()) {
                    val name = c.getString(0)
                    val stored = c.getString(1)
                    val size = c.getLong(2)
                    val mode = c.getString(3)
                    val src = if (c.isNull(4)) null else c.getString(4)
                    sb.appendLine("row: name=$name stored=$stored size=$size mode=$mode src=$src")
                    val f = if (stored != null) File(context.filesDir, "photos/$stored") else null
                    sb.appendLine("  copy exists=${f?.exists()} len=${f?.length()}")
                    if (f != null && f.exists()) {
                        val located = ExifLocator.open(f)
                        sb.appendLine("  locateBase=${located?.base}")
                        located?.use { loc ->
                            val t = TiffReader(loc.raf, loc.base)
                            val ok = t.parse()
                            sb.appendLine("  parse=$ok ifd0=${t.ifd0.size} exif=${t.exif.size} gps=${t.gps.size} chain=${t.ifdChain.size} sub=${t.subIfds.size}")
                            sb.appendLine("  gpsKeys=${t.gps.keys.sorted()}")
                            val ptr = t.ifd0[0x8825]
                            sb.appendLine("  gpsPointer type=${ptr?.type} count=${ptr?.count} off=${ptr?.valueOffset}")
                            // 打印 GPS 值区原始字节，判断是否真的读到了数据
                            runCatching {
                                val p2 = t.gps[0x0002]!!
                                loc.raf.seek(loc.base + p2.valueOffset)
                                val buf = ByteArray(24)
                                val n = loc.raf.read(buf)
                                sb.appendLine("  raw@(base+${p2.valueOffset}) n=$n hex=" + buf.take(maxOf(n, 0)).joinToString(" ") { b -> "%02X".format(b) })
                            }
                            val e1 = t.gps[0x0001]
                            val e2 = t.gps[0x0002]
                            val e3 = t.gps[0x0003]
                            val e4 = t.gps[0x0004]
                            sb.appendLine("  entry1 type=${e1?.type} count=${e1?.count} off=${e1?.valueOffset}")
                            sb.appendLine("  entry2 type=${e2?.type} count=${e2?.count} off=${e2?.valueOffset}")
                            sb.appendLine("  ascii(1)=[${t.ascii(e1)}] ascii(3)=[${t.ascii(e3)}]")
                            sb.appendLine("  rationals(2)=${t.rationals(e2)?.take(3)} rationals(4)=${t.rationals(e4)?.take(3)}")
                            val e = ExifReader.read(f)
                            sb.appendLine("  ExifReader gps=${e.gpsLat},${e.gpsLon} camera=${e.cameraText}")
                        }
                    }
                }
            }
        }
        android.util.Log.i("YingCeDeep", "\n$sb")
        println(sb)
    }
}
