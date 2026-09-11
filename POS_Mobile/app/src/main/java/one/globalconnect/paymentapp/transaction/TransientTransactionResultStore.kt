package one.globalconnect.paymentapp.transaction

import java.util.UUID

/**
 * Holds approved non-batch transactions only long enough for the result screen to consume them.
 *
 * PIN maintenance results must remain printable without becoming active settlement records. The
 * store is intentionally process-local: after process death the operator can repeat the
 * non-financial operation without recovering a batch record.
 */
internal object TransientTransactionResultStore {
    private const val TOKEN_PREFIX = "transient:"
    private const val MAX_PENDING_RESULTS = 8
    private val pendingResults = linkedMapOf<String, Transaction>()

    /**
     * Stores [transaction] and returns an opaque navigation token.
     *
     * The oldest unconsumed entry is discarded when the bounded store is full.
     */
    fun store(transaction: Transaction): String = synchronized(pendingResults) {
        while (pendingResults.size >= MAX_PENDING_RESULTS) {
            pendingResults.remove(pendingResults.keys.first())
        }
        val token = TOKEN_PREFIX + UUID.randomUUID().toString()
        pendingResults[token] = transaction
        token
    }

    /** Returns and removes the transient result identified by [token], if it is still available. */
    fun consume(token: String): Transaction? = synchronized(pendingResults) {
        pendingResults.remove(token)
    }

    /** Clears pending results so isolated tests cannot leak state into each other. */
    fun clear() = synchronized(pendingResults) {
        pendingResults.clear()
    }
}
