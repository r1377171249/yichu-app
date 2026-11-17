package com.xiaoyichu.app.data

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class WardrobeToolsTest {
    private fun garment(id:String,category:String="上衣")=Garment(id,"白衬衫 $id",category,"garments/$id.original","thumbnails/$id.png",createdAt=100,color="白色",season="四季",occasion="通勤")
    @Test fun searchCombinesAllConditionsAndExcludesTrash() {
        val a=garment("a");val b=garment("b").copy(color="蓝色");val c=garment("c").copy(deletedAt=1)
        assertEquals(listOf(a),filterWardrobe(listOf(a,b,c),WardrobeQuery(text="白衬衫 通勤",color="白色",season="夏季",occasion="通勤")))
        assertTrue(filterWardrobe(listOf(a),WardrobeQuery(category="鞋子")).isEmpty())
        assertTrue(filterWardrobe(listOf(a),WardrobeQuery(text="不存在")).isEmpty())
    }
    @Test fun sortingIsStableAndFavoritesDoNotDiscardOtherItems() {
        val a=garment("a");val b=garment("b").copy(favorite=true)
        assertEquals(listOf("b","a"),filterWardrobe(listOf(a,b),WardrobeQuery(sort="收藏优先")).map{it.id})
        assertEquals(listOf("a","b"),filterWardrobe(listOf(b,a),WardrobeQuery()).map{it.id})
    }
    @Test fun recommendationsRespectSeasonAndAvailabilityAndRequireBase() {
        val top=garment("top");val pants=garment("pants","裤装");val winter=garment("coat","外套").copy(season="冬季")
        val trash=garment("shoe","鞋子").copy(deletedAt=1)
        val result=suggestOutfit(listOf(top,pants,winter,trash),"夏季","通勤",4)!!
        assertEquals(setOf("top","pants"),result.garments.map{it.id}.toSet())
        assertNull(suggestOutfit(listOf(top),"","",1))
        assertNull(suggestOutfit(listOf(top,pants),"夏季","运动",1))
    }
    @Test fun scheduleRejectsFutureActualWearButAllowsFuturePlan() {
        val today=LocalDate.of(2026,10,19)
        validateSchedule("2026-10-20",false,today)
        validateSchedule("2026-10-18",true,today)
        assertThrows(IllegalArgumentException::class.java){validateSchedule("2026-10-20",true,today)}
        assertThrows(Exception::class.java){validateSchedule("2026-02-30",false,today)}
    }
}
