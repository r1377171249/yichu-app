package com.xiaoyichu.app.data

import java.util.UUID
import kotlin.math.*

data class OutfitDraft(val id: String, val name: String = "", val items: List<OutfitItem> = emptyList())
data class CanvasState(val draft: OutfitDraft, val selectedId: String?, val dirty: Boolean, val canUndo: Boolean, val canRedo: Boolean)
data class WorkspaceRecovery(val draft: OutfitDraft, val baseline: OutfitDraft, val selectedId: String?)

fun OutfitItem.valid() = listOf(x, y, width, rotation, cropLeft, cropTop, cropRight, cropBottom).all { it.isFinite() } &&
    x in 0f..1f && y in 0f..1f && width in .08f.. .9f && rotation >= -180f && rotation < 180f &&
    cropLeft >= 0f && cropTop >= 0f && cropRight <= 1f && cropBottom <= 1f &&
    cropRight - cropLeft >= .01f && cropBottom - cropTop >= .01f && filter in OutfitFilters

/** Keep the point beneath the previous finger centroid anchored after zoom/rotation. */
fun OutfitItem.anchoredDelta(cx: Float, cy: Float, panX: Float, panY: Float, zoom: Float, angle: Float,
    boardWidth: Float, boardHeight: Float): Pair<Float, Float> {
    if (boardWidth <= 0 || boardHeight <= 0 || !listOf(cx, cy, panX, panY, zoom, angle).all { it.isFinite() } || zoom <= 0) return 0f to 0f
    val effectiveZoom = (width * zoom).coerceIn(.08f, .9f) / width
    val dx = x * boardWidth - cx; val dy = y * boardHeight - cy
    val radians = Math.toRadians(angle.toDouble())
    val nextX = cx + (dx * cos(radians) - dy * sin(radians)) * effectiveZoom + panX
    val nextY = cy + (dx * sin(radians) + dy * cos(radians)) * effectiveZoom + panY
    return (nextX.toFloat() / boardWidth - x) to (nextY.toFloat() / boardHeight - y)
}

fun OutfitItem.imageRatio(sourceWidth: Int, sourceHeight: Int): Float =
    sourceHeight.toFloat() / sourceWidth * (cropBottom - cropTop) / (cropRight - cropLeft)

fun OutfitItem.transformed(dx: Float, dy: Float, zoom: Float, angle: Float): OutfitItem {
    if (!listOf(dx, dy, zoom, angle).all { it.isFinite() } || zoom <= 0) return this
    val degrees = ((rotation + angle + 180f) % 360f + 360f) % 360f - 180f
    return copy(x = (x + dx).coerceIn(0f, 1f), y = (y + dy).coerceIn(0f, 1f),
        width = (width * zoom).coerceIn(.08f, .9f), rotation = degrees)
}

/** Hit testing uses the inverse of the same center/rotation/size transform used for drawing. */
fun OutfitItem.contains(px: Float, py: Float, canvasWidth: Float, canvasHeight: Float, imageRatio: Float): Boolean {
    if (canvasWidth <= 0 || canvasHeight <= 0 || imageRatio <= 0) return false
    val dx = px - x * canvasWidth; val dy = py - y * canvasHeight
    val radians = Math.toRadians(rotation.toDouble())
    val localX = dx * cos(radians) + dy * sin(radians)
    val localY = -dx * sin(radians) + dy * cos(radians)
    return abs(localX) <= width * canvasWidth / 2 && abs(localY) <= width * canvasWidth * imageRatio / 2
}

class OutfitWorkspace(initial: OutfitDraft = OutfitDraft(UUID.randomUUID().toString())) {
    companion object {
        fun restore(saved: WorkspaceRecovery) = OutfitWorkspace(saved.draft).apply {
            require(saved.draft.id == saved.baseline.id)
            baseline = saved.baseline
            select(saved.selectedId)
        }
    }
    var draft = initial; private set
    private var baseline = initial
    var selectedId: String? = null; private set
    private val undo = ArrayDeque<OutfitDraft>()
    private val redo = ArrayDeque<OutfitDraft>()
    private var gesture: OutfitDraft? = null
    val state get() = CanvasState(draft, selectedId, draft != baseline, undo.isNotEmpty(), redo.isNotEmpty())
    val recovery get() = WorkspaceRecovery(draft, baseline, selectedId)

    fun select(id: String?) { selectedId = id?.takeIf { target -> draft.items.any { it.id == target } } }
    private fun remember(before: OutfitDraft) {
        if (before == draft) return
        undo.addLast(before); if (undo.size > 50) undo.removeFirst(); redo.clear()
    }
    private fun change(operation: () -> Unit) {
        endGesture(); val before = draft; operation()
        draft = draft.copy(items = draft.items.mapIndexed { index, item -> item.copy(zIndex = index) })
        remember(before)
    }
    fun rename(value: String) { if (value.length <= 60) change { draft = draft.copy(name = value) } }
    fun add(garmentId: String) = change {
        if (draft.items.size < 30) {
            val id = UUID.randomUUID().toString(); val offset = draft.items.size % 3 * .12f
            draft = draft.copy(items = draft.items + OutfitItem(id, draft.id, garmentId, .38f + offset, .35f + offset, .42f, 0f, draft.items.size))
            selectedId = id
        }
    }
    fun beginGesture() { endGesture(); gesture = draft }
    fun endGesture() { gesture?.let { remember(it) }; gesture = null }
    fun transform(dx: Float, dy: Float, zoom: Float, angle: Float) {
        draft = draft.copy(items = draft.items.map { if (it.id == selectedId) it.transformed(dx, dy, zoom, angle) else it })
    }
    fun adjust(zoom: Float = 1f, angle: Float = 0f) = change { transform(0f, 0f, zoom, angle) }
    fun remove() = change { draft = draft.copy(items = draft.items.filterNot { it.id == selectedId }); selectedId = null }
    fun layer(top: Boolean) = change {
        val item = draft.items.find { it.id == selectedId } ?: return@change
        val rest = draft.items.filterNot { it.id == selectedId }
        draft = draft.copy(items = if (top) rest + item else listOf(item) + rest)
    }
    fun duplicate() = change {
        val item = draft.items.find { it.id == selectedId } ?: return@change
        if (draft.items.size < 30) {
            val copy = item.copy(id = UUID.randomUUID().toString(), x = (item.x + .04f).coerceAtMost(1f), y = (item.y + .04f).coerceAtMost(1f))
            draft = draft.copy(items = draft.items + copy); selectedId = copy.id
        }
    }
    fun replace(garmentId: String) = change { draft = draft.copy(items = draft.items.map { if (it.id == selectedId) it.copy(garmentId = garmentId, cropLeft=0f, cropTop=0f, cropRight=1f, cropBottom=1f) else it }) }
    fun crop(bounds: CropBounds) = change { draft = draft.copy(items = draft.items.map {
        if (it.id == selectedId) it.copy(cropLeft = bounds.left, cropTop = bounds.top, cropRight = bounds.right, cropBottom = bounds.bottom) else it }) }
    fun filter(value: String) { require(value in OutfitFilters); change { draft = draft.copy(items = draft.items.map { if (it.id == selectedId) it.copy(filter = value) else it }) } }
    fun standardLayout(ratios: Map<String, Float> = emptyMap()) = change {
        val count = draft.items.size
        val tallest = draft.items.maxOfOrNull { ratios[it.garmentId] ?: 1.5f } ?: 1.5f
        val columns = if (count <= 4) 2 else ceil(sqrt(count * .8f * tallest)).toInt().coerceIn(2,count)
        val rows = maxOf(1, (count + columns - 1) / columns)
        draft = draft.copy(items = draft.items.mapIndexed { index, item -> item.copy(
            x = (index % columns + .5f) / columns, y = (index / columns + .5f) / rows,
            width = minOf(.8f / columns, 1f / (rows * (ratios[item.garmentId] ?: 1.5f) *
                (item.cropBottom-item.cropTop)/(item.cropRight-item.cropLeft))).coerceIn(.08f,.9f), rotation = 0f) })
    }
    fun undo() { endGesture(); if (undo.isNotEmpty()) { redo.addLast(draft); draft = undo.removeLast(); select(selectedId) } }
    fun redo() { endGesture(); if (redo.isNotEmpty()) { undo.addLast(draft); draft = redo.removeLast(); select(selectedId) } }
    fun markSaved() { endGesture(); draft = draft.copy(name = draft.name.trim()); baseline = draft }
}
