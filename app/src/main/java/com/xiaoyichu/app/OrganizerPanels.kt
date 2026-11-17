package com.xiaoyichu.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.xiaoyichu.app.data.*
import java.time.LocalDate
import java.time.YearMonth

val WardrobeState.activeGarments get()=garments.filter{it.deletedAt==null}
val WardrobeState.activeOutfits get()=outfits.filter{it.outfit.deletedAt==null}

@Composable
internal fun ChoiceField(label:String,value:String,options:List<String>,enabled:Boolean=true,onChange:(String)->Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick={expanded=true},enabled=enabled,modifier=Modifier.fillMaxWidth()) { Text("$label：${value.ifBlank{"不限"}}",maxLines=2) }
        DropdownMenu(expanded,{expanded=false},modifier=Modifier.heightIn(max=320.dp)) {
            options.forEach { v -> DropdownMenuItem(text={Text(v.ifBlank{"不限 / 未填写"})},onClick={expanded=false;onChange(v)}) }
        }
    }
}

@Composable
internal fun SearchFilters(
    query:WardrobeQuery,
    count:Int,
    backupReminder:String?,
    enabled:Boolean,
    onBackup:()->Unit,
    onChange:(WardrobeQuery)->Unit
) {
    var filters by rememberSaveable { mutableStateOf(false) }
    val canReset=query.text.isNotEmpty() || query.color.isNotBlank() || query.season.isNotBlank() ||
        query.occasion.isNotBlank() || query.sort!=WardrobeQuery().sort
    val reset={onChange(WardrobeQuery(category=query.category))}
    Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(6.dp)) {
        OutlinedTextField(query.text,{onChange(query.copy(text=it.take(120)))},enabled=enabled,label={Text("搜索衣物 / 颜色 / 季节")},singleLine=true,modifier=Modifier.weight(1f))
        TextButton(onClick={filters=true},enabled=enabled){Text("筛选")}
    }
    if(backupReminder!=null || canReset) {
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(6.dp)) {
            Box(Modifier.weight(1f)) {
                if(backupReminder!=null)TextButton(onClick=onBackup,enabled=enabled,contentPadding=PaddingValues(horizontal=4.dp,vertical=8.dp)) {
                    Text(backupReminder,fontSize=12.sp)
                }
            }
            if(canReset)FilledTonalButton(
                onClick=reset,enabled=enabled,shape=RoundedCornerShape(12.dp),
                colors=ButtonDefaults.filledTonalButtonColors(containerColor=MaterialTheme.colorScheme.primaryContainer),
                contentPadding=PaddingValues(horizontal=12.dp,vertical=6.dp),
                modifier=Modifier.defaultMinSize(minHeight=48.dp)
            ) { Text("↶ 重置",fontSize=12.sp) }
        }
    }
    val summary=listOf(query.color,query.season,query.occasion).filter{it.isNotBlank()}.joinToString(" · ")
    if(summary.isNotBlank()||query.text.isNotBlank())Text("找到 $count 件${if(summary.isBlank())"" else " · $summary"}",fontSize=12.sp)
    if(filters)AlertDialog(onDismissRequest={filters=false},title={Text("筛选与排序")},text={Column(Modifier.verticalScroll(rememberScrollState())) {
        ChoiceField("颜色",query.color,listOf("")+GarmentColors){onChange(query.copy(color=it))}
        ChoiceField("季节",query.season,listOf("")+GarmentSeasons){onChange(query.copy(season=it))}
        ChoiceField("场合",query.occasion,listOf("")+GarmentOccasions){onChange(query.copy(occasion=it))}
        ChoiceField("排序",query.sort,WardrobeSorts){onChange(query.copy(sort=it))}
    }},confirmButton={TextButton(onClick={filters=false}){Text("完成")}},dismissButton={TextButton(onClick=reset){Text("重置筛选")}})
}

@Composable
internal fun AttributesDialog(g:Garment,onClose:()->Unit,onSave:(String,String,String)->Unit) {
    var color by rememberSaveable(g.id){mutableStateOf(g.color)}
    var season by rememberSaveable(g.id){mutableStateOf(g.season)}
    var occasion by rememberSaveable(g.id){mutableStateOf(g.occasion)}
    AlertDialog(onDismissRequest=onClose,title={Text("衣物属性")},text={Column(Modifier.verticalScroll(rememberScrollState())) {
        ChoiceField("颜色",color,listOf("")+GarmentColors){color=it}
        ChoiceField("季节",season,listOf("")+GarmentSeasons){season=it}
        ChoiceField("场合",occasion,listOf("")+GarmentOccasions){occasion=it}
    }},confirmButton={TextButton(onClick={onSave(color,season,occasion);onClose()}){Text("保存属性")}},dismissButton={TextButton(onClick=onClose){Text("取消")}})
}

@Composable
private fun FullPanel(title:String,onClose:()->Unit,content:@Composable ColumnScope.()->Unit) {
    Dialog(onDismissRequest=onClose,properties=DialogProperties(usePlatformDefaultWidth=false)) {
        BackHandler(onBack=onClose)
        Surface(Modifier.fillMaxSize(),color=MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal=12.dp)) {
                Row(Modifier.fillMaxWidth().height(56.dp),verticalAlignment=Alignment.CenterVertically) {
                    TextButton(onClick=onClose){Text("返回")};Text(title,fontSize=19.sp,fontWeight=FontWeight.Medium)
                }
                content()
            }
        }
    }
}

@Composable
internal fun BatchPanel(state:WardrobeState,model:WardrobeViewModel,onAdd:()->Unit,onClose:()->Unit) {
    var editId by rememberSaveable { mutableStateOf<String?>(null) }
    val current=state.batch.find{it.photo.id==editId}
    FullPanel("批量录入（${state.batch.size}）",onClose) {
        Text("自动处理后集中检查。失败照片可单独重试或确认保留背景；返回后草稿仍保留。",fontSize=13.sp)
        Row(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
            OutlinedButton(onClick=onAdd,enabled=!state.busy&&state.batch.size<100){Text("添加照片")}
            TextButton(onClick=model::continueBatch,enabled=!state.busy&&state.batch.any{it.photo.cutout==null&&!it.originalAccepted}){Text("处理未完成照片")}
        }
        if(state.busy){LinearProgressIndicator(Modifier.fillMaxWidth());Text(state.batchProgress ?: "正在保存…")}
        state.message?.let {Text(it,fontSize=13.sp)}
        LazyColumn(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(8.dp),contentPadding=PaddingValues(vertical=8.dp)) {
            items(state.batch,key={it.photo.id}) { b ->
                Surface(color=MaterialTheme.colorScheme.surfaceVariant,shape=MaterialTheme.shapes.medium) {
                    Row(Modifier.fillMaxWidth().padding(8.dp),verticalAlignment=Alignment.CenterVertically) {
                        LocalPhoto(b.photo.preview,b.name,Modifier.size(88.dp))
                        Column(Modifier.weight(1f).padding(start=8.dp)) {
                            Text(b.name);Text("${b.category} ${b.color} ${b.season} ${b.occasion}",fontSize=12.sp)
                            Text(if(b.originalAccepted)"保留背景" else if(b.photo.cutout!=null)"抠图完成" else if(b.photo.cutoutError)"抠图失败" else "等待处理",fontSize=12.sp)
                            Row {
                                TextButton(onClick={editId=b.photo.id},enabled=!state.busy){Text("检查")}
                                if(b.photo.cutout==null)TextButton(onClick={model.retryBatch(b.photo.id)},enabled=!state.busy){Text("重试")}
                                TextButton(onClick={model.removeBatch(b.photo.id)},enabled=!state.busy){Text("移除")}
                            }
                        }
                    }
                }
            }
            if(state.batch.isEmpty())item{Text("从相册选择多张单件衣物照片开始。",Modifier.padding(20.dp))}
        }
        Button(onClick=model::saveBatch,enabled=!state.busy&&state.batch.any{it.ready},modifier=Modifier.fillMaxWidth().padding(vertical=10.dp)) {
            Text("保存可用照片（${state.batch.count{it.ready}} 件）")
        }
    }
    if(current!=null)BatchEdit(current,{editId=null}){model.updateBatch(it);editId=null}
}

@Composable
private fun BatchEdit(b:BatchPhoto,onClose:()->Unit,onSave:(BatchPhoto)->Unit) {
    var name by rememberSaveable(b.photo.id){mutableStateOf(b.name)}
    var category by rememberSaveable(b.photo.id){mutableStateOf(b.category)}
    var color by rememberSaveable(b.photo.id){mutableStateOf(b.color)}
    var season by rememberSaveable(b.photo.id){mutableStateOf(b.season)}
    var occasion by rememberSaveable(b.photo.id){mutableStateOf(b.occasion)}
    var original by rememberSaveable(b.photo.id){mutableStateOf(b.originalAccepted)}
    AlertDialog(onDismissRequest=onClose,title={Text("检查照片")},text={Column(Modifier.heightIn(max=480.dp).verticalScroll(rememberScrollState())) {
        LocalPhoto(if(original)b.photo.backgroundPreview else b.photo.preview,name,Modifier.fillMaxWidth().height(160.dp))
        OutlinedTextField(name,{name=it.take(60)},label={Text("衣物名称")},singleLine=true)
        ChoiceField("分类",category,GarmentCategories){category=it}
        ChoiceField("颜色",color,listOf("")+GarmentColors){color=it}
        ChoiceField("季节",season,listOf("")+GarmentSeasons){season=it}
        ChoiceField("场合",occasion,listOf("")+GarmentOccasions){occasion=it}
        Row(verticalAlignment=Alignment.CenterVertically){Checkbox(original,{original=it});Text("保留背景保存（仍保留原照片）")}
    }},confirmButton={TextButton(onClick={onSave(b.copy(name=name.trim(),category=category,color=color,season=season,occasion=occasion,originalAccepted=original))},enabled=name.isNotBlank()){Text("确认信息")}},dismissButton={TextButton(onClick=onClose){Text("取消")}})
}

@Composable
internal fun TrashPanel(state:WardrobeState,model:WardrobeViewModel,onClose:()->Unit) {
    var permanent by rememberSaveable {mutableStateOf<String?>(null)}
    val gs=state.garments.filter{it.deletedAt!=null};val os=state.outfits.filter{it.outfit.deletedAt!=null}
    FullPanel("回收站",onClose) {
        Text("不会自动清空。恢复搭配会同时恢复它使用的衣物；永久删除无法撤销。",fontSize=13.sp)
        state.message?.let{Text(it)}
        LazyColumn(Modifier.weight(1f)) {
            items(gs,key={"g"+it.id}){g->Row(verticalAlignment=Alignment.CenterVertically){
                LocalPhoto(model.thumbnail(g),g.name,Modifier.size(64.dp));Text(g.name,Modifier.weight(1f))
                TextButton(onClick={model.restoreGarment(g)},enabled=!state.busy){Text("恢复")}
                TextButton(onClick={permanent="g"+g.id},enabled=!state.busy){Text("永久删除")}
            }}
            items(os,key={"o"+it.outfit.id}){o->Row(verticalAlignment=Alignment.CenterVertically){
                Text("搭配：${o.outfit.name}",Modifier.weight(1f))
                TextButton(onClick={model.restoreOutfit(o)},enabled=!state.busy){Text("恢复")}
                TextButton(onClick={permanent="o"+o.outfit.id},enabled=!state.busy){Text("永久删除")}
            }}
            if(gs.isEmpty()&&os.isEmpty())item{Text("回收站是空的",Modifier.padding(24.dp))}
        }
    }
    if(permanent!=null)AlertDialog(onDismissRequest={permanent=null},title={Text("永久删除？")},text={Text("将删除这项本地记录。衣物仍被搭配引用时会阻止删除，请先修改或永久删除对应搭配。")},confirmButton={TextButton(onClick={
        val key=permanent!!;if(key.startsWith("g"))gs.find{it.id==key.drop(1)}?.let(model::permanentlyDeleteGarment) else os.find{it.outfit.id==key.drop(1)}?.let(model::permanentlyDeleteOutfit);permanent=null
    },enabled=!state.busy){Text("确认永久删除")}},dismissButton={TextButton(onClick={permanent=null}){Text("保留")}})
}

@Composable
internal fun CalendarPanel(state:WardrobeState,model:WardrobeViewModel,onClose:()->Unit) {
    var monthText by rememberSaveable{mutableStateOf(YearMonth.now().toString())}
    var dateText by rememberSaveable{mutableStateOf(LocalDate.now().toString())}
    var outfitId by rememberSaveable{mutableStateOf("")}
    var worn by rememberSaveable{mutableStateOf(false)}
    val month=YearMonth.parse(monthText);val active=state.activeOutfits;val entry=state.calendar.find{it.date==dateText}
    LaunchedEffect(dateText,entry){outfitId=entry?.outfitId ?: "";worn=entry?.worn ?: false}
    FullPanel("穿搭日历",onClose) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Row(verticalAlignment=Alignment.CenterVertically) {
                TextButton(onClick={monthText=month.minusMonths(1).toString()}){Text("上月")}
                Text(month.toString(),Modifier.weight(1f));TextButton(onClick={monthText=month.plusMonths(1).toString()}){Text("下月")}
            }
            Row {listOf("一","二","三","四","五","六","日").forEach{Text(it,Modifier.weight(1f))}}
            val offset=month.atDay(1).dayOfWeek.value-1
            for(week in 0 until (offset+month.lengthOfMonth()+6)/7)Row(Modifier.fillMaxWidth()) {
                for(dayOfWeek in 0..6) {
                    val day=week*7+dayOfWeek-offset+1
                    if(day !in 1..month.lengthOfMonth())Spacer(Modifier.weight(1f)) else {
                        val date=month.atDay(day).toString();val marked=state.calendar.any{it.date==date}
                        TextButton(onClick={dateText=date},modifier=Modifier.weight(1f),contentPadding=PaddingValues(0.dp),colors=ButtonDefaults.textButtonColors(containerColor=if(dateText==date)MaterialTheme.colorScheme.primaryContainer else androidx.compose.ui.graphics.Color.Transparent)) {
                            Text("$day${if(marked)"·" else ""}",fontSize=13.sp)
                        }
                    }
                }
            }
            Text("选择日期：$dateText",fontWeight=FontWeight.Medium)
            var choosing by remember{mutableStateOf(false)}
            Box {
                OutlinedButton(onClick={choosing=true},enabled=!state.busy,modifier=Modifier.fillMaxWidth()){Text(active.find{it.outfit.id==outfitId}?.outfit?.name ?: "选择已保存搭配")}
                DropdownMenu(choosing,{choosing=false},modifier=Modifier.heightIn(max=300.dp)) {
                    active.forEach{o->DropdownMenuItem(text={Text(o.outfit.name)},onClick={outfitId=o.outfit.id;choosing=false})}
                }
            }
            Row(verticalAlignment=Alignment.CenterVertically){Checkbox(worn,{worn=it},enabled=!LocalDate.parse(dateText).isAfter(LocalDate.now()));Text("已经实际穿过（未勾选为计划）")}
            Row {
                Button(onClick={active.find{it.outfit.id==outfitId}?.let{model.scheduleOutfit(it,dateText,worn)}},enabled=!state.busy&&active.any{it.outfit.id==outfitId}){Text("保存日历")}
                TextButton(onClick={model.unschedule(dateText)},enabled=!state.busy&&entry!=null){Text("移除记录")}
            }
            state.message?.let{Text(it)}
            Text("本月计划与穿着记录",Modifier.padding(top=14.dp),fontWeight=FontWeight.Medium)
            state.calendar.filter{it.date.startsWith(monthText)}.sortedBy{it.date}.forEach { e ->
                val o=state.outfits.find{it.outfit.id==e.outfitId}
                TextButton(onClick={dateText=e.date},modifier=Modifier.fillMaxWidth()) {
                    Text("${e.date} · ${o?.outfit?.name ?: "搭配"}${if(o?.outfit?.deletedAt!=null)"（回收站）"else ""} · ${if(e.worn)"已穿" else "计划"}")
                }
            }
        }
    }
}

@Composable
internal fun RecommendPanel(state:WardrobeState,model:WardrobeViewModel,onClose:()->Unit) {
    var season by rememberSaveable{mutableStateOf("")};var occasion by rememberSaveable{mutableStateOf("")}
    var seed by rememberSaveable{mutableIntStateOf(1)}
    val suggestion=remember(state.garments,season,occasion,seed){suggestOutfit(state.garments,season,occasion,seed)}
    FullPanel("本地搭配灵感",onClose) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Text("只使用你已有的衣物，按标签与收藏组合，全程离线。标签未填写的单品也可参与。",fontSize=13.sp)
            ChoiceField("季节",season,listOf("")+GarmentSeasons){season=it}
            ChoiceField("场合",occasion,listOf("")+GarmentOccasions){occasion=it}
            if(suggestion==null)Text("当前条件下，需要名称标明“连衣”的裙装，或上衣加裤装 / 半裙才能组合。可放宽筛选或先补充衣物。") else {
                suggestion.garments.forEach{g->Row(verticalAlignment=Alignment.CenterVertically){LocalPhoto(model.thumbnail(g),g.name,Modifier.size(80.dp));Text(g.name)}}
                Text(suggestion.explanation)
                TextButton(onClick={seed++}){Text("换一组")}
                Button(onClick={model.recommended(suggestion);onClose()},enabled=!state.busy){Text("放进画布继续调整")}
            }
        }
    }
}
