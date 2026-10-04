package one.globalconnect.xtmsagent.remote

import java.net.URL
import org.junit.Assert.*
import org.junit.Test

class RemoteTransferUrlPolicyTest {
    @Test fun standaloneAcceptsOnlySelectedSecureObjectEndpoint() {
        assertTrue(allowed("https://demo.globalconnect.one/standalone/objects?signature=test", true))
        for (url in listOf("http://demo.globalconnect.one/standalone/objects", "https://demo.globalconnect.one:8443/standalone/objects",
            "https://demo.globalconnect.one.attacker.test/standalone/objects", "https://attacker.test/standalone/objects",
            "https://demo.globalconnect.one/other", "https://user@demo.globalconnect.one/standalone/objects",
            "https://demo.globalconnect.one/standalone/objects#fragment", "https://bucket.s3.amazonaws.com/object")) assertFalse(url, allowed(url, true))
    }
    @Test fun awsPreservesSignedAmazonObjectTransfers() {
        assertTrue(allowed("https://bucket.s3.us-east-1.amazonaws.com/object?signature=test", false))
        assertFalse(allowed("https://demo.globalconnect.one/standalone/objects", false))
        assertFalse(allowed("https://bucket.s3.amazonaws.com.attacker.test/object", false))
    }
    private fun allowed(value: String, standalone: Boolean) = RemoteTransferUrlPolicy.isAllowed(URL(value), "demo.globalconnect.one", standalone)
}
