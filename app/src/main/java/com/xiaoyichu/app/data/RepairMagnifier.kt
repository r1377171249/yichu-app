package com.xiaoyichu.app.data

import kotlin.math.max
import kotlin.math.min

data class MagnifierPlacement(val left:Float,val top:Float,val size:Float)

/** Place beside the contact, flip below near the top, and keep the lens inside its canvas. */
fun repairMagnifierPlacement(width:Float,height:Float,x:Float,y:Float,diameter:Float,margin:Float,gap:Float):MagnifierPlacement {
    require(listOf(width,height,x,y,diameter,margin,gap).all{it.isFinite()} && width>0 && height>0 && diameter>0 && margin>=0 && gap>=0)
    val edge=min(margin,min(width,height)/4)
    val side=min(diameter,min(width,height)-2*edge)
    val right=x+gap;val left=x-gap-side;val above=y-gap-side;val below=y+gap
    val preferred=if(x<width/2)listOf(right to above,right to below,left to above,left to below)
        else listOf(left to above,left to below,right to above,right to below)
    val candidates=preferred.map{(a,b)->MagnifierPlacement(a.coerceIn(edge,width-edge-side),b.coerceIn(edge,height-edge-side),side)}
    fun distance(p:MagnifierPlacement):Float {
        val dx=max(max(p.left-x,x-p.left-side),0f);val dy=max(max(p.top-y,y-p.top-side),0f)
        return dx*dx+dy*dy
    }
    return candidates.firstOrNull{distance(it)>=gap*gap} ?: candidates.maxBy{distance(it)}
}
