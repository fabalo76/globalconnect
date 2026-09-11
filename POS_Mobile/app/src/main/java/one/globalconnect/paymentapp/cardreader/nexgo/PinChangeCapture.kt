package one.globalconnect.paymentapp.cardreader.nexgo

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Identifies how the secure PIN-entry activity will use the captured value. */
enum class SecurePinEntryPurpose {
    EMV_CVM,
    NEW_PIN,
    CONFIRM_NEW_PIN,
}

/** Describes the terminal result of one secure PIN-entry request. */
enum class SecurePinEntryStatus {
    ENTERED,
    BYPASSED,
    CANCELED,
    TIMED_OUT,
    ERROR,
}

/** Carries only encrypted output from one secure PIN-entry request. */
data class SecurePinEntryResult(
    val status: SecurePinEntryStatus,
    val pinBlock: String = "",
    val ksn: String = "",
    val errorMessage: String = "",
)

/** Holds the opaque identifier and completion handle for an activity PIN request. */
internal data class SecurePinEntryTicket(
    val requestId: String,
    val result: Deferred<SecurePinEntryResult>,
)

/** Bridges a PIN-entry activity result back to the suspended transaction flow. */
internal object SecurePinEntryResultBroker {
    private val pending = ConcurrentHashMap<String, CompletableDeferred<SecurePinEntryResult>>()

    /** Opens a result channel for a new secure PIN-entry activity. */
    fun open(): SecurePinEntryTicket {
        val requestId = UUID.randomUUID().toString()
        val deferred = CompletableDeferred<SecurePinEntryResult>()
        pending[requestId] = deferred
        return SecurePinEntryTicket(requestId, deferred)
    }

    /** Completes and removes the result channel identified by [requestId]. */
    fun complete(requestId: String, result: SecurePinEntryResult): Boolean {
        if (requestId.isBlank()) return false
        return pending.remove(requestId)?.complete(result) == true
    }

    /** Cancels and removes an abandoned result channel identified by [requestId]. */
    fun cancel(requestId: String) {
        if (requestId.isBlank()) return
        pending.remove(requestId)?.cancel()
    }
}

/** Identifies why a two-entry new-PIN capture could not be completed. */
internal enum class PinChangeCaptureFailure {
    INVALID_CONFIGURATION,
    FIRST_ENTRY_BYPASSED,
    FIRST_ENTRY_CANCELED,
    FIRST_ENTRY_TIMED_OUT,
    FIRST_ENTRY_FAILED,
    CONFIRMATION_BYPASSED,
    CONFIRMATION_CANCELED,
    CONFIRMATION_TIMED_OUT,
    CONFIRMATION_FAILED,
    PIN_MISMATCH,
    KSN_CHANGED,
    KSN_INCREMENT_FAILED,
}

/** Represents either confirmed encrypted PIN data or a classified capture failure. */
internal sealed interface PinChangeCaptureResult {
    data class Success(
        val pinBlock: String,
        val ksn: String,
        val scheme: OnlinePinScheme,
        val keyIndex: Int,
        val compatibleAcquirerIds: Set<String>,
    ) : PinChangeCaptureResult

    data class Failure(
        val reason: PinChangeCaptureFailure,
        val detail: String = "",
    ) : PinChangeCaptureResult
}

/** Supplies secure hardware operations to [PinChangeCaptureOrchestrator]. */
interface PinChangeCaptureGateway {
    /** Returns the current DUKPT KSN for [keyIndex], or null when unavailable. */
    fun currentDukptKsn(keyIndex: Int): String?

    /** Persists a reservation before the DUKPT transaction key can be used. */
    fun reserveDukptKsn(keyIndex: Int, ksn: String): Boolean

    /** Closes a reservation, optionally advancing the DUKPT KSN before clearing it. */
    fun closeDukptKsnReservation(keyIndex: Int, ksn: String, advance: Boolean): Boolean

    /** Displays secure PIN entry and returns only the encrypted result. */
    suspend fun captureSecurePin(
        purpose: SecurePinEntryPurpose,
        pan: String,
        scheme: OnlinePinScheme,
        keyIndex: Int,
        compatibleAcquirerIds: Set<String>,
    ): SecurePinEntryResult
}

/** Coordinates new-PIN entry and confirmation without exposing clear PIN digits. */
internal class PinChangeCaptureOrchestrator(
    private val gateway: PinChangeCaptureGateway,
) {
    private val captureMutex = Mutex()

    /**
     * Captures and confirms a new PIN using the key profile owned by the selected acquirer.
     * A DUKPT KSN is deliberately held for both entries and consumed exactly once afterward.
     */
    suspend fun capture(
        pan: String,
        scheme: OnlinePinScheme,
        keyIndex: Int,
        compatibleAcquirerIds: Set<String>,
    ): PinChangeCaptureResult = captureMutex.withLock {
        val numericPan = pan.filter(Char::isDigit)
        if (numericPan.length < MINIMUM_PAN_LENGTH || keyIndex !in MINIMUM_KEY_INDEX..MAXIMUM_KEY_INDEX) {
            return@withLock PinChangeCaptureResult.Failure(
                PinChangeCaptureFailure.INVALID_CONFIGURATION,
            )
        }

        val reservedKsn = if (scheme == OnlinePinScheme.DUKPT) {
            val current = gateway.currentDukptKsn(keyIndex)
                ?.normalizeHex()
                ?.takeIf { it.isValidKsn() }
                ?: return@withLock PinChangeCaptureResult.Failure(
                    PinChangeCaptureFailure.INVALID_CONFIGURATION,
                )
            if (!gateway.reserveDukptKsn(keyIndex, current)) {
                return@withLock PinChangeCaptureResult.Failure(
                    PinChangeCaptureFailure.INVALID_CONFIGURATION,
                )
            }
            current
        } else {
            ""
        }

        var firstPinBlockCreated = false
        var result: PinChangeCaptureResult
        var cancellation: CancellationException? = null
        try {
            val first = gateway.captureSecurePin(
                purpose = SecurePinEntryPurpose.NEW_PIN,
                pan = numericPan,
                scheme = scheme,
                keyIndex = keyIndex,
                compatibleAcquirerIds = compatibleAcquirerIds,
            )
            if (first.status != SecurePinEntryStatus.ENTERED) {
                result = first.toFailure(firstEntry = true)
            } else {
                firstPinBlockCreated = true
                val firstBlock = first.pinBlock.normalizeHex()
                val firstKsn = first.ksn.normalizeHex()
                if (!firstBlock.isValidPinBlock() ||
                    (scheme == OnlinePinScheme.DUKPT && firstKsn != reservedKsn)
                ) {
                    result = PinChangeCaptureResult.Failure(
                        if (scheme == OnlinePinScheme.DUKPT && firstKsn != reservedKsn) {
                            PinChangeCaptureFailure.KSN_CHANGED
                        } else {
                            PinChangeCaptureFailure.FIRST_ENTRY_FAILED
                        },
                    )
                } else {
                    val confirmation = gateway.captureSecurePin(
                        purpose = SecurePinEntryPurpose.CONFIRM_NEW_PIN,
                        pan = numericPan,
                        scheme = scheme,
                        keyIndex = keyIndex,
                        compatibleAcquirerIds = compatibleAcquirerIds,
                    )
                    if (confirmation.status != SecurePinEntryStatus.ENTERED) {
                        result = confirmation.toFailure(firstEntry = false)
                    } else {
                        val confirmationBlock = confirmation.pinBlock.normalizeHex()
                        val confirmationKsn = confirmation.ksn.normalizeHex()
                        result = when {
                            !confirmationBlock.isValidPinBlock() -> PinChangeCaptureResult.Failure(
                                PinChangeCaptureFailure.CONFIRMATION_FAILED,
                            )
                            scheme == OnlinePinScheme.DUKPT && confirmationKsn != reservedKsn -> {
                                PinChangeCaptureResult.Failure(PinChangeCaptureFailure.KSN_CHANGED)
                            }
                            !constantTimeHexEquals(firstBlock, confirmationBlock) -> {
                                PinChangeCaptureResult.Failure(PinChangeCaptureFailure.PIN_MISMATCH)
                            }
                            else -> PinChangeCaptureResult.Success(
                                pinBlock = firstBlock,
                                ksn = reservedKsn,
                                scheme = scheme,
                                keyIndex = keyIndex,
                                compatibleAcquirerIds = compatibleAcquirerIds,
                            )
                        }
                    }
                }
            }
        } catch (error: CancellationException) {
            cancellation = error
            result = PinChangeCaptureResult.Failure(PinChangeCaptureFailure.CONFIRMATION_CANCELED)
        } catch (error: Throwable) {
            result = PinChangeCaptureResult.Failure(
                if (firstPinBlockCreated) {
                    PinChangeCaptureFailure.CONFIRMATION_FAILED
                } else {
                    PinChangeCaptureFailure.FIRST_ENTRY_FAILED
                },
                error.javaClass.simpleName,
            )
        } finally {
            if (scheme == OnlinePinScheme.DUKPT) {
                val closed = gateway.closeDukptKsnReservation(
                    keyIndex = keyIndex,
                    ksn = reservedKsn,
                    advance = firstPinBlockCreated,
                )
                if (!closed) {
                    result = PinChangeCaptureResult.Failure(
                        PinChangeCaptureFailure.KSN_INCREMENT_FAILED,
                    )
                }
            }
        }
        cancellation?.let { throw it }
        result
    }

    /** Converts a secure-entry terminal status into a phase-specific failure. */
    private fun SecurePinEntryResult.toFailure(firstEntry: Boolean): PinChangeCaptureResult.Failure {
        val reason = when (status) {
            SecurePinEntryStatus.BYPASSED -> if (firstEntry) {
                PinChangeCaptureFailure.FIRST_ENTRY_BYPASSED
            } else {
                PinChangeCaptureFailure.CONFIRMATION_BYPASSED
            }
            SecurePinEntryStatus.CANCELED -> if (firstEntry) {
                PinChangeCaptureFailure.FIRST_ENTRY_CANCELED
            } else {
                PinChangeCaptureFailure.CONFIRMATION_CANCELED
            }
            SecurePinEntryStatus.TIMED_OUT -> if (firstEntry) {
                PinChangeCaptureFailure.FIRST_ENTRY_TIMED_OUT
            } else {
                PinChangeCaptureFailure.CONFIRMATION_TIMED_OUT
            }
            SecurePinEntryStatus.ENTERED,
            SecurePinEntryStatus.ERROR,
            -> if (firstEntry) {
                PinChangeCaptureFailure.FIRST_ENTRY_FAILED
            } else {
                PinChangeCaptureFailure.CONFIRMATION_FAILED
            }
        }
        return PinChangeCaptureResult.Failure(reason, errorMessage)
    }

    /** Normalizes hexadecimal text without logging or otherwise exposing its content. */
    private fun String.normalizeHex(): String = filterNot(Char::isWhitespace).uppercase(Locale.US)

    /** Validates a DES/TDES ISO PIN block encoded as sixteen hexadecimal characters. */
    private fun String.isValidPinBlock(): Boolean =
        length == PIN_BLOCK_HEX_LENGTH && all { it.digitToIntOrNull(16) != null }

    /** Validates the KSN sizes supported by the host protocol. */
    private fun String.isValidKsn(): Boolean =
        length in MINIMUM_KSN_HEX_LENGTH..MAXIMUM_KSN_HEX_LENGTH &&
            length % HEX_BYTE_LENGTH == 0 &&
            all { it.digitToIntOrNull(16) != null }

    /** Compares encrypted PIN blocks without data-dependent early termination. */
    private fun constantTimeHexEquals(first: String, second: String): Boolean {
        val firstBytes = first.decodeHex() ?: return false
        val secondBytes = second.decodeHex() ?: return false
        return MessageDigest.isEqual(firstBytes, secondBytes)
    }

    /** Decodes normalized hexadecimal text for constant-time comparison. */
    private fun String.decodeHex(): ByteArray? {
        if (length % HEX_BYTE_LENGTH != 0) return null
        return ByteArray(length / HEX_BYTE_LENGTH) { index ->
            substring(index * HEX_BYTE_LENGTH, index * HEX_BYTE_LENGTH + HEX_BYTE_LENGTH)
                .toIntOrNull(16)
                ?.toByte()
                ?: return null
        }
    }

    private companion object {
        const val MINIMUM_PAN_LENGTH = 12
        const val MINIMUM_KEY_INDEX = 1
        const val MAXIMUM_KEY_INDEX = 10
        const val PIN_BLOCK_HEX_LENGTH = 16
        const val MINIMUM_KSN_HEX_LENGTH = 12
        const val MAXIMUM_KSN_HEX_LENGTH = 20
        const val HEX_BYTE_LENGTH = 2
    }
}
