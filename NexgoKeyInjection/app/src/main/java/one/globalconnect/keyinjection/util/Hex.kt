package one.globalconnect.keyinjection.util

/** Converts a hexadecimal string to a byte array. */
fun String.hexToByteArray(): ByteArray =
    chunked(2).map { it.toInt(16).toByte() }.toByteArray()

/** Converts a two-character hexadecimal string to a single byte. */
fun String.hexToByte(): Byte =
    toInt(16).toByte()

/** Converts a two-character hexadecimal string to int. */
fun String.hexToInt(): Int =
    toInt(16)
