package com.yingce.gallery

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yingce.gallery.format.TiffDecoder
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** 用真实 260MB / 16bit 未压缩 TIFF 验证内置解码器。 */
@RunWith(AndroidJUnit4::class)
class TiffDecoderInstrumentedTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun decodesLargeNikonTiff() {
        val dir = File(context.getExternalFilesDir(null), "tiff-test")
        val file = dir.listFiles()?.firstOrNull { it.name.lowercase().endsWith(".tif") || it.name.lowercase().endsWith(".tiff") }
        val sb = StringBuilder()
        if (file == null) {
            sb.appendLine("没有 TIFF 样本: ${dir.absolutePath}")
        } else {
            sb.appendLine("file=${file.name} sizeMB=${file.length() / 1024 / 1024}")
            val t0 = System.currentTimeMillis()
            val bmp = TiffDecoder.decodeScaled(file, 1080)
            val ms = System.currentTimeMillis() - t0
            if (bmp == null) {
                sb.appendLine("decodeScaled -> null（不支持的压缩/布局）")
            } else {
                var minR = 255; var maxR = 0; var sum = 0L
                for (y in 0 until bmp.height step 16) {
                    for (x in 0 until bmp.width step 16) {
                        val p = bmp.getPixel(x, y)
                        val r = (p shr 16) and 0xFF
                        if (r < minR) minR = r
                        if (r > maxR) maxR = r
                        sum += r
                    }
                }
                val n = ((bmp.height + 15) / 16) * ((bmp.width + 15) / 16)
                sb.appendLine("decoded=${bmp.width}x${bmp.height} in ${ms}ms, R[min=$minR max=$maxR avg=${sum / n}]")
                bmp.recycle()
            }
        }
        android.util.Log.i("YingCeTiff", "\n$sb")
        println(sb)
    }
}
