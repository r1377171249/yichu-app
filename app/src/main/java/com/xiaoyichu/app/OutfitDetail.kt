package com.xiaoyichu.app

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xiaoyichu.app.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun OutfitList(state: WardrobeState, model: WardrobeViewModel, onNew: () -> Unit, modifier: Modifier,
    onDetailChange: (Boolean) -> Unit = {}) {
    var calendarOpen by rememberSaveable{mutableStateOf(false)}
    var recommendOpen by rememberSaveable{mutableStateOf(false)}
    var collectionFilter by rememberSaveable{mutableStateOf("")}
    var detailId by rememberSaveable { mutableStateOf<String?>(null) }
    val saved = state.activeOutfits.find { it.outfit.id == detailId }
    LaunchedEffect(detailId) { onDetailChange(detailId != null) }
    LaunchedEffect(state.outfitsLoading, state.outfits, detailId) {
        if(!state.outfitsLoading && !state.outfitsLoadFailed && detailId != null && saved == null) detailId = null
    }
    if(calendarOpen)CalendarPanel(state,model){calendarOpen=false}
    if(recommendOpen)RecommendPanel(state,model){recommendOpen=false}
    val displayed=state.activeOutfits.filter{collectionFilter.isBlank()||it.outfit.collectionName==collectionFilter}
    if(detailId != null && saved != null) {
        BackHandler { detailId = null }
        OutfitDetail(saved,state,model,{detailId=null},modifier)
    } else Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment=Alignment.CenterVertically) {
            Text("我的搭配",Modifier.weight(1f),fontSize=19.sp,fontWeight=FontWeight.Medium)
            TextButton(onClick=onNew,enabled=!state.busy) { Text("新建搭配") }
        }
        Row {
            TextButton(onClick={calendarOpen=true},enabled=!state.busy){Text("穿搭日历")}
            TextButton(onClick={recommendOpen=true},enabled=!state.busy){Text("搭配灵感")}
        }
        ChoiceField("合集",collectionFilter,listOf("")+state.activeOutfits.map{it.outfit.collectionName}.filter{it.isNotBlank()}.distinct().sorted()){collectionFilter=it}
        when {
            state.outfitsLoading -> CircularProgressIndicator()
            state.outfitsLoadFailed -> TextButton(onClick=model::reloadOutfits) { Text("搭配暂时无法读取，点击重试") }
            displayed.isEmpty() -> Text("这里会收好你的穿搭灵感，先新建一套搭配。",Modifier.padding(20.dp))
            else -> LazyVerticalGrid(GridCells.Fixed(2),horizontalArrangement=Arrangement.spacedBy(12.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
                items(displayed,key={it.outfit.id}) { item ->
                    Column(Modifier.clip(RoundedCornerShape(16.dp)).background(Color.White).clickable(enabled=!state.busy){detailId=item.outfit.id}) {
                        OutfitBoard(item.items,state.garments,model,modifier=Modifier.fillMaxWidth().aspectRatio(.8f).clip(RoundedCornerShape(16.dp)))
                        Text(item.outfit.name,Modifier.padding(start=10.dp,top=9.dp,end=10.dp),fontSize=15.sp,maxLines=1,overflow=TextOverflow.Ellipsis)
                        Text("${item.items.size} 件单品${if(item.outfit.todayDate==java.time.LocalDate.now().toString())" · 今日穿搭" else ""}",Modifier.padding(10.dp),color=Color.Gray,fontSize=12.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun OutfitDetail(saved: SavedOutfit, state: WardrobeState, model: WardrobeViewModel, onClose: () -> Unit, modifier: Modifier) {
    var collecting by rememberSaveable(saved.outfit.id){mutableStateOf(false)}
    var collectionName by rememberSaveable(saved.outfit.id){mutableStateOf(saved.outfit.collectionName)}
    var editing by rememberSaveable(saved.outfit.id) { mutableStateOf(false) }
    var deleting by rememberSaveable(saved.outfit.id) { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var exporting by remember { mutableStateOf(false) }
    var exportPath by rememberSaveable { mutableStateOf<String?>(null) }
    val context=LocalContext.current; val scope=rememberCoroutineScope()
    val creator=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri ->
        if(uri!=null && exportPath!=null) scope.launch {
            try { OutfitRenderer.write(context,File(exportPath!!),uri); model.showMessage("图片已导出") }
            catch(cancel:CancellationException){throw cancel}
            catch(_:Exception){model.showMessage("图片导出失败，请重新选择保存位置")}
        }
    }
    fun export(share:Boolean) { if(exporting)return; exporting=true; scope.launch {
        try {
            val file=OutfitRenderer.export(context,saved,state.garments); exportPath=file.path
            if(share)OutfitRenderer.share(context,file) else creator.launch("${saved.outfit.name.replace(Regex("[\\\\/:*?\"<>|]"),"_")}.png")
        } catch(cancel:CancellationException){throw cancel}
        catch(_:Exception){model.showMessage("图片未生成，请检查衣物图片和存储空间后重试")}
        finally { exporting=false }
    } }
    Column(modifier.fillMaxWidth()) {
        ScreenHeader("搭配详情",onClose) {
            Box { GlyphButton("more","搭配菜单",!state.busy&&!exporting){menu=true}
                DropdownMenu(menu,{menu=false}) {
                    DropdownMenuItem(text={Text("导出图片")},onClick={menu=false;export(false)},enabled=!exporting)
                    DropdownMenuItem(text={Text("设置搭配合集")},onClick={menu=false;collecting=true})
                    DropdownMenuItem(text={Text("修改名称和标签")},onClick={menu=false;editing=true})
                }
            }
        }
        Column(Modifier.weight(1f).padding(horizontal=8.dp).verticalScroll(rememberScrollState())) {
            Box(Modifier.fillMaxWidth().aspectRatio(1.03f).clip(RoundedCornerShape(18.dp)).background(Color(OutfitRenderer.Background)),Alignment.Center) {
                OutfitBoard(saved.items,state.garments,model,modifier=Modifier.fillMaxHeight().aspectRatio(.8f))
                Surface(Modifier.align(Alignment.TopEnd).padding(10.dp),shape=RoundedCornerShape(12.dp),color=Color.White) {
                    IconButton(onClick={model.favoriteOutfit(saved)},enabled=!state.busy,modifier=Modifier.size(38.dp).semantics {contentDescription=if(saved.outfit.favorite)"取消收藏搭配" else "收藏搭配"}) {
                        LineIcon("heart",Modifier.size(19.dp),color=Pink,filled=saved.outfit.favorite)
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top=6.dp),verticalAlignment=Alignment.CenterVertically) {
                Text(saved.outfit.name,fontSize=20.sp,fontWeight=FontWeight.Medium,modifier=Modifier.weight(1f,fill=false),maxLines=2)
                GlyphButton("edit","修改名称和标签",!state.busy,iconSize=16.dp,touchSize=28.dp){editing=true}
            }
            LazyRow(horizontalArrangement=Arrangement.spacedBy(7.dp),modifier=Modifier.padding(bottom=12.dp)) {
                val tags=saved.outfit.tags.split(',').filter{it.isNotBlank()}
                if(tags.isEmpty()) item { TextButton(onClick={editing=true},contentPadding=PaddingValues(0.dp),modifier=Modifier.height(28.dp)) {Text("添加标签",fontSize=12.sp)} }
                items(tags) { tag -> Text(tag,Modifier.background(Color(0xFFFFE8EB),RoundedCornerShape(20.dp)).padding(horizontal=12.dp,vertical=4.dp),color=Color(0xFFC46F81),fontSize=14.sp,lineHeight=18.sp) }
            }
            Text("包含单品（${saved.items.size}）",fontSize=16.sp,fontWeight=FontWeight.Medium,modifier=Modifier.padding(bottom=12.dp))
            val order=listOf("上衣","外套","裤装","裙装","包包","鞋子","配饰")
            val included=saved.items.sortedWith(compareBy<OutfitItem>{ item -> order.indexOf(state.garments.find{it.id==item.garmentId}?.category) }.thenBy{it.zIndex})
            BoxWithConstraints(Modifier.fillMaxWidth().height(80.dp)) {
            val thumbnailWidth = (maxWidth-24.dp)/4
            LazyRow(horizontalArrangement=Arrangement.spacedBy(8.dp),modifier=Modifier.fillMaxSize()) {
                items(included,key={it.id}) { item -> state.garments.find { it.id==item.garmentId }?.let { garment ->
                    LocalPhoto(model.thumbnail(garment),garment.name,Modifier.width(thumbnailWidth).fillMaxHeight().background(PhotoGround,RoundedCornerShape(13.dp)).padding(4.dp))
                } }
            }
            }
            Spacer(Modifier.height(13.dp))
            DetailRow("edit","合集：${saved.outfit.collectionName.ifBlank{"未分类"}}",!state.busy){collecting=true}
            DetailRow("edit","编辑搭配",!state.busy){model.openOutfit(saved)}
            DetailRow("today",if(saved.outfit.todayDate==java.time.LocalDate.now().toString())"已设为今日穿搭" else "设为今日穿搭",!state.busy){model.todayOutfit(saved)}
            DetailRow("share",if(exporting)"正在生成图片…" else "分享图片",!state.busy&&!exporting){export(true)}
            DetailRow("delete","删除搭配",!state.busy,Color(0xFFF05B63)){deleting=true}
            Spacer(Modifier.height(12.dp))
        }
    }
    if(collecting)AlertDialog(onDismissRequest={collecting=false},title={Text("设置搭配合集")},text={Column {
        OutlinedTextField(collectionName,{collectionName=it.take(40)},label={Text("例如：通勤、约会、旅行")},singleLine=true)
        ChoiceField("已有合集",collectionName,listOf("")+state.activeOutfits.map{it.outfit.collectionName}.filter{it.isNotBlank()}.distinct()){collectionName=it}
        Text("留空可移出合集，不会删除搭配。")
    }},confirmButton={TextButton(onClick={model.collection(saved,collectionName);collecting=false},enabled=!state.busy){Text("保存合集")}},dismissButton={TextButton(onClick={collecting=false}){Text("取消")}})
    if(editing)NameDialog(saved.outfit.name,saved.outfit.tags,true,{editing=false}) {name,tags->model.outfitMetadata(saved,name,tags);editing=false}
    if(deleting)AlertDialog(onDismissRequest={deleting=false},title={Text("删除这套搭配？")},text={Text("搭配将移入回收站，可从衣橱菜单恢复；衣物和日历记录会保留。")},
        confirmButton={TextButton(onClick={model.deleteOutfit(saved);deleting=false;onClose()},enabled=!state.busy){Text("确认删除搭配")}},
        dismissButton={TextButton(onClick={deleting=false}){Text("保留搭配")}})
}

@Composable
private fun DetailRow(icon:String,label:String,enabled:Boolean,tint:Color=Charcoal,onClick:()->Unit) {
    HorizontalDivider(thickness=.5.dp,color=Color(0xFFEDE6E3))
    Row(Modifier.fillMaxWidth().height(48.dp).clickable(enabled=enabled,onClick=onClick).padding(horizontal=10.dp),verticalAlignment=Alignment.CenterVertically) {
        LineIcon(icon,Modifier.size(19.dp),tint); Spacer(Modifier.width(14.dp))
        Text(label,Modifier.weight(1f),fontSize=15.sp,color=tint)
        if(icon!="delete")LineIcon("next",Modifier.size(14.dp),Color(0xFFA9ACAF))
    }
}
