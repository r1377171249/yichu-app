package com.xiaoyichu.app.data

import org.junit.Assert.*
import org.junit.Test

class RepairRasterTest {
    @Test fun longRepairSessionKeepsStrokeOrderAndOriginalPixels() {
        val source=IntArray(10000){0x80db8fa2.toInt()}
        val strokes=List(201) { index -> RepairStroke(index%2==1,.2f,listOf(RepairPoint(.5f,.5f))) }
        val erased=RepairRaster.apply(source,source,100,100,strokes)
        assertEquals(0,erased[5050] ushr 24)
        val restored=RepairRaster.apply(source,source,100,100,strokes+strokes.last().copy(restore=true))
        assertEquals(source[5050],restored[5050])
        assertEquals(source[0],restored[0])
        assertArrayEquals(IntArray(10000){0x80db8fa2.toInt()},source)
    }
    @Test fun eraseAndRestoreUseOriginalColorsWithoutMutatingInputs() {
        val source=IntArray(10000){0xffdb8fa2.toInt()};val base=source.copyOf()
        val erase=RepairStroke(false,.2f,listOf(RepairPoint(.5f,.5f)))
        val erased=RepairRaster.apply(source,base,100,100,listOf(erase))
        assertEquals(0,erased[5050] ushr 24);assertEquals(source[0],erased[0]);assertArrayEquals(source,base)
        val restored=RepairRaster.apply(source,erased,100,100,listOf(erase.copy(restore=true)))
        assertEquals(source[5050],restored[5050]);assertArrayEquals(IntArray(10000){0xffdb8fa2.toInt()},source)
    }
    @Test fun restorePreservesOriginalTransparencyAndRepeatedPointsDoNotCompoundCoverage() {
        val source=IntArray(10000){0x8000aacc.toInt()};val base=IntArray(10000)
        val stroke=RepairStroke(true,.15f,listOf(RepairPoint(.5f,.5f)))
        val once=RepairRaster.apply(source,base,100,100,listOf(stroke))
        val repeated=RepairRaster.apply(source,base,100,100,listOf(stroke.copy(points=List(20){stroke.points[0]})))
        assertArrayEquals(once,repeated);assertEquals(128,once[5050] ushr 24)
    }
    @Test fun normalizedStrokeAndInterpolationCoverFullSegment() {
        val source=IntArray(10000){-1};val stroke=RepairStroke(false,.1f,listOf(RepairPoint(.1f,.5f),RepairPoint(.9f,.5f)))
        val result=RepairRaster.apply(source,source,100,100,listOf(stroke))
        assertEquals(0,result[5050] ushr 24);assertEquals(255,result[1050] ushr 24)
        assertFalse(stroke.copy(size=Float.NaN).valid());assertFalse(stroke.copy(points=listOf(RepairPoint(-1f,0f))).valid())
    }
}
