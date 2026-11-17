package com.xiaoyichu.app.data

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

@Entity(tableName = "outfits")
data class Outfit(@PrimaryKey val id: String, val name: String, val createdAt: Long, val updatedAt: Long,
    @ColumnInfo(defaultValue = "''") val tags: String = "",
    @ColumnInfo(defaultValue = "0") val favorite: Boolean = false,
    val todayDate: String? = null,
    @ColumnInfo(defaultValue = "''") val collectionName:String="",
    val deletedAt:Long?=null)

@Entity(tableName = "outfit_items", foreignKeys = [
    ForeignKey(entity = Outfit::class, parentColumns = ["id"], childColumns = ["outfitId"], onDelete = ForeignKey.CASCADE),
    ForeignKey(entity = Garment::class, parentColumns = ["id"], childColumns = ["garmentId"], onDelete = ForeignKey.RESTRICT)
], indices = [Index("outfitId"), Index("garmentId")])
data class OutfitItem(
    @PrimaryKey val id: String, val outfitId: String, val garmentId: String,
    val x: Float, val y: Float, val width: Float, val rotation: Float, val zIndex: Int,
    @ColumnInfo(defaultValue = "0.0") val cropLeft: Float = 0f,
    @ColumnInfo(defaultValue = "0.0") val cropTop: Float = 0f,
    @ColumnInfo(defaultValue = "1.0") val cropRight: Float = 1f,
    @ColumnInfo(defaultValue = "1.0") val cropBottom: Float = 1f,
    @ColumnInfo(defaultValue = "'original'") val filter: String = "original"
)

val OutfitItem.crop get() = CropBounds(cropLeft, cropTop, cropRight, cropBottom)
val OutfitFilters = listOf("original", "warm", "mono")

data class SavedOutfit(@Embedded val outfit: Outfit,
    @Relation(parentColumn = "id", entityColumn = "outfitId") val items: List<OutfitItem>)

@Dao
interface OutfitDao {
    @Query("SELECT * FROM outfits ORDER BY id") suspend fun snapshot(): List<Outfit>
    @Query("SELECT * FROM outfit_items ORDER BY id") suspend fun itemSnapshot(): List<OutfitItem>
    @Transaction @Query("SELECT * FROM outfits ORDER BY updatedAt DESC, id ASC")
    fun observeAll(): kotlinx.coroutines.flow.Flow<List<SavedOutfit>>
    @Query("SELECT * FROM outfits WHERE id = :id") suspend fun find(id: String): Outfit?
    @Upsert suspend fun upsert(outfit: Outfit)
    @Insert suspend fun insertItems(items: List<OutfitItem>)
    @Query("DELETE FROM outfit_items WHERE outfitId = :id") suspend fun clearItems(id: String)
    @Query("DELETE FROM outfits WHERE id = :id") suspend fun delete(id: String)
    @Query("SELECT COUNT(*) FROM outfit_items WHERE garmentId = :id") suspend fun references(id: String): Int
    @Query("UPDATE outfits SET name = :name, tags = :tags, updatedAt = :now WHERE id = :id")
    suspend fun metadata(id: String, name: String, tags: String, now: Long)
    @Query("UPDATE outfits SET favorite = NOT favorite WHERE id = :id") suspend fun favorite(id: String)
    @Query("UPDATE outfits SET todayDate = NULL WHERE todayDate = :date") suspend fun clearToday(date: String)
    @Query("UPDATE outfits SET todayDate = :date WHERE id = :id") suspend fun today(id: String, date: String)
}

class GarmentInUseException : IOException("这件衣物已用于搭配，请先从搭配中移除后再删除")

class OutfitRepository(private val context: Context) {
    private val db = WardrobeDatabase.get(context)
    val outfits = db.outfits().observeAll()

    suspend fun save(draft: OutfitDraft) = withContext(NonCancellable + Dispatchers.IO) {
        require(draft.name.isNotBlank() && draft.name.length <= 60 && draft.items.size in 1..30)
        require(draft.items.map { it.id }.distinct().size == draft.items.size)
        require(draft.items.all { it.outfitId == draft.id && it.valid() })
        val staged = File(context.cacheDir, "preview-${java.util.UUID.randomUUID()}.png")
        try {
        OutfitRenderer.render(context, draft.items, db.garments().snapshot(), staged, 512)
        db.withTransaction {
            val now = System.currentTimeMillis()
            val old = db.outfits().find(draft.id)
            db.outfits().upsert((old ?: Outfit(draft.id, draft.name.trim(), now, now)).copy(name = draft.name.trim(), updatedAt = now))
            db.outfits().clearItems(draft.id)
            db.outfits().insertItems(draft.items.mapIndexed { index, item -> item.copy(zIndex = index) })
        }
        val target = preview(draft.id); target.parentFile!!.mkdirs()
        // A preview is a regenerable cache; primary editable data is already committed.
        if (!staged.renameTo(target)) target.delete()
        } finally { staged.delete() }
    }

    suspend fun delete(id: String) { db.tools().trashOutfit(id,System.currentTimeMillis()) }
    suspend fun restore(id:String) { db.withTransaction {
        val garments=db.garments().snapshot().associateBy{it.id}
        db.outfits().itemSnapshot().filter{it.outfitId==id}.forEach{ db.tools().trashGarment(it.garmentId,null);check(garments.containsKey(it.garmentId)) }
        db.tools().trashOutfit(id,null)
    } }
    suspend fun permanentlyDelete(id:String) { db.withTransaction{require(db.outfits().find(id)?.deletedAt!=null);db.outfits().delete(id)};preview(id).delete() }
    suspend fun collection(id:String,name:String) { require(name.length<=40);db.tools().collection(id,name.trim()) }
    suspend fun schedule(id:String,date:String,worn:Boolean) { validateSchedule(date,worn);db.withTransaction {
        check(db.outfits().find(id)?.deletedAt==null && db.outfits().find(id)!=null)
        db.tools().schedule(CalendarEntry(date,id,worn))
        if(date==java.time.LocalDate.now().toString()){db.outfits().clearToday(date);db.outfits().today(id,date)}
    } }
    suspend fun unschedule(date:String) { java.time.LocalDate.parse(date);db.withTransaction{ db.tools().unschedule(date);db.outfits().clearToday(date) } }
    val calendar=db.tools().calendar()
    suspend fun metadata(id: String, name: String, tags: String) = withContext(Dispatchers.IO) {
        require(name.isNotBlank() && name.length <= 60 && tags.length <= 160)
        db.outfits().metadata(id, name.trim(), tags.split('，', ',', ' ').filter { it.isNotBlank() }.distinct().take(8).joinToString(","), System.currentTimeMillis())
    }
    suspend fun favorite(id: String) = db.outfits().favorite(id)
    suspend fun today(id: String) = db.withTransaction {
        val date = java.time.LocalDate.now().toString()
        db.outfits().clearToday(date); db.outfits().today(id, date);db.tools().schedule(CalendarEntry(date,id,true))
    }
    fun preview(id: String) = File(context.filesDir, "outfit-previews/${java.util.UUID.nameUUIDFromBytes(id.toByteArray())}.png")

    fun artwork(garment: Garment): File = File(context.filesDir,
        garment.cutoutPath ?: garment.cropPath ?: garment.originalPath)
}
