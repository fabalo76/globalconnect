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
 * Parser implementation for numeric ISO 8583 fields stored as packed BCD.
 *
 * @author UIC
 */
public final class NumericFieldParser extends IsoFieldParser {

    /**
     * Creates a parser for a numeric field.
     */
    public NumericFieldParser(
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

        int digits = getMaxLength();
        if (prefixSize > 0) {
            digits = IsoByteUtils.bytesToInt(buffer, offset, prefixSize, getLengthType());
        }

        if (digits < 0 || digits > getMaxLength()) {
            throw new Iso8583ParseException(
                    "Field " + fieldNumber + " declares length " + digits + " which exceeds the configured maximum "
                            + getMaxLength());
        }

        int bytesRequired = (digits + 1) / 2;
        if (offset + prefixSize + bytesRequired > buffer.length) {
            throw new Iso8583ParseException("Field " + fieldNumber + " exceeds available data in the buffer");
        }

        byte[] data = Arrays.copyOfRange(buffer, offset + prefixSize, offset + prefixSize + bytesRequired);
        String value = IsoByteUtils.bcdToString(data, getAlign(), digits);
        IsoFieldValue fieldValue = new IsoFieldValue(this, value);
        fieldValue.setParseOffset(prefixSize + bytesRequired);
        return fieldValue;
    }

    @Override
    public void write(OutputStream outputStream, String value) throws IOException, Iso8583Exception {
        if (value == null) {
            value = "";
        }
        String digits = value.trim();
        if (digits.length() > getMaxLength()) {
            digits = digits.substring(0, getMaxLength());
        }
        digits = digits.toUpperCase(Locale.US);
        for (int i = 0; i < digits.length(); i++) {
            if (Character.digit(digits.charAt(i), 16) == -1) {
                throw new Iso8583Exception("Numeric field accepts digits only");
            }
        }

        byte[] packed = IsoByteUtils.stringToBcd(digits, getAlign(), getPaddingByte());
        int prefixSize = getLengthIndicatorSize();

        if (prefixSize > 0) {
            outputStream.write(IsoByteUtils.intToBytes(digits.length(), getLengthType(), prefixSize));
            outputStream.write(packed);
            return;
        }

        int targetLength = (getMaxLength() + 1) / 2;
        byte[] fieldBytes = IsoHexUtils.pad(packed, targetLength, getAlign(), getPaddingByte());
        outputStream.write(fieldBytes);
    }
}
