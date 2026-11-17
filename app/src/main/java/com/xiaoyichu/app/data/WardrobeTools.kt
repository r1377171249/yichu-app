package com.xiaoyichu.app.data

import androidx.room.*
import java.time.LocalDate
import java.util.Locale

val GarmentColors=listOf("白色","黑色","灰色","米色","棕色","粉色","红色","橙色","黄色","绿色","蓝色","紫色","多色")
val GarmentSeasons=listOf("春季","夏季","秋季","冬季","四季")
val GarmentOccasions=listOf("日常","通勤","约会","运动","正式","旅行")
val WardrobeSorts=listOf("最新录入","最早录入","名称排序","最近修改","收藏优先")

data class WardrobeQuery(val text:String="",val category:String="全部",val color:String="",val season:String="",val occasion:String="",val sort:String="最新录入")
fun filterWardrobe(garments:List<Garment>,q:WardrobeQuery):List<Garment> {
    val words=q.text.trim().lowercase(Locale.ROOT).split(Regex("\\s+")).filter{it.isNotBlank()}
    val filtered=garments.filter { g -> g.deletedAt==null && (q.category=="全部" || g.category==q.category) &&
        (q.color.isBlank() || g.color==q.color) && (q.season.isBlank() || g.season==q.season || g.season=="四季") &&
        (q.occasion.isBlank() || g.occasion==q.occasion) && words.all { it in "${g.name} ${g.category} ${g.color} ${g.season} ${g.occasion}".lowercase(Locale.ROOT) } }
    val order=when(q.sort){
        "最早录入" -> compareBy<Garment>{it.createdAt}
        "名称排序" -> compareBy{it.name.lowercase(Locale.ROOT)}
        "最近修改" -> compareByDescending{it.updatedAt}
        "收藏优先" -> compareByDescending<Garment>{it.favorite}.thenByDescending{it.createdAt}
        else -> compareByDescending{it.createdAt}
    }
    return filtered.sortedWith(order.thenBy{it.id})
}

@Entity(tableName="calendar_entries",foreignKeys=[ForeignKey(entity=Outfit::class,parentColumns=["id"],childColumns=["outfitId"],onDelete=ForeignKey.CASCADE)],indices=[Index("outfitId")])
data class CalendarEntry(@PrimaryKey val date:String,val outfitId:String,val worn:Boolean=false)
@Entity(tableName="restored_backups")
data class RestoredBackup(@PrimaryKey val fingerprint:String,val restoredAt:Long)
@Dao
interface WardrobeToolsDao {
    @Query("SELECT * FROM calendar_entries ORDER BY date") fun calendar():kotlinx.coroutines.flow.Flow<List<CalendarEntry>>
    @Query("SELECT * FROM calendar_entries ORDER BY date") suspend fun calendarSnapshot():List<CalendarEntry>
    @Upsert suspend fun schedule(entry:CalendarEntry)
    @Query("DELETE FROM calendar_entries WHERE date=:date") suspend fun unschedule(date:String)
    @Query("SELECT COUNT(*) FROM restored_backups WHERE fingerprint=:fingerprint") suspend fun restored(fingerprint:String):Int
    @Insert suspend fun recordRestore(value:RestoredBackup)
    @Query("UPDATE garments SET color=:color,season=:season,occasion=:occasion,updatedAt=:now WHERE id=:id")
    suspend fun attributes(id:String,color:String,season:String,occasion:String,now:Long)
    @Query("UPDATE garments SET deletedAt=:time WHERE id=:id") suspend fun trashGarment(id:String,time:Long?)
    @Query("UPDATE outfits SET deletedAt=:time WHERE id=:id") suspend fun trashOutfit(id:String,time:Long?)
    @Query("UPDATE outfits SET collectionName=:name WHERE id=:id") suspend fun collection(id:String,name:String)
}

data class OutfitSuggestion(val garments:List<Garment>,val explanation:String)
/** Deterministic local selection: one outfit base, no unavailable items, diversify with a user-controlled seed. */
fun suggestOutfit(garments:List<Garment>,season:String,occasion:String,seed:Int):OutfitSuggestion? {
    val eligible=garments.filter{it.deletedAt==null && (season.isBlank() || it.season.isBlank() || it.season==season || it.season=="四季") &&
        (occasion.isBlank() || it.occasion.isBlank() || it.occasion==occasion)}
    val random=kotlin.random.Random(seed)
    fun pick(category:String):Garment? {
        val choices=eligible.filter{it.category==category};if(choices.isEmpty())return null
        val weights=choices.map{1+(if(it.favorite)2 else 0)+(if(season.isNotBlank()&&it.season==season)2 else 0)+(if(occasion.isNotBlank()&&it.occasion==occasion)2 else 0)}
        var roll=random.nextInt(weights.sum())
        choices.forEachIndexed{index,g->roll-=weights[index];if(roll<0)return g}
        return choices.last()
    }
    val top=pick("上衣"); val pants=pick("裤装"); val skirt=pick("裙装")
    val dress=skirt?.takeIf{"连衣" in it.name}
    val bottoms=listOfNotNull(pants,skirt?.takeUnless{"连衣" in it.name})
    val base=if(top!=null && bottoms.isNotEmpty())listOf(top,bottoms.random(random)) else if(dress!=null)listOf(dress) else return null
    val chosen=base+listOfNotNull(pick("鞋子"),pick("包包"),if(season=="冬季"||season=="秋季")pick("外套")else null)
    return OutfitSuggestion(chosen,"按${season.ifBlank{"不限季节"}}、${occasion.ifBlank{"不限场合"}}筛选，优先匹配标签与收藏；可换一组或进入画布调整。")
}
fun validateSchedule(date:String,worn:Boolean,today:LocalDate=LocalDate.now()) {
    val parsed=LocalDate.parse(date);require(parsed.toString()==date && (!worn || !parsed.isAfter(today)))
}
