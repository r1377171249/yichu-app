package com.xiaoyichu.app.data

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import kotlinx.coroutines.*
import org.json.*
import java.io.*
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.*

data class BackupSummary(val garments: Int, val outfits: Int, val instances: Int, val bytes: Long)
class PreparedBackup internal constructor(val folder: File, val garments: List<Garment>, val outfits: List<Outfit>,
    val items: List<OutfitItem>, val images: List<BackupImage>,val calendar:List<CalendarEntry>,val fingerprint:String,val alreadyRestored:Boolean) {
    val summary get() = BackupSummary(garments.size,outfits.size,items.size,images.sumOf { it.size })
}
private data class BackupSnapshot(val garments:List<Garment>,val outfits:List<Outfit>,val items:List<OutfitItem>,val calendar:List<CalendarEntry>)

data class BackupImage(val path: String, val size: Long, val sha256: String)

class BackupRepository(private val context: Context) {
    private val db = WardrobeDatabase.get(context)
    private val preferences=context.getSharedPreferences("wardrobe-backup",Context.MODE_PRIVATE)
    fun lastBackupAt()=preferences.getLong("exportedAt",0)
    fun markExported() { check(preferences.edit().putLong("exportedAt",System.currentTimeMillis()).commit()) }
    private val cache get() = File(context.cacheDir,"backups").apply { mkdirs() }

    suspend fun export(): File = withContext(Dispatchers.IO) {
        pruneOldCaches()
        val snapshot = db.withTransaction { BackupSnapshot(db.garments().snapshot(),db.outfits().snapshot(),db.outfits().itemSnapshot(),db.tools().calendarSnapshot()) }
        val garments=snapshot.garments;val outfits=snapshot.outfits;val items=snapshot.items;val calendar=snapshot.calendar
        require(garments.size <= 1000 && outfits.size <= 1000 && items.size <= 30000 && calendar.size<=10000)
        val paths=garments.flatMap { listOfNotNull(it.originalPath,it.thumbnailPath,it.cutoutPath,it.cropPath) }.distinct().sorted()
        require(paths.size <= BackupRules.MaxFiles && paths.all(BackupRules::imagePath))
        val images=paths.map { path -> val file=privateImage(path); BackupImage(path,file.length(),digest(file)) }
        require(images.all { BackupRules.validSize(it.size) } && images.sumOf{it.size} <= BackupRules.MaxTotalBytes)
        check(cache.usableSpace > images.sumOf{it.size} + 16*1024*1024L)
        val manifest=JSONObject().put("format","xiaoyichu-backup").put("formatVersion",2).put("schemaVersion",6)
            .put("createdAt",System.currentTimeMillis()).put("garments",JSONArray(garments.map(::garmentJson)))
            .put("outfits",JSONArray(outfits.map(::outfitJson))).put("items",JSONArray(items.map(::itemJson)))
            .put("calendar",JSONArray(calendar.map {JSONObject().put("date",it.date).put("outfitId",it.outfitId).put("worn",it.worn)}))
            .put("images",JSONArray(images.map { JSONObject().put("path",it.path).put("size",it.size).put("sha256",it.sha256) }))
        val bytes=manifest.toString().toByteArray(Charsets.UTF_8);require(bytes.size <= BackupRules.MaxManifestBytes)
        val file=File(cache,"wardrobe-${UUID.randomUUID()}.zip")
        try {
            FileOutputStream(file).use { stream -> ZipOutputStream(BufferedOutputStream(stream)).use { zip ->
                zip.putNextEntry(ZipEntry("manifest.json"));zip.write(bytes);zip.closeEntry()
                images.forEach { image ->
                    ensureActive();zip.putNextEntry(ZipEntry("images/${image.path}"))
                    privateImage(image.path).inputStream().use { it.copyTo(zip) };zip.closeEntry()
                }
                zip.finish();zip.flush();stream.fd.sync()
            } }
            file
        } catch(error:Throwable) { file.delete();throw error }
    }

    suspend fun inspect(uri: Uri): PreparedBackup = withContext(Dispatchers.IO) {
        pruneOldCaches()
        val folder=File(cache,"restore-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            context.contentResolver.openInputStream(uri)?.use { input -> ZipInputStream(BufferedInputStream(input)).use { zip ->
                require(zip.nextEntry?.name=="manifest.json")
                val manifestBytes=readLimited(zip,BackupRules.MaxManifestBytes.toLong())
                val fingerprint=hex(MessageDigest.getInstance("SHA-256").digest(manifestBytes))
                val manifest=JSONObject(String(manifestBytes,Charsets.UTF_8))
                require(manifest.getString("format")=="xiaoyichu-backup")
                val version=manifest.getInt("formatVersion");require((version==1&&manifest.getInt("schemaVersion")==5)||(version==2&&manifest.getInt("schemaVersion")==6))
                val garments=manifest.getJSONArray("garments").objects(1000).map(::readGarment)
                val outfits=manifest.getJSONArray("outfits").objects(1000).map(::readOutfit)
                val items=manifest.getJSONArray("items").objects(30000).map(::readItem)
                val images=manifest.getJSONArray("images").objects(BackupRules.MaxFiles).map {
                    BackupImage(it.getString("path"),it.getLong("size"),it.getString("sha256"))
                }
                validate(garments,outfits,items,images)
                val calendar=if(version==2)manifest.getJSONArray("calendar").objects(10000).map { j ->
                    CalendarEntry(j.getString("date"),j.getString("outfitId"),j.getBoolean("worn"))
                } else outfits.mapNotNull{o->o.todayDate?.let{CalendarEntry(it,o.id,false)}}
                require(calendar.map{it.date}.distinct().size==calendar.size)
                require(calendar.all { e -> runCatching{validateSchedule(e.date,e.worn);true}.getOrDefault(false) && outfits.any{it.id==e.outfitId} })
                check(folder.usableSpace > images.sumOf{it.size}+16*1024*1024L)
                zip.closeEntry()
                val expected=images.associateBy { "images/${it.path}" };val seen=mutableSetOf<String>()
                var count=0
                while(true) {
                    ensureActive();val entry=zip.nextEntry ?: break
                    require(++count<=BackupRules.MaxFiles && !entry.isDirectory && seen.add(entry.name))
                    val image=expected[entry.name] ?: error("Unexpected archive entry")
                    val file=File(folder,image.path);require(file.canonicalPath.startsWith(folder.canonicalPath+File.separator))
                    file.parentFile!!.mkdirs()
                    val hash=MessageDigest.getInstance("SHA-256");var size=0L
                    FileOutputStream(file).use { output ->
                        val buffer=ByteArray(64*1024)
                        while(true) { val n=zip.read(buffer);if(n<0)break;size+=n;require(size<=image.size)
                            hash.update(buffer,0,n);output.write(buffer,0,n) }
                    }
                    require(size==image.size && hex(hash.digest())==image.sha256)
                    val thumbnail=decodeThumbnail(file,64);thumbnail.recycle()
                    zip.closeEntry()
                }
                require(seen==expected.keys)
                PreparedBackup(folder,garments,outfits,items,images,calendar,fingerprint,db.tools().restored(fingerprint)>0)
            } } ?: error("Unreadable backup")
        } catch(error:Throwable) { folder.deleteRecursively();throw error }
    }

    suspend fun restore(prepared: PreparedBackup,allowDuplicate:Boolean=false): BackupSummary = withContext(NonCancellable + Dispatchers.IO) {
        val created=mutableListOf<File>()
        try {
            check(allowDuplicate || db.tools().restored(prepared.fingerprint)==0){"This backup was already restored"}
            check(context.filesDir.usableSpace > prepared.summary.bytes + 16*1024*1024L)
            val remappedPaths=prepared.images.associate { image ->
                val path="${image.path.substringBefore('/')}/${UUID.randomUUID()}.${if(image.path.substringBefore('/')=="garments")"original" else "png"}"
                val output=privateImage(path);output.parentFile!!.mkdirs();created+=output
                val source=File(prepared.folder,image.path)
                require(source.length()==image.size && digest(source)==image.sha256)
                FileOutputStream(output).use { stream -> source.inputStream().use{it.copyTo(stream)};stream.fd.sync() }
                image.path to path
            }
            val garmentIds=prepared.garments.associate { it.id to UUID.randomUUID().toString() }
            val outfitIds=prepared.outfits.associate { it.id to UUID.randomUUID().toString() }
            db.withTransaction {
                check(allowDuplicate || db.tools().restored(prepared.fingerprint)==0)
                val occupiedCalendar=db.tools().calendarSnapshot().map{it.date}.toSet()
                val occupiedDates=db.outfits().snapshot().mapNotNull { it.todayDate }.toSet()
                prepared.garments.forEach { old -> db.garments().insert(old.copy(id=garmentIds.getValue(old.id),
                    originalPath=remappedPaths.getValue(old.originalPath),thumbnailPath=remappedPaths.getValue(old.thumbnailPath),
                    cutoutPath=old.cutoutPath?.let(remappedPaths::getValue),cropPath=old.cropPath?.let(remappedPaths::getValue))) }
                prepared.outfits.forEach { old -> db.outfits().upsert(old.copy(id=outfitIds.getValue(old.id),todayDate=old.todayDate?.takeUnless { it in occupiedDates })) }
                db.outfits().insertItems(prepared.items.map { it.copy(id=UUID.randomUUID().toString(),outfitId=outfitIds.getValue(it.outfitId),garmentId=garmentIds.getValue(it.garmentId)) })
                prepared.calendar.filterNot{it.date in occupiedCalendar}.forEach { db.tools().schedule(it.copy(outfitId=outfitIds.getValue(it.outfitId))) }
                if(db.tools().restored(prepared.fingerprint)==0)db.tools().recordRestore(RestoredBackup(prepared.fingerprint,System.currentTimeMillis()))
            }
            prepared.summary
        } catch(error:Throwable) { created.forEach { it.delete() };throw error }
        finally { prepared.folder.deleteRecursively() }
    }
    suspend fun discard(prepared: PreparedBackup) = withContext(Dispatchers.IO) { prepared.folder.deleteRecursively();Unit }

    private fun pruneOldCaches() {
        cache.listFiles()?.filter { it.name.matches(Regex("(wardrobe-[a-f0-9-]+\\.zip|restore-[a-f0-9-]+)")) &&
            System.currentTimeMillis()-it.lastModified()>24*60*60*1000L }?.forEach { it.deleteRecursively() }
    }

    private fun privateImage(path:String):File {
        require(BackupRules.imagePath(path));val file=File(context.filesDir,path)
        require(file.canonicalPath.startsWith(context.filesDir.canonicalPath+File.separator));return file
    }
    private fun validate(gs:List<Garment>,os:List<Outfit>,items:List<OutfitItem>,images:List<BackupImage>) {
        require(gs.map{it.id}.distinct().size==gs.size && os.map{it.id}.distinct().size==os.size && items.map{it.id}.distinct().size==items.size)
        require(gs.all { listOf(it.color,it.season,it.occasion).all{v->v.length<=30} && (it.deletedAt==null||it.deletedAt>=0) && BackupRules.validId(it.id) && it.name.isNotBlank() && it.name.length<=60 && it.category in GarmentCategories && it.createdAt>=0 && it.updatedAt>=0 &&
            listOf(it.cropLeft,it.cropTop,it.cropRight,it.cropBottom).all{v->v.isFinite()} && it.cropLeft>=0 && it.cropTop>=0 && it.cropRight<=1 && it.cropBottom<=1 && it.cropLeft<it.cropRight && it.cropTop<it.cropBottom })
        require(os.all { it.collectionName.length<=40 && (it.deletedAt==null||it.deletedAt>=0) && BackupRules.validId(it.id) && it.name.isNotBlank() && it.name.length<=60 && it.tags.length<=160 && it.tags.split(',').filter{tag->tag.isNotBlank()}.size<=8 && it.createdAt>=0 && it.updatedAt>=0 &&
            (it.todayDate==null || runCatching { java.time.LocalDate.parse(it.todayDate);true }.getOrDefault(false)) })
        val dates=os.mapNotNull{it.todayDate};require(dates.distinct().size==dates.size)
        val garmentIds=gs.map{it.id}.toSet();val outfitIds=os.map{it.id}.toSet()
        require(items.all { BackupRules.validId(it.id) && it.valid() && it.garmentId in garmentIds && it.outfitId in outfitIds })
        os.forEach { outfit -> val list=items.filter{it.outfitId==outfit.id};require(list.size in 1..30 && list.map{it.zIndex}.sorted()==list.indices.toList()) }
        require(images.all { BackupRules.imagePath(it.path) && BackupRules.validSize(it.size) && BackupRules.validHash(it.sha256) })
        require(images.map{it.path}.distinct().size==images.size && images.sumOf{it.size}<=BackupRules.MaxTotalBytes)
        require(gs.flatMap{listOfNotNull(it.originalPath,it.thumbnailPath,it.cropPath,it.cutoutPath)}.toSet()==images.map{it.path}.toSet())
    }

    private fun garmentJson(g:Garment)=JSONObject().put("id",g.id).put("name",g.name).put("category",g.category)
        .put("originalPath",g.originalPath).put("thumbnailPath",g.thumbnailPath).put("favorite",g.favorite)
        .put("createdAt",g.createdAt).put("updatedAt",g.updatedAt).put("cutoutPath",g.cutoutPath ?: JSONObject.NULL).put("cropPath",g.cropPath ?: JSONObject.NULL)
        .put("color",g.color).put("season",g.season).put("occasion",g.occasion).put("deletedAt",g.deletedAt ?: JSONObject.NULL)
        .put("cropLeft",g.cropLeft.toDouble()).put("cropTop",g.cropTop.toDouble()).put("cropRight",g.cropRight.toDouble()).put("cropBottom",g.cropBottom.toDouble())
    private fun outfitJson(o:Outfit)=JSONObject().put("id",o.id).put("name",o.name).put("createdAt",o.createdAt).put("updatedAt",o.updatedAt)
        .put("collectionName",o.collectionName).put("deletedAt",o.deletedAt ?: JSONObject.NULL).put("tags",o.tags).put("favorite",o.favorite).put("todayDate",o.todayDate ?: JSONObject.NULL)
    private fun itemJson(i:OutfitItem)=JSONObject().put("id",i.id).put("outfitId",i.outfitId).put("garmentId",i.garmentId)
        .put("x",i.x.toDouble()).put("y",i.y.toDouble()).put("width",i.width.toDouble()).put("rotation",i.rotation.toDouble()).put("zIndex",i.zIndex)
        .put("cropLeft",i.cropLeft.toDouble()).put("cropTop",i.cropTop.toDouble()).put("cropRight",i.cropRight.toDouble()).put("cropBottom",i.cropBottom.toDouble()).put("filter",i.filter)
    private fun readGarment(j:JSONObject)=Garment(j.getString("id"),j.getString("name"),j.getString("category"),j.getString("originalPath"),j.getString("thumbnailPath"),j.getBoolean("favorite"),j.getLong("createdAt"),j.getLong("updatedAt"),j.nullString("cutoutPath"),j.nullString("cropPath"),j.f("cropLeft"),j.f("cropTop"),j.f("cropRight"),j.f("cropBottom"),j.optString("color",""),j.optString("season",""),j.optString("occasion",""),if(j.isNull("deletedAt"))null else j.getLong("deletedAt"))
    private fun readOutfit(j:JSONObject)=Outfit(j.getString("id"),j.getString("name"),j.getLong("createdAt"),j.getLong("updatedAt"),j.getString("tags"),j.getBoolean("favorite"),j.nullString("todayDate"),j.optString("collectionName",""),if(j.isNull("deletedAt"))null else j.getLong("deletedAt"))
    private fun readItem(j:JSONObject)=OutfitItem(j.getString("id"),j.getString("outfitId"),j.getString("garmentId"),j.f("x"),j.f("y"),j.f("width"),j.f("rotation"),j.getInt("zIndex"),j.f("cropLeft"),j.f("cropTop"),j.f("cropRight"),j.f("cropBottom"),j.getString("filter"))
    private fun JSONObject.f(key:String)=getDouble(key).toFloat()
    private fun JSONObject.nullString(key:String)=if(isNull(key))null else getString(key)
    private fun JSONArray.objects(max:Int):List<JSONObject> { require(length()<=max);return (0 until length()).map{getJSONObject(it)} }
    private fun digest(file:File):String { val hash=MessageDigest.getInstance("SHA-256");file.inputStream().use{input->val buffer=ByteArray(65536);while(true){val n=input.read(buffer);if(n<0)break;hash.update(buffer,0,n)}};return hex(hash.digest()) }
    private fun hex(bytes:ByteArray)=bytes.joinToString(""){"%02x".format(it)}
    private fun readLimited(input:InputStream,limit:Long):ByteArray {val output=ByteArrayOutputStream();val buffer=ByteArray(65536);var total=0L;while(true){val n=input.read(buffer);if(n<0)break;total+=n;require(total<=limit);output.write(buffer,0,n)};return output.toByteArray()}
}
