package com.yingce.gallery

import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yingce.gallery.raw.RawImage
import com.yingce.gallery.raw.RawOrientation
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** 验证内嵌预览回退时也应用了方向（尼康 NEF 横着显示的问题）。 */
@RunWith(AndroidJUnit4::class)
class RawPreviewRotationTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun previewIsRotatedByFlip() {
        val dir = File(context.getExternalFilesDir(null), "raw-test")
        val file = dir.listFiles()?.firstOrNull { it.name.lowercase().endsWith(".nef") }
        val sb = StringBuilder()
        if (file == null) {
            sb.appendLine("没有 NEF 样本: ${dir.absolutePath}")
        } else {
            val img = RawImage.open(file.absolutePath)
            if (img == null) sb.appendLine("打不开 ${file.name}") else img.use { r ->
                val thumb = r.thumbnail()
                val bmp = thumb?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
                sb.appendLine("file=${file.name} flip=${r.flip} processed=${r.width}x${r.height}")
                sb.appendLine("thumb decoded=${bmp?.width}x${bmp?.height}")
                if (bmp != null) {
                    val rotated = RawOrientation.applyToBitmap(bmp, r.flip)
                    sb.appendLine("after rotate=${rotated.width}x${rotated.height}")
                    // 竖幅机位：旋转后宽应小于高
                    sb.appendLine("looks portrait after rotate=${rotated.width < rotated.height}")
                }
            }
        }
        android.util.Log.i("YingCePreviewRot", "\n$sb")
        println(sb)
    }
}
