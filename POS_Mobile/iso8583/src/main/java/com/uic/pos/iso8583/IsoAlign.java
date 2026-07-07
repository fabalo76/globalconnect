package com.uic.pos.iso8583;

/**
 * Enumerates the alignment strategies that can be applied to fixed length ISO 8583 fields.
 * Alignment is only relevant when the field value does not fill the maximum number of
 * characters defined in the message specification.
 *
 * @author UIC
 */
public enum IsoAlign {
    /**
     * Pad the remaining characters on the right hand side so that the value starts at the
     * first position of the field.
     */
    LEFT,

    /**
     * Pad the remaining characters on the left hand side so that the value ends at the
     * last position of the field.
     */
    RIGHT
}
