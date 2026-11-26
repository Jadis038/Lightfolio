package com.yingce.gallery.exif

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.io.File

/**
 * 用真实带 GPS 的 JPEG 验证 EXIF 解析（JVM 单测，不依赖设备）。
 * 样本：exiftool 写入 GPS 39.9087N / 116.3975E，机型 Xiaomi 14。
 */
class GpsExifTest {

    private fun sample(): File {
        val f = File("src/test/resources/geo_sample.jpg")
        assertNotNull("样本文件应存在: ${f.absolutePath}", f.takeIf { it.exists() })
        return f
    }

    @Test
    fun readsGpsCoordinatesFromJpeg() {
        val e = ExifReader.read(sample())
        assertEquals("Xiaomi 14", e.cameraText)
        assertNotNull("应解析出纬度", e.gpsLat)
        assertNotNull("应解析出经度", e.gpsLon)
        assertEquals(39.9087, e.gpsLat!!, 0.001)
        assertEquals(116.3975, e.gpsLon!!, 0.001)
    }

    @Test
    fun formatsCoordinatesWithDirection() {
        val e = ExifReader.read(sample())
        val dms = com.yingce.gallery.geo.GeoText.dms(e.gpsLat!!, e.gpsLon!!)
        assertEquals("39°54'31.32\"N  116°23'51.00\"E", dms)
    }
}
