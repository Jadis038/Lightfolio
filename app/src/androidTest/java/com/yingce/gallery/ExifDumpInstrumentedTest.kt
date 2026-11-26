package com.yingce.gallery

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yingce.gallery.data.AppDatabase
import com.yingce.gallery.data.Photo
import com.yingce.gallery.exif.ExifReader
import com.yingce.gallery.exif.PhotoExif
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * 把外部 samples 目录里的样例拷进作品目录，逐个解析 EXIF，导出 TSV 供主机侧与 ExifTool 比对，
 * 同时登记进作品库以便界面截图。
 */
@RunWith(AndroidJUnit4::class)
class ExifDumpInstrumentedTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun dumpExifForSamples() = runBlocking {
        val photosDir = File(context.filesDir, "photos").apply { mkdirs() }
        // 样例由 adb 侧推入 files/photos，这里只负责解析与导出
        val files = photosDir.listFiles()
            ?.filter { it.isFile && !it.name.startsWith(".") && it.name != "t.txt" }
            ?.sortedBy { it.name } ?: emptyList()
        assertTrue("photos 目录里应有样例，实际 ${files.size}", files.size >= 5)

        val sb = StringBuilder()
        sb.append("file\taperture\tshutter\tiso\tfocal35\tfocal\twb\tprogram\tbias\tmetering\twidth\theight\tmp\tmake\tmodel\tlens\ttakenAt\tformat\tsize\n")

        files.forEach { f ->
            val e = try {
                ExifReader.read(f)
            } catch (t: Throwable) {
                PhotoExif(fileName = f.name, formatLabel = "PARSE_ERROR:${t.javaClass.simpleName}", sizeBytes = f.length())
            }
            sb.append(
                listOf(
                    e.fileName, e.apertureText, e.shutterText, e.isoText, e.focal35Text, e.focalText,
                    e.whiteBalanceText, e.exposureProgramText, e.exposureBiasText, e.meteringModeText,
                    e.width?.toString() ?: "", e.height?.toString() ?: "",
                    e.megapixels?.let { String.format(java.util.Locale.US, "%.2f", it) } ?: "",
                    e.make ?: "", e.model ?: "", e.lens ?: "", e.takenAt ?: "",
                    e.formatLabel, e.sizeBytes.toString(),
                ).joinToString("\t") + "\n"
            )
        }

        val out = File(context.getExternalFilesDir(null), "exif_dump.tsv")
        out.writeText(sb.toString())
        android.util.Log.i("ExifDump", "tsv=${out.absolutePath} rows=${files.size}")

        // 附加：分辨率候选值明细（IFD0 / ExifIFD / 各 SubIFD），用于解释与 ExifTool 的差异
        val dbg = StringBuilder()
        dbg.append("file\tifd0_exif\tifd0_dim\tsubifd_dims\tchosen\n")
        files.forEach { f ->
            val loc = com.yingce.gallery.exif.ExifLocator.open(f)
            if (loc == null) {
                dbg.append("${f.name}\tNO_TIFF\t\t\t\n")
                return@forEach
            }
            loc.use {
                val t = com.yingce.gallery.exif.TiffReader(loc.raf, loc.base)
                t.parse()
                fun dims(m: Map<Int, com.yingce.gallery.exif.IfdEntry>): String {
                    val w = t.firstLong(m[0x0100]); val h = t.firstLong(m[0x0101])
                    return if (w != null && h != null) "${w}x$h" else "-"
                }
                val exifDim = "0xA002=${t.firstLong(t.exif[0xA002]) ?: "-"}x${t.firstLong(t.exif[0xA003]) ?: "-"}"
                val ifd0Dim = dims(t.ifd0)
                val subs = t.subIfds.joinToString("|") { dims(it) }.ifBlank { "-" }
                val chosen = ExifReader.read(f)
                dbg.append("${f.name}\t$exifDim\t$ifd0Dim\t$subs\t${chosen.width}x${chosen.height}\n")
            }
        }
        File(context.getExternalFilesDir(null), "dim_debug.tsv").writeText(dbg.toString())
        assertTrue("TSV 应非空", out.length() > 0)

        val dao = AppDatabase.get(context).photoDao()
        dao.deleteAll()
        files.forEach { f ->
            dao.insert(
                Photo(
                    displayName = f.name,
                    storedName = f.name,
                    sizeBytes = f.length(),
                    mimeType = null,
                    importedAt = f.lastModified(),
                )
            )
        }
        assertTrue("作品数应等于文件数", dao.count() == files.size)
    }
}
