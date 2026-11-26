package com.yingce.gallery

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yingce.gallery.raw.RawImage
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** 打印解码块的像素统计：判断是"值域是 16bit 被当 8bit 截断"还是"真的全白"。 */
@RunWith(AndroidJUnit4::class)
class RawPixelDiagnosticTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun pixelStats() {
        val dir = File("/sdcard/Android/data/com.yingce.gallery.debug/files/selftest-in")
        val samples = listOf("Apple_iPhone_12_Pro.dng", "Canon_EOS_R5.cr3", "Nikon_D750.nef")
        val sb = StringBuilder()
        for (name in samples) {
            val f = File(dir, name)
            if (!f.exists()) { sb.appendLine("$name missing"); continue }
            val img = RawImage.open(f.absolutePath) ?: run { sb.appendLine("$name open failed"); continue }
            img.use { r ->
                val w = minOf(64, r.width)
                val h = minOf(64, r.height)
                val px = r.decodeRegion(0, 0, w, h, 8)
                if (px == null) { sb.appendLine("$name decode null"); return@use }
                var minR = 255; var maxR = 0; var sum = 0L
                var minA = 255; var maxA = 0
                for (p in px) {
                    val rr = (p shr 16) and 0xFF
                    val aa = (p ushr 24) and 0xFF
                    if (rr < minR) minR = rr
                    if (rr > maxR) maxR = rr
                    if (aa < minA) minA = aa
                    if (aa > maxA) maxA = aa
                    sum += rr
                }
                val avg = if (px.isNotEmpty()) sum / px.size else 0
                sb.appendLine("$name flip=${r.flip} ${r.width}x${r.height} block=${w}x$h R[min=$minR max=$maxR avg=$avg] A[min=$minA max=$maxA] first=${px.take(3).joinToString()}")
            }
        }
        android.util.Log.i("YingCeRawPx", "\n$sb")
        println(sb)
    }
}
