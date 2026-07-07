package com.uic.pos.iso8583.parse;

import com.uic.pos.iso8583.IsoAlign;
import com.uic.pos.iso8583.IsoFieldParser;
import com.uic.pos.iso8583.IsoFieldType;
import com.uic.pos.iso8583.IsoFieldValue;
import com.uic.pos.iso8583.IsoLengthType;
import com.uic.pos.iso8583.exception.Iso8583Exception;
import com.uic.pos.iso8583.exception.Iso8583ParseException;
import com.uic.pos.iso8583.util.IsoByteUtils;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Parser implementation for alphanumeric ISO 8583 fields. These fields are stored as ASCII bytes
 * and can either be fixed length or contain a prefix that indicates the actual size.
 *
 * @author UIC
 */
public final class AlphaFieldParser extends IsoFieldParser {

    /**
     * Creates a parser for an alphanumeric field.
     */
    public AlphaFieldParser(
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

        int valueLength = getMaxLength();
        if (prefixSize > 0) {
            valueLength = IsoByteUtils.bytesToInt(buffer, offset, prefixSize, getLengthType());
        }

        if (valueLength < 0 || valueLength > getMaxLength()) {
            throw new Iso8583ParseException(
                    "Field " + fieldNumber + " declares length " + valueLength + " which exceeds the configured maximum "
                            + getMaxLength());
        }

        if (offset + prefixSize + valueLength > buffer.length) {
            throw new Iso8583ParseException("Field " + fieldNumber + " exceeds available data in the buffer");
        }

        byte[] data = Arrays.copyOfRange(buffer, offset + prefixSize, offset + prefixSize + valueLength);
        String value = new String(data, StandardCharsets.US_ASCII);
        IsoFieldValue fieldValue = new IsoFieldValue(this, value);
        fieldValue.setParseOffset(prefixSize + valueLength);
        return fieldValue;
    }

    @Override
    public void write(OutputStream outputStream, String value) throws IOException {
        if (value == null) {
            value = "";
        }

        String truncated = value.substring(0, Math.min(getMaxLength(), value.length()));
        byte[] ascii = truncated.getBytes(StandardCharsets.US_ASCII);
        int prefixSize = getLengthIndicatorSize();

        if (prefixSize > 0) {
            outputStream.write(IsoByteUtils.intToBytes(ascii.length, getLengthType(), prefixSize));
            outputStream.write(ascii);
            return;
        }

        byte[] fieldBytes = new byte[getMaxLength()];
        Arrays.fill(fieldBytes, getPaddingByte());
        if (getAlign() == IsoAlign.LEFT) {
            System.arraycopy(ascii, 0, fieldBytes, 0, ascii.length);
        } else {
            System.arraycopy(ascii, 0, fieldBytes, fieldBytes.length - ascii.length, ascii.length);
        }
        outputStream.write(fieldBytes);
    }
}
