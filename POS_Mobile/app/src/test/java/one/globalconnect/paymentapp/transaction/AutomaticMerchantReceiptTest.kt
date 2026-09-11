package one.globalconnect.paymentapp.transaction

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Test

class AutomaticMerchantReceiptTest {
    @Test
    fun `disabled receipt flag skips printing`() = runBlocking {
        val events = mutableListOf<String>()
        AutomaticMerchantReceipt(this).submit(false) { events += "print" }
        events += "remove card"
        assertEquals(listOf("remove card"), events)
    }

    @Test
    fun `card removal waits for submission and repeated effects share one copy`() = runBlocking {
        val events = mutableListOf("sensory complete")
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val receipt = AutomaticMerchantReceipt(this)
        val first = launch {
            receipt.submit(true) {
                events += "submit merchant"
                started.complete(Unit)
                release.await()
                events += "submitted"
            }
            events += "remove card"
        }
        started.await()
        val repeated = launch { receipt.submit(true) { events += "duplicate" } }
        yield()
        assertEquals(listOf("sensory complete", "submit merchant"), events)
        release.complete(Unit)
        first.join()
        repeated.join()
        receipt.submit(true) { events += "duplicate" }
        assertEquals(listOf("sensory complete", "submit merchant", "submitted", "remove card"), events)
    }

    @Test
    fun `UI cancellation does not cancel or duplicate printer submission`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val receipt = AutomaticMerchantReceipt(this)
        var copies = 0
        val ui = launch {
            receipt.submit(true) { copies++; started.complete(Unit); release.await() }
        }
        started.await()
        ui.cancel()
        ui.join()
        release.complete(Unit)
        receipt.submit(true) { copies++ }
        assertEquals(1, copies)
    }
}
