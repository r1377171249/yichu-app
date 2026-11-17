package com.xiaoyichu.app.data

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DraftCleanupTest {
    @get:Rule val temporary = TemporaryFolder()
    private val id = "12345678-1234-1234-1234-123456789abc"
    private val other = "abcdef12-1234-1234-1234-123456789abc"
    private val attempt = "99999999-1234-1234-1234-123456789abc"
    private val now = 2_000_000_000_000L
    private val old = now - DraftCleanup.RetentionMillis - 1000
    private fun file(folder:File, name:String, time:Long=old):File = File(folder,name).apply {
        writeBytes(byteArrayOf(1,2,3)); assertTrue(setLastModified(time))
    }

    @Test fun expiresOnlyKnownOldFilesAndNeverSavedSiblingsOrDirectories() {
        val folder=temporary.newFolder("imports");val saved=temporary.newFolder("garments")
        file(folder,"$id.original");file(folder,"$id-$attempt-repaired-thumb.png")
        val unknown=file(folder,"$id-unrecognized.png");val stored=file(saved,"$id.original")
        val directory=File(folder,"$other.png").apply{mkdir()};val nested=file(directory,"$other.original")
        val result=DraftCleanup.prune(folder,null,now)
        assertEquals(DraftCleanupResult(2,6),result)
        assertTrue(unknown.exists());assertTrue(stored.exists());assertTrue(nested.exists())
    }
    @Test fun protectsWholeActiveGroupIncludingOldAlternateProcessingFiles() {
        val folder=temporary.newFolder();val raw=file(folder,"$id.original")
        val oldAlternate=file(folder,"$id-$attempt-cutout.png")
        val abandoned=file(folder,"$other.original")
        assertEquals(1,DraftCleanup.prune(folder,id,now).deletedFiles)
        assertTrue(raw.exists());assertTrue(oldAlternate.exists());assertFalse(abandoned.exists())
    }
    @Test fun recentMemberAndBoundaryProtectEntireGroupAndFutureClockIsSafe() {
        val folder=temporary.newFolder();val raw=file(folder,"$id.original")
        file(folder,"$id-$attempt-crop.png",now-DraftCleanup.RetentionMillis)
        val future=file(folder,"$other.original",now+1000)
        assertEquals(0,DraftCleanup.prune(folder,null,now).deletedFiles)
        assertTrue(raw.exists());assertTrue(future.exists())
        assertEquals(0,DraftCleanup.prune(folder,null,1000).deletedFiles)
    }
    @Test fun filenamesAreStrictAndEveryGeneratedVariantHasTheSameOwner() {
        for(suffix in listOf("cutout","thumb","crop","crop-thumb","repaired","repaired-thumb"))
            assertEquals(id,DraftCleanup.owner("$id-$attempt-$suffix.png"))
        assertEquals(id,DraftCleanup.owner("$id.original"));assertEquals(id,DraftCleanup.owner("$id.png"))
        for(name in listOf("../$id.original","$id.original.bak","$id-$attempt-other.png","abc.original"))
            assertNull(DraftCleanup.owner(name))
    }
}
