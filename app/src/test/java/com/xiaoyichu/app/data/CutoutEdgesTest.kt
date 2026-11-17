package com.xiaoyichu.app.data

import org.junit.Assert.*
import org.junit.Test

class CutoutEdgesTest {
    @Test fun modesPreserveRgbInputAndOriginalTransparency() {
        val pixels=intArrayOf(0xfffa0a05.toInt(),0x80fa0a05.toInt(),0x00fa0a05)
        val mask=intArrayOf(255,255,255)
        for(mode in CutoutModes){
            val output=refineCutout(pixels,mask,3,1,mode)
            assertArrayEquals(pixels,output)
        }
        assertArrayEquals(intArrayOf(255,255,255),mask)
    }
    @Test fun cleanupSuppressesLowAlphaHazeMoreThanDetailMode() {
        val pixels=IntArray(9){0xffcc7788.toInt()};val mask=IntArray(9){32}
        val detail=refineCutout(pixels,mask,3,3,"保留细节")
        val clean=refineCutout(pixels,mask,3,3,"清理残留")
        assertTrue((detail[4] ushr 24)>0);assertEquals(0,clean[4] ushr 24)
        assertEquals(0x00cc7788,clean[4] and 0x00ffffff)
    }
    @Test fun originalModeReproducesRawAlphaAndInvalidShapesFail() {
        val pixels=intArrayOf(0xff123456.toInt(),0xff123456.toInt())
        val result=refineCutout(pixels,intArrayOf(31,211),2,1,"原始模型")
        assertEquals(31,result[0] ushr 24);assertEquals(211,result[1] ushr 24)
        assertThrows(IllegalArgumentException::class.java){refineCutout(pixels,intArrayOf(1),2,1,"保留细节")}
    }
}
