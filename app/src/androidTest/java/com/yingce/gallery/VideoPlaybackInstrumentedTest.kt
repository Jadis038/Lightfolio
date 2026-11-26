package com.yingce.gallery

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * M6 播放通路验证：用 Media3 打开一段真实 MP4，等它进入 READY 并真的播到 >0ms。
 *
 * 样本由 SampleVideoGenActivity 生成（H.264 SDR 720p）。
 * 用 instrumented 测试而不是点 UI，是为了在无头模拟器上稳定复现。
 */
@RunWith(AndroidJUnit4::class)
class VideoPlaybackInstrumentedTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun sample(): File {
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        return File(dir, "generated_sdr_h264_720p.mp4")
    }

    @Test
    fun playsGeneratedMp4AndAdvancesPosition() {
        val file = sample()
        assertTrue("样本不存在，请先运行 SampleVideoGenActivity: ${file.absolutePath}", file.exists())

        val instrumentation = InstrumentationRegistry.getInstrumentation()
        var player: ExoPlayer? = null
        var readyState = Player.STATE_IDLE
        var positionAfter = 0L
        var duration = 0L
        var error: String? = null

        // 播放器必须在 main 线程创建/访问，但等待不能阻塞 main（否则播放器无法推进）
        instrumentation.runOnMainSync {
            player = ExoPlayer.Builder(context).build().apply {
                setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
                playWhenReady = true
                prepare()
            }
        }

        val deadline = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < deadline) {
            instrumentation.runOnMainSync {
                val p = player ?: return@runOnMainSync
                readyState = p.playbackState
                positionAfter = p.currentPosition
                duration = if (p.duration > 0) p.duration else 0
                p.playerError?.let { error = it.message }
            }
            if (error != null) break
            if (readyState == Player.STATE_READY && positionAfter > 300) break
            Thread.sleep(100)
        }

        instrumentation.runOnMainSync { player?.release() }
        assertTrue("播放器报错: $error", error == null)
        assertTrue("应进入 READY，实际 state=$readyState", readyState == Player.STATE_READY)
        assertTrue("时长应 > 0，实际 $duration", duration > 0)
        assertTrue("播放位置应前进，实际 ${positionAfter}ms", positionAfter > 0)
        android.util.Log.i("YingCeVideo", "READY duration=${duration}ms position=${positionAfter}ms")
        println("PLAY OK: duration=${duration}ms position=${positionAfter}ms")
    }
}
