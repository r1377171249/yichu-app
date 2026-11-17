package com.xiaoyichu.app.data

import java.io.File

data class DraftCleanupResult(val deletedFiles: Int, val reclaimedBytes: Long)

/** Only abandoned, generated draft groups expire. Never walk saved image directories. */
object DraftCleanup {
    const val RetentionMillis = 7L * 24 * 60 * 60 * 1000
    private const val uuid = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"
    private val original = Regex("($uuid)\\.(?:original|png)")
    private val processed = Regex("($uuid)-$uuid-(?:cutout|thumb|crop|crop-thumb|repaired|repaired-thumb)\\.png")

    fun owner(name: String): String? = (original.matchEntire(name) ?: processed.matchEntire(name))?.groupValues?.get(1)

    fun prune(directory: File, activeId: String?, now: Long = System.currentTimeMillis()): DraftCleanupResult {
        if (!directory.isDirectory || now <= RetentionMillis) return DraftCleanupResult(0, 0)
        val root = directory.canonicalFile
        // Refuse a symlinked directory, symlinked entries, nested directories or unknown names.
        // Android may alias /data/user/0 to /data/data. Resolve trusted ancestors first,
        // then reject a link at the directory itself rather than rejecting that OS alias.
        if (root != File(directory.parentFile.canonicalFile, directory.name)) return DraftCleanupResult(0, 0)
        val groups = directory.listFiles().orEmpty().mapNotNull { file ->
            val id = owner(file.name) ?: return@mapNotNull null
            if (!file.isFile || file.canonicalFile != File(root, file.name)) null else id to file
        }.groupBy({ it.first }, { it.second })
        var deleted = 0; var bytes = 0L
        val cutoff = now - RetentionMillis
        groups.forEach { (id, files) ->
            if (id == activeId || files.any { it.lastModified() <= 0 || it.lastModified() >= cutoff }) return@forEach
            files.forEach { file ->
                val size = file.length()
                if (file.canonicalFile.parentFile == root && file.lastModified() in 1 until cutoff && file.delete()) {
                    deleted++; bytes += size
                }
            }
        }
        return DraftCleanupResult(deleted, bytes)
    }
}
