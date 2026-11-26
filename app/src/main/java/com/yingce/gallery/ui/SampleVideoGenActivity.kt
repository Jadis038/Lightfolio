package com.yingce.gallery.ui

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 生成测试视频（debug 用）。
 *
 * 为什么要它：这台模拟器是无头 swiftshader，`screenrecord` 只录到 1 帧（duration=0），
 * 拿不到有效样本。这里用 MediaCodec 直接编码一段 H.264 SDR MP4，供播放与轨道探测使用。
 *
 * 启动：
 *   adb shell am start -n <pkg>/com.yingce.gallery.ui.SampleVideoGenActivity
 */
class SampleVideoGenActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            YingceTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    GenScreen()
                }
            }
        }
    }
}

@Composable
private fun GenScreen() {
    val context = LocalContext.current
    var lines by remember { mutableStateOf(listOf("生成中…")) }

    LaunchedEffect(Unit) {
        val out = withContext(Dispatchers.IO) {
            val dir = context.getExternalFilesDir(null) ?: context.filesDir
            val target = File(dir, "generated_sdr_h264_720p.mp4")
            runCatching { encode(target) }
                .fold(
                    onSuccess = { listOf("已生成: ${target.absolutePath}", "${target.length() / 1024} KB") },
                    onFailure = { listOf("生成失败: ${it.javaClass.simpleName}: ${it.message}") },
                )
        }
        lines = out
        android.util.Log.i("YingCeGen", out.joinToString("\n"))
    }

    Column(Modifier.fillMaxSize().padding(12.dp).verticalScroll(rememberScrollState())) {
        Text("测试视频生成", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        lines.forEach { Text(it, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace) }
    }
}

private const val W = 1280
private const val H = 720
private const val FPS = 30
private const val SECONDS = 4
private const val BITRATE = 4_000_000

private fun encode(target: File) {
    if (target.exists()) target.delete()
    val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, W, H).apply {
        setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar)
        setInteger(MediaFormat.KEY_BIT_RATE, BITRATE)
        setInteger(MediaFormat.KEY_FRAME_RATE, FPS)
        setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        // 明确标成 BT.709 / SDR，便于我们验证"轨道色彩信息能读出来"
        setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709)
        setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
        setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
    }

    val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
    codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
    codec.start()

    val muxer = MediaMuxer(target.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    var trackIndex = -1
    var muxerStarted = false

    val frameCount = FPS * SECONDS
    val frameBytes = W * H * 3 / 2
    val yuv = ByteArray(frameBytes)
    val info = MediaCodec.BufferInfo()
    var ptsUs = 0L

    try {
        for (frame in 0 until frameCount) {
            // 画一个移动的彩色条
            fillFrame(yuv, frame)
            val inIndex = codec.dequeueInputBuffer(10_000)
            if (inIndex >= 0) {
                val buf = codec.getInputBuffer(inIndex)!!
                buf.clear()
                buf.put(yuv, 0, minOf(yuv.size, buf.capacity()))
                codec.queueInputBuffer(inIndex, 0, minOf(yuv.size, buf.capacity()), ptsUs, 0)
                ptsUs += 1_000_000L / FPS
            }
            // 取输出
            while (true) {
                val outIndex = codec.dequeueOutputBuffer(info, 0)
                if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    if (!muxerStarted) {
                        trackIndex = muxer.addTrack(codec.outputFormat)
                        muxer.start()
                        muxerStarted = true
                    }
                } else if (outIndex >= 0) {
                    val outBuf = codec.getOutputBuffer(outIndex)!!
                    if (info.size > 0 && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && muxerStarted) {
                        outBuf.position(info.offset)
                        outBuf.limit(info.offset + info.size)
                        muxer.writeSampleData(trackIndex, outBuf, info)
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                } else break
            }
        }
        // 收尾：阻塞等一个输入槽，确保 EOS 一定排得进去
        val inIndex = codec.dequeueInputBuffer(500_000)
        android.util.Log.i("YingCeGen", "eos input index=$inIndex")
        if (inIndex >= 0) {
            codec.queueInputBuffer(inIndex, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
        }
        var eos = false
        var guard = 0
        while (!eos && guard++ < 10_000) {
            val outIndex = codec.dequeueOutputBuffer(info, 10_000)
            if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (!muxerStarted) {
                    trackIndex = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                    muxerStarted = true
                }
            } else if (outIndex >= 0) {
                val outBuf = codec.getOutputBuffer(outIndex)!!
                if (info.size > 0 && muxerStarted) {
                    outBuf.position(info.offset)
                    outBuf.limit(info.offset + info.size)
                    muxer.writeSampleData(trackIndex, outBuf, info)
                }
                if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) eos = true
                codec.releaseOutputBuffer(outIndex, false)
            }
        }
    } finally {
        runCatching { codec.stop() }.onFailure { android.util.Log.e("YingCeGen", "codec.stop: $it") }
        runCatching { codec.release() }.onFailure { android.util.Log.e("YingCeGen", "codec.release: $it") }
        if (muxerStarted) {
            runCatching { muxer.stop() }.onFailure { android.util.Log.e("YingCeGen", "muxer.stop: $it") }
        }
        runCatching { muxer.release() }.onFailure { android.util.Log.e("YingCeGen", "muxer.release: $it") }
        android.util.Log.i("YingCeGen", "done size=${target.length()}")
    }
}

/** NV12：Y 平面 + 交错 UV。画三段色条并在时间轴上平移。 */
private fun fillFrame(yuv: ByteArray, frame: Int) {
    val shift = (frame * 8) % W
    for (y in 0 until H) {
        for (x in 0 until W) {
            val band = ((x + shift) / (W / 3)) % 3
            val luma = when (band) {
                0 -> 90
                1 -> 150
                else -> 210
            }
            yuv[y * W + x] = luma.toByte()
        }
    }
    val uvOffset = W * H
    for (y in 0 until H / 2) {
        for (x in 0 until W / 2) {
            val band = ((x * 2 + shift) / (W / 3)) % 3
            val u = when (band) { 0 -> 90; 1 -> 128; else -> 160 }
            val v = when (band) { 0 -> 160; 1 -> 128; else -> 90 }
            val i = uvOffset + (y * W / 2 + x) * 2
            if (i + 1 < yuv.size) {
                yuv[i] = u.toByte()
                yuv[i + 1] = v.toByte()
            }
        }
    }
}
