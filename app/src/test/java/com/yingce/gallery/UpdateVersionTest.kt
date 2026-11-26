package com.yingce.gallery

import com.yingce.gallery.data.UpdateChecker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 版本号比较：更新检查的正确性完全依赖它，必须覆盖各种写法。 */
class UpdateVersionTest {

    @Test
    fun parsesNumbersFromTag() {
        assertEquals(listOf(1, 50), UpdateChecker.Versions.parts("v1.50"))
        assertEquals(listOf(1, 42), UpdateChecker.Versions.parts("V1.42"))
        assertEquals(listOf(1, 50), UpdateChecker.Versions.parts("V1.50"))
        // 带后缀的 tag 也能解析
        assertEquals(listOf(2, 0, 1), UpdateChecker.Versions.parts("release-2.0.1"))
    }

    @Test
    fun newerVersionDetected() {
        assertTrue(UpdateChecker.Versions.isNewer("v1.50", "V1.42"))
        assertTrue(UpdateChecker.Versions.isNewer("v2.0", "V1.99"))
        assertTrue(UpdateChecker.Versions.isNewer("v1.51", "V1.50"))
        // 1.5 与 1.50 数值相等，不算更新
        assertFalse(UpdateChecker.Versions.isNewer("v1.5", "V1.50"))
    }

    @Test
    fun sameOrOlderIsNotUpdate() {
        assertFalse(UpdateChecker.Versions.isNewer("v1.42", "V1.42"))
        assertFalse(UpdateChecker.Versions.isNewer("v1.41", "V1.42"))
        assertFalse(UpdateChecker.Versions.isNewer("", "V1.42"))
        assertFalse(UpdateChecker.Versions.isNewer("v0.9", "V1.0"))
    }

    @Test
    fun differentSegmentCountsCompareAsIfPaddedWithZero() {
        assertTrue(UpdateChecker.Versions.isNewer("v1.50.1", "V1.50"))
        assertFalse(UpdateChecker.Versions.isNewer("v1.50", "V1.50.1"))
    }
}
