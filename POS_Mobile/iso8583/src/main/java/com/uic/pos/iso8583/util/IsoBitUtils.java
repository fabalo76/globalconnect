package com.uic.pos.iso8583.util;

/**
 * Utility methods for manipulating bitmaps represented as byte arrays.
 *
 * @author UIC
 */
public final class IsoBitUtils {
    private IsoBitUtils() {
    }

    /**
     * Reads the value of the bit at the specified index.
     *
     * @param buffer the bitmap stored as a byte array.
     * @param index  the bit index (zero based).
     * @return {@code true} if the bit is set, {@code false} otherwise.
     */
    public static boolean getBit(byte[] buffer, int index) {
        int byteIndex = index / 8;
        int bitIndex = 7 - (index % 8);
        return (buffer[byteIndex] & (1 << bitIndex)) != 0;
    }

    /**
     * Updates the bit at the specified index to the provided value.
     *
     * @param buffer the bitmap stored as a byte array.
     * @param index  the bit index (zero based).
     * @param value  the desired bit value.
     */
    public static void setBit(byte[] buffer, int index, boolean value) {
        int byteIndex = index / 8;
        int bitIndex = 7 - (index % 8);
        if (value) {
            buffer[byteIndex] |= 1 << bitIndex;
        } else {
            buffer[byteIndex] &= ~(1 << bitIndex);
        }
    }
}
