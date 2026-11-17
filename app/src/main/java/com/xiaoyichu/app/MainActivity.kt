package com.xiaoyichu.app

import android.os.Bundle
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import android.graphics.BitmapFactory
import com.xiaoyichu.app.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private val Rose = Color(0xFFD77F92)
private val Blush = Color(0xFFFFE6EB)
private val WarmWhite = Color(0xFFFFFCFB)
private val Ink = Color(0xFF22272B)
private enum class Page { Wardrobe, Canvas, Outfits }

class MainActivity : ComponentActivity() {
    private lateinit var model: WardrobeViewModel
    private val captures by lazy { CaptureFiles(applicationContext) }
    private var pendingCapture: String? = null
    private var pendingCategory: String = "上衣"
    private val camera = registerForActivityResult(object : ActivityResultContracts.TakePicture() {
        override fun createIntent(context: Context, input: Uri): Intent = super.createIntent(context, input).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            clipData = ClipData.newRawUri("衣物照片", input)
        }
    }) { success ->
        val file = captures.find(pendingCapture)
        pendingCapture = null
        file?.let { revokeCapture(it) }
        if (success && file != null && (model.state.value.restoringSession || (!model.state.value.busy && model.state.value.draft == null))) {
            model.importCapture(file,pendingCategory)
        } else {
            file?.delete()
            if (success) model.showMessage("照片暂时不可用，请重新拍摄")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingCapture = savedInstanceState?.getString("pendingCapture")
        pendingCategory = savedInstanceState?.getString("pendingCategory") ?: "上衣"
        enableEdgeToEdge()
        val factory = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                require(modelClass.isAssignableFrom(WardrobeViewModel::class.java))
                @Suppress("UNCHECKED_CAST")
                return WardrobeViewModel(GarmentRepository(applicationContext), OutfitRepository(applicationContext), BackupRepository(applicationContext), EditorSessionStore(applicationContext),BatchImportStore(applicationContext)) as T
            }
        }
        model = ViewModelProvider(this, factory)[WardrobeViewModel::class.java]
        setContent { WardrobeApp(model, onTakePhoto = ::takePhoto) }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("pendingCapture", pendingCapture)
        outState.putString("pendingCategory", pendingCategory)
        super.onSaveInstanceState(outState)
    }

    private fun takePhoto(category:String) {
        if (pendingCapture != null || model.state.value.busy || model.state.value.draft != null) return
        try {
            val file = captures.create()
            pendingCapture = file.name
            pendingCategory = category
            camera.launch(FileProvider.getUriForFile(this, "$packageName.capture", file))
        } catch (error: Exception) {
            captures.find(pendingCapture)?.let { revokeCapture(it); it.delete() }
            pendingCapture = null
            model.showMessage(if (error is ActivityNotFoundException) "没有可用的相机，请从相册选择照片" else "暂时无法打开相机，请重试或从相册选择")
        }
    }

    private fun revokeCapture(file: File) {
        runCatching {
            revokeUriPermission(FileProvider.getUriForFile(this, "$packageName.capture", file),
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
    }
}

@Composable
private fun WardrobeTheme(content: @Composable () -> Unit) {
    val deviceDensity=LocalDensity.current
    val config=LocalConfiguration.current
    val scale=(minOf(config.screenWidthDp,config.screenHeightDp)/375f).coerceIn(1f,1.25f)
    CompositionLocalProvider(LocalDensity provides Density(deviceDensity.density*scale,deviceDensity.fontScale)) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Rose, onPrimary = Color.White,
            primaryContainer = Blush, onPrimaryContainer = Ink,
            secondary = Rose, onSecondary = Color.White,
            secondaryContainer = Blush, onSecondaryContainer = Ink,
            outline = Color(0xFFD4C3C7), outlineVariant = Color(0xFFECE0E1),
            background = WarmWhite, onBackground = Ink,
            surface = WarmWhite, onSurface = Ink,
            surfaceVariant = Color(0xFFF5EEEC), onSurfaceVariant = Color(0xFF74646A)
        ), content = content
    )
    }
}

@Composable
fun WardrobeApp(model: WardrobeViewModel, onTakePhoto: (String) -> Unit) {
    val state by model.state.collectAsState()
    var page by rememberSaveable { mutableStateOf(Page.Wardrobe) }
    var category by rememberSaveable { mutableStateOf(AllWardrobeCategory) }
    val importCategory=category.takeIf{it in GarmentCategories} ?: "上衣"
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    var chooseSource by rememberSaveable { mutableStateOf(false) }
    var cropOpen by rememberSaveable { mutableStateOf(false) }
    var confirmLeaveCanvas by rememberSaveable { mutableStateOf(false) }
    var outfitDetail by rememberSaveable { mutableStateOf(false) }
    var wardrobeMenu by remember { mutableStateOf(false) }
    var trashOpen by rememberSaveable { mutableStateOf(false) }
    var batchOpen by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable(stateSaver=listSaver<WardrobeQuery,String>(save={listOf(it.text,it.category,it.color,it.season,it.occasion,it.sort)},restore={WardrobeQuery(it[0],it[1],it[2],it[3],it[4],it[5])})) { mutableStateOf(WardrobeQuery()) }
    var backupOpen by rememberSaveable { mutableStateOf(false) }
    var repairOpen by rememberSaveable { mutableStateOf(false) }
    val editing = state.activeGarments.find { it.id == editingId }
    val snackbar = remember { SnackbarHostState() }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) model.importPhoto(uri,importCategory)
    }
    val batchPicker=rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(100)) { uris ->
        if(uris.isNotEmpty()){batchOpen=true;model.importBatch(uris,importCategory)}
    }
    fun pickBatch(){batchPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))}
    fun pickPhoto() { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
    LaunchedEffect(state.message) {
        state.message?.let { message -> snackbar.showSnackbar(message); model.clearMessage() }
    }
    fun leaveCanvas() {
        if (state.canvas?.dirty == true) confirmLeaveCanvas = true
        else { model.closeOutfit(); page = Page.Outfits }
    }
    BackHandler(enabled = state.busy || state.canvas != null || state.draft != null || editing != null || page != Page.Wardrobe) {
        when {
            state.busy -> Unit
            state.canvas != null -> leaveCanvas()
            state.draft != null -> model.discardDraft()
            editing != null -> editingId = null
            else -> page = Page.Wardrobe
        }
    }
    WardrobeTheme {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = {
                if (state.draft == null && editing == null && state.canvas == null && !outfitDetail) {
                    NavigationBar(containerColor = Color.White) {
                        listOf(Page.Wardrobe to "衣橱", Page.Canvas to "搭配画布", Page.Outfits to "我的搭配")
                            .forEachIndexed { index, (destination, label) ->
                                NavigationBarItem(
                                    selected = page == destination, enabled = !state.busy,
                                    onClick = { page = destination; if (destination == Page.Canvas) model.newOutfit() },
                                    icon = { LineIcon(listOf("wardrobe", "filter", "heart")[index], color = if(page == destination)Rose else Ink) },
                                    label = { Text(label) }
                                )
                            }
                    }
                }
            }
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = if(state.canvas!=null)8.dp else 12.dp)) {
                if (state.canvas != null) {
                    OutfitEditor(state.canvas!!, state.garments, state.busy, model, onClose = { leaveCanvas() })
                } else if (state.draft != null) {
                    val draft = state.draft!!
                    GarmentEditor(
                        key = draft.id, title = "录入衣物", photo = draft.preview,
                        initialName = draft.suggestedName, initialCategory = draft.suggestedCategory ?: importCategory, busy = state.busy,
                        initialColor=draft.color,initialSeason=draft.season,initialOccasion=draft.occasion,onAttributesChange=model::draftAttributes,
                        onMetadataChange = model::draftMetadata,
                        processingCutout = state.processingCutout,
                        photoControls = {
                            Text("自动抠图（试用）", fontWeight = FontWeight.Medium)
                            ChoiceField("抠图方式",draft.cutoutMode,CutoutModes,!state.busy){model.retryCutout(it)}
                            Text("细节优先适合薄纱细带；清理残留更强。衣架或复杂背景仍可修补，原照片始终保留。",fontSize=12.sp)
                            when {
                                state.processingCutout -> {
                                    LinearProgressIndicator(Modifier.fillMaxWidth())
                                    Text("正在自动抠图，请稍候…", fontSize = 13.sp)
                                }
                                draft.cutoutError -> Text("这次抠图未完成，可重试或使用原图。", fontSize = 13.sp)
                                draft.cutout != null -> Text(if (draft.useCutout) "当前将保存透明背景图片，原图同时保留。" else if (draft.cropped != null) "当前将保存裁剪后的照片，原照片同时保留。" else "当前将使用原图保存。", fontSize = 13.sp)
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilterChip(selected = !draft.useCutout, enabled = !state.busy,
                                    onClick = { model.chooseCutout(false) }, label = { Text(if (draft.cropped != null) "保留背景" else "使用原图") })
                                FilterChip(selected = draft.useCutout, enabled = !state.busy && draft.cutout != null,
                                    onClick = { model.chooseCutout(true) }, label = { Text("使用抠图") })
                                TextButton(onClick = { model.retryCutout() }, enabled = !state.busy) { Text("重新抠图") }
                            }
                            TextButton(onClick = { cropOpen = true }, enabled = !state.busy) {
                                Text(if (draft.cropped == null) "裁剪照片" else "调整裁剪")
                            }
                            TextButton(onClick = { repairOpen = true }, enabled = !state.busy && draft.cutout != null) { Text("修补边缘") }
                            Text("请检查衣物边缘，可用“修补边缘”处理残留或误删。", fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        },
                        onCancel = { model.discardDraft() },
                        onSave = { name, chosen, color, season, occasion -> if(category!=AllWardrobeCategory)category = chosen; model.saveDraft(name, chosen,color,season,occasion) }
                    )
                } else if (editing != null) {
                    GarmentEditor(
                        key = editing.id, title = "衣物详情", photo = model.thumbnail(editing),
                        initialName = editing.name, initialCategory = editing.category, busy = state.busy,
                        initialColor=editing.color,initialSeason=editing.season,initialOccasion=editing.occasion,
                        photoControls={
                            val linked=state.activeOutfits.filter{it.items.any{piece->piece.garmentId==editing.id}}
                            Text("关联搭配（${linked.size}）")
                            linked.forEach{o->TextButton(onClick={model.openOutfit(o)},enabled=!state.busy){Text("编辑搭配：${o.outfit.name}")}}
                        },
                        onCancel = { editingId = null },
                        onSave = { name, chosen, color, season, occasion ->
                            model.edit(editing, name, chosen,color,season,occasion) { if(category!=AllWardrobeCategory)category = chosen; editingId = null }
                        },
                        onDelete = { model.delete(editing) { editingId = null } },
                        onReprocess = { name, chosen, color, season, occasion -> editingId = null; model.reprocess(editing,name,chosen,color,season,occasion) }
                    )
                } else if (page == Page.Outfits) {
                    OutfitList(state, model, onNew = { model.newOutfit() }, Modifier.weight(1f), onDetailChange = { outfitDetail = it })
                } else {
                    Text("小衣橱", color = Rose, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(6.dp))
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            when (page) {
                                Page.Wardrobe -> "我的衣橱"
                                Page.Canvas -> "今日穿搭"
                                Page.Outfits -> "我的搭配"
                            }, modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold
                        )
                        if (page == Page.Wardrobe) Button(onClick = { chooseSource = true }, enabled = !state.busy && !state.loading && !state.loadFailed) { Text("录入衣物") }
                        if (page == Page.Wardrobe) Box {
                            GlyphButton("more", "衣橱菜单", !state.busy) { wardrobeMenu = true }
                            DropdownMenu(wardrobeMenu,{wardrobeMenu=false}) {
                                DropdownMenuItem(text={Text("备份与恢复")},onClick={wardrobeMenu=false;backupOpen=true})
                                DropdownMenuItem(text={Text("回收站")},onClick={wardrobeMenu=false;trashOpen=true})
                                DropdownMenuItem(text={Text("批量录入草稿（${state.batch.size}）")},onClick={wardrobeMenu=false;batchOpen=true})
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    when (page) {
                        Page.Wardrobe -> {
                            CategoryTabs(category, !state.busy, includeAll=true) { category = it }
                            val visible = filterWardrobe(state.garments,query.copy(category=category))
                            val backupReminder=if(state.activeGarments.isNotEmpty() &&
                                (state.lastBackupAt==0L || System.currentTimeMillis()-state.lastBackupAt>7L*24*60*60*1000))
                                if(state.lastBackupAt==0L)"尚未备份 · 保护你的衣橱" else "距上次备份已超过 7 天 · 立即备份"
                                else null
                            SearchFilters(query,visible.size,backupReminder,!state.busy,onBackup={backupOpen=true}){query=it}
                            Text(if(category==AllWardrobeCategory)"全部 ${visible.size} 件单品" else "${state.activeGarments.size} 件衣物 · 当前分类 ${visible.size} 件", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            when {
                                state.loading -> Box(Modifier.weight(1f).fillMaxWidth(), Alignment.Center) { CircularProgressIndicator() }
                                state.loadFailed -> Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("衣橱暂时无法读取")
                                    Button(onClick = { model.reload() }) { Text("重试") }
                                }
                                visible.isEmpty() -> EmptyCard("把喜欢的衣服放进来", if(query.text.isNotBlank()||query.color.isNotBlank()||query.season.isNotBlank()||query.occasion.isNotBlank())"没有匹配的单品，试试清空搜索或重置筛选。" else if(category==AllWardrobeCategory)"选择平铺或悬挂的照片，收好衣服、鞋子、包包和配饰。" else "还没有${category}。选择平铺或悬挂的照片，慢慢整理你的衣橱。", Modifier.weight(1f))
                                else -> LazyVerticalGrid(
                                    columns = GridCells.Fixed(3), modifier = Modifier.weight(1f),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(10.dp),
                                    contentPadding = PaddingValues(vertical = 12.dp)
                                ) {
                                    items(visible, key = { it.id }) { garment ->
                                        WardrobeTile(garment, model, state.busy, onOpen = { editingId = garment.id })
                                    }
                                }
                            }
                        }
                        Page.Canvas -> {
                            Surface(Modifier.weight(1f).fillMaxWidth(), shape = RoundedCornerShape(24.dp), color = Color(0xFFF6EFED)) {
                                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("搭配从一件喜欢开始", style = MaterialTheme.typography.titleLarge)
                                    Spacer(Modifier.height(12.dp))
                                    Text(if (state.activeGarments.isEmpty()) "先录入衣物，再来组合你的穿搭。" else "衣物已收好，可以开始组合穿搭。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Spacer(Modifier.height(20.dp))
                                    Button(onClick = { model.newOutfit() }, enabled = !state.busy) { Text("新建搭配") }
                                }
                            }
                        }
                        Page.Outfits -> OutfitList(state, model, onNew = { model.newOutfit() }, Modifier.weight(1f))
                    }
                    if (state.busy) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text("正在处理，请稍候…", fontSize = 12.sp)
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }
    if(batchOpen)WardrobeTheme {BatchPanel(state,model,::pickBatch){batchOpen=false}}
    if(trashOpen)WardrobeTheme {TrashPanel(state,model){trashOpen=false}}
    if (backupOpen) WardrobeTheme { BackupPanel(state, model) { backupOpen = false } }
    if (repairOpen && state.draft?.cutout != null) WardrobeTheme {
        val draft=state.draft!!
        EdgeRepairDialog(draft.cropped ?: draft.original,draft.cutout!!,{repairOpen=false}) { strokes -> repairOpen=false;model.repair(strokes) }
    }
    if (confirmLeaveCanvas && state.canvas != null) WardrobeTheme {
        AlertDialog(onDismissRequest = { if (!state.busy) confirmLeaveCanvas = false }, title = { Text("搭配尚未保存") },
            text = { Text("保存后可以继续编辑，或放弃本次未保存的修改。") },
            confirmButton = { TextButton(enabled = !state.busy && state.canvas!!.draft.items.isNotEmpty(),
                onClick = { if(state.canvas!!.draft.name.isBlank())model.renameOutfit("我的穿搭"); model.saveOutfit(closeAfterSave = true) { confirmLeaveCanvas = false; page = Page.Outfits } }) { Text("保存并返回") } },
            dismissButton = {
                Row {
                    TextButton(enabled = !state.busy, onClick = { confirmLeaveCanvas = false }) { Text("继续编辑") }
                    TextButton(enabled = !state.busy, onClick = { confirmLeaveCanvas = false; model.closeOutfit(); page = Page.Outfits }) { Text("放弃修改") }
                }
            })
    }
    if (cropOpen && state.draft != null) WardrobeTheme {
        val draft = state.draft!!
        CropDialog(draft.thumbnail, draft.cropBounds,
            onCancel = { cropOpen = false },
            onConfirm = { bounds -> cropOpen = false; model.crop(bounds) })
    }
    if (chooseSource) WardrobeTheme {
        AlertDialog(
            onDismissRequest = { chooseSource = false },
            title = { Text("录入衣物") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("一次拍一件，平铺或挂起，尽量完整入镜。背景与衣服颜色分开，抠图会更清楚。")
                    Button(onClick = { chooseSource = false; onTakePhoto(importCategory) }, modifier = Modifier.fillMaxWidth()) { Text("拍照录入") }
                    OutlinedButton(onClick = { chooseSource = false; pickPhoto() }, modifier = Modifier.fillMaxWidth()) { Text("从相册选择") }
                    OutlinedButton(onClick={chooseSource=false;batchOpen=true;if(state.batch.isEmpty())pickBatch()},modifier=Modifier.fillMaxWidth()){Text("批量选择照片")}
                }
            },
            confirmButton = { TextButton(onClick = { chooseSource = false }) { Text("取消") } }
        )
    }
}

@Composable
private fun ColumnScope.GarmentEditor(
    key: String, title: String, photo: File, initialName: String, initialCategory: String, busy: Boolean,
    onCancel: () -> Unit, onSave: (String, String,String,String,String) -> Unit, onDelete: (() -> Unit)? = null,
    processingCutout: Boolean = false, photoControls: (@Composable () -> Unit)? = null,
    onReprocess: ((String,String,String,String,String)->Unit)? = null,
    onMetadataChange: ((String,String)->Unit)? = null,
    initialColor:String="",initialSeason:String="",initialOccasion:String="",onAttributesChange:((String,String,String)->Unit)?=null
) {
    var name by rememberSaveable(key) { mutableStateOf(initialName) }
    var category by rememberSaveable(key) { mutableStateOf(initialCategory) }
    var color by rememberSaveable(key){mutableStateOf(initialColor)}
    var season by rememberSaveable(key){mutableStateOf(initialSeason)}
    var occasion by rememberSaveable(key){mutableStateOf(initialOccasion)}
    var confirmDelete by rememberSaveable(key) { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onCancel, enabled = !busy) { Text("返回") }
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
    }
    Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).imePadding()) {
        LocalPhoto(photo, "衣物预览", Modifier.fillMaxWidth().height(260.dp).clip(RoundedCornerShape(20.dp)).background(Color(0xFFF4EFEC)).padding(12.dp))
        photoControls?.invoke()
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(value = name, onValueChange = { if (it.length <= 60) { name = it;onMetadataChange?.invoke(name,category) } },
            modifier = Modifier.fillMaxWidth(), enabled = !busy, singleLine = true,
            label = { Text("衣物名称") }, placeholder = { Text("例如：粉色针织衫") }, supportingText = { Text("${name.length}/60") })
        Text("衣物分类", style = MaterialTheme.typography.titleSmall)
        CategoryTabs(category, !busy) { category = it;onMetadataChange?.invoke(name,category) }
        Spacer(Modifier.height(12.dp))
        ChoiceField("颜色",color,listOf("")+GarmentColors,!busy){color=it;onAttributesChange?.invoke(color,season,occasion)}
        ChoiceField("季节",season,listOf("")+GarmentSeasons,!busy){season=it;onAttributesChange?.invoke(color,season,occasion)}
        ChoiceField("场合",occasion,listOf("")+GarmentOccasions,!busy){occasion=it;onAttributesChange?.invoke(color,season,occasion)}
        Button(onClick = { onSave(name.trim(), category,color,season,occasion) }, enabled = name.isNotBlank() && !busy,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(if (processingCutout) "正在自动抠图…" else if (busy) "正在保存…" else "保存衣物") }
        if (onDelete != null) {
            if(onReprocess!=null) {
                OutlinedButton(onClick={onReprocess(name,category,color,season,occasion)},enabled=!busy,modifier=Modifier.fillMaxWidth()) { Text("重新处理照片（另存新单品）") }
                Text("从原照片重新裁剪、抠图或修补。保存为新单品，已有搭配继续使用原版本。",fontSize=12.sp)
            }
            TextButton(onClick = { confirmDelete = true }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("删除衣物", color = MaterialTheme.colorScheme.error) }
        } else {
            TextButton(onClick = onCancel, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("取消录入") }
        }
        Spacer(Modifier.height(16.dp))
    }
    if (confirmDelete) AlertDialog(
        onDismissRequest = { if (!busy) confirmDelete = false }, title = { Text("删除这件衣物？") },
        text = { Text("将移入回收站，照片和搭配引用保留，可从衣橱菜单恢复。") },
        confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete?.invoke() }, enabled = !busy) { Text("确认删除") } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("保留衣物") } }
    )
}

@Composable
internal fun LocalPhoto(file: File, description: String, modifier: Modifier) {
    val result by produceState<Pair<Boolean, ImageBitmap?>>(false to null, file.path) {
        value = false to null
        value = true to withContext(Dispatchers.IO) { try { decodeThumbnail(file, 512).asImageBitmap() } catch(_: Exception) { null } }
    }
    if (result.second != null) Image(result.second!!, description, modifier, contentScale = ContentScale.Fit)
    else Box(modifier, contentAlignment = Alignment.Center) { if(result.first)Text("照片暂不可用", fontSize = 12.sp) }
}

@Composable
private fun CategoryTabs(selected: String, enabled: Boolean = true, includeAll:Boolean=false, onSelect: (String) -> Unit) {
    BaselineCategories(selected, enabled, includeAll=includeAll, onSelect = onSelect)
}

@Composable
private fun EmptyCard(title: String, description: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Column(Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(24.dp)).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            LineIcon("heart", Modifier.size(44.dp), color = Rose)
            Spacer(Modifier.height(16.dp))
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(12.dp))
            Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
