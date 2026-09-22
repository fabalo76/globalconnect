package one.globalconnect.pinpad.logging

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.LocalDate

class DailyLogStoreTest {
    @get:Rule val temp = TemporaryFolder()
    private val day = LocalDate.of(2026, 9, 13)

    @Test fun publicAppendPreservesEarlierEntriesAndRecreatesDeletedCopy() {
        val privateDir = temp.newFolder()
        val publicDir = temp.newFolder()
        val store = DailyLogStore(privateDir)
        store.append(day, "first\n")
        store.publish(publicDir)
        store.append(day, "second\n")
        store.publish(publicDir)
        val today = File(publicDir, "log_today.txt")
        assertEquals("first\nsecond\n", today.readText())
        today.delete()
        store.publish(publicDir)
        assertEquals("first\nsecond\n", today.readText())
        store.publish(publicDir)
        assertEquals("first\nsecond\n", today.readText())
    }

    @Test fun midnightKeepsTodayPlusNineArchivesAndLeavesUnrelatedFilesAlone() {
        val privateDir = temp.newFolder()
        val publicDir = temp.newFolder()
        File(publicDir, "operator-note.txt").writeText("keep")
        val store = DailyLogStore(privateDir)
        repeat(15) { offset ->
            store.append(day.plusDays(offset.toLong()), "day=$offset\n")
            store.publish(publicDir)
            assertTrue(publicDir.listFiles()!!.count { it.name.startsWith("log_") } <= 10)
        }
        assertEquals(10, publicDir.listFiles()!!.count { it.name.startsWith("log_") })
        assertEquals("day=14\n", File(publicDir, "log_today.txt").readText())
        assertFalse(File(publicDir, "log_$day.txt").exists())
        assertEquals("day=5\n", File(publicDir, "log_${day.plusDays(5)}.txt").readText())
        assertEquals("keep", File(publicDir, "operator-note.txt").readText())
    }

    @Test fun restartAndEqualLengthDayRolloverDoNotKeepStaleTodayContents() {
        val privateDir = temp.newFolder()
        val publicDir = temp.newFolder()
        DailyLogStore(privateDir).apply { append(day, "OLD\n"); publish(publicDir) }
        DailyLogStore(privateDir).apply { append(day.plusDays(1), "NEW\n"); publish(publicDir) }
        assertEquals("NEW\n", File(publicDir, "log_today.txt").readText())
        assertEquals("OLD\n", File(publicDir, "log_$day.txt").readText())
    }

    @Test fun sizeLimitRetainsRecentLinesAndPublicMirrorMatchesAfterTruncation() {
        val privateDir = temp.newFolder()
        val publicDir = temp.newFolder()
        val store = DailyLogStore(privateDir, maxBytes = 500)
        repeat(100) { index ->
            store.append(day, "line $index: command=M23 bytes=1390\n")
            store.publish(publicDir)
            assertTrue(File(publicDir, "log_today.txt").length() <= 500)
        }
        val contents = File(publicDir, "log_today.txt").readText()
        assertTrue(contents.contains("line 99:"))
        assertTrue(contents.contains("Earlier entries discarded"))
        assertEquals(File(privateDir, "log_today.txt").readText(), contents)
    }

    @Test fun publicStorageFailureKeepsInternalLogForLaterPermissionGrant() {
        val privateDir = temp.newFolder()
        val unavailable = temp.newFile()
        val store = DailyLogStore(privateDir)
        store.append(day, "transport error\n")
        assertThrows(IllegalStateException::class.java) { store.publish(unavailable) }
        assertEquals("transport error\n", File(privateDir, "log_today.txt").readText())
        val available = temp.newFolder()
        store.publish(available)
        assertEquals("transport error\n", File(available, "log_today.txt").readText())
    }
}
