package com.xiaoyichu.app.data

import kotlin.math.abs
import kotlin.math.roundToInt

val CutoutModes=listOf("保留细节","清理残留","原始模型")

/** Color-guided 3x3 alpha refinement. RGB is never repainted; source transparency is respected. */
fun refineCutout(source:IntArray,mask:IntArray,width:Int,height:Int,mode:String):IntArray {
    require(width>0&&height>0&&width.toLong()*height==source.size.toLong()&&mask.size==source.size&&mode in CutoutModes)
    fun channel(p:Int,c:Int)=(p ushr c) and 255
    return IntArray(source.size){i->
        val original=source[i];val alpha=mask[i] and 255
        var guided=alpha.toFloat()
        if(mode!="原始模型"&&alpha in 1..254) {
            val x=i%width;val y=i/width;var sum=alpha*4f;var weights=4f
            for(dy in -1..1)for(dx in -1..1){
                if(dx==0&&dy==0)continue
                val xx=x+dx;val yy=y+dy;if(xx !in 0 until width || yy !in 0 until height)continue
                val index=yy*width+xx;val other=source[index]
                val difference=abs(channel(original,16)-channel(other,16))+abs(channel(original,8)-channel(other,8))+abs(channel(original,0)-channel(other,0))
                val weight=1f/(1f+difference/24f)
                sum+=(mask[index] and 255)*weight;weights+=weight
            }
            guided=sum/weights
        }
        val refined=when(mode) {
            "清理残留" -> {val t=((guided-48f)/184f).coerceIn(0f,1f);t*t*(3-2*t)*255f}
            "保留细节" -> {val t=((guided-4f)/247f).coerceIn(0f,1f);t*t*(3-2*t)*255f}
            else -> guided
        }
        val resultAlpha=(refined.roundToInt().coerceIn(0,255)*channel(original,24))/255
        (original and 0x00ffffff) or (resultAlpha shl 24)
    }
}
