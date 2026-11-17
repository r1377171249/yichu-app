package com.xiaoyichu.app

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xiaoyichu.app.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException

data class WardrobeState(
    val garments: List<Garment> = emptyList(),
    val loading: Boolean = true,
    val loadFailed: Boolean = false,
    val busy: Boolean = true,
    val restoringSession: Boolean = true,
    val processingCutout: Boolean = false,
    val draft: DraftPhoto? = null,
    val message: String? = null,
    val outfits: List<SavedOutfit> = emptyList(),
    val outfitsLoading: Boolean = true,
    val outfitsLoadFailed: Boolean = false,
    val canvas: CanvasState? = null,
    val backupSummary: BackupSummary? = null,
    val calendar:List<CalendarEntry> = emptyList(),
    val batch:List<BatchPhoto> = emptyList(),
    val batchProgress:String? = null,
    val lastBackupAt:Long = 0,
    val backupDuplicate:Boolean = false
)

class WardrobeViewModel(private val repository: GarmentRepository, private val outfitRepository: OutfitRepository,
    private val backupRepository: BackupRepository, private val sessionStore: EditorSessionStore, private val batchStore:BatchImportStore) : ViewModel() {
    private val mutable = MutableStateFlow(WardrobeState())
    val state = mutable.asStateFlow()
    private var observation: Job? = null
    private var outfitObservation: Job? = null
    private var workspace: OutfitWorkspace? = null
    private var preparedBackup: PreparedBackup? = null
    private var autosave: Job? = null
    private val ready = CompletableDeferred<Unit>()

    init {
        reload(); reloadOutfits()
        viewModelScope.launch { outfitRepository.calendar.collect { entries -> mutable.update{it.copy(calendar=entries)} } }
        viewModelScope.launch {
            try {
                val result=sessionStore.load()
                try{val batch=batchStore.load();mutable.update{it.copy(batch=batch,lastBackupAt=backupRepository.lastBackupAt())}}catch(_:Exception){showMessage("批量草稿暂时无法读取，已保存衣橱未改动")}
                workspace=result.session?.canvas?.let { OutfitWorkspace.restore(it) }
                mutable.update { it.copy(draft=result.session?.photo,canvas=workspace?.state,
                    message=if(result.damaged) "未保存草稿无法恢复，已保存衣橱未改动" else if(result.session?.photo!=null || workspace!=null) "已恢复上次未关闭的编辑" else it.message) }
                // A damaged record may still refer to valuable photos: never expire its files.
                if(!result.damaged) repository.pruneAbandonedDrafts(result.session?.photo?.id)
            } catch (_: Exception) { showMessage("暂时无法恢复草稿，已保存衣橱未改动") }
            finally { mutable.update { it.copy(busy=false,restoringSession=false) }; ready.complete(Unit) }
            val photo=mutable.value.draft
            if(photo!=null && photo.cutout==null && !photo.cutoutError) retryCutout()
        }
    }

    private suspend fun checkpoint() {
        val photo=mutable.value.draft
        sessionStore.write(EditorSession(photo,workspace?.recovery))
        photo?.let { repository.pruneDraft(it) }
    }
    private fun scheduleCheckpoint() {
        autosave?.cancel()
        autosave=viewModelScope.launch {
            delay(350)
            try { checkpoint() }
            catch (error: CancellationException) { throw error }
            catch (_: Exception) { showMessage("草稿自动保存未完成，请保留此页面并检查存储空间") }
        }
    }

    fun reloadOutfits() {
        outfitObservation?.cancel()
        mutable.update { it.copy(outfitsLoading = true, outfitsLoadFailed = false) }
        outfitObservation = viewModelScope.launch {
            outfitRepository.outfits.catch {
                mutable.update { state -> state.copy(outfitsLoading = false, outfitsLoadFailed = true) }
            }.collect { saved -> mutable.update { it.copy(outfits = saved, outfitsLoading = false) } }
        }
    }

    private fun publishCanvas() { mutable.update { it.copy(canvas = workspace?.state) }; scheduleCheckpoint() }
    fun newOutfit() { if (!mutable.value.busy) { workspace = OutfitWorkspace(); publishCanvas() } }
    fun openOutfit(saved: SavedOutfit) {
        if (mutable.value.busy) return
        workspace = OutfitWorkspace(OutfitDraft(saved.outfit.id, saved.outfit.name, saved.items.sortedBy { it.zIndex }))
        publishCanvas()
    }
    fun closeOutfit() = perform("编辑暂时无法关闭，请检查存储空间后重试") {
        val previous=workspace;workspace=null
        try { checkpoint() } catch(error:Exception) { workspace=previous;throw error }
        publishCanvas()
    }
    private fun canvasAction(action: OutfitWorkspace.() -> Unit) {
        if (mutable.value.busy) return
        workspace?.action(); publishCanvas()
    }
    fun renameOutfit(name: String) = canvasAction { rename(name) }
    fun selectPiece(id: String?) = canvasAction { select(id) }
    fun beginCanvasGesture() = canvasAction { beginGesture() }
    fun endCanvasGesture() = canvasAction { endGesture() }
    fun transformPiece(dx: Float, dy: Float, zoom: Float, angle: Float) = canvasAction { transform(dx, dy, zoom, angle) }
    fun addPiece(garment: Garment) {
        if (workspace?.draft?.items?.size == 30) { showMessage("每套搭配最多 30 个单品"); return }
        canvasAction { add(garment.id) }
    }
    fun adjustPiece(zoom: Float = 1f, angle: Float = 0f) = canvasAction { adjust(zoom, angle) }
    fun removePiece() = canvasAction { remove() }
    fun layerPiece(top: Boolean) = canvasAction { layer(top) }
    fun duplicatePiece() {
        if (workspace?.draft?.items?.size == 30) { showMessage("每套搭配最多 30 个单品"); return }
        canvasAction { duplicate() }
    }
    fun replacePiece(garment: Garment) = canvasAction { replace(garment.id) }
    fun cropPiece(bounds: CropBounds) = canvasAction { crop(bounds) }
    fun filterPiece(value: String) = canvasAction { filter(value) }
    fun standardLayout() = perform("布局未调整，请检查衣物图片后重试") {
        val draft = workspace?.draft ?: return@perform
        val ratios = withContext(Dispatchers.IO) { draft.items.map { it.garmentId }.distinct().associateWith { id ->
            val garment = mutable.value.garments.first { it.id == id }
            val image = decodeThumbnail(artwork(garment), 128)
            try { image.height.toFloat() / image.width } finally { image.recycle() }
        } }
        workspace?.standardLayout(ratios); publishCanvas()
    }
    fun undoCanvas() = canvasAction { undo() }
    fun redoCanvas() = canvasAction { redo() }
    fun saveOutfit(closeAfterSave: Boolean = false, onSaved: () -> Unit = {}) = perform("搭配未保存，请检查衣物和存储空间后重试") {
        val editor = workspace ?: return@perform
        editor.endGesture()
        outfitRepository.save(editor.draft)
        editor.markSaved(); if (closeAfterSave) workspace = null; publishCanvas()
        mutable.update { it.copy(message = "搭配已保存") }
        onSaved()
    }
    fun deleteOutfit(saved: SavedOutfit) = perform("搭配暂时无法删除，请重试") {
        outfitRepository.delete(saved.outfit.id)
        mutable.update { it.copy(message = "搭配已移入回收站，可随时恢复") }
    }
    fun artwork(garment: Garment): File = outfitRepository.artwork(garment)
    fun createBackup(onReady: (File) -> Unit) = perform("备份未生成，请检查图片和可用存储空间后重试") {
        onReady(backupRepository.export())
    }
    fun inspectBackup(uri: Uri) = perform("备份无法读取或校验不通过，原衣橱未改动") {
        preparedBackup?.let { backupRepository.discard(it) }; preparedBackup = null
        mutable.update { it.copy(backupSummary = null) }
        preparedBackup = backupRepository.inspect(uri)
        mutable.update { it.copy(backupSummary = preparedBackup?.summary,backupDuplicate=preparedBackup?.alreadyRestored==true) }
    }
    fun backupPreview()=preparedBackup?.let { p -> "衣物："+p.garments.take(8).joinToString("、"){it.name}+"\n搭配："+p.outfits.take(8).joinToString("、"){it.name}+"\n日历记录 ${p.calendar.size} 条；完整内容将一起导入" } ?: ""
    fun discardBackup() = perform("暂时无法取消，请重试") {
        preparedBackup?.let { backupRepository.discard(it) }; preparedBackup = null
        mutable.update { it.copy(backupSummary = null) }
    }
    fun restoreBackup(allowDuplicate:Boolean=false) = perform("恢复未完成，原衣橱已保留，请检查存储空间后重试") {
        val prepared = preparedBackup ?: return@perform
        try {
            val result = backupRepository.restore(prepared,allowDuplicate)
            showMessage("已合并导入 ${result.garments} 件衣物、${result.outfits} 套搭配")
        } finally { preparedBackup = null; mutable.update { it.copy(backupSummary = null) } }
    }
    fun outfitMetadata(saved: SavedOutfit, name: String, tags: String) = perform("搭配信息未保存，请重试") {
        outfitRepository.metadata(saved.outfit.id, name, tags)
    }
    fun favoriteOutfit(saved: SavedOutfit) = perform("收藏未更新，请重试") { outfitRepository.favorite(saved.outfit.id) }
    fun collection(saved:SavedOutfit,name:String)=perform("合集未保存，请重试"){outfitRepository.collection(saved.outfit.id,name)}
    fun scheduleOutfit(saved:SavedOutfit,date:String,worn:Boolean)=perform("日期无效，实际穿着不能记录在未来；原记录未改动"){outfitRepository.schedule(saved.outfit.id,date,worn)}
    fun unschedule(date:String)=perform("日历未修改，请重试"){outfitRepository.unschedule(date)}
    fun restoreGarment(g:Garment)=perform("衣物未恢复，请重试"){repository.restore(g)}
    fun restoreOutfit(o:SavedOutfit)=perform("搭配未恢复，请重试"){outfitRepository.restore(o.outfit.id)}
    fun permanentlyDeleteGarment(g:Garment)=perform("永久删除未完成；若衣物仍用于搭配，请先删除或修改对应搭配"){repository.permanentlyDelete(g)}
    fun permanentlyDeleteOutfit(o:SavedOutfit)=perform("永久删除未完成，请重试"){outfitRepository.permanentlyDelete(o.outfit.id)}
    fun saveAttributes(g:Garment,color:String,season:String,occasion:String)=perform("衣物属性未保存，请重试"){repository.attributes(g.id,color,season,occasion)}
    fun backupWritten() { backupRepository.markExported();mutable.update{it.copy(lastBackupAt=backupRepository.lastBackupAt())} }
    fun recommended(suggestion:OutfitSuggestion) {
        if(mutable.value.busy)return
        workspace=OutfitWorkspace();workspace!!.rename("穿搭灵感")
        suggestion.garments.forEach{workspace!!.add(it.id)};publishCanvas();standardLayout()
    }
    fun todayOutfit(saved: SavedOutfit) = perform("今日穿搭未更新，请重试") {
        outfitRepository.today(saved.outfit.id); showMessage("已设为今日穿搭")
    }

    fun reload() {
        observation?.cancel()
        mutable.update { it.copy(loading = true, loadFailed = false) }
        observation = viewModelScope.launch {
            repository.garments.catch {
                mutable.update { state -> state.copy(loading = false, loadFailed = true, message = "衣橱暂时无法读取，请重试") }
            }.collect { garments -> mutable.update { it.copy(garments = garments, loading = false) } }
        }
    }

    fun importPhoto(uri: Uri, category:String = "上衣") = perform("无法读取照片，请重新选择其他图片") {
        check(mutable.value.draft==null)
        val photo = repository.prepare(uri).copy(suggestedCategory=category.takeIf { it in GarmentCategories } ?: "上衣")
        mutable.update { it.copy(draft = photo) }
        checkpoint()
        automaticCutout()
    }
    fun reprocess(garment:Garment,name:String,category:String,color:String=garment.color,season:String=garment.season,occasion:String=garment.occasion) = perform("重新处理未完成，原单品仍然保留，可以重试") {
        require(category in GarmentCategories)
        var photo=repository.prepare(Uri.fromFile(repository.original(garment))).copy(
            suggestedName=(name.trim().ifBlank{garment.name}).removeSuffix("（新版本）").take(55)+"（新版本）",
            suggestedCategory=category,favoriteOnSave=garment.favorite,color=color,season=season,occasion=occasion)
        mutable.update { it.copy(draft=photo) }
        checkpoint()
        val bounds=CropBounds(garment.cropLeft,garment.cropTop,garment.cropRight,garment.cropBottom)
        if(bounds!=CropBounds.Full){photo=repository.crop(photo,bounds);mutable.update{it.copy(draft=photo)};checkpoint()}
        automaticCutout()
    }

    fun importCapture(file: File, category:String = "上衣") = perform("这张照片无法读取，请重新拍摄或从相册选择") {
        val photo = try { check(mutable.value.draft==null); repository.prepare(Uri.fromFile(file)).copy(suggestedCategory=category.takeIf{it in GarmentCategories} ?: "上衣") }
        finally { withContext(NonCancellable + Dispatchers.IO) { file.delete() } }
        mutable.update { it.copy(draft = photo) }
        checkpoint()
        automaticCutout()
    }

    fun showMessage(message: String) { mutable.update { it.copy(message = message) } }

    fun retryCutout(mode:String?=null) = perform("自动抠图未完成，可使用原图保存") {
        if(mode!=null){require(mode in CutoutModes);mutable.update{it.copy(draft=it.draft?.copy(cutoutMode=mode))}}
        automaticCutout()
    }
    fun repair(strokes:List<RepairStroke>) = perform("边缘修补未保存，之前的图片已保留，请重试") {
        val photo=mutable.value.draft ?: return@perform
        val changed=repository.repair(photo,strokes)
        mutable.update { it.copy(draft=changed,message="边缘修补已应用，可继续保存") }
    }

    fun crop(bounds: CropBounds) = perform("裁剪未完成，已保留之前的图片，请重试") {
        val photo = mutable.value.draft ?: return@perform
        if (bounds == photo.cropBounds) return@perform
        val changed = repository.crop(photo, bounds)
        mutable.update { it.copy(draft = changed) }
        checkpoint()
        automaticCutout()
    }

    private suspend fun automaticCutout() {
        val photo = mutable.value.draft ?: return
        mutable.update { it.copy(processingCutout = true) }
        try {
            val processed = repository.cutout(photo)
            mutable.update { it.copy(draft = processed) }
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) {
            mutable.update { it.copy(draft = photo.copy(cutoutError = true), message = "自动抠图未完成，可以重试或使用原图保存") }
        } catch (error: LinkageError) {
            mutable.update { it.copy(draft = photo.copy(useCutout = false, cutoutError = true), message = "这台设备暂时无法运行自动抠图，可以使用原图保存") }
        } finally { mutable.update { it.copy(processingCutout = false) } }
    }

    fun chooseCutout(enabled: Boolean) {
        if (mutable.value.busy) return
        mutable.update { state -> state.copy(draft = state.draft?.let {
            it.copy(useCutout = enabled && it.cutout != null)
        }) }
        scheduleCheckpoint()
    }

    fun draftMetadata(name:String,category:String) {
        if(mutable.value.busy || name.length>60 || category !in GarmentCategories) return
        mutable.update { it.copy(draft=it.draft?.copy(suggestedName=name,suggestedCategory=category)) }
        scheduleCheckpoint()
    }

    fun draftAttributes(color:String,season:String,occasion:String) {
        if(mutable.value.busy || listOf(color,season,occasion).any{it.length>30})return
        mutable.update{it.copy(draft=it.draft?.copy(color=color,season=season,occasion=occasion))};scheduleCheckpoint()
    }
    fun discardDraft() = perform("暂时无法取消，请重试") {
        val previous=mutable.value.draft
        mutable.update { it.copy(draft = null) }
        try { checkpoint() } catch(error:Exception) { mutable.update{it.copy(draft=previous)};throw error }
        previous?.let { repository.discard(it) }
    }

    fun saveDraft(name: String, category: String, color:String="", season:String="", occasion:String="") = perform("保存失败，请检查存储空间后重试") {
        val draft = mutable.value.draft ?: return@perform
        repository.save(draft, name.trim(), category,color,season,occasion)
        mutable.update { it.copy(draft = null, message = "已放进衣橱") }
    }

    fun edit(garment: Garment, name: String, category: String,color:String,season:String,occasion:String, onSaved: () -> Unit) = perform("修改未保存，请重试") {
        repository.update(garment, name.trim(), category,color,season,occasion)
        onSaved()
        mutable.update { it.copy(message = "已保存修改") }
    }

    fun favorite(garment: Garment) = perform("收藏未更新，请重试") { repository.favorite(garment) }

    fun delete(garment: Garment, onDeleted: () -> Unit) = perform("删除失败，请重试") {
        repository.delete(garment)
        onDeleted()
        mutable.update { it.copy(message = "衣物已移入回收站，可随时恢复") }
    }

    fun thumbnail(garment: Garment): File = repository.thumbnail(garment)
    fun clearMessage() { mutable.update { it.copy(message = null) } }


    private suspend fun publishBatch(items:List<BatchPhoto>) { batchStore.write(items);mutable.update{it.copy(batch=items)} }
    fun importBatch(uris:List<Uri>,category:String)=perform("批量录入未全部完成，已准备的照片仍保留，可继续处理") {
        check(mutable.value.draft==null && mutable.value.batch.size+uris.size<=100)
        var failed=0
        for((index,uri) in uris.withIndex()) {
            mutable.update{it.copy(batchProgress="正在读取 ${index+1}/${uris.size}")}
            try {
                val photo=repository.prepare(uri,true)
                val item=BatchPhoto(photo,"新衣物 ${mutable.value.batch.size+1}",category.takeIf{it in GarmentCategories} ?: "上衣")
                try{publishBatch(mutable.value.batch+item)}catch(e:Exception){repository.discard(photo);throw e}
            } catch(e:CancellationException){throw e}catch(_:Exception){failed++}
        }
        processBatchCutouts()
        if(failed>0)showMessage("${failed} 张照片无法读取，请重新选择；其余照片已准备")
    }
    private suspend fun processBatchCutouts() {
        val pending=mutable.value.batch.filter{it.photo.cutout==null && !it.originalAccepted}
        try {for((index,item) in pending.withIndex()) {
            mutable.update{it.copy(batchProgress="正在自动抠图 ${index+1}/${pending.size}")}
            val photo=try{repository.cutout(item.photo)}catch(e:CancellationException){throw e}catch(_:Exception){item.photo.copy(cutoutError=true)}catch(_:LinkageError){item.photo.copy(cutoutError=true)}
            publishBatch(mutable.value.batch.map{if(it.photo.id==item.photo.id)it.copy(photo=photo) else it})
            repository.pruneDraft(photo)
        }}finally{mutable.update{it.copy(batchProgress=null)}}
    }
    fun continueBatch()=perform("部分照片未处理，可逐张重试"){processBatchCutouts()}
    fun retryBatch(id:String)=perform("这张照片抠图未完成，可重试或保留背景") {
        val item=mutable.value.batch.firstOrNull{it.photo.id==id} ?: return@perform
        val changed=try{repository.cutout(item.photo)}catch(_:LinkageError){showMessage("这台设备暂时无法运行抠图，可确认保留背景");return@perform}
        publishBatch(mutable.value.batch.map{if(it.photo.id==id)it.copy(photo=changed.copy(useCutout=!item.originalAccepted))else it});repository.pruneDraft(changed)
    }
    fun updateBatch(item:BatchPhoto)=perform("批量信息未保存，请重试") {
        require(item.name.length<=60 && item.category in GarmentCategories && listOf(item.color,item.season,item.occasion).all{it.length<=30})
        val old=mutable.value.batch.firstOrNull{it.photo.id==item.photo.id} ?: return@perform
        publishBatch(mutable.value.batch.map{if(it.photo.id==item.photo.id)item.copy(photo=old.photo.copy(useCutout=!item.originalAccepted&&old.photo.cutout!=null))else it})
    }
    fun removeBatch(id:String)=perform("这张照片暂未移除，请重试") {
        val old=mutable.value.batch.firstOrNull{it.photo.id==id} ?: return@perform
        publishBatch(mutable.value.batch.filterNot{it.photo.id==id});repository.discard(old.photo)
    }
    fun saveBatch()=perform("部分照片未保存，已保存的不会重复入库；可继续重试") {
        for(item in mutable.value.batch.filter{it.ready}) {
            if(!repository.exists(item.photo.id))repository.save(item.photoForSave(),item.name,item.category,item.color,item.season,item.occasion)
            publishBatch(mutable.value.batch.filterNot{it.photo.id==item.photo.id})
        }
        showMessage(if(mutable.value.batch.isEmpty())"批量衣物已放进衣橱" else "已保存可用照片；其余请重试抠图或确认保留背景")
    }

    private fun perform(failure: String, operation: suspend () -> Unit) {
        if(mutable.value.restoringSession) { viewModelScope.launch { ready.await();perform(failure,operation) };return }
        if (mutable.value.busy) return
        mutable.update { it.copy(busy = true, message = null) }
        viewModelScope.launch {
            try { autosave?.cancelAndJoin(); operation() }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                val message = if (error is GarmentInUseException || (error is IOException && error.message?.startsWith("照片超过") == true)) error.message!! else failure
                mutable.update { it.copy(message = message) }
            } finally {
                // Join any edit-triggered writer before the final state, so an older snapshot cannot win.
                withContext(NonCancellable) {
                    autosave?.cancelAndJoin()
                    try { checkpoint() }
                    catch (_: Exception) { showMessage("草稿恢复点未写入，请检查存储空间；已保存衣橱仍保留") }
                }
                mutable.update { it.copy(busy = false,batchProgress=null) }
            }
        }
    }
}
