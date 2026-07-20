package one.globalconnect.pinpad.device

import android.content.Context
import android.util.Log
import com.nexgo.oaf.apiv3.DeviceEngine
import com.nexgo.oaf.apiv3.SdkResult
import com.nexgo.oaf.apiv3.card.mifare.AuthEntity
import com.nexgo.oaf.apiv3.card.mifare.BlockEntity
import com.nexgo.oaf.apiv3.card.mifare.M1CardOperTypeEnum
import com.nexgo.oaf.apiv3.card.mifare.M1KeyTypeEnum
import com.nexgo.oaf.apiv3.device.reader.CardSlotTypeEnum
import com.nexgo.oaf.apiv3.device.reader.RfCardNxpTypeEnum
import com.nexgo.oaf.apiv3.device.reader.TypeAInfoEntity
import one.globalconnect.pinpad.logging.PinpadTraceLog
import one.globalconnect.pinpad.storage.PinpadPreferences
import java.util.Locale

class PinpadMifareController(
    context: Context,
    private val deviceEngine: DeviceEngine,
) {
    private val prefs = PinpadPreferences(context)
    private val cardReader by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { deviceEngine.cardReader }
    private val m1Card by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { deviceEngine.getM1CardHandler() }
    private val ultralightC by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { deviceEngine.getUltralightCCardHandler() }
    private val ultralightEv1 by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { deviceEngine.getUltralightEV1CardHandler() }
    private val ntag by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { deviceEngine.getNTAGCardHandler() }
    private val desfire by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { deviceEngine.getDesfireHandler() }
    private val rfCpuCard by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        deviceEngine.getCPUCardHandler(CardSlotTypeEnum.RF)
    }

    @Volatile private var enabled = false
    @Volatile private var currentType: RfCardNxpTypeEnum? = null
    @Volatile private var currentAtqa = ""
    @Volatile private var currentSak = ""
    @Volatile private var currentUid = ""
    @Volatile private var currentAts = ""

    fun process(commandId: String, payload: String): String {
        return when (commandId) {
            "P01" -> enableDisable(commandId, payload)
            "P02" -> queryPresence(commandId)
            "P03" -> anticollision(commandId)
            "P04" -> select(commandId)
            "P05" -> activate(commandId)
            "P06" -> halt(commandId)
            "P07" -> authenticateClassic(commandId, payload)
            "P08" -> readUltralightPage(commandId, payload)
            "P09" -> writeUltralightPage(commandId, payload)
            "P10" -> readBlock(commandId, payload)
            "P11" -> writeBlock(commandId, payload)
            "P12" -> readSector(commandId, payload)
            "P13" -> writeSector(commandId, payload)
            "P14" -> valueOperation(commandId, payload)
            "P15" -> loadKey(commandId, payload)
            "P16" -> identify(commandId)
            "P17" -> activateDesfire(commandId)
            "P18" -> deselectDesfire(commandId)
            "P19" -> apduExchange(commandId, payload)
            "P20" -> blockExchange(commandId, payload)
            else -> fail(commandId, REASON_FORMAT)
        }
    }

    private fun enableDisable(commandId: String, payload: String): String {
        val flag = payload.firstOrNull() ?: return fail(commandId, REASON_FORMAT)
        return when (flag) {
            '0' -> {
                enabled = false
                clearCardState()
                runCatching { cardReader.close(CardSlotTypeEnum.RF) }
                    .onFailure { Log.w(TAG, "Unable to close RF reader", it) }
                ok(commandId)
            }
            '1' -> {
                enabled = true
                runCatching { cardReader.open(CardSlotTypeEnum.RF) }
                    .onFailure { Log.w(TAG, "Unable to open RF reader", it) }
                ok(commandId)
            }
            else -> fail(commandId, REASON_FORMAT)
        }.also { PinpadTraceLog.device("MIFARE P01 flag=$flag response=$it") }
    }

    private fun queryPresence(commandId: String): String {
        val info = refreshTypeAInfo() ?: return fail(commandId, REASON_CARD_NOT_EXISTS)
        return ok(commandId, info.atqa)
    }

    private fun anticollision(commandId: String): String {
        val info = refreshTypeAInfo() ?: return fail(commandId, REASON_OPERATION)
        return if (info.uid.isBlank()) fail(commandId, REASON_OPERATION) else ok(commandId, info.uid)
    }

    private fun select(commandId: String): String {
        val info = refreshTypeAInfo() ?: return fail(commandId, REASON_OPERATION)
        return if (info.sak.isBlank()) fail(commandId, REASON_OPERATION) else ok(commandId, info.sak)
    }

    private fun activate(commandId: String): String {
        val info = refreshTypeAInfo() ?: return fail(commandId, REASON_OPERATION)
        return ok(commandId, "${info.atqa}$FS${info.sak}$FS${info.uid}")
    }

    private fun halt(commandId: String): String {
        clearCardState()
        return runCatching {
            cardReader.close(CardSlotTypeEnum.RF)
            if (enabled) cardReader.open(CardSlotTypeEnum.RF)
            ok(commandId)
        }.getOrElse {
            Log.w(TAG, "Unable to halt MIFARE card", it)
            fail(commandId, REASON_OPERATION)
        }
    }

    private fun authenticateClassic(commandId: String, payload: String): String {
        val sector = payload.take(2).toIntOrNull() ?: return fail(commandId, REASON_FORMAT)
        if (sector !in MIFARE_SECTOR_RANGE) return fail(commandId, REASON_FORMAT)
        val block = firstBlockOfSector(sector) ?: return fail(commandId, REASON_FORMAT)
        val keyType: Char
        val keyHex: String
        when {
            payload.length == 2 -> {
                keyType = 'A'
                keyHex = DEFAULT_MIFARE_KEY
            }
            payload.length == 15 -> {
                keyType = payload[2]
                keyHex = payload.drop(3).take(12)
            }
            payload.length == 6 -> {
                val keyNumber = payload.drop(2).take(3).toIntOrNull() ?: return fail(commandId, REASON_FORMAT)
                keyType = payload[5]
                val stored = prefs.mifareKey(keyNumber) ?: return fail(commandId, REASON_OPERATION)
                keyHex = if (keyType.uppercaseChar() == 'B') stored.keyB else stored.keyA
            }
            else -> return fail(commandId, REASON_FORMAT)
        }
        if (keyType.uppercaseChar() !in setOf('A', 'B') || !keyHex.isHexLength(12)) {
            return fail(commandId, REASON_FORMAT)
        }
        val uid = currentUid.ifBlank { runCatching { m1Card.readUid().orEmpty() }.getOrDefault("") }
        val entity = AuthEntity().apply {
            setBlkNo(block)
            setKeyType(if (keyType.uppercaseChar() == 'B') M1KeyTypeEnum.KEYTYPE_B else M1KeyTypeEnum.KEYTYPE_A)
            setPwd(keyHex.hexToBytesOrNull())
            setUid(uid)
        }
        val result = runCatching { m1Card.authority(entity) }.getOrDefault(SdkResult.Fail)
        PinpadTraceLog.device("MIFARE P07 sector=$sector block=$block keyType=$keyType sdkResult=$result")
        return if (result == SdkResult.Success) ok(commandId) else fail(commandId, REASON_OPERATION)
    }

    private fun readUltralightPage(commandId: String, payload: String): String {
        val page = payload.take(2).toIntOrNull() ?: return fail(commandId, REASON_FORMAT)
        if (page !in ULTRALIGHT_PAGE_RANGE) return fail(commandId, REASON_FORMAT)
        val data = readUltralight(page) ?: return fail(commandId, REASON_OPERATION)
        return ok(commandId, data.take(4).toByteArray().toHex())
    }

    private fun writeUltralightPage(commandId: String, payload: String): String {
        val page = payload.take(2).toIntOrNull() ?: return fail(commandId, REASON_FORMAT)
        val data = payload.drop(2).take(8)
        if (page !in ULTRALIGHT_PAGE_RANGE || !data.isHexLength(8)) return fail(commandId, REASON_FORMAT)
        val result = runCatching { writeUltralight(page, data.hexToBytesOrNull()) }.getOrDefault(SdkResult.Fail)
        return if (result == SdkResult.Success) ok(commandId) else fail(commandId, REASON_OPERATION)
    }

    private fun readBlock(commandId: String, payload: String): String {
        val block = payload.take(3).toIntOrNull() ?: return fail(commandId, REASON_FORMAT)
        if (block !in MIFARE_BLOCK_RANGE) return fail(commandId, REASON_FORMAT)
        val data = if (isClassicType()) readClassicBlock(block) else readUltralight(block)
        return data?.let { ok(commandId, it.toHex()) } ?: fail(commandId, REASON_OPERATION)
    }

    private fun writeBlock(commandId: String, payload: String): String {
        val block = payload.take(3).toIntOrNull() ?: return fail(commandId, REASON_FORMAT)
        val dataHex = payload.drop(3).take(32)
        if (block !in MIFARE_BLOCK_RANGE || !dataHex.isHexLength(32)) return fail(commandId, REASON_FORMAT)
        val data = dataHex.hexToBytesOrNull()
        val result = if (isClassicType()) writeClassicBlock(block, data) else writeUltralight(block, data.take(4).toByteArray())
        return if (result == SdkResult.Success) ok(commandId) else fail(commandId, REASON_OPERATION)
    }

    private fun readSector(commandId: String, payload: String): String {
        val sector = payload.take(2).toIntOrNull() ?: return fail(commandId, REASON_FORMAT)
        val blocks = blocksForSector(sector) ?: return fail(commandId, REASON_FORMAT)
        val data = buildString {
            for (block in blocks) {
                val blockData = (if (isClassicType()) readClassicBlock(block) else readUltralight(block))
                    ?: return fail(commandId, REASON_OPERATION)
                append(blockData.toHex())
            }
        }
        return ok(commandId, data)
    }

    private fun writeSector(commandId: String, payload: String): String {
        val sector = payload.take(2).toIntOrNull() ?: return fail(commandId, REASON_FORMAT)
        val dataHex = payload.drop(2)
        val blocks = blocksForSector(sector)?.toList() ?: return fail(commandId, REASON_FORMAT)
        if (dataHex.isBlank() || dataHex.length % 32 != 0 || !dataHex.isHex()) return fail(commandId, REASON_FORMAT)
        val chunks = dataHex.chunked(32)
        if (chunks.size > blocks.size) return fail(commandId, REASON_FORMAT)
        chunks.forEachIndexed { index, chunk ->
            val result = if (isClassicType()) {
                writeClassicBlock(blocks[index], chunk.hexToBytesOrNull())
            } else {
                writeUltralight(blocks[index], chunk.hexToBytesOrNull().take(4).toByteArray())
            }
            if (result != SdkResult.Success) return fail(commandId, REASON_OPERATION)
        }
        return ok(commandId)
    }

    private fun valueOperation(commandId: String, payload: String): String {
        val block = payload.take(3).toIntOrNull() ?: return fail(commandId, REASON_FORMAT)
        val opMode = payload.getOrNull(3) ?: return fail(commandId, REASON_FORMAT)
        if (block !in MIFARE_BLOCK_RANGE) return fail(commandId, REASON_FORMAT)
        val entity = BlockEntity().apply { setBlkNo(block) }
        val result = when (opMode) {
            '0' -> {
                val value = payload.drop(4).take(8).toLongOrNull(16)?.toInt() ?: return fail(commandId, REASON_FORMAT)
                entity.setBlkValue(value)
                runCatching { m1Card.writeBlockValue(entity) }.getOrDefault(SdkResult.Fail)
            }
            '1',
            '2' -> {
                val value = payload.drop(4).take(8).toLongOrNull(16)?.toInt() ?: return fail(commandId, REASON_FORMAT)
                val transferBlock = payload.drop(12).take(3).toIntOrNull() ?: return fail(commandId, REASON_FORMAT)
                entity.setBlkValue(value)
                entity.setDesBlkNo(transferBlock)
                entity.setOperType(if (opMode == '1') M1CardOperTypeEnum.DECREMENT else M1CardOperTypeEnum.INCREMENT)
                runCatching { m1Card.operateBlock(entity) }.getOrDefault(SdkResult.Fail)
            }
            '3' -> {
                val transferBlock = payload.drop(4).take(3).toIntOrNull() ?: return fail(commandId, REASON_FORMAT)
                entity.setDesBlkNo(transferBlock)
                entity.setOperType(M1CardOperTypeEnum.BACKUP)
                runCatching { m1Card.operateBlock(entity) }.getOrDefault(SdkResult.Fail)
            }
            else -> return fail(commandId, REASON_FORMAT)
        }
        return if (result == SdkResult.Success) ok(commandId) else fail(commandId, valueOperationReason(opMode))
    }

    private fun loadKey(commandId: String, payload: String): String {
        val keyNumber = payload.take(3).toIntOrNull() ?: return fail(commandId, REASON_FORMAT)
        val keyA = payload.drop(3).take(12)
        val keyB = payload.drop(15).take(12)
        if (keyNumber !in MIFARE_KEY_RANGE || !keyA.isHexLength(12) || !keyB.isHexLength(12)) {
            return fail(commandId, REASON_FORMAT)
        }
        prefs.setMifareKey(keyNumber, keyA.uppercase(Locale.US), keyB.uppercase(Locale.US))
        return ok(commandId)
    }

    private fun identify(commandId: String): String {
        refreshTypeAInfo() ?: return fail(commandId, REASON_OPERATION)
        return ok(commandId, mifareTypeCode().toString())
    }

    private fun activateDesfire(commandId: String): String {
        val info = refreshTypeAInfo() ?: return fail(commandId, REASON_OPERATION)
        val type = currentType ?: cardReader.getRfCardNxpType()
        return if (type == RfCardNxpTypeEnum.Desfire) {
            ok(commandId, info.ats.ifBlank { info.uid })
        } else {
            fail(commandId, REASON_OPERATION)
        }
    }

    private fun deselectDesfire(commandId: String): String {
        clearCardState()
        return runCatching {
            cardReader.close(CardSlotTypeEnum.RF)
            if (enabled) cardReader.open(CardSlotTypeEnum.RF)
            ok(commandId)
        }.getOrElse {
            Log.w(TAG, "Unable to deselect DESFire card", it)
            fail(commandId, REASON_OPERATION)
        }
    }

    private fun apduExchange(commandId: String, payload: String): String {
        if (!payload.isHex() || payload.length < 8) return fail(commandId, REASON_FORMAT)
        if (currentUid.isBlank()) refreshTypeAInfo()
        val data = payload.hexToBytesOrNull()
        val response = runCatching {
            if (currentType == RfCardNxpTypeEnum.Desfire || currentType == RfCardNxpTypeEnum.TYPE_A_CPU) {
                rfCpuCard.exchangeAPDUCmd(data)
            } else {
                rawExchange(data)
            }
        }.getOrNull()
        return response?.let { ok(commandId, it.toHex()) } ?: fail(commandId, REASON_OPERATION)
    }

    private fun blockExchange(commandId: String, payload: String): String {
        if (payload.length < 5) return fail(commandId, REASON_FORMAT)
        if (currentUid.isBlank()) refreshTypeAInfo()
        val crcMode = payload[0]
        val waitTime = payload.drop(1).take(4)
        val dataHex = payload.drop(5)
        if (crcMode !in setOf('0', '1') || waitTime.any { !it.isDigit() } || !dataHex.isHex()) {
            return fail(commandId, REASON_FORMAT)
        }
        val response = rawExchange(dataHex.hexToBytesOrNull())
        return response?.let { ok(commandId, it.toHex()) } ?: fail(commandId, REASON_OPERATION)
    }

    private fun readClassicBlock(block: Int): ByteArray? {
        val entity = BlockEntity().apply { setBlkNo(block) }
        val result = runCatching { m1Card.readBlock(entity) }.getOrDefault(SdkResult.Fail)
        return if (result == SdkResult.Success) entity.blkData else null
    }

    private fun writeClassicBlock(block: Int, data: ByteArray): Int {
        val entity = BlockEntity().apply {
            setBlkNo(block)
            setBlkData(data)
        }
        return runCatching { m1Card.writeBlock(entity) }.getOrDefault(SdkResult.Fail)
    }

    private fun readUltralight(block: Int): ByteArray? {
        return runCatching {
            when (currentType) {
                RfCardNxpTypeEnum.MifareUltralight -> ultralightEv1.read(block.toByte())
                else -> ultralightC.readBlock(block.toByte())
            }
        }.getOrNull()
    }

    private fun writeUltralight(block: Int, data: ByteArray): Int {
        return runCatching {
            when (currentType) {
                RfCardNxpTypeEnum.MifareUltralight -> ultralightEv1.write(block.toByte(), data, false)
                else -> ultralightC.writeBlock(block.toByte(), data)
            }
        }.getOrDefault(SdkResult.Fail)
    }

    private fun rawExchange(data: ByteArray): ByteArray? {
        return runCatching {
            when (currentType) {
                RfCardNxpTypeEnum.MifareUltralight -> ultralightEv1.exchangeCmd(data)
                else -> ultralightC.exchangeCmd(data) ?: ntag.exchangeCmd(data)
            }
        }.getOrNull()
    }

    private fun refreshTypeAInfo(): TypeAInfo? {
        if (!enabled) runCatching { cardReader.open(CardSlotTypeEnum.RF) }
        enabled = true
        val info = TypeAInfoEntity()
        val result = runCatching { cardReader.getRfCardInfo(info) }.getOrDefault(SdkResult.Fail)
        val exists = result == SdkResult.Success || runCatching { cardReader.isCardExist(CardSlotTypeEnum.RF) }.getOrDefault(false)
        if (!exists) return null
        val uid = info.uid?.toHex().orEmpty().ifBlank { runCatching { m1Card.readUid().orEmpty() }.getOrDefault("") }
        currentAtqa = info.atqa?.toHex().orEmpty()
        currentSak = "%02X".format(info.sak.toInt() and 0xFF)
        currentUid = uid.uppercase(Locale.US)
        currentAts = info.ats?.toHex().orEmpty()
        currentType = runCatching { cardReader.getRfCardNxpType() }.getOrNull()
        PinpadTraceLog.device(
            "MIFARE RF info result=$result type=$currentType atqa=$currentAtqa sak=$currentSak uidChars=${currentUid.length}",
        )
        return TypeAInfo(currentAtqa, currentSak, currentUid, currentAts)
    }

    private fun isClassicType(): Boolean {
        return when (currentType ?: cardReader.getRfCardNxpType()) {
            RfCardNxpTypeEnum.MifareClassic1K,
            RfCardNxpTypeEnum.MifareClassic4K,
            RfCardNxpTypeEnum.MifareMini,
            RfCardNxpTypeEnum.MifarePlus -> true
            else -> false
        }
    }

    private fun mifareTypeCode(): Char {
        return when (currentType ?: runCatching { cardReader.getRfCardNxpType() }.getOrNull()) {
            RfCardNxpTypeEnum.MifareUltralight -> '1'
            RfCardNxpTypeEnum.MifareClassic1K -> '2'
            RfCardNxpTypeEnum.MifareClassic4K -> '3'
            RfCardNxpTypeEnum.Desfire -> '4'
            RfCardNxpTypeEnum.MifarePlus -> '5'
            RfCardNxpTypeEnum.MifareMini -> '6'
            else -> '0'
        }
    }

    private fun blocksForSector(sector: Int): IntRange? {
        if (sector !in MIFARE_SECTOR_RANGE) return null
        val first = firstBlockOfSector(sector) ?: return null
        val count = if (sector < 32) 4 else 16
        return first until first + count
    }

    private fun firstBlockOfSector(sector: Int): Int? {
        if (sector !in MIFARE_SECTOR_RANGE) return null
        return if (sector < 32) sector * 4 else 128 + ((sector - 32) * 16)
    }

    private fun valueOperationReason(opMode: Char): Char {
        return when (opMode) {
            '0' -> '3'
            '1',
            '2' -> '5'
            '3' -> '6'
            else -> REASON_OPERATION
        }
    }

    private fun clearCardState() {
        currentType = null
        currentAtqa = ""
        currentSak = ""
        currentUid = ""
        currentAts = ""
    }

    private fun ok(commandId: String, data: String = ""): String {
        PinpadTraceLog.device("MIFARE $commandId result=0 dataChars=${data.length}")
        return "0$data"
    }

    private fun fail(commandId: String, reason: Char): String {
        PinpadTraceLog.device("MIFARE $commandId result=1 reason=$reason")
        return "1$reason"
    }

    private fun String.isHexLength(length: Int): Boolean = this.length == length && isHex()

    private fun String.isHex(): Boolean = isNotEmpty() && all { it.digitToIntOrNull(16) != null }

    private fun String.hexToBytesOrNull(): ByteArray {
        return chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }

    private fun ByteArray?.take(count: Int): List<Byte> = this?.toList()?.take(count).orEmpty()

    private fun ByteArray.toHex(): String = joinToString(separator = "") { "%02X".format(it) }

    private data class TypeAInfo(
        val atqa: String,
        val sak: String,
        val uid: String,
        val ats: String,
    )

    private companion object {
        private const val TAG = "PinpadMifareController"
        private const val FS = '\u001C'
        private const val DEFAULT_MIFARE_KEY = "FFFFFFFFFFFF"
        private const val REASON_FORMAT = '1'
        private const val REASON_OPERATION = '3'
        private const val REASON_CARD_NOT_EXISTS = '3'
        private val MIFARE_SECTOR_RANGE = 0..39
        private val MIFARE_BLOCK_RANGE = 0..255
        private val ULTRALIGHT_PAGE_RANGE = 0..47
        private val MIFARE_KEY_RANGE = 0..255
    }
}
