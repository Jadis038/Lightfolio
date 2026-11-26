package com.yingce.gallery.exif

import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * HEIC / HEIF / AVIF 的 EXIF 提取（ISO-BMFF 容器）。
 *
 * 结构：ftyp 'meta' { hdlr, iinf{ infe... }, iloc, idat? }
 *  - `iinf` 里的 `infe` 描述每个 item 的类型，找 item_type == "Exif"
 *  - `iloc` 描述该 item 的数据位置（construction_method 0=文件绝对偏移，1=idat 内相对）
 *  - Exif item 载荷 = 4 字节偏移 + TIFF 数据；偏移值各家不一，这里直接在载荷头部扫 TIFF 魔数
 *
 * 只读、只做定位，不做像素解码；失败一律返回 null，不让上层崩。
 */
object HeicExif {

    private val HEIF_BRANDS = setOf("heic", "heix", "heim", "heis", "hevc", "hevx", "mif1", "msf1", "avif", "avis")

    private data class Box(val type: String, val payloadStart: Long, val payloadEnd: Long)

    fun isHeif(head: ByteArray): Boolean {
        if (head.size < 12) return false
        val brand = String(head, 8, 4, Charsets.ISO_8859_1)
        return brand in HEIF_BRANDS || brand.startsWith("hei") || brand.startsWith("mif")
    }

    /** 返回 TIFF 头（"II*\0" / "MM\0*"）在文件中的绝对偏移。 */
    fun findExifTiffBase(raf: RandomAccessFile): Long? {
        val len = raf.length()
        val meta = findTopLevelBox(raf, 0, len, "meta") ?: return null

        // meta 是 full box：4 字节 version/flags
        val childrenStart = meta.payloadStart + 4
        var exifItemId = -1L
        var iloc: Box? = null
        var idat: Box? = null

        var pos = childrenStart
        while (pos + 8 <= meta.payloadEnd) {
            val box = readBox(raf, pos, meta.payloadEnd) ?: break
            when (box.type) {
                "iinf" -> exifItemId = findExifItemId(raf, box) ?: exifItemId
                "iloc" -> iloc = box
                "idat" -> idat = box
            }
            if (box.payloadEnd <= pos) break
            pos = box.payloadEnd
        }
        if (exifItemId < 0 || iloc == null) return null

        val range = parseIlocForItem(raf, iloc, exifItemId) ?: return null
        val absolute = if (range.constructionMethod == 1) {
            val idatStart = idat?.payloadStart ?: return null
            idatStart + range.offset
        } else {
            range.offset
        }
        val payloadLen = range.length.coerceAtMost(len - absolute).toInt()
        if (payloadLen <= 0) return null

        // 在 Exif item 载荷的前 64 字节里找 TIFF 魔数（通常前面有 4 字节偏移）
        val head = readAt(raf, absolute, minOf(payloadLen, 64)) ?: return null
        for (i in 0..head.size - 4) {
            val ii = head[i] == 0x49.toByte() && head[i + 1] == 0x49.toByte() &&
                head[i + 2] == 0x2A.toByte() && head[i + 3] == 0x00.toByte()
            val mm = head[i] == 0x4D.toByte() && head[i + 1] == 0x4D.toByte() &&
                head[i + 2] == 0x00.toByte() && head[i + 3] == 0x2A.toByte()
            if (ii || mm) return absolute + i
        }
        return null
    }

    // ---------- box 解析 ----------

    private fun readBox(raf: RandomAccessFile, pos: Long, limit: Long): Box? {
        if (pos + 8 > limit) return null
        val head = readAt(raf, pos, 8) ?: return null
        val size32 = u32(head, 0)
        val type = String(head, 4, 4, Charsets.ISO_8859_1)
        var headerSize = 8L
        var size = size32
        if (size32 == 1L) {
            // 64 位长度
            val big = readAt(raf, pos + 8, 8) ?: return null
            size = ByteBuffer.wrap(big).order(ByteOrder.BIG_ENDIAN).long
            headerSize = 16L
        } else if (size32 == 0L) {
            size = limit - pos
        }
        if (size < headerSize) return null
        val end = (pos + size).coerceAtMost(limit)
        return Box(type, pos + headerSize, end)
    }

    private fun findTopLevelBox(raf: RandomAccessFile, from: Long, to: Long, type: String): Box? {
        var pos = from
        while (pos + 8 <= to) {
            val box = readBox(raf, pos, to) ?: return null
            if (box.type == type) return box
            if (box.payloadEnd <= pos) return null
            pos = box.payloadEnd
        }
        return null
    }

    /** iinf: fullbox(4) + entry_count(2 or 4) + infe... */
    private fun findExifItemId(raf: RandomAccessFile, iinf: Box): Long? {
        val head = readAt(raf, iinf.payloadStart, 8) ?: return null
        val version = head[0].toInt() and 0xFF
        var pos = iinf.payloadStart + 4
        val countSize = if (version == 0) 2 else 4
        val countBytes = readAt(raf, pos, countSize) ?: return null
        val count = if (countSize == 2) u16(countBytes, 0).toLong() else u32(countBytes, 0)
        pos += countSize

        var i = 0L
        while (i < count && pos + 8 <= iinf.payloadEnd) {
            val box = readBox(raf, pos, iinf.payloadEnd) ?: return null
            if (box.type == "infe") {
                val id = parseInfeForExif(raf, box)
                if (id != null && id >= 0) return id
            }
            if (box.payloadEnd <= pos) return null
            pos = box.payloadEnd
            i++
        }
        return null
    }

    /** infe v2/v3: fullbox(4) + item_ID(2/4) + protection(2) + item_type(4cc) ... */
    private fun parseInfeForExif(raf: RandomAccessFile, infe: Box): Long? {
        val head = readAt(raf, infe.payloadStart, 16) ?: return null
        val version = head[0].toInt() and 0xFF
        return try {
            if (version >= 2) {
                val sb = ByteBuffer.wrap(head).order(ByteOrder.BIG_ENDIAN)
                val itemId = if (version == 2) (sb.getShort(4).toLong() and 0xFFFF)
                else (sb.getInt(4).toLong() and 0xFFFFFFFFL)
                val typeOffset = if (version == 2) 8 else 10
                if (typeOffset + 4 > head.size) return null
                val itemType = String(head, typeOffset, 4, Charsets.ISO_8859_1)
                if (itemType == "Exif") itemId else null
            } else {
                // v0/v1：没有 item_type，用 item_name 里是否含 "Exif" 判断
                val nameStart = infe.payloadStart + 4 + 2 + 2
                val nameBytes = readAt(raf, nameStart, 32) ?: return null
                val name = nameBytes.toString(Charsets.ISO_8859_1)
                if (name.contains("Exif", ignoreCase = true)) {
                    (u16(head, 4)).toLong()
                } else null
            }
        } catch (_: Exception) {
            null
        }
    }

    private data class ExtentRange(val constructionMethod: Int, val offset: Long, val length: Long)

    /** iloc: fullbox(4) + [offset_size/len_size, base_offset_size/index_size] + item_count + items... */
    private fun parseIlocForItem(raf: RandomAccessFile, iloc: Box, targetItemId: Long): ExtentRange? {
        val head = readAt(raf, iloc.payloadStart, 8) ?: return null
        val version = head[0].toInt() and 0xFF
        val offsetSize = (head[4].toInt() shr 4) and 0x0F
        val lengthSize = head[4].toInt() and 0x0F
        val baseOffsetSize = (head[5].toInt() shr 4) and 0x0F
        val indexSize = if (version >= 1) head[5].toInt() and 0x0F else 0

        // version/flags(4) 之后还有 2 字节的 size 字段（offset/length/base/index 的宽度），
        // 第一版漏跳了这 2 字节，把 size 字段当成 item_count 读，导致 iloc 解析必定失败。
        var pos = iloc.payloadStart + 4 + 2
        val countSize = if (version < 2) 2 else 4
        val countBytes = readAt(raf, pos, countSize) ?: return null
        val itemCount = if (countSize == 2) u16(countBytes, 0).toLong() else u32(countBytes, 0)
        pos += countSize

        var i = 0L
        while (i < itemCount) {
            val idSize = if (version < 2) 2 else 4
            val idBytes = readAt(raf, pos, idSize) ?: return null
            val itemId = if (idSize == 2) u16(idBytes, 0).toLong() else u32(idBytes, 0)
            pos += idSize

            var constructionMethod = 0
            if (version >= 1) {
                val cm = readAt(raf, pos, 2) ?: return null
                constructionMethod = u16(cm, 0) and 0x0F
                pos += 2
            }
            pos += 2 // data_reference_index

            val baseBytes = readAt(raf, pos, baseOffsetSize) ?: return null
            val baseOffset = readN(baseBytes, 0, baseOffsetSize)
            pos += baseOffsetSize

            val extCountBytes = readAt(raf, pos, 2) ?: return null
            val extentCount = u16(extCountBytes, 0)
            pos += 2

            var firstOffset = -1L
            var firstLength = 0L
            for (e in 0 until extentCount) {
                if (indexSize > 0) pos += indexSize
                val offBytes = readAt(raf, pos, offsetSize) ?: return null
                val extOffset = readN(offBytes, 0, offsetSize)
                pos += offsetSize
                val lenBytes = readAt(raf, pos, lengthSize) ?: return null
                val extLength = readN(lenBytes, 0, lengthSize)
                pos += lengthSize
                if (e == 0) {
                    firstOffset = baseOffset + extOffset
                    firstLength = extLength
                }
            }
            if (itemId == targetItemId && firstOffset >= 0) {
                return ExtentRange(constructionMethod, firstOffset, firstLength)
            }
            i++
        }
        return null
    }

    // ---------- 基础读取 ----------

    private fun readAt(raf: RandomAccessFile, pos: Long, n: Int): ByteArray? = try {
        // n == 0 是合法情况（base_offset_size / index_size 为 0），返回空数组而不是 null，
        // 否则整个 iloc 解析会被误判为失败。
        if (n == 0) ByteArray(0)
        else if (pos < 0 || n < 0) null
        else {
            raf.seek(pos)
            val b = ByteArray(n)
            val r = raf.read(b)
            if (r <= 0) null else b.copyOf(r)
        }
    } catch (_: Exception) {
        null
    }

    private fun u16(b: ByteArray, o: Int): Int =
        ((b[o].toInt() and 0xFF) shl 8) or (b[o + 1].toInt() and 0xFF)

    private fun u32(b: ByteArray, o: Int): Long =
        ((b[o].toLong() and 0xFF) shl 24) or ((b[o + 1].toLong() and 0xFF) shl 16) or
            ((b[o + 2].toLong() and 0xFF) shl 8) or (b[o + 3].toLong() and 0xFF)

    private fun readN(b: ByteArray, o: Int, n: Int): Long {
        var v = 0L
        for (i in 0 until n) {
            if (o + i >= b.size) break
            v = (v shl 8) or (b[o + i].toLong() and 0xFF)
        }
        return v
    }
}
