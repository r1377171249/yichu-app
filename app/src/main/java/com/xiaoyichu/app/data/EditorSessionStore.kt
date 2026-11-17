package com.xiaoyichu.app.data

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException

data class EditorSession(val photo: DraftPhoto?, val canvas: WorkspaceRecovery?)
data class SessionLoad(val session: EditorSession?, val damaged: Boolean = false)

/** Private, bounded, atomic checkpoints. Images stay separate from the JSON and saved wardrobe. */
class EditorSessionStore(private val context: Context) {
    private val atomic = AtomicFile(File(context.filesDir, "editor-session.json"))
    private val mutex = Mutex()
    private val folder get() = File(context.filesDir, "drafts/imports").canonicalFile

    suspend fun write(session: EditorSession) = withContext(Dispatchers.IO) { mutex.withLock {
        val json = JSONObject().put("version", 1)
        session.photo?.let { p ->
            fun file(f: File?): Any = f?.let {
                require(it.canonicalFile.parentFile == folder && it.isFile && it.length() in 1..50L*1024*1024)
                it.name
            } ?: JSONObject.NULL
            json.put("photo", JSONObject().put("id", p.id).put("original", file(p.original)).put("thumbnail", file(p.thumbnail))
                .put("cutout", file(p.cutout)).put("cutoutThumbnail", file(p.cutoutThumbnail))
                .put("cropped", file(p.cropped)).put("croppedThumbnail", file(p.croppedThumbnail))
                .put("useCutout", p.useCutout).put("cutoutError", p.cutoutError)
                .put("crop", JSONArray(listOf(p.cropBounds.left,p.cropBounds.top,p.cropBounds.right,p.cropBounds.bottom)))
                .put("cutoutMode",p.cutoutMode).put("color",p.color).put("season",p.season).put("occasion",p.occasion).put("name", p.suggestedName).put("category",p.suggestedCategory ?: JSONObject.NULL).put("favorite", p.favoriteOnSave))
        }
        session.canvas?.let { c -> json.put("canvas", JSONObject().put("draft", draftJson(c.draft))
            .put("baseline", draftJson(c.baseline)).put("selected", c.selectedId ?: JSONObject.NULL)) }
        val bytes = json.toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= 1024*1024)
        val stream = atomic.startWrite()
        try { stream.write(bytes); atomic.finishWrite(stream) }
        catch (error: Throwable) { atomic.failWrite(stream); throw error }
    } }

    suspend fun load(): SessionLoad = withContext(Dispatchers.IO) { mutex.withLock {
        val raw = try { atomic.openRead().use { input ->
            val output = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
            while (true) { val count = input.read(buffer); if (count < 0) break
                require(output.size()+count <= 1024*1024); output.write(buffer,0,count) }
            output.toByteArray()
        } } catch (_: FileNotFoundException) { return@withLock SessionLoad(null) }
        catch (_: Exception) { return@withLock SessionLoad(null, true) }
        try {
            val json = JSONObject(String(raw, Charsets.UTF_8)); require(json.getInt("version") == 1)
            val garments = WardrobeDatabase.get(context).garments().snapshot().map { it.id }.toSet()
            val photo = json.optJSONObject("photo")?.let { j ->
                val id = j.getString("id"); require(id.matches(Regex("[0-9a-f-]{36}")))
                // A save may commit just before process death. Never resurrect its former draft.
                if (id in garments) null else {
                    fun file(key:String, required:Boolean=false):File? {
                        if (j.isNull(key)) { require(!required); return null }
                        val name=j.getString(key)
                        require(name == "$id.original" || name == "$id.png" || name.matches(Regex("${Regex.escape(id)}-[0-9a-f-]{36}-[a-z-]+\\.png")))
                        return File(folder,name).also { require(it.canonicalFile.parentFile==folder && it.isFile && it.length() in 1..50L*1024*1024) }
                    }
                    val crop=j.getJSONArray("crop"); require(crop.length()==4)
                    val name=j.getString("name");require(name.length<=60)
                    val category=if(j.isNull("category")) null else j.getString("category").also{require(it in GarmentCategories)}
                    val bounds=CropBounds(crop.getDouble(0).toFloat(),crop.getDouble(1).toFloat(),crop.getDouble(2).toFloat(),crop.getDouble(3).toFloat())
                    val cutout=file("cutout");val cutThumb=file("cutoutThumbnail"); val cropped=file("cropped");val cropThumb=file("croppedThumbnail")
                    require((cutout==null)==(cutThumb==null) && (cropped==null)==(cropThumb==null))
                    require(!j.getBoolean("useCutout") || cutout!=null)
                    DraftPhoto(id, requireNotNull(file("original",true)), requireNotNull(file("thumbnail",true)),
                        cutout,cutThumb,j.getBoolean("useCutout"),j.getBoolean("cutoutError"),cropped,cropThumb,bounds,name,category,j.getBoolean("favorite"),j.optString("color",""),j.optString("season",""),j.optString("occasion",""),j.optString("cutoutMode","保留细节").also{require(it in CutoutModes)})
                }
            }
            val canvas=json.optJSONObject("canvas")?.let { j ->
                val draft=parseDraft(j.getJSONObject("draft"),garments);val baseline=parseDraft(j.getJSONObject("baseline"),garments)
                require(draft.id==baseline.id)
                val selected=if(j.isNull("selected")) null else j.getString("selected")
                require(selected==null || draft.items.any{it.id==selected})
                val db=WardrobeDatabase.get(context).outfits()
                val saved=db.find(draft.id)
                val committed=saved!=null && saved.name==draft.name && db.itemSnapshot().filter{it.outfitId==draft.id}.sortedBy{it.zIndex}==draft.items
                WorkspaceRecovery(draft,if(committed) draft else baseline,selected)
            }
            SessionLoad(EditorSession(photo,canvas))
        } catch (_: Exception) { SessionLoad(null, true) }
    } }

    private fun draftJson(d:OutfitDraft):JSONObject = JSONObject().put("id",d.id).put("name",d.name)
        .put("items",JSONArray().also { array -> d.items.forEach { i -> array.put(JSONObject()
            .put("id",i.id).put("garment",i.garmentId).put("x",i.x).put("y",i.y).put("width",i.width).put("rotation",i.rotation).put("z",i.zIndex)
            .put("left",i.cropLeft).put("top",i.cropTop).put("right",i.cropRight).put("bottom",i.cropBottom).put("filter",i.filter)) } })

    private fun parseDraft(j:JSONObject,garments:Set<String>):OutfitDraft {
        val id=j.getString("id");require(id.isNotBlank() && id.length<=128)
        val name=j.getString("name");require(name.length<=60)
        val array=j.getJSONArray("items");require(array.length()<=30)
        val items=(0 until array.length()).map { index -> val i=array.getJSONObject(index)
            OutfitItem(i.getString("id"),id,i.getString("garment"),i.getDouble("x").toFloat(),i.getDouble("y").toFloat(),
                i.getDouble("width").toFloat(),i.getDouble("rotation").toFloat(),i.getInt("z"),
                i.getDouble("left").toFloat(),i.getDouble("top").toFloat(),i.getDouble("right").toFloat(),i.getDouble("bottom").toFloat(),i.getString("filter")).also {
                require(it.id.isNotBlank() && it.id.length<=128 && it.garmentId in garments && it.valid() && it.zIndex==index)
            }
        }
        require(items.map{it.id}.distinct().size==items.size)
        return OutfitDraft(id,name,items)
    }
}
