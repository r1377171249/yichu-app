package com.xiaoyichu.app.data

import org.junit.Assert.*
import org.junit.Test

class OutfitWorkspaceTest {
    @Test fun standardLayoutFitsThirtyTallGarmentsWithinCells() {
        val editor=OutfitWorkspace();repeat(30){editor.add("g$it")}
        editor.standardLayout((0 until 30).associate { "g$it" to 3f })
        editor.draft.items.forEach { item ->
            assertTrue(item.valid());assertTrue(item.x-item.width/2>=0)
            assertTrue(item.x+item.width/2<=1)
            assertTrue(item.y-item.width*.8f*3/2>=0)
            assertTrue(item.y+item.width*.8f*3/2<=1)
        }
    }
    @Test fun offCenterPinchKeepsFingerAnchorAndRespectsZoomLimit() {
        val item = OutfitItem("i", "o", "g", .5f, .5f, .4f, 0f, 0)
        val delta = item.anchoredDelta(400f, 500f, 10f, 20f, 2f, 90f, 1000f, 1250f)
        val result = item.transformed(delta.first, delta.second, 2f, 90f)
        assertEquals(.8f, result.width, .00001f)
        assertEquals(.16f, result.x, .00001f)
        assertEquals(.576f, result.y, .00001f)
        val limited = item.copy(width = .9f)
        assertEquals(0f, limited.anchoredDelta(100f, 100f, 0f, 0f, 2f, 0f, 1000f, 1250f).first, .00001f)
    }
    @Test fun cropAndFilterAreInstanceSpecificUndoableAndValidated() {
        val editor = OutfitWorkspace(); editor.add("g"); editor.duplicate()
        val before = editor.draft
        editor.crop(CropBounds(.1f,.2f,.8f,.9f)); editor.filter("mono")
        assertEquals(CropBounds.Full,editor.draft.items.first().crop)
        assertEquals("original",editor.draft.items.first().filter)
        assertEquals("mono",editor.draft.items.last().filter)
        assertTrue(editor.draft.items.all { it.valid() })
        editor.undo(); editor.undo(); assertEquals(before,editor.draft)
        assertFalse(before.items.first().copy(cropRight=0f).valid())
        assertFalse(before.items.first().copy(filter="unknown").valid())
    }
    @Test fun standardLayoutCanBeUndoneWithoutChangingInstanceIdentity() {
        val editor=OutfitWorkspace(); repeat(7){editor.add("g$it")}; val before=editor.draft
        editor.standardLayout(); assertEquals(before.items.map{it.id},editor.draft.items.map{it.id})
        assertTrue(editor.draft.items.all{it.valid()}); editor.undo(); assertEquals(before,editor.draft)
    }
    @Test fun invalidTransformsDoNotPoisonSavedCoordinates() {
        val item = OutfitItem("i", "o", "g", .5f, .5f, .4f, 0f, 0)
        assertEquals(item, item.transformed(Float.NaN, 0f, 1f, 0f))
        assertEquals(item, item.transformed(0f, 0f, 0f, 0f))
        val changed = item.transformed(20f, -20f, 50f, 555f)
        assertTrue(changed.valid()); assertEquals(1f, changed.x); assertEquals(0f, changed.y)
        assertEquals(.9f, changed.width); assertEquals(-165f, changed.rotation)
    }
    @Test fun inverseRotationHitTestMatchesRotatedRectangle() {
        val item = OutfitItem("i", "o", "g", .5f, .5f, .4f, 90f, 0)
        assertTrue(item.contains(500f, 625f, 1000f, 1250f, .5f))
        assertTrue(item.contains(500f, 800f, 1000f, 1250f, .5f))
        assertFalse(item.contains(675f, 625f, 1000f, 1250f, .5f))
    }
    @Test fun wholeGestureIsOneUndoAndRedoStep() {
        val editor = OutfitWorkspace(); editor.add("g"); val before = editor.draft
        editor.beginGesture()
        repeat(20) { editor.transform(.001f, -.001f, 1.01f, 1f) }
        editor.endGesture(); val after = editor.draft
        assertNotEquals(before, after); editor.undo(); assertEquals(before, editor.draft)
        editor.redo(); assertEquals(after, editor.draft)
    }
    @Test fun duplicateAndReplaceKeepInstanceIdentityAndLayerOrder() {
        val editor = OutfitWorkspace(); editor.add("g1"); val first = editor.draft.items.first().id
        editor.duplicate(); val second = editor.draft.items.last().id; assertNotEquals(first, second)
        editor.replace("g2"); assertEquals(second, editor.draft.items.last().id)
        editor.layer(false); assertEquals(second, editor.draft.items.first().id)
        assertEquals(listOf(0, 1), editor.draft.items.map { it.zIndex })
        editor.remove(); assertEquals(first, editor.draft.items.single().id)
        editor.undo(); assertEquals(2, editor.draft.items.size)
    }
    @Test fun savedSnapshotAndUndoTrackDirtyState() {
        val editor = OutfitWorkspace(); editor.rename("Spring"); editor.add("g"); editor.markSaved()
        assertFalse(editor.state.dirty); editor.adjust(angle = 15f); assertTrue(editor.state.dirty)
        editor.undo(); assertFalse(editor.state.dirty); editor.redo(); assertTrue(editor.state.dirty)
        editor.undo(); editor.add("another"); assertFalse(editor.state.canRedo)
    }
    @Test fun recoveryKeepsGeometrySelectionAndSavedBaselineWithoutUndoHistory() {
        val editor=OutfitWorkspace();editor.add("g");editor.rename("saved");editor.markSaved()
        val baseline=editor.draft
        editor.adjust(zoom=1.2f,angle=33f);editor.crop(CropBounds(.1f,.2f,.9f,.8f));editor.filter("warm")
        val restored=OutfitWorkspace.restore(editor.recovery)
        assertEquals(editor.draft,restored.draft);assertEquals(editor.selectedId,restored.selectedId)
        assertTrue(restored.state.dirty);assertFalse(restored.state.canUndo);assertFalse(restored.state.canRedo)
        assertEquals(baseline,restored.recovery.baseline)
        restored.markSaved();assertFalse(OutfitWorkspace.restore(restored.recovery).state.dirty)
    }
    @Test fun emptyDraftAndRemovedSelectionRestoreSafely() {
        val initial=OutfitDraft("empty")
        val restored=OutfitWorkspace.restore(WorkspaceRecovery(initial,initial,"missing"))
        assertNull(restored.selectedId);assertFalse(restored.state.dirty)
    }
    @Test fun noMovementDoesNotCreateGestureHistoryAndLimitIsEnforced() {
        val editor = OutfitWorkspace(); repeat(31) { editor.add("g") }; assertEquals(30, editor.draft.items.size)
        val before = editor.draft; editor.beginGesture(); editor.endGesture(); editor.undo()
        assertEquals(29, editor.draft.items.size); editor.redo(); assertEquals(before, editor.draft)
    }
}
