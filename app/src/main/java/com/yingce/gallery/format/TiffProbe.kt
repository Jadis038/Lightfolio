package com.yingce.gallery.format

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 极简 TIFF 预览提取：平台没有 TIFF 解码器，退一步取 TIFF 内嵌的 JPEG 预览
 * （JPEGInterchangeFormat 0x0201 / JPEGInterchangeFormatLength 0x0202）。
 *
 * 只做只读探测，不改文件；失败一律返回 null。
 */
object TiffProbe {

    data class Embedded(val offset: Long, val length: Long, val width: Int?, val height: Int?)

    fun findLargestEmbeddedJpeg(file: File): Embedded? = runCatching {
        RandomAccessFile(file, "r").use { raf ->
            val head = ByteArray(8)
            if (raf.read(head) < 8) return null
            val le = head[0] == 'I'.code.toByte() && head[1] == 'I'.code.toByte()
            val order = if (le) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN
            val magic = readU16(head, 2, order)
            if (magic != 42) return null

            var ifdOffset = readU32(head, 4, order)
            var best: Embedded? = null
            var guard = 0
            while (ifdOffset > 0 && ifdOffset < raf.length() && guard++ < 4) {
                val ifd = readIfd(raf, ifdOffset, order) ?: break
                val off = ifd[0x0201]?.first
                val len = ifd[0x0202]?.first
                if (off != null && len != null && len > 0 && off + len <= raf.length()) {
                    if (best == null || len > best!!.length) {
                        val w = ifd[0x0100]?.first?.toInt()
                        val h = ifd[0x0101]?.first?.toInt()
                        best = Embedded(off, len, w, h)
                    }
                }
                ifdOffset = ifd[-1]?.first ?: 0L // -1 用来携带 next IFD 指针
            }
            best
        }
    }.getOrNull()

    /** 返回 tag -> (值, 类型)；额外塞一个 key=-1 表示下一个 IFD 偏移。 */
    private fun readIfd(raf: RandomAccessFile, offset: Long, order: ByteOrder): Map<Int, Pair<Long, Int>>? {
        if (offset + 2 > raf.length()) return null
        raf.seek(offset)
        val cnt = ByteArray(2)
        if (raf.read(cnt) < 2) return null
        val n = readU16(cnt, 0, order)
        if (n <= 0 || n > 512) return null
        val data = ByteArray(n * 12)
        if (raf.read(data) < data.size) return null

        val out = HashMap<Int, Pair<Long, Int>>(n + 1)
        for (i in 0 until n) {
            val o = i * 12
            val tag = readU16(data, o, order)
            val type = readU16(data, o + 2, order)
            val count = readU32(data, o + 4, order)
            val size = sizeOf(type, count)
            val value: Long = if (size <= 4) {
                if (order == ByteOrder.LITTLE_ENDIAN) readU32(data, o + 8, order)
                else readU32(data, o + 8, order)
            } else {
                readU32(data, o + 8, order)
            }
            out[tag] = value to type
        }
        val next = ByteArray(4)
        if (raf.read(next) == 4) out[-1] = readU32(next, 0, order) to 4
        return out
    }

    private fun sizeOf(type: Int, count: Long): Long = when (type) {
        1, 2, 6, 7 -> count
        3, 8 -> count * 2
        4, 9, 11 -> count * 4
        5, 10, 12 -> count * 8
        else -> 0
    }

    private fun readU16(b: ByteArray, o: Int, order: ByteOrder): Int {
        val v = if (order == ByteOrder.LITTLE_ENDIAN) {
            (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
        } else {
            ((b[o].toInt() and 0xFF) shl 8) or (b[o + 1].toInt() and 0xFF)
        }
        return v
    }

    private fun readU32(b: ByteArray, o: Int, order: ByteOrder): Long {
        val buf = ByteBuffer.wrap(b, o, 4).order(order)
        return buf.int.toLong() and 0xFFFFFFFFL
    }
}
