package com.uic.pos.iso8583.util;

import com.uic.pos.iso8583.IsoAlign;
import com.uic.pos.iso8583.IsoLengthType;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Utility functions for converting between ISO 8583 data representations.
 *
 * @author UIC
 */
public final class IsoByteUtils {
    private IsoByteUtils() {
    }

    /**
     * Converts the supplied string into an ASCII encoded byte array.
     *
     * @param value the value to convert.
     * @return an ASCII encoded byte array.
     */
    public static byte[] toAscii(String value) {
        return value.getBytes(StandardCharsets.US_ASCII);
    }

    /**
     * Converts ASCII encoded digits into an integer.
     *
     * @param ascii the ASCII characters representing a number.
     * @return the integer value, or {@code 0} if the input is not numeric.
     */
    public static int asciiToInt(byte[] ascii) {
        int result = 0;
        for (byte b : ascii) {
            if (b < '0' || b > '9') {
                return 0;
            }
            result = result * 10 + (b - '0');
        }
        return result;
    }

    /**
     * Converts packed BCD into an integer value.
     *
     * @param bcd the packed BCD bytes.
     * @return the integer value represented by {@code bcd}.
     */
    public static int bcdToInt(byte[] bcd) {
        int result = 0;
        for (byte b : bcd) {
            int high = (b >>> 4) & 0x0F;
            int low = b & 0x0F;
            result = result * 100 + high * 10 + low;
        }
        return result;
    }

    /**
     * Converts a big-endian binary array into an integer.
     *
     * @param binary the binary representation.
     * @return the integer value.
     */
    public static int binaryToInt(byte[] binary) {
        int result = 0;
        for (byte b : binary) {
            result = (result << 8) | (b & 0xFF);
        }
        return result;
    }

    /**
     * Converts the supplied buffer slice into an integer based on the requested length encoding.
     *
     * @param buffer the raw buffer containing the data.
     * @param offset the starting offset.
     * @param length the number of bytes to consume.
     * @param type   the encoding used for the length indicator.
     * @return the decoded value, or {@code -1} if the slice exceeds the buffer bounds.
     */
    public static int bytesToInt(byte[] buffer, int offset, int length, IsoLengthType type) {
        if (offset + length > buffer.length) {
            return -1;
        }
        byte[] slice = Arrays.copyOfRange(buffer, offset, offset + length);
        switch (type) {
            case ASC:
                return asciiToInt(slice);
            case HEX:
                return binaryToInt(slice);
            case BCD:
            default:
                return bcdToInt(slice);
        }
    }

    /**
     * Encodes an integer value according to the requested length encoding scheme.
     *
     * @param value  the integer to encode.
     * @param type   the encoding to use.
     * @param length the number of bytes that should be produced.
     * @return the encoded representation.
     */
    public static byte[] intToBytes(int value, IsoLengthType type, int length) {
        switch (type) {
            case ASC:
                return IsoHexUtils.intToAscii(value, length);
            case HEX:
                return IsoHexUtils.intToBinary(value, length);
            case BCD:
            default:
                return IsoHexUtils.intToPackedBcd(value, length);
        }
    }

    /**
     * Converts packed BCD data to its string representation while respecting alignment rules.
     *
     * @param bcd    the packed BCD bytes.
     * @param align  the alignment used when the data was stored.
     * @param digits the number of digits requested by the field definition.
     * @return the textual representation of the numeric field.
     */
    public static String bcdToString(byte[] bcd, IsoAlign align, int digits) {
        String value = IsoHexUtils.encodeHex(bcd, 0, bcd.length);
        return align == IsoAlign.LEFT ? value.substring(0, Math.min(digits, value.length()))
                : value.substring(Math.max(0, value.length() - digits));
    }

    /**
     * Converts a string containing numeric digits into packed BCD while respecting the supplied
     * alignment and padding rules.
     *
     * @param digits       the numeric value to encode.
     * @param align        the alignment used for the target field.
     * @param paddingByte  the padding byte configured for the field.
     * @return the packed BCD representation.
     */
    public static byte[] stringToBcd(String digits, IsoAlign align, byte paddingByte) {
        if (digits == null) {
            digits = "";
        }
        String trimmed = digits.trim();
        String padding = String.format("%02X", paddingByte);
        if (trimmed.length() % 2 != 0) {
            trimmed = align == IsoAlign.LEFT ? trimmed + padding.charAt(1) : padding.charAt(0) + trimmed;
        }
        return IsoHexUtils.decodeHex(trimmed);
    }
}
