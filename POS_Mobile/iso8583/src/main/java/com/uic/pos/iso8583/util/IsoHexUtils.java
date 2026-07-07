package com.uic.pos.iso8583.util;

import java.util.Arrays;

/**
 * Helper methods for encoding and decoding hexadecimal values.
 *
 * @author UIC
 */
public final class IsoHexUtils {
    private static final char[] HEX_DIGITS = "0123456789ABCDEF".toCharArray();

    private IsoHexUtils() {
    }

    /**
     * Encodes the supplied byte array into an uppercase hexadecimal string.
     *
     * @param data   the source byte array.
     * @param offset the starting offset within {@code data}.
     * @param length the number of bytes to encode.
     * @return the hexadecimal representation of the requested slice.
     */
    public static String encodeHex(byte[] data, int offset, int length) {
        if (length == 0) {
            return "";
        }

        char[] chars = new char[length * 2];
        for (int i = 0; i < length; i++) {
            int value = data[offset + i] & 0xFF;
            chars[i * 2] = HEX_DIGITS[value >>> 4];
            chars[i * 2 + 1] = HEX_DIGITS[value & 0x0F];
        }
        return new String(chars);
    }

    /**
     * Decodes a hexadecimal string into a byte array.
     *
     * @param hex the string to decode.
     * @return the decoded byte array (never {@code null}).
     */
    public static byte[] decodeHex(String hex) {
        if (hex == null || hex.isEmpty()) {
            return new byte[0];
        }

        String normalised = hex.length() % 2 == 0 ? hex : "0" + hex;
        byte[] buffer = new byte[normalised.length() / 2];
        for (int i = 0; i < buffer.length; i++) {
            int high = Character.digit(normalised.charAt(i * 2), 16);
            int low = Character.digit(normalised.charAt(i * 2 + 1), 16);
            if (high < 0 || low < 0) {
                throw new IllegalArgumentException("Value is not hexadecimal: " + hex);
            }
            buffer[i] = (byte) ((high << 4) | low);
        }
        return buffer;
    }

    /**
     * Encodes an integer into a big-endian binary array of the requested length.
     *
     * @param value  the integer value to encode.
     * @param length the number of bytes that should be produced.
     * @return a big-endian representation of {@code value}.
     */
    public static byte[] intToBinary(int value, int length) {
        byte[] result = new byte[length];
        for (int i = length - 1; i >= 0; i--) {
            result[i] = (byte) (value & 0xFF);
            value >>>= 8;
        }
        return result;
    }

    /**
     * Encodes an integer into an ASCII character array with zero padding on the left.
     *
     * @param value  the value to encode.
     * @param length the desired number of characters.
     * @return an ASCII representation of the supplied value.
     */
    public static byte[] intToAscii(int value, int length) {
        byte[] result = new byte[length];
        for (int i = length - 1; i >= 0; i--) {
            result[i] = (byte) ('0' + (value % 10));
            value /= 10;
        }
        return result;
    }

    /**
     * Encodes an integer into packed BCD.
     *
     * @param value  the value to encode.
     * @param length the number of bytes expected in the result.
     * @return the packed BCD representation.
     */
    public static byte[] intToPackedBcd(int value, int length) {
        byte[] result = new byte[length];
        for (int i = length - 1; i >= 0; i--) {
            int low = value % 10;
            value /= 10;
            int high = value % 10;
            value /= 10;
            result[i] = (byte) ((high << 4) | low);
        }
        return result;
    }

    /**
     * Pads a byte array to the requested length using the supplied padding byte and alignment.
     *
     * @param value        the value to pad.
     * @param targetLength the desired length of the result.
     * @param align        the alignment to use when copying the data.
     * @param paddingByte  the padding byte that should fill unused positions.
     * @return an array with length {@code targetLength} containing {@code value} at the correct alignment.
     */
    public static byte[] pad(byte[] value, int targetLength, com.uic.pos.iso8583.IsoAlign align, byte paddingByte) {
        byte[] result = new byte[targetLength];
        Arrays.fill(result, paddingByte);
        if (value.length == 0) {
            return result;
        }
        if (align == com.uic.pos.iso8583.IsoAlign.LEFT) {
            System.arraycopy(value, 0, result, 0, Math.min(value.length, targetLength));
        } else {
            System.arraycopy(value, 0, result, Math.max(0, targetLength - value.length), Math.min(value.length, targetLength));
        }
        return result;
    }
}
