package com.yingce.gallery.exif

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 极简 TIFF/IFD 解析器 + 容器定位器。
 *
 * 为什么不用 androidx.exifinterface：它只认 JPEG / PNG / WebP / HEIF / DNG，
 * 对 CR2 / CR3 / NEF / ARW / RAF / ORF / RW2 / PEF / SRW / 3FR 这些 RAW 容器无能为力。
 * 本 M2 只读元数据，不做像素解码，因此用"找到容器里的 TIFF 块 → 按 IFD 解析"这条通用路径。
 */
private const val T_BYTE = 1
private const val T_ASCII = 2
private const val T_SHORT = 3
private const val T_LONG = 4
private const val T_RATIONAL = 5
private const val T_SBYTE = 6
private const val T_UNDEFINED = 7
private const val T_SSHORT = 8
private const val T_SLONG = 9
private const val T_SRATIONAL = 10
private const val T_FLOAT = 11
private const val T_DOUBLE = 12

class IfdEntry(
    val tag: Int,
    val type: Int,
    val count: Int,
    /** 4 字节值域原始字节（小端/大端由 TIFF 头决定） */
    val inlineBytes: ByteArray,
    /** 当数据超过 4 字节时，指向文件内的相对偏移 */
    val valueOffset: Long,
)

class TiffReader(
    private val raf: RandomAccessFile,
    private val base: Long,
) {
    var order: ByteOrder = ByteOrder.LITTLE_ENDIAN
        private set

    val ifd0 = LinkedHashMap<Int, IfdEntry>()
    val exif = LinkedHashMap<Int, IfdEntry>()
    val gps = LinkedHashMap<Int, IfdEntry>()
    val interop = LinkedHashMap<Int, IfdEntry>()
    /** SubIFDs（0x014A）：RAW 文件里主图往往在这里，IFD0 只有缩略图。 */
    val subIfds = mutableListOf<LinkedHashMap<Int, IfdEntry>>()
    /** IFD 链上 IFD0 之后的各节（CR3 等容器把 ExifIFD 放在 next-IFD 链里）。 */
    val ifdChain = mutableListOf<LinkedHashMap<Int, IfdEntry>>()

    private val fileLen = raf.length()

    fun parse(): Boolean {
        val head = readAt(base, 8) ?: return false
        val ii = head[0] == 0x49.toByte() && head[1] == 0x49.toByte()
        val mm = head[0] == 0x4D.toByte() && head[1] == 0x4D.toByte()
        if (!ii && !mm) return false
        order = if (ii) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN

        // 标准 TIFF 是 42；ORF 用 0x4F52("RO")，RW2 用 0x55
        val magic = u16(head, 2)
        if (magic != 42 && magic != 0x4F52 && magic != 0x55) return false

        val firstIfd = u32(head, 4)
        if (firstIfd <= 0L || firstIfd >= fileLen - base) return false

        readIfd(firstIfd, ifd0)

        // 跟随 IFD 链：CR3 等容器把 ExifIFD 挂在 IFD0 的 next-IFD 指针上，而不是 0x8769
        var next = nextIfdOf(firstIfd)
        var guard = 0
        while (next != null && next > 0 && next < fileLen - base && guard < 8) {
            val m = LinkedHashMap<Int, IfdEntry>()
            readIfd(next, m)
            if (m.isNotEmpty()) ifdChain += m
            next = nextIfdOf(next)
            guard++
        }

        // 注意：ExifIFD / GPS / Interop 的指针是"内联 LONG"（4 字节值域里直接存子 IFD 偏移），
        // 不能当成"超过 4 字节才有的 offset"，否则整块 EXIF 会读空。
        firstLong(ifd0[TAG_EXIF_POINTER])?.let { readIfd(it, exif) }
        firstLong(ifd0[TAG_GPS_POINTER])?.let { readIfd(it, gps) }
        firstLong(exif[TAG_INTEROP_POINTER])?.let { readIfd(it, interop) }

        // SubIFDs：RAW 的主图尺寸通常在这里
        longs(ifd0[TAG_SUB_IFDS])?.forEach { off ->
            if (off > 0 && off < fileLen - base) {
                val m = LinkedHashMap<Int, IfdEntry>()
                readIfd(off, m)
                if (m.isNotEmpty()) subIfds += m
            }
        }
        return ifd0.isNotEmpty()
    }

    private fun readIfd(offset: Long, into: MutableMap<Int, IfdEntry>) {
        val cntBytes = readAt(base + offset, 2) ?: return
        val n = u16(cntBytes, 0)
        if (n <= 0 || n > 4096) return
        val data = readAt(base + offset + 2, n * 12) ?: return
        for (i in 0 until n) {
            val o = i * 12
            val tag = u16(data, o)
            val type = u16(data, o + 2)
            val count = u32(data, o + 4).toInt()
            if (count < 0) continue
            val inline = data.copyOfRange(o + 8, o + 12)
            val size = valueSize(type, count)
            val entry = IfdEntry(tag, type, count, inline, if (size <= 4) 0L else u32(data, o + 8))
            if (size <= 4 || entry.valueOffset > 0) into[tag] = entry
        }
    }

    /** IFD 条目区之后的 4 字节：指向下一个 IFD 的相对偏移。 */
    private fun nextIfdOf(offset: Long): Long? {
        val cntBytes = readAt(base + offset, 2) ?: return null
        val n = u16(cntBytes, 0)
        if (n <= 0 || n > 4096) return null
        val b = readAt(base + offset + 2 + n * 12L, 4) ?: return null
        return u32(b, 0)
    }

    private fun valueSize(type: Int, count: Int): Long = when (type) {
        T_BYTE, T_ASCII, T_SBYTE, T_UNDEFINED -> count.toLong()
        T_SHORT, T_SSHORT -> count * 2L
        T_LONG, T_SLONG, T_FLOAT -> count * 4L
        T_RATIONAL, T_SRATIONAL, T_DOUBLE -> count * 8L
        else -> 0L
    }

    private fun payload(e: IfdEntry): ByteArray? {
        val size = valueSize(e.type, e.count).toInt()
        if (size <= 0) return null
        return if (size <= 4) e.inlineBytes.copyOf(size) else readAt(base + e.valueOffset, size)
    }

    fun ascii(e: IfdEntry?): String? {
        if (e == null) return null
        val b = payload(e) ?: return null
        return b.toString(Charsets.ISO_8859_1).trimEnd('\u0000').trim().ifBlank { null }
    }

    fun longs(e: IfdEntry?): LongArray? {
        if (e == null) return null
        val b = payload(e) ?: return null
        val bb = ByteBuffer.wrap(b).order(order)
        return when (e.type) {
            T_BYTE, T_UNDEFINED -> LongArray(b.size) { (b[it].toLong() and 0xFF) }
            T_SBYTE -> LongArray(b.size) { b[it].toLong() }
            T_SHORT -> LongArray(b.size / 2) { (bb.getShort(it * 2).toLong() and 0xFFFF) }
            T_SSHORT -> LongArray(b.size / 2) { bb.getShort(it * 2).toLong() }
            T_LONG -> LongArray(b.size / 4) { (bb.getInt(it * 4).toLong() and 0xFFFFFFFFL) }
            T_SLONG -> LongArray(b.size / 4) { bb.getInt(it * 4).toLong() }
            else -> null
        }
    }

    fun firstLong(e: IfdEntry?): Long? = longs(e)?.firstOrNull()

    fun rationals(e: IfdEntry?): List<Pair<Double, Double>>? {
        if (e == null) return null
        val b = payload(e) ?: return null
        val bb = ByteBuffer.wrap(b).order(order)
        return when (e.type) {
            T_RATIONAL -> (0 until b.size / 8).map {
                (bb.getInt(it * 8).toLong() and 0xFFFFFFFFL).toDouble() to
                    (bb.getInt(it * 8 + 4).toLong() and 0xFFFFFFFFL).toDouble()
            }
            T_SRATIONAL -> (0 until b.size / 8).map {
                bb.getInt(it * 8).toDouble() to bb.getInt(it * 8 + 4).toDouble()
            }
            else -> null
        }
    }

    fun firstRational(e: IfdEntry?): Pair<Double, Double>? = rationals(e)?.firstOrNull()

    fun firstDouble(e: IfdEntry?): Double? {
        if (e == null) return null
        val r = firstRational(e)
        if (r != null) return if (r.second == 0.0) null else r.first / r.second
        val b = payload(e) ?: return null
        val bb = ByteBuffer.wrap(b).order(order)
        return when (e.type) {
            T_DOUBLE -> if (b.size >= 8) bb.getDouble(0) else null
            T_FLOAT -> if (b.size >= 4) bb.getFloat(0).toDouble() else null
            else -> firstLong(e)?.toDouble()
        }
    }

    fun bytes(e: IfdEntry?): ByteArray? = if (e == null) null else payload(e)

    val baseOffset: Long get() = base

    /**
     * 把另一段 TIFF 块里的 IFD 合并进来（CR3 等把 ExifIFD 放在同容器内的另一个 TIFF 块）。
     * 注意：条目里的 valueOffset 是相对"它自己那块 TIFF 头"的，必须按差值重定位，
     * 否则会用主块基址去取值，读出一堆垃圾（曾把 f/4 读成 f/1843）。
     */
    fun attachIfd(other: TiffReader) {
        val delta = other.baseOffset - base
        fun rebase(m: Map<Int, IfdEntry>): LinkedHashMap<Int, IfdEntry> {
            val out = LinkedHashMap<Int, IfdEntry>(m.size)
            m.forEach { (k, e) ->
                out[k] = if (valueSize(e.type, e.count) <= 4) e
                else IfdEntry(e.tag, e.type, e.count, e.inlineBytes, e.valueOffset + delta)
            }
            return out
        }
        if (other.ifd0.isNotEmpty()) ifdChain += rebase(other.ifd0)
        if (other.exif.isNotEmpty()) ifdChain += rebase(other.exif)
    }

    private fun readAt(pos: Long, len: Int): ByteArray? {
        if (pos < 0 || len <= 0 || pos + len > fileLen) return null
        val buf = ByteArray(len)
        return try {
            raf.seek(pos)
            var read = 0
            while (read < len) {
                val n = raf.read(buf, read, len - read)
                if (n <= 0) return null
                read += n
            }
            buf
        } catch (_: Exception) {
            null
        }
    }

    private fun u16(b: ByteArray, o: Int): Int {
        val v = if (order == ByteOrder.LITTLE_ENDIAN) {
            (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
        } else {
            ((b[o].toInt() and 0xFF) shl 8) or (b[o + 1].toInt() and 0xFF)
        }
        return v
    }

    private fun u32(b: ByteArray, o: Int): Long {
        val v = if (order == ByteOrder.LITTLE_ENDIAN) {
            (b[o].toLong() and 0xFF) or
                ((b[o + 1].toLong() and 0xFF) shl 8) or
                ((b[o + 2].toLong() and 0xFF) shl 16) or
                ((b[o + 3].toLong() and 0xFF) shl 24)
        } else {
            ((b[o].toLong() and 0xFF) shl 24) or
                ((b[o + 1].toLong() and 0xFF) shl 16) or
                ((b[o + 2].toLong() and 0xFF) shl 8) or
                (b[o + 3].toLong() and 0xFF)
        }
        return v
    }

    companion object {
        const val TAG_EXIF_POINTER = 0x8769
        const val TAG_GPS_POINTER = 0x8825
        const val TAG_INTEROP_POINTER = 0xA005
        const val TAG_SUB_IFDS = 0x014A
    }
}

/** 文件级尺寸：JPEG 从 SOF 段读、PNG 从 IHDR 读（这两类的 EXIF 里常常没有像素尺寸）。 */
object RasterSize {

    fun read(file: File): Pair<Int, Int>? {
        return try {
            RandomAccessFile(file, "r").use { raf ->
                val head = ByteArray(8)
                if (raf.read(head) < 8) return null
                when {
                    head[0].toInt() and 0xFF == 0xFF && head[1].toInt() and 0xFF == 0xD8 -> jpeg(raf)
                    head[0] == 0x89.toByte() && head[1] == 'P'.code.toByte() -> png(raf)
                    else -> null
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun jpeg(raf: RandomAccessFile): Pair<Int, Int>? {
        raf.seek(2)
        while (raf.filePointer < raf.length() - 9) {
            val b = raf.read()
            if (b != 0xFF) continue
            var marker = raf.read()
            while (marker == 0xFF) marker = raf.read()
            if (marker == 0xD8 || marker == 0x01 || marker in 0xD0..0xD7) continue
            val hi = raf.read(); val lo = raf.read()
            if (hi < 0 || lo < 0) return null
            val len = (hi shl 8) or lo
            // SOF0..SOF15，排除 DHT(C4) / JPG(C8) / DAC(CC)
            if (marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC) {
                val seg = ByteArray(len - 2)
                if (raf.read(seg) < 6) return null
                val h = ((seg[1].toInt() and 0xFF) shl 8) or (seg[2].toInt() and 0xFF)
                val w = ((seg[3].toInt() and 0xFF) shl 8) or (seg[4].toInt() and 0xFF)
                if (w > 0 && h > 0) return w to h
                return null
            }
            if (marker == 0xDA) return null
            raf.seek(raf.filePointer + len - 2)
        }
        return null
    }

    private fun png(raf: RandomAccessFile): Pair<Int, Int>? {
        val ihdr = ByteArray(8)
        raf.seek(16) // 8 签名 + 4 长度 + 4 "IHDR" 之后才是宽高
        if (raf.read(ihdr) < 8) return null
        val w = ((ihdr[0].toInt() and 0xFF) shl 24) or ((ihdr[1].toInt() and 0xFF) shl 16) or
            ((ihdr[2].toInt() and 0xFF) shl 8) or (ihdr[3].toInt() and 0xFF)
        val h = ((ihdr[4].toInt() and 0xFF) shl 24) or ((ihdr[5].toInt() and 0xFF) shl 16) or
            ((ihdr[6].toInt() and 0xFF) shl 8) or (ihdr[7].toInt() and 0xFF)
        return if (w > 0 && h > 0) w to h else null
    }
}

/** 找到文件里 EXIF/TIFF 块的起始位置。 */
object ExifLocator {

    data class Located(val raf: RandomAccessFile, val base: Long) : AutoCloseable {
        override fun close() {
            runCatching { raf.close() }
        }
    }

    fun open(file: File): Located? {
        val raf = try {
            RandomAccessFile(file, "r")
        } catch (_: Exception) {
            return null
        }
        val base = locateBase(raf)
        if (base == null) {
            runCatching { raf.close() }
            return null
        }
        return Located(raf, base)
    }

    /** 在 [start, end) 区间里再找一个 TIFF 头（用于 CR3 这类一个容器里放多个 TIFF 块的格式）。 */
    fun findNextTiff(raf: RandomAccessFile, start: Long, end: Long): Long? {
        val to = minOf(raf.length(), end)
        if (start >= to) return null
        return scanTiffMagic(raf, start + 4, to, maxBytes = to - start)
    }

    /** 按 box 名（如 CR3 的 CMT2）定位其载荷起点，返回载荷位置。 */
    fun findBoxPayload(raf: RandomAccessFile, boxName: String, from: Long, to: Long): Long? {
        val pos = findAscii(raf, boxName, from, minOf(raf.length(), to)) ?: return null
        return pos + boxName.length
    }

    private fun locateBase(raf: RandomAccessFile): Long? {
        val len = raf.length()
        if (len < 16) return null
        val head = ByteArray(minOf(16L, len).toInt())
        raf.seek(0)
        if (raf.read(head) < 8) return null

        // 1) 标准 TIFF 系：II*\0 / MM\0* / IIRO(ORF) / IIU\0(RW2)
        if (isTiffLike(head, 0)) return 0L

        // 2) JPEG：扫 APP1 里的 Exif 段
        if (head[0].toInt() and 0xFF == 0xFF && head[1].toInt() and 0xFF == 0xD8) {
            scanJpegExif(raf, 0, len)?.let { return it }
        }

        // 3) RAF（Fujifilm）：头里有内嵌 JPEG 的偏移/长度（大端）
        if (head.size >= 92 && head.copyOfRange(0, 16).toString(Charsets.ISO_8859_1).startsWith("FUJIFILMCCD-RAW")) {
            raf.seek(84)
            val b = ByteArray(8)
            if (raf.read(b) == 8) {
                val bb = ByteBuffer.wrap(b).order(ByteOrder.BIG_ENDIAN)
                val jpegOffset = bb.getInt(0).toLong() and 0xFFFFFFFFL
                val jpegLength = bb.getInt(4).toLong() and 0xFFFFFFFFL
                if (jpegOffset in 1 until len) {
                    scanJpegExif(raf, jpegOffset, minOf(len, jpegOffset + jpegLength))?.let { return it }
                }
            }
        }

        // 3.2) PNG：EXIF 存在 eXIf chunk 里（chunk 头 8 字节 + 数据 + 4 字节 CRC）
        if (head.size >= 8 && head[1] == 0x50.toByte() && head[2] == 0x4E.toByte() && head[3] == 0x47.toByte()) {
            findPngExif(raf, len)?.let { return it }
        }

        // 3.5) HEIC / HEIF / AVIF（ISO BMFF）：EXIF 在 meta/iinf/iloc 指向的 "Exif" item 里
        if (HeicExif.isHeif(head)) {
            HeicExif.findExifTiffBase(raf)?.let { return it }
        }

        // 4) CR3（ISO BMFF）：CMT1 box 里就是 TIFF
        findAscii(raf, "CMT1", 0, minOf(len, 4L * 1024 * 1024))?.let { pos ->
            if (isTiffLike(readAt(raf, pos + 4, 8), 0)) return pos + 4
        }

        // 5) 通用兜底：在文件里找一个 TIFF 头（覆盖 X3F / MRW / 未知容器）
        return scanTiffMagic(raf, 0, len, maxBytes = 8L * 1024 * 1024)
    }

    private fun isTiffLike(b: ByteArray?, off: Int): Boolean {
        if (b == null || b.size < off + 8) return false
        val ii = b[off] == 0x49.toByte() && b[off + 1] == 0x49.toByte()
        val mm = b[off] == 0x4D.toByte() && b[off + 1] == 0x4D.toByte()
        if (!ii && !mm) return false
        val m = if (ii) {
            (b[off + 2].toInt() and 0xFF) or ((b[off + 3].toInt() and 0xFF) shl 8)
        } else {
            ((b[off + 2].toInt() and 0xFF) shl 8) or (b[off + 3].toInt() and 0xFF)
        }
        return m == 42 || m == 0x4F52 || m == 0x55
    }

    /** 遍历 PNG chunk，找到 eXIf 后返回其 TIFF 数据起点。 */
    private fun findPngExif(raf: RandomAccessFile, len: Long): Long? {
        var pos = 8L // 跳过 8 字节 PNG 签名
        var guard = 0
        while (pos + 8 <= len && guard++ < 2000) {
            val head = readAt(raf, pos, 8) ?: return null
            val size = ((head[0].toLong() and 0xFF) shl 24) or ((head[1].toLong() and 0xFF) shl 16) or
                ((head[2].toLong() and 0xFF) shl 8) or (head[3].toLong() and 0xFF)
            val type = String(head, 4, 4, Charsets.ISO_8859_1)
            if (type == "eXIf") {
                val dataStart = pos + 8
                val probe = readAt(raf, dataStart, 8)
                if (probe != null && isTiffLike(probe, 0)) return dataStart
            }
            if (type == "IEND") return null
            if (size < 0 || size > len) return null
            pos += 12 + size
        }
        return null
    }

    private fun scanJpegExif(raf: RandomAccessFile, from: Long, to: Long): Long? {
        var pos = from
        while (pos < to - 8) {
            val b = readAt(raf, pos, 4) ?: return null
            if (b[0].toInt() and 0xFF != 0xFF) { pos++; continue }
            val marker = b[1].toInt() and 0xFF
            if (marker == 0xD8 || marker == 0x01 || marker in 0xD0..0xD7) { pos += 2; continue }
            val segLen = ((b[2].toInt() and 0xFF) shl 8) or (b[3].toInt() and 0xFF)
            if (marker == 0xE1 && segLen > 8) {
                val tag = readAt(raf, pos + 4, 6)
                if (tag != null && tag.toString(Charsets.ISO_8859_1) == "Exif\u0000\u0000") {
                    return pos + 10
                }
            }
            if (marker == 0xDA) break // 到 SOS 还没找到就放弃
            pos += 2L + segLen
        }
        return null
    }

    private fun findAscii(raf: RandomAccessFile, needle: String, from: Long, to: Long): Long? {
        val n = needle.toByteArray(Charsets.US_ASCII)
        val chunk = 256 * 1024
        var pos = from
        while (pos < to) {
            val len = minOf(chunk.toLong(), to - pos).toInt()
            val buf = readAt(raf, pos, len) ?: return null
            var i = 0
            while (i <= buf.size - n.size) {
                var hit = true
                for (k in n.indices) if (buf[i + k] != n[k]) { hit = false; break }
                if (hit) return pos + i
                i++
            }
            pos += (len - n.size + 1).coerceAtLeast(1)
        }
        return null
    }

    private fun scanTiffMagic(raf: RandomAccessFile, from: Long, to: Long, maxBytes: Long): Long? {
        val end = minOf(to, from + maxBytes)
        val chunk = 256 * 1024
        var pos = from
        while (pos < end) {
            val len = minOf(chunk.toLong(), end - pos).toInt()
            val buf = readAt(raf, pos, len) ?: return null
            var i = 0
            while (i <= buf.size - 8) {
                if (isTiffLike(buf, i)) return pos + i
                i++
            }
            pos += (len - 8).coerceAtLeast(1)
        }
        return null
    }

    private fun readAt(raf: RandomAccessFile, pos: Long, len: Int): ByteArray? {
        if (pos < 0 || len <= 0) return null
        val buf = ByteArray(len)
        return try {
            raf.seek(pos)
            var read = 0
            while (read < len) {
                val n = raf.read(buf, read, len - read)
                if (n <= 0) return null
                read += n
            }
            buf
        } catch (_: Exception) {
            null
        }
    }
}
