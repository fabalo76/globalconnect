package com.uic.pos.iso8583;

import com.uic.pos.iso8583.exception.Iso8583Exception;
import java.io.IOException;
import java.io.OutputStream;

/**
 * Represents the runtime value of a single ISO 8583 field. A field is backed by a parser definition
 * describing its type, alignment and maximum length. The value can be written back to an output
 * stream using the attached parser definition.
 *
 * @author UIC
 */
public final class IsoFieldValue {
    private final IsoFieldParser parser;
    private String value;
    private int parseOffset;

    /**
     * Creates a new field value for the supplied parser definition.
     *
     * @param parser the parser that knows how to serialise the field value.
     * @param value  the decoded field contents.
     */
    public IsoFieldValue(IsoFieldParser parser, String value) {
        this.parser = parser;
        this.value = value;
    }

    /**
     * Returns the decoded value.
     *
     * @return the field contents as a string representation.
     */
    public String getValue() {
        return value;
    }

    /**
     * Updates the decoded value.
     *
     * @param value the new field contents.
     */
    public void setValue(String value) {
        this.value = value;
    }

    /**
     * Writes the field to the provided {@link OutputStream} using the parser definition supplied at
     * construction time.
     *
     * @param outputStream the destination stream.
     * @throws IOException        if the data cannot be written.
     * @throws Iso8583Exception   if the value breaches the ISO 8583 definition.
     */
    public void write(OutputStream outputStream) throws IOException, Iso8583Exception {
        parser.write(outputStream, value);
    }

    /**
     * Returns the number of bytes consumed when this field was parsed from an input buffer. The
     * value includes the length indicator for variable length fields.
     *
     * @return the number of bytes that were consumed.
     */
    public int getParseOffset() {
        return parseOffset;
    }

    /**
     * Updates the number of bytes consumed during parsing.
     *
     * @param parseOffset the total number of bytes consumed.
     */
    public void setParseOffset(int parseOffset) {
        this.parseOffset = parseOffset;
    }

    /**
     * Returns the parser configuration associated with this value.
     *
     * @return the ISO 8583 parser used to encode/decode the field.
     */
    public IsoFieldParser getParser() {
        return parser;
    }
}
