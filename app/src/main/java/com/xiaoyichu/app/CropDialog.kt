package com.xiaoyichu.app

import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.xiaoyichu.app.data.CropBounds
import com.xiaoyichu.app.data.decodeThumbnail
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

private val BoundsSaver = listSaver<CropBounds, Float>(
    save = { listOf(it.left, it.top, it.right, it.bottom) },
    restore = { CropBounds(it[0], it[1], it[2], it[3]) }
)
private const val MinSpan = .08f

@Composable
fun CropDialog(photo: File, initial: CropBounds, onCancel: () -> Unit, onConfirm: (CropBounds) -> Unit) {
    var selection by rememberSaveable(photo.path, stateSaver = BoundsSaver) { mutableStateOf(initial) }
    val bitmap by produceState<ImageBitmap?>(null, photo.path) {
        value = withContext(Dispatchers.IO) { try { decodeThumbnail(photo, 768).asImageBitmap() } catch(_: Exception) { null } }
    }
    Dialog(onDismissRequest = onCancel, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BackHandler(onBack = onCancel)
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = onCancel) { Text("取消裁剪") }
                    Text("裁剪照片", Modifier.padding(top = 12.dp), style = MaterialTheme.typography.titleMedium)
                    Button(onClick = { onConfirm(selection) }, enabled = bitmap != null) { Text("确认裁剪") }
                }
                Text("拖动四角调整范围，拖动框内移动。原照片会保留。", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                val current = bitmap
                if (current != null) {
                    CropCanvas(current, selection, { selection = it }, Modifier.weight(1f).fillMaxWidth())
                } else {
                    Box(Modifier.weight(1f).fillMaxWidth()) { Text("正在读取照片…") }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = { selection = CropBounds.Full }) { Text("恢复整张") }
                    TextButton(onClick = { selection = CropBounds(.1f, .1f, .9f, .9f) }) { Text("居中 80%") }
                }
                Text("左右范围：${(selection.left * 100).roundToInt()}% — ${(selection.right * 100).roundToInt()}%", style = MaterialTheme.typography.bodySmall)
                RangeSlider(value = selection.left..selection.right, onValueChange = { range ->
                    val left = range.start.coerceIn(0f, maxOf(0f, selection.right - MinSpan))
                    val right = range.endInclusive.coerceIn(minOf(1f, left + MinSpan), 1f)
                    selection = selection.copy(left = left, right = right)
                }, modifier = Modifier.fillMaxWidth().semantics { contentDescription = "左右裁剪范围" })
                Text("上下范围：${(selection.top * 100).roundToInt()}% — ${(selection.bottom * 100).roundToInt()}%", style = MaterialTheme.typography.bodySmall)
                RangeSlider(value = selection.top..selection.bottom, onValueChange = { range ->
                    val top = range.start.coerceIn(0f, maxOf(0f, selection.bottom - MinSpan))
                    val bottom = range.endInclusive.coerceIn(minOf(1f, top + MinSpan), 1f)
                    selection = selection.copy(top = top, bottom = bottom)
                }, modifier = Modifier.fillMaxWidth().semantics { contentDescription = "上下裁剪范围" })
            }
        }
    }
}

private fun fittedRect(size: Size, bitmap: ImageBitmap, padding: Float): Rect {
    val scale = minOf(maxOf(1f, size.width - padding * 2) / bitmap.width,
        maxOf(1f, size.height - padding * 2) / bitmap.height)
    val width = bitmap.width * scale
    val height = bitmap.height * scale
    return Rect((size.width - width) / 2, (size.height - height) / 2,
        (size.width + width) / 2, (size.height + height) / 2)
}

@Composable
private fun CropCanvas(bitmap: ImageBitmap, bounds: CropBounds, onChange: (CropBounds) -> Unit, modifier: Modifier) {
    val latestBounds by rememberUpdatedState(bounds)
    val latestChange by rememberUpdatedState(onChange)
    val rose = MaterialTheme.colorScheme.primary
    Canvas(modifier.background(Color(0xFFF1EAE7)).semantics {
        contentDescription = "裁剪区域"
        stateDescription = "左${(bounds.left * 100).roundToInt()}%，上${(bounds.top * 100).roundToInt()}%，右${(bounds.right * 100).roundToInt()}%，下${(bounds.bottom * 100).roundToInt()}%"
    }.pointerInput(bitmap) {
        var handle = -1
        detectDragGestures(onDragStart = { point ->
            val rect = fittedRect(Size(size.width.toFloat(), size.height.toFloat()), bitmap, 20.dp.toPx())
            val b = latestBounds
            val corners = listOf(Offset(b.left, b.top), Offset(b.right, b.top), Offset(b.left, b.bottom), Offset(b.right, b.bottom))
                .map { Offset(rect.left + it.x * rect.width, rect.top + it.y * rect.height) }
            val nearest = corners.indices.minBy { (point - corners[it]).getDistance() }
            val frame = Rect(rect.left + b.left * rect.width, rect.top + b.top * rect.height,
                rect.left + b.right * rect.width, rect.top + b.bottom * rect.height)
            handle = if ((point - corners[nearest]).getDistance() <= 32.dp.toPx()) nearest else if (frame.contains(point)) 4 else -1
        }, onDragEnd = { handle = -1 }, onDragCancel = { handle = -1 }) { change, delta ->
            if (handle >= 0) {
                change.consume()
                val rect = fittedRect(Size(size.width.toFloat(), size.height.toFloat()), bitmap, 20.dp.toPx())
                val b = latestBounds
                val dx = delta.x / rect.width
                val dy = delta.y / rect.height
                val next = when (handle) {
                    0 -> b.copy(left = (b.left + dx).coerceIn(0f, maxOf(0f, b.right - MinSpan)), top = (b.top + dy).coerceIn(0f, maxOf(0f, b.bottom - MinSpan)))
                    1 -> b.copy(right = (b.right + dx).coerceIn(minOf(1f, b.left + MinSpan), 1f), top = (b.top + dy).coerceIn(0f, maxOf(0f, b.bottom - MinSpan)))
                    2 -> b.copy(left = (b.left + dx).coerceIn(0f, maxOf(0f, b.right - MinSpan)), bottom = (b.bottom + dy).coerceIn(minOf(1f, b.top + MinSpan), 1f))
                    3 -> b.copy(right = (b.right + dx).coerceIn(minOf(1f, b.left + MinSpan), 1f), bottom = (b.bottom + dy).coerceIn(minOf(1f, b.top + MinSpan), 1f))
                    else -> {
                        val moveX = dx.coerceIn(-b.left, 1f - b.right)
                        val moveY = dy.coerceIn(-b.top, 1f - b.bottom)
                        CropBounds((b.left + moveX).coerceIn(0f, 1f), (b.top + moveY).coerceIn(0f, 1f),
                            (b.right + moveX).coerceIn(0f, 1f), (b.bottom + moveY).coerceIn(0f, 1f))
                    }
                }
                latestChange(next)
            }
        }
    }) {
        val image = fittedRect(size, bitmap, 20.dp.toPx())
        drawImage(bitmap, dstOffset = IntOffset(image.left.roundToInt(), image.top.roundToInt()),
            dstSize = IntSize(maxOf(1, image.width.roundToInt()), maxOf(1, image.height.roundToInt())))
        val l = image.left + bounds.left * image.width
        val t = image.top + bounds.top * image.height
        val r = image.left + bounds.right * image.width
        val b = image.top + bounds.bottom * image.height
        val dim = Color.Black.copy(alpha = .5f)
        drawRect(dim, Offset(image.left, image.top), Size(image.width, t - image.top))
        drawRect(dim, Offset(image.left, b), Size(image.width, image.bottom - b))
        drawRect(dim, Offset(image.left, t), Size(l - image.left, b - t))
        drawRect(dim, Offset(r, t), Size(image.right - r, b - t))
        drawRect(rose, Offset(l, t), Size(r - l, b - t), style = Stroke(2.dp.toPx()))
        for (i in 1..2) {
            drawLine(Color.White.copy(alpha = .6f), Offset(l + (r - l) * i / 3, t), Offset(l + (r - l) * i / 3, b))
            drawLine(Color.White.copy(alpha = .6f), Offset(l, t + (b - t) * i / 3), Offset(r, t + (b - t) * i / 3))
        }
        for (point in listOf(Offset(l, t), Offset(r, t), Offset(l, b), Offset(r, b))) {
            drawCircle(Color.White, 7.dp.toPx(), point)
            drawCircle(rose, 7.dp.toPx(), point, style = Stroke(2.dp.toPx()))
        }
    }
}
