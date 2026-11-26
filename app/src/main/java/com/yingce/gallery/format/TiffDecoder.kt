package com.yingce.gallery.format

import android.graphics.Bitmap
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.Inflater

/**
 * 最小 TIFF 像素解码器（只读查看用）。
 *
 * 支持：未压缩 / PackBits / Deflate；RGB、灰度、白为零；8bit 与 16bit；Chunky。
 * 不支持：LZW(5)、Planar(2)、YCbCr 光度 —— 这些情况返回 null，界面会明确提示"解码器不支持"。
 *
 * 内存策略：未压缩时**只按需读需要的行**（相机导出的 TIFF 常常是 200MB+ 单条 strip，
 * 不能整条读进内存）；压缩格式要求单条 strip 不超过 64MB 才处理。
 */
object TiffDecoder {

    private const val MAX_STRIP_BYTES = 64L * 1024 * 1024

    private data class Tags(
        val width: Int,
        val height: Int,
        val bitsPerSample: Int,
        val compression: Int,
        val photometric: Int,
        val samplesPerPixel: Int,
        val rowsPerStrip: Int,
        val planarConfig: Int,
        val stripOffsets: LongArray,
        val stripByteCounts: LongArray,
        val little: Boolean,
    ) {
        val bytesPerSample: Int get() = if (bitsPerSample > 8) 2 else 1
        val rowBytes: Int get() = width * samplesPerPixel * bytesPerSample
    }

    fun decodeScaled(file: File, targetWidth: Int): Bitmap? = runCatching {
        RandomAccessFile(file, "r").use { raf ->
            val t = readTags(raf) ?: return null
            if (t.compression == 5 || t.planarConfig == 2 || t.photometric == 6) return null

            val scale = if (targetWidth > 0 && t.width > targetWidth) t.width.toDouble() / targetWidth else 1.0
            val outW = (t.width / scale).toInt().coerceAtLeast(1)
            val outH = (t.height / scale).toInt().coerceAtLeast(1)
            val pixels = IntArray(outW * outH)

            // 压缩格式：逐条 strip 解码并缓存
            var cachedStrip = -1
            var cachedData: ByteArray? = null

            for (oy in 0 until outH) {
                val sy = (oy * scale).toInt().coerceIn(0, t.height - 1)
                val stripIndex = (sy / t.rowsPerStrip).coerceIn(0, t.stripOffsets.lastIndex)
                val stripRow = sy - stripIndex * t.rowsPerStrip

                val rowBytes: ByteArray?
                if (t.compression == 1) {
                    val offset = t.stripOffsets[stripIndex] + stripRow.toLong() * t.rowBytes
                    rowBytes = readAt(raf, offset, t.rowBytes)
                } else {
                    if (cachedStrip != stripIndex) {
                        cachedStrip = stripIndex
                        cachedData = decodeStrip(raf, t, stripIndex)
                    }
                    val data = cachedData
                    rowBytes = if (data != null) {
                        val start = stripRow * t.rowBytes
                        if (start + t.rowBytes <= data.size) data.copyOfRange(start, start + t.rowBytes) else null
                    } else null
                }
                if (rowBytes == null) continue

                for (ox in 0 until outW) {
                    val sx = (ox * scale).toInt().coerceIn(0, t.width - 1)
                    val o = sx * t.samplesPerPixel * t.bytesPerSample
                    val (r, g, b) = toRgb(rowBytes, o, t)
                    pixels[oy * outW + ox] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                }
            }
            Bitmap.createBitmap(pixels, outW, outH, Bitmap.Config.ARGB_8888)
        }
    }.getOrNull()

    private fun decodeStrip(raf: RandomAccessFile, t: Tags, stripIndex: Int): ByteArray? {
        val off = t.stripOffsets.getOrNull(stripIndex) ?: return null
        val len = t.stripByteCounts.getOrNull(stripIndex) ?: 0L
        if (len <= 0 || len > MAX_STRIP_BYTES) return null
        val raw = readAt(raf, off, len.toInt()) ?: return null
        val rows = minOf(t.rowsPerStrip, t.height - stripIndex * t.rowsPerStrip)
        val outLen = rows * t.rowBytes
        return when (t.compression) {
            32773 -> packBits(raw, outLen)
            8 -> inflate(raw, outLen)
            else -> null
        }
    }

    private fun readAt(raf: RandomAccessFile, offset: Long, len: Int): ByteArray? {
        if (len <= 0 || offset < 0 || offset + len > raf.length()) return null
        val buf = ByteArray(len)
        raf.seek(offset)
        return if (raf.read(buf) == len) buf else null
    }

    private fun packBits(src: ByteArray, outLen: Int): ByteArray? {
        val out = ByteArray(outLen)
        var i = 0
        var o = 0
        while (i < src.size && o < outLen) {
            val n = src[i++].toInt()
            if (n >= 0) {
                val count = n + 1
                for (k in 0 until count) {
                    if (i >= src.size || o >= outLen) break
                    out[o++] = src[i++]
                }
            } else if (n != -128) {
                val count = 1 - n
                if (i >= src.size) break
                val b = src[i++]
                for (k in 0 until count) {
                    if (o >= outLen) break
                    out[o++] = b
                }
            }
        }
        return if (o > 0) out else null
    }

    private fun inflate(src: ByteArray, outLen: Int): ByteArray? = runCatching {
        val inflater = Inflater()
        inflater.setInput(src)
        val out = ByteArray(outLen)
        var written = 0
        while (written < outLen && !inflater.finished()) {
            val n = inflater.inflate(out, written, outLen - written)
            if (n <= 0) break
            written += n
        }
        inflater.end()
        if (written > 0) out else null
    }.getOrNull()

    private fun toRgb(row: ByteArray, o: Int, t: Tags): Triple<Int, Int, Int> {
        fun sample(pos: Int): Int = if (t.bytesPerSample == 2) {
            if (pos + 1 >= row.size) 0
            else {
                val v = if (t.little) {
                    ((row[pos + 1].toInt() and 0xFF) shl 8) or (row[pos].toInt() and 0xFF)
                } else {
                    ((row[pos].toInt() and 0xFF) shl 8) or (row[pos + 1].toInt() and 0xFF)
                }
                v shr 8
            }
        } else {
            if (pos >= row.size) 0 else row[pos].toInt() and 0xFF
        }
        val step = t.bytesPerSample
        return if (t.photometric == 2 && t.samplesPerPixel >= 3) {
            Triple(sample(o), sample(o + step), sample(o + 2 * step))
        } else {
            val g = sample(o)
            val v = if (t.photometric == 0) 255 - g else g
            Triple(v, v, v)
        }
    }

    private fun readTags(raf: RandomAccessFile): Tags? {
        val head = readAt(raf, 0, 8) ?: return null
        val little = head[0] == 0x49.toByte()
        val order = if (little) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN
        if (readU16(head, 2, order) != 42) return null
        val ifdOffset = readU32(head, 4, order)
        val cnt = readAt(raf, ifdOffset, 2) ?: return null
        val n = readU16(cnt, 0, order)
        if (n <= 0 || n > 2000) return null
        val data = readAt(raf, ifdOffset + 2, n * 12) ?: return null

        val values = HashMap<Int, LongArray>()
        for (i in 0 until n) {
            val o = i * 12
            val tag = readU16(data, o, order)
            val type = readU16(data, o + 2, order)
            val count = readU32(data, o + 4, order)
            val sizePer = when (type) { 1, 2, 6, 7 -> 1; 3, 8 -> 2; 4, 9 -> 4; 5, 10 -> 8; else -> 1 }
            val total = count * sizePer
            if (total > 4 * 1024 * 1024) continue
            val buf = if (total <= 4) {
                data.copyOfRange(o + 8, o + 12)
            } else {
                val off = readU32(data, o + 8, order)
                readAt(raf, off, total.toInt()) ?: continue
            }
            values[tag] = parseLongs(buf, type, count.toInt(), order)
        }

        val width = values[256]?.firstOrNull()?.toInt() ?: return null
        val height = values[257]?.firstOrNull()?.toInt() ?: return null
        if (width <= 0 || height <= 0) return null
        val bits = values[258]?.firstOrNull()?.toInt() ?: 8
        val compression = values[259]?.firstOrNull()?.toInt() ?: 1
        val photometric = values[262]?.firstOrNull()?.toInt() ?: 2
        val spp = values[277]?.firstOrNull()?.toInt() ?: 3
        val rowsPerStrip = (values[278]?.firstOrNull()?.toInt() ?: height).coerceAtLeast(1)
        val planar = values[284]?.firstOrNull()?.toInt() ?: 1
        val offsets = values[273] ?: return null
        val counts = values[279] ?: LongArray(0)
        return Tags(width, height, bits, compression, photometric, spp, rowsPerStrip, planar, offsets, counts, little)
    }

    private fun parseLongs(b: ByteArray, type: Int, count: Int, order: ByteOrder): LongArray {
        val out = LongArray(count)
        for (i in 0 until count) {
            out[i] = when (type) {
                1, 6, 7 -> (b.getOrNull(i)?.toLong() ?: 0L) and 0xFF
                3 -> readU16(b, i * 2, order).toLong()
                4, 9 -> readU32(b, i * 4, order)
                5, 10 -> {
                    val num = readU32(b, i * 8, order)
                    val den = readU32(b, i * 8 + 4, order)
                    if (den == 0L) 0L else num / den
                }
                else -> 0L
            }
        }
        return out
    }

    private fun readU16(b: ByteArray, o: Int, order: ByteOrder): Int {
        if (o + 1 >= b.size) return 0
        return if (order == ByteOrder.LITTLE_ENDIAN) {
            (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
        } else {
            ((b[o].toInt() and 0xFF) shl 8) or (b[o + 1].toInt() and 0xFF)
        }
    }

    private fun readU32(b: ByteArray, o: Int, order: ByteOrder): Long {
        if (o + 3 >= b.size) return 0
        return ByteBuffer.wrap(b, o, 4).order(order).int.toLong() and 0xFFFFFFFFL
    }
}
