package com.uic.uicpaymentapp.cardreader

import android.util.Log
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nexgo.oaf.apiv3.SdkResult
import com.nexgo.oaf.apiv3.device.reader.CardInfoEntity
import com.nexgo.oaf.apiv3.device.reader.CardSlotTypeEnum
import com.nexgo.oaf.apiv3.device.reader.RfCardTypeEnum
import com.nexgo.oaf.apiv3.emv.EmvOnlineResultEntity
import com.nexgo.oaf.apiv3.emv.EmvProcessResultEntity
import com.nexgo.oaf.apiv3.emv.PromptEnum
import com.uic.uicpaymentapp.R
import com.uic.uicpaymentapp.UICApplication
import com.uic.uicpaymentapp.cardreader.nexgo.EmvTransactionListener
import com.uic.uicpaymentapp.cardreader.nexgo.EmvTransactionRequest
import com.uic.uicpaymentapp.cardreader.nexgo.MagstripeData
import com.uic.uicpaymentapp.cardreader.nexgo.NexgoApi
import com.uic.uicpaymentapp.cardreader.nexgo.NexgoSdkResult
import com.uic.uicpaymentapp.cardreader.nexgo.PinStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale

private const val TAG = "CardReaderViewModel"

private val EMV_TAG_WHITELIST = arrayOf(
    "9f26",
    "9f27",
    "9f10",
    "9f37",
    "9f36",
    "95",
    "9a",
    "9c",
    "9f02",
    "5f2a",
    "82",
    "9f1a",
    "9f03",
    "9f33",
    "9f34",
    "9f35",
    "9f1e",
    "9f09",
    "84",
    "9f41",
    "5a",
    "57",
    "56",
    "5f24",
    "5f34",
)

private data class EmvData(
    val tags: List<EmvTag> = emptyList(),
    val rawTlv: String? = null,
    val values: Map<String, String> = emptyMap(),
)

/**
 * ViewModel responsible for orchestrating card search operations using the Nexgo SDK.
 */
class CardReaderViewModel(
    private val nexgoApi: NexgoApi = UICApplication.instance.nexgoApi,
) : ViewModel() {

    private val _uiState = MutableStateFlow(CardReaderUiState())
    val uiState: StateFlow<CardReaderUiState> = _uiState.asStateFlow()

    private val stateLock = Any()
    @Volatile
    private var searchActive: Boolean = false
    private var activeCardInfo: CardInfoEntity? = null
    private var activeSlot: CardSlotTypeEnum? = null

    private val transactionListener = CardReaderTransactionListener()

    init {
        Log.d(TAG, "CardReaderViewModel init assigning listeners")
        nexgoApi.transactionListener = transactionListener
        nexgoApi.pinEntryHandler = { _, _ ->
            // Intentionally no UI; PIN entry is automatically bypassed for the test flow.
        }
        Log.d(TAG, "CardReaderViewModel init complete")
    }

    /**
     * Initiates a card search across magnetic, contact, and contactless readers.
     */
    fun startCardSearch(
        amount: String,
        cashbackAmount: String = "0.00",
        timeoutSeconds: Int = 60,
        countryCode: String = "0840",
        currencyCode: String = "0840",
    ) {
        Log.d(
            TAG,
            "startCardSearch amount=$amount cashbackAmount=$cashbackAmount timeout=$timeoutSeconds country=$countryCode currency=$currencyCode searchActive=$searchActive",
        )
        synchronized(stateLock) {
            if (searchActive) {
                Log.d(TAG, "startCardSearch already active, ignoring request")
                return
            }
            searchActive = true
            Log.d(TAG, "startCardSearch marked searchActive=true")
        }

        activeCardInfo = null
        activeSlot = null
        Log.d(TAG, "startCardSearch reset active card data")

        postState {
            it.copy(
                isSearching = true,
                status = CardReaderStatus.Waiting,
                cardData = null,
            )
        }

        val normalizedAmount = normaliseAmountInput(amount)
        val normalizedCashback = normaliseAmountInput(cashbackAmount)
        Log.d(
            TAG,
            "startCardSearch normalizedAmount=$normalizedAmount normalizedCashback=$normalizedCashback",
        )

        val request = EmvTransactionRequest(
            amount = normalizedAmount,
            cashbackAmount = normalizedCashback,
            traceNumber = nextTraceNumber(),
            timeoutSeconds = timeoutSeconds,
            allowSwipe = true,
            allowContact = true,
            allowContactless = true,
            forceOnline = false,
            countryCode = countryCode,
            currencyCode = currencyCode,
        )

        Log.d(TAG, "startCardSearch created request trace=${request.traceNumber}")

        try {
            nexgoApi.startTransaction(request)
            Log.d(TAG, "startCardSearch invoked Nexgo API")
        } catch (error: Throwable) {
            Log.e(TAG, "Unable to start card transaction", error)
            synchronized(stateLock) {
                searchActive = false
            }
            postState {
                it.copy(
                    isSearching = false,
                    status = CardReaderStatus.Error(
                        getString(
                            R.string.card_reader_error_exception,
                            error.message ?: error.javaClass.simpleName,
                        )
                    ),
                    cardData = null,
                )
            }
        }
    }

    /**
     * Cancels an ongoing card search if one is active.
     */
    fun cancelCardSearch() {
        Log.d(TAG, "cancelCardSearch invoked")
        val wasActive = synchronized(stateLock) {
            val current = searchActive
            searchActive = false
            current
        }

        Log.d(TAG, "cancelCardSearch wasActive=$wasActive")

        activeCardInfo = null
        activeSlot = null
        Log.d(TAG, "cancelCardSearch cleared active card data")

        if (wasActive) {
            try {
                nexgoApi.cancelTransaction()
                Log.d(TAG, "cancelCardSearch requested Nexgo cancellation")
            } catch (error: Throwable) {
                Log.w(TAG, "Failed to cancel card transaction", error)
            }
        }

        postState {
            it.copy(
                isSearching = false,
                status = CardReaderStatus.Idle,
                cardData = null,
            )
        }
    }

    override fun onCleared() {
        Log.d(TAG, "onCleared invoked")
        try {
            nexgoApi.cancelTransaction()
            Log.d(TAG, "onCleared requested Nexgo cancellation")
        } catch (error: Throwable) {
            Log.w(TAG, "Failed to cancel card transaction on clear", error)
        }
        nexgoApi.transactionListener = null
        nexgoApi.pinEntryHandler = null
        Log.d(TAG, "onCleared released Nexgo API listeners")
        super.onCleared()
    }

    private fun respondToOnlineProcessing() {
        Log.d(TAG, "respondToOnlineProcessing invoked")
        val handler = NexgoApi.emvHandler ?: return
        try {
            val onlineResult = EmvOnlineResultEntity().apply {
                rejCode = "00"
                authCode = "000000"
                recvField55 = null
            }
            handler.onSetOnlineProcResponse(SdkResult.Success, onlineResult)
            Log.d(TAG, "respondToOnlineProcessing acknowledged with success")
        } catch (error: Throwable) {
            Log.w(TAG, "Failed to acknowledge EMV online processing", error)
        }
    }

    private fun gatherEmvData(): EmvData {
        Log.d(TAG, "gatherEmvData invoked")
        val handler = NexgoApi.emvHandler ?: return EmvData()
        return try {
            val raw = handler.getTlvByTags(EMV_TAG_WHITELIST)
            Log.d(TAG, "gatherEmvData rawLength=${raw?.length}")
            parseEmvData(raw)
        } catch (error: Throwable) {
            Log.w(TAG, "Unable to read EMV tags", error)
            EmvData()
        }
    }

    private fun parseEmvData(raw: String?): EmvData {
        Log.d(TAG, "parseEmvData raw=${raw}")
        if (raw.isNullOrBlank()) {
            return EmvData()
        }

        val normalized = raw.trim()
        val pattern = Regex("(?i)\\b([0-9a-f]{2,})\\s*(?:=|:)\\s*([0-9a-f]+)\\b")
        val tagValues = linkedMapOf<String, String>()

        pattern.findAll(normalized).forEach { matchResult ->
            val tag = matchResult.groupValues[1].uppercase(Locale.US)
            val value = matchResult.groupValues[2].uppercase(Locale.US)
            tagValues[tag] = value
        }

        val rawDisplay = normalized.uppercase(Locale.US)
        if (tagValues.isNotEmpty()) {
            return EmvData(
                tags = tagValues.map { (tag, value) -> EmvTag(tag, value) },
                rawTlv = rawDisplay,
                values = tagValues.toMap(),
            )
        }

        val tlvValues = parseRawTlv(normalized)
        if (tlvValues.isNotEmpty()) {
            return EmvData(
                tags = tlvValues.map { (tag, value) -> EmvTag(tag, value) },
                rawTlv = rawDisplay,
                values = tlvValues,
            )
        }

        return EmvData(rawTlv = rawDisplay)
    }

    private fun parseRawTlv(raw: String): Map<String, String> {
        Log.d(TAG, "parseRawTlv rawLength=${raw.length}")
        if (raw.isBlank()) {
            return emptyMap()
        }

        val collapsed = raw.replace("\\s+".toRegex(), "")
        if (collapsed.length < 4) {
            return emptyMap()
        }

        val normalized = collapsed.uppercase(Locale.US)
        val values = linkedMapOf<String, String>()
        var index = 0

        while (index + 4 <= normalized.length) {
            var tagEnd = index + 2
            var tag = normalized.substring(index, tagEnd)
            val firstByte = tag.toIntOrNull(16) ?: break

            if ((firstByte and 0x1F) == 0x1F) {
                do {
                    if (tagEnd + 2 > normalized.length) {
                        return values
                    }
                    val nextByte = normalized.substring(tagEnd, tagEnd + 2)
                    tag += nextByte
                    tagEnd += 2
                    val nextValue = nextByte.toIntOrNull(16) ?: return values
                    if ((nextValue and 0x80) != 0x80) {
                        break
                    }
                } while (true)
            }

            index = tagEnd
            if (index + 2 > normalized.length) {
                return values
            }

            var lengthByte = normalized.substring(index, index + 2).toIntOrNull(16) ?: return values
            index += 2

            if ((lengthByte and 0x80) == 0x80) {
                val numberOfBytes = lengthByte and 0x7F
                if (numberOfBytes <= 0 || index + numberOfBytes * 2 > normalized.length) {
                    return values
                }

                lengthByte = 0
                repeat(numberOfBytes) {
                    val component = normalized.substring(index, index + 2).toIntOrNull(16) ?: return values
                    index += 2
                    lengthByte = (lengthByte shl 8) or component
                }
            }

            val valueLength = lengthByte * 2
            if (valueLength < 0 || index + valueLength > normalized.length) {
                return values
            }

            val value = normalized.substring(index, index + valueLength)
            index += valueLength
            values[tag] = value
        }

        return values
    }

    private fun finishTransaction(status: CardReaderStatus, result: CardReadResult?) {
        Log.d(TAG, "finishTransaction status=$status hasResult=${result != null}")
        synchronized(stateLock) {
            searchActive = false
        }
        activeCardInfo = null
        activeSlot = null
        Log.d(TAG, "finishTransaction cleared state")

        postState {
            it.copy(
                isSearching = false,
                status = status,
                cardData = if (status is CardReaderStatus.Success) result else null,
            )
        }
    }

    private fun postState(reducer: (CardReaderUiState) -> CardReaderUiState) {
        Log.d(TAG, "postState scheduling state update")
        viewModelScope.launch {
            Log.d(TAG, "postState applying reducer")
            _uiState.update(reducer)
        }
    }

    private fun nextTraceNumber(): String {
        Log.d(TAG, "nextTraceNumber invoked")
        val value = (System.currentTimeMillis() % 1_000_000).toInt()
        return value.toString().padStart(6, '0')
    }

    private fun normaliseAmountInput(value: String): String {
        Log.d(TAG, "normaliseAmountInput value=$value")
        val sanitized = value.trim().replace("[^0-9.]".toRegex(), "")
        val defaulted = sanitized.ifBlank { "0" }
        return try {
            val normalized = BigDecimal(defaulted)
                .movePointRight(2)
                .setScale(0, RoundingMode.HALF_UP)
            val nonNegative = if (normalized.signum() < 0) BigDecimal.ZERO else normalized
            val plain = nonNegative.toPlainString()
            val padded = plain.padStart(12, '0')
            val result = if (padded.length > 12) padded.takeLast(12) else padded
            Log.d(TAG, "normaliseAmountInput result=$result")
            result
        } catch (error: NumberFormatException) {
            Log.w(TAG, "normaliseAmountInput failed for '$value'", error)
            "000000000000"
        }
    }

    private fun createCardResult(
        retCode: Int,
        info: CardInfoEntity?,
        trackData: MagstripeData?,
        emvData: EmvData,
    ): CardReadResult {
        Log.d(
            TAG,
            "createCardResult retCode=$retCode slot=${info?.cardExistslot} pan=${info?.cardNo?.let(::maskPan)}",
        )
        val slot = info?.cardExistslot ?: activeSlot
        val derived = deriveCardData(trackData, emvData)
        val base = info?.toResult(retCode, trackData, emvData.tags, emvData.rawTlv)

        return base?.copy(
            maskedCardNumber = base.maskedCardNumber ?: derived.maskedPan,
            cardNumber = base.cardNumber ?: derived.pan,
            track1 = base.track1 ?: derived.track1,
            track2 = base.track2 ?: derived.track2,
            track3 = base.track3 ?: derived.track3,
            expiryDate = base.expiryDate ?: derived.expiryDate,
            serviceCode = base.serviceCode ?: derived.serviceCode,
            csn = base.csn ?: derived.cardSequenceNumber,
        ) ?: CardReadResult(
            returnCode = retCode,
            slotType = slot,
            isIcc = slot == CardSlotTypeEnum.ICC1 || slot == CardSlotTypeEnum.ICC2,
            maskedCardNumber = derived.maskedPan,
            cardNumber = derived.pan,
            track1 = derived.track1,
            track2 = derived.track2,
            track3 = derived.track3,
            expiryDate = derived.expiryDate,
            serviceCode = derived.serviceCode,
            rfCardType = info?.rfCardType,
            csn = derived.cardSequenceNumber,
            emvTags = emvData.tags,
            rawEmvData = emvData.rawTlv,
        )
    }

    private fun deriveCardData(
        trackData: MagstripeData?,
        emvData: EmvData,
    ): DerivedCardData {
        Log.d(
            TAG,
            "deriveCardData track1Present=${!trackData?.track1.isNullOrBlank()} track2Present=${!trackData?.track2.isNullOrBlank()} emvTagCount=${emvData.tags.size}",
        )
        val track1 = trackData?.track1 ?: decodeTrack1FromEmv(emvData.values["56"])
        val track2 = trackData?.track2 ?: decodeTrack2FromEmv(emvData.values["57"])
        val track3 = trackData?.track3

        val track2Components = parseTrack2Components(track2)
        val panFromTag = decodeBcd(emvData.values["5A"])
        val expiryFromTag = decodeBcd(emvData.values["5F24"])?.takeIf { it.length >= 4 }?.substring(0, 4)
        val csnFromTag = decodeBcd(emvData.values["5F34"])

        val pan = track2Components.pan ?: panFromTag
        val expiry = track2Components.expiryDate ?: expiryFromTag
        val serviceCode = track2Components.serviceCode
        val csn = csnFromTag?.let { value ->
            when {
                value.isBlank() -> null
                value.length == 1 -> value.padStart(2, '0')
                else -> value
            }
        }

        return DerivedCardData(
            pan = pan,
            maskedPan = pan?.let(::maskPan),
            track1 = track1,
            track2 = track2,
            track3 = track3,
            expiryDate = expiry,
            serviceCode = serviceCode,
            cardSequenceNumber = csn,
        )
    }

    private data class DerivedCardData(
        val pan: String?,
        val maskedPan: String?,
        val track1: String?,
        val track2: String?,
        val track3: String?,
        val expiryDate: String?,
        val serviceCode: String?,
        val cardSequenceNumber: String?,
    )

    private data class Track2Components(
        val pan: String? = null,
        val expiryDate: String? = null,
        val serviceCode: String? = null,
    )

    private fun decodeTrack1FromEmv(value: String?): String? {
        Log.d(TAG, "decodeTrack1FromEmv valueLength=${value?.length}")
        if (value.isNullOrBlank()) {
            return null
        }

        val sanitized = value.trim()
        if (sanitized.length < 2) {
            return null
        }

        val byteCount = sanitized.length / 2
        if (byteCount == 0) {
            return null
        }

        val bytes = ByteArray(byteCount)
        for (index in 0 until byteCount) {
            val start = index * 2
            val end = start + 2
            val hexByte = sanitized.substring(start, end)
            val byteValue = hexByte.toIntOrNull(16) ?: return null
            bytes[index] = byteValue.toByte()
        }

        val decoded = String(bytes, Charsets.US_ASCII).trim()
        return decoded.takeIf { it.isNotEmpty() }
    }

    private fun decodeTrack2FromEmv(value: String?): String? {
        Log.d(TAG, "decodeTrack2FromEmv valueLength=${value?.length}")
        val decoded = decodeBcd(value, '=')?.trim()
        return decoded?.takeIf { it.isNotEmpty() }
    }

    private fun decodeBcd(value: String?, separatorChar: Char? = null): String? {
        Log.d(TAG, "decodeBcd valueLength=${value?.length} separator=$separatorChar")
        if (value.isNullOrBlank()) {
            return null
        }

        val sanitized = value.trim()
        if (sanitized.length < 2) {
            return null
        }

        val builder = StringBuilder(sanitized.length)
        var index = 0
        while (index + 1 < sanitized.length) {
            val high = Character.digit(sanitized[index], 16)
            val low = Character.digit(sanitized[index + 1], 16)

            if (high >= 0) {
                appendBcdNibble(builder, high, separatorChar)
            }
            if (low >= 0) {
                appendBcdNibble(builder, low, separatorChar)
            }

            index += 2
        }

        val result = builder.toString().trim()
        return result.takeIf { it.isNotEmpty() }
    }

    private fun appendBcdNibble(builder: StringBuilder, nibble: Int, separatorChar: Char?) {
        //Log.d(TAG, "appendBcdNibble nibble=$nibble separator=$separatorChar")
        when (nibble) {
            in 0..9 -> builder.append(('0'.code + nibble).toChar())
            0xA -> builder.append('A')
            0xB -> builder.append('B')
            0xC -> builder.append('C')
            0xD -> if (separatorChar != null) {
                builder.append(separatorChar)
            } else {
                builder.append('D')
            }
            0xE -> builder.append('E')
            0xF -> Unit
            else -> Unit
        }
    }

    private fun parseTrack2Components(track2: String?): Track2Components {
        Log.d(TAG, "parseTrack2Components track2=${track2?.take(32)}")
        if (track2.isNullOrBlank()) {
            return Track2Components()
        }

        val trimmed = track2.trim()
        val sanitized = trimmed
            .substringBefore('?')
            .trimEnd('F', 'f')
            .replace('d', '=')
            .replace('D', '=')
        val withoutSentinel = sanitized.removePrefix(";")
        val match = TRACK2_PATTERN.find(withoutSentinel)

        val pan = match?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }?.trimEnd('F', 'f')
        val expiry = match?.groupValues?.getOrNull(2)?.takeIf { it.length == 4 }
        val serviceCode = match?.groupValues?.getOrNull(3)?.takeIf { it.length == 3 }

        return Track2Components(
            pan = pan,
            expiryDate = expiry,
            serviceCode = serviceCode,
        )
    }

    private fun maskPan(pan: String): String {
        Log.d(TAG, "maskPan panLength=${pan.length}")
        if (pan.isBlank()) {
            return pan
        }

        return when {
            pan.length <= 4 -> "*".repeat(pan.length)
            pan.length <= 6 -> pan.take(1) + "*".repeat(pan.length - 2) + pan.takeLast(1)
            pan.length <= 10 -> pan.take(2) + "*".repeat(pan.length - 6) + pan.takeLast(4)
            else -> pan.take(6) + "*".repeat(pan.length - 10) + pan.takeLast(4)
        }
    }

    companion object {
        private val TRACK2_PATTERN = Regex("^([0-9]{0,19})=([0-9]{4})([0-9]{3})?.*")
    }

    private inner class CardReaderTransactionListener : EmvTransactionListener {
        override fun onCardDetected(slot: CardSlotTypeEnum, info: CardInfoEntity) {
            Log.d(
                TAG,
                "onCardDetected slot=$slot maskedPan=${info.cardNo?.let(::maskPan)} tk2Present=${!info.tk2.isNullOrBlank()}",
            )
            activeCardInfo = info
            activeSlot = slot

            if (slot == CardSlotTypeEnum.ICC1 || slot == CardSlotTypeEnum.ICC2 || slot == CardSlotTypeEnum.RF) {
                postState {
                    it.copy(
                        status = CardReaderStatus.ProcessingEmv,
                        cardData = null,
                    )
                }
            }
        }

        override fun onMagstripeRead(data: MagstripeData) {
            Log.d(
                TAG,
                "onMagstripeRead track1=${data.track1?.length} track2=${data.track2?.length} track3=${data.track3?.length}",
            )
            val result = createCardResult(
                SdkResult.Success,
                activeCardInfo,
                data,
                EmvData(),
            )
            Log.d(TAG, "onMagstripeRead generated card result maskedPan=${result.maskedCardNumber}")
            finishTransaction(CardReaderStatus.Success, result)
        }

        override fun onPrompt(prompt: PromptEnum?) {
            Log.d(TAG, "onPrompt prompt=${prompt?.name}")
            try {
                NexgoApi.emvHandler?.onSetPromptResponse(true)
            } catch (error: Throwable) {
                Log.w(TAG, "Failed to acknowledge EMV prompt", error)
            }
        }

        override fun onPinRequested(isOnlinePin: Boolean, attemptsRemaining: Int) {
            Log.d(TAG, "onPinRequested online=$isOnlinePin attemptsRemaining=$attemptsRemaining")
            try {
                NexgoApi.pinData.status = PinStatus.BYPASSED
                NexgoApi.pinEntryDone = true
                NexgoApi.emvHandler?.onSetPinInputResponse(true, true)
            } catch (error: Throwable) {
                Log.w(TAG, "Unable to respond to PIN request", error)
            }
        }

        override fun onOnlineProcessing() {
            Log.d(TAG, "onOnlineProcessing callback")
            postState {
                it.copy(status = CardReaderStatus.ProcessingEmv)
            }
            respondToOnlineProcessing()
        }

        override fun onContactlessRetryRequired() {
            Log.d(TAG, "onContactlessRetryRequired callback")
            try {
                NexgoApi.emvHandler?.onSetContactlessTapCardResponse(true)
            } catch (error: Throwable) {
                Log.w(TAG, "Unable to acknowledge contactless retry", error)
            }
            postState {
                it.copy(
                    status = CardReaderStatus.Waiting,
                    cardData = null,
                )
            }
        }

        override fun onRemoveCard() {
            Log.d(TAG, "onRemoveCard callback")
            try {
                NexgoApi.emvHandler?.onSetRemoveCardResponse()
            } catch (error: Throwable) {
                Log.w(TAG, "Unable to acknowledge remove card request", error)
            }
        }

        override fun onTransactionFinished(resultCode: Int, result: EmvProcessResultEntity?) {
            val sdkName = NexgoSdkResult.sdkName(resultCode)
            Log.d(TAG, "onTransactionFinished resultCode=$resultCode ($sdkName)")
            val emvData = gatherEmvData()
            val cardResult = createCardResult(resultCode, activeCardInfo, null, emvData)
            val status = if (resultCode == SdkResult.Success) {
                CardReaderStatus.Success
            } else {
                Log.w(TAG, "EMV result: $sdkName ($resultCode) — ${NexgoSdkResult.friendlyMessage(resultCode)}")
                CardReaderStatus.Error(NexgoSdkResult.friendlyMessage(resultCode))
            }
            finishTransaction(status, cardResult)
        }

        override fun onError(message: String, throwable: Throwable?) {
            Log.d(TAG, "onError message=$message throwable=${throwable?.javaClass?.simpleName}")
            Log.e(TAG, "Card reader error: $message", throwable)
            finishTransaction(CardReaderStatus.Error(message), null)
        }

        override fun onMultipleCardsDetected() {
            Log.d(TAG, "onMultipleCardsDetected callback")
            finishTransaction(CardReaderStatus.MultipleCards, null)
        }

        override fun onSwipeIncorrect() {
            Log.d(TAG, "onSwipeIncorrect callback")
            postState {
                it.copy(
                    status = CardReaderStatus.SwipeIncorrect,
                    cardData = null,
                )
            }
        }
    }
}

private fun CardInfoEntity.toResult(
    retCode: Int,
    trackData: MagstripeData?,
    emvTags: List<EmvTag>,
    rawEmvData: String?,
): CardReadResult {
    Log.d(
        TAG,
        "CardInfoEntity.toResult retCode=$retCode slot=$cardExistslot maskedPan=$maskCardNo track2Length=${(trackData?.track2 ?: tk2)?.length}",
    )
    return CardReadResult(
        returnCode = retCode,
        slotType = cardExistslot,
        isIcc = isICC,
        maskedCardNumber = maskCardNo,
        cardNumber = cardNo,
        track1 = trackData?.track1 ?: tk1,
        track2 = trackData?.track2 ?: tk2,
        track3 = trackData?.track3 ?: tk3,
        expiryDate = expiredDate,
        serviceCode = serviceCode,
        rfCardType = rfCardType,
        csn = csn,
        emvTags = emvTags,
        rawEmvData = rawEmvData,
    )
}

private fun getString(@StringRes resId: Int, vararg args: Any): String =
    UICApplication.instance.getString(resId, *args).also {
        Log.d(TAG, "getString resId=$resId resultLength=${it.length}")
    }

data class CardReaderUiState(
    val isSearching: Boolean = false,
    val status: CardReaderStatus = CardReaderStatus.Idle,
    val cardData: CardReadResult? = null,
)

sealed class CardReaderStatus {
    data object Idle : CardReaderStatus()
    data object Waiting : CardReaderStatus()
    data object ProcessingEmv : CardReaderStatus()
    data object SwipeIncorrect : CardReaderStatus()
    data object MultipleCards : CardReaderStatus()
    data object Success : CardReaderStatus()
    data class Error(val reason: String) : CardReaderStatus()
}

data class CardReadResult(
    val returnCode: Int,
    val slotType: CardSlotTypeEnum?,
    val isIcc: Boolean,
    val maskedCardNumber: String?,
    val cardNumber: String?,
    val track1: String?,
    val track2: String?,
    val track3: String?,
    val expiryDate: String?,
    val serviceCode: String?,
    val rfCardType: RfCardTypeEnum?,
    val csn: String?,
    val emvTags: List<EmvTag> = emptyList(),
    val rawEmvData: String? = null,
)

data class EmvTag(
    val tag: String,
    val value: String,
)
