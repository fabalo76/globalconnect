package one.globalconnect.xtmsagent.diagnostics

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.security.MessageDigest

class PssPackageExporterTest {
    @Test
    fun copyingMultipleBuffersPreservesApkBytesAndReportsTheirHash() {
        val bytes = ByteArray(200_123) { (it * 37).toByte() }
        val output = ByteArrayOutputStream()
        val (size, hash) = PssPackageExporter.copyAndHash(bytes.inputStream(), output)
        assertArrayEquals(bytes, output.toByteArray())
        assertEquals(bytes.size.toLong(), size)
        assertEquals(MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02X".format(it) }, hash)
    }

    @Test
    fun copyingKnownContentProducesStandardSha256() {
        val output = ByteArrayOutputStream()
        val (size, hash) = PssPackageExporter.copyAndHash("abc".byteInputStream(), output)
        assertEquals(3L, size)
        assertEquals("BA7816BF8F01CFEA414140DE5DAE2223B00361A396177A9CB410FF61F20015AD", hash)
    }
}
