package com.yingce.gallery.exif

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * HEIC（ISO-BMFF）EXIF 提取的单测。
 *
 * 没有现成的带 EXIF 的 HEIC 样片，所以按 HEIF 规范手工拼一个最小容器：
 *   ftyp + meta{ iinf{ infe(Exif) }, iloc } + Exif item(载荷 = 4 字节偏移 + TIFF)
 * 其中 TIFF 部分直接复用 JPEG 样张里已有的 EXIF 块，保证是真实数据。
 */
class HeicExifTest {

    private fun jpegExifTiff(): ByteArray {
        val f = File("src/test/resources/geo_sample.jpg")
        assertNotNull("需要 JPEG 样张", f.takeIf { it.exists() })
        val bytes = f.readBytes()
        // 在 JPEG 里找 APP1 的 "Exif\0\0"，其后就是 TIFF
        val marker = byteArrayOf(0x45, 0x78, 0x69, 0x66, 0x00, 0x00)
        for (i in 0..bytes.size - marker.size) {
            var ok = true
            for (k in marker.indices) if (bytes[i + k] != marker[k]) { ok = false; break }
            if (ok) {
                val start = i + marker.size
                // 到 APP1 段尾
                return bytes.copyOfRange(start, minOf(bytes.size, start + 8000))
            }
        }
        throw AssertionError("样张里没有 EXIF APP1")
    }

    private fun box(type: String, payload: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(8 + payload.size).array())
        out.write(type.toByteArray(Charsets.ISO_8859_1))
        out.write(payload)
        return out.toByteArray()
    }

    private fun buildHeic(tiff: ByteArray): ByteArray {
        val ftyp = box("ftyp", "heic".toByteArray() + ByteBuffer.allocate(4).putInt(0).array() + "mif1heic".toByteArray())

        // infe v2: fullbox(0x02 000000) + item_ID(2) + protection(2) + item_type(4) + item_name("Exif\0")
        val infePayload = ByteArrayOutputStream().apply {
            write(byteArrayOf(0x02, 0x00, 0x00, 0x00))
            write(ByteBuffer.allocate(2).putShort(1).array())          // item_ID = 1
            write(ByteBuffer.allocate(2).putShort(0).array())          // protection = 0
            write("Exif".toByteArray())
            write(byteArrayOf(0x00))
        }.toByteArray()
        val infe = box("infe", infePayload)

        // iinf: fullbox v0 + entry_count(2) + infe
        val iinfPayload = ByteArrayOutputStream().apply {
            write(byteArrayOf(0x00, 0x00, 0x00, 0x00))
            write(ByteBuffer.allocate(2).putShort(1).array())
            write(infe)
        }.toByteArray()
        val iinf = box("iinf", iinfPayload)

        // 先把 meta 之外的长度算出来，再填 iloc 的绝对偏移
        // 布局：ftyp | meta(hdlr? 省略) | idat? 不使用 → Exif 数据紧跟在 meta 后面
        val exifPayload = ByteArrayOutputStream().apply {
            write(ByteBuffer.allocate(4).putInt(6).array())   // 指向 TIFF 的相对偏移（常见值 6）
            write(tiff)
        }.toByteArray()

        // iloc v1: fullbox(01 000000) + offset_size/len_size(0x44) + base/index(0x00)
        //           + item_count(2) + item_ID(2) + construction_method(2) + data_ref(2)
        //           + base_offset(0) + extent_count(2) + extent_offset(4) + extent_length(4)
        fun buildMeta(exifAbsoluteOffset: Int): ByteArray {
            val ilocPayload = ByteArrayOutputStream().apply {
                write(byteArrayOf(0x01, 0x00, 0x00, 0x00))          // version=1
                write(byteArrayOf(0x44, 0x00))                      // offset=4, length=4, base=0, index=0
                write(ByteBuffer.allocate(2).putShort(1).array())   // item_count
                write(ByteBuffer.allocate(2).putShort(1).array())   // item_ID
                write(ByteBuffer.allocate(2).putShort(0).array())   // construction_method=0（文件绝对偏移）
                write(ByteBuffer.allocate(2).putShort(0).array())   // data_reference_index
                write(ByteBuffer.allocate(2).putShort(1).array())   // extent_count
                write(ByteBuffer.allocate(4).putInt(exifAbsoluteOffset).array())
                write(ByteBuffer.allocate(4).putInt(exifPayload.size).array())
            }.toByteArray()
            val iloc = box("iloc", ilocPayload)
            return box("meta", ByteArrayOutputStream().apply {
                write(byteArrayOf(0x00, 0x00, 0x00, 0x00)) // meta 的 version/flags
                write(iinf)
                write(iloc)
            }.toByteArray())
        }

        val metaSize = buildMeta(0).size
        // meta 之后紧跟 exifPayload，其绝对偏移 = ftyp.size + metaSize
        val exifOffset = ftyp.size + metaSize
        val meta = buildMeta(exifOffset)

        return ByteArrayOutputStream().apply {
            write(ftyp)
            write(meta)
            write(exifPayload)
        }.toByteArray()
    }

    @Test
    fun locatesTiffInsideHeicContainer() {
        val tiff = jpegExifTiff()
        val heic = buildHeic(tiff)
        val tmp = File.createTempFile("heic_fixture", ".heic")
        try {
            tmp.writeBytes(heic)
            val base = java.io.RandomAccessFile(tmp, "r").use { HeicExif.findExifTiffBase(it) }
            assertNotNull("应能在 HEIC 容器里定位 TIFF 基址", base)
            // 定位到的位置必须以 TIFF 魔数开头
            java.io.RandomAccessFile(tmp, "r").use { raf ->
                raf.seek(base!!)
                val magic = ByteArray(4)
                raf.readFully(magic)
                val ii = magic[0] == 0x49.toByte() && magic[1] == 0x49.toByte() && magic[2] == 0x2A.toByte()
                val mm = magic[0] == 0x4D.toByte() && magic[1] == 0x4D.toByte() && magic[3] == 0x2A.toByte()
                assertTrue("基址处应是 TIFF 头", ii || mm)
            }
        } finally {
            tmp.delete()
        }
    }

    @Test
    fun readsGpsFromHeicFixture() {
        val tiff = jpegExifTiff()
        val heic = buildHeic(tiff)
        val tmp = File.createTempFile("heic_gps", ".heic")
        try {
            tmp.writeBytes(heic)
            val exif = ExifReader.read(tmp)
            assertEquals("Xiaomi 14", exif.cameraText)
            assertNotNull("HEIC 里应读出纬度", exif.gpsLat)
            assertNotNull("HEIC 里应读出经度", exif.gpsLon)
            assertEquals(39.9087, exif.gpsLat!!, 0.001)
            assertEquals(116.3975, exif.gpsLon!!, 0.001)
        } finally {
            tmp.delete()
        }
    }

    @Test
    fun nonHeicFileReturnsNull() {
        val jpeg = File("src/test/resources/geo_sample.jpg")
        java.io.RandomAccessFile(jpeg, "r").use {
            assertNull("JPEG 不应走进 HEIC 解析", HeicExif.findExifTiffBase(it))
        }
    }

    private fun assertTrue(msg: String, cond: Boolean) = org.junit.Assert.assertTrue(msg, cond)
}
