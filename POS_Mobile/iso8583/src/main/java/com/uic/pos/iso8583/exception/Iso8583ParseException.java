package com.uic.pos.iso8583.exception;

/**
 * Thrown when a byte buffer cannot be parsed into an ISO 8583 message because the payload does not
 * match the configured field definitions.
 *
 * @author UIC
 */
public class Iso8583ParseException extends Iso8583Exception {

    /**
     * Creates a new exception with the supplied message.
     *
     * @param message a human readable description of the failure.
     */
    public Iso8583ParseException(String message) {
        super(message);
    }

    /**
     * Creates a new exception with the underlying cause attached.
     *
     * @param message a human readable description of the failure.
     * @param cause   the originating exception.
     */
    public Iso8583ParseException(String message, Throwable cause) {
        super(message, cause);
    }
}
