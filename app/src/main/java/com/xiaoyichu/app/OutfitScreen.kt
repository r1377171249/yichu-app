package com.xiaoyichu.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xiaoyichu.app.data.*

@Composable
fun ColumnScope.OutfitEditor(canvas: CanvasState, garments: List<Garment>, busy: Boolean, model: WardrobeViewModel, onClose: () -> Unit) {
    var category by rememberSaveable(canvas.draft.id) { mutableStateOf("上衣") }
    var panel by rememberSaveable(canvas.draft.id) { mutableStateOf<String?>(null) }
    var selecting by rememberSaveable(canvas.draft.id) { mutableStateOf(false) }
    var replacing by rememberSaveable(canvas.draft.id) { mutableStateOf(false) }
    val selected = canvas.draft.items.find { it.id == canvas.selectedId }
    if (selecting) {
        BackHandler { selecting = false; replacing = false }
        Column(Modifier.fillMaxSize().padding(horizontal=2.dp)) {
        GarmentSelection(garments.filter{it.deletedAt==null}, busy, model, { selecting = false; replacing = false }) {
            if (replacing && selected != null) model.replacePiece(it) else model.addPiece(it)
            selecting = false; replacing = false
        }
        }
        return
    }
    Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
        GlyphButton("back", "返回", !busy, onClick = onClose)
        Text("今日穿搭", Modifier.weight(1f), fontSize = 18.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        GlyphButton("undo", "撤销", !busy && canvas.canUndo, onClick = model::undoCanvas)
        GlyphButton("redo", "重做", !busy && canvas.canRedo, onClick = model::redoCanvas)
        Button(onClick = { panel = "save" }, enabled = !busy && canvas.draft.items.isNotEmpty(),
            shape = RoundedCornerShape(11.dp), contentPadding = PaddingValues(horizontal = 18.dp, vertical = 0.dp),
            modifier = Modifier.height(33.dp)) { Text("保存", fontSize = 14.sp) }
    }
    val boardContent: @Composable () -> Unit = {
    BoxWithConstraints(Modifier.fillMaxSize().padding(top = 4.dp, bottom = 2.dp).clip(RoundedCornerShape(19.dp)).background(Color(OutfitRenderer.Background)), Alignment.Center) {
        val width = minOf(maxWidth, maxHeight * .8f)
        Box(Modifier.width(width).aspectRatio(.8f).clip(RoundedCornerShape(19.dp))) {
            OutfitBoard(canvas.draft.items, garments, model, canvas.selectedId, !busy,
                model::selectPiece, model::beginCanvasGesture, model::transformPiece, model::endCanvasGesture, Modifier.fillMaxSize())
            if (canvas.draft.items.isEmpty()) Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("从一件喜欢的衣服开始", fontSize = 15.sp, color = Color(0xFF8C8081))
                TextButton(onClick = { selecting = true }) { Text("添加单品") }
            }
            if (selected != null) Surface(Modifier.align(Alignment.CenterEnd).padding(end = 3.dp).width(54.dp),
                shape = RoundedCornerShape(14.dp), color = Color.White.copy(alpha = .96f), shadowElevation = 4.dp) {
                Column(Modifier.padding(vertical = 4.dp).verticalScroll(rememberScrollState())) {
                    Tool("top", "置顶", !busy) { model.layerPiece(true) }
                    Tool("bottom", "置底", !busy) { model.layerPiece(false) }
                    Tool("copy", "复制", !busy, model::duplicatePiece)
                    Tool("delete", "删除", !busy, model::removePiece)
                }
            }
        }
    }
    }
    val controlsContent: @Composable () -> Unit = {
    Surface(Modifier.fillMaxWidth().height(86.dp), color = Color.White, shape = RoundedCornerShape(18.dp)) {
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            listOf("replace" to "替换", "crop" to "裁剪", "adjust" to "调整", "filter" to "滤镜", "more" to "更多").forEach { (icon, label) ->
                Box(Modifier.weight(1f)) { Tool(icon, label, !busy && (selected != null || icon == "more")) {
                    if (icon == "replace") { replacing = true; selecting = true } else panel = icon
                } }
            }
        }
    }
    BaselineCategories(category, !busy, height = 54.dp) { category = it }
    val visible = garments.filter { it.deletedAt==null && it.category == category }
    LazyRow(Modifier.fillMaxWidth().height(106.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 5.dp)) {
        if (visible.isEmpty()) item { Text("这个分类还没有衣物，可返回衣橱录入", Modifier.padding(15.dp), color = Color.Gray, fontSize = 13.sp) }
        items(visible, key = { it.id }) { garment ->
            Box(Modifier.width(94.dp).semantics { contentDescription = "加入画布：${garment.name}" }) {
                WardrobeTile(garment, model, busy, compact = true) { model.addPiece(garment) }
            }
        }
    }
    }
    val configuration = LocalConfiguration.current
    if(configuration.screenWidthDp > configuration.screenHeightDp) Row(Modifier.weight(1f).fillMaxWidth()) {
        Box(Modifier.weight(1f).fillMaxHeight()) { boardContent() }
        Column(Modifier.width(280.dp).fillMaxHeight().verticalScroll(rememberScrollState())) { controlsContent() }
    } else {
        Box(Modifier.weight(1f).fillMaxWidth()) { boardContent() }
        controlsContent()
    }
    when (panel) {
        "save" -> NameDialog(canvas.draft.name.ifBlank { "我的穿搭" }, "", false, { panel = null }) { name, _ ->
            model.renameOutfit(name); model.saveOutfit(); panel = null
        }
        "crop" -> garments.find { it.id == selected?.garmentId }?.let { garment ->
            CropDialog(model.artwork(garment), selected!!.crop, { panel = null }) { model.cropPiece(it); panel = null }
        }
        "adjust" -> AlertDialog(onDismissRequest = { panel = null }, title = { Text("调整单品") }, text = {
            Column {
                Row { TextButton(onClick = { model.adjustPiece(1f / 1.1f) }) { Text("缩小") }; TextButton(onClick = { model.adjustPiece(1.1f) }) { Text("放大") } }
                Row { TextButton(onClick = { model.adjustPiece(angle = -15f) }) { Text("左转 15°") }; TextButton(onClick = { model.adjustPiece(angle = 15f) }) { Text("右转 15°") } }
                Text("也可以单指移动、双指缩放和旋转", fontSize = 13.sp)
            }
        }, confirmButton = { TextButton(onClick = { panel = null }) { Text("完成") } })
        "filter" -> AlertDialog(onDismissRequest = { panel = null }, title = { Text("单品滤镜") }, text = {
            Column { listOf("original" to "原色", "warm" to "暖调", "mono" to "黑白").forEach { (value, label) ->
                Row(Modifier.fillMaxWidth().clickable { model.filterPiece(value) }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected?.filter == value, { model.filterPiece(value) }); Text(label)
                }
            }; Text("只影响这套搭配，衣橱原图保持完整", fontSize = 12.sp) }
        }, confirmButton = { TextButton(onClick = { panel = null }) { Text("完成") } })
        "more" -> AlertDialog(onDismissRequest = { panel = null }, title = { Text("更多") }, text = {
            Column {
                TextButton(onClick = { selecting = true; panel = null }) { Text("添加单品") }
                TextButton(onClick = { model.standardLayout(); panel = null }, enabled = canvas.draft.items.isNotEmpty()) { Text("标准布局") }
                Text("标准布局会重新排列当前单品，可撤销。自由拖动即可继续自由布局。", fontSize = 13.sp)
            }
        }, confirmButton = { TextButton(onClick = { panel = null }) { Text("关闭") } })
    }
}

@Composable
private fun Tool(icon: String, label: String, enabled: Boolean, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().height(66.dp).clickable(enabled = enabled, onClick = onClick)
        .semantics(mergeDescendants = true) { contentDescription = label },
        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        LineIcon(icon, color = if(enabled)Charcoal else Color(0xFFB2ADB0))
        Spacer(Modifier.height(5.dp)); Text(label, fontSize = 12.sp,lineHeight=16.sp, color = if(enabled)Charcoal else Color(0xFFB2ADB0))
    }
}

@Composable
internal fun NameDialog(initialName: String, initialTags: String, withTags: Boolean, onClose: () -> Unit, onSave: (String, String) -> Unit) {
    var name by rememberSaveable { mutableStateOf(initialName) }; var tags by rememberSaveable { mutableStateOf(initialTags) }
    AlertDialog(onDismissRequest = onClose, title = { Text(if(withTags)"搭配信息" else "保存搭配") }, text = {
        Column {
            OutlinedTextField(name, { if(it.length<=60)name=it }, label={ Text("搭配名称") }, singleLine=true)
            if(withTags) { Spacer(Modifier.height(12.dp)); OutlinedTextField(tags, { if(it.length<=160)tags=it }, label={ Text("标签，用逗号分隔") }, supportingText={Text("最多 8 个标签")}) }
        }
    }, confirmButton = { TextButton(onClick = { onSave(name.trim(),tags) }, enabled=name.isNotBlank()) { Text("确认保存") } },
        dismissButton = { TextButton(onClick=onClose) { Text("取消") } })
}
