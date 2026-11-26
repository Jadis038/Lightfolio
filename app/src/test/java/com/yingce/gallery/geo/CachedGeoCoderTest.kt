package com.yingce.gallery.geo

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 验证"同一坐标连续打开 5 次只发一次请求"，且全部用假传输层，不产生任何真实网络请求。
 */
class CachedGeoCoderTest {

    private class FakeTransport(var result: String? = "北京市东城区东华门街道") : GeoTransport {
        var calls = 0
        val seen = mutableListOf<Pair<Double, Double>>()
        override fun fetch(lat: Double, lon: Double): String? {
            calls++
            seen += lat to lon
            return result
        }
    }

    @Test
    fun `same coordinate queried 5 times only calls transport once`() = runBlocking {
        val fake = FakeTransport()
        val coder = CachedGeoCoder(fake, gridDegrees = 0.01)

        repeat(5) {
            val v = coder.lookup(39.904989, 116.405285)
            assertEquals("北京市东城区东华门街道", v)
        }
        assertEquals("应当只请求一次", 1, fake.calls)
        assertEquals(1, coder.requestCount.get())
    }

    @Test
    fun `coordinates inside same grid cell share one request`() = runBlocking {
        val fake = FakeTransport()
        val coder = CachedGeoCoder(fake, gridDegrees = 0.01)

        // 39.901 与 39.904 落在同一 0.01° 网格
        coder.lookup(39.901, 116.405)
        coder.lookup(39.904, 116.408)
        assertEquals("同网格只请求一次", 1, fake.calls)
    }

    @Test
    fun `different grid cells produce separate requests`() = runBlocking {
        val fake = FakeTransport()
        val coder = CachedGeoCoder(fake, gridDegrees = 0.01)
        coder.lookup(39.904, 116.405)
        coder.lookup(31.230, 121.473)
        assertEquals(2, fake.calls)
    }

    @Test
    fun `concurrent lookups on same cell are coalesced`() = runBlocking {
        val fake = FakeTransport()
        val coder = CachedGeoCoder(fake, gridDegrees = 0.01)

        val results = (1..8).map {
            async { coder.lookup(39.904989, 116.405285) }
        }.awaitAll()

        assertEquals(8, results.size)
        assertEquals("并发 8 次只应发 1 次", 1, fake.calls)
    }

    @Test
    fun `failure is negatively cached within ttl`() = runBlocking {
        val fake = FakeTransport(result = null)
        var now = 1_000_000L
        val coder = CachedGeoCoder(fake, clock = { now })

        assertNull(coder.lookup(39.9, 116.4))
        assertNull(coder.lookup(39.9, 116.4))
        assertEquals("失败也应只请求一次（负缓存）", 1, fake.calls)

        // 超过负缓存 TTL 后允许重试
        now += 11 * 60 * 1000
        coder.lookup(39.9, 116.4)
        assertEquals("负缓存过期后应重试", 2, fake.calls)
    }

    @Test
    fun `successful result survives negative ttl horizon`() = runBlocking {
        val fake = FakeTransport()
        var now = 1_000_000L
        val coder = CachedGeoCoder(fake, clock = { now })

        coder.lookup(39.9, 116.4)
        now += 60 * 60 * 1000
        coder.lookup(39.9, 116.4)
        assertEquals("成功结果在 30 天 TTL 内不应重发", 1, fake.calls)
    }

    @Test
    fun `amap transport without key never touches network`() {
        val transport = AmapTransport(apiKey = "")
        assertNull("没有 Key 时必须直接返回 null", transport.fetch(39.9, 116.4))
    }

    @Test
    fun `whitelist has exactly one host`() {
        assertEquals("restapi.amap.com", GeoWhitelist.HOST)
        // 白名单只有一个域名：URL 里的 host 必须与 HOST 完全一致，且不含其它域名
        val host = GeoWhitelist.BASE_URL.substringAfter("://").substringBefore("/")
        assertEquals("BASE_URL 的 host 必须就是白名单域名", GeoWhitelist.HOST, host)
        assertEquals("除白名单外不应出现其它域名", 1, Regex("https?://").findAll(GeoWhitelist.BASE_URL).count())
    }
}
