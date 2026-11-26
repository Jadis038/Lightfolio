package com.yingce.gallery.raw

/**
 * RAW 方向修正。
 *
 * LibRaw 不会自动旋转，`imgdata.sizes.flip` 只是元数据（dcraw 约定）：
 *   0 = 不变；1 = 水平镜像；2 = 垂直镜像；3 = 180°
 *   5 = 逆时针 90°（ExifTool 显示 "Rotate 270 CW"）
 *   6 = 顺时针 90°（ExifTool 显示 "Rotate 90 CW"）
 * 与 ExifTool 的对照实测：
 *   Apple_iPhone_12_Pro.dng flip=6 ↔ Orientation "Rotate 90 CW"
 *   Kodak_DCS_Pro.dcr       flip=5 ↔ Orientation "Rotate 270 CW"
 *
 * 注意：旋转必须在**整幅**上做。如果以后改成按块解码，每块都要带上完整图像的
 * 方向信息（否则拼起来会是转错的）。
 */
object RawOrientation {

    data class Result(val pixels: IntArray, val width: Int, val height: Int)

    fun apply(pixels: IntArray, width: Int, height: Int, flip: Int): Result {
        if (width <= 0 || height <= 0 || pixels.size < width * height) {
            return Result(pixels, width, height)
        }
        return when (flip) {
            3, 4 -> {
                // 180°
                val out = IntArray(pixels.size)
                for (i in pixels.indices) out[i] = pixels[pixels.size - 1 - i]
                Result(out, width, height)
            }
            1 -> {
                // 水平镜像
                val out = IntArray(pixels.size)
                for (y in 0 until height) {
                    for (x in 0 until width) {
                        out[y * width + x] = pixels[y * width + (width - 1 - x)]
                    }
                }
                Result(out, width, height)
            }
            2 -> {
                // 垂直镜像
                val out = IntArray(pixels.size)
                for (y in 0 until height) {
                    for (x in 0 until width) {
                        out[y * width + x] = pixels[(height - 1 - y) * width + x]
                    }
                }
                Result(out, width, height)
            }
            5 -> rotate90(pixels, width, height, clockwise = false)
            6 -> rotate90(pixels, width, height, clockwise = true)
            else -> Result(pixels, width, height)
        }
    }

    /**
     * 对已解码的 Bitmap 应用同一套方向（用于内嵌预览回退路径）。
     * 之前只有像素数组那条路做了旋转，缩略图回退时就露馅了 —— 尼康 NEF 的预览会横着。
     */
    fun applyToBitmap(bitmap: android.graphics.Bitmap, flip: Int): android.graphics.Bitmap {
        val degrees = when (flip) {
            6 -> 90f
            5 -> 270f
            3, 4 -> 180f
            else -> 0f
        }
        if (degrees == 0f) return bitmap
        return runCatching {
            val m = android.graphics.Matrix()
            m.postRotate(degrees)
            val rotated = android.graphics.Bitmap.createBitmap(
                bitmap, 0, 0, bitmap.width, bitmap.height, m, true,
            )
            if (rotated !== bitmap) bitmap.recycle()
            rotated
        }.getOrDefault(bitmap)
    }

    private fun rotate90(pixels: IntArray, width: Int, height: Int, clockwise: Boolean): Result {
        val newW = height
        val newH = width
        val out = IntArray(pixels.size)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val value = pixels[y * width + x]
                if (clockwise) {
                    // 顺时针：新(x'=height-1-y, y'=x)
                    val nx = height - 1 - y
                    val ny = x
                    out[ny * newW + nx] = value
                } else {
                    // 逆时针：新(x'=y, y'=width-1-x)
                    val nx = y
                    val ny = width - 1 - x
                    out[ny * newW + nx] = value
                }
            }
        }
        return Result(out, newW, newH)
    }
}
