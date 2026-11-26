package com.yingce.gallery.data

import java.security.MessageDigest

/**
 * 去重指纹：(总字节数, 首 64KB 的 SHA-256, 尾 64KB 的 SHA-256)。
 *
 * 为什么不用整文件哈希：5000 万像素 RAW 有 50MB，整文件 SHA-256 会明显拖慢导入；
 * 首尾各 64KB + 精确大小，对"同一源文件"的判定已经足够可靠。
 *
 * 单趟读取：边读边喂，不需要把文件读两遍。
 */
class HashTap(private val windowBytes: Int = 64 * 1024) {

    private val headDigest = MessageDigest.getInstance("SHA-256")
    private var headFilled = 0

    private val tail = ByteArray(windowBytes)
    private var tailPos = 0
    private var tailFilled = 0

    var totalBytes: Long = 0
        private set

    fun write(buf: ByteArray, off: Int, len: Int) {
        if (len <= 0) return
        totalBytes += len

        // 头
        if (headFilled < windowBytes) {
            val take = minOf(len, windowBytes - headFilled)
            headDigest.update(buf, off, take)
            headFilled += take
        }
        // 尾（环形缓冲）
        var remaining = len
        var cursor = off
        while (remaining > 0) {
            val chunk = minOf(remaining, windowBytes - tailPos)
            System.arraycopy(buf, cursor, tail, tailPos, chunk)
            tailPos = (tailPos + chunk) % windowBytes
            tailFilled = minOf(windowBytes, tailFilled + chunk)
            cursor += chunk
            remaining -= chunk
        }
    }

    /** 返回 "size:headHex:tailHex" */
    fun fingerprint(): String {
        val head = hex(headDigest.digest())

        // 尾：从头（最旧）开始按环形顺序取出
        val ordered = ByteArray(tailFilled)
        if (tailFilled < windowBytes) {
            System.arraycopy(tail, 0, ordered, 0, tailFilled)
        } else {
            System.arraycopy(tail, tailPos, ordered, 0, windowBytes - tailPos)
            System.arraycopy(tail, 0, ordered, windowBytes - tailPos, tailPos)
        }
        val tailHex = hex(MessageDigest.getInstance("SHA-256").digest(ordered))
        return "$totalBytes:$head:$tailHex"
    }

    private fun hex(bytes: ByteArray): String = buildString(bytes.size * 2) {
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            append(HEX[v ushr 4])
            append(HEX[v and 0x0F])
        }
    }

    companion object {
        private val HEX = "0123456789abcdef".toCharArray()
    }
}
