package com.yingce.gallery.raw

/**
 * LibRaw 的 JNI 门面。所有原生调用都在这里，上层不直接碰 JNI。
 *
 * 画质口径（本轮硬要求）：不做 half_size、不做缩放；16bit 输出、AHD 去马赛克、
 * 相机白平衡、高光恢复不裁切。区域解码用 LibRaw 的 cropbox，只处理视口那块。
 */
class RawImage private constructor(private var handle: Long) : AutoCloseable {

    val rawWidth: Int
    val rawHeight: Int
    val width: Int
    val height: Int
    val colors: Int
    val thumbFormat: Int
    val thumbBytes: Int
    val flip: Int
    val blackLevel: Int
    val maximum: Int

    init {
        val info = RawNative.info(handle) ?: IntArray(10)
        rawWidth = if (info.size > 0) info[0] else 0
        rawHeight = if (info.size > 1) info[1] else 0
        width = if (info.size > 2) info[2] else 0
        height = if (info.size > 3) info[3] else 0
        colors = if (info.size > 4) info[4] else 0
        thumbFormat = if (info.size > 5) info[5] else 0
        thumbBytes = if (info.size > 6) info[6] else 0
        flip = if (info.size > 7) info[7] else 0
        blackLevel = if (info.size > 8) info[8] else 0
        maximum = if (info.size > 9) info[9] else 0
    }

    val hasThumbnail: Boolean get() = thumbBytes > 0 && (thumbFormat == 1 || thumbFormat == 0)

    fun cameraInfo(): String = RawNative.cameraInfo(handle) ?: ""

    fun make(): String = cameraInfo().split('|').getOrElse(0) { "" }

    fun model(): String = cameraInfo().split('|').getOrElse(1) { "" }

    fun thumbnail(): ByteArray? = RawNative.thumb(handle)

    /**
     * 解码一块区域，返回 ARGB_8888（[w]×[h]）。
     * [bps] 建议 8（显示用）；16 会更慢但保留更多高光层次。
     */
    fun decodeRegion(x: Int, y: Int, w: Int, h: Int, bps: Int = 8, halfSize: Boolean = false): IntArray? =
        RawNative.decodeRegion(handle, x, y, w, h, bps, if (halfSize) 1 else 0)

    override fun close() {
        if (handle != 0L) {
            RawNative.close(handle)
            handle = 0L
        }
    }

    companion object {
        @Volatile
        private var loaded = false

        fun ensureLoaded(): Boolean {
            if (loaded) return true
            return synchronized(this) {
                if (loaded) return true
                loaded = runCatching { System.loadLibrary("raw") }.isSuccess
                loaded
            }
        }

        fun open(path: String): RawImage? {
            if (!ensureLoaded()) return null
            val h = RawNative.open(path)
            return if (h == 0L) null else RawImage(h)
        }
    }
}

internal object RawNative {
    external fun open(path: String): Long
    external fun info(handle: Long): IntArray?
    external fun cameraInfo(handle: Long): String?
    external fun thumb(handle: Long): ByteArray?
    external fun decodeRegion(handle: Long, x: Int, y: Int, w: Int, h: Int, bps: Int, half: Int): IntArray?
    external fun close(handle: Long)
}
