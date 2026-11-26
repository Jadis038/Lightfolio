package com.yingce.gallery

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.request.crossfade
import okio.Path.Companion.toOkioPath

/**
 * 刻意保持极轻：这里不做任何 IO、数据库初始化或后台任务。
 * 冷启动预算 3 秒内，Room 实例在首次访问时惰性创建。
 *
 * 只额外配置 Coil 的缓存：滚动时要复用已解码的缩略图，避免每帧重复解码。
 */
class YingceApp : Application(), SingletonImageLoader.Factory {

    override fun newImageLoader(context: PlatformContext): ImageLoader {
        return ImageLoader.Builder(context)
            .memoryCache {
                MemoryCache.Builder()
                    .maxSizePercent(context, 0.20)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache").toOkioPath())
                    .maxSizeBytes(256L * 1024 * 1024)
                    .build()
            }
            .crossfade(false)
            .build()
    }
}