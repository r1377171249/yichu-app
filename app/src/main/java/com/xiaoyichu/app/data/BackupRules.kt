package com.xiaoyichu.app.data

object BackupRules {
    const val MaxImageBytes = 50L * 1024 * 1024
    const val MaxTotalBytes = 1024L * 1024 * 1024
    const val MaxManifestBytes = 8 * 1024 * 1024
    const val MaxFiles = 5000
    fun imagePath(path: String): Boolean = path.length <= 160 &&
        path.matches(Regex("(garments|thumbnails|cutouts|crops)/[A-Za-z0-9_-]+\\.(original|png|jpg|jpeg|webp|heic)"))
    fun validSize(size: Long) = size in 1..MaxImageBytes
    fun validHash(hash: String) = hash.matches(Regex("[a-f0-9]{64}"))
    fun validId(id: String) = id.length in 1..100 && id.none { it.isISOControl() }
}
