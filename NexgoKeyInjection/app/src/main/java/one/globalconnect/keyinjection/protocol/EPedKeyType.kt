package one.globalconnect.keyinjection.protocol

/**
 * Enumeration of key types recognized by the PED (Pin Entry Device) along with
 * their corresponding numeric identifiers.
 *
 * Each constant maps to a specific cryptographic key type used in secure
 * transaction environments.
 *
 * @property pedkeyType The byte value that uniquely identifies the key type
 *                      within the PED.
 */
enum class EPedKeyType(val pedkeyType: Byte) {

    /** No key type defined. */
    None(0.toByte()),

    /** Terminal Load Key (TLK). */
    TLK(1.toByte()),

    /** Terminal Master Key (TMK). */
    TMK(2.toByte()),

    /** Terminal PIN Key (TPK). */
    TPK(3.toByte()),

    /** Terminal Authentication Key (TAK). */
    TAK(4.toByte()),

    /** Terminal Data Key (TDK). */
    TDK(5.toByte()),

    /** Terminal DUKPT Key (TIK). */
    TIK(7.toByte()),

    /** SM2 public key for encryption/verification. */
    SM2_PUB_KEY(49.toByte()),

    /** SM2 private key for decryption/signing. */
    SM2_PVT_KEY(48.toByte()),

    /** SM4 Terminal Authentication Key (TAK). */
    SM4_TAK(52.toByte()),

    /** SM4 Terminal Data Key (TDK). */
    SM4_TDK(53.toByte()),

    /** SM4 Terminal Master Key (TMK). */
    SM4_TMK(50.toByte()),

    /** SM4 Terminal PIN Key (TPK). */
    SM4_TPK(51.toByte()),

    /** AES Session Key (TAESK). */
    TAESK(32.toByte()),

    /** PED AES Terminal Data Key. */
    PED_AES_TDK(32.toByte()),

    /** AES Terminal Master Key. */
    AES_TMK(34.toByte()),

    /** AES Terminal PIN Key. */
    AES_TPK(35.toByte()),

    /** AES Terminal Authentication Key. */
    AES_TAK(36.toByte()),

    /** AES Check Digit Key (TCHDK). */
    AES_TCHDK(38.toByte()),

    /** AES Terminal Issuer Key. */
    AES_TIK(81.toByte()),

    /** PPAD Terminal Master Key. */
    PPAD_TMK(67.toByte()),

    /** PPAD Terminal PIN Key. */
    PPAD_TPK(68.toByte()),

    /** PED Terminal Migration Key (TM1K). */
    PED_TM1K(73.toByte()),

    /** Face recognition Terminal Data Key. */
    FACE_TDK(17.toByte()),

    /** SM4 AES Check Digit Key (TCHDK). */
    SM4_TCHDK(54.toByte()),

    /** SM4 Face recognition Terminal Data Key. */
    SM4_FACE_TDK(55.toByte()),

    /** Key type used for secure data storage. */
    SECURE_DATA(76.toByte()),

    /** AES PPAD Terminal PIN Key. */
    AES_PPAD_TPK(42.toByte()),

    /** PED Terminal Issuer Derived Key (TIDK). */
    PED_TIDK(69.toByte())
}
