package com.uic.pos.iso8583;

import com.uic.pos.iso8583.exception.Iso8583Exception;
import com.uic.pos.iso8583.exception.Iso8583ParseException;
import com.uic.pos.iso8583.parse.AlphaFieldParser;
import com.uic.pos.iso8583.parse.HexFieldParser;
import com.uic.pos.iso8583.parse.NumericFieldParser;
import java.io.IOException;
import java.io.OutputStream;

/**
 * Describes how to parse and serialise a specific ISO 8583 field. Subclasses implement the
 * encoding logic for the supported field types.
 *
 * @author UIC
 */
public abstract class IsoFieldParser {
    private final IsoFieldType fieldType;
    private final IsoAlign align;
    private final int maxLength;
    private final IsoLengthType lengthType;
    private final int lengthIndicatorSize;
    private final byte paddingByte;

    /**
     * Creates a parser instance.
     *
     * @param fieldType           the data type of the field.
     * @param align               the alignment strategy for fixed length fields.
     * @param maxLength           the maximum number of characters or bytes that can be stored.
     * @param lengthType          the encoding used for the length indicator.
     * @param lengthIndicatorSize the number of bytes used for the length indicator (0 for fixed length fields).
     * @param paddingByte         the padding byte used for fixed length fields.
     */
    protected IsoFieldParser(
            IsoFieldType fieldType,
            IsoAlign align,
            int maxLength,
            IsoLengthType lengthType,
            int lengthIndicatorSize,
            byte paddingByte) {
        this.fieldType = fieldType;
        this.align = align;
        this.maxLength = maxLength;
        this.lengthType = lengthType;
        this.lengthIndicatorSize = lengthIndicatorSize;
        this.paddingByte = paddingByte;
    }

    /**
     * Returns the declared field type.
     *
     * @return the {@link IsoFieldType} associated with this parser.
     */
    public IsoFieldType getFieldType() {
        return fieldType;
    }

    /**
     * Returns the alignment strategy for fixed length fields.
     *
     * @return the configured {@link IsoAlign} value.
     */
    public IsoAlign getAlign() {
        return align;
    }

    /**
     * Returns the maximum number of characters or bytes the field can hold.
     *
     * @return the configured maximum length.
     */
    public int getMaxLength() {
        return maxLength;
    }

    /**
     * Returns the encoding used for the length indicator.
     *
     * @return the {@link IsoLengthType} used for variable length fields.
     */
    public IsoLengthType getLengthType() {
        return lengthType;
    }

    /**
     * Returns the number of bytes used to encode the length indicator.
     *
     * @return zero for fixed length fields or the indicator size for variable length fields.
     */
    public int getLengthIndicatorSize() {
        return lengthIndicatorSize;
    }

    /**
     * Returns the padding byte used when the value does not occupy the full field width.
     *
     * @return the byte used for padding.
     */
    public byte getPaddingByte() {
        return paddingByte;
    }

    /**
     * Parses the field value from the provided buffer.
     *
     * @param fieldNumber the ISO 8583 field number being parsed.
     * @param buffer      the raw message bytes.
     * @param offset      the offset within {@code buffer} where the field starts.
     * @return the parsed field value.
     * @throws Iso8583ParseException if the value cannot be parsed.
     */
    public abstract IsoFieldValue parse(int fieldNumber, byte[] buffer, int offset) throws Iso8583ParseException;

    /**
     * Writes the value to the provided output stream.
     *
     * @param outputStream the destination stream.
     * @param value        the textual representation of the field.
     * @throws IOException      if the value cannot be written to the stream.
     * @throws Iso8583Exception if the value does not respect the field definition.
     */
    public abstract void write(OutputStream outputStream, String value) throws IOException, Iso8583Exception;

    /**
     * Factory method that selects the correct parser implementation for the supplied configuration.
     *
     * @param fieldType           the data type of the field.
     * @param align               the alignment strategy for fixed length fields.
     * @param maxLength           the maximum number of characters or bytes the field can contain.
     * @param lengthType          the encoding used for the length indicator (ignored for fixed length fields).
     * @param lengthIndicatorSize the number of bytes used to encode the field length.
     * @param hasCustomPadding    {@code true} if a custom padding byte has been configured.
     * @param paddingByte         the padding byte to use if {@code hasCustomPadding} is {@code true}.
     * @return a parser ready to decode the field.
     */
    public static IsoFieldParser create(
            IsoFieldType fieldType,
            IsoAlign align,
            int maxLength,
            IsoLengthType lengthType,
            int lengthIndicatorSize,
            boolean hasCustomPadding,
            byte paddingByte) {
        byte effectivePadding = hasCustomPadding ? paddingByte : getDefaultPadding(fieldType);
        switch (fieldType) {
            case ALPHA:
                return new AlphaFieldParser(fieldType, align, maxLength, lengthType, lengthIndicatorSize, effectivePadding);
            case NUMERIC:
                return new NumericFieldParser(fieldType, align, maxLength, lengthType, lengthIndicatorSize, effectivePadding);
            case HEX:
            default:
                return new HexFieldParser(fieldType, align, maxLength, lengthType, lengthIndicatorSize, effectivePadding);
        }
    }

    private static byte getDefaultPadding(IsoFieldType fieldType) {
        return fieldType == IsoFieldType.ALPHA ? (byte) ' ' : (byte) 0x00;
    }
}
