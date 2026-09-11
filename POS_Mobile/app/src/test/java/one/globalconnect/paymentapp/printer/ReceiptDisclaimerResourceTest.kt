package one.globalconnect.paymentapp.printer

import java.io.File
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReceiptDisclaimerResourceTest {

    @Test
    fun `localized EMV signature disclaimers fit the selected printer line`() {
        val english = readResource("values/strings.xml", "receipt_no_signature_required_emv")
        val spanish = readResource("values-es/string.xml", "receipt_no_signature_required_emv")
        val cvmEnglish = readResource("values/strings.xml", "receipt_no_signature_required_cvm")
        val cvmSpanish = readResource("values-es/string.xml", "receipt_no_signature_required_cvm")
        val cdcvmEnglish = readResource("values/strings.xml", "receipt_no_signature_required_cdcvm")
        val cdcvmSpanish = readResource("values-es/string.xml", "receipt_no_signature_required_cdcvm")

        assertNotEquals(english, spanish)
        assertTrue("English disclaimer is ${english.length} characters", english.length <= 38)
        assertTrue("Spanish disclaimer is ${spanish.length} characters", spanish.length <= 38)
        assertNotEquals(cvmEnglish, cvmSpanish)
        assertTrue("English CVM disclaimer is ${cvmEnglish.length} characters", cvmEnglish.length <= 38)
        assertTrue("Spanish CVM disclaimer is ${cvmSpanish.length} characters", cvmSpanish.length <= 38)
        assertNotEquals(cdcvmEnglish, cdcvmSpanish)
        assertTrue("English CDCVM disclaimer is ${cdcvmEnglish.length} characters", cdcvmEnglish.length <= 38)
        assertTrue("Spanish CDCVM disclaimer is ${cdcvmSpanish.length} characters", cdcvmSpanish.length <= 38)
    }

    private fun readResource(relativePath: String, name: String): String {
        val workingDirectory = File(System.getProperty("user.dir") ?: ".")
        val sourceFile = sequenceOf(
            File(workingDirectory, "src/main/res/$relativePath"),
            File(workingDirectory, "app/src/main/res/$relativePath"),
        ).firstOrNull(File::isFile)
            ?: error("Unable to locate Android resource file $relativePath from $workingDirectory")
        val match = Regex("""<string\s+name="$name">(.*?)</string>""")
            .find(sourceFile.readText())
            ?: error("Unable to locate string resource $name in $sourceFile")
        return match.groupValues[1]
    }
}
