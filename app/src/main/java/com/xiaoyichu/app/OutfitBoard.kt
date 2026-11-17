package com.xiaoyichu.app

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import com.xiaoyichu.app.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.*

@Composable
fun OutfitBoard(items: List<OutfitItem>, garments: List<Garment>, model: WardrobeViewModel,
    selectedId: String? = null, interactive: Boolean = false, onSelect: (String?) -> Unit = {},
    onBegin: () -> Unit = {}, onTransform: (Float, Float, Float, Float) -> Unit = { _, _, _, _ -> },
    onEnd: () -> Unit = {}, modifier: Modifier = Modifier) {
    val assets = items.map { it.garmentId }.distinct().sorted().mapNotNull { id ->
        garments.find { it.id == id }?.let { id to model.artwork(it) }
    }
    val images by produceState<Map<String, Bitmap>>(emptyMap(), assets, interactive) {
        value = emptyMap()
        value = withContext(Dispatchers.IO) { assets.mapNotNull { (id, file) ->
            try { id to decodeThumbnail(file, if (interactive) 512 else 384) }
            catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { null }
        }.toMap() }
    }
    val latestItems by rememberUpdatedState(items); val latestImages by rememberUpdatedState(images)
    val latestSelect by rememberUpdatedState(onSelect); val latestBegin by rememberUpdatedState(onBegin)
    val latestTransform by rememberUpdatedState(onTransform); val latestEnd by rememberUpdatedState(onEnd)
    val latestSelected by rememberUpdatedState(selectedId)
    Canvas(modifier.background(Color(OutfitRenderer.Background)).clipToBounds().semantics { contentDescription = "搭配画布，${items.size} 个单品" }
        .pointerInput(interactive) {
            if (!interactive) return@pointerInput
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val selection = latestItems.find { it.id == latestSelected }
                var handleMode: String? = null
                if (selection != null) {
                    val image = latestImages[selection.garmentId]
                    val w = selection.width * size.width
                    val h = w * (image?.let { selection.imageRatio(it.width,it.height) } ?: 1f)
                    val dx = down.position.x - selection.x * size.width
                    val dy = down.position.y - selection.y * size.height
                    val radians = Math.toRadians(selection.rotation.toDouble())
                    val local = Offset((dx*cos(radians)+dy*sin(radians)).toFloat(),(-dx*sin(radians)+dy*cos(radians)).toFloat())
                    val radius = 17.dp.toPx()
                    if ((local-Offset(w/2+6.dp.toPx(),-h/2-15.dp.toPx())).getDistance()<=radius) handleMode="rotate"
                    else if(listOf(Offset(-w/2,-h/2),Offset(0f,-h/2),Offset(w/2,-h/2),Offset(-w/2,0f),Offset(w/2,0f),Offset(-w/2,h/2),Offset(0f,h/2),Offset(w/2,h/2)).any { (local-it).getDistance()<=radius })handleMode="scale"
                }
                val hit = if(handleMode!=null) selection else latestItems.sortedByDescending { it.zIndex }.firstOrNull { item ->
                    val image = latestImages[item.garmentId]
                    item.contains(down.position.x, down.position.y, size.width.toFloat(), size.height.toFloat(),
                        image?.let { item.imageRatio(it.width, it.height) } ?: 1f)
                }
                latestSelect(hit?.id); if (hit != null) latestBegin()
                try {
                    do {
                        val event = awaitPointerEvent()
                        if (hit != null) {
                            val pan = event.calculatePan(); val zoom = event.calculateZoom(); val angle = event.calculateRotation()
                            val centroid = event.calculateCentroid(useCurrent = false)
                            val active = latestItems.find { it.id == hit.id }
                            val pointer = event.changes.firstOrNull()
                            if (active != null && handleMode != null && pointer != null && event.changes.count { it.pressed } == 1) {
                                val center = Offset(active.x*size.width,active.y*size.height)
                                val old = pointer.previousPosition-center; val next = pointer.position-center
                                val scale = if(handleMode=="scale" && old.getDistance()>1f) next.getDistance()/old.getDistance() else 1f
                                val rotation = if(handleMode=="rotate")Math.toDegrees((atan2(next.y,next.x)-atan2(old.y,old.x)).toDouble()).toFloat() else 0f
                                latestTransform(0f,0f,scale,rotation)
                            } else if (active != null && centroid.isSpecified) {
                                val delta = active.anchoredDelta(centroid.x, centroid.y, pan.x, pan.y, zoom, angle, size.width.toFloat(), size.height.toFloat())
                                latestTransform(delta.first, delta.second, zoom, angle)
                            }
                            event.changes.forEach { if (it.pressed || it.previousPressed) it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                } finally { if (hit != null) latestEnd() }
            }
        }) {
        items.sortedBy { it.zIndex }.forEach { item ->
            val image = images[item.garmentId]
            val width = item.width * size.width
            val height = width * (image?.let { item.imageRatio(it.width, it.height) } ?: 1f)
            val center = Offset(item.x * size.width, item.y * size.height)
            if(image != null) drawIntoCanvas { OutfitRenderer.drawPiece(it.nativeCanvas, item, image, size.width, size.height) }
            else rotate(item.rotation, center) { drawRect(Color(0xFFD4C3C7), Offset(center.x-width/2,center.y-height/2), Size(width,height), style=Stroke(1.dp.toPx())) }
            if (item.id == selectedId) rotate(item.rotation, center) {
                val left = center.x-width/2; val top = center.y-height/2
                drawRect(Color(0xFFF084A0),Offset(left,top),Size(width,height),style=Stroke(1.dp.toPx()))
                listOf(Offset(left,top),Offset(center.x,top),Offset(left+width,top),Offset(left,center.y),
                    Offset(left+width,center.y),Offset(left,top+height),Offset(center.x,top+height),Offset(left+width,top+height)).forEach {
                    drawCircle(Paper,4.5.dp.toPx(),it); drawCircle(Color(0xFFF084A0),4.5.dp.toPx(),it,style=Stroke(1.dp.toPx()))
                }
                val handle=Offset(left+width+6.dp.toPx(),top-15.dp.toPx())
                drawLine(Color(0xFFF084A0), Offset(left+width,top),handle,1.dp.toPx())
                drawCircle(Pink,10.dp.toPx(),handle)
                drawArc(Color.White,35f,285f,false,handle-Offset(5.dp.toPx(),5.dp.toPx()),Size(10.dp.toPx(),10.dp.toPx()),style=Stroke(1.dp.toPx()))
            }
        }
    }
}
