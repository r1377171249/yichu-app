package com.xiaoyichu.app.data

import kotlin.math.*

data class RepairPoint(val x:Float,val y:Float)
data class RepairStroke(val restore:Boolean,val size:Float,val points:List<RepairPoint>) {
    fun valid() = size.isFinite() && size in .005f.. .2f && points.size in 1..500 &&
        points.all { it.x.isFinite() && it.y.isFinite() && it.x in 0f..1f && it.y in 0f..1f }
}
data class RepairRect(val left:Int,val top:Int,val right:Int,val bottom:Int)

/** Union coverage within one stroke prevents repeated points from eroding a soft edge twice. */
class RepairBrush(private val source:IntArray,val pixels:IntArray,private val width:Int,private val height:Int,
    private val restore:Boolean,diameter:Float) {
    private val beforeAlpha=ByteArray(pixels.size) { (pixels[it] ushr 24).toByte() }
    private val coverage=ByteArray(pixels.size)
    private val radius=diameter*min(width,height)/2f
    private var previous:RepairPoint?=null
    init { require(width>0 && height>0 && source.size.toLong()==width.toLong()*height && pixels.size==source.size && diameter.isFinite() && diameter in .005f.. .2f) }
    fun add(point:RepairPoint):RepairRect {
        require(point.x.isFinite() && point.y.isFinite() && point.x in 0f..1f && point.y in 0f..1f)
        val prior=previous ?: point;previous=point
        val ax=prior.x*width;val ay=prior.y*height;val bx=point.x*width;val by=point.y*height
        val left=floor(min(ax,bx)-radius-1).toInt().coerceIn(0,width)
        val top=floor(min(ay,by)-radius-1).toInt().coerceIn(0,height)
        val right=ceil(max(ax,bx)+radius+1).toInt().coerceIn(0,width)
        val bottom=ceil(max(ay,by)+radius+1).toInt().coerceIn(0,height)
        val dx=bx-ax;val dy=by-ay;val length=dx*dx+dy*dy
        for(y in top until bottom)for(x in left until right) {
            val t=if(length>0) (((x+.5f-ax)*dx+(y+.5f-ay)*dy)/length).coerceIn(0f,1f) else 0f
            val distance=hypot(x+.5f-(ax+t*dx),y+.5f-(ay+t*dy))
            val value=((radius+.5f-distance).coerceIn(0f,1f)*255).roundToInt()
            val index=y*width+x
            if(value<=(coverage[index].toInt() and 255))continue
            coverage[index]=value.toByte()
            val before=beforeAlpha[index].toInt() and 255
            val wanted=if(restore)source[index] ushr 24 else 0
            val alpha=(before*(255-value)+wanted*value+127)/255
            pixels[index]=(source[index] and 0x00ffffff) or (alpha shl 24)
        }
        return RepairRect(left,top,right,bottom)
    }
}

object RepairRaster {
    fun apply(source:IntArray,base:IntArray,width:Int,height:Int,strokes:List<RepairStroke>):IntArray {
        require(strokes.all{it.valid()})
        require(width>0 && height>0 && source.size.toLong()==width.toLong()*height && base.size==source.size)
        val result=base.copyOf()
        strokes.forEach { stroke -> val brush=RepairBrush(source,result,width,height,stroke.restore,stroke.size)
            stroke.points.forEach(brush::add) }
        return result
    }
}
