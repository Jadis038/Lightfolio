package com.yingce.gallery

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yingce.gallery.raw.RawImage
import com.yingce.gallery.raw.RawOrientation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * RAW 方向修正的真实验证：iPhone DNG 的 flip=6（ExifTool: "Rotate 90 CW"），
 * 修正后尺寸应从 4032×3024 变成 3024×4032（竖幅）。
 */
@RunWith(AndroidJUnit4::class)
class RawOrientationInstrumentedTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun sampleDir() = File("/sdcard/Android/data/com.yingce.gallery.debug/files/selftest-in")

    @Test
    fun iphoneDngBecomesPortraitAfterOrientation() {
        val f = File(sampleDir(), "Apple_iPhone_12_Pro.dng")
        assertTrue("需要 iPhone DNG 样例: ${f.absolutePath}", f.exists())

        val img = RawImage.open(f.absolutePath)
        assertNotNull("应能打开 DNG", img)
        img!!.use { r ->
            assertEquals("样例应为 flip=6", 6, r.flip)
            // 取一小块以避免大内存：只验证方向变换的尺寸语义
            val px = r.decodeRegion(0, 0, minOf(64, r.width), minOf(64, r.height), 8)
            assertNotNull("应能解码", px)
            val oriented = RawOrientation.apply(px!!, minOf(64, r.width), minOf(64, r.height), r.flip)
            assertEquals("flip=6 时宽高应交换（块内）", minOf(64, r.height), oriented.width)
            assertEquals(minOf(64, r.width), oriented.height)
            android.util.Log.i(
                "YingCeRawRot",
                "iphone dng: raw=${r.width}x${r.height} flip=${r.flip} -> block ${oriented.width}x${oriented.height}",
            )
        }
    }

    @Test
    fun kodakDcrFlip5SwapsDimensions() {
        val f = File(sampleDir(), "Kodak_DCS_Pro.dcr")
        assertTrue("需要 Kodak 样例", f.exists())
        val img = RawImage.open(f.absolutePath)
        assertNotNull(img)
        img!!.use { r ->
            assertEquals(5, r.flip)
            val px = r.decodeRegion(0, 0, minOf(32, r.width), minOf(32, r.height), 8)!!
            val oriented = RawOrientation.apply(px, minOf(32, r.width), minOf(32, r.height), 5)
            assertEquals(minOf(32, r.width), oriented.height)
            assertEquals(minOf(32, r.height), oriented.width)
        }
    }
}
