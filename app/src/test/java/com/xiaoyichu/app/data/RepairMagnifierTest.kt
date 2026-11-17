package com.xiaoyichu.app.data

import org.junit.Assert.*
import org.junit.Test

class RepairMagnifierTest {
    @Test fun lensAvoidsContactAndStaysInsideForEdgesAndCorners() {
        for(x in listOf(0f,150f,300f))for(y in listOf(0f,250f,500f)) {
            val p=repairMagnifierPlacement(300f,500f,x,y,112f,12f,24f)
            assertTrue(p.left>=12f && p.top>=12f && p.left+p.size<=288f && p.top+p.size<=488f)
            assertFalse(x in p.left..p.left+p.size && y in p.top..p.top+p.size)
        }
    }
    @Test fun lensFlipsBelowNearTopAndFitsSmallLandscapeCanvas() {
        val top=repairMagnifierPlacement(600f,200f,80f,10f,112f,12f,24f)
        assertTrue(top.top>10f)
        val small=repairMagnifierPlacement(45f,30f,22f,15f,112f,12f,24f)
        assertTrue(small.size>0 && small.left>=0 && small.top>=0 && small.left+small.size<=45 && small.top+small.size<=30)
    }
}
