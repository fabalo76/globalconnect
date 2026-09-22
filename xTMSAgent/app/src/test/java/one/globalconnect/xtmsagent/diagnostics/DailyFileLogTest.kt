package one.globalconnect.xtmsagent.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DailyFileLogTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun backlogAndSubsequentEventsAreCopiedWithoutDuplicates() {
        val privateDir = temp.newFolder("private")
        val publicDir = temp.newFolder("public")
        val source = File(privateDir, "log_2026-09-12.txt")
        source.writeText("before permission\n")
        DailyFileLog.publish(privateDir, publicDir, "2026-09-12")
        source.appendText("after permission\n")
        DailyFileLog.publish(privateDir, publicDir, "2026-09-12")
        DailyFileLog.publish(privateDir, publicDir, "2026-09-12")
        assertEquals(source.readText(), File(publicDir, "log_today.txt").readText())
        assertEquals(source.readText(), File(publicDir, source.name).readText())
    }

    @Test fun midnightReplacesTodayAndKeepsDatedHistory() {
        val privateDir = temp.newFolder("private")
        val publicDir = temp.newFolder("public")
        File(privateDir, "log_2026-09-12.txt").writeText("yesterday\n")
        DailyFileLog.publish(privateDir, publicDir, "2026-09-12")
        File(privateDir, "log_2026-09-13.txt").writeText("today\n")
        DailyFileLog.publish(privateDir, publicDir, "2026-09-13")
        assertEquals("today\n", File(publicDir, "log_today.txt").readText())
        assertEquals("yesterday\n", File(publicDir, "log_2026-09-12.txt").readText())
    }
}
