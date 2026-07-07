package com.uic.pos.iso8583.exception;

/**
 * Base exception thrown by the ISO 8583 module when a configuration or runtime error occurs.
 *
 * @author UIC
 */
public class Iso8583Exception extends Exception {

    /**
     * Creates a new exception with the supplied message.
     *
     * @param message a human readable description of the failure.
     */
    public Iso8583Exception(String message) {
        super(message);
    }

    /**
     * Creates a new exception with a root cause.
     *
     * @param message a human readable description of the failure.
     * @param cause   the originating exception.
     */
    public Iso8583Exception(String message, Throwable cause) {
        super(message, cause);
    }
}
