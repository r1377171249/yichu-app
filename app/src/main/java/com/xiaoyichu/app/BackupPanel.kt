package com.xiaoyichu.app

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.xiaoyichu.app.data.OutfitRenderer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.io.File

@Composable
internal fun BackupPanel(state:WardrobeState,model:WardrobeViewModel,onClose:()->Unit) {
    var exportPath by rememberSaveable { mutableStateOf<String?>(null) }
    var writing by remember { mutableStateOf(false) }
    var allowDuplicate by rememberSaveable { mutableStateOf(false) }
    var status by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(state.message) { if(state.message!=null)status=state.message }
    val context=LocalContext.current;val scope=rememberCoroutineScope()
    val creator=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if(uri!=null && exportPath!=null) scope.launch {
            writing=true
            try { OutfitRenderer.write(context,File(exportPath!!),uri);model.backupWritten();model.showMessage("备份已导出") }
            catch(cancel:CancellationException){throw cancel}
            catch(_:Exception){model.showMessage("备份导出失败，请重新选择保存位置")}
            finally { writing=false }
        }
    }
    val opener=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if(uri!=null){allowDuplicate=false;model.inspectBackup(uri)} }
    val busy=state.busy||writing
    val summary=state.backupSummary
    if(summary!=null) AlertDialog(onDismissRequest={if(!busy)model.discardBackup()},title={Text(if(state.backupDuplicate)"这份备份已导入过" else "确认合并导入？")},text={
        Column(Modifier.heightIn(max=360.dp).verticalScroll(androidx.compose.foundation.rememberScrollState())) {
            Text("备份已通过完整性检查，包含 ${summary.garments} 件衣物、${summary.outfits} 套搭配、${summary.instances} 个实例（含回收站）。保留现有衣橱，冲突日期保留当前记录。")
            Text(model.backupPreview())
            if(state.backupDuplicate)Row { Checkbox(allowDuplicate,{allowDuplicate=it});Text("我仍要再次导入并新增副本") }
        }
    },confirmButton={TextButton(onClick={model.restoreBackup(allowDuplicate);allowDuplicate=false},enabled=!busy&&(!state.backupDuplicate||allowDuplicate)){Text(if(busy)"正在导入…" else "合并导入")}},
        dismissButton={TextButton(onClick={allowDuplicate=false;model.discardBackup()},enabled=!busy){Text("取消导入")}})
    else AlertDialog(onDismissRequest={if(!busy)onClose()},title={Text("备份与恢复")},text={Column(verticalArrangement=Arrangement.spacedBy(14.dp)) {
        Text("把衣物、可编辑搭配、日历、合集和回收站一起保存到你选择的位置。")
        Text(if(state.lastBackupAt==0L)"尚未导出备份，建议现在备份" else "最近备份："+java.text.SimpleDateFormat("yyyy-MM-dd HH:mm",java.util.Locale.CHINA).format(java.util.Date(state.lastBackupAt)))
        Button(onClick={status=null;model.createBackup {file->exportPath=file.path;creator.launch("小衣橱-${java.time.LocalDate.now()}.zip")}},enabled=!busy,modifier=Modifier.fillMaxWidth()){Text("导出完整备份")}
        OutlinedButton(onClick={status=null;opener.launch(arrayOf("application/zip","application/x-zip-compressed","application/octet-stream"))},enabled=!busy,modifier=Modifier.fillMaxWidth()){Text("从备份合并导入")}
        Text("导入前会核对文件和图片，保留当前数据。卸载应用前请把备份保存到应用外。")
        if(busy){LinearProgressIndicator(Modifier.fillMaxWidth());Text("正在处理，请稍候…")}
        if(status!=null)Text(status!!)
    }},confirmButton={TextButton(onClick=onClose,enabled=!busy){Text("关闭")}})
}
