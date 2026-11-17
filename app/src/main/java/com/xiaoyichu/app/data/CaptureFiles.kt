package com.xiaoyichu.app.data

import android.content.Context
import java.io.File
import java.io.IOException
import java.util.UUID

/** Only this directory is exposed to the delegated camera through FileProvider. */
class CaptureFiles(context: Context) {
    private val folder = File(context.cacheDir, "captures")
    private val validName = Regex("capture-[a-f0-9-]{36}\\.jpg")

    fun find(name: String?): File? = name?.takeIf { validName.matches(it) }?.let { File(folder, it) }

    fun create(): File {
        if (!folder.isDirectory && !folder.mkdirs()) throw IOException("无法准备拍照空间")
        // Interrupted camera sessions are not drafts. Keep recent files in case the camera is still writing.
        val cutoff = System.currentTimeMillis() - 24 * 60 * 60 * 1000L
        folder.listFiles()?.filter { validName.matches(it.name) && it.lastModified() < cutoff }?.forEach { it.delete() }
        return File(folder, "capture-${UUID.randomUUID()}.jpg").also {
            if (!it.createNewFile()) throw IOException("无法创建照片文件")
        }
    }
}
