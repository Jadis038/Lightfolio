package com.yingce.gallery.exif

import org.junit.Assert.assertEquals
import org.junit.Test

class PhotoExifFormatTest {

    private fun base() = PhotoExif(fileName = "x.jpg", formatLabel = "JPEG", sizeBytes = 1024)

    @Test
    fun `pending fields render as not-recorded`() {
        val e = base()
        assertEquals(NOT_RECORDED, e.apertureText)
        assertEquals(NOT_RECORDED, e.shutterText)
        assertEquals(NOT_RECORDED, e.isoText)
        assertEquals(NOT_RECORDED, e.focal35Text)
        assertEquals(NOT_RECORDED, e.focalText)
        assertEquals(NOT_RECORDED, e.whiteBalanceText)
        assertEquals(NOT_RECORDED, e.exposureProgramText)
        assertEquals(NOT_RECORDED, e.exposureBiasText)
        assertEquals(NOT_RECORDED, e.meteringModeText)
        assertEquals(NOT_RECORDED, e.resolutionText)
        assertEquals(NOT_RECORDED, e.megapixelText)
        assertEquals(NOT_RECORDED, e.cameraText)
        assertEquals(NOT_RECORDED, e.lensText)
    }

    @Test
    fun `aperture formatting keeps one decimal when needed`() {
        assertEquals("f/2.8", base().copy(aperture = 2.8).apertureText)
        assertEquals("f/8", base().copy(aperture = 8.0).apertureText)
        assertEquals("f/1.8", base().copy(aperture = 1.8).apertureText)
    }

    @Test
    fun `shutter formatting uses fractions below one second`() {
        assertEquals("1/250 s", base().copy(exposureSeconds = 1.0 / 250).shutterText)
        assertEquals("1/60 s", base().copy(exposureSeconds = 0.0166666666).shutterText)
        assertEquals("2 s", base().copy(exposureSeconds = 2.0).shutterText)
        assertEquals("4 s", base().copy(exposureSeconds = 4.0).shutterText)
    }

    @Test
    fun `focal length formatting`() {
        assertEquals("105 mm", base().copy(focalLengthMm = 105.0).focalText)
        assertEquals("24 mm", base().copy(focalLengthMm = 24.0).focalText)
        assertEquals("18.5 mm", base().copy(focalLengthMm = 18.5).focalText)
        assertEquals("27 mm", base().copy(focalLength35Mm = 27).focal35Text)
    }

    @Test
    fun `megapixels keep two decimals`() {
        val e = base().copy(width = 7262, height = 5456)
        assertEquals("7262 × 5456", e.resolutionText)
        assertEquals("39.62 MP", e.megapixelText)   // 39.621... -> 39.62
    }

    @Test
    fun `megapixels rounding`() {
        assertEquals("44.76 MP", base().copy(width = 8192, height = 5464).megapixelText)
        assertEquals("2.46 MP", base().copy(width = 1920, height = 1280).megapixelText)
    }

    @Test
    fun `white balance mapping`() {
        assertEquals("自动", base().copy(whiteBalance = 0).whiteBalanceText)
        assertEquals("手动", base().copy(whiteBalance = 1).whiteBalanceText)
        assertEquals(NOT_RECORDED, base().copy(whiteBalance = 7).whiteBalanceText)
    }

    @Test
    fun `exposure program mapping`() {
        assertEquals("手动", base().copy(exposureProgram = 1).exposureProgramText)
        assertEquals("程序自动", base().copy(exposureProgram = 2).exposureProgramText)
        assertEquals("光圈优先", base().copy(exposureProgram = 3).exposureProgramText)
        assertEquals("快门优先", base().copy(exposureProgram = 4).exposureProgramText)
    }

    @Test
    fun `exposure bias formatting`() {
        assertEquals("0 EV", base().copy(exposureBiasEv = 0.0).exposureBiasText)
        assertEquals("+0.33 EV", base().copy(exposureBiasEv = 1.0 / 3.0).exposureBiasText)
        assertEquals("-0.5 EV", base().copy(exposureBiasEv = -0.5).exposureBiasText)
    }

    @Test
    fun `metering mode mapping`() {
        assertEquals("中央重点平均", base().copy(meteringMode = 2).meteringModeText)
        assertEquals("评价测光（分区）", base().copy(meteringMode = 5).meteringModeText)
        assertEquals("其他", base().copy(meteringMode = 255).meteringModeText)
    }

    @Test
    fun `camera text avoids duplicating make inside model`() {
        assertEquals("Canon EOS R5", base().copy(make = "Canon", model = "Canon EOS R5").cameraText)
        assertEquals("NIKON D750", base().copy(make = "NIKON CORPORATION", model = "NIKON D750").cameraText)
        assertEquals("Hasselblad H3D", base().copy(make = "Hasselblad", model = "Hasselblad H3D").cameraText)
    }

    @Test
    fun `file size formatting`() {
        assertEquals("1.0 KB", base().copy(sizeBytes = 1024).sizeText)
        assertEquals("52.46 MB", base().copy(sizeBytes = 55_007_232).sizeText)
    }
}
