package one.globalconnect.keyinjection.protocol

/**
 * Payload for a serial number request command.
 *
 * @property commandVersion version of the command in ASCII hex
 */
data class SerialNumRequest(
    val commandVersion: String,
)

/**
 * Payload for the "Erase Keys" (05) command.
 *
 * @property commandVersion version of the command in ASCII hex
 */
data class EraseKeysRequest(
    val commandVersion: String,
)

/**
 * Payload for a serial number write command.
 *
 * @property commandVersion version of the command in ASCII hex
 * @property serialNumber device serial number
 */
data class SerialNumWrite(
    val commandVersion: String,
    val serialNumber: String,
)

/**
 * Payload for the "Inject Master Session Key" (01) command.
 *
 * @property keySlot two digit key slot number in ASCII hex, "00" if the device has only one key location
 * @property key 32 or 48 digit key payload in ASCII hex
 * @property calculatedKCV calculated key check value for the injected key
 */
data class MKInject(
    val keySlot: String,
    val key: String,
    val calculatedKCV: String,
)

/**
 * Payload for the "Inject DUKPT Key" (00) command.
 *
 * @property keySlot two digit key slot number in ASCII hex, "00" if the device has only one key location
 * @property iKsn 20 digit Key Serial Number (KSN) in ASCII hex (all zeros if the KSN is not used)
 * @property iPek 32 or 48 digit key payload in ASCII hex
 * @property calculatedKCV calculated key check value for the injected key
 */
data class DUKPTInject(
    val keySlot: String,
    val iKsn: String,
    val iPek: String,
    val calculatedKCV: String,
)

/**
 * Payload for the "Inject Key Under KTK" (02) command.
 *
 * To inject symmetric keys into the terminal, the SKI Series sends this command and
 * receives a corresponding response packet.
 *
 * Key Type Table:
 * ``
 * Type   Name                        Modifier
 * 0x01   Master Session Key          0
 * 0x02   DUKPT Initial Key           8
 * 0x03   DUKPT BDK Key               8
 * 0x04   MAC Key                     3
 * 0x05   PIN Encryption Key          1
 * 0x06   Key Exchange Key            0
 * 0x07   Host Verification KTK       0
 * 0x08   DUKPT 3DES BDK Key          8
 * 0x09   Default KTK                 0
 * 0x0A   Balance decryption Key      0
 * 0x0B   TDR DUKPT BDK               0
 * ```
 *
 * @property commandVersion version of the command in ASCII hex
 * @property keySlot two digit key slot number in ASCII hex, "00" if the device has only one key location
 * @property ktkSlot two digit KTK slot number in ASCII hex, "00" if the device has only one key location or if no KTK is sent
 * @property keyType identifier of key type in ASCII hex
 * @property keyEncryption identifier if key is clear key load ("00"), encrypted under a preloaded KTK ("01"), or clear KTK ("02")
 * @property keyChecksum four digit ASCII hex checksum of incoming key
 * @property ktkChecksum four digit ASCII hex checksum of the KTK (all zeros if not used)
 * @property ksn 20 digit Key Serial Number in ASCII hex (all zeros if KSN is not used)
 * @property keyLength length of incoming key or key block in ASCII hex ("010", "020", "030", etc.)
 * @property keyPayload key or key block value; 16, 32, or 48 digit ASCII hex key or TR-31A key block
 * @property ktkLength length of KTK in ASCII hex ("010", "020", "030"), all zeros if [keyEncryption] is not "02"
 * @property ktkPayload 16, 32, or 48 digit ASCII hex key (only used if [keyEncryption] is "02")
 */
data class KTKEncryptedKeyInject(
    val commandVersion: String,
    val keySlot: String,
    val ktkSlot: String,
    val keyType: String,
    val keyEncryption: String,
    val keyChecksum: String,
    val ktkChecksum: String,
    val ksn: String,
    val keyLength: String,
    val keyPayload: String,
    val ktkLength: String,
    val ktkPayload: String,
    //val calculatedKCV: String,
)
{
    /**
     * Resolve the PED key type associated with the [keyType] identifier.
     *
     * @return matching [EPedKeyType] or `EPedKeyType.None` when not applicable
     */
    fun keyType() : EPedKeyType
    {
        return when (keyType) {
            "01" -> EPedKeyType.TMK
            "02" -> EPedKeyType.TIK
            "03" -> EPedKeyType.TIK
            "04" -> EPedKeyType.TAK
            "05" -> EPedKeyType.TPK
            "06" -> EPedKeyType.TLK
            "07" -> EPedKeyType.None
            "08" -> EPedKeyType.TIK
            "09" -> EPedKeyType.TLK
            "0A" -> EPedKeyType.None
            "0B" -> EPedKeyType.None
            else -> EPedKeyType.None
        }
    }
    /**
     * Human readable label describing the key specified by [keyType].
     *
     * @return string name of the key type or `null` when unknown
     */
    fun keyTypeName() : String?
    {
        return when (keyType) {
            "01" -> "TMK"
            "02" -> "TIK"
            "03" -> "TIK"
            "04" -> "TAK"
            "05" -> "TPK"
            "06" -> "TLK"
            "07" -> "null"
            "08" -> "TIK"
            "09" -> "TLK"
            "0A" -> "null"
            "0B" -> "null"
            else -> "null"
        }
    }

}
