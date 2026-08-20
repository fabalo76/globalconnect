package one.globalconnect.paymentapp

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class ParameterRequestGuardTest {

    @Test
    fun `blocks before reading the batch when an operation is active`() = runBlocking {
        var reads = 0
        val result = checkParameterRequestReadiness(
            operationInProgress = { true },
            transactionCount = { reads++; 0 },
        )

        assertEquals(ParameterRequestReadiness.OperationInProgress, result)
        assertEquals(0, reads)
    }

    @Test
    fun `blocks when either batch read finds a live transaction`() = runBlocking {
        var reads = 0
        val result = checkParameterRequestReadiness(
            operationInProgress = { false },
            transactionCount = { if (++reads == 1) 0 else 1 },
        )

        assertEquals(ParameterRequestReadiness.LiveTransactions(1), result)
        assertEquals(2, reads)
    }

    @Test
    fun `blocks when an operation begins during verification`() = runBlocking {
        var operationChecks = 0
        val result = checkParameterRequestReadiness(
            operationInProgress = { ++operationChecks >= 2 },
            transactionCount = { 0 },
        )

        assertEquals(ParameterRequestReadiness.OperationInProgress, result)
    }

    @Test
    fun `fails closed when the batch cannot be read`() = runBlocking {
        val result = checkParameterRequestReadiness(
            operationInProgress = { false },
            transactionCount = { error("database unavailable") },
        )

        assertEquals(ParameterRequestReadiness.UnableToVerify, result)
    }

    @Test
    fun `allows request only after two empty reads while idle`() = runBlocking {
        var reads = 0
        val result = checkParameterRequestReadiness(
            operationInProgress = { false },
            transactionCount = { reads++; 0 },
        )

        assertEquals(ParameterRequestReadiness.Ready, result)
        assertEquals(2, reads)
    }
}
