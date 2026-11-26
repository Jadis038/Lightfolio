package com.yingce.gallery.util

import org.junit.Assert.assertEquals
import org.junit.Test

class NamingTest {

    @Test
    fun `first import keeps original name`() {
        assertEquals("IMG_0001.jpg", Naming.candidate("IMG_0001.jpg", 0))
    }

    @Test
    fun `second import appends space and index`() {
        assertEquals("IMG_0001 (1).jpg", Naming.candidate("IMG_0001.jpg", 1))
        assertEquals("IMG_0001 (2).jpg", Naming.candidate("IMG_0001.jpg", 2))
    }

    @Test
    fun `extension with uppercase is preserved`() {
        assertEquals("DSC" to ".JPG", Naming.splitExt("DSC.JPG"))
        assertEquals("DSC (1).JPG", Naming.candidate("DSC.JPG", 1))
    }

    @Test
    fun `source name that already contains index is not stripped`() {
        // 源文件本身就叫 "photo (1).jpg"：第 0 次必须原样，第 1 次是 "photo (1) (1).jpg"
        assertEquals("photo (1).jpg", Naming.candidate("photo (1).jpg", 0))
        assertEquals("photo (1) (1).jpg", Naming.candidate("photo (1).jpg", 1))
    }

    @Test
    fun `name without extension`() {
        assertEquals("README (1)", Naming.candidate("README", 1))
    }

    @Test
    fun `dotfile has no extension`() {
        assertEquals(".hidden (1)", Naming.candidate(".hidden", 1))
    }

    @Test
    fun `resolve skips occupied names`() {
        val taken = mutableSetOf("a.jpg", "a (1).jpg")
        assertEquals("a (2).jpg", Naming.resolve("a.jpg") { it in taken })
    }

    @Test
    fun `resolve returns original when free`() {
        assertEquals("a.jpg", Naming.resolve("a.jpg") { false })
    }

    @Test
    fun `simulating two imports of same source file`() {
        val onDisk = mutableSetOf<String>()
        val first = Naming.resolve("name.jpg") { it in onDisk }.also { onDisk += it }
        val second = Naming.resolve("name.jpg") { it in onDisk }.also { onDisk += it }
        val third = Naming.resolve("name.jpg") { it in onDisk }
        assertEquals("name.jpg", first)
        assertEquals("name (1).jpg", second)
        assertEquals("name (2).jpg", third)
    }
}
