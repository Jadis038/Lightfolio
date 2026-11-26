package com.yingce.gallery

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yingce.gallery.data.MediaStoreSource
import com.yingce.gallery.data.MediaTypes
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 验证"视频能从相册查询到"。
 * 前置：设备上至少有一个视频（/sdcard/Movies 下推一个 mp4 并扫描）。
 */
@RunWith(AndroidJUnit4::class)
class VideoQueryInstrumentedTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun queryVideosReturnsAtLeastOneVideo() {
        val videos = MediaStoreSource.queryVideos(context)
        android.util.Log.i("YingCeVidQ", "queryVideos -> ${videos.size} 条: ${videos.take(5).map { it.displayName }}")
        assertTrue("相册视频查询不应为空（先推一个 mp4 到 /sdcard/Movies 并扫描）", videos.isNotEmpty())
        videos.forEach {
            assertTrue("被判为视频: ${it.displayName}", MediaTypes.isVideoWork(it.displayName, it.mimeType))
        }
    }

    @Test
    fun imagesQueryStillWorks() {
        val images = MediaStoreSource.queryImages(context)
        assertTrue("图片查询不应为空", images.isNotEmpty())
    }
}
