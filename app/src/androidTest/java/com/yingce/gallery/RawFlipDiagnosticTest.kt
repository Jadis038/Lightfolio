package com.yingce.gallery

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yingce.gallery.raw.RawImage
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * 打印每个 RAW 的 LibRaw flip 值、处理后尺寸，用于和 ExifTool 的 Orientation 对照，
 * 从而确定"applyFlip 的映射是否正确"。
 */
@RunWith(AndroidJUnit4::class)
class RawFlipDiagnosticTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun dumpFlip() {
        val dir = File("/sdcard/Android/data/com.yingce.gallery.debug/files/selftest-in")
        val files = dir.listFiles()?.sortedBy { it.name } ?: emptyList()
        val sb = StringBuilder()
        for (f in files) {
            val img = RawImage.open(f.absolutePath)
            if (img == null) {
                sb.appendLine("${f.name}\tOPEN_FAILED")
                continue
            }
            img.use {
                sb.appendLine(
                    "${f.name}\tflip=${it.flip}\tprocessed=${it.width}x${it.height}\traw=${it.rawWidth}x${it.rawHeight}",
                )
            }
        }
        android.util.Log.i("YingCeFlip", "\n$sb")
        println(sb)
    }
}
