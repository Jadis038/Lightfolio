package com.yingce.gallery.exif

import org.junit.Test
import java.io.File

/**
 * 用真实测试文件验证 EXIF/位置解析。
 * 文件位于桌面「测试」目录，文件名即用途备注。
 */
class RealFileExifDiagnosticTest {

    private fun dir(): File = File(System.getProperty("user.home") ?: "", "Desktop/测试")

    @Test
    fun dumpAll() {
        val d = dir()
        if (!d.exists()) {
            println("目录不存在: ${d.absolutePath}")
            return
        }
        val files = d.listFiles()?.filter { it.isFile }?.sortedBy { it.name } ?: return
        val sb = StringBuilder()
        sb.appendLine("file\tformat\tgpsLat\tgpsLon\tmake\tmodel\ttakenAt\tdims")
        for (f in files) {
            val r = runCatching { ExifReader.read(f) }
            r.onSuccess { e ->
                sb.appendLine(
                    listOf(
                        f.name,
                        e.formatLabel,
                        e.gpsLat?.toString() ?: "null",
                        e.gpsLon?.toString() ?: "null",
                        e.make ?: "-",
                        e.model ?: "-",
                        e.takenAt ?: "-",
                        "${e.width}x${e.height}",
                    ).joinToString("\t"),
                )
            }.onFailure { t ->
                sb.appendLine("${f.name}\tERROR\t${t.javaClass.simpleName}: ${t.message}")
            }
        }
        println(sb)
    }
}
