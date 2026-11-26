package com.yingce.gallery.geo

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger

/** 只允许一个白名单域名：高德 Web 服务（逆地理编码）。 */
object GeoWhitelist {
    const val HOST = "restapi.amap.com"
    const val BASE_URL = "https://$HOST/v3/geocode/regeo"
}

/** 可注入的传输层，便于用假实现验证缓存与并发合并（不产生任何真实网络请求）。 */
interface GeoTransport {
    /** 返回地址文本；失败返回 null（不要抛到上层）。 */
    fun fetch(lat: Double, lon: Double): String?
}

/**
 * 带"坐标网格缓存 + 并发合并"的地理编码调用器。
 *
 * 约束：
 * - 结果按网格缓存：网格边长 [gridDegrees]（默认 0.01° ≈ 1.1km），同网格视为同一坐标。
 * - 同一网格并发多次请求只会真正发一次（single-flight）。
 * - 失败结果做短 TTL 负缓存，避免断网时反复重试。
 *
 * 该类的所有网络行为都经由 [transport]，因此可以用假传输层在 JVM 上完整验证：
 * "同一坐标连续打开 5 次只发一次请求"。
 */
class CachedGeoCoder(
    private val transport: GeoTransport,
    private val gridDegrees: Double = 0.01,
    private val ttlMs: Long = 30L * 24 * 60 * 60 * 1000,
    private val negativeTtlMs: Long = 10L * 60 * 1000,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    private data class Entry(val value: String?, val at: Long)

    private val cache = HashMap<String, Entry>()
    private val inflight = HashMap<String, CompletableDeferred<String?>>()
    private val mutex = Mutex()

    /** 真实发生的传输调用次数（观测/验收用）。 */
    val requestCount = AtomicInteger(0)

    fun cellKey(lat: Double, lon: Double): String {
        val kLat = Math.round(lat / gridDegrees) * gridDegrees
        val kLon = Math.round(lon / gridDegrees) * gridDegrees
        return String.format(java.util.Locale.US, "%.4f,%.4f", kLat, kLon)
    }

    suspend fun lookup(lat: Double, lon: Double): String? {
        val key = cellKey(lat, lon)

        var existing: CompletableDeferred<String?>? = null
        var owned: CompletableDeferred<String?>? = null

        mutex.withLock {
            val hit = cache[key]
            if (hit != null) {
                val ttl = if (hit.value != null) ttlMs else negativeTtlMs
                if (clock() - hit.at < ttl) return hit.value
                cache.remove(key)
            }
            val running = inflight[key]
            if (running != null) {
                existing = running
            } else {
                val d = CompletableDeferred<String?>()
                inflight[key] = d
                owned = d
            }
        }

        existing?.let { return it.await() }

        requestCount.incrementAndGet()
        val value = withContext(Dispatchers.IO) {
            runCatching { transport.fetch(lat, lon) }.getOrNull()
        }

        mutex.withLock {
            cache[key] = Entry(value, clock())
            inflight.remove(key)
        }
        owned?.complete(value)
        return value
    }
}

/**
 * 高德逆地理编码传输层。Key 留空时 [fetch] 直接返回 null，**不会发起任何请求**。
 * 只有用户在设置里打开"联网补充"且配置了 Key，才可能产生网络访问。
 */
class AmapTransport(
    private val apiKey: String,
    private val timeoutMs: Int = 5000,
) : GeoTransport {

    override fun fetch(lat: Double, lon: Double): String? {
        if (apiKey.isBlank()) return null
        val url = "${GeoWhitelist.BASE_URL}?location=" +
            String.format(java.util.Locale.US, "%.6f,%.6f", lon, lat) +
            "&key=$apiKey&extensions=base"
        var conn: java.net.HttpURLConnection? = null
        return try {
            conn = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
            }
            if (conn.responseCode != 200) return null
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            extractAddress(body)
        } catch (_: Exception) {
            null
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    /** 只取 regeocode.formatted_address，避免引入 JSON 库。 */
    private fun extractAddress(body: String): String? {
        val m = Regex("\"formatted_address\"\\s*:\\s*\"([^\"]*)\"").find(body) ?: return null
        val v = m.groupValues[1]
        return v.ifBlank { null }
    }
}
