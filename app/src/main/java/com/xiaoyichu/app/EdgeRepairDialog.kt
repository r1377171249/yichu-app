package com.xiaoyichu.app

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.findViewTreeViewModelStoreOwner
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.*
import com.xiaoyichu.app.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.*

// Retain potentially long histories across rotation without putting them in the Activity Bundle.
class RepairHistoryViewModel : ViewModel() {
    private var imageKey: Pair<String,String>? = null
    val history = mutableStateOf(emptyList<RepairStroke>())
    val redo = mutableStateOf(emptyList<RepairStroke>())
    fun bind(source:String,cutout:String) {
        val next=source to cutout
        if(imageKey!=next) { clear();imageKey=next }
    }
    fun clear() { history.value=emptyList();redo.value=emptyList() }
}
private class RepairPreview(val source:Bitmap,val result:Bitmap,val originalPixels:IntArray,val basePixels:IntArray) {
    var pixels=basePixels.copyOf()
    var renderedHistory:List<RepairStroke>?=null
    var rebuildVersion=-1
}
private data class RepairLoad(val preview:RepairPreview?=null,val failed:Boolean=false)
private fun repairFit(width:Float,height:Float,image:Bitmap,padding:Float,zoom:Float,panX:Float,panY:Float):Rect {
    val scale=min((width-2*padding).coerceAtLeast(1f)/image.width,(height-2*padding).coerceAtLeast(1f)/image.height)
    val w=image.width*scale*zoom;val h=image.height*scale*zoom
    val dx=(panX*w).coerceIn(-max(0f,(w-width)/2),max(0f,(w-width)/2))
    val dy=(panY*h).coerceIn(-max(0f,(h-height)/2),max(0f,(h-height)/2))
    return Rect((width-w)/2+dx,(height-h)/2+dy,(width+w)/2+dx,(height+h)/2+dy)
}

@Composable
fun EdgeRepairDialog(source:File,cutout:File,onCancel:()->Unit,onConfirm:(List<RepairStroke>)->Unit) {
    val owner=checkNotNull(LocalView.current.findViewTreeViewModelStoreOwner())
    val retained=remember(owner){ViewModelProvider(owner)[RepairHistoryViewModel::class.java]}
    retained.bind(source.path,cutout.path)
    var history by retained.history
    var redo by retained.redo
    val cancel={retained.clear();onCancel()}
    var restore by rememberSaveable { mutableStateOf(false) }
    var brushSize by rememberSaveable { mutableFloatStateOf(.06f) }
    var showOriginal by rememberSaveable { mutableStateOf(false) }
    var moving by rememberSaveable { mutableStateOf(false) }
    var zoom by rememberSaveable { mutableFloatStateOf(1f) }
    var panX by rememberSaveable { mutableFloatStateOf(0f) }
    var panY by rememberSaveable { mutableFloatStateOf(0f) }
    var painting by remember { mutableStateOf(false) }
    var rendering by remember { mutableStateOf(true) }
    var frame by remember { mutableIntStateOf(0) }
    var rebuild by remember { mutableIntStateOf(0) }
    var cursor by remember { mutableStateOf<Offset?>(null) }
    var contact by remember { mutableStateOf<Offset?>(null) }
    val lensLabel=remember { android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color=android.graphics.Color.rgb(55,49,51);textAlign=android.graphics.Paint.Align.CENTER
    } }
    val load by produceState(RepairLoad(),source.path,cutout.path) {
        value=withContext(Dispatchers.IO) {
            try {
                val base=decodeThumbnail(cutout,768);val raw=decodeThumbnail(source,1280)
                val original=Bitmap.createScaledBitmap(raw,base.width,base.height,true)
                if(original!==raw)raw.recycle()
                val result=checkNotNull(base.copy(Bitmap.Config.ARGB_8888,true))
                val originalPixels=IntArray(base.width*base.height);val basePixels=IntArray(originalPixels.size)
                original.getPixels(originalPixels,0,base.width,0,0,base.width,base.height)
                base.getPixels(basePixels,0,base.width,0,0,base.width,base.height);base.recycle()
                RepairLoad(RepairPreview(original,result,originalPixels,basePixels))
            } catch(cancel:CancellationException){throw cancel}
            catch(_:Exception){RepairLoad(failed=true)}
        }
    }
    val preview=load.preview
    LaunchedEffect(preview,history,rebuild) {
        if(preview==null)return@LaunchedEffect
        rendering=true
        if(preview.renderedHistory!==history || preview.rebuildVersion!=rebuild) {
            val pixels=withContext(Dispatchers.Default) { RepairRaster.apply(preview.originalPixels,preview.basePixels,preview.result.width,preview.result.height,history) }
            preview.pixels=pixels;preview.result.setPixels(pixels,0,preview.result.width,0,0,preview.result.width,preview.result.height)
            preview.renderedHistory=history;preview.rebuildVersion=rebuild
        }
        frame++;rendering=false
    }
    Dialog(onDismissRequest=cancel,properties=DialogProperties(usePlatformDefaultWidth=false)) {
        BackHandler(onBack=cancel)
        Surface(Modifier.fillMaxSize(),color=Paper) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal=12.dp)) {
                Row(Modifier.fillMaxWidth().height(56.dp),verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
                    TextButton(onClick=cancel){Text("取消修补")}
                    Text("修补边缘",Modifier.weight(1f),fontSize=17.sp)
                    GlyphButton("undo","撤销修补",!painting&&!rendering&&history.isNotEmpty()) {
                        redo=redo+history.last();rendering=true;history=history.dropLast(1)
                    }
                    GlyphButton("redo","重做修补",!painting&&!rendering&&redo.isNotEmpty()) {
                        rendering=true;history=history+redo.last();redo=redo.dropLast(1)
                    }
                }
                val previewContent: @Composable ()->Unit = {
                if(preview!=null) {
                    Canvas(Modifier.fillMaxSize().clipToBounds().semantics {
                        contentDescription="边缘修补画布"
                        stateDescription=if(cursor!=null&&!moving&&!showOriginal)"局部放大镜已显示" else "局部放大镜已隐藏"
                    }
                        .pointerInput(preview,restore,brushSize,rendering,showOriginal,moving,zoom) {
                            if(rendering||showOriginal)return@pointerInput
                            val image=preview.result
                            var points=mutableListOf<RepairPoint>();var brush:RepairBrush?=null
                            fun paint(position:Offset) {
                                contact=position
                                val rect=repairFit(size.width.toFloat(),size.height.toFloat(),image,12.dp.toPx(),zoom,panX,panY)
                                val point=RepairPoint(((position.x-rect.left)/rect.width).coerceIn(0f,1f),((position.y-rect.top)/rect.height).coerceIn(0f,1f))
                                if(points.size>=500 || points.lastOrNull()==point)return
                                points.add(point);val changed=brush?.add(point) ?: return
                                image.setPixels(preview.pixels,changed.top*image.width+changed.left,image.width,
                                    changed.left,changed.top,changed.right-changed.left,changed.bottom-changed.top)
                                cursor=Offset(rect.left+point.x*rect.width,rect.top+point.y*rect.height);frame++
                            }
                            awaitEachGesture {
                                val down=awaitFirstDown()
                                val rect=repairFit(size.width.toFloat(),size.height.toFloat(),image,12.dp.toPx(),zoom,panX,panY)
                                if(!moving && !rect.contains(down.position))return@awaitEachGesture
                                points=mutableListOf();painting=true;down.consume()
                                if(!moving){brush=RepairBrush(preview.originalPixels,preview.pixels,image.width,image.height,restore,brushSize);paint(down.position)}
                                var completed=false
                                try {
                                    do {
                                        val event=awaitPointerEvent();val change=event.changes.firstOrNull{it.id==down.id}
                                        if(change!=null) {
                                            if(moving) {
                                                val fit=repairFit(size.width.toFloat(),size.height.toFloat(),image,12.dp.toPx(),zoom,panX,panY)
                                                val maxX=max(0f,(1-size.width/fit.width)/2);val maxY=max(0f,(1-size.height/fit.height)/2)
                                                panX=(panX+(change.position.x-change.previousPosition.x)/fit.width).coerceIn(-maxX,maxX)
                                                panY=(panY+(change.position.y-change.previousPosition.y)/fit.height).coerceIn(-maxY,maxY)
                                            } else if(change.pressed)paint(change.position)
                                        }
                                        event.changes.forEach{it.consume()}
                                    } while(event.changes.any{it.id==down.id && it.pressed})
                                    completed=true
                                } finally {
                                    brush=null;painting=false;cursor=null;contact=null
                                    if(!moving) {
                                        if(completed && points.isNotEmpty()) {
                                            val stroke=RepairStroke(restore,brushSize,points.toList());points.clear()
                                            // The live brush already applied this stroke; avoid replaying all earlier strokes.
                                            val next=history+stroke
                                            preview.renderedHistory=next;preview.rebuildVersion=rebuild
                                            rendering=true;history=next;redo=emptyList()
                                        } else {points.clear();rebuild++}
                                    }
                                }
                            }
                        }) {
                        if(frame<0)return@Canvas
                        val image=if(showOriginal)preview.source else preview.result
                        val rect=repairFit(size.width,size.height,image,12.dp.toPx(),zoom,panX,panY)
                        clipRect(rect.left,rect.top,rect.right,rect.bottom) {
                            val tile=10.dp.toPx();val columns=ceil(rect.width/tile).toInt();val rows=ceil(rect.height/tile).toInt()
                            val firstX=max(0,floor(-rect.left/tile).toInt());val firstY=max(0,floor(-rect.top/tile).toInt())
                            val lastX=min(columns,ceil((size.width-rect.left)/tile).toInt());val lastY=min(rows,ceil((size.height-rect.top)/tile).toInt())
                            for(y in firstY..lastY)for(x in firstX..lastX)drawRect(if((x+y)%2==0)Color(0xFFF2EFED) else Color.White,
                                Offset(rect.left+x*tile,rect.top+y*tile),Size(tile,tile))
                            drawImage(image.asImageBitmap(),dstOffset=IntOffset(rect.left.roundToInt(),rect.top.roundToInt()),dstSize=IntSize(rect.width.roundToInt().coerceAtLeast(1),rect.height.roundToInt().coerceAtLeast(1)))
                        }
                        cursor?.let { drawCircle(if(restore)Color(0xFF618D7C) else Pink,brushSize*min(rect.width,rect.height)/2,it,style=Stroke(1.dp.toPx())) }
                        if(!moving&&!showOriginal)cursor?.let { focus ->
                            val touch=contact ?: focus
                            val placement=repairMagnifierPlacement(size.width,size.height,touch.x,touch.y,112.dp.toPx(),12.dp.toPx(),24.dp.toPx())
                            val lens=Rect(placement.left,placement.top,placement.left+placement.size,placement.top+placement.size)
                            val radius=16.dp.toPx();val modeColor=if(restore)Color(0xFF618D7C) else Pink
                            drawRoundRect(Color.Black.copy(alpha=.14f),lens.topLeft+Offset(0f,3.dp.toPx()),lens.size,CornerRadius(radius))
                            val outline=Path().apply {addRoundRect(RoundRect(lens,CornerRadius(radius)))}
                            clipPath(outline) {
                                drawRect(Color.White,lens.topLeft,lens.size)
                                val tile=8.dp.toPx();val count=ceil(lens.width/tile).toInt()
                                for(y in 0..count)for(x in 0..count)if((x+y)%2==0)
                                    drawRect(Color(0xFFF2EFED),lens.topLeft+Offset(x*tile,y*tile),Size(tile,tile))
                                val factor=3f
                                val enlarged=lens.center-(focus-rect.topLeft)*factor
                                drawImage(image.asImageBitmap(),dstOffset=IntOffset(enlarged.x.roundToInt(),enlarged.y.roundToInt()),
                                    dstSize=IntSize((rect.width*factor).roundToInt().coerceAtLeast(1),(rect.height*factor).roundToInt().coerceAtLeast(1)))
                                drawCircle(modeColor,brushSize*min(rect.width,rect.height)*factor/2,lens.center,style=Stroke(1.dp.toPx()))
                                val arm=4.dp.toPx()
                                drawLine(modeColor,lens.center-Offset(arm,0f),lens.center+Offset(arm,0f),1.dp.toPx())
                                drawLine(modeColor,lens.center-Offset(0f,arm),lens.center+Offset(0f,arm),1.dp.toPx())
                                val badge=Offset(lens.right-17.dp.toPx(),lens.bottom-15.dp.toPx())
                                drawCircle(Color.White.copy(alpha=.94f),12.dp.toPx(),badge)
                                lensLabel.textSize=10.sp.toPx()
                                drawContext.canvas.nativeCanvas.drawText("3×",badge.x,badge.y-(lensLabel.ascent()+lensLabel.descent())/2,lensLabel)
                            }
                            drawRoundRect(Color.White,lens.topLeft,lens.size,CornerRadius(radius),style=Stroke(4.dp.toPx()))
                            drawRoundRect(modeColor,lens.topLeft,lens.size,CornerRadius(radius),style=Stroke(1.5.dp.toPx()))
                        }
                    }
                } else Box(Modifier.fillMaxSize(),androidx.compose.ui.Alignment.Center) {
                    if(load.failed)Text("图片暂不可用，请取消后重试") else CircularProgressIndicator()
                }
                }
                val controlsContent: @Composable ()->Unit = {
                Row(Modifier.fillMaxWidth(),verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
                    FilterChip(!restore&&!moving,{restore=false;moving=false},enabled=!painting&&!rendering,label={Text("擦除")})
                    Spacer(Modifier.width(6.dp));FilterChip(restore&&!moving,{restore=true;moving=false},enabled=!painting&&!rendering,label={Text("恢复")})
                    Spacer(Modifier.width(6.dp));FilterChip(moving,{moving=true},enabled=!painting&&!rendering,label={Text("移动")})
                    Spacer(Modifier.weight(1f));TextButton(onClick={showOriginal=!showOriginal},enabled=!painting&&!rendering){Text(if(showOriginal)"结果" else "原图")}
                }
                Row(verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
                    Text("画笔 ${(brushSize*1000).roundToInt()/10f}% · 已修补 ${history.size} 笔",Modifier.weight(1f),fontSize=12.sp)
                    TextButton(onClick={zoom=(zoom/1.5f).coerceAtLeast(1f);panX=0f;panY=0f},enabled=!painting&&!rendering&&zoom>1f,modifier=Modifier.size(40.dp).semantics{contentDescription="缩小修补照片"}){Text("−")}
                    Text("${(zoom*10).roundToInt()/10f}×",fontSize=12.sp)
                    TextButton(onClick={zoom=(zoom*1.5f).coerceAtMost(4f);panX=0f;panY=0f},enabled=!painting&&!rendering&&zoom<4f,modifier=Modifier.size(40.dp).semantics{contentDescription="放大修补照片"}){Text("＋")}
                }
                Slider(brushSize,{brushSize=it},enabled=!painting&&!rendering,valueRange=.005f.. .16f,
                    modifier=Modifier.fillMaxWidth().semantics{contentDescription="修补画笔大小"})
                Text(if(rendering&&preview!=null)"正在更新预览…" else if(showOriginal)"原图对照 · 切回结果后继续修补" else "可放大并移动查看细节；原图始终保留。",fontSize=12.sp)
                Button(onClick={val strokes=history;retained.clear();onConfirm(strokes)},enabled=preview!=null&&!rendering&&!painting&&history.isNotEmpty(),modifier=Modifier.fillMaxWidth().padding(vertical=10.dp)){Text("确认修补")}
                }
                val config=LocalConfiguration.current
                if(config.screenWidthDp>config.screenHeightDp)Row(Modifier.weight(1f).fillMaxWidth()) {
                    Box(Modifier.weight(1f).fillMaxHeight()){previewContent()}
                    Column(Modifier.width(280.dp).fillMaxHeight().verticalScroll(rememberScrollState())){controlsContent()}
                } else {
                    Box(Modifier.weight(1f).fillMaxWidth()){previewContent()};controlsContent()
                }
            }
        }
    }
}
