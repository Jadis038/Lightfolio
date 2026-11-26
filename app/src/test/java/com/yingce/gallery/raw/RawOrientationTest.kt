package com.yingce.gallery.raw

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 方向修正的纯逻辑单测。用 3×2 的编号矩阵，逐种 flip 验证输出布局。
 *
 * 原始矩阵（w=3, h=2）：
 *   1 2 3
 *   4 5 6
 */
class RawOrientationTest {

    private val src = intArrayOf(1, 2, 3, 4, 5, 6)
    private val w = 3
    private val h = 2

    @Test
    fun flip0_keepsAsIs() {
        val r = RawOrientation.apply(src, w, h, 0)
        assertArrayEquals(src, r.pixels)
        assertEquals(3, r.width)
        assertEquals(2, r.height)
    }

    @Test
    fun flip3_rotates180() {
        val r = RawOrientation.apply(src, w, h, 3)
        assertArrayEquals(intArrayOf(6, 5, 4, 3, 2, 1), r.pixels)
    }

    @Test
    fun flip1_mirrorsHorizontally() {
        val r = RawOrientation.apply(src, w, h, 1)
        assertArrayEquals(intArrayOf(3, 2, 1, 6, 5, 4), r.pixels)
    }

    @Test
    fun flip2_mirrorsVertically() {
        val r = RawOrientation.apply(src, w, h, 2)
        assertArrayEquals(intArrayOf(4, 5, 6, 1, 2, 3), r.pixels)
    }

    @Test
    fun flip6_rotates90Clockwise() {
        // 顺时针 90°：新宽=2, 新高=3 → 4 1 / 5 2 / 6 3
        val r = RawOrientation.apply(src, w, h, 6)
        assertEquals(2, r.width)
        assertEquals(3, r.height)
        assertArrayEquals(intArrayOf(4, 1, 5, 2, 6, 3), r.pixels)
    }

    @Test
    fun flip5_rotates90CounterClockwise() {
        // 逆时针 90°：新宽=2, 新高=3 → 3 6 / 2 5 / 1 4
        val r = RawOrientation.apply(src, w, h, 5)
        assertEquals(2, r.width)
        assertEquals(3, r.height)
        assertArrayEquals(intArrayOf(3, 6, 2, 5, 1, 4), r.pixels)
    }

    @Test
    fun invalidInputIsReturnedUnchanged() {
        val r = RawOrientation.apply(intArrayOf(1, 2), 3, 2, 6)
        assertArrayEquals(intArrayOf(1, 2), r.pixels)
    }
}
