package com.yingce.gallery

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yingce.gallery.geo.AdminGeoIndex
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 离线行政区划解析验收：断网状态下给 10 个坐标，必须解析出省 / 市 / 区县。
 * 全程只用内置二进制库，不发起任何网络请求。
 */
@RunWith(AndroidJUnit4::class)
class AdminGeoInstrumentedTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private data class Case(val name: String, val lat: Double, val lon: Double, val expectProvince: String)

    private val cases = listOf(
        Case("北京天安门", 39.9087, 116.3975, "北京市"),
        Case("上海人民广场", 31.2304, 121.4737, "上海市"),
        Case("广州越秀", 23.1291, 113.2644, "广东省"),
        Case("深圳福田", 22.5431, 114.0579, "广东省"),
        Case("成都青羊", 30.5728, 104.0668, "四川省"),
        Case("西安莲湖", 34.3416, 108.9398, "陕西省"),
        Case("拉萨城关", 29.6520, 91.1721, "西藏自治区"),
        Case("乌鲁木齐天山", 43.8256, 87.6168, "新疆维吾尔自治区"),
        Case("哈尔滨南岗", 45.8038, 126.5349, "黑龙江省"),
        Case("三亚吉阳", 18.2528, 109.5119, "海南省"),
    )

    @Test
    fun resolvesTenCoordinatesOffline() {
        val index = AdminGeoIndex.get(context)
        assertTrue("离线库应已加载", index.isReady)

        val report = StringBuilder()
        val problems = mutableListOf<String>()

        cases.forEach { c ->
            val p = index.resolve(c.lat, c.lon)
            report.append(
                String.format(
                    java.util.Locale.US,
                    "%-12s (%.4f, %.4f) -> %s / %s / %s  [adcode=%s]%n",
                    c.name, c.lat, c.lon,
                    p?.province ?: "-", p?.city ?: "-", p?.county ?: "-", p?.adcode ?: "-",
                )
            )
            if (p == null) {
                problems += "${c.name}: 未命中"
            } else {
                if (p.province != c.expectProvince) problems += "${c.name}: 省=${p.province} 期望=${c.expectProvince}"
                if (p.county == null) problems += "${c.name}: 只到 ${p.province}/${p.city}"
            }
        }

        android.util.Log.i("AdminGeo", "\n$report")
        println(report.toString())
        println("PROBLEMS: " + problems.joinToString(" | "))
        assertTrue("解析问题: ${problems.joinToString(" | ")}", problems.isEmpty())
    }

    @Test
    fun unknownCoordinateReturnsNullInsteadOfGuessing() {
        val index = AdminGeoIndex.get(context)
        val p = index.resolve(0.0, 180.0)
        assertNull("境外坐标不应编造行政区", p)
    }
}
