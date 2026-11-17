package com.xiaoyichu.app.data

import org.junit.Assert.*
import org.junit.Test

class BackupRulesTest {
    @Test fun pathsCannotEscapeOrReachPrivateDatabase() {
        listOf("../wardrobe.db","/data/wardrobe.db","garments/../a.png","garments/..\\a.png",
            "databases/wardrobe.db","garments/a/b.png","cutouts/a.png/","garments/a:stream.png","garments/%2e%2e.png").forEach { assertFalse(it,BackupRules.imagePath(it)) }
        assertTrue(BackupRules.imagePath("garments/1234-ab.original"))
        assertTrue(BackupRules.imagePath("cutouts/1234-ab.png"))
    }
    @Test fun archiveBudgetsAndHashesRejectUnboundedOrInvalidData() {
        assertFalse(BackupRules.validSize(-1));assertFalse(BackupRules.validSize(0))
        assertFalse(BackupRules.validSize(BackupRules.MaxImageBytes+1));assertTrue(BackupRules.validSize(BackupRules.MaxImageBytes))
        assertTrue(BackupRules.validHash("a".repeat(64)));assertFalse(BackupRules.validHash("0".repeat(63)));assertFalse(BackupRules.validHash("z".repeat(64)))
        assertFalse(BackupRules.validId("x\n"));assertFalse(BackupRules.validId(""))
    }
}
