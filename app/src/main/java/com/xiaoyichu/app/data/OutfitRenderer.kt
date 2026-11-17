package com.xiaoyichu.app.data

import android.content.Context
import android.graphics.*
import android.net.Uri
import androidx.core.content.FileProvider
import android.content.Intent
import android.content.ClipData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/** One renderer for the interactive canvas, detail preview and exported PNG. */
object OutfitRenderer {
    const val Background = 0xFFF8F4F1.toInt()
    fun drawPiece(canvas: Canvas, item: OutfitItem, image: Bitmap, width: Float, height: Float) {
        val rect = item.crop.pixels(image.width, image.height)
        val drawnWidth = item.width * width
        val drawnHeight = drawnWidth * item.imageRatio(image.width, image.height)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        paint.colorFilter = when (item.filter) {
            "mono" -> ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
            "warm" -> ColorMatrixColorFilter(ColorMatrix(floatArrayOf(
                1.04f,0f,0f,0f,4f, 0f,1f,0f,0f,1f, 0f,0f,.93f,0f,0f, 0f,0f,0f,1f,0f)))
            else -> null
        }
        canvas.save()
        canvas.rotate(item.rotation, item.x * width, item.y * height)
        canvas.drawBitmap(image, Rect(rect[0], rect[1], rect[0] + rect[2], rect[1] + rect[3]),
            RectF(item.x * width - drawnWidth / 2, item.y * height - drawnHeight / 2,
                item.x * width + drawnWidth / 2, item.y * height + drawnHeight / 2), paint)
        canvas.restore()
    }

    suspend fun render(context: Context, items: List<OutfitItem>, garments: List<Garment>, output: File,
        width: Int = 1080) = withContext(Dispatchers.IO) {
        require(items.isNotEmpty() && items.all { it.valid() } && width in 256..1440)
        val result = Bitmap.createBitmap(width, width * 5 / 4, Bitmap.Config.ARGB_8888)
        val staging = File(output.parentFile, "${output.name}.${UUID.randomUUID()}.tmp")
        try {
            val canvas = Canvas(result); canvas.drawColor(Background)
            // Decode one material at a time: memory use does not grow with the number of layers.
            items.sortedBy { it.zIndex }.forEach { item ->
                val garment = garments.find { it.id == item.garmentId } ?: error("搭配中的衣物暂不可用")
                val source = File(context.filesDir, garment.cutoutPath ?: garment.cropPath ?: garment.originalPath)
                val image = decodeThumbnail(source, 1280)
                try { drawPiece(canvas, item, image, result.width.toFloat(), result.height.toFloat()) }
                finally { image.recycle() }
            }
            output.parentFile!!.mkdirs()
            FileOutputStream(staging).use { stream ->
                check(result.compress(Bitmap.CompressFormat.PNG, 100, stream)); stream.fd.sync()
            }
            check(staging.renameTo(output)) { "图片写入失败" }
        } finally { staging.delete(); result.recycle() }
    }

    suspend fun export(context: Context, saved: SavedOutfit, garments: List<Garment>): File {
        val folder = File(context.cacheDir, "exports").apply { mkdirs() }
        // Leave recently shared files available while another app reads them.
        folder.listFiles()?.filter { it.name.matches(Regex("outfit-[a-f0-9-]+\\.png")) &&
            System.currentTimeMillis() - it.lastModified() > 24 * 60 * 60 * 1000L }?.forEach { it.delete() }
        return File(folder, "outfit-${UUID.randomUUID()}.png").also { render(context, saved.items, garments, it) }
    }

    suspend fun write(context: Context, source: File, uri: Uri) = withContext(Dispatchers.IO) {
        context.contentResolver.openOutputStream(uri, "wt")?.use { output -> source.inputStream().use { it.copyTo(output) } }
            ?: error("无法写入所选位置")
    }
    fun share(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.capture", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"; putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri("搭配图片", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "分享搭配图片"))
    }
}
