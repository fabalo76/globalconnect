package one.globalconnect.paymentapp.cardreader.nexgo

import one.globalconnect.tms.paymentapp.TMSDATA
import one.globalconnect.tms.paymentapp.TMS_PinKeyScheme
import java.math.BigInteger

enum class OnlinePinScheme {
    MKSK,
    DUKPT,
}

internal data class OnlinePinPanRange(
    val low: String,
    val high: String,
    val panLength: Int,
) {
    fun matches(pan: String): Boolean {
        val digits = pan.filter(Char::isDigit)
        if (panLength > 0 && digits.length != panLength) return false
        val lowDigits = low.filter(Char::isDigit)
        val highDigits = high.filter(Char::isDigit)
        val compareLength = maxOf(lowDigits.length, highDigits.length)
        if (compareLength == 0 || digits.length < compareLength) return false
        val prefix = digits.take(compareLength).toBigIntegerOrNull() ?: return false
        val minimum = lowDigits.padEnd(compareLength, '0').toBigIntegerOrNull() ?: BigInteger.ZERO
        val maximum = highDigits.padEnd(compareLength, '9').toBigIntegerOrNull() ?: return false
        return prefix in minimum..maximum
    }
}

internal data class OnlinePinProfile(
    val acquirerId: String,
    val scheme: OnlinePinScheme,
    val keyIndex: Int,
    val ranges: List<OnlinePinPanRange>,
)

internal data class OnlinePinSelection(
    val scheme: OnlinePinScheme,
    val keyIndex: Int,
    val compatibleAcquirerIds: Set<String>,
)

internal class OnlinePinProfileResolver private constructor(
    private val profiles: List<OnlinePinProfile>,
) {
    fun resolve(pan: String): OnlinePinSelection? {
        val matching = profiles.filter { profile -> profile.ranges.any { it.matches(pan) } }
        val candidates = matching.ifEmpty {
            profiles.takeIf { configured -> configured.map { it.scheme to it.keyIndex }.distinct().size == 1 }
                .orEmpty()
        }
        val keyProfiles = candidates.groupBy { it.scheme to it.keyIndex }
        if (keyProfiles.size != 1) return null
        val (schemeAndIndex, selectedProfiles) = keyProfiles.entries.single()
        return OnlinePinSelection(
            scheme = schemeAndIndex.first,
            keyIndex = schemeAndIndex.second,
            compatibleAcquirerIds = selectedProfiles.map { it.acquirerId }.toSet(),
        )
    }

    companion object {
        fun from(database: TMSDATA): OnlinePinProfileResolver {
            val rangesById = database.CardRange.associateBy { it.CardRangeID }
            val issuersByAcquirer = database.Issuer.groupBy { it.AcqID }
            val profiles = database.Acquirer.mapNotNull { acquirer ->
                val scheme = when (acquirer.pinKeyScheme) {
                    TMS_PinKeyScheme.MKSK -> OnlinePinScheme.MKSK
                    TMS_PinKeyScheme.DUKPT -> OnlinePinScheme.DUKPT
                    TMS_PinKeyScheme.NONE -> return@mapNotNull null
                }
                val keyIndex = acquirer.nexgoPinKeyIndex ?: return@mapNotNull null
                val ranges = issuersByAcquirer[acquirer.AcqID]
                    .orEmpty()
                    .flatMap { it.cardRangeRefs }
                    .distinct()
                    .mapNotNull(rangesById::get)
                    .map { range ->
                        OnlinePinPanRange(
                            low = range.binLow,
                            high = range.binHigh,
                            panLength = range.Length.toInt(),
                        )
                    }
                OnlinePinProfile(acquirer.AcqID, scheme, keyIndex, ranges)
            }
            return OnlinePinProfileResolver(profiles)
        }

        internal fun fromProfiles(profiles: List<OnlinePinProfile>) = OnlinePinProfileResolver(profiles)
    }
}
