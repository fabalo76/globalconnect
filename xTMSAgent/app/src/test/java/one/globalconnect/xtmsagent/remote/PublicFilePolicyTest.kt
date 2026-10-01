package one.globalconnect.xtmsagent.remote

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.Assume.assumeNoException

class PublicFilePolicyTest {
    @Test fun restrictsPublicStorageAndPrivateApplicationFolders() {
        val directory = Files.createTempDirectory("public-file-policy").toFile()
        try {
            val policy = PublicFilePolicy(directory)
            assertEquals(directory.canonicalFile, policy.resolve("/sdcard"))
            assertEquals(File(directory, "Download/receipt.txt"), policy.resolve("/sdcard/Download/receipt.txt"))
            assertEquals(File(directory, "Android/media/shared"), policy.resolve("/sdcard/Android/media/shared"))
            listOf("/data/data/app", "/sdcard-other/a", "/sdcard/../../private",
                "/sdcard/Android/data/app/file", "/sdcard/Android/obb/app/file", "/sdcard/a\u0000b").forEach {
                assertThrows(IllegalArgumentException::class.java) { policy.resolve(it) }
            }
        } finally { directory.deleteRecursively() }
    }

    @Test fun canonicalPathsRejectLinksToPrivateStorage() {
        val directory = Files.createTempDirectory("public-file-links").toFile()
        val outside = Files.createTempDirectory("private-file-links").toFile()
        try {
            val policy = PublicFilePolicy(directory)
            try {
                Files.createSymbolicLink(File(directory, "escape").toPath(), outside.toPath())
            } catch (e: java.nio.file.FileSystemException) {
                assumeNoException("Host does not permit creating symbolic links", e)
            }
            assertThrows(IllegalArgumentException::class.java) { policy.resolve("/sdcard/escape/file") }
            val privateDirectory = File(directory, "Android/data").apply { mkdirs() }
            Files.createSymbolicLink(File(directory, "alias").toPath(), privateDirectory.toPath())
            assertThrows(IllegalArgumentException::class.java) { policy.resolve("/sdcard/alias/file") }
        } finally { directory.deleteRecursively(); outside.deleteRecursively() }
    }
}
