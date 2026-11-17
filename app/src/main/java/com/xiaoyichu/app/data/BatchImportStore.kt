package com.xiaoyichu.app.data

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.*
import java.io.File
import java.io.FileNotFoundException

data class BatchPhoto(val photo:DraftPhoto,val name:String,val category:String="上衣",val color:String="",val season:String="",val occasion:String="",val originalAccepted:Boolean=false) {
    val ready get()=name.isNotBlank() && (photo.cutout!=null || originalAccepted)
    fun photoForSave()=photo.copy(useCutout=!originalAccepted&&photo.cutout!=null)
}
/** Separate from single-photo recovery: never pruned as an abandoned editor draft. */
class BatchImportStore(private val context:Context) {
    private val atomic=AtomicFile(File(context.filesDir,"batch/queue.json"))
    private val folder get()=File(context.filesDir,"batch/imports").canonicalFile
    suspend fun write(items:List<BatchPhoto>)=withContext(Dispatchers.IO) {
        require(items.size<=100)
        val json=JSONArray()
        items.forEach { b ->
            val p=b.photo
            fun file(f:File?):Any=f?.let { require(it.canonicalFile.parentFile==folder && it.isFile);it.name } ?: JSONObject.NULL
            json.put(JSONObject().put("id",p.id).put("original",file(p.original)).put("thumbnail",file(p.thumbnail))
                .put("cutout",file(p.cutout)).put("cutoutThumbnail",file(p.cutoutThumbnail)).put("failed",p.cutoutError)
                .put("useCutout",p.useCutout).put("name",b.name).put("category",b.category).put("color",b.color)
                .put("season",b.season).put("occasion",b.occasion).put("originalAccepted",b.originalAccepted))
        }
        val bytes=json.toString().toByteArray(Charsets.UTF_8);require(bytes.size<=1024*1024)
        val output=atomic.startWrite()
        try {output.write(bytes);atomic.finishWrite(output)}catch(error:Throwable){atomic.failWrite(output);throw error}
    }
    suspend fun load():List<BatchPhoto> = withContext(Dispatchers.IO) {
        val raw=try{atomic.openRead().use{it.readBytes().also{bytes->require(bytes.size<=1024*1024)}}}catch(_:FileNotFoundException){return@withContext emptyList()}
        val array=JSONArray(String(raw,Charsets.UTF_8));require(array.length()<=100)
        val saved=WardrobeDatabase.get(context).garments().snapshot().map{it.id}.toSet()
        (0 until array.length()).mapNotNull { n ->
            val j=array.getJSONObject(n);val id=j.getString("id");require(id.matches(Regex("[a-f0-9-]{36}")))
            if(id in saved)return@mapNotNull null
            fun file(key:String):File?=if(j.isNull(key))null else File(folder,j.getString(key)).also {
                require(it.canonicalFile.parentFile==folder && it.name.startsWith(id) && it.isFile && it.length() in 1..50L*1024*1024)
            }
            val name=j.getString("name");val category=j.getString("category");require(name.length<=60 && category in GarmentCategories)
            val cutout=file("cutout");val thumb=file("cutoutThumbnail");require((cutout==null)==(thumb==null))
            val color=j.getString("color");val season=j.getString("season");val occasion=j.getString("occasion")
            require(listOf(color,season,occasion).all{it.length<=30})
            BatchPhoto(DraftPhoto(id,requireNotNull(file("original")),requireNotNull(file("thumbnail")),cutout,thumb,
                !j.getBoolean("originalAccepted")&&cutout!=null,j.getBoolean("failed")),name,category,color,season,occasion,j.getBoolean("originalAccepted"))
        }.also{ require(it.map{b->b.photo.id}.distinct().size==it.size) }
    }
}
