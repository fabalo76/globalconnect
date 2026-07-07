package com.uic.pos.iso8583;

/**
 * Describes the data encoding that should be used when serialising or parsing an ISO 8583 field.
 * The type influences how both the value and the field length indicator are interpreted.
 *
 * @author UIC
 */
public enum IsoFieldType {
    /**
     * The field contains alphanumeric characters that are stored as ASCII/UTF-8 bytes.
     */
    ALPHA,

    /**
     * The field contains numeric digits that are stored using packed BCD encoding.
     */
    NUMERIC,

    /**
     * The field contains raw binary data that is represented as hexadecimal characters.
     */
    HEX
}
