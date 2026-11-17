package com.xiaoyichu.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xiaoyichu.app.data.*

internal val Paper = Color(0xFFFFFCFB)
internal val Pink = Color(0xFFD77F92)
internal val Petal = Color(0xFFFBDDE2)
internal val PhotoGround = Color(0xFFF5F1EF)
internal val Charcoal = Color(0xFF22272B)
internal const val AllWardrobeCategory = "全部"

/** Small original line icons; no platform emoji glyphs or heavyweight icon dependency. */
@Composable
internal fun LineIcon(kind: String, modifier: Modifier = Modifier, color: Color = Charcoal, filled: Boolean = false) {
    Canvas(modifier.size(22.dp)) {
        val stroke = Stroke(1.55f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        scale(size.width / 24f, size.height / 24f, Offset.Zero) {
            fun line(vararg points: Float) { val p = Path(); p.moveTo(points[0], points[1]); for (i in 2 until points.size step 2) p.lineTo(points[i], points[i+1]); drawPath(p, color, style = stroke) }
            when (kind) {
                "back" -> line(14f,5f,7f,12f,14f,19f)
                "next" -> line(9f,5f,16f,12f,9f,19f)
                "heart" -> { val p = Path().apply { moveTo(12f,20f); cubicTo(9f,17f,3f,13f,3f,8f); cubicTo(3f,2f,10f,2f,12f,7f); cubicTo(14f,2f,21f,2f,21f,8f); cubicTo(21f,13f,15f,17f,12f,20f); close() }; drawPath(p,color,style = if(filled) Fill else stroke) }
                "undo", "redo" -> { val flip = kind == "redo"; scale(if(flip)-1f else 1f,1f, Offset(12f,12f)) { line(8f,5f,3f,10f,8f,10f); val p=Path().apply { moveTo(3f,10f); lineTo(14f,10f); cubicTo(22f,10f,22f,20f,15f,20f); lineTo(6f,20f) }; drawPath(p,color,style=stroke) } }
                "more" -> listOf(4f,12f,20f).forEach { drawCircle(color,1.5f,Offset(it,12f)) }
                "copy" -> { drawRoundRect(color,Offset(8f,3f),androidx.compose.ui.geometry.Size(12f,15f),androidx.compose.ui.geometry.CornerRadius(2f),style=stroke); line(5f,7f,3f,7f,3f,21f,15f,21f,15f,19f) }
                "delete" -> { line(4f,6f,20f,6f); line(8f,6f,8f,3f,16f,3f,16f,6f); line(6f,6f,7f,21f,17f,21f,18f,6f); line(10f,10f,10f,17f); line(14f,10f,14f,17f) }
                "top", "bottom" -> { line(8f,5f,10f,3f,14f,3f,16f,5f,21f,8f,18f,12f,17f,10f,17f,21f,7f,21f,7f,10f,6f,12f,3f,8f,8f,5f)
                    line(12f,16f,12f,8f);if(kind=="top")line(9f,11f,12f,8f,15f,11f) else line(9f,13f,12f,16f,15f,13f) }
                "crop" -> { line(6f,2f,6f,18f,22f,18f); line(2f,6f,18f,6f,18f,22f); line(8f,16f,20f,4f) }
                "adjust" -> { listOf(6f,12f,18f).forEach { line(3f,it,21f,it) }; drawCircle(Paper,2f,Offset(9f,6f)); drawCircle(color,2f,Offset(9f,6f),style=stroke); drawCircle(Paper,2f,Offset(16f,12f)); drawCircle(color,2f,Offset(16f,12f),style=stroke); drawCircle(Paper,2f,Offset(8f,18f)); drawCircle(color,2f,Offset(8f,18f),style=stroke) }
                "filter" -> listOf(Offset(12f,7f),Offset(7f,15f),Offset(17f,15f)).forEach { drawCircle(color,5f,it,style=stroke) }
                "replace" -> { line(4f,9f,4f,5f,8f,5f); line(20f,15f,20f,19f,16f,19f); val p=Path().apply { moveTo(4f,7f); cubicTo(10f,-1f,20f,4f,20f,9f); moveTo(20f,17f); cubicTo(14f,25f,4f,20f,4f,15f) }; drawPath(p,color,style=stroke) }
                "edit" -> { line(4f,16f,16f,4f,20f,8f,8f,20f,4f,20f,4f,16f); line(13f,7f,17f,11f); line(3f,23f,21f,23f) }
                "share" -> { line(12f,15f,12f,2f); line(7f,7f,12f,2f,17f,7f); line(5f,12f,3f,12f,3f,22f,21f,22f,21f,12f,19f,12f) }
                "today" -> { drawCircle(color,9f,Offset(12f,13f),style=stroke); line(12f,1f,12f,7f); line(9f,13f,12f,16f,15f,12f) }
                "plus" -> { line(12f,4f,12f,20f); line(4f,12f,20f,12f) }
                "wardrobe" -> { line(3f,3f,21f,3f,21f,21f,3f,21f,3f,3f); line(12f,3f,12f,21f); line(9f,11f,9f,14f); line(15f,11f,15f,14f) }
                else -> { line(3f,3f,21f,3f,21f,21f,3f,21f,3f,3f); line(7f,8f,17f,8f); line(7f,13f,17f,13f) }
            }
        }
    }
}

@Composable
internal fun GlyphButton(icon: String, label: String, enabled: Boolean = true, tint: Color = Charcoal,
    iconSize: androidx.compose.ui.unit.Dp = 22.dp, touchSize: androidx.compose.ui.unit.Dp = 44.dp, onClick: () -> Unit) {
    IconButton(onClick, enabled = enabled, modifier = Modifier.size(touchSize).semantics { contentDescription = label }) {
        LineIcon(icon, Modifier.size(iconSize), color = if (enabled) tint else Color(0xFFADB0B3))
    }
}

@Composable
internal fun ScreenHeader(title: String, onBack: () -> Unit, end: @Composable RowScope.() -> Unit = {}) {
    Box(Modifier.fillMaxWidth().height(56.dp)) {
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            GlyphButton("back", "返回", onClick = onBack)
            Spacer(Modifier.weight(1f)); end()
        }
        Text(title, Modifier.align(Alignment.Center), fontSize = 18.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
internal fun BaselineCategories(selected: String, enabled: Boolean = true, height: androidx.compose.ui.unit.Dp = 48.dp, includeAll:Boolean=false, onSelect: (String) -> Unit) {
    LazyRow(Modifier.fillMaxWidth().height(height), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        items(if(includeAll)listOf(AllWardrobeCategory)+GarmentCategories else GarmentCategories) { category ->
            Box(Modifier.height(height).clickable(enabled = enabled) { onSelect(category) }, Alignment.Center) {
                Box(Modifier.clip(RoundedCornerShape(20.dp)).background(if(category==selected)Petal else Color.Transparent)
                    .padding(horizontal=11.dp,vertical=6.dp)) {
                    Text(category, fontSize = 14.sp, lineHeight=18.sp, color = if(category == selected) Color(0xFFC86F82) else Color(0xFF606369))
                }
            }
        }
    }
}

@Composable
internal fun WardrobeTile(garment: Garment, model: WardrobeViewModel, busy: Boolean, compact: Boolean = false, onOpen: () -> Unit) {
    Column(Modifier.clip(RoundedCornerShape(13.dp)).background(Color.White).clickable(enabled = !busy, onClick = onOpen)) {
        Box(Modifier.fillMaxWidth().aspectRatio(.94f).background(PhotoGround, RoundedCornerShape(13.dp))) {
            LocalPhoto(model.thumbnail(garment), garment.name, Modifier.fillMaxSize().padding(if(compact)5.dp else 10.dp))
            IconButton(onClick = { model.favorite(garment) }, enabled = !busy,
                modifier = Modifier.align(Alignment.TopEnd).size(28.dp).semantics { contentDescription = "${if(garment.favorite)"取消收藏" else "收藏"}${garment.name}" }) {
                LineIcon("heart", Modifier.size(13.dp), if(garment.favorite)Pink else Color(0xFF7B8388), garment.favorite)
            }
        }
        if(!compact) Text(garment.name, Modifier.fillMaxWidth().padding(vertical = 4.dp, horizontal = 3.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 12.sp,lineHeight=16.sp)
    }
}

@Composable
internal fun ColumnScope.GarmentSelection(garments: List<Garment>, busy: Boolean, model: WardrobeViewModel,
    onBack: () -> Unit, onChoose: (Garment) -> Unit) {
    var category by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("上衣") }
    ScreenHeader("添加单品", onBack)
    HorizontalDivider(color = Color(0xFFF2EBE9), thickness = .5.dp)
    BaselineCategories(category, !busy) { category = it }
    val visible = garments.filter { it.category == category }
    if (visible.isEmpty()) Box(Modifier.weight(1f).fillMaxWidth(), Alignment.Center) { Text("这个分类还没有衣物", color = Color.Gray, fontSize = 14.sp) }
    else LazyVerticalGrid(GridCells.Fixed(3), Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(top = 6.dp, bottom = 16.dp)) {
        items(visible, key = { it.id }) { garment -> WardrobeTile(garment, model, busy) { onChoose(garment) } }
    }
}
