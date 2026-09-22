package one.globalconnect.paymentapp.transaction

import kotlinx.coroutines.runBlocking
import com.uic.pos.iso8583.IsoLengthType
import com.uic.pos.iso8583.config.IsoConfigParser
import one.globalconnect.paymentapp.dao.TransactionRepository
import one.globalconnect.paymentapp.uicpos.pos.host.HostAddress
import one.globalconnect.paymentapp.uicpos.pos.host.HostEndpoint
import one.globalconnect.paymentapp.uicpos.pos.host.HostSettings
import one.globalconnect.paymentapp.uicpos.pos.host.HostTransactionResult
import one.globalconnect.paymentapp.uicpos.pos.host.LengthConfig
import one.globalconnect.tms.paymentapp.TMSDATA
import one.globalconnect.tms.paymentapp.TMS_Acquirer
import one.globalconnect.tms.paymentapp.TMS_HostConnectionInfo
import one.globalconnect.tms.paymentapp.TMS_Terminal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.lang.reflect.Proxy
import java.time.LocalDateTime

class PendingReversalProcessorTest {

    @Test
    fun `empty queue completes without opening a host connection`() = runBlocking {
        var hostCalled = false
        val processor = PendingReversalProcessor(
            transactionRepository = repositoryWith(emptyList()),
            hostExecutor = {
                hostCalled = true
                error("Host must not be called for an empty queue")
            },
        )

        val result = processor.processAll(TMSDATA())

        assertEquals(0, result.found)
        assertEquals(0, result.sent)
        assertEquals(0, result.remaining)
        assertTrue(result.allSent)
        assertTrue(!hostCalled)
    }

    @Test
    fun `queued reversal remains pending when terminal configuration is unavailable`() = runBlocking {
        val reversal = pendingReversal()
        val processor = PendingReversalProcessor(repositoryWith(listOf(reversal)))

        val result = processor.processAll(TMSDATA())

        assertEquals(1, result.found)
        assertEquals(0, result.sent)
        assertEquals(1, result.remaining)
        assertEquals(setOf("acquirer-1"), result.failedAcquirerIds)
    }

    @Test
    fun `approved reversal is sent as 0400 and removed from queue`() = runBlocking {
        val planField = one.globalconnect.paymentapp.uicpos.pos.host.protocol.PrivateUseData63.encode(listOf(
            one.globalconnect.paymentapp.uicpos.pos.host.protocol.PrivateUseData63.Tag("45", "06INT10000000000000000S")))!!
        val reversal = pendingReversal().copy(fieldValues = mapOf(11 to "000123", 37 to "654321", 63 to planField))
        var deleted: PendingReversal? = null
        val repository = repositoryWith(listOf(reversal), onDelete = { deleted = it })
        val factory = IsoConfigParser.fromFile(isoConfigFile())
        val response = factory.newMessage().apply {
            setMessageType("0410")
            setFieldValue(39, "00")
        }
        var requestMessageType: String? = null
        var requestProcessingCode: String? = null
        var requestPlanField: String? = null
        val processor = PendingReversalProcessor(
            transactionRepository = repository,
            hostExecutor = { request ->
                requestMessageType = request.message.messageType
                requestProcessingCode = request.message.getFieldValue(3)
                requestPlanField = request.message.getFieldValue(63)
                HostTransactionResult(
                    isoMessage = response,
                    rawRequest = byteArrayOf(),
                    rawResponse = byteArrayOf(),
                    endpoint = HostEndpoint(HostAddress("127.0.0.1", 9000), HostEndpoint.EndpointType.PRIMARY),
                    attempt = 1,
                    retry = 1,
                    roundTripMs = 1,
                    timestamp = LocalDateTime.parse("2026-09-04T10:01:00"),
                )
            },
        )

        val result = processor.processForAcquirer(
            acquirer = TMS_Acquirer(acquirer_id = "acquirer-1"),
            ipProfile = TMS_HostConnectionInfo(config_id = "host-1"),
            terminal = TMS_Terminal(),
            hostSettings = hostSettings(),
            isoFactory = factory,
        )

        assertEquals("0400", requestMessageType)
        assertEquals("000000", requestProcessingCode)
        assertEquals(planField, requestPlanField)
        assertEquals(reversal, deleted)
        assertEquals(1, result.sent)
        assertEquals(0, result.remaining)
    }

    @Test
    fun `declined reversal records response and remains queued`() = runBlocking {
        val reversal = pendingReversal()
        var updated: PendingReversal? = null
        val repository = repositoryWith(listOf(reversal), onUpdate = { updated = it })
        val factory = IsoConfigParser.fromFile(isoConfigFile())
        val response = factory.newMessage().apply {
            setMessageType("0410")
            setFieldValue(39, "05")
        }
        val processor = PendingReversalProcessor(
            transactionRepository = repository,
            hostExecutor = {
                HostTransactionResult(
                    isoMessage = response,
                    rawRequest = byteArrayOf(),
                    rawResponse = byteArrayOf(),
                    endpoint = HostEndpoint(HostAddress("127.0.0.1", 9000), HostEndpoint.EndpointType.PRIMARY),
                    attempt = 1,
                    retry = 1,
                    roundTripMs = 1,
                    timestamp = LocalDateTime.parse("2026-09-04T10:01:00"),
                )
            },
            now = { LocalDateTime.parse("2026-09-04T10:01:00") },
        )

        val result = processor.processForAcquirer(
            acquirer = TMS_Acquirer(acquirer_id = "acquirer-1"),
            ipProfile = TMS_HostConnectionInfo(config_id = "host-1"),
            terminal = TMS_Terminal(),
            hostSettings = hostSettings(),
            isoFactory = factory,
        )

        assertEquals(1, updated?.attempts)
        assertEquals("05", updated?.lastResponseCode)
        assertEquals(0, result.sent)
        assertEquals(1, result.remaining)
    }

    @Suppress("UNCHECKED_CAST")
    private fun repositoryWith(
        pending: List<PendingReversal>,
        onDelete: (PendingReversal) -> Unit = {},
        onUpdate: (PendingReversal) -> Unit = {},
    ): TransactionRepository {
        return Proxy.newProxyInstance(
            TransactionRepository::class.java.classLoader,
            arrayOf(TransactionRepository::class.java),
        ) { proxy, method, args ->
            when (method.name) {
                "getAllPendingReversals" -> pending
                "getPendingReversals" -> pending
                "deleteReversal" -> {
                    onDelete(args?.first() as PendingReversal)
                    Unit
                }
                "updateReversal" -> {
                    onUpdate(args?.first() as PendingReversal)
                    Unit
                }
                "toString" -> "PendingReversalProcessorTestRepository"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                else -> error("Unexpected repository call: ${method.name}")
            }
        } as TransactionRepository
    }

    private fun pendingReversal(): PendingReversal = PendingReversal(
        acquirerId = "acquirer-1",
        transactionType = TransactionType.SALE.toTransactionString(),
        stan = "000123",
        originalMessageType = "0200",
        processingCode = "000000",
        header = null,
        fieldValues = emptyMap(),
        createdAt = "2026-09-04T10:00:00",
    )

    private fun hostSettings(): HostSettings = HostSettings(
        isTls = false,
        primary = HostAddress("127.0.0.1", 9000),
        secondary = null,
        connectTimeoutSeconds = 10,
        readTimeoutSeconds = 60,
        attempts = 1,
        primaryRetries = 1,
        secondaryRetries = 1,
        length = LengthConfig(2, IsoLengthType.HEX),
    )

    private fun isoConfigFile(): File {
        val appRelative = File("src/main/assets/iso8583_ISSWITCH_config.xml")
        if (appRelative.exists()) return appRelative
        return File("app/src/main/assets/iso8583_ISSWITCH_config.xml")
    }
}
