package com.uic.pos.iso8583;

/**
 * Indicates the encoding used for variable length prefixes in ISO 8583 messages.
 *
 * @author UIC
 */
public enum IsoLengthType {
    /**
     * The length value is stored as binary coded decimal (packed BCD).
     */
    BCD,

    /**
     * The length value is stored as ASCII characters.
     */
    ASC,

    /**
     * The length value is stored using binary big-endian bytes.
     */
    HEX
}
