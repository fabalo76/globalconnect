package com.uic.pos.iso8583.parse;

import com.uic.pos.iso8583.IsoAlign;
import com.uic.pos.iso8583.IsoFieldParser;
import com.uic.pos.iso8583.IsoFieldType;
import com.uic.pos.iso8583.IsoFieldValue;
import com.uic.pos.iso8583.IsoLengthType;
import com.uic.pos.iso8583.exception.Iso8583Exception;
import com.uic.pos.iso8583.exception.Iso8583ParseException;
import com.uic.pos.iso8583.util.IsoByteUtils;
import com.uic.pos.iso8583.util.IsoHexUtils;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.Locale;

/**
 * Parser implementation for binary ISO 8583 fields represented as hexadecimal strings.
 *
 * @author UIC
 */
public final class HexFieldParser extends IsoFieldParser {

    /**
     * Creates a parser for a hex encoded field.
     */
    public HexFieldParser(
            IsoFieldType fieldType,
            IsoAlign align,
            int maxLength,
            IsoLengthType lengthType,
            int lengthIndicatorSize,
            byte paddingByte) {
        super(fieldType, align, maxLength, lengthType, lengthIndicatorSize, paddingByte);
    }

    @Override
    public IsoFieldValue parse(int fieldNumber, byte[] buffer, int offset) throws Iso8583ParseException {
        if (offset < 0 || offset >= buffer.length) {
            throw new Iso8583ParseException("Field " + fieldNumber + " offset " + offset + " is outside of the buffer");
        }

        int prefixSize = getLengthIndicatorSize();
        if (offset + prefixSize > buffer.length) {
            throw new Iso8583ParseException("Field " + fieldNumber + " length indicator exceeds buffer capacity");
        }

        int bytes = getMaxLength();
        if (prefixSize > 0) {
            bytes = IsoByteUtils.bytesToInt(buffer, offset, prefixSize, getLengthType());
        }

        if (bytes < 0 || bytes > getMaxLength()) {
            throw new Iso8583ParseException(
                    "Field " + fieldNumber + " declares length " + bytes + " which exceeds the configured maximum "
                            + getMaxLength());
        }

        if (offset + prefixSize + bytes > buffer.length) {
            throw new Iso8583ParseException("Field " + fieldNumber + " exceeds available data in the buffer");
        }

        byte[] data = Arrays.copyOfRange(buffer, offset + prefixSize, offset + prefixSize + bytes);
        String value = IsoHexUtils.encodeHex(data, 0, data.length);
        int expectedChars = bytes * 2;
        value = getAlign() == IsoAlign.LEFT
                ? value.substring(0, Math.min(expectedChars, value.length()))
                : value.substring(Math.max(0, value.length() - expectedChars));
        IsoFieldValue fieldValue = new IsoFieldValue(this, value);
        fieldValue.setParseOffset(prefixSize + bytes);
        return fieldValue;
    }

    @Override
    public void write(OutputStream outputStream, String value) throws IOException, Iso8583Exception {
        if (value == null) {
            value = "";
        }
        String normalised = value.replace(" ", "").toUpperCase(Locale.US);
        if (normalised.length() > getMaxLength() * 2) {
            normalised = normalised.substring(0, getMaxLength() * 2);
        }

        byte[] hexBytes;
        try {
            hexBytes = toHexBytes(normalised);
        } catch (IllegalArgumentException e) {
            throw new Iso8583Exception("Hex field contains invalid characters", e);
        }
        int prefixSize = getLengthIndicatorSize();
        if (prefixSize > 0) {
            outputStream.write(IsoByteUtils.intToBytes(hexBytes.length, getLengthType(), prefixSize));
            outputStream.write(hexBytes);
            return;
        }

        byte[] fieldBytes = IsoHexUtils.pad(hexBytes, getMaxLength(), getAlign(), getPaddingByte());
        outputStream.write(fieldBytes);
    }

    private byte[] toHexBytes(String value) {
        String trimmed = value;
        if (trimmed.length() % 2 != 0) {
            String padding = String.format(Locale.US, "%02X", getPaddingByte());
            trimmed = getAlign() == IsoAlign.LEFT ? trimmed + padding.charAt(1) : padding.charAt(0) + trimmed;
        }
        return IsoHexUtils.decodeHex(trimmed);
    }
}
