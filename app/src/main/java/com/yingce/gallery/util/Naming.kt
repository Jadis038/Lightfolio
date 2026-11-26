package com.yingce.gallery.util

/**
 * 重名规则（与界面显示规则分离的核心）。
 *
 * 约定：
 * - [candidate] 只负责“第 n 次落盘该叫什么”，第 0 次就是原名，第 1 次是 "名称 (1).jpg"。
 * - [resolve] 在给定“是否已存在”的判据下，找到第一个未被占用的落盘名。
 * - **绝不**从落盘名反推显示名，也绝不用正则剥掉 "(1)" 之类的后缀：
 *   源文件本身就可能叫 "photo (1).jpg"，剥后缀会把源名改掉。
 */
object Naming {

    /** 把文件名拆成 (基名, 扩展名含点)。".hidden" 视为无扩展名。 */
    fun splitExt(name: String): Pair<String, String> {
        val i = name.lastIndexOf('.')
        return if (i <= 0) name to "" else name.substring(0, i) to name.substring(i)
    }

    /**
     * 第 n 个候选落盘名。n = 0 返回原名（不做任何改写）。
     */
    fun candidate(displayName: String, n: Int): String {
        if (n == 0) return displayName
        val (base, ext) = splitExt(displayName)
        return "$base ($n)$ext"
    }

    /**
     * 依次尝试 candidate(0), candidate(1), ... 直到 [exists] 返回 false。
     */
    fun resolve(displayName: String, exists: (String) -> Boolean): String {
        var n = 0
        while (true) {
            val name = candidate(displayName, n)
            if (!exists(name)) return name
            n++
            if (n > MAX_ATTEMPTS) {
                throw IllegalStateException("重名候选超过 $MAX_ATTEMPTS 个: $displayName")
            }
        }
    }

    private const val MAX_ATTEMPTS = 100_000
}
