package com.xiaoyichu.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import androidx.exifinterface.media.ExifInterface
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID
import kotlin.math.max

val GarmentCategories = listOf("上衣", "裤装", "裙装", "外套", "鞋子", "包包", "配饰")

data class DraftPhoto(
    val id: String, val original: File, val thumbnail: File,
    val cutout: File? = null, val cutoutThumbnail: File? = null,
    val useCutout: Boolean = false, val cutoutError: Boolean = false,
    val cropped: File? = null, val croppedThumbnail: File? = null,
    val cropBounds: CropBounds = CropBounds.Full,
    val suggestedName:String = "",
    val suggestedCategory:String? = null,
    val favoriteOnSave:Boolean = false,
    val color:String="",val season:String="",val occasion:String="",val cutoutMode:String="保留细节"
) {
    val backgroundPreview: File get() = croppedThumbnail ?: thumbnail
    val preview: File get() = if (useCutout) cutoutThumbnail ?: backgroundPreview else backgroundPreview
}

class GarmentRepository(private val context: Context) {
    private val db = WardrobeDatabase.get(context)
    private val dao = db.garments()
    val garments = dao.observeAll()
    fun original(garment:Garment)=File(context.filesDir,garment.originalPath)

    suspend fun pruneAbandonedDrafts(activeId: String?) = withContext(Dispatchers.IO) {
        listOf(File(context.filesDir, "drafts/imports"), File(context.cacheDir, "imports")).forEach { folder ->
            // Cleanup is best effort. An unreadable directory must not prevent recovering a draft.
            try { DraftCleanup.prune(folder, activeId) } catch (_: java.io.IOException) { }
        }
    }

    suspend fun prepare(uri: Uri, batch:Boolean=false): DraftPhoto = withContext(Dispatchers.IO) {
        val folder = File(context.filesDir, if(batch)"batch/imports" else "drafts/imports").apply { check(mkdirs() || isDirectory) }
        val id = UUID.randomUUID().toString()
        val original = File(folder, "$id.original")
        val thumbnail = File(folder, "$id.png")
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(original).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > 50L * 1024 * 1024) throw IOException("照片超过 50 MB，请选择较小的图片")
                        output.write(buffer, 0, count)
                    }
                    output.fd.sync()
                }
            } ?: throw IOException("无法读取这张照片，请重新选择")
            val bitmap = decodeThumbnail(original)
            try {
                FileOutputStream(thumbnail).use { output ->
                    check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                    output.fd.sync()
                }
            } finally { bitmap.recycle() }
            DraftPhoto(id, original, thumbnail)
        } catch (error: Exception) {
            original.delete()
            thumbnail.delete()
            throw error
        }
    }

    suspend fun cutout(photo: DraftPhoto): DraftPhoto = withContext(Dispatchers.IO) {
        // Each attempt uses fresh filenames so Compose does not reuse a stale decoded preview.
        val attempt = UUID.randomUUID().toString()
        val output = File(photo.original.parentFile, "${photo.id}-$attempt-cutout.png")
        val thumbnail = File(photo.original.parentFile, "${photo.id}-$attempt-thumb.png")
        try {
            val source = decodeThumbnail(photo.cropped ?: photo.original, 1280)
            val result = try { AutomaticCutout.get(context).removeBackground(source,photo.cutoutMode) } finally { source.recycle() }
            try { writePng(result, output) } finally { result.recycle() }
            val small = decodeThumbnail(output)
            try { writePng(small, thumbnail) } finally { small.recycle() }
            photo.copy(cutout = output, cutoutThumbnail = thumbnail, useCutout = true, cutoutError = false)
        } catch (error: Exception) {
            output.delete()
            thumbnail.delete()
            throw error
        }
    }

    suspend fun repair(photo:DraftPhoto,strokes:List<RepairStroke>):DraftPhoto = withContext(Dispatchers.IO) {
        require(strokes.isNotEmpty() && strokes.all{it.valid()})
        val cutout=requireNotNull(photo.cutout)
        val source=decodeThumbnail(photo.cropped ?: photo.original,1280)
        val base=try { decodeThumbnail(cutout,1280) } catch(error:Throwable) { source.recycle();throw error }
        val result=try {
            require(source.width==base.width && source.height==base.height)
            val originals=IntArray(source.width*source.height);val current=IntArray(originals.size)
            source.getPixels(originals,0,source.width,0,0,source.width,source.height)
            base.getPixels(current,0,base.width,0,0,base.width,base.height)
            val pixels=RepairRaster.apply(originals,current,source.width,source.height,strokes)
            Bitmap.createBitmap(pixels,base.width,base.height,Bitmap.Config.ARGB_8888)
        } finally { source.recycle();base.recycle() }
        val attempt=UUID.randomUUID().toString()
        val output=File(photo.original.parentFile,"${photo.id}-$attempt-repaired.png")
        val thumb=File(photo.original.parentFile,"${photo.id}-$attempt-repaired-thumb.png")
        try {
            try { writePng(result,output) } finally { result.recycle() }
            val small=decodeThumbnail(output);try {writePng(small,thumb)}finally{small.recycle()}
            photo.copy(cutout=output,cutoutThumbnail=thumb,useCutout=true,cutoutError=false)
        } catch(error:Throwable){output.delete();thumb.delete();throw error}
    }

    suspend fun crop(photo: DraftPhoto, bounds: CropBounds): DraftPhoto = withContext(Dispatchers.IO) {
        var cropped: File? = null
        var thumbnail: File? = null
        try {
            if (bounds != CropBounds.Full) {
                val attempt = UUID.randomUUID().toString()
                cropped = File(photo.original.parentFile, "${photo.id}-$attempt-crop.png")
                thumbnail = File(photo.original.parentFile, "${photo.id}-$attempt-crop-thumb.png")
                val source = decodeThumbnail(photo.original, 1280)
                try {
                    val rect = bounds.pixels(source.width, source.height)
                    val result = Bitmap.createBitmap(source, rect[0], rect[1], rect[2], rect[3])
                    try { writePng(result, cropped) } finally { if (result !== source) result.recycle() }
                } finally { source.recycle() }
                val small = decodeThumbnail(cropped)
                try { writePng(small, thumbnail) } finally { small.recycle() }
            }
        } catch (error: Exception) {
            cropped?.delete()
            thumbnail?.delete()
            throw error
        }
        // Previous files remain until the new recovery checkpoint is durable.
        photo.copy(cropped = cropped, croppedThumbnail = thumbnail, cropBounds = bounds,
            cutout = null, cutoutThumbnail = null, useCutout = false, cutoutError = false)
    }

    suspend fun discard(photo: DraftPhoto) = withContext(NonCancellable + Dispatchers.IO) {
        draftFiles(photo).forEach { it.delete() }
        Unit
    }

    private fun draftFiles(photo: DraftPhoto): List<File> {
        val folder = photo.original.canonicalFile.parentFile!!
        require(folder in listOf(File(context.filesDir,"drafts/imports").canonicalFile,File(context.filesDir,"batch/imports").canonicalFile))
        return folder.listFiles()?.filter { it.isFile &&
            (it.name == "${photo.id}.original" || it.name == "${photo.id}.png" || it.name.startsWith("${photo.id}-")) } ?: emptyList()
    }

    suspend fun pruneDraft(photo: DraftPhoto) = withContext(Dispatchers.IO) {
        val keep = listOfNotNull(photo.original, photo.thumbnail, photo.cutout, photo.cutoutThumbnail,
            photo.cropped, photo.croppedThumbnail).map { it.name }.toSet()
        draftFiles(photo).filterNot { it.name in keep }.forEach { it.delete() }
    }

    // Files are durable before inserting the record. A failed insert removes only this new import.
    suspend fun save(photo: DraftPhoto, name: String, category: String, color:String="", season:String="", occasion:String="") = withContext(NonCancellable + Dispatchers.IO) {
        require(name.isNotBlank() && name.length <= 60 && category in GarmentCategories)
        require(color.length<=30 && season.length<=30 && occasion.length<=30)
        val original = File(context.filesDir, "garments/${photo.id}.original")
        val thumbnail = File(context.filesDir, "thumbnails/${photo.id}.png")
        val cutout = if (photo.useCutout && photo.cutout != null) File(context.filesDir, "cutouts/${photo.id}.png") else null
        val cropped = photo.cropped?.let { File(context.filesDir, "crops/${photo.id}.png") }
        original.parentFile!!.mkdirs()
        thumbnail.parentFile!!.mkdirs()
        cutout?.parentFile?.mkdirs()
        cropped?.parentFile?.mkdirs()
        try {
            copyDurably(photo.original, original)
            copyDurably(photo.preview, thumbnail)
            if (cutout != null) copyDurably(photo.cutout!!, cutout)
            if (cropped != null) copyDurably(photo.cropped!!, cropped)
            dao.insert(Garment(photo.id, name.trim(), category, "garments/${original.name}", "thumbnails/${thumbnail.name}",
                favorite = photo.favoriteOnSave,
                cutoutPath = cutout?.let { "cutouts/${it.name}" }, cropPath = cropped?.let { "crops/${it.name}" },
                cropLeft = photo.cropBounds.left, cropTop = photo.cropBounds.top,
                cropRight = photo.cropBounds.right, cropBottom = photo.cropBounds.bottom,color=color,season=season,occasion=occasion))
        } catch (error: Exception) {
            original.delete()
            thumbnail.delete()
            cutout?.delete()
            cropped?.delete()
            throw error
        }
        discard(photo)
    }

    suspend fun update(garment: Garment, name: String, category: String,color:String,season:String,occasion:String) {
        require(name.isNotBlank() && name.length <= 60 && category in GarmentCategories)
        require(listOf(color,season,occasion).all{it.length<=30})
        dao.updateDetails(garment.id, name.trim(), category, System.currentTimeMillis(),color,season,occasion)
    }

    suspend fun favorite(garment: Garment) = dao.setFavorite(garment.id, !garment.favorite, System.currentTimeMillis())

    suspend fun attributes(id:String,color:String,season:String,occasion:String) {
        require(color.length<=30 && season.length<=30 && occasion.length<=30)
        db.tools().attributes(id,color,season,occasion,System.currentTimeMillis())
    }
    suspend fun delete(garment:Garment) { db.tools().trashGarment(garment.id,System.currentTimeMillis()) }
    suspend fun restore(garment:Garment) { db.tools().trashGarment(garment.id,null) }
    suspend fun exists(id:String)=db.garments().snapshot().any{it.id==id}
    suspend fun permanentlyDelete(garment: Garment) = withContext(NonCancellable + Dispatchers.IO) {
        db.withTransaction {
            require(db.garments().snapshot().firstOrNull{it.id==garment.id}?.deletedAt!=null)
            if (db.outfits().references(garment.id) > 0) throw GarmentInUseException()
            dao.delete(garment.id)
        }
        File(context.filesDir, garment.originalPath).delete()
        File(context.filesDir, garment.thumbnailPath).delete()
        garment.cutoutPath?.let { File(context.filesDir, it).delete() }
        garment.cropPath?.let { File(context.filesDir, it).delete() }
        Unit
    }

    fun thumbnail(garment: Garment) = File(context.filesDir, garment.thumbnailPath)

    private fun copyDurably(source: File, target: File) {
        source.inputStream().use { input ->
            FileOutputStream(target).use { output -> input.copyTo(output); output.fd.sync() }
        }
    }
}

private fun writePng(bitmap: Bitmap, file: File) {
    FileOutputStream(file).use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)); it.fd.sync() }
}

internal fun decodeThumbnail(file: File, maxSide: Int = 512): Bitmap {
    if (Build.VERSION.SDK_INT >= 28) {
        return ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
            val scale = minOf(1f, maxSide.toFloat() / max(info.size.width, info.size.height))
            decoder.setTargetSize(max(1, (info.size.width * scale).toInt()), max(1, (info.size.height * scale).toInt()))
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
    }
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IOException("这张图片无法打开，请选择其他照片")
    var sample = 1
    while (max(bounds.outWidth, bounds.outHeight) / sample > maxSide * 2) sample *= 2
    val bitmap = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
        ?: throw IOException("这张图片无法打开，请选择其他照片")
    val matrix = Matrix()
    when (ExifInterface(file).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
        ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.setScale(1f, -1f)
        ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.setRotate(90f); matrix.postScale(-1f, 1f) }
        ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
        ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.setRotate(270f); matrix.postScale(-1f, 1f) }
        ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(270f)
    }
    val oriented = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    if (oriented !== bitmap) bitmap.recycle()
    val scale = minOf(1f, maxSide.toFloat() / max(oriented.width, oriented.height))
    val result = Bitmap.createScaledBitmap(oriented, max(1, (oriented.width * scale).toInt()), max(1, (oriented.height * scale).toInt()), true)
    if (result !== oriented) oriented.recycle()
    return result
}
