package com.yingce.gallery.data

import android.content.Context
import android.content.pm.PackageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * 更新检查与下载。
 *
 * **这是全项目唯一会发起网络请求的地方。**
 * - 只访问 `api.github.com` 与本仓库 Release 资产的下载地址
 * - 不携带任何用户标识、不上报任何数据；请求头只有必要的 User-Agent / Accept
 * - 用户可在「应用信息」里关闭自动检查，关闭后 App 完全不联网
 */
object UpdateChecker {

    const val REPO = "Jadis038/Lightfolio"

    /**
     * 首选更新源：仓库里的静态 JSON（走 raw CDN，**没有 API 配额限制**，匿名可读）。
     * 之前直连 api.github.com 会被限流返回 403，所以主路径改成读这个文件。
     *
     * 注意：raw CDN 有约 5 分钟缓存，直接取会读到旧版本号。
     * 这里追加时间戳参数绕过缓存，保证发版后立刻可见。
     */
    private fun rawVersionUrl(): String {
        val ts = System.currentTimeMillis() / 1000
        return "https://raw.githubusercontent.com/$REPO/main/version.json?t=$ts"
    }

    /** 兜底更新源：GitHub Releases API（匿名每小时 60 次，可能 403）。 */
    private const val API_LATEST = "https://api.github.com/repos/$REPO/releases/latest"

    data class UpdateInfo(
        /** 远端版本名，例如 "V1.50" */
        val versionName: String,
        /** 更新说明（Release body） */
        val notes: String,
        /** APK 下载地址 */
        val apkUrl: String,
        /** 文件大小（字节），未知为 0 */
        val sizeBytes: Long,
        /** Release 说明里声明的 SHA-256（若有） */
        val sha256: String?,
    )

    /** 版本号比较：抽取出所有数字段，逐段比大小（1.50 > 1.42）。 */
    object Versions {
        fun parts(v: String): List<Int> =
            Regex("\\d+").findAll(v).mapNotNull { it.value.toIntOrNull() }.toList()

        fun isNewer(remote: String, local: String): Boolean {
            val a = parts(remote)
            val b = parts(local)
            if (a.isEmpty()) return false
            val n = maxOf(a.size, b.size)
            for (i in 0 until n) {
                val x = a.getOrElse(i) { 0 }
                val y = b.getOrElse(i) { 0 }
                if (x != y) return x > y
            }
            return false
        }
    }

    fun localVersionName(context: Context): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull() ?: "V0.0"

    /**
     * 检查更新。返回 null 表示"已是最新"。
     * 失败（无网络/DNS/超时）时抛异常，由调用方决定是否提示。
     *
     * 顺序：先读 version.json（CDN，无限流），失败再退回 Releases API。
     */
    suspend fun check(context: Context): UpdateInfo? = withContext(Dispatchers.IO) {
        val local = localVersionName(context)
        val fromJson = runCatching { checkViaJson(local) }.getOrNull()
        if (fromJson != null) return@withContext fromJson
        // version.json 拿不到时再试 API；若 API 也失败，抛最后的异常
        checkViaApi(context, local)
    }

    private fun checkViaJson(local: String): UpdateInfo? {
        val obj = JSONObject(httpGet(rawVersionUrl()))
        val remote = obj.optString("version", "")
        if (remote.isBlank()) return null
        if (!Versions.isNewer(remote, local)) return null
        val apkUrl = obj.optString("apkUrl", "")
        if (apkUrl.isBlank()) return null
        return UpdateInfo(
            versionName = remote,
            notes = obj.optString("notes", "").trim(),
            apkUrl = apkUrl,
            sizeBytes = obj.optLong("sizeBytes", 0L),
            sha256 = obj.optString("sha256", "").ifBlank { null },
        )
    }

    private fun checkViaApi(context: Context, local: String): UpdateInfo? {
        val json = httpGet(API_LATEST)
        val obj = JSONObject(json)
        val tag = obj.optString("tag_name", "")
        val body = obj.optString("body", "")
        if (tag.isBlank()) return null

        if (!Versions.isNewer(tag, local)) return null

        val assets = obj.optJSONArray("assets")
        var apkUrl = ""
        var size = 0L
        if (assets != null) {
            for (i in 0 until assets.length()) {
                val a = assets.optJSONObject(i) ?: continue
                val name = a.optString("name", "")
                if (name.endsWith(".apk", ignoreCase = true)) {
                    apkUrl = a.optString("browser_download_url", "")
                    size = a.optLong("size", 0L)
                    break
                }
            }
        }
        if (apkUrl.isBlank()) return null // 该 Release 没有附带安装包，视为无更新

        return UpdateInfo(
            versionName = tag,
            notes = body.trim(),
            apkUrl = apkUrl,
            sizeBytes = size,
            sha256 = extractSha256(body),
        )
    }

    private fun extractSha256(body: String): String? =
        Regex("(?i)sha-?256[^0-9a-f]{0,8}([0-9a-f]{64})").find(body)?.groupValues?.getOrNull(1)

    private fun httpGet(url: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 8000
            readTimeout = 12000
            setRequestProperty("User-Agent", "Lightfolio-Updater")
            setRequestProperty("Accept", "application/vnd.github+json")
        }
        try {
            val code = conn.responseCode
            if (code != 200) error("HTTP $code")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    /**
     * 下载 APK 到 cache/updates/。返回下载好的文件。
     * [onProgress] 回调 (已下载字节, 总字节)，总字节未知时为 0。
     */
    suspend fun download(
        context: Context,
        info: UpdateInfo,
        onProgress: (Long, Long) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        val target = File(dir, "yingce-${info.versionName.replace(Regex("[^A-Za-z0-9._-]"), "_")}.apk")
        if (target.exists()) target.delete()

        val conn = (URL(info.apkUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10000
            readTimeout = 20000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Lightfolio-Updater")
            setRequestProperty("Accept", "application/octet-stream")
        }
        try {
            val code = conn.responseCode
            if (code != 200) error("HTTP $code")
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: info.sizeBytes
            var done = 0L
            conn.inputStream.use { input ->
                target.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                        done += n
                        onProgress(done, total)
                    }
                }
            }
        } finally {
            conn.disconnect()
        }

        // 校验：Release 说明里写了 SHA-256 就核对，不一致直接删掉并报错
        val expected = info.sha256
        if (!expected.isNullOrBlank()) {
            val actual = sha256Of(target)
            if (!actual.equals(expected, ignoreCase = true)) {
                target.delete()
                error("校验失败：下载的文件与发布说明中的 SHA-256 不一致")
            }
        }
        target
    }

    fun sha256Of(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02X".format(it) }
    }

    fun hasInstallPermission(context: Context): Boolean = runCatching {
        context.packageManager.canRequestPackageInstalls()
    }.getOrDefault(true)

    fun packageInstalled(context: Context, pkg: String): Boolean = runCatching {
        context.packageManager.getPackageInfo(pkg, 0)
        true
    }.getOrDefault(false)

    @Suppress("unused")
    private fun manager(context: Context): PackageManager = context.packageManager
}

/** 自动检查开关（默认开启）。关闭后 App 完全不联网。 */
object UpdatePrefs {
    private const val FILE = "yingce_update"
    private const val KEY_AUTO = "auto_check"

    fun isAutoCheckEnabled(context: Context): Boolean =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(KEY_AUTO, true)

    fun setAutoCheckEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean(KEY_AUTO, enabled).apply()
    }
}
