package one.globalconnect.paymentapp.transaction

import androidx.annotation.MainThread
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async

/** Keeps receipt submission alive across UI effect restarts and prevents duplicate copies. */
internal class AutomaticMerchantReceipt(private val scope: CoroutineScope) {
    private var submission: Deferred<Unit>? = null

    @MainThread
    suspend fun submit(enabled: Boolean, printReceipt: suspend () -> Unit) {
        if (!enabled) return
        val task = submission ?: scope.async(start = CoroutineStart.LAZY) { printReceipt() }
            .also { submission = it; it.start() }
        task.await()
    }
}
