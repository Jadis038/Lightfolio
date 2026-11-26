package com.yingce.gallery.geo

import android.content.Context
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** 行政区查询结果。乡镇级是否可用取决于是否内置了第四级数据（见 M4 方案）。 */
data class AdminPlace(
    val province: String?,
    val city: String?,
    val county: String?,
    val township: String?,
    val adcode: Int?,
    val source: String,
) {
    /** 形如 "北京市 · 东城区"，自动去掉与省级重名的市级（直辖市）。 */
    fun displayPath(): String {
        val parts = ArrayList<String>(4)
        province?.let { parts += it }
        city?.takeIf { it != province }?.let { parts += it }
        county?.takeIf { it != city }?.let { parts += it }
        township?.let { parts += it }
        return if (parts.isEmpty()) "" else parts.joinToString(" · ")
    }
}

/**
 * 内置离线行政区划索引（assets/admin_cn.bin）。
 *
 * 文件格式（大端，Java DataOutputStream 默认）：
 *   [0,64) 头：magic "YCG1" | version u16 | count u32 | namesLen u32 | geomLen u32 | scale u32
 *   [64, 64+count*44) 分区表：
 *       adcode u32 | parent u32 | level u8 | ringCount u8 | pad u16
 *       nameOff u32 | nameLen u16 | pad u16
 *       minx i32 | miny i32 | maxx i32 | maxy i32 | geomOff u32
 *   名字区：UTF-8
 *   几何区：每区 ringCount 个环；环 = varint(点数) + 逐点 zigzag varint(增量)
 *
 * 定位：只对"区县"级做点在多边形内判定（bbox 先过滤），命中后沿父链取市、省。
 */
class AdminGeoIndex private constructor(
    private val raf: RandomAccessFile,
) : AutoCloseable {

    private var count = 0
    private var scale = 10000
    private var namesStart = 0L
    private var geomStart = 0L

    private var adcodes = IntArray(0)
    private var parents = IntArray(0)
    private var levels = IntArray(0)
    private var ringCounts = IntArray(0)
    private var nameOffs = IntArray(0)
    private var nameLens = IntArray(0)
    private var minx = IntArray(0)
    private var miny = IntArray(0)
    private var maxx = IntArray(0)
    private var maxy = IntArray(0)
    private var geomOffs = IntArray(0)

    private val indexByAdcode = HashMap<Int, Int>()
    private val nameCache = HashMap<Int, String>()

    val isReady: Boolean get() = count > 0

    private fun load() {
        val head = ByteArray(64)
        raf.seek(0)
        raf.readFully(head)
        require(String(head, 0, 4, Charsets.US_ASCII) == "YCG1") { "admin_cn.bin magic 不匹配" }
        val bb = ByteBuffer.wrap(head).order(ByteOrder.BIG_ENDIAN)
        bb.position(4)
        bb.short
        count = bb.int
        val namesLen = bb.int
        bb.int // geomLen（当前实现按需读，不整体载入）
        scale = bb.int

        adcodes = IntArray(count); parents = IntArray(count); levels = IntArray(count)
        ringCounts = IntArray(count); nameOffs = IntArray(count); nameLens = IntArray(count)
        minx = IntArray(count); miny = IntArray(count); maxx = IntArray(count); maxy = IntArray(count)
        geomOffs = IntArray(count)

        val table = ByteArray(count * RECORD)
        raf.seek(64)
        raf.readFully(table)
        val tb = ByteBuffer.wrap(table).order(ByteOrder.BIG_ENDIAN)
        for (i in 0 until count) {
            val b = i * RECORD
            adcodes[i] = tb.getInt(b)
            parents[i] = tb.getInt(b + 4)
            levels[i] = tb.get(b + 8).toInt()
            ringCounts[i] = tb.get(b + 9).toInt() and 0xFF
            nameOffs[i] = tb.getInt(b + 12)
            nameLens[i] = tb.getShort(b + 16).toInt() and 0xFFFF
            minx[i] = tb.getInt(b + 20)
            miny[i] = tb.getInt(b + 24)
            maxx[i] = tb.getInt(b + 28)
            maxy[i] = tb.getInt(b + 32)
            geomOffs[i] = tb.getInt(b + 36)
            indexByAdcode[adcodes[i]] = i
        }
        namesStart = 64L + count.toLong() * RECORD
        geomStart = namesStart + namesLen
    }

    private fun nameOf(i: Int): String = nameCache.getOrPut(i) {
        val b = ByteArray(nameLens[i])
        raf.seek(namesStart + nameOffs[i])
        raf.readFully(b)
        String(b, Charsets.UTF_8)
    }

    /** 从几何区按需读字节的游标。 */
    private inner class Cursor(start: Long) {
        private val buf = ByteArray(32 * 1024)
        private var pos = 0
        private var len = 0
        private var filePos = start

        fun varint(): Int {
            var result = 0
            var shift = 0
            while (true) {
                if (pos >= len) fill()
                val b = buf[pos++].toInt() and 0xFF
                result = result or ((b and 0x7F) shl shift)
                if (b and 0x80 == 0) break
                shift += 7
                if (shift > 35) break
            }
            return result
        }

        private fun fill() {
            pos = 0
            len = 0
            raf.seek(filePos)
            val r = raf.read(buf, 0, buf.size)
            if (r <= 0) throw IllegalStateException("几何数据 EOF")
            len = r
            filePos += r
        }
    }

    private val unzigzag: (Int) -> Int = { v -> (v ushr 1) xor -(v and 1) }

    /** 只判断环的内部，不做洞的减法（政区飞地极少，误差可接受，已在文档说明）。 */
    private fun contains(idx: Int, lon: Double, lat: Double): Boolean {
        return try {
            val cur = Cursor(geomStart + geomOffs[idx])
            for (r in 0 until ringCounts[idx]) {
                val n = cur.varint()
                if (n <= 2) {
                    repeat(n) { cur.varint(); cur.varint() }
                    continue
                }
                val xs = DoubleArray(n)
                val ys = DoubleArray(n)
                var px = 0
                var py = 0
                for (k in 0 until n) {
                    px += unzigzag(cur.varint())
                    py += unzigzag(cur.varint())
                    xs[k] = px.toDouble() / scale
                    ys[k] = py.toDouble() / scale
                }
                if (pointInRing(xs, ys, lon, lat)) return true
            }
            false
        } catch (_: Exception) {
            false
        }
    }

    private fun pointInRing(xs: DoubleArray, ys: DoubleArray, lon: Double, lat: Double): Boolean {
        var inside = false
        var j = xs.size - 1
        for (i in xs.indices) {
            if (((ys[i] > lat) != (ys[j] > lat)) &&
                (lon < (xs[j] - xs[i]) * (lat - ys[i]) / (ys[j] - ys[i]) + xs[i])
            ) {
                inside = !inside
            }
            j = i
        }
        return inside
    }

    @Synchronized
    fun resolve(lat: Double, lon: Double): AdminPlace? {
        if (!isReady) return null
        val sLon = (lon * scale).toInt()
        val sLat = (lat * scale).toInt()

        var hit = -1
        for (level in 2 downTo 0) {
            for (i in 0 until count) {
                if (levels[i] != level) continue
                if (sLon < minx[i] || sLon > maxx[i] || sLat < miny[i] || sLat > maxy[i]) continue
                if (contains(i, lon, lat)) { hit = i; break }
            }
            if (hit >= 0) break
        }
        if (hit < 0) return null

        var countyIdx = -1
        var cityIdx = -1
        var provIdx = -1

        var node = hit
        when (levels[hit]) {
            2 -> countyIdx = hit
            1 -> cityIdx = hit
            0 -> provIdx = hit
        }
        // 沿父链补齐
        var guard = 0
        while (levels[node] != 0 && guard++ < 8) {
            val p = indexByAdcode[parents[node]] ?: break
            node = p
            if (levels[node] == 1 && cityIdx < 0) cityIdx = node
            if (levels[node] == 0) provIdx = node
        }

        fun nm(i: Int): String? = if (i >= 0) nameOf(i) else null
        return AdminPlace(
            province = nm(provIdx),
            city = nm(cityIdx),
            county = nm(countyIdx),
            township = null,
            adcode = adcodes[hit],
            source = "OFFLINE",
        )
    }

    override fun close() {
        runCatching { raf.close() }
    }

    companion object {
        private const val RECORD = 44

        @Volatile
        private var instance: AdminGeoIndex? = null

        /** 资产需不压缩（build.gradle 的 noCompress "bin"），否则无法按偏移随机读。 */
        fun get(context: Context): AdminGeoIndex {
            instance?.let { return it }
            synchronized(this) {
                instance?.let { return it }
                val copy = File(context.filesDir, "admin_cn.bin")
                val assetLen = runCatching {
                    context.assets.openFd("admin_cn.bin").use { it.length }
                }.getOrDefault(-1L)
                val needsCopy = !copy.exists() || copy.length() < 1024 ||
                    (assetLen > 0 && copy.length() != assetLen)
                if (needsCopy) {
                    context.assets.open("admin_cn.bin").use { input ->
                        copy.outputStream().use { output -> input.copyTo(output, 256 * 1024) }
                    }
                }
                val idx = AdminGeoIndex(RandomAccessFile(copy, "r"))
                idx.load()
                instance = idx
                return idx
            }
        }

        /** 测试用：直接从文件构造。 */
        fun open(file: File): AdminGeoIndex {
            val idx = AdminGeoIndex(RandomAccessFile(file, "r"))
            idx.load()
            return idx
        }
    }
}

/** 经纬度文本：十进制 + 度分秒 + 半球方向。 */
object GeoText {

    fun decimal(lat: Double, lon: Double): String {
        val ns = if (lat >= 0) "N" else "S"
        val ew = if (lon >= 0) "E" else "W"
        return String.format(
            java.util.Locale.US, "%.6f° %s, %.6f° %s",
            kotlin.math.abs(lat), ns, kotlin.math.abs(lon), ew,
        )
    }

    fun dms(lat: Double, lon: Double): String = dmsPart(lat, true) + "  " + dmsPart(lon, false)

    private fun dmsPart(v: Double, isLat: Boolean): String {
        val abs = kotlin.math.abs(v)
        val d = abs.toInt()
        val minutesFull = (abs - d) * 60.0
        val m = minutesFull.toInt()
        val s = (minutesFull - m) * 60.0
        val hemi = if (isLat) (if (v >= 0) "N" else "S") else (if (v >= 0) "E" else "W")
        return String.format(java.util.Locale.US, "%d°%d'%.2f\"%s", d, m, s, hemi)
    }
}
