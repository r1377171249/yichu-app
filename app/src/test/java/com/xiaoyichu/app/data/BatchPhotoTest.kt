package com.xiaoyichu.app.data

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class BatchPhotoTest {
    private val photo=DraftPhoto("photo",File("original"),File("thumb"),cutout=File("cutout"),useCutout=true)
    @Test fun explicitOriginalChoiceWinsEvenIfRetryGeneratedNewCutout() {
        val item=BatchPhoto(photo,"衣物",originalAccepted=true)
        assertTrue(item.ready);assertFalse(item.photoForSave().useCutout)
        assertTrue(photo.useCutout)
    }
    @Test fun cutoutChoiceAndReadinessRequireAnActualResultOrOriginalConsent() {
        assertTrue(BatchPhoto(photo,"衣物").photoForSave().useCutout)
        val failed=photo.copy(cutout=null,cutoutError=true,useCutout=false)
        assertFalse(BatchPhoto(failed,"衣物").ready)
        assertTrue(BatchPhoto(failed,"衣物",originalAccepted=true).ready)
        assertFalse(BatchPhoto(photo,"").ready)
    }
}
